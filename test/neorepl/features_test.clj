(ns neorepl.features-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.test-server :as test-server :refer [*port* with-client]]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport]))

(use-fixtures :once (test-server/fixture))

(defn- wrap-fake-cider
  "Answers info, complete and apropos the way cider-nrepl does, with canned
  results, to tell its rung of the ladder from the others."
  [handler]
  (fn [{:keys [op sym] :as msg}]
    (case op
      "info" (case sym
               "map" (transport/respond-to msg :status "done" :ns "clojure.core" :name "map"
                                           :arglists-str "[f]\n[f coll]" :doc "Cider's map." :line 1)
               "if" (transport/respond-to msg :status "done" :name "if" :special-form "true"
                                          :forms-str "(if test then else?)")
               "broken" (transport/respond-to msg :status ["done" "info-error"])
               (transport/respond-to msg :status ["done" "no-info"]))
      "complete" (transport/respond-to msg :status "done"
                                       :completions [{:candidate "map" :type "function" :ns "clojure.core"}])
      "apropos" (transport/respond-to msg :status "done"
                                      :apropos-matches [{:name "clojure.core/map" :type "function"
                                                         :doc "Cider's map.\nMore."}])
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-cider {:handles {"info" {} "complete" {} "apropos" {}}})

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

(deftest text-test
  (testing "like clojure.repl/doc"
    (is (= "clojure.core/map\n([f] [f coll])\n  Maps."
           (features/doc-text {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Maps."})))
    (is (= "when\n([test & body])\nMacro"
           (features/doc-text {:name "when" :arglists ["[test & body]"] :macro true})))
    (is (= "if\n  (if test then else?)\nSpecial Form"
           (features/doc-text {:name "if" :forms ["(if test then else?)"] :special-form true})))
    (is (= "let\n  (let [bindings*] exprs*)\nSpecial Form"
           (features/doc-text {:name "let" :forms ["(let [bindings*] exprs*)"] :special-form true :macro true}))))
  (testing "eldoc"
    (are [info text] (= text (features/eldoc-text info))
      {:arglists ["[f]" "[f coll]"]} "[f] [f coll]"
      {:forms ["(if test then else?)"]} "(if test then else?)"
      {:name "cfg"} nil)))

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

(deftest error-test
  (with-client [client {:port *port*}]
    (testing "errors aren't the same as finding nothing"
      (are [f] (= ::features/failed (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f client))))))
        #(features/source % "map" "no.such.ns")
        #(features/apropos % "(")))))

(deftest cider-test
  (let [srv (server/start-server :port 0 :handler (server/default-handler #'wrap-fake-cider))]
    (try
      (with-client [client {:port (:port srv)}]
        (testing "cider-nrepl's ops come first"
          (is (= {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Cider's map." :line 1}
                 (features/info client "map" "user")))
          (is (= {:name "if" :special-form true :forms ["(if test then else?)"]}
                 (features/info client "if" "user")))
          (is (nil? (features/info client "no-such-thing" "user")))
          (is (= ::features/failed
                 (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/info client "broken" "user")))))))
          (is (= [{:candidate "map" :type "function" :ns "clojure.core"}]
                 (features/complete client "ma" "user")))
          (is (= [{:name "clojure.core/map" :type "function" :doc "Cider's map.\nMore."}]
                 (features/apropos client "map")))))
      (finally
        (server/stop-server srv)))))
