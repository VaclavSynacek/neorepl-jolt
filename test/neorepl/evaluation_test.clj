(ns neorepl.evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.evaluation :as evaluation]))

(deftest text-printer-test
  (let [printed (fn [& responses]
                  (let [print-response (evaluation/text-printer)]
                    ;; stdout and stderr on one terminal.
                    (with-out-str
                      (binding [*err* *out*]
                        (doseq [response responses]
                          (if (= :prompt response)
                            (do (print "user=> ")
                                (print-response))
                            (print-response response)))))))]
    (testing "each value goes on a line of its own"
      (is (= "hi\nnil\n3\n" (printed {:out "hi\n"} {:value "nil"} {:value "3"})))
      (is (= "partial\n3\n" (printed {:out "partial"} {:value "3"}))))
    (testing "the value after a prompt goes right after it, whatever came before"
      (is (= "partialboom\nuser=> 3\n" (printed {:out "partial"} {:err "boom\n"} :prompt {:value "3"}))))))
