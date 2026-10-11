(ns neorepl.jolt-transport
  "TCP transport using the upstream codec, without its JVM queue/TLS machinery.
  This adapter is supplied explicitly to neorepl's connection factory."
  (:require [clojure.walk :as walk]
            [nrepl.bencode :as bencode]
            [nrepl.transport :as transport]))

(defn connect [{:keys [host port socket] :or {host "127.0.0.1"}}]
  (when socket
    (throw (ex-info "Jolt supports TCP connections only" {:socket socket})))
  (let [sock (java.net.Socket. host (int port))]
    (try
      (let [in (java.io.PushbackInputStream. (.getInputStream sock))
            out (.getOutputStream sock)]
        (transport/->FnTransport
         (fn [_timeout]
           ;; The client has one blocking reader and times individual requests.
           (walk/keywordize-keys
            (walk/postwalk (fn [x] (if (bytes? x) (String. x "UTF-8") x))
                           (bencode/read-bencode in))))
         (fn [msg]
           (locking out
             (bencode/write-bencode out msg)
             (.flush out)))
         #(.close sock)))
      (catch Throwable e
        (.close sock)
        (throw e)))))
