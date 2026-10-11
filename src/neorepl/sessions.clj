(ns neorepl.sessions
  "Named sessions that outlive the process that created them, so separate CLI
  calls can share *ns*, *1, *e and the like. nREPL sessions have no names,
  so the ids live in a cache file, keyed by server address and name."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [neorepl.nrepl :as nrepl])
  (:import (java.io File IOException)
           (java.nio.channels FileChannel)
           (java.nio.file CopyOption Files OpenOption StandardCopyOption StandardOpenOption)))

(defn- read-cache [^File file]
  (let [cache (when (.isFile file)
                ;; It's only a cache, so a broken one counts as empty.
                (try (edn/read-string (slurp file)) (catch Exception _ nil)))]
    (if (map? cache) cache {})))

(defn- write-cache!
  "Replaces file in one go, so concurrent readers never see half of it."
  [^File file cache]
  (try
    ;; Some non-JVM Files/move shims replace empty directories. A cache is
    ;; always a regular file; never let an invalid path clobber a directory.
    (when (.isDirectory file) (throw (IOException. "Session cache path is a directory")))
    (io/make-parents file)
    (let [tmp (File/createTempFile "sessions" ".edn" (.getParentFile file))]
      (try
        (spit tmp (pr-str cache))
        (Files/move (.toPath tmp) (.toPath file)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        true
        (finally
          (.delete tmp))))
    ;; The session works without the cache, it just won't be there next time.
    (catch IOException e
      (binding [*out* *err*]
        (println (str "neorepl: Couldn't save the session in " file ": " (ex-message e))))
      false)))

(def ^:private in-process
  "What threads hold while one of them has the cache locked: file locks keep
  other processes out, and fail for a thread whose process holds one already."
  (Object.))

(defn- jvm-lock
  "Calls f holding a lock on file's lock file, so CLI calls running at the
  same time update the cache one after the other instead of dropping each
  other's sessions. When there's no locking it (e.g. on a file system
  without locks), f runs all the same, as sessions work without the cache."
  [^File file f]
  (locking in-process
    (let [^FileChannel channel (try
                                 (io/make-parents file)
                                 (FileChannel/open (.toPath (io/file (str file ".lock")))
                                                   (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                                 (catch IOException _))]
      (try
        (when channel
          (try
            (.lock channel)
            (catch IOException _)))
        (f)
        (finally
          ;; Closing the channel releases the lock.
          (some-> channel .close))))))

(defn- call-with-lock [file client f]
  (if-let [cache-lock (:cache-lock client)]
    (locking in-process (cache-lock file f))
    (jvm-lock file f)))

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
  (let [before (cached file client name)]
    (if (and before ((live-sessions client) before))
      before
      (call-with-lock file client
        #(let [cache (read-cache file)
               now (get-in cache [(:address client) name])]
           ;; Another call may have cached a new one in the meantime.
           (if (and now (not= now before))
             now
             (let [session (nrepl/clone-session client)
                   saved? (write-cache! file (assoc-in cache [(:address client) name] session))]
               ;; A strict runtime lock adapter must not leave an untracked
               ;; session behind when its supposedly persistent cache fails.
               (when (and (:cache-lock client) (not saved?))
                 (try (nrepl/close-session client session) (catch Exception _ nil))
                 (throw (ex-info "Cannot persist the named session; cleanup of its new session was attempted"
                                 {:type ::cache-failed :file (str file)})))
               session)))))))

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
  (call-with-lock file client
    #(let [cache (read-cache file)]
       (when-let [session (get-in cache [(:address client) name])]
         ;; Resolve and remove the same id under one lock: a concurrent ensure!
         ;; must not replace it between close and cache removal.
         (let [closed? (nrepl/close-session client session)
               saved? (write-cache! file (update cache (:address client) dissoc name))]
           (when (and (:cache-lock client) (not saved?))
             (throw (ex-info "Session closed, but its cache entry could not be removed"
                             {:type ::cache-failed :file (str file)})))
           (when closed? session))))))
