(ns neorepl.test-server
  "An nREPL server and temporary directories for the tests that need them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.nrepl :as nrepl]
            [nrepl.server :as server])
  (:import (java.io File)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *port* nil)

(defn fixture
  "A :once fixture that runs the tests with an nREPL server listening on
  *port*. opts go to nrepl.server/start-server."
  [& opts]
  (fn [f]
    (let [srv (apply server/start-server :port 0 opts)]
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
