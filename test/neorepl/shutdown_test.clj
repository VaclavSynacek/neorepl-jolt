(ns neorepl.shutdown-test
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.shutdown :as shutdown]
            [neorepl.test-server :as test-server :refer [lf]]))

(deftest remove-test
  (testing "what's removed before neorepl exits is the caller's to clean up"
    (is (true? (shutdown/remove! (shutdown/add! (fn []))))))
  (testing "and so is what couldn't be added"
    (is (true? (shutdown/remove! nil)))))

(deftest exit-test
  (testing "on exit, the cleanups run latest first, and one failing doesn't stop the rest"
    ;; Keywords, since strings lose their quotes on the way to a process on
    ;; Windows.
    (let [process (.start (ProcessBuilder. ^java.util.List
                                           (conj (test-server/clojure-command)
                                                 "-e" (pr-str '(do (require 'neorepl.shutdown)
                                                                   (neorepl.shutdown/add! #(println :first))
                                                                   (neorepl.shutdown/add! #(throw (Exception.)))
                                                                   (neorepl.shutdown/add! #(println :second))
                                                                   (neorepl.shutdown/remove! (neorepl.shutdown/add! #(println :removed)))
                                                                   ;; Not a value for -e to print.
                                                                   nil)))))]
      (is (= ":second\n:first\n" (lf (slurp (.getInputStream process)))))
      (is (zero? (.waitFor process))))))
