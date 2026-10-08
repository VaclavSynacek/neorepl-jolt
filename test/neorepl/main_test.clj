(ns neorepl.main-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file]])
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

(defn- run
  "Runs neorepl eval against the test server with args."
  [& args]
  (apply run-in *dir* "" "eval" "-p" (str *port*) args))

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
    (let [client (nrepl/connect {:port *port*})]
      (try
        (let [lost (sessions/ensure! (sessions-file) client "lost")]
          (nrepl/close-session client lost)
          (is (= {:status 0 :out "2\n" :err ""} (run "-s" "lost" "2")))
          (is (not= lost (sessions/ensure! (sessions-file) client "lost"))))
        (finally
          (nrepl/close client))))))

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
  "Delivered by code the killed neorepl evaluates on the test server."
  (atom nil))

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

(deftest discovery-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir ".nrepl-port" (str *port*))
     (is (= {:status 0 :out "3\n" :err ""}
            (run-in (io/file dir "sub") "" "eval" "(+ 1 2)"))))))

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
      ["evil"] "Unknown command: evil"))
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
    ["eval" "-h"] "--timeout SECS"))
