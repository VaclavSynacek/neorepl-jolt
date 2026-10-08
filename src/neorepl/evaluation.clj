(ns neorepl.evaluation
  "Evaluating code on the server: following the responses, answering reads
  from *in*, interrupting when needed and summing up what came back. Front
  ends decide how to show it."
  (:require [clojure.string :as str]
            [neorepl.nrepl :as nrepl])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private failure-statuses
  "The statuses that make an evaluation fail, the most telling first."
  ["disconnected" "interrupted" "eval-error" "error"])

(defn- add-response [acc {:keys [value out err ns ex root-ex status]}]
  (cond-> (update acc :statuses into status)
    value (update :values conj value)
    (seq out) (-> (update :out conj out) (update :out-size + (count out)))
    (seq err) (-> (update :err conj err) (update :err-size + (count err)))
    ns (assoc :ns ns)
    (and ex (not (:ex acc))) (assoc :ex ex :root-ex root-ex)))

(defn- cut
  "s cut down to n characters, short of splitting a surrogate pair."
  [^String s n]
  (cond
    (<= (count s) n) s
    (and (pos? n) (Character/isHighSurrogate (.charAt s (dec n)))) (subs s 0 (dec n))
    :else (subs s 0 n)))

(defn- fit
  "Cuts response's value at max-output characters and its out and err to what
  acc leaves of max-output for them."
  [{:keys [out-size err-size]} max-output response]
  (cond-> response
    (:value response) (update :value cut max-output)
    (:out response) (update :out cut (- max-output out-size))
    (:err response) (update :err cut (- max-output err-size))))

(defn- result
  "What an evaluation came to, given its accumulated responses and, when it
  ran out of time, the timeout and whether the interrupt worked."
  [{:keys [values out err ns ex root-ex statuses truncated?]} {:keys [timeout interrupted?] :as timed-out}]
  (let [status (if timed-out "timeout" (or (some statuses failure-statuses) "ok"))
        message (case status
                  "timeout" (str "Timed out after " timeout "s"
                                 (if interrupted?
                                   " and interrupted the evaluation"
                                   ", and interrupting the evaluation failed"))
                  "interrupted" "The evaluation was interrupted"
                  "disconnected" "Lost the connection to the nREPL server"
                  "error" (str "The server reported " (str/join ", " (sort (disj statuses "done" "error"))))
                  nil)]
    (cond-> {:status status :values values :out (str/join out) :err (str/join err) :ns ns}
      truncated? (assoc :truncated true)
      ex (assoc :ex ex :root-ex root-ex)
      message (assoc :message message))))

(defn- send-stdin
  "Answers need-input with line, or end of file when it's nil."
  [client session line]
  (nrepl/send! client {:op "stdin" :session session :stdin (if line (str line "\n") "")}
               (fn [_])))

(defn- interrupt!
  "Interrupts the evaluation with message id, giving the server a second to
  confirm. Returns whether it did, or found it done already."
  [client session id]
  (try
    (contains? #{:interrupted :idle} (nrepl/interrupt client session id {:timeout-ms 1000}))
    (catch Exception _ false)))

(defn evaluate
  "Sends msg, an eval or load-file, and returns its result: a map with the
  :status (ok, eval-error, interrupted, disconnected, error or timeout), the
  printed :values, the :out and :err output and the session's :ns after it.
  Failed evaluations add the :ex and :root-ex classes, and a :message unless
  :err already tells.

  Interrupts the evaluation when neorepl gets killed. Options:

    :on-response  called with each response as it arrives
    :read-line    called when the code reads *in*, for the next line or nil
                  at end of input (read-line by default)
    :timeout      seconds after which to interrupt the evaluation
    :max-output   cuts each value at that many characters, and stdout and
                  stderr too, marking the result :truncated"
  [client {:keys [session] :as msg} {:keys [on-response read-line timeout max-output]
                                     :or {on-response (fn [_]) read-line clojure.core/read-line}}]
  (let [deadline (when timeout (+ (System/currentTimeMillis) (long (* 1000 timeout))))
        responses (LinkedBlockingQueue.)
        ;; The server cuts values itself, sparing us an endless (range), but
        ;; not every server has the print middleware.
        id (nrepl/send! client (cond-> msg max-output (assoc :nrepl.middleware.print/quota max-output))
                        #(.put responses %))
        ;; Otherwise the evaluation keeps the session busy for later calls.
        hook (Thread. ^Runnable #(interrupt! client session id))
        runtime (Runtime/getRuntime)]
    (.addShutdownHook runtime hook)
    (try
      (loop [acc {:values [] :out [] :err [] :out-size 0 :err-size 0 :statuses #{}}]
        (if-let [response (if deadline
                            (.poll responses (- deadline (System/currentTimeMillis)) TimeUnit/MILLISECONDS)
                            (.take responses))]
          (let [fitted (cond->> response max-output (fit acc max-output))
                truncated? (and max-output
                                (or (not= fitted response)
                                    (nrepl/status? response "nrepl.middleware.print/truncated")))
                acc (cond-> (add-response acc fitted) truncated? (assoc :truncated? true))]
            (on-response fitted)
            (when (nrepl/status? response "need-input")
              (send-stdin client session (read-line)))
            (if (nrepl/done? response)
              (result acc nil)
              (recur acc)))
          (result acc {:timeout timeout :interrupted? (interrupt! client session id)})))
      (finally
        (.removeShutdownHook runtime hook)))))

(defn text-printer
  "A response handler printing output to stdout and stderr, and each value on
  a line of its own."
  []
  (let [fresh-line? (volatile! true)]
    (fn [{:keys [out err value]}]
      (when (seq out)
        (print out)
        (flush)
        (vreset! fresh-line? (str/ends-with? out "\n")))
      (when err
        (binding [*out* *err*]
          (print err)
          (flush)))
      (when value
        (when-not @fresh-line? (newline))
        (println value)
        (vreset! fresh-line? true)))))
