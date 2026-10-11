(ns neorepl.posix
  "Small POSIX seams Jolt's Java shims do not provide. No Readline dependency."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]))

(ffi/load-library)
(ffi/defcfn c-isatty "isatty" [:int] :int)
(ffi/defcfn c-open "open" [:string :int :int] :int)
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-flock "flock" [:int :int] :int :blocking)

(defn terminal? []
  (and (= 1 (c-isatty 0)) (= 1 (c-isatty 1))
       (not= "dumb" (System/getenv "TERM"))))

(defn call-with-lock
  "Run f under an exclusive lock on the cache's stable .lock file.
  Fail rather than silently updating a shared cache without a lock."
  [file f]
  (io/make-parents file)
  (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        fd (c-open (str file ".lock") (bit-or 2 (if mac? 512 64)) 384)]
    (when (neg? fd)
      (throw (ex-info (str "Cannot open session lock " file ".lock")
                      {:type :neorepl.sessions/lock-failed :errno (ffi/errno)})))
    (try
      (loop []
        (when-not (zero? (c-flock fd 2))
          (if (= 4 (ffi/errno))
            (recur)
            (throw (ex-info (str "Cannot lock " file ".lock")
                            {:type :neorepl.sessions/lock-failed :errno (ffi/errno)})))))
      (f)
      (finally
        ;; Closing the descriptor also releases the lock, including on errors.
        (c-close fd)))))
