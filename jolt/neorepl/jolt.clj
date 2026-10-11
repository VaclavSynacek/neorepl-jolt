(ns neorepl.jolt
  "Jolt entry point. TCP and POSIX terminals; no JLine or TLS required."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.main :as main]
            [neorepl.jolt-transport :as transport]
            [neorepl.posix :as posix]
            [neorepl.shutdown :as shutdown]
            [neorepl.xdg :as xdg]))

(defn context
  "The runtime seams are ordinary options, not process-wide redefinitions."
  []
  {:dir (System/getProperty "user.dir")
   :transport-factory transport/connect
   :cache-lock posix/call-with-lock
   :in-process? false
   :sessions-file (io/file (xdg/cache-home) "neorepl-jolt" "sessions.edn")
   ;; The backend honors :history-file, but computes its default without the
   ;; JVM-only MessageDigest used by the common REPL. EDN and JLine history
   ;; also deliberately live in separate runtime-specific directories.
   :history-dir nil
   :terminal {:history-dir (io/file (xdg/state-home) "neorepl-jolt" "history")
              :color? (str/blank? (System/getenv "NO_COLOR"))}
   :editor-command (some #(not-empty (System/getenv %)) ["VISUAL" "EDITOR"])})

(defn -main [& args]
  (let [status (main/run args (context))]
    ;; Closing the editor wakes the input thread during a signal shutdown.
    ;; Let Jolt's signal dispatcher finish exiting with 128+signal rather
    ;; than racing it with a successful System/exit from this thread.
    (when (shutdown/exiting?) @(promise))
    (flush)
    (System/exit status)))
