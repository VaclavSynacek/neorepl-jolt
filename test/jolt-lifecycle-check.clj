;; Run in a PTY: initialization rollback, re-opening, and close during input.
(require '[neorepl.readline :as r] '[neorepl.editor :as e] '[neorepl.lexer :as lexer])
(def opts {:highlight e/highlight :incomplete? lexer/incomplete? :eldoc? false})
(def initialize @#'neorepl.readline/initialize!)
(def failed? (atom false))
(with-redefs [r/initialize! (fn [ed] (initialize ed) (throw (ex-info "injected failure after terminal setup" {})))]
  (try (r/open opts)
       (catch Exception e (assert (= "injected failure after terminal setup" (ex-message e))) (reset! failed? true))))
(assert @failed?)
(println "EXPECTED_FAILURE")
;; Cleanup must release the global claim and every callback, permitting reuse.
(let [ed (r/open opts)
      read (future (r/read-code ed "test=> "))]
  (Thread/sleep 100)
  (r/close ed)
  (assert (nil? (deref read 2000 :timeout)))
  (r/close ed))
(println "LIFECYCLE_OK")
(System/exit 0)
