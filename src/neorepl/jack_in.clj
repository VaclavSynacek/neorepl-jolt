(ns neorepl.jack-in
  "Starting an nREPL server for a project, with cider-nrepl, the way CIDER's
  jack-in does: the project's own build tool runs it, so it has the
  project's classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.discovery :as discovery]
            [neorepl.shutdown :as shutdown])
  (:import (java.io BufferedReader IOException)))

(def versions
  "What goes on the server's classpath: nREPL, newer than what the build tool
  might bring, and cider-nrepl."
  {:nrepl "1.8.0"
   :cider-nrepl "0.63.1"})

(def tools
  "The build tools jack-in knows, by the file a project of theirs has, and
  whether they take :aliases?, deps.edn aliases or Leiningen profiles. A
  directory with several files goes with the first: a deps.edn next to a
  project.clj is usually there for tooling, and a bb.edn next to either
  holds the project's tasks."
  [{:tool "lein" :file "project.clj" :aliases? true}
   {:tool "clojure" :file "deps.edn" :aliases? true}
   {:tool "bb" :file "bb.edn"}])

(defn tool
  "The entry in tools for the tool called name."
  [name]
  (some #(when (= name (:tool %)) %) tools))

(defn project-files
  "The files that make a project tool's, or any tool's without one, as text."
  [tool-name]
  (let [files (map :file (if tool-name [(tool tool-name)] tools))]
    (if (next files)
      (str (str/join ", " (butlast files)) " or " (last files))
      (first files))))

(defn find-project
  "The project dir is in: the nearest directory, dir or a parent, with a file
  of tool's, or of any tool's without one, as its :dir and the :tool to use.
  Nil when there's none."
  [dir tool-name]
  (first (for [d (discovery/dirs dir)
               {:keys [file] :as t} tools
               :when (and (or (nil? tool-name) (= tool-name (:tool t)))
                          (.isFile (io/file d file)))]
           {:dir (str d) :tool (:tool t)})))

(defn no-project
  "The error for there being no project for find-project to find."
  [dir tool-name]
  (ex-info (str "No " (project-files tool-name) " in " (.getCanonicalPath (io/file dir)) " or its parents")
           {:type ::no-project}))

(defn command
  "The command line that starts an nREPL server with cider-nrepl for tool,
  on port, or any port without one. aliases are the deps.edn aliases, or
  Leiningen profiles, to start it with, as names without the colon."
  [tool-name {:keys [aliases port]}]
  (let [{:keys [nrepl cider-nrepl]} versions]
    (case tool-name
      "clojure" (cond-> ["clojure"
                         ;; The same every time, so the CLI's classpath cache
                         ;; keeps working.
                         "-Sdeps" (binding [*print-namespace-maps* false]
                                    (pr-str {:deps {'nrepl/nrepl {:mvn/version nrepl}
                                                    'cider/cider-nrepl {:mvn/version cider-nrepl}}
                                             :aliases {:neorepl/jack-in
                                                       {:main-opts ["-m" "nrepl.cmdline"
                                                                    "--middleware" "[cider.nrepl/cider-middleware]"]}}}))
                         ;; The jack-in alias goes last, so its :main-opts win.
                         (str "-M" (str/join (map #(str ":" %) (concat aliases ["neorepl/jack-in"]))))]
                  port (conj "--port" (str port)))
      "lein" (concat ["lein"
                      "update-in" ":dependencies" "conj" (str "[nrepl/nrepl " (pr-str nrepl) "]") "--"
                      "update-in" ":plugins" "conj" (str "[cider/cider-nrepl " (pr-str cider-nrepl) "]") "--"]
                     (when (seq aliases)
                       ;; Added to the default profiles, unless they say otherwise.
                       ["with-profile" (str/join "," (map #(if (re-find #"^[+-]" %) % (str "+" %)) aliases))])
                     ["repl" ":headless"]
                     (when port [":port" (str port)]))
      ;; babashka's server has CIDER's essentials built in.
      "bb" ["bb" "nrepl-server" (str "127.0.0.1:" (or port 0))])))

(defn- address
  "Where the server that printed line says it's listening, if it does: nREPL
  and Leiningen say it their way, babashka its own."
  [line]
  (if-let [[_ port host] (re-find #"nREPL server started on port (\d+)(?: on host (\S+))?" line)]
    {:host (or host "127.0.0.1") :port (Long/parseLong port)}
    (when-let [[_ host port] (re-find #"Started nREPL server at (\S+):(\d+)" line)]
      {:host host :port (Long/parseLong port)})))

(def ^:private windows?
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- stop
  "Stops process, and the processes it started, the way they would on
  SIGTERM, giving them a few seconds to before killing them."
  [^Process process]
  ;; Build tools like Leiningen run the project in a process of its own.
  ;; Their handles go without type hints, since babashka doesn't know
  ;; ProcessHandle as a class.
  (let [all (cons (.toHandle process) (iterator-seq (.iterator (.descendants process))))
        deadline (+ (System/currentTimeMillis) 5000)]
    (run! #(.destroy %) all)
    (doseq [p all]
      (when (= ::timeout (deref (.onExit p) (max 0 (- deadline (System/currentTimeMillis))) ::timeout))
        (.destroyForcibly p)))))

(defn with-server
  "Starts the server command describes in dir and, once it says where it
  listens, calls (f server) with its :host, :port and :process, returning
  what f does. The server stops when f returns, or when neorepl exits
  first, even before the server's up. Each line it prints goes to
  (on-line line), on a thread of its own, the last of them before this
  returns. Throws ::failed when the server can't start, or ends before it's
  up.

  The server ignores SIGINT, which a terminal sends everything in the
  foreground on Ctrl-C, when that's meant for whatever runs it."
  [dir command on-line f]
  (let [process (try
                  (-> (ProcessBuilder. ^java.util.List (vec (if windows?
                                                               command
                                                               (concat ["sh" "-c" "trap '' INT; exec \"$@\"" "sh"]
                                                                       command))))
                      (.directory (io/file dir))
                      (.redirectErrorStream true)
                      .start)
                  (catch IOException e
                    (throw (ex-info (str "Couldn't start the server: " (ex-message e)) {:type ::failed}))))
        up (promise)
        output (doto (Thread. (fn []
                                (with-open [^BufferedReader r (io/reader (.getInputStream process))]
                                  ;; Not doseq over line-seq, which keeps every line
                                  ;; on babashka.
                                  (loop []
                                    (when-let [line (.readLine r)]
                                      (on-line line)
                                      (when-not (realized? up)
                                        (some->> (address line) (deliver up)))
                                      (recur))))
                                (.waitFor process)
                                (deliver up nil))
                              "neorepl-server-output")
                 (.setDaemon true))]
    ;; Nothing for it to read.
    (.close (.getOutputStream process))
    (shutdown/call-with-cleanup
     ;; What it says as it stops goes out too.
     #(do (stop process)
          (.join output 1000))
     (fn []
       (.start output)
       (if-let [address @up]
         (f (assoc address :process process))
         (throw (ex-info (str "The server ended before it started, with exit status " (.waitFor process))
                         {:type ::failed})))))))
