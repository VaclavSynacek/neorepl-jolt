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

(defn- live-sessions
  "A predicate telling whether client's server knows a session id. Servers
  without ls-sessions get the benefit of the doubt."
  [client]
  (if (contains? (:ops client) "ls-sessions")
    (set (some :sessions (nrepl/request client {:op "ls-sessions"})))
    any?))

(defn named
  "The sessions on client's server that have a name in file, as a sorted map
  of names to ids. Sessions the server doesn't know anymore, typically
  because it restarted, are left out."
  [file client]
  (let [live? (live-sessions client)]
    (into (sorted-map)
          (filter (comp live? val))
          (get (read-cache file) (:address client)))))

(defn- cached [file client name]
  (get-in (read-cache file) [(:address client) name]))

(defn ensure!
  "The id of the session called name on client's server, as cached in file.
  Clones and caches a new one when there's none yet or the server doesn't
  know the cached one anymore."
  [file client name]
  (or (get (named file client) name)
      (let [session (nrepl/clone-session client)]
        ;; Read again, to keep what other processes wrote in the meantime.
        (write-cache! file (assoc-in (read-cache file) [(:address client) name] session))
        session)))

(defn interrupt
  "Interrupts whatever runs in the session called name, returning how that
  went as nrepl/interrupt does."
  [file client name]
  ;; No need to check the session is alive: the server says so if it isn't.
  (if-let [session (cached file client name)]
    (nrepl/interrupt client session nil)
    :unknown-session))

(defn close!
  "Closes the session called name on client's server and drops it from file.
  Returns its id, or nil when there's no such session."
  [file client name]
  (when-let [session (cached file client name)]
    (let [closed? (nrepl/close-session client session)]
      (write-cache! file (update (read-cache file) (:address client) dissoc name))
      (when closed? session))))
