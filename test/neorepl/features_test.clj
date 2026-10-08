(ns neorepl.features-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :as test-server :refer [*port* with-client]]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport]))

(use-fixtures :once (test-server/fixture))

(def ^:private refresh-fails? (atom false))

(defn- wrap-fake-cider
  "Answers info, complete and apropos the way cider-nrepl does, with canned
  results, to tell its rung of the ladder from the others."
  [handler]
  (fn [{:keys [op sym] :as msg}]
    (case op
      "info" (case sym
               "map" (transport/respond-to msg :status "done" :ns "clojure.core" :name "map"
                                           :arglists-str "[f]\n[f coll]" :doc "Cider's map."
                                           :file "file:/tmp/a%20b/core.clj" :line 1)
               "mapv" (transport/respond-to msg :status "done" :ns "clojure.core" :name "mapv"
                                            :file "jar:file:/x.jar!/clojure/core.clj" :line 2)
               "made-at-the-repl" (transport/respond-to msg :status "done" :ns "user" :name "made-at-the-repl"
                                                        :file "NO_SOURCE_PATH" :line 1)
               "if" (transport/respond-to msg :status "done" :name "if" :special-form "true"
                                          :forms-str "(if test then else?)")
               "broken" (transport/respond-to msg :status ["done" "info-error"])
               (transport/respond-to msg :status ["done" "no-info"]))
      "complete" (transport/respond-to msg :status "done"
                                       :completions [{:candidate "map" :type "function" :ns "clojure.core"}])
      "apropos" (transport/respond-to msg :status "done"
                                      :apropos-matches [{:name "clojure.core/map" :type "function"
                                                         :doc "Cider's map.\nMore."}])
      "macroexpand" (transport/respond-to msg :status "done" :expansion (str "Cider's " (:expander msg)))
      "refresh" (if @refresh-fails?
                  ;; cider-nrepl prints the error, as the REPL would.
                  (do (transport/respond-to msg :reloading ["broken.ns"])
                      (transport/respond-to msg :err "Syntax error compiling at (broken/ns.clj:1:1).\n")
                      (transport/respond-to msg :status "error" :error-ns "broken.ns")
                      (transport/respond-to msg :status "done"))
                  (do (transport/respond-to msg :reloading ["changed.ns"])
                      (transport/respond-to msg :err "Reflection warning, changed/ns.clj:3:1\n")
                      (transport/respond-to msg :status ["ok" "done"])))
      "analyze-last-stacktrace" (do (transport/respond-to msg :class "clojure.lang.ExceptionInfo" :message "boom"
                                                          :data "{:k 1}" :phase "execution"
                                                          :stacktrace [{:name "user/f" :file "x.clj" :line 1
                                                                        :file-url []}])
                                    (transport/respond-to msg :class "java.lang.Exception" :message []
                                                          :stacktrace [])
                                    (transport/respond-to msg :status "done"))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-cider {:handles {"info" {} "complete" {} "apropos" {}
                                                         "macroexpand" {} "analyze-last-stacktrace" {}
                                                         "refresh" {}}})

(defn- without
  "client, pretending its server doesn't have ops."
  [client & ops]
  (update client :ops #(apply disj % ops)))

(deftest info-test
  (with-client [client {:port *port*}]
    (doseq [[rung client] {"lookup" client "eval" (without client "lookup")}]
      (testing rung
        (let [info (features/info client "map" "user")]
          (is (= {:ns "clojure.core" :name "map"}
                 (select-keys info [:ns :name])))
          (is (= ["[f]" "[f coll]"] (take 2 (:arglists info))))
          (is (str/starts-with? (:doc info) "Returns a lazy sequence"))
          (is (pos? (:line info))))
        (testing "macros"
          (is (:macro (features/info client "when" "user"))))
        (testing "nothing there"
          (is (nil? (features/info client "no-such-thing" "user"))))
        (testing "nowhere for what was made at a REPL"
          (nrepl/request client {:op "eval" :ns "user" :code "(defn made-at-the-repl [])"})
          (is (= {:ns "user" :name "made-at-the-repl"}
                 (select-keys (features/info client "made-at-the-repl" "user") [:ns :name :file :line]))))
        (testing "symbols are only ever symbols"
          (is (nil? (features/info client "(System/exit 1)" "user"))))))
    (testing "special forms"
      (is (= {:special-form true :forms ["(if test then else?)"]}
             (select-keys (features/info client "if" "user") [:special-form :forms])))
      ;; On babashka they're macros, without forms.
      (when-not (System/getProperty "babashka.version")
        (testing "with nested shapes and bare symbols"
          (is (= "(Classname/staticMethod args*) Classname/staticField"
                 (->> (features/info client "." "user") :forms (take-last 2) (str/join " ")))))))))

(deftest complete-test
  (with-client [client {:port *port*}]
    ;; Only the JVM's nREPL says what type a candidate is.
    (is (some #(= {:candidate "mapcat" :ns "clojure.core"} (dissoc % :type))
              (features/complete client "mapc" "user")))
    (testing "no completion without the ops for it"
      (is (nil? (features/complete (without client "completions") "mapc" "user"))))))

(deftest source-test
  (with-client [client {:port *port*}]
    (is (str/starts-with? (features/source client "when" "user") "(defmacro when"))
    (is (nil? (features/source client "no-such-thing" "user")))))

(deftest apropos-test
  (with-client [client {:port *port*}]
    (is (some #{{:name "clojure.core/mapcat"}} (features/apropos client "^mapc")))))

(deftest expand-test
  (with-client [client {:port *port*}]
    (is (= "(if x (do y))" (features/expand client "(when x y)" "user" false)))
    (is (str/includes? (features/expand client "(when-let [a 1] a)" "user" true) "let*"))
    (testing "one form, only ever read, not evaluated"
      (are [text] (= ::features/failed
                     (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/expand client text "user" false))))))
        "#=(System/exit 1)"
        "(when x y) (+ 1 2)"
        "x) (spit \"/tmp/p\" 1"
        ""))))

(deftest last-error-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)]
      (testing "nothing yet"
        (is (nil? (features/last-error client session))))
      (nrepl/request client {:op "eval" :session session
                             :code "(defn boom [] (throw (ex-info \"boom\" {:k 1} (ArithmeticException. \"inner\")))) (boom)"})
      (let [[outer inner :as causes] (features/last-error client session)]
        (is (= 2 (count causes)))
        (is (= {:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"}
               (select-keys outer [:class :message :data])))
        (is (= {:class "java.lang.ArithmeticException" :message "inner"}
               (select-keys inner [:class :message])))
        (is (seq (:stacktrace inner)))
        (testing "with frames that follow on in the same function flagged dup"
          (let [shown (remove #(some #{"dup" "tooling"} (:flags %)) (:stacktrace inner))
                fn-of #(first (str/split (:name %) #"/"))]
            (is (every? (fn [[a b]] (not= (fn-of a) (fn-of b))) (partition 2 1 shown))))))
      (testing "leaves the session's *1 alone"
        (is (= ["#'user/boom"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"}))))))))

(deftest reload-test
  (with-client [client {:port *port*}]
    (testing "the namespaces given"
      (is (= {:reloaded ["clojure.set"]} (features/reload client ["clojure.set"]))))
    (testing "what changed, which takes cider-nrepl"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl" (features/reload client []))))))

(deftest error-test
  (with-client [client {:port *port*}]
    (testing "errors aren't the same as finding nothing"
      (are [f] (= ::features/failed (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f client))))))
        #(features/source % "map" "no.such.ns")
        #(features/apropos % "(")
        #(features/reload % ["no.such.ns"])))))

(deftest cider-test
  (let [srv (server/start-server :port 0 :handler (server/default-handler #'wrap-fake-cider))]
    (try
      (with-client [client {:port (:port srv)}]
        (testing "cider-nrepl's ops come first"
          (is (= {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Cider's map."
                  :file (str (io/file "/tmp/a b/core.clj")) :line 1}
                 (features/info client "map" "user")))
          (testing "with files in jars as they are, and none for what was made at a REPL"
            (is (= "jar:file:/x.jar!/clojure/core.clj" (:file (features/info client "mapv" "user"))))
            (is (= {:ns "user" :name "made-at-the-repl"} (features/info client "made-at-the-repl" "user"))))
          (is (= {:name "if" :special-form true :forms ["(if test then else?)"]}
                 (features/info client "if" "user")))
          (is (nil? (features/info client "no-such-thing" "user")))
          (is (= ::features/failed
                 (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/info client "broken" "user")))))))
          (is (= [{:candidate "map" :type "function" :ns "clojure.core"}]
                 (features/complete client "ma" "user")))
          (is (= [{:name "clojure.core/map" :type "function" :doc "Cider's map.\nMore."}]
                 (features/apropos client "map")))
          (is (= "Cider's macroexpand-all" (features/expand client "(when x y)" "user" true)))
          (is (= {:reloaded ["changed.ns"] :output "Reflection warning, changed/ns.clj:3:1\n"}
                 (features/reload client [])))
          (reset! refresh-fails? true)
          (try
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refresh: Syntax error compiling at \(broken/ns\.clj"
                                  (features/reload client [])))
            (finally
              (reset! refresh-fails? false)))
          (is (= [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"
                   :stacktrace [{:name "user/f" :file "x.clj" :line 1}]}
                  ;; cider-nrepl sends nil as an empty list.
                  {:class "java.lang.Exception" :message nil :data nil :stacktrace []}]
                 (features/last-error client (nrepl/clone-session client))))))
      (finally
        (server/stop-server srv)))))
