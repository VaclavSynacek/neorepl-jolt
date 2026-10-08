(ns neorepl.render-test
  (:require [clojure.test :refer [are deftest is testing]]
            [neorepl.render :as render]))

(deftest doc-text-test
  (testing "like clojure.repl/doc"
    (is (= "clojure.core/map\n([f] [f coll])\n  Maps."
           (render/doc-text {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Maps."})))
    (is (= "when\n([test & body])\nMacro"
           (render/doc-text {:name "when" :arglists ["[test & body]"] :macro true})))
    (is (= "if\n  (if test then else?)\nSpecial Form"
           (render/doc-text {:name "if" :forms ["(if test then else?)"] :special-form true})))
    (is (= "let\n  (let [bindings*] exprs*)\nSpecial Form"
           (render/doc-text {:name "let" :forms ["(let [bindings*] exprs*)"] :special-form true :macro true})))))

(deftest eldoc-text-test
  (are [info text] (= text (render/eldoc-text info))
    {:arglists ["[f]" "[f coll]"]} "[f] [f coll]"
    {:forms ["(if test then else?)"]} "(if test then else?)"
    {:name "cfg"} nil))

(deftest apropos-text-test
  (is (= "clojure.core/map     Returns a lazy sequence\nclojure.core/mapcat"
         (render/apropos-text [{:name "clojure.core/map" :doc "Returns a lazy sequence\nmore"}
                               {:name "clojure.core/mapcat"}]))))

(deftest error-text-test
  (is (= (str "clojure.lang.ExceptionInfo: boom\n"
              "  {:k 1,\n"
              "   :l 2}\n"
              "    at user/f (x.clj:1)\n"
              "Caused by: java.lang.ArithmeticException\n"
              "    at user/g (x.clj)\n"
              "    at java.lang.Object/wait (Unknown Source)")
         (render/error-text [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1,\n :l 2}\n"
                              :stacktrace [{:name "user/f" :file "x.clj" :line 1}
                                           {:name "nrepl.x/y" :file "y.clj" :line 3 :flags ["tooling"]}]}
                             {:class "java.lang.ArithmeticException"
                              :stacktrace [{:name "user/g" :file "x.clj"}
                                           {:name "user/g" :file "x.clj" :line 2 :flags ["dup"]}
                                           {:name "java.lang.Object/wait"}]}]))))
