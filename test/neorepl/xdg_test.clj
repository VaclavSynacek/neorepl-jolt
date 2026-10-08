(ns neorepl.xdg-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [are deftest is testing]]
            [neorepl.xdg :as xdg]))

(deftest base-dir-test
  (let [home (.getAbsolutePath (io/file "home"))
        xdg (.getAbsolutePath (io/file "xdg"))]
    (is (= (io/file xdg) (xdg/base-dir xdg home ".cache")))
    (testing "the default under home when the variable is unset, empty or relative"
      (are [value] (= (io/file home ".cache") (xdg/base-dir value home ".cache"))
        nil
        ""
        "cache"))))
