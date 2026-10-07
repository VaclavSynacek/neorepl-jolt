(ns neorepl.discovery
  "Finds nREPL servers through the port files they write into the directory
  they were started in."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.nrepl :as nrepl])
  (:import (java.io File)))

(def port-files
  "Where servers write their port, relative to the project directory: nREPL,
  Leiningen, babashka and friends use the first, shadow-cljs the second."
  [".nrepl-port" ".shadow-cljs/nrepl.port"])

(defn- read-port [^File f]
  (when (.isFile f)
    (try
      (Long/parseLong (str/trim (slurp f)))
      ;; Unreadable, or not a number: either way there's no port in it.
      (catch Exception _ nil))))

(defn find-ports
  "The port files in dir and its parents, nearest first, as maps with the
  :file and the :port in it. Stale files included."
  [dir]
  (for [^File d (take-while some? (iterate #(.getParentFile ^File %) (.getCanonicalFile (io/file dir))))
        name port-files
        :let [f (io/file d name)
              port (read-port f)]
        :when port]
    {:file (str f) :port port}))

(defn connect
  "Connects to the nearest server that answers, going by the port files in
  dir and its parents."
  [dir]
  (let [found (find-ports dir)]
    (or (some (fn [{:keys [port]}]
                (try
                  (nrepl/connect {:port port})
                  (catch clojure.lang.ExceptionInfo e
                    (when-not (= ::nrepl/no-server (:type (ex-data e)))
                      (throw e)))))
              found)
        (throw (ex-info (if (seq found)
                          (str "No nREPL server answering at the ports in "
                               (str/join ", " (map :file found)))
                          (str "No nREPL port file (" (str/join ", " port-files) ") in "
                               (.getCanonicalPath (io/file dir)) " or its parents"))
                        {:type ::nrepl/no-server :ports found})))))
