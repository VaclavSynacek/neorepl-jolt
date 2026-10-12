;; PTY-only probes: lifecycle, partial setup, cleanup failures and waiters.
(require '[neorepl.readline :as r] '[neorepl.editor :as e] '[neorepl.lexer :as lexer]
         '[neorepl.terminal-bridge :as bridge] '[jolt.ffi :as ffi])

(def opts {:highlight e/highlight :incomplete? lexer/incomplete? :eldoc? false})
(def initialize @#'neorepl.readline/initialize!)

(defn wait-for [predicate]
  (loop [n 0]
    (when-not (predicate)
      (assert (< n 200) "Timed out waiting for owner state")
      (Thread/sleep 10)
      (recur (inc n)))))
(defn phase [ed] (:phase @(:state ed)))
(defn read-result [f]
  (try (f) (catch Throwable e {:error e})))
(defn completed [f]
  (let [value (deref f 2000 ::timeout)]
    (assert (not= ::timeout value) "Waiting caller was not released")
    value))
(defn expect-failure [f]
  (let [result (read-result f)]
    (assert (:error result) "Expected an error")
    (:error result)))
(defn closed [ed]
  (assert (= :closed (phase ed)))
  (assert (not @(:accepting? ed)))
  (assert (nil? (:read @(:state ed))))
  (assert (empty? (:deferred @(:state ed)))))

;; Failure after full terminal setup must unwind every allocation and claim.
(let [seen (atom nil)]
  (with-redefs [r/initialize! (fn [ed] (reset! seen ed) (initialize ed)
                              (throw (ex-info "injected failure after terminal setup" {})))]
    (assert (= "injected failure after terminal setup" (ex-message (expect-failure #(r/open opts))))))
  (closed @seen)
  (assert (empty? @(:resources @seen))))
(println "EXPECTED_FAILURE")

;; Throw after a key/global has already been mutated, not just before setup.
(doseq [point [:key :global]]
  (let [seen (atom nil) writes (atom 0) freed (atom 0)
        bind r/c-bind write ffi/write free ffi/free-callable]
    (with-redefs [r/initialize! (fn [ed] (reset! seen ed) (initialize ed))
                  r/c-bind (fn [key callback]
                             (let [result (bind key callback)]
                               (when (and (= point :key) (= 2 (swap! writes inc)))
                                 (throw (ex-info "partial key setup" {})))
                               result))
                  ffi/write (fn [p type value offset]
                              (let [result (write p type value offset)]
                                (when (and (= point :global) (= 2 (swap! writes inc)))
                                  (throw (ex-info "partial global setup" {})))
                                result))
                  ffi/free-callable (fn [callback] (swap! freed inc) (free callback))]
      (expect-failure #(r/open opts)))
    (closed @seen)
    (assert (= 10 @freed) "Partial initialization leaked a callback")
    (assert (empty? @(:resources @seen)))
    (r/close (r/open opts))))

;; Active read completion/normal repeated close.
(let [ed (r/open opts)
      _ (assert (= :idle (phase ed)))
      read (future (r/read-code ed "test=> "))]
  (wait-for #(= :reading (phase ed)))
  (r/close ed)
  (assert (nil? (completed read)))
  (closed ed)
  (assert (empty? @(:resources ed)))
  (r/close ed))

;; Normal suspend/resume preserves the lease and does not accumulate terminal
;; resources. Callback-mutated completion settings also belong to this lease.
(let [global @#'neorepl.readline/global
      names ["rl_attempted_completion_over" "rl_completion_suppress_append"]
      saved (into {} (map (fn [name] [name (ffi/read (global name) :int)]) names))
      ed (r/open opts) resource-count (count @(:resources ed))]
  (dotimes [_ 3]
    (assert (= :handed-over
               (r/suspended ed #(do (assert (= :suspended (phase ed))) :handed-over))))
    (assert (= :idle (phase ed)))
    (assert (= resource-count (count @(:resources ed)))))
  (doseq [name names] (ffi/write (global name) :int (bit-xor 1 (get saved name)) 0))
  (r/close ed)
  (doseq [name names] (assert (= (get saved name) (ffi/read (global name) :int)))))

;; Read setup failing after the native callback was installed must also close,
;; not leave :reading behind with a promise nobody can ever fulfill.
(let [ed (r/open opts) input-mode bridge/input-mode]
  (with-redefs [bridge/input-mode (fn [active]
                                   (let [result (input-mode active)]
                                     (if (= active 1) -1 result)))]
    (expect-failure #(r/read-code ed "failure=> "))
    (expect-failure #(r/close ed)))
  (closed ed)
  (assert (empty? @(:resources ed)))
  (r/close (r/open opts)))

;; Suspension defers operations; close must release all their callers, even
;; when one callback release throws AFTER freeing it. Never retry that free.
(let [ed (r/open opts) handover (promise) resume (promise)
      suspended (future (r/suspended ed #(do (deliver handover true) @resume)))
      free ffi/free-callable freed (atom 0)]
  (assert (true? (deref handover 2000 false)))
  (assert (= :suspended (phase ed)))
  (let [output (future (read-result #(r/print-text ed nil "deferred output")))
        input (future (read-result #(r/read-code ed "deferred=> ")))]
    (wait-for #(= 2 (count (:deferred @(:state ed)))))
    (with-redefs [ffi/free-callable (fn [callback]
                                    (free callback)
                                    (when (= 1 (swap! freed inc)) (throw (ex-info "injected free failure" {}))))]
      (let [error (expect-failure #(r/close ed))]
        (assert (= :neorepl.readline/cleanup-failed (:type (ex-data error))))
        (assert (not (:restart-required? (ex-data error))))))
    (assert (= 10 @freed) "One cleanup failure skipped later frees")
    (closed ed)
    (assert (:error (completed output)))
    (assert (:error (completed input)))
    (expect-failure #(r/close ed))
    (assert (= 10 @freed) "Repeated close retried a possibly completed free")
    (deliver resume true)
    (assert (true? (completed suspended)))
    ;; Native references were restored; the failed free doesn't prevent reuse.
    (r/close (r/open opts))))

;; A fatal owner error must reject accepted-but-not-yet-run commands. Their
;; callers must finish, and no native work should run after the fatal failure.
(let [ed (r/open opts) entered (promise) release (promise)
      owner-call @#'neorepl.readline/call!
      blocker (future (owner-call ed #(do (deliver entered true) @release
                                         (swap! (:state ed) assoc :fatal (ex-info "injected owner failure" {})))))]
  (assert (true? (deref entered 2000 false)))
  (let [output (future (read-result #(r/print-text ed nil "MUST NOT PRINT")))
        input (future (read-result #(r/read-code ed "must-not-read=> ")))]
    (wait-for #(= 2 (.size (:queue ed))))
    (deliver release true)
    (completed blocker)
    (expect-failure #(r/close ed))
    (assert (:error (completed output)))
    (assert (:error (completed input)))
    (closed ed)
    (assert (empty? @(:resources ed)))
    (r/close (r/open opts))))

;; An error in history reporting itself must not strand an active input caller.
(let [ed (r/open opts)
      read (future (read-result #(r/read-code ed "reporting=> ")))]
  (wait-for #(= :reading (phase ed)))
  (with-redefs [r/save-history! (fn [_] (throw (AssertionError. "injected history failure")))]
    (expect-failure #(r/close ed)))
  (assert (:error (completed read)))
  (closed ed)
  (r/close (r/open opts)))

;; The owner can request closure from INSIDE a foreign redisplay callback.
;; Waiting there for stopped would deadlock, and freeing there would be unsafe.
(let [holder (atom nil) requested (atom false)
      ed (r/open (assoc opts :eldoc? true
                       :eldoc (fn [_text _point] (reset! requested true) (r/close @holder) nil)))]
  (reset! holder ed)
  (let [read (future (r/read-code ed "callback-close=> "))]
    (assert (nil? (completed read)))
    (assert @requested)
    (r/close ed)
    (closed ed)
    (assert (empty? @(:resources ed)))))

;; A native reference that cannot be restored must NOT be freed. Still try
;; every other restoration, release requests, and forbid an unsafe re-open.
;; This probe is last: the deliberately poisoned lease lasts until process exit.
(let [ed (r/open opts) global @#'neorepl.readline/global
      bind r/c-bind-in-map free ffi/free-callable restored (atom 0) freed (atom 0)]
  (with-redefs [r/global (fn [name] (if (= name "rl_redisplay_function")
                                    (throw (ex-info "injected restore failure" {})) (global name)))
                r/c-bind-in-map (fn [key value keymap] (swap! restored inc) (bind key value keymap))
                ffi/free-callable (fn [callback] (swap! freed inc) (free callback))]
    (let [error (expect-failure #(r/close ed))]
      (assert (:restart-required? (ex-data error)))))
  (closed ed)
  (assert (= 8 @restored) "Failed global restore skipped key restorations")
  (assert (zero? @freed) "Freed a callback still referenced by Readline")
  (assert (= 11 (count (filter #(= :memory (:kind %)) @(:resources ed)))))
  (expect-failure #(r/open opts)))

(println "LIFECYCLE_OK")
(System/exit 0)
