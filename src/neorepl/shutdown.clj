(ns neorepl.shutdown
  "Not leaving things behind when neorepl exits before it's done with them,
  on Ctrl-C or SIGTERM.")

(defonce ^:private cleanups
  ;; What runs when neorepl exits, in the order it was added, or ::exiting
  ;; once it's running.
  (atom []))

(defn- run-cleanups
  "Runs the cleanups latest first, the way they'd run had neorepl got to
  finish, so an evaluation gets interrupted before its session closes and
  that before its server stops. A JVM runs its shutdown hooks all at once."
  []
  (doseq [cleanup (rseq (first (reset-vals! cleanups ::exiting)))]
    ;; The rest still run.
    (try (cleanup) (catch Throwable _ nil))))

(defonce ^:private hook
  (delay (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable run-cleanups))))

(defn exiting?
  "Whether neorepl's exiting."
  []
  (= ::exiting @cleanups))

(defn add!
  "Has f run when neorepl exits, before whatever was added earlier, until
  it's removed. Returns what remove! takes, nil when neorepl's exiting
  already."
  [f]
  (try
    @hook
    (catch IllegalStateException _
      ;; Too late for the hook, as neorepl's exiting already.
      (reset! cleanups ::exiting)))
  ;; A fresh fn, so that f added twice takes removing twice.
  (let [cleanup (fn [] (f))]
    (when (vector? (swap! cleanups #(cond-> % (vector? %) (conj cleanup))))
      cleanup)))

(defn remove!
  "Takes what add! returned off what runs when neorepl exits. Returns
  whether it's up to the caller to clean up now: false when neorepl's
  exiting, which ran it or runs it."
  [cleanup]
  (or (nil? cleanup)
      (vector? (first (swap-vals! cleanups #(cond->> % (vector? %) (filterv (complement #{cleanup}))))))))

(defn call-with-cleanup
  "Calls f, then cleanup, which runs when neorepl exits before f returns
  too."
  [cleanup f]
  (let [added (add! cleanup)]
    (try
      (f)
      (finally
        (when (remove! added)
          (cleanup))))))
