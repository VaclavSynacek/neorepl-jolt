(ns neorepl.test-server
  "An nREPL server and temporary directories for the tests that need them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.nrepl :as nrepl]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import (java.io File)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *port* nil)

(defn wrap-stuck
  "Never answers an evaluation of (stuck), the way shadow-cljs doesn't one
  that keeps the JavaScript runtime busy, and answers one of (slow) after a
  second. nREPL can't interrupt either, since they don't run in the
  session."
  [handler]
  (fn [{:keys [op code] :as msg}]
    (cond
      (not= "eval" op) (handler msg)
      (= "(stuck)" code) nil
      (= "(slow)" code) (future
                          (Thread/sleep 1000)
                          (transport/respond-to msg :ns "user" :value ":slow")
                          (transport/respond-to msg :status "done"))
      :else (handler msg))))

(middleware/set-descriptor! #'wrap-stuck {:expects #{"eval"}})

(defn fixture
  "A :once fixture that runs the tests with an nREPL server listening on
  *port*, with wrap-stuck unless opts, which go to
  nrepl.server/start-server, give it a :handler."
  [& opts]
  (fn [f]
    (let [srv (apply server/start-server :port 0 :handler (server/default-handler #'wrap-stuck) opts)]
      (try
        (binding [*port* (:port srv)]
          (f))
        (finally
          (server/stop-server srv))))))

(defmacro with-client
  "Evaluates body with sym bound to a client connected with opts."
  [[sym opts] & body]
  `(let [~sym (nrepl/connect ~opts)]
     (try ~@body (finally (nrepl/close ~sym)))))

(defn windows? []
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn lf
  "s with \n for the \r\n that println ends lines with on Windows."
  [s]
  (some-> s (str/replace "\r\n" "\n")))

(defn clojure-command
  "The command that runs Clojure the way the tests run, on babashka or the
  JVM, with their classpath, to which clojure.main's arguments go."
  []
  (let [;; Absolute, since the process may run somewhere else.
        classpath (->> (str/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
                       (map #(.getAbsolutePath (io/file %)))
                       (str/join File/pathSeparator))]
    (if (System/getProperty "babashka.version")
      ["bb" "-cp" classpath]
      [(str (io/file (System/getProperty "java.home") "bin" "java")) "-cp" classpath "clojure.main"])))

(defn free-port
  "A port nothing listens on, for the time being."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn call-with-temp-dir
  "Calls f with a fresh temporary directory, deleting it afterwards."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "neorepl-test" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        ;; Children come after their parents in file-seq.
        (run! #(.delete ^File %) (reverse (file-seq dir)))))))

(defn spit-file
  "Writes content to path under dir, creating the directories in between, and
  returns the file."
  ^File [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)
    f))
