(ns neorepl.sessions
  "Named sessions that outlive the process that created them, so separate CLI
  calls can share *ns*, *1, *e and the like. nREPL sessions have no names,
  so the ids live in a cache file, keyed by server address and name."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [neorepl.nrepl :as nrepl])
  (:import (java.io File IOException)
           (java.nio.file CopyOption Files StandardCopyOption)))

(defn cache-file
  "sessions.edn under the XDG cache directory: xdg-cache-home when it's an
  absolute path, as the spec says, ~/.cache otherwise."
  ^File [xdg-cache-home user-home]
  (io/file (if (and xdg-cache-home (.isAbsolute (io/file xdg-cache-home)))
             xdg-cache-home
             (io/file user-home ".cache"))
           "neorepl" "sessions.edn"))

(defn default-file ^File []
  (cache-file (System/getenv "XDG_CACHE_HOME") (System/getProperty "user.home")))

(defn- read-cache [^File file]
  (let [cache (when (.isFile file)
                ;; It's only a cache, so a broken one counts as empty.
                (try (edn/read-string (slurp file)) (catch Exception _ nil)))]
    (if (map? cache) cache {})))

(defn- write-cache!
  "Replaces file in one go, so concurrent readers never see half of it."
  [^File file cache]
  (try
    (io/make-parents file)
    (let [tmp (File/createTempFile "sessions" ".edn" (.getParentFile file))]
      (try
        (spit tmp (pr-str cache))
        (Files/move (.toPath tmp) (.toPath file)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (finally
          (.delete tmp))))
    ;; The session works without the cache, it just won't be there next time.
    (catch IOException e
      (binding [*out* *err*]
        (println (str "neorepl: Couldn't save the session in " file ": " (ex-message e)))))))

(defn- live? [client session]
  ;; Servers without ls-sessions get the benefit of the doubt.
  (or (not (contains? (:ops client) "ls-sessions"))
      (contains? (set (some :sessions (nrepl/request client {:op "ls-sessions"}))) session)))

(defn- cached [file client name]
  (get-in (read-cache file) [(:address client) name]))

(defn ensure!
  "The id of the session called name on client's server, as cached in file.
  Clones and caches a new one when there's none yet or the server doesn't
  know the cached one anymore, typically because it restarted."
  [file client name]
  (let [session (cached file client name)]
    (if (and session (live? client session))
      session
      (let [session (nrepl/clone-session client)]
        ;; Read again, to keep what other processes wrote in the meantime.
        (write-cache! file (assoc-in (read-cache file) [(:address client) name] session))
        session))))

(defn interrupt
  "Interrupts whatever runs in the session called name, returning how that
  went as nrepl/interrupt does."
  [file client name]
  ;; No need to check the session is alive: the server says so if it isn't.
  (if-let [session (cached file client name)]
    (nrepl/interrupt client session nil)
    :unknown-session))
