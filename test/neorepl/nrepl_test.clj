(ns neorepl.nrepl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.nrepl :as nrepl]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import (java.net ServerSocket)))

(defn- wrap-ping
  "A test op whose name has a namespace, like cider-nrepl's cider/* ops."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "neorepl/ping" op)
      (transport/respond-to msg :status "done" :pong "yes")
      (handler msg))))

(middleware/set-descriptor! #'wrap-ping {:handles {"neorepl/ping" {:doc "Answers with pong."}}})

(defn- start-server [& opts]
  (apply server/start-server :handler (server/default-handler #'wrap-ping) opts))

(def ^:dynamic *port* nil)

(use-fixtures :once
  (fn [f]
    (let [srv (start-server :port 0)]
      (try
        (binding [*port* (:port srv)]
          (f))
        (finally
          (server/stop-server srv))))))

(defmacro ^:private with-client
  "Evaluates body with sym bound to a client connected with opts."
  [[sym opts] & body]
  `(let [~sym (nrepl/connect ~opts)]
     (try ~@body (finally (nrepl/close ~sym)))))

(defn- eval-in [client session code]
  (nrepl/request client {:op "eval" :code code :session session}))

(defn- values [responses]
  (keep :value responses))

(defn- windows? []
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(deftest parse-url-test
  (are [url opts] (= opts (nrepl/parse-url url))
    "nrepl://localhost:7889" {:host "localhost" :port 7889}
    "nrepl://example.com" {:host "example.com" :port 7888}
    "nrepl://my_service:7888/" {:host "my_service" :port 7888}
    "NREPL://[::1]:1234" {:host "::1" :port 1234}
    "nrepl+unix:/tmp/nrepl.sock" {:socket "/tmp/nrepl.sock"}
    "nrepl+unix:relative/nrepl.sock" {:socket "relative/nrepl.sock"}
    "nrepl+unix:/tmp/a%20b/nrepl.sock" {:socket "/tmp/a b/nrepl.sock"})
  (are [url] (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported nREPL URL"
                               (nrepl/parse-url url))
    "http://localhost:7888"
    "nrepl://:7888"
    "nrepl:/foo"
    "nrepl+unix://host/path"))

(deftest connect-test
  (with-client [client {:port *port*}]
    (testing "describe info comes along"
      (is (every? (:ops client) ["eval" "clone" "interrupt" "completions" "lookup"]))
      (is (contains? (:versions client) :nrepl)))
    (testing "op names keep their namespace"
      (is (contains? (:ops client) "neorepl/ping"))
      (is (= "yes" (some :pong (nrepl/request client {:op "neorepl/ping"}))))))
  (testing "the same server through a URL"
    (with-client [client {:url (str "nrepl://127.0.0.1:" *port*)}]
      (is (= ["3"] (values (nrepl/request client {:op "eval" :code "(+ 1 2)"}))))))
  (testing "nothing listening"
    (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
          e (is (thrown? clojure.lang.ExceptionInfo (nrepl/connect {:port port})))]
      (is (= ::nrepl/no-server (:type (ex-data e))))))
  (testing "something that isn't an nREPL server"
    (with-open [socket (ServerSocket. 0)]
      (future (with-open [conn (.accept socket)]
                (doto (.getOutputStream conn)
                  (.write (.getBytes "HTTP/1.1 400 Bad Request\r\n\r\n"))
                  (.flush))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo (nrepl/connect {:port (.getLocalPort socket)})))]
        (is (= ::nrepl/no-server (:type (ex-data e))))))))

(deftest request-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          other (nrepl/clone-session client)]
      (testing "values come back as strings, output separately"
        (let [responses (eval-in client session "(println \"hi\") (+ 1 2) {:a 1}")]
          (is (= ["nil" "3" "{:a 1}"] (values responses)))
          (is (= "hi\n" (apply str (keep :out responses))))
          (is (nrepl/done? (peek responses)))))
      (testing "sessions keep their state"
        (eval-in client session "(def x 41) (in-ns 'neorepl.scratch)")
        (let [responses (eval-in client session "(clojure.core/inc user/x)")]
          (is (= ["42"] (values responses)))
          (is (= "neorepl.scratch" (some :ns responses)))))
      (testing "and don't share it"
        (is (= "user" (some :ns (eval-in client other "1")))))
      (testing "cloning a session copies its bindings, except *ns*"
        (eval-in client session "(set! clojure.core/*print-length* 3)")
        (let [copy (nrepl/clone-session client session)]
          (is (= ["3"] (values (eval-in client copy "clojure.core/*print-length*"))))
          (is (= "user" (some :ns (eval-in client copy "1"))))))
      (testing "closing a session"
        (let [doomed (nrepl/clone-session client)
              live-sessions #(set (some :sessions (nrepl/request client {:op "ls-sessions"})))]
          (is (contains? (live-sessions) doomed))
          (nrepl/close-session client doomed)
          (is (not (contains? (live-sessions) doomed)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Couldn't create a session"
                                (nrepl/clone-session client doomed)))))
      (testing "errors"
        (let [responses (eval-in client other "(/ 1 0)")]
          (is (some #(nrepl/status? % "eval-error") responses))
          (is (= "class java.lang.ArithmeticException" (some :ex responses))))))))

(deftest timeout-test
  (let [unclaimed (atom [])]
    (with-client [client {:port *port* :on-unclaimed #(swap! unclaimed conj %)}]
      (let [session (nrepl/clone-session client)
            e (is (thrown? clojure.lang.ExceptionInfo
                           (nrepl/request client {:op "eval" :session session :code "(Thread/sleep 500) :late"}
                                          {:timeout-ms 100})))]
        (testing "a request that doesn't finish in time throws with the message id"
          (is (= ::nrepl/timeout (:type (ex-data e))))
          (is (string? (:id (ex-data e)))))
        (testing "and its late responses are dropped rather than unclaimed"
          (is (= ["1"] (values (eval-in client session "1"))))
          (is (empty? @unclaimed)))))))

(deftest send!-test
  (let [unclaimed (promise)]
    (with-client [client {:port *port* :on-unclaimed #(when (:out %) (deliver unclaimed %))}]
      (let [session (nrepl/clone-session client)]
        (testing "handlers see responses as they stream in"
          (let [seen (atom [])
                done (promise)]
            (nrepl/send! client {:op "eval" :session session
                                 :code "(println 1) (flush) (Thread/sleep 100) (println 2)"}
                         (fn [response]
                           (swap! seen conj response)
                           (when (nrepl/done? response) (deliver done true))))
            (is (deref done 5000 false))
            (is (= ["1\n" "2\n"] (keep :out @seen)))))
        (testing "output that arrives after done goes to on-unclaimed"
          (eval-in client session "(future (Thread/sleep 100) (println \"late\"))")
          (is (= "late\n" (:out (deref unclaimed 5000 nil)))))
        (testing "interrupting a running evaluation"
          (let [started (promise)
                done (promise)
                statuses (atom #{})
                id (nrepl/send! client {:op "eval" :session session
                                        :code "(println :started) (flush) (Thread/sleep 10000)"}
                                (fn [response]
                                  (when (:out response) (deliver started true))
                                  (swap! statuses into (:status response))
                                  (when (nrepl/done? response) (deliver done true))))]
            (is (deref started 5000 false))
            (nrepl/interrupt client session id)
            (is (deref done 5000 false))
            (is (contains? @statuses "interrupted"))))))))

(deftest handler-error-test
  (let [error (promise)]
    (with-client [client {:port *port* :on-error #(deliver error %)}]
      (testing "a handler that throws doesn't take the connection down"
        (nrepl/send! client {:op "eval" :code "1"} (fn [_] (throw (ex-info "boom" {}))))
        (is (= "boom" (some-> (deref error 5000 nil) ex-message)))
        (is (= ["2"] (values (nrepl/request client {:op "eval" :code "2"}))))))))

(deftest disconnect-test
  (testing "pending requests finish when the server goes away"
    (let [srv (start-server :port 0)
          response (promise)]
      (with-client [client {:port (:port srv)}]
        (nrepl/send! client {:op "eval" :code "(Thread/sleep 10000)"}
                     #(when (nrepl/done? %) (deliver response %)))
        (Thread/sleep 200)
        (server/stop-server srv)
        (is (nrepl/status? (deref response 5000 nil) "disconnected"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not connected"
                              (nrepl/request client {:op "eval" :code "1"}))))))
  (testing "and when the client closes the connection itself"
    (let [client (nrepl/connect {:port *port*})
          response (promise)]
      (nrepl/send! client {:op "eval" :code "(Thread/sleep 10000)"}
                   #(when (nrepl/done? %) (deliver response %)))
      (nrepl/close client)
      (is (nrepl/status? (deref response 5000 nil) "disconnected"))
      (.join ^Thread (:reader client) 5000)
      (is (not (.isAlive ^Thread (:reader client)))))))

(deftest unix-socket-test
  (when-not (windows?)
    (let [path (str (System/getProperty "java.io.tmpdir") "/neorepl-test-" (System/nanoTime) ".sock")
          srv (start-server :socket path)]
      (try
        (doseq [opts [{:socket path} {:url (str "nrepl+unix:" path)}]]
          (with-client [client opts]
            (is (= ["3"] (values (nrepl/request client {:op "eval" :code "(+ 1 2)"}))))))
        (finally
          (server/stop-server srv))))))
