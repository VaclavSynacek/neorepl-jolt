(ns neorepl.jolt
  "Experimental Jolt entry point. TCP only; no JLine or TLS required."
  (:require [clojure.walk :as walk]
            [neorepl.main :as main]
            [neorepl.sessions :as sessions]
            [neorepl.nrepl :as client]
            [nrepl.core :as nrepl]
            [nrepl.bencode :as bencode]
            [nrepl.transport :as transport]))

(defn- connect [& {:keys [host port socket] :or {host "127.0.0.1"}}]
  (when socket
    (throw (ex-info "Jolt adapter supports TCP connections only" {:socket socket})))
  (let [sock (java.net.Socket. host (int port))
        in (java.io.PushbackInputStream. (.getInputStream sock))
        out (.getOutputStream sock)]
    (transport/->FnTransport
     (fn [_timeout]
       ;; NeoREPL has a dedicated blocking reader and times requests itself.
       (walk/keywordize-keys
        (walk/postwalk (fn [x] (if (bytes? x) (String. x "UTF-8") x))
                       (bencode/read-bencode in))))
     (fn [msg]
       (locking out
         (bencode/write-bencode out msg)
         (.flush out)))
     #(.close sock))))

(defn -main [& args]
  ;; Jolt currently lacks FileChannel.lock. Use a fresh session per call
  ;; rather than pretending the on-disk session cache is concurrency-safe.
  (with-redefs [nrepl/connect connect
                sessions/ensure! (fn [_file c _name] (client/clone-session c))
                sessions/named (fn [_file _client] (sorted-map))
                sessions/close! (fn [_file _client _name] nil)
                sessions/interrupt (fn [_file _client _name] :unknown-session)]
    (let [status (main/run args {:dir (System/getProperty "user.dir")
                                 :history-dir nil
                                 :terminal {}
                                 :editor-command (or (System/getenv "VISUAL")
                                                     (System/getenv "EDITOR"))})]
      (flush)
      (System/exit status))))
