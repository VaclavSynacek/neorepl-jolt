;; Jolt-only: jolt -A:jolt test/jolt-editor-check.clj
(require '[clojure.test :refer [deftest is run-tests]]
         '[clojure.string :as str]
         '[neorepl.editor :as editor])

(deftest highlighting-preserves-text
  (doseq [text ["" "(+ 1 2)" "{:a 42}" "  ; comment\n\"hello\""
                "[\"😀\" :žluťoučký]" "(map\n  inc [1 2])"]]
    (is (= text (str/replace (editor/highlight text) #"\u001b\[[0-9;]*m" "")))))

(deftest semantic-colors
  (is (str/includes? (editor/highlight "{:a 42}") "\u001b[36m:a\u001b[0m"))
  (is (str/includes? (editor/highlight "{:a 42}") "\u001b[35m42\u001b[0m")))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
