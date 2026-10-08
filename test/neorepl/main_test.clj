(ns neorepl.main-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.discovery :as discovery]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file with-client]])
  (:import (java.io StringWriter)
           (java.util.concurrent TimeUnit)))

(def ^:dynamic *dir*
  "A directory without port files, holding the session cache as if it was
  XDG_CACHE_HOME."
  nil)

(defn- sessions-file []
  (sessions/cache-file (str *dir*) nil))

(use-fixtures :once
  (test-server/fixture)
  (fn [f] (call-with-temp-dir #(binding [*dir* %] (f)))))

(defn- run-in
  "Runs neorepl with args in dir, with stdin as standard input. Returns the
  exit :status and what went to :out and :err."
  [dir stdin & args]
  (let [err (StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (with-in-str stdin
                  (reset! status (main/run args {:dir dir :sessions-file (sessions-file)})))))]
    {:status @status :out out :err (str err)}))

(defn- run-command
  "Runs a neorepl command against the test server with args."
  [command & args]
  (apply run-in *dir* "" command "-p" (str *port*) args))

(defn- run
  "Runs neorepl eval against the test server with args."
  [& args]
  (apply run-command "eval" args))

(deftest text-format-test
  (testing "values on lines of their own, after any output"
    (is (= {:status 0 :out "a\nnil\n3\nb\nnil\n" :err ""}
           (run "(print \"a\") (+ 1 2) (println \"b\")"))))
  (testing "stderr output"
    (is (= {:status 0 :out "nil\n" :err "oops\n"}
           (run "(binding [*out* *err*] (println \"oops\"))"))))
  (testing "evaluation errors"
    (let [{:keys [status out err]} (run "(/ 1 0) :after")]
      (is (= 1 status))
      (is (= ":after\n" out))
      (is (str/includes? err "Divide by zero"))))
  (testing "warnings start on a line of their own"
    (is (= {:status 124 :out "nil\n" :err "oops\nneorepl: Timed out after 0.1s and interrupted the evaluation\n"}
           (run "-s" "warn-line" "--timeout" "0.1" "(binding [*out* *err*] (print \"oops\") (flush)) (Thread/sleep 5000)")))))

(deftest structured-format-test
  (testing "edn"
    (let [{:keys [status out]} (run "--format" "edn" "(println \"hi\") 42")]
      (is (zero? status))
      (is (= {:status "ok" :values ["nil" "42"] :out "hi\n" :err "" :ns "user"}
             (edn/read-string out)))))
  (testing "edn for an error"
    (let [{:keys [status out]} (run "--format=edn" "(/ 1 0)")
          result (edn/read-string out)]
      (is (= 1 status))
      (is (= "eval-error" (:status result)))
      (is (= "class java.lang.ArithmeticException" (:ex result)))))
  (testing "json"
    (let [{:keys [status out]} (run "--format" "json" "(+ 1 2) \"s\"")]
      (is (zero? status))
      (is (str/starts-with? out "{\"status\":\"ok\",\"values\":[\"3\",\"\\\"s\\\"\"],"))
      (is (str/ends-with? out "}\n")))))

(deftest session-test
  (testing "a session keeps its namespace between calls"
    (run "-s" "ns-test" "(in-ns 'neorepl.scratch)")
    (is (= "neorepl.scratch" (:ns (edn/read-string (:out (run "-s" "ns-test" "--format" "edn" "1"))))))
    (testing "and other sessions don't see it"
      (is (= "user" (:ns (edn/read-string (:out (run "-s" "other" "--format" "edn" "1"))))))))
  (testing "*1 carries over"
    (run "-s" "star-test" "(+ 40 2)")
    (is (= "42\n" (:out (run "-s" "star-test" "*1")))))
  (testing "a session the server lost gets replaced"
    (run "-s" "lost" "1")
    (with-client [client {:port *port*}]
      (let [lost (sessions/ensure! (sessions-file) client "lost")]
        (nrepl/close-session client lost)
        (is (= {:status 0 :out "2\n" :err ""} (run "-s" "lost" "2")))
        (is (not= lost (sessions/ensure! (sessions-file) client "lost")))))))

(deftest sessions-command-test
  (run "-s" "listed" "(in-ns 'neorepl.listed)")
  (run "-s" "listed-too" "1")
  (let [ids (with-client [client {:port *port*}]
              (sessions/named (sessions-file) client))
        listed #(set (edn/read-string (:out (run-command "sessions" "--format" "edn"))))]
    (testing "listing"
      (is (set/subset? #{{:name "listed" :id (ids "listed")} {:name "listed-too" :id (ids "listed-too")}}
                       (listed)))
      (let [{:keys [status out]} (run-command "sessions")]
        (is (zero? status))
        (is (re-find (re-pattern (str "(?m)^listed +" (ids "listed") "$")) out))))
    (testing "closing"
      (is (= {:status 0 :out "" :err ""} (run-command "sessions" "close" "listed")))
      (is (not-any? #(= "listed" (:name %)) (listed)))
      (testing "starts the name over"
        (is (= "user" (:ns (edn/read-string (:out (run "-s" "listed" "--format" "edn" "1"))))))))
    (testing "closing what isn't there"
      (is (= {:status 1 :out "" :err "neorepl: No session called nope\n"}
             (run-command "sessions" "close" "nope"))))))

(deftest timeout-test
  (testing "an evaluation running too long gets interrupted"
    (let [{:keys [status err]} (run "-s" "slow" "--timeout" "0.2" "(Thread/sleep 10000)")]
      (is (= 124 status))
      (is (str/includes? err "Timed out after 0.2s"))))
  (testing "and the session is free again"
    (is (= {:status 0 :out "1\n" :err ""}
           (run "-s" "slow" "--timeout" "5" "1"))))
  (testing "timeouts show up in structured output"
    (let [{:keys [status out]} (run "-s" "slow" "--timeout" "0.2" "--format" "edn" "(Thread/sleep 10000)")]
      (is (= 124 status))
      (is (= "timeout" (:status (edn/read-string out)))))))

(deftest max-output-test
  (testing "values get cut"
    (is (= {:status 0 :out "(0 1 \n:x\n" :err "neorepl: Cut the output at 5 characters\n"}
           (run "--max-output" "5" "(range 10) :x"))))
  (testing "stdout and stderr get cut once they've had their share"
    (is (= {:status 0 :out "abcde\n:x\n" :err "12345\nneorepl: Cut the output at 5 characters\n"}
           (run "--max-output" "5" "(do (print \"abc\") (flush) (print \"defgh\") (binding [*out* *err*] (print 1234567)) :x)"))))
  (testing "surrogate pairs stay whole"
    (is (= "a\n:x\n" (:out (run "--max-output" "2" "(do (print \"a\uD83D\uDE00b\") :x)")))))
  (testing "the server stops printing at the limit, so endless values are fine"
    (is (= {:status 0 :out "(0 1 2 3 4\n" :err "neorepl: Cut the output at 10 characters\n"}
           (run "--max-output" "10" "--timeout" "10" "(range)"))))
  (testing "structured output says when something got cut"
    (let [cut (edn/read-string (:out (run "--max-output" "3" "--format" "edn" "(println 12345) :abcd")))]
      (is (= {:status "ok" :values ["nil" ":ab"] :out "123" :err "" :ns "user" :truncated true} cut)))
    (is (not (contains? (edn/read-string (:out (run "--max-output" "3" "--format" "edn" "1")))
                        :truncated)))
    (testing "but not about cuts the session asked for itself"
      (run "-s" "quota" "(set! nrepl.middleware.print/*quota* 3)")
      (is (= {:status 0 :out "(0 \n" :err ""} (run "-s" "quota" "(range 10)"))))))

(def started
  "Delivered by code that neorepl evaluates on the test server, to say it's
  running."
  (atom nil))

(deftest interrupt-test
  (testing "interrupting an evaluation, here one whose client went away"
    (reset! started (promise))
    (with-client [client {:port *port*}]
      (nrepl/send! client {:op "eval"
                           :session (sessions/ensure! (sessions-file) client "stuck")
                           :code "(deliver @neorepl.main-test/started true) (Thread/sleep 20000)"}
                   (fn [_]))
      (is (deref @started 10000 false)))
    (is (= {:status 0 :out "" :err ""} (run-command "interrupt" "-s" "stuck")))
    (testing "frees the session"
      (is (= {:status 0 :out "1\n" :err ""} (run "-s" "stuck" "--timeout" "5" "1")))))
  (testing "nothing to interrupt"
    (is (= {:status 1 :out "" :err "neorepl: Nothing is running in session stuck\n"}
           (run-command "interrupt" "-s" "stuck")))
    (is (= {:status "idle" :message "Nothing is running in session stuck"}
           (edn/read-string (:out (run-command "interrupt" "-s" "stuck" "--format" "edn"))))))
  (testing "no such session"
    (is (= {:status 1 :out "" :err "neorepl: No session called nope\n"}
           (run-command "interrupt" "-s" "nope")))))

(defn- start-neorepl
  "Runs neorepl with args in a process of its own, on the runtime running the
  tests, with the test session cache."
  ^Process [& args]
  (let [classpath (System/getProperty "java.class.path")
        cmd (if (System/getProperty "babashka.version")
              ["bb" "-cp" classpath "-m" "neorepl.main"]
              [(str (io/file (System/getProperty "java.home") "bin" "java"))
               "-cp" classpath "clojure.main" "-m" "neorepl.main"])
        builder (ProcessBuilder. ^java.util.List (into cmd args))]
    (.put (.environment builder) "XDG_CACHE_HOME" (str *dir*))
    (.start builder)))

(deftest kill-test
  ;; Windows has no SIGTERM, so destroy skips the shutdown hooks there.
  (when-not (test-server/windows?)
    (testing "killing neorepl interrupts its evaluation, freeing the session"
      (reset! started (promise))
      (let [process (start-neorepl "eval" "-p" (str *port*) "-s" "killed"
                                   "(deliver @neorepl.main-test/started true) (Thread/sleep 20000)")]
        (is (deref @started 30000 false))
        (.destroy process)
        (is (.waitFor process 10 TimeUnit/SECONDS))
        (is (= {:status 0 :out "1\n" :err ""}
               (run "-s" "killed" "--timeout" "5" "1")))))))

(deftest input-test
  (testing "code from a file"
    (let [file (spit-file *dir* "code.clj" "(+ 1 2)\n*file*")]
      (is (= {:status 0 :out (str "3\n" (pr-str (.getAbsolutePath file)) "\n") :err ""}
             (run "-f" (str file))))))
  (testing "code from stdin"
    (is (= {:status 0 :out "3\n" :err ""}
           (run-in *dir* "(+ 1 2)" "eval" "-p" (str *port*) "-"))))
  (testing "reads from *in* get a line of stdin each"
    ;; A fresh session, since servers send need-input to the connection that
    ;; created the session.
    (is (= {:status 0 :out "\"hello\"\n\"world\"\nnil\n" :err ""}
           (run-in *dir* "hello\nworld\n" "eval" "-p" (str *port*) "-s" "stdin-test"
                   "(read-line) (read-line) (read-line)")))))

(deftest load-test
  (let [file (spit-file *dir* "src/demo/core.clj"
                        "(ns demo.core)\n(println :loading)\n(defn answer [] 42)\n(answer)")]
    (testing "only the last value comes back"
      (is (= {:status 0 :out ":loading\n42\n" :err ""}
             (run-command "load" "-s" "load-test" (str file)))))
    (testing "the session stays in its namespace"
      (is (= {:status "ok" :values ["42"] :out "" :err "" :ns "user"}
             (edn/read-string (:out (run "-s" "load-test" "--format" "edn" "(demo.core/answer)")))))))
  (testing "loading stops at the first error, which points at the file"
    (let [file (spit-file *dir* "src/demo/broken.clj" "(ns demo.broken)\n(/ 1 0)\n(println :after)")
          {:keys [status out err]} (run-command "load" (str file))]
      (is (= 1 status))
      (is (= "" out))
      (is (str/includes? err "broken.clj:2"))))
  (testing "structured output"
    (let [file (spit-file *dir* "src/demo/value.clj" "(ns demo.value)\n:a\n(range 10)")]
      ;; load-file doesn't say which namespace the session's in.
      (is (= {:status "ok" :values ["(0 1 2"] :out "" :err "" :ns nil :truncated true}
             (edn/read-string (:out (run-command "load" "--format" "edn" "--max-output" "6" (str file)))))))))

(deftest discovery-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir ".nrepl-port" (str *port*))
     (is (= {:status 0 :out "3\n" :err ""}
            (run-in (io/file dir "sub") "" "eval" "(+ 1 2)"))))))

(deftest ports-test
  (call-with-temp-dir
   (fn [dir]
     (testing "no port files"
       ;; Unless a parent of the temp dir happens to have one.
       (when (empty? (discovery/find-ports dir))
         (let [{:keys [status err]} (run-in dir "" "ports")]
           (is (= 3 status))
           (is (str/includes? err "No nREPL port file")))))
     (let [app (io/file dir "app")
           stale (.getCanonicalPath (spit-file app ".nrepl-port" (str (test-server/free-port))))
           live (.getCanonicalPath (spit-file dir ".nrepl-port" (str *port*)))]
       (testing "nearest first, live or not"
         (let [{:keys [status out]} (run-in app "" "ports")]
           (is (zero? status))
           (is (re-find (re-pattern (str "^\\Q" stale "\\E +\\d+ +not answering\n"
                                         "\\Q" live "\\E +" *port* " +.*nrepl \\d")) out))))
       (testing "structured"
         (let [[s l] (edn/read-string (:out (run-in app "" "ports" "--format" "edn")))]
           (is (= {:file stale :live false} (dissoc s :port)))
           (is (= {:file live :port *port* :live true} (dissoc l :versions)))
           (is (string? (get-in l [:versions :nrepl])))))
       (testing "nothing answering"
         (spit live (str (test-server/free-port)))
         (is (= 3 (:status (run-in app "" "ports")))))))))

(deftest errors-test
  (testing "usage errors"
    (are [args message] (let [{:keys [status out err]} (apply run-in *dir* "" args)]
                          (and (= 2 status)
                               (= "" out)
                               (str/includes? err message)))
      ["eval" "--bogus" "1"] "Unknown option: --bogus"
      ["eval" "-p"] "-p needs a value"
      ["eval" "-p" "http" "1"] "Invalid value for -p: http"
      ["eval" "--timeout" "-1" "1"] "Invalid value for --timeout"
      ["eval" "--format" "yaml" "1"] "Invalid value for --format: yaml"
      ["eval" "--timeout" "1234567890" "1"] "Invalid value for --timeout"
      ["eval" "--max-output" "0" "1"] "Invalid value for --max-output"
      ["eval" "--max-output" "1.5" "1"] "Invalid value for --max-output"
      ["eval" "--url" "http://localhost:1" "1"] "Invalid value for --url"
      ["eval" "--url" "nrepl://localhost:1" "-p" "1" "1"] "--url doesn't go with --host or --port"
      ["eval" "--host" "localhost" "1"] "--host needs a --port"
      ["eval" "--host=" "-p" "1" "1"] "Invalid value for --host"
      ["eval" "-s" "" "1"] "Invalid value for -s"
      ["eval" "-p" "1"] "Nothing to evaluate"
      ["eval" "-p" "1" "(+" "1)"] "Too many arguments"
      ["eval" "-f" "x.clj" "1"] "not both"
      ["eval" "-f" "/does/not/exist.clj"] "No such file"
      ["load" "-p" "1"] "Nothing to load"
      ["load" "-p" "1" "a.clj" "b.clj"] "Load one file at a time"
      ["load" "-p" "1" "/does/not/exist.clj"] "No such file"
      ["load" "-f" "x.clj"] "Unknown option: -f"
      ["interrupt" "-p" "1" "now"] "Unexpected argument: now"
      ["sessions" "-p" "1" "open"] "Unexpected argument: open"
      ["sessions" "-p" "1" "close"] "Give the NAME of the session to close"
      ["sessions" "-p" "1" "close" "a" "b"] "Unexpected argument: b"
      ["ports" "here"] "Unexpected argument: here"
      ["ports" "-p" "1"] "Unknown option: -p"
      ["evil"] "Unknown command: evil"))
  (testing "usage errors point at the right help"
    (is (str/includes? (:err (run-in *dir* "" "load" "--bogus")) "Run neorepl load --help"))
    (is (str/includes? (:err (run-in *dir* "" "evil")) "Run neorepl --help")))
  (testing "no server"
    (let [{:keys [status err]} (run-in *dir* "" "eval" "-p" (str (test-server/free-port)) "1")]
      (is (= 3 status))
      (is (str/includes? err "No nREPL server answering")))))

(deftest help-test
  (are [args text] (let [{:keys [status out]} (apply run-in *dir* "" args)]
                     (and (zero? status) (str/includes? out text)))
    [] "Commands:"
    ["--help"] "Commands:"
    ["eval" "--help"] "Usage: neorepl eval"
    ["eval" "-h"] "--timeout SECS"
    [] "load "
    ["load" "--help"] "Usage: neorepl load"
    ["interrupt" "--help"] "Usage: neorepl interrupt"
    ["sessions" "--help"] "Usage: neorepl sessions"
    ["ports" "--help"] "Usage: neorepl ports"))
