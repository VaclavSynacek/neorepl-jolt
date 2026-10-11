;; Jolt-only integration suite: jolt -A:jolt test/jolt-client-check.clj JVM_PORT
(require '[clojure.test :refer [deftest is run-tests]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[neorepl.jolt :as jolt]
         '[neorepl.main :as main]
         '[neorepl.nrepl :as client]
         '[neorepl.sessions :as sessions]
         '[nrepl.core :as upstream])

(def port (Long/parseLong (first *command-line-args*)))
(def ctx (jolt/context))
(def original-connect upstream/connect)
(def session-name (str "jolt-check-" (System/currentTimeMillis)))
(defn cli [& args]
  (let [status (atom nil)
        text (with-out-str (reset! status (main/run (vec args) ctx)))]
    {:status @status :text text}))
(defn evaluate [code & options]
  (let [{:keys [status text]} (apply cli "eval" "--port" (str port) "--session" session-name
                                    "--format" "edn" (concat options [code]))]
    (is (zero? status) text)
    (edn/read-string text)))
(defn live [c] (set (some :sessions (client/request c {:op "ls-sessions"}))))

(deftest session-and-transport-contract
  (let [c (client/connect (merge ctx {:port port})) before (live c)]
    (try
      (evaluate "(clojure.core/in-ns 'jolt.check.scratch)")
      (is (str/includes? (first (:values (evaluate "clojure.core/*ns*"))) "jolt.check.scratch"))
      (evaluate "(clojure.core/+ 40 2)")
      (is (= ["42"] (:values (evaluate "clojure.core/*1"))))
      (is (= 1 (- (count (live c)) (count before))))
      (is (= ["[\"😀\" :žluťoučký]"] (:values (evaluate "[\"😀\" :žluťoučký]"))))
      ;; Qualified middleware keys must survive encoding: a print quota must
      ;; stop an infinite lazy sequence on the server, not just cut it locally.
      (let [r (evaluate "(clojure.core/range)" "--max-output" "40" "--timeout" "2")]
        (is (= "ok" (:status r)))
        (is (:truncated r)))
      (is (= :idle (sessions/interrupt (:sessions-file ctx) c session-name)))
      (is (contains? (sessions/named (:sessions-file ctx) c) session-name))
      (let [{:keys [status text]} (cli "repl" "--port" (str port))]
        ;; The harness sends EOF on stdin: the interactive session is ephemeral.
        (is (zero? status) text)
        (is (= 1 (- (count (live c)) (count before)))))
      (is (identical? original-connect upstream/connect))
      (finally
        (sessions/close! (:sessions-file ctx) c session-name)
        (is (= before (live c)))
        (client/close c)))))

(deftest unwritable-cache-does-not-leak
  (let [c (client/connect (merge ctx {:port port}))
        file (io/file (.getParentFile (:sessions-file ctx)) (str session-name "-directory"))]
    (.mkdirs file)
    (try
      (let [before (live c)]
        (is (thrown? clojure.lang.ExceptionInfo (sessions/ensure! file c "cannot-save")))
        (is (= before (live c))))
      (finally
        (.delete file)
        (.delete (io/file (str file ".lock")))
        (client/close c)))))

(deftest lock-releases-after-errors
  (let [file (:sessions-file ctx)]
    (is (thrown? clojure.lang.ExceptionInfo
                 ((:cache-lock ctx) file #(throw (ex-info "expected" {})))))
    (is (= :released ((:cache-lock ctx) file (constantly :released))))))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
