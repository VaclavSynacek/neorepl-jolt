(ns neorepl.main
  "The neorepl command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.discovery :as discovery]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.json :as json]
            [neorepl.lexer :as lexer]
            [neorepl.lookups :as lookups]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.sessions :as sessions]
            [neorepl.xdg :as xdg])
  (:import (java.io File IOException)))

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

(def ^:private help-option
  {:short "-h" :long "--help"
   :doc "Show this help"})

(def ^:private common-options
  [{:long "--format" :arg "FORMAT" :parse #{"text" "edn" "json"} :default "text"
    :doc "text (default), edn or json"}
   help-option])

(def ^:private port-file-names
  (str "(" (str/join ", " discovery/port-files) ")"))

(def ^:private discovery-note
  (str "Without --port or --url it connects to the nearest server with a port file
" port-file-names " in this directory or a parent."))

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

(defn- reject-args [args]
  (when (seq args)
    (throw (usage-error (str "Unexpected argument: " (first args))))))

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

(defn- print-rows
  "Prints items in format: as edn or json, or as text, with each item's
  (row item), a vector of strings, in columns."
  [format row items]
  (if (= "text" format)
    (run! println (cli/columns (map row items)))
    (emit format items)))

(defn- report
  "Prints result, a map with a :status and maybe a :message, in format. Text
  is only the message, on stderr."
  [format {:keys [message] :as result}]
  (if (= "text" format)
    (some-> message warn)
    (emit format result)))

(defn- no-session [name]
  {:status "no-session" :message (str "No session called " name)})

(defn- report-evaluation
  "Evaluates msg on the server options point at, in the session they name,
  prints the result in the format they ask for and returns the exit status."
  [msg {:keys [session timeout max-output format] :as options} {:keys [dir sessions-file]}]
  (with-client options dir
    (fn [client]
      (let [{:keys [status message truncated err] :as result}
            (evaluation/evaluate client
                      (assoc msg :session (sessions/ensure! sessions-file client session))
                      {:timeout timeout
                       :max-output max-output
                       :on-response (if (= "text" format) (evaluation/text-printer) (fn [_]))})]
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
  (reject-args args)
  (with-client options dir
    (fn [client]
      (let [{:keys [status] :as result}
            (case (sessions/interrupt sessions-file client session)
              :interrupted {:status "interrupted"}
              :idle {:status "idle" :message (str "Nothing is running in session " session)}
              :unknown-session (no-session session)
              {:status "error" :message "The server couldn't interrupt the evaluation"})]
        (report format result)
        (if (= "interrupted" status) 0 1)))))

(def ^:private sessions-options
  (concat connection-options common-options))

(def ^:private sessions-help
  (help-text
   "Usage: neorepl sessions [options]
       neorepl sessions [options] close NAME"
   "Lists the named sessions on the server, or closes one. Closing a session
throws its state away: the next evaluation under that name starts afresh in
the user namespace."
   sessions-options
   discovery-note
   "Exit status: 0 on success, 1 when there's no session to close, 2 on usage
errors and 3 when there's no server to talk to."
   "Examples:
  neorepl sessions
  neorepl sessions close agent"))

(defn- sessions-command [{:keys [format] :as options} args {:keys [dir sessions-file]}]
  (let [[action name & more] args]
    (cond
      (not= "close" action) (reject-args args)
      (nil? name) (throw (usage-error "Give the NAME of the session to close"))
      :else (reject-args more))
    (with-client options dir
      (fn [client]
        (if name
          (let [{:keys [status] :as result}
                (if (sessions/close! sessions-file client name)
                  {:status "closed"}
                  (no-session name))]
            (report format result)
            (if (= "closed" status) 0 1))
          (do (print-rows format (juxt :name :id) (for [[name id] (sessions/named sessions-file client)]
                                                     {:name name :id id}))
              0))))))

(def ^:private ports-options common-options)

(def ^:private ports-help
  (help-text
   "Usage: neorepl ports [options]"
   (str "Lists the port files " port-file-names " in this
directory and its parents, nearest first, and the servers answering on their
ports. The other commands connect to the first server that answers.")
   ports-options
   "Exit status: 0 when a server answers, 2 on usage errors and 3 when none
does."
   "Examples:
  neorepl ports
  neorepl ports --format json"))

(defn- ports-command [{:keys [format]} args {:keys [dir]}]
  (reject-args args)
  (let [ports (discovery/probe dir)]
    (print-rows format
                (fn [{:keys [file port live versions]}]
                  [file
                   (str port)
                   (if live
                     (str/join ", " (for [[k v] versions] (str (name k) " " v)))
                     "not answering")])
                ports)
    (if (some :live ports) 0 3)))

(def ^:private check-options common-options)

(def ^:private check-help
  (help-text
   "Usage: neorepl check [options] FILE...
       neorepl check [options] -"
   "Checks that the parens, brackets and braces in Clojure source balance and
that its strings end, and reports the first problem in each file. Standard
input stands in for a file as -. No server needed."
   check-options
   "Exit status: 0 when everything balances, 1 when something doesn't or a file
can't be read and 2 on usage errors."
   "Examples:
  neorepl check src/my/app.clj
  neorepl check --format json $(git ls-files '*.clj')"))

(defn- delimiter-problem
  "The first delimiter that doesn't balance in text, as a map with its :line,
  :column and a :message, or nil."
  [text]
  (when-let [{:keys [error open close expected]} (lexer/delimiter-error (lexer/lex text))]
    (let [at #(lexer/position text (:start %))
          [line column] (at (or close open))
          delimiter #(lexer/text text %)]
      {:line line
       :column column
       :message (case error
                  :unclosed (str "Unclosed " (delimiter open))
                  :unmatched (str "Unmatched " (delimiter close))
                  :mismatched (format "Mismatched %s: expected %s to close the %s at %s"
                                      (delimiter close) expected (delimiter open) (str/join ":" (at open)))
                  :open-string (if (str/starts-with? (delimiter open) "#")
                                 "Unterminated regex"
                                 "Unterminated string"))})))

(defn- check-file
  "The first delimiter problem in the file at path, or standard input for -,
  as delimiter-problem has it plus the :file. When the file can't be read,
  that's the problem, with only a :message."
  [path]
  (let [stdin? (= "-" path)
        text (if stdin? (slurp *in*) (try (slurp path) (catch IOException _ nil)))]
    (some->> (if text
               (delimiter-problem text)
               {:message (if (.exists (io/file path)) "Can't read the file" "No such file")})
             (into {:file (if stdin? "<stdin>" path)}))))

(defn- check-command [{:keys [format]} args _]
  (when (empty? args)
    (throw (usage-error "Nothing to check")))
  (let [problems (reduce (fn [problems {:keys [message] :as problem}]
                           (when (= "text" format)
                             (println (str (render/location-text problem) ": " message)))
                           (conj problems problem))
                         []
                         ;; Lexed in parallel, reported in order.
                         (remove nil? (pmap check-file args)))]
    (when-not (= "text" format)
      (emit format problems))
    (if (seq problems) 1 0)))

(def ^:private ns-option
  {:long "--ns" :arg "NS" :parse not-empty :default "user"
   :doc "Look symbols up in the namespace NS (default: user)"})

(def ^:private lookup-options
  (concat connection-options [ns-option] common-options))

(defn- the-arg
  "The one argument in args, named name in the usage."
  [args name]
  (when (empty? args)
    (throw (usage-error (str "Give a " name))))
  (reject-args (rest args))
  (first args))

(def ^:private doc-help
  (help-text
   "Usage: neorepl doc [options] SYM"
   "Prints the documentation of SYM, a var or special form, the way
clojure.repl/doc does. --format edn and json have its arglists, docstring
and where it's defined."
   lookup-options
   discovery-note
   "Exit status: 0 when SYM is found, 1 when it isn't, 2 on usage errors and 3
when there's no server to talk to."
   "Examples:
  neorepl doc map
  neorepl doc --ns my.app str/join"))

(defn- report-lookup
  "Does the lookup called name, see neorepl.lookups, and prints what it found
  in format. Returns the exit status: 0, or 1 when it found nothing."
  [format name client ctx arg]
  (let [{:keys [found text none]} (lookups/run name client ctx arg)]
    (if found
      (do (if (= "text" format)
            (println text)
            (emit format found))
          0)
      (do (report format {:status "not-found" :message none})
          1))))

(defn- lookup-command
  "The command doing the lookup called name on its one argument."
  [name]
  (let [arg-name (:arg (lookups/lookup name))]
    (fn [{:keys [ns format] :as options} args {:keys [dir]}]
      (let [arg (the-arg args arg-name)]
        (with-client options dir
          #(report-lookup format name % {:ns ns} arg))))))

(def ^:private source-help
  (help-text
   "Usage: neorepl source [options] SYM"
   "Prints the source of the var SYM, the way clojure.repl/source does."
   lookup-options
   discovery-note
   "Exit status: 0 when the source is found, 1 when it isn't, 2 on usage errors
and 3 when there's no server to talk to."
   "Examples:
  neorepl source when
  neorepl source --ns my.app my.app/start"))

(def ^:private complete-help
  (help-text
   "Usage: neorepl complete [options] PREFIX"
   "Lists the completions for PREFIX, with their types when the server knows
them."
   lookup-options
   discovery-note
   "Examples:
  neorepl complete str/
  neorepl complete --ns my.app --format json ma"))

(defn- complete-command [{:keys [ns format] :as options} args {:keys [dir]}]
  (let [prefix (the-arg args "PREFIX")]
    (with-client options dir
      (fn [client]
        (print-rows format (juxt :candidate (comp str :type)) (features/complete client prefix ns))
        0))))

(def ^:private apropos-options
  (concat connection-options common-options))

(def ^:private apropos-help
  (help-text
   "Usage: neorepl apropos [options] REGEX"
   "Lists the vars, in every loaded namespace, whose names match REGEX."
   apropos-options
   discovery-note
   "Exit status: 0 when something matches, 1 when nothing does, 2 on usage
errors and 3 when there's no server to talk to."
   "Examples:
  neorepl apropos split
  neorepl apropos --format json '^map'"))

(def ^:private macroexpand-options
  (concat connection-options
          [(assoc ns-option :doc "Expand in the namespace NS (default: user)")
           {:long "--all"
            :doc "Expand every macro in FORM, not just FORM itself"}]
          common-options))

(def ^:private macroexpand-help
  (help-text
   "Usage: neorepl macroexpand [options] FORM"
   "Expands the macro that FORM calls, once, the way macroexpand-1 does. With
--all, it expands every macro in FORM instead, all the way down."
   macroexpand-options
   discovery-note
   "Examples:
  neorepl macroexpand '(when x (println x))'
  neorepl macroexpand --all '(-> x f (g y))'"))

(defn- macroexpand-command [{:keys [ns all format] :as options} args {:keys [dir]}]
  (let [form (the-arg args "FORM")]
    (with-client options dir
      #(report-lookup format (if all "macroexpand-all" "macroexpand") % {:ns ns} form))))

(def ^:private where-help
  (help-text
   "Usage: neorepl where [options] SYM"
   "Prints where SYM is defined, as file:line:column, the way compilers and
editors write locations. A definition in a jar comes as a jar: URL."
   lookup-options
   discovery-note
   "Exit status: 0 when the server knows where SYM is, 1 when it doesn't, 2 on
usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl where map
  neorepl where --ns my.app --format json start"))

(def ^:private pst-options
  (concat connection-options [session-option] common-options))

(def ^:private pst-help
  (help-text
   "Usage: neorepl pst [options]"
   "Prints the stack trace of the last error in a session, the way
clojure.repl/pst does: the session neorepl eval calls share, or the one
--session names. --format edn and json have each cause's class, message,
ex-data and stack frames."
   pst-options
   discovery-note
   "Exit status: 0 when there's an error to print, 1 when there's none, 2 on
usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl pst
  neorepl pst --session agent --format json"))

(defn- pst-command [{:keys [session format] :as options} args {:keys [dir sessions-file]}]
  (reject-args args)
  (with-client options dir
    (fn [client]
      (if-let [id (get (sessions/named sessions-file client) session)]
        (report-lookup format "pst" client {:session id :session-name session} nil)
        (do (report format (no-session session))
            1)))))

(def ^:private repl-options
  (concat connection-options
          [(-> session-option
               (dissoc :default)
               (assoc :doc "Use the session called NAME instead of a fresh one"))
           help-option]))

(def ^:private repl-help
  (help-text
   "Usage: neorepl [repl] [options]"
   "Starts a REPL on a running server, in a fresh session of its own unless
--session names one, such as the one neorepl eval calls share. Enter
evaluates the input once its forms are complete, Ctrl-C interrupts an
evaluation and Ctrl-D exits. Commands start with a comma, like ,doc map,
and ,help lists them.

Without a terminal, as in a pipe or Emacs' M-x shell, it reads forms from
standard input and prints prompts and results the way clojure.main's REPL
does. Ctrl-C interrupts an evaluation there too, and ends the REPL when
nothing's running."
   repl-options
   discovery-note
   "Exit status: 0 when the REPL ends, 2 on usage errors and 3 when there's no
server to talk to or the connection to it drops."
   "Examples:
  neorepl
  neorepl -p 7888
  neorepl --session default"))

(defn- repl-command [{:keys [session] :as options} args {:keys [dir sessions-file] :as ctx}]
  (reject-args args)
  (with-client options dir
    (fn [client]
      (let [id (if session
                 (sessions/ensure! sessions-file client session)
                 (nrepl/clone-session client))
            close #(when-not session
                     ;; There's nothing to close when the connection is gone.
                     (try (nrepl/close-session client id) (catch Exception _ nil)))
            ;; A fresh session goes with the REPL, even when it gets killed.
            hook (Thread. ^Runnable close)
            runtime (Runtime/getRuntime)
            ;; Loaded only when needed, along with JLine.
            start (requiring-resolve 'neorepl.repl/start)]
        (.addShutdownHook runtime hook)
        (try
          (start client id (select-keys ctx [:dir :history-dir :terminal :editor-command]))
          0
          (finally
            (.removeShutdownHook runtime hook)
            (close)))))))

(def ^:private commands
  [{:name "repl" :summary "Start a REPL (the default)" :options repl-options :help repl-help
    :main repl-command}
   {:name "eval" :summary "Evaluate code" :options eval-options :help eval-help :main eval-command}
   {:name "load" :summary "Load a file" :options load-options :help load-help :main load-command}
   {:name "doc" :summary "Show a var's documentation" :options lookup-options :help doc-help
    :main (lookup-command "doc")}
   {:name "source" :summary "Show a var's source" :options lookup-options :help source-help
    :main (lookup-command "source")}
   {:name "complete" :summary "List completions" :options lookup-options :help complete-help
    :main complete-command}
   {:name "apropos" :summary "Find vars by name" :options apropos-options :help apropos-help
    :main (lookup-command "apropos")}
   {:name "macroexpand" :summary "Expand macros" :options macroexpand-options :help macroexpand-help
    :main macroexpand-command}
   {:name "where" :summary "Show where a var is defined" :options lookup-options :help where-help
    :main (lookup-command "where")}
   {:name "pst" :summary "Print the last error's stack trace" :options pst-options :help pst-help
    :main pst-command}
   {:name "interrupt" :summary "Interrupt an evaluation" :options interrupt-options :help interrupt-help
    :main interrupt-command}
   {:name "sessions" :summary "List or close sessions" :options sessions-options :help sessions-help
    :main sessions-command}
   {:name "ports" :summary "List the servers for this directory" :options ports-options :help ports-help
    :main ports-command}
   {:name "check" :summary "Check files for unbalanced delimiters" :options check-options :help check-help
    :main check-command}])

(def ^:private help
  (str "Usage: neorepl [COMMAND] [options]

A terminal client for nREPL servers. Without a COMMAND it starts a REPL.

Commands:
" (cli/indented-columns (map (juxt :name :summary) commands)) "

Run neorepl COMMAND --help for its options."))

(defn- run-command [{:keys [options help main]} args ctx]
  (let [{:keys [error] :as parsed} (cli/parse options args)]
    (cond
      error (throw (usage-error error))
      (:help (:options parsed)) (do (println help) 0)
      :else (main (:options parsed) (:args parsed) ctx))))

(defn run
  "Runs the neorepl command line args and returns the exit status. ctx has
  the :dir to look for port files in, the :sessions-file, the REPL's
  :history-dir, the :terminal options for its line editor, without which the
  REPL is the plain kind, and the :editor-command ,edit uses."
  [args ctx]
  (let [[command & more] args
        ;; Without a command, it's the REPL, options and all.
        [command args] (if (or (nil? command)
                               (and (str/starts-with? command "-") (not (#{"-h" "--help"} command))))
                         ["repl" args]
                         [command more])
        spec (some #(when (= command (:name %)) %) commands)]
    (try
      (cond
        (contains? #{"help" "-h" "--help"} command) (do (println help) 0)
        spec (run-command spec args ctx)
        :else (throw (usage-error (str "Unknown command: " command))))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          ::usage (do (warn (str (ex-message e) "\nRun neorepl " (when spec (str command " ")) "--help for usage."))
                      2)
          (::nrepl/no-server ::nrepl/disconnected ::nrepl/timeout) (do (warn (ex-message e)) 3)
          ::features/failed (do (warn (ex-message e)) 1)
          (throw e))))))

(defn -main [& args]
  (let [status (run args {:dir (System/getProperty "user.dir")
                          :sessions-file (io/file (xdg/cache-home) "neorepl" "sessions.edn")
                          :history-dir (io/file (xdg/state-home) "neorepl" "history")
                          ;; See https://no-color.org.
                          :terminal {:color? (str/blank? (System/getenv "NO_COLOR"))}
                          :editor-command (some #(not-empty (System/getenv %)) ["VISUAL" "EDITOR"])})]
    (flush)
    (System/exit status)))
