(ns neorepl.jack-in-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.jack-in :as jack-in]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :refer [call-with-temp-dir spit-file windows? with-client]]))

(deftest find-project-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir "deps.edn" "{}")
     (spit-file dir "bb.edn" "{}")
     (spit-file dir "src/demo/core.clj" "(ns demo.core)")
     (spit-file dir "sub/project.clj" "(defproject sub \"0.1.0\")")
     (let [path #(.getCanonicalPath (io/file dir %))]
       (testing "the nearest project, with the first of its tools"
         (is (= {:dir (path ".") :tool "clojure"} (jack-in/find-project (io/file dir "src/demo") nil)))
         (is (= {:dir (path "sub") :tool "lein"} (jack-in/find-project (io/file dir "sub") nil))))
       (testing "or the one asked for"
         (is (= {:dir (path ".") :tool "bb"} (jack-in/find-project (io/file dir "sub") "bb"))))
       (testing "or none, saying what's missing"
         (is (nil? (jack-in/find-project dir "lein")))
         (is (= (str "No project.clj in " (path ".") " or its parents")
                (ex-message (jack-in/no-project dir "lein"))))
         (is (= "project.clj, deps.edn or bb.edn" (jack-in/project-files nil))))))))

(deftest command-test
  (let [{:keys [nrepl cider-nrepl]} jack-in/versions]
    (testing "the Clojure CLI, with the deps and an alias of its own"
      (let [[clojure sdeps deps & more] (jack-in/command "clojure" {:aliases ["dev" "test"] :port 7888})]
        (is (= ["clojure" "-Sdeps"] [clojure sdeps]))
        (is (= {:deps {'nrepl/nrepl {:mvn/version nrepl}
                       'cider/cider-nrepl {:mvn/version cider-nrepl}}
                :aliases {:neorepl/jack-in {:main-opts ["-m" "nrepl.cmdline"
                                                        "--middleware" "[cider.nrepl/cider-middleware]"]}}}
               (edn/read-string deps)))
        (testing "written out plainly, for people to read"
          (is (not (str/includes? deps "#:"))))
        (testing "and the port after it, keeping the deps the same for the CLI's cache"
          (is (= ["-M:dev:test:neorepl/jack-in" "--port" "7888"] more)))))
    (testing "Leiningen"
      (is (= ["lein"
              "update-in" ":dependencies" "conj" (str "[nrepl/nrepl \"" nrepl "\"]") "--"
              "update-in" ":plugins" "conj" (str "[cider/cider-nrepl \"" cider-nrepl "\"]") "--"
              "with-profile" "+dev,-test"
              "repl" ":headless" ":port" "7888"]
             (jack-in/command "lein" {:aliases ["dev" "-test"] :port 7888})))
      (is (= ["repl" ":headless"] (take-last 2 (jack-in/command "lein" {})))))
    (testing "babashka"
      (is (= ["bb" "nrepl-server" "127.0.0.1:0"] (jack-in/command "bb" {})))
      (is (= ["bb" "nrepl-server" "127.0.0.1:7888"] (jack-in/command "bb" {:port 7888}))))))

(deftest with-server-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir "bb.edn" "{}")
     (let [lines (atom [])
           process (jack-in/with-server dir (jack-in/command "bb" {}) #(swap! lines conj %)
                     (fn [{:keys [^Process process] :as server}]
                       (testing "says where the server listens"
                         (with-client [client (select-keys server [:host :port])]
                           (is (= ["3"] (keep :value (nrepl/request client {:op "eval" :code "(+ 1 2)"}))))))
                       (testing "passes on what it prints"
                         (is (str/starts-with? (first @lines) "Started nREPL server")))
                       (when-not (windows?)
                         (testing "with the server ignoring Ctrl-C, which is for whatever runs it"
                           (.waitFor (.start (ProcessBuilder. ^java.util.List ["kill" "-INT" (str (.pid process))])))
                           (Thread/sleep 200)
                           (is (.isAlive process))))
                       process))]
       (testing "stops it after"
         (is (not (.isAlive ^Process process)))
         ;; Windows has no SIGTERM, so the server has no say in it.
         (when-not (windows?)
           (testing "the way it cleans up after itself"
             (is (not (.exists (io/file dir ".nrepl-port")))))))))))

(deftest stop-test
  (when-not (windows?)
    (testing "the processes the server started go too"
      (call-with-temp-dir
       (fn [dir]
         (let [[process children]
               (jack-in/with-server dir ["sh" "-c" "sleep 60 & sleep 60 & echo 'nREPL server started on port 1 on host x'; wait"]
                 (fn [_])
                 (fn [{:keys [^Process process] :as server}]
                   (is (= {:host "x" :port 1} (select-keys server [:host :port])))
                   [process (vec (iterator-seq (.iterator (.descendants process))))]))]
           (is (seq children))
           (is (not (.isAlive ^Process process)))
           (is (not-any? #(.isAlive %) children))))))))

(deftest failed-test
  (call-with-temp-dir
   (fn [dir]
     (let [lines (atom [])]
       (testing "a server that ends before it's up"
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ended before it started, with exit status 3"
                               (jack-in/with-server dir ["bb" "-e" "(binding [*out* *err*] (println :oops)) (System/exit 3)"]
                                 #(swap! lines conj %)
                                 (fn [_]))))
         (testing "after what it said"
           (is (= [":oops"] @lines))))
       (testing "one that can't start"
         (is (= ::jack-in/failed
                (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                             (jack-in/with-server (io/file dir "nope") ["bb"] (fn [_]) (fn [_])))))))))))))
