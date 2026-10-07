(ns neorepl.sessions-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file]])
  (:import (java.io StringWriter)))

(use-fixtures :once (test-server/fixture))

(deftest cache-file-test
  (let [home (.getAbsolutePath (io/file "home"))
        xdg (.getAbsolutePath (io/file "xdg"))]
    (is (= (io/file xdg "neorepl" "sessions.edn") (sessions/cache-file xdg home)))
    (testing "~/.cache when XDG_CACHE_HOME is unset, empty or relative"
      (are [x] (= (io/file home ".cache" "neorepl" "sessions.edn") (sessions/cache-file x home))
        nil
        ""
        "cache"))))

(deftest ensure!-test
  (call-with-temp-dir
   (fn [dir]
     (let [file (io/file dir "cache/sessions.edn")
           eval-in #(keep :value (nrepl/request %1 {:op "eval" :code %3 :session %2}))
           session (let [client (nrepl/connect {:port *port*})]
                     (try
                       (let [session (sessions/ensure! file client "agent")]
                         (eval-in client session "(def x 41)")
                         session)
                       (finally
                         (nrepl/close client))))
           client (nrepl/connect {:port *port*})]
       (try
         (testing "the session gets cached under the server's address"
           (is (= {(:address client) {"agent" session}} (edn/read-string (slurp file)))))
         (testing "the same name gets the same session from another connection"
           (is (= session (sessions/ensure! file client "agent")))
           (is (= ["42"] (eval-in client session "(inc x)"))))
         (testing "other names get other sessions"
           (let [other (sessions/ensure! file client "other")]
             (is (not= session other))
             (is (= other (sessions/ensure! file client "other")))))
         (testing "a session the server lost gets replaced"
           (nrepl/close-session client session)
           (testing "unless the server can't tell us"
             (is (= session (sessions/ensure! file (update client :ops disj "ls-sessions") "agent"))))
           (let [fresh (sessions/ensure! file client "agent")]
             (is (not= session fresh))
             (is (= fresh (sessions/ensure! file client "agent")))))
         (testing "a broken cache file is as good as none"
           (spit file "{:oops")
           (let [fresh (sessions/ensure! file client "agent")]
             (is (= fresh (sessions/ensure! file client "agent")))))
         (testing "a cache that can't be written costs a warning, not the session"
           (spit-file dir "blocker" "")
           (let [err (StringWriter.)
                 session (binding [*err* err]
                           (sessions/ensure! (io/file dir "blocker/sessions.edn") client "agent"))]
             (is (= ["1"] (eval-in client session "1")))
             (is (str/includes? (str err) "Couldn't save the session"))))
         (finally
           (nrepl/close client)))))))
