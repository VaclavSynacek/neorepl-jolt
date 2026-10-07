(ns neorepl.lexer-test
  (:require [clojure.test :refer [are deftest is testing]]
            [neorepl.lexer :as lexer]))

(defn- pairs
  "s as [type text] pairs."
  [s]
  (mapv (juxt :type #(lexer/text s %)) (lexer/tokenize s)))

(defn- text-at
  "The text of the token (f lexed-s cursor) finds, if any."
  [f s cursor]
  (some->> (f (lexer/lex s) cursor) (lexer/text s)))

(deftest tokenize-test
  (testing "lists, symbols, numbers and the like"
    (is (= [[:open "("] [:symbol "+"] [:number "1"] [:number "-2.5"] [:number "1/2"]
            [:constant "nil"] [:keyword "::a/b"] [:close ")"]]
           (pairs "(+ 1, -2.5 1/2 nil ::a/b)"))))

  (testing "strings, including escaped quotes and newlines"
    (is (= [[:string "\"a \\\" b\""] [:string "\"x\ny\""]]
           (pairs "\"a \\\" b\" \"x\ny\""))))

  (testing "unterminated strings run to the end of the input"
    (is (= [[:open "("] [:symbol "str"] [:open-string "\"abc )"]]
           (pairs "(str \"abc )"))))

  (testing "char literals swallow the character after the backslash"
    (is (= [[:char "\\("] [:char "\\)"] [:char "\\\""] [:char "\\;"] [:char "\\newline"]
            [:char "\\u00e9"] [:char "\\\\"]]
           (pairs "\\( \\) \\\" \\; \\newline \\u00e9 \\\\")))
    (is (= [[:char "\\a"] [:char "\\b"] [:close ")"]]
           (pairs "\\a\\b)"))))

  (testing "comments run to the end of the line"
    (is (= [[:open "("] [:comment "; a (comment"] [:close ")"]]
           (pairs "(; a (comment\n)")))
    (is (= [[:comment "#!/usr/bin/env bb"] [:number "1"]]
           (pairs "#!/usr/bin/env bb\n1"))))

  (testing "dispatch forms"
    (is (= [[:open "#{"] [:open "#("] [:symbol "%"] [:close ")"] [:close "}"]
            [:regex "#\"\\d+\""] [:discard "#_"] [:symbol "x"]]
           (pairs "#{#(%)} #\"\\d+\" #_x")))
    (is (= [[:reader-macro "#?"] [:open "("] [:keyword ":clj"] [:number "1"] [:close ")"]
            [:reader-macro "#?@"] [:open "("] [:close ")"]
            [:reader-macro "#:ns"] [:open "{"] [:close "}"]
            [:reader-macro "#inst"] [:string "\"2026\""] [:number "##Inf"] [:macro-char "#'"] [:symbol "foo"]]
           (pairs "#?(:clj 1) #?@() #:ns{} #inst \"2026\" ##Inf #'foo"))))

  (testing "quoting and other macro characters"
    (is (= [[:macro-char "'"] [:symbol "a"] [:macro-char "`"] [:open "("] [:macro-char "~"]
            [:macro-char "@"] [:symbol "xs"] [:close ")"] [:macro-char "^"] [:keyword ":private"]]
           (pairs "'a `(~@xs) ^:private"))))

  (testing "terminating macro characters end symbols; ' and # don't"
    (is (= [[:symbol "foo"] [:macro-char "@"] [:symbol "bar"] [:symbol "x'"] [:symbol "y#"]]
           (pairs "foo@bar x' y#"))))

  (testing "offsets point into the input"
    (is (= [{:type :open :start 0 :end 1} {:type :symbol :start 1 :end 4}]
           (lexer/tokenize "(abc")))))

(deftest incomplete?-test
  (are [s] (lexer/incomplete? (lexer/lex s))
    "("
    "(+ 1"
    "(let [x 1]"
    "{:a [1 2"
    "#{"
    "#(inc %"
    "\"unterminated"
    "(str \"abc )"
    "(+ 1 ; and a comment"
    "#\"open regex"
    "'"
    "(foo) '"
    "`"
    "@"
    "^:private"
    "^"
    "^{:doc \"x\"}"
    "''"
    "#_"
    "(+ 1 2) #_"
    "#'"
    "#?"
    "#inst"
    "#:ns")
  (are [s] (not (lexer/incomplete? (lexer/lex s)))
    ""
    "   "
    "42"
    "(+ 1 2)"
    "(str \")\")"
    "[\\( \\[ \\{]"
    "(+ 1 2))"
    ";; just a comment"
    "#?(:clj 1 :cljs 2)"
    "(do\n  1\n  2)"
    "(let [x 1)"
    "[(]"
    "{:a 1]"
    "#'foo"
    "##Inf"
    "'sym"
    "''sym"
    "^:private x"
    "^{:doc \"x\"} x"
    "#_x 1"
    "#?(:clj 1)"
    "#inst \"2026\""))

(deftest symbol-at-test
  (let [s "(map inc :kw)"]
    (are [cursor expected] (= expected (text-at lexer/symbol-at s cursor))
      0 nil
      1 "map"
      3 "map"
      4 "map"
      5 "inc"
      9 ":kw"
      12 ":kw"
      13 nil))
  (testing "past var quotes, and on constants"
    (is (= "ma" (text-at lexer/symbol-at "#'ma" 4)))
    (is (= "nil" (text-at lexer/symbol-at "nil" 3)))))

(deftest enclosing-op-test
  (are [s cursor expected] (= expected (text-at lexer/enclosing-op s cursor))
    "(map inc " 9 "map"
    "(map inc xs)" 5 "map"
    "(map inc xs)" 12 nil
    "(map (fn [x] " 13 "fn"
    "(map (fn [x] x) " 16 "map"
    "(let [x " 8 "let"
    "(assoc {:a " 11 "assoc"
    "#(inc " 6 "inc"
    "((comp inc) " 12 nil
    "(:k m" 5 nil
    "inc" 3 nil
    "(ma" 3 "ma"
    "(let [x 1) (" 12 nil))
