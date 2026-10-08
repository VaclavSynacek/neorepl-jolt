(ns neorepl.shutdown
  "Not leaving things behind when neorepl exits before it's done with them,
  on Ctrl-C or SIGTERM.")

(defn call-with-cleanup
  "Calls f, then cleanup, which runs when neorepl exits before f returns
  too."
  [cleanup f]
  (let [hook (Thread. ^Runnable cleanup)
        runtime (Runtime/getRuntime)]
    (.addShutdownHook runtime hook)
    (try
      (f)
      (finally
        ;; When neorepl's exiting already, the hook does it.
        (when (try
                (.removeShutdownHook runtime hook)
                (catch IllegalStateException _ false))
          (cleanup))))))
