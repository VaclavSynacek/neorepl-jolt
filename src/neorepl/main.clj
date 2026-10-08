(ns neorepl.main
  "The neorepl command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.discovery :as discovery]
            [neorepl.json :as json]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions])
  (:import (java.io File)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- usage-error [msg]
  (ex-info msg {:type ::usage}))

(defn- warn [msg]
  (binding [*out* *err*]
    (println (str "neorepl: " msg))))

(defn- parse-port [s]
  (let [port (Long/parseLong s)]
    (when (< 0 port 65536) port)))

(defn- parse-count [s]
  (when (re-matches #"\d{1,9}" s)
    (let [n (Long/parseLong s)]
      (when (pos? n) n))))

(defn- parse-seconds [s]
  ;; Nine digits are plenty, and the milliseconds can't overflow.
  (when (re-matches #"\d{1,9}(\.\d+)?" s)
    (let [n (if (str/includes? s ".") (Double/parseDouble s) (Long/parseLong s))]
      (when (pos? n) n))))

(def ^:private connection-options
  [{:short "-p" :long "--port" :arg "PORT" :parse parse-port
    :doc "Connect to the server on PORT"}
   {:long "--host" :arg "HOST" :parse not-empty
    :doc "Host for --port (default: 127.0.0.1)"}
   {:long "--url" :arg "URL" :parse nrepl/parse-url
    :doc "Connect to nrepl://HOST:PORT or nrepl+unix:PATH"}])

(def ^:private session-option
  {:short "-s" :long "--session" :arg "NAME" :parse not-empty :default "default"
   :doc "Use the session called NAME (default: default)"})

(def ^:private evaluation-options
  [session-option
   {:long "--timeout" :arg "SECS" :parse parse-seconds
    :doc "Interrupt the evaluation after SECS seconds"}
   {:long "--max-output" :arg "CHARS" :parse parse-count
    :doc "Cut each value, stdout and stderr at CHARS characters"}])

(def ^:private common-options
  [{:long "--format" :arg "FORMAT" :parse #{"text" "edn" "json"} :default "text"
    :doc "text (default), edn or json"}
   {:short "-h" :long "--help"
    :doc "Show this help"}])

(def ^:private discovery-note
  "Without --port or --url it connects to the nearest server with a port file
(.nrepl-port, .shadow-cljs/nrepl.port) in this directory or a parent.")

(def ^:private sessions-note
  "Evaluations share a session, per server and --session name, so *ns*, *1, *e
and the like carry over from one call to the next.")

(def ^:private evaluation-exit-statuses
  "Exit status: 0 on success, 1 when the evaluation fails, 2 on usage errors,
3 when there's no server to talk to and 124 when it times out.")

(defn- help-text
  "A --help text: the usage and description paragraphs, then the options and
  any paragraphs after them."
  [usage description options & more]
  (str/join "\n\n" (concat [usage description (str "Options:\n" (cli/summary options))] more)))

(defn- existing-file ^File [path]
  (let [file (io/file path)]
    (if (.isFile file)
      file
      (throw (usage-error (str "No such file: " path))))))

(defn- connect [{:keys [url host port]} dir]
  (cond
    (and url (or host port)) (throw (usage-error "--url doesn't go with --host or --port"))
    url (nrepl/connect url)
    port (nrepl/connect (cond-> {:port port} host (assoc :host host)))
    host (throw (usage-error "--host needs a --port"))
    :else (discovery/connect dir)))

(defn- with-client
  "Calls f with a client connected the way options say, and closes it after."
  [options dir f]
  (let [client (connect options dir)]
    (try
      (f client)
      (finally
        (nrepl/close client)))))

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
  "Answers need-input with a line from *in*, or end of file once it's used up."
  [client session]
  (let [line (read-line)]
    (nrepl/send! client {:op "stdin" :session session :stdin (if line (str line "\n") "")}
                 (fn [_]))))

(defn- interrupt!
  "Interrupts the evaluation with message id, giving the server a second to
  confirm. Returns whether it did, or found it done already."
  [client session id]
  (try
    (contains? #{:interrupted :idle} (nrepl/interrupt client session id {:timeout-ms 1000}))
    (catch Exception _ false)))

(defn- evaluate
  "Sends msg, an eval, and returns its result. Calls on-response with each
  response as it arrives. Interrupts the evaluation when it runs longer than
  timeout seconds, or when neorepl gets killed. With max-output, cuts each
  value at that many characters, and stdout and stderr too."
  [client {:keys [session] :as msg} {:keys [timeout max-output on-response]}]
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

(defn- end-err-line
  "Ends the line err, the evaluation's stderr output, left unfinished, so
  that warnings after it get a line of their own."
  [err]
  (when-not (or (empty? err) (str/ends-with? err "\n"))
    (binding [*out* *err*]
      (newline))))

(defn- emit
  "Prints data in format, edn or json."
  [format data]
  (case format
    "edn" (prn data)
    "json" (println (json/write-str data))))

(defn- report
  "Prints result, a map with a :status and maybe a :message, in format. Text
  is only the message, on stderr."
  [format {:keys [message] :as result}]
  (if (= "text" format)
    (some-> message warn)
    (emit format result)))

(defn- report-evaluation
  "Evaluates msg on the server options point at, in the session they name,
  prints the result in the format they ask for and returns the exit status."
  [msg {:keys [session timeout max-output format] :as options} {:keys [dir sessions-file]}]
  (with-client options dir
    (fn [client]
      (let [{:keys [status message truncated err] :as result}
            (evaluate client
                      (assoc msg :session (sessions/ensure! sessions-file client session))
                      {:timeout timeout
                       :max-output max-output
                       :on-response (if (= "text" format) (text-printer) (fn [_]))})]
        (if (= "text" format)
          (do (when (or truncated message) (end-err-line err))
              (when truncated (warn (str "Cut the output at " max-output " characters")))
              (some-> message warn))
          (emit format result))
        (case status "ok" 0 "timeout" 124 1)))))

(def ^:private eval-options
  (concat connection-options
          [{:short "-f" :long "--file" :arg "FILE" :parse not-empty
            :doc "Evaluate the code in FILE"}]
          evaluation-options
          common-options))

(def ^:private eval-help
  (help-text
   "Usage: neorepl eval [options] CODE
       neorepl eval [options] -f FILE
       neorepl eval [options] -"
   "Evaluates CODE, the contents of FILE or, with -, standard input. Output is
printed as it arrives and each value on a line of its own."
   eval-options
   discovery-note
   sessions-note
   evaluation-exit-statuses
   "Examples:
  neorepl eval '(+ 1 2)'
  neorepl eval --format json -f script.clj
  neorepl eval --max-output 2000 '(slurp \"big.txt\")'
  echo '(range 3)' | neorepl eval -"))

(defn- read-code [file args]
  (cond
    (and file (seq args)) (throw (usage-error "Give either CODE or --file, not both"))
    file (slurp (existing-file file))
    (= ["-"] args) (slurp *in*)
    (= 1 (count args)) (first args)
    (empty? args) (throw (usage-error "Nothing to evaluate"))
    :else (throw (usage-error "Too many arguments: quote the code to pass it as one"))))

(defn- eval-command [{:keys [file] :as options} args ctx]
  (report-evaluation (cond-> {:op "eval" :code (read-code file args)}
                       ;; For the file and line numbers in errors.
                       file (assoc :file (.getAbsolutePath (io/file file))))
                     options
                     ctx))

(def ^:private load-options
  (concat connection-options evaluation-options common-options))

(def ^:private load-help
  (help-text
   "Usage: neorepl load [options] FILE"
   "Loads FILE the way load-file does: loading stops at the first error, only
the value of the last form comes back and the session stays in its
namespace. eval -f, on the other hand, evaluates the forms one by one, and
an ns form switches the session to that namespace."
   load-options
   discovery-note
   sessions-note
   evaluation-exit-statuses
   "Examples:
  neorepl load src/my/app.clj
  neorepl load --timeout 60 --format json src/my/app.clj"))

(defn- load-command [options args ctx]
  (let [file (case (count args)
               0 (throw (usage-error "Nothing to load"))
               1 (existing-file (first args))
               (throw (usage-error "Load one file at a time")))]
    (report-evaluation {:op "load-file"
                        :file (slurp file)
                        :file-path (.getAbsolutePath file)
                        :file-name (.getName file)}
                       options
                       ctx)))

(def ^:private interrupt-options
  (concat connection-options [session-option] common-options))

(def ^:private interrupt-help
  (help-text
   "Usage: neorepl interrupt [options]"
   "Interrupts the evaluation running in a session, freeing it up for the next
one. It's meant for evaluations nobody is waiting for anymore, like one a
neorepl eval left behind when it got killed. A neorepl eval that's still
waiting won't find out that its evaluation ended, so stop that one with
Ctrl-C instead."
   interrupt-options
   discovery-note
   "Exit status: 0 when it interrupted an evaluation, 1 when there was none to
interrupt, 2 on usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl interrupt
  neorepl interrupt -s agent"))

(defn- interrupt-command [{:keys [session format] :as options} args {:keys [dir sessions-file]}]
  (when (seq args)
    (throw (usage-error (str "Unexpected argument: " (first args)))))
  (with-client options dir
    (fn [client]
      (let [{:keys [status] :as result}
            (case (sessions/interrupt sessions-file client session)
              :interrupted {:status "interrupted"}
              :idle {:status "idle" :message (str "Nothing is running in session " session)}
              :unknown-session {:status "no-session" :message (str "No session called " session)}
              {:status "error" :message "The server couldn't interrupt the evaluation"})]
        (report format result)
        (if (= "interrupted" status) 0 1)))))

(def ^:private commands
  [{:name "eval" :summary "Evaluate code" :options eval-options :help eval-help :main eval-command}
   {:name "load" :summary "Load a file" :options load-options :help load-help :main load-command}
   {:name "interrupt" :summary "Interrupt an evaluation" :options interrupt-options :help interrupt-help
    :main interrupt-command}])

(def ^:private help
  (let [width (apply max (map (comp count :name) commands))]
    (str "Usage: neorepl COMMAND [options]

A terminal client for nREPL servers.

Commands:
" (str/join "\n" (for [{:keys [name summary]} commands]
                   (format (str "  %-" width "s  %s") name summary))) "

Run neorepl COMMAND --help for its options.")))

(defn- run-command [{:keys [options help main]} args ctx]
  (let [{:keys [error] :as parsed} (cli/parse options args)]
    (cond
      error (throw (usage-error error))
      (:help (:options parsed)) (do (println help) 0)
      :else (main (:options parsed) (:args parsed) ctx))))

(defn run
  "Runs the neorepl command line args and returns the exit status. ctx has
  the :dir to look for port files in and the :sessions-file."
  [args ctx]
  (let [[command & args] args
        spec (some #(when (= command (:name %)) %) commands)]
    (try
      (cond
        (contains? #{nil "help" "-h" "--help"} command) (do (println help) 0)
        spec (run-command spec args ctx)
        :else (throw (usage-error (str "Unknown command: " command))))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          ::usage (do (warn (str (ex-message e) "\nRun neorepl " (when spec (str command " ")) "--help for usage."))
                      2)
          (::nrepl/no-server ::nrepl/disconnected ::nrepl/timeout) (do (warn (ex-message e)) 3)
          (throw e))))))

(defn -main [& args]
  (let [status (run args {:dir (System/getProperty "user.dir")
                          :sessions-file (sessions/default-file)})]
    (flush)
    (System/exit status)))
