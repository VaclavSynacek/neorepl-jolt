(ns neorepl.readline-lifecycle
  "Readline's owner-thread lifecycle and resource ledger. No native calls.")

(def ^:private transitions
  {:opening #{:idle :closing}
   :idle #{:reading :suspended :closing}
   :reading #{:idle :closing}
   :suspended #{:idle :closing}
   :closing #{:closed}
   :closed #{}})

(defn transition
  "Move to phase, rejecting invalid transitions. Only the owner changes phase."
  [state phase]
  (let [from (:phase state)]
    (when-not (and (contains? transitions from) (contains? transitions phase)
                   (or (= from phase) (contains? (get transitions from) phase)))
      (throw (ex-info "Invalid Readline lifecycle transition" {:from from :to phase})))
    (assoc state :phase phase)))

(defn track!
  "Register an acquired resource immediately. Releases run in reverse order.
  :reference restorations precede the :memory they reference; :terminal is
  restored independently. A failed reference restoration retains memory
  rather than freeing a callback that native code might still invoke."
  [resources kind name release]
  (let [token (Object.)]
    (swap! resources conj {:token token :kind kind :name name :release release})
    token))

(defn release!
  "Release a temporary resource, leaving it tracked if release throws so
  final cleanup can retry. Called only on the owner thread."
  [resources token]
  (when-let [resource (some #(when (identical? token (:token %)) %) @resources)]
    ((:release resource))
    (swap! resources #(vec (remove (fn [r] (identical? token (:token r))) %)))))

(defn unwind!
  "Attempt every restoration, even after a failure. Returns failures and
  whether another editor may safely acquire the terminal/native globals.
  Unsafe native references retain their allocations and prevent re-opening;
  memory-release failures alone do not leave native references installed."
  [resources]
  (loop [[resource & more] (reverse @resources)
         failures [] retained [] references-safe? true terminal-safe? true]
    (if resource
      (if (and (= :memory (:kind resource)) (not references-safe?))
        (recur more failures (conj retained resource) references-safe? terminal-safe?)
        (let [error (try ((:release resource)) nil (catch Throwable e e))]
          (recur more
                 (cond-> failures error (conj {:resource (:name resource) :error error}))
                 ;; A memory release may have freed before throwing. Never
                 ;; retry it; uncertain native references retain unattempted
                 ;; allocations, not potentially already-freed pointers.
                 (cond-> retained (and error (not= :memory (:kind resource))) (conj resource))
                 (and references-safe? (not (and error (= :reference (:kind resource)))))
                 (and terminal-safe? (not (and error (= :terminal (:kind resource))))))))
      (do
        (reset! resources (vec (reverse retained)))
        {:failures failures :reusable? (and references-safe? terminal-safe?)}))))
