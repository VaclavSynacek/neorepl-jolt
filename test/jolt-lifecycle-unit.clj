;; Pure lifecycle/ownership checks: neither a terminal nor native libraries.
(require '[clojure.test :refer [deftest is run-tests]]
         '[neorepl.readline-lifecycle :as lifecycle])

(deftest phases
  (is (= :closed (:phase (reduce lifecycle/transition {:phase :opening}
                                [:idle :reading :idle :suspended :idle :closing :closed]))))
  (doseq [phase [:opening :idle :reading :suspended]]
    (is (= :closed (:phase (-> {:phase phase}
                              (lifecycle/transition :closing)
                              (lifecycle/transition :closed))))))
  (doseq [[from to] [[:opening :reading] [:reading :suspended]
                    [:suspended :reading] [:closing :idle] [:closed :reading]
                    [:idle :unknown] [:unknown :unknown]]]
    (is (thrown? clojure.lang.ExceptionInfo (lifecycle/transition {:phase from} to)))))

(deftest resource-order-and-idempotence
  (let [resources (atom []) calls (atom [])
        memory (lifecycle/track! resources :memory :buffer #(swap! calls conj :buffer))
        handler (lifecycle/track! resources :reference :handler #(swap! calls conj :handler))]
    (lifecycle/release! resources handler)
    (lifecycle/release! resources handler)
    (is (= [:handler] @calls))
    (is (= {:failures [] :reusable? true} (lifecycle/unwind! resources)))
    (is (= [:handler :buffer] @calls))
    (is (empty? @resources))
    (lifecycle/unwind! resources)
    (lifecycle/release! resources memory)
    (is (= [:handler :buffer] @calls))))

(deftest release-failure-can-unwind
  (let [resources (atom []) calls (atom 0)
        resource (lifecycle/track! resources :reference :handler
                   #(when (= 1 (swap! calls inc)) (throw (ex-info "first removal failed" {}))))]
    (is (thrown? clojure.lang.ExceptionInfo (lifecycle/release! resources resource)))
    (is (= 1 (count @resources)))
    (is (:reusable? (lifecycle/unwind! resources)))
    (is (= 2 @calls))
    (is (empty? @resources))))

(deftest failures-do-not-short-circuit
  (let [resources (atom []) calls (atom [])]
    (lifecycle/track! resources :memory :first #(swap! calls conj :first))
    (lifecycle/track! resources :memory :broken #(do (swap! calls conj :broken) (throw (ex-info "free failed" {}))))
    (lifecycle/track! resources :reference :global #(swap! calls conj :global))
    (lifecycle/track! resources :terminal :terminal #(swap! calls conj :terminal))
    (let [{:keys [failures reusable?]} (lifecycle/unwind! resources)]
      (is (= [:terminal :global :broken :first] @calls))
      (is (= [:broken] (mapv :resource failures)))
      (is reusable?)
      (is (empty? @resources))
      (lifecycle/unwind! resources)
      (is (= [:terminal :global :broken :first] @calls)))))

(deftest native-reference-failure-retains-memory
  (let [resources (atom []) calls (atom [])]
    (lifecycle/track! resources :memory :callback #(swap! calls conj :freed))
    (lifecycle/track! resources :reference :other-global #(swap! calls conj :restored))
    (lifecycle/track! resources :reference :bad-global #(throw (ex-info "cannot restore" {})))
    (lifecycle/track! resources :terminal :terminal #(swap! calls conj :terminal))
    (let [{:keys [failures reusable?]} (lifecycle/unwind! resources)]
      (is (= [:terminal :restored] @calls))
      (is (= [:bad-global] (mapv :resource failures)))
      (is (not reusable?))
      (is (= [:callback :bad-global] (mapv :name @resources))))))

(deftest terminal-failure-still-releases-native-memory
  (let [resources (atom []) freed (atom false)]
    (lifecycle/track! resources :memory :callback #(reset! freed true))
    (lifecycle/track! resources :reference :global (constantly nil))
    (lifecycle/track! resources :terminal :terminal #(throw (ex-info "terminal restore failed" {})))
    (let [{:keys [failures reusable?]} (lifecycle/unwind! resources)]
      (is @freed)
      (is (= [:terminal] (mapv :resource failures)))
      (is (not reusable?)))))

(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
