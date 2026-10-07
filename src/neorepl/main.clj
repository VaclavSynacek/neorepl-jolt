(ns neorepl.main
  "The neorepl command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.discovery :as discovery]
            [neorepl.json :as json]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- usage-error
  "An error in how neorepl was called; command is the one whose help to point at."
  [command msg]
  (ex-info msg {:type ::usage :command command}))

(defn- warn [msg]
  (binding [*out* *err*]
    (println (str "neorepl: " msg))))

(defn- parse-port [s]
  (let [port (Long/parseLong s)]
    (when (< 0 port 65536) port)))

(defn- parse-seconds [s]
  ;; Nine digits are plenty, and the milliseconds can't overflow.
  (when (re-matches #"\d{1,9}(\.\d+)?" s)
    (let [n (if (str/includes? s ".") (Double/parseDouble s) (Long/parseLong s))]
      (when (pos? n) n))))

(def ^:private eval-options
  [{:short "-p" :long "--port" :arg "PORT" :parse parse-port
    :doc "Connect to the server on PORT"}
   {:long "--host" :arg "HOST" :parse not-empty
    :doc "Host for --port (default: 127.0.0.1)"}
   {:long "--url" :arg "URL" :parse nrepl/parse-url
    :doc "Connect to nrepl://HOST:PORT or nrepl+unix:PATH"}
   {:short "-f" :long "--file" :arg "FILE" :parse not-empty
    :doc "Evaluate the code in FILE"}
   {:short "-s" :long "--session" :arg "NAME" :parse not-empty
    :doc "Evaluate in the session called NAME (default: default)"}
   {:long "--timeout" :arg "SECS" :parse parse-seconds
    :doc "Interrupt the evaluation after SECS seconds"}
   {:long "--format" :arg "FORMAT" :parse #{"text" "edn" "json"}
    :doc "text (default), edn or json"}
   {:short "-h" :long "--help"
    :doc "Show this help"}])

(def ^:private help
  "Usage: neorepl COMMAND [options]

A terminal client for nREPL servers.

Commands:
  eval    Evaluate code on a running server

Run neorepl COMMAND --help for its options.")

(def ^:private eval-help
  (str "Usage: neorepl eval [options] CODE
       neorepl eval [options] -f FILE
       neorepl eval [options] -

Evaluates CODE, the contents of FILE or, with -, standard input. Output is
printed as it arrives and each value on a line of its own.

Without --port or --url it connects to the nearest server with a port file
(.nrepl-port, .shadow-cljs/nrepl.port) in this directory or a parent.

Evaluations share a session, per server and --session name, so *ns*, *1, *e
and the like carry over from one call to the next.

Options:
" (cli/summary eval-options) "

Exit status: 0 on success, 1 when the evaluation fails, 2 on usage errors,
3 when there's no server to talk to and 124 when it times out.

Examples:
  neorepl eval '(+ 1 2)'
  neorepl eval --format json -f script.clj
  echo '(range 3)' | neorepl eval -"))

(defn- read-code [file args]
  (cond
    (and file (seq args)) (throw (usage-error "eval" "Give either CODE or --file, not both"))
    file (if (.isFile (io/file file))
           (slurp file)
           (throw (usage-error "eval" (str "No such file: " file))))
    (= ["-"] args) (slurp *in*)
    (= 1 (count args)) (first args)
    (empty? args) (throw (usage-error "eval" "Nothing to evaluate"))
    :else (throw (usage-error "eval" "Too many arguments: quote the code to pass it as one"))))

(defn- connect [{:keys [url host port]} dir]
  (cond
    (and url (or host port)) (throw (usage-error "eval" "--url doesn't go with --host or --port"))
    url (nrepl/connect url)
    port (nrepl/connect (cond-> {:port port} host (assoc :host host)))
    host (throw (usage-error "eval" "--host needs a --port"))
    :else (discovery/connect dir)))

(def ^:private failure-statuses
  "The statuses that make an evaluation fail, the most telling first."
  ["disconnected" "interrupted" "eval-error" "error"])

(defn- add-response [acc {:keys [value out err ns ex root-ex status]}]
  (cond-> (update acc :statuses into status)
    value (update :values conj value)
    out (update :out conj out)
    err (update :err conj err)
    ns (assoc :ns ns)
    (and ex (not (:ex acc))) (assoc :ex ex :root-ex root-ex)))

(defn- result
  "What an evaluation came to, given its accumulated responses and, when it
  ran out of time, the timeout and whether the interrupt worked."
  [{:keys [values out err ns ex root-ex statuses]} {:keys [timeout interrupted?] :as timed-out}]
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
      ex (assoc :ex ex :root-ex root-ex)
      message (assoc :message message))))

(defn- send-stdin
  "Answers need-input with a line from *in*, or end of file once it's used up."
  [client session]
  (let [line (read-line)]
    (nrepl/send! client {:op "stdin" :session session :stdin (if line (str line "\n") "")}
                 (fn [_]))))

(defn- interrupt!
  "Interrupts the evaluation with message id, giving the server a second to
  confirm. Returns whether it did."
  [client session id]
  (try
    (not-any? #(nrepl/status? % "error") (nrepl/interrupt client session id {:timeout-ms 1000}))
    (catch Exception _ false)))

(defn- evaluate
  "Sends msg, an eval, and returns its result. Calls on-response with each
  response as it arrives. Interrupts the evaluation when it runs longer than
  timeout seconds, or when neorepl gets killed."
  [client {:keys [session] :as msg} {:keys [timeout on-response]}]
  (let [deadline (when timeout (+ (System/currentTimeMillis) (long (* 1000 timeout))))
        responses (LinkedBlockingQueue.)
        id (nrepl/send! client msg #(.put responses %))
        ;; Otherwise the evaluation keeps the session busy for later calls.
        hook (Thread. ^Runnable #(interrupt! client session id))
        runtime (Runtime/getRuntime)]
    (.addShutdownHook runtime hook)
    (try
      (loop [acc {:values [] :out [] :err [] :statuses #{}}]
        (if-let [response (if deadline
                            (.poll responses (- deadline (System/currentTimeMillis)) TimeUnit/MILLISECONDS)
                            (.take responses))]
          (let [acc (add-response acc response)]
            (on-response response)
            (when (nrepl/status? response "need-input")
              (send-stdin client session))
            (if (nrepl/done? response)
              (result acc nil)
              (recur acc)))
          (result acc {:timeout timeout :interrupted? (interrupt! client session id)})))
      (finally
        (.removeShutdownHook runtime hook)))))

(defn- text-printer
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

(defn- eval-command [args {:keys [dir sessions-file]}]
  (let [{:keys [options args error]} (cli/parse eval-options args)
        {:keys [file session timeout format] :or {session "default" format "text"}} options]
    (cond
      error (throw (usage-error "eval" error))
      (:help options) (do (println eval-help) 0)
      :else
      (let [code (read-code file args)
            client (connect options dir)]
        (try
          (let [msg (cond-> {:op "eval"
                             :code code
                             :session (sessions/ensure! sessions-file client session)}
                      ;; For the file and line numbers in errors.
                      file (assoc :file (.getAbsolutePath (io/file file))))
                {:keys [status message] :as result}
                (evaluate client msg {:timeout timeout
                                      :on-response (if (= "text" format) (text-printer) (fn [_]))})]
            (case format
              "text" (some-> message warn)
              "edn" (prn result)
              "json" (println (json/write-str result)))
            (case status "ok" 0 "timeout" 124 1))
          (finally
            (nrepl/close client)))))))

(defn run
  "Runs the neorepl command line args and returns the exit status. ctx has
  the :dir to look for port files in and the :sessions-file."
  [args ctx]
  (try
    (let [[command & args] args]
      (case command
        (nil "help" "-h" "--help") (do (println help) 0)
        "eval" (eval-command args ctx)
        (throw (usage-error nil (str "Unknown command: " command)))))
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type command]} (ex-data e)]
        (case type
          ::usage (do (warn (str (ex-message e) "\nRun neorepl " (some-> command (str " ")) "--help for usage."))
                      2)
          (::nrepl/no-server ::nrepl/disconnected ::nrepl/timeout) (do (warn (ex-message e)) 3)
          (throw e))))))

(defn -main [& args]
  (let [status (run args {:dir (System/getProperty "user.dir")
                          :sessions-file (sessions/default-file)})]
    (flush)
    (System/exit status)))
