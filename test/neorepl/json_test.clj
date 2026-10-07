(ns neorepl.json-test
  (:require [clojure.test :refer [are deftest is]]
            [neorepl.json :as json]))

(deftest write-str-test
  (are [x s] (= s (json/write-str x))
    nil "null"
    true "true"
    false "false"
    42 "42"
    -1 "-1"
    "plain" "\"plain\""
    "say \"hi\"\\" "\"say \\\"hi\\\"\\\\\""
    "a\nb\r\tc" "\"a\\nb\\r\\tc\""
    "\u0000\u001b" "\"\\u0000\\u001b\""
    "日本語 é" "\"日本語 é\""
    :status "\"status\""
    'sym "\"sym\""
    [] "[]"
    ["1" 2 nil] "[\"1\",2,null]"
    '("a") "[\"a\"]"
    {} "{}"
    {:root-ex "x" "k" [1]} "{\"root-ex\":\"x\",\"k\":[1]}"
    {:a/b {:c true}} "{\"a/b\":{\"c\":true}}")
  (is (thrown? clojure.lang.ExceptionInfo (json/write-str 1.5))))
