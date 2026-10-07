(ns neorepl.test-runner
  "Runs the tests the same way on the JVM and on babashka. Without arguments it
  runs every namespace under test/ ending in -test; otherwise just the
  namespaces given."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test"))
       (map #(str/replace (str %) "\\" "/"))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (str/replace #"^test/|\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& namespaces]
  (let [namespaces (if (seq namespaces) (map symbol namespaces) (test-namespaces))]
    (apply require namespaces)
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
