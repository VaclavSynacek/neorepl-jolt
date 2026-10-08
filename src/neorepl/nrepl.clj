(ns neorepl.nrepl
  "The client side of the nREPL protocol on top of nrepl.core's transports.

  A client owns one connection and one reader thread. The reader hands every
  response to the handler registered for its message id. Responses nobody
  claims go to the :on-unclaimed handler given to `connect`: typically output
  that a future prints after the evaluation that started it is done.

  Values stay strings. Reading them on the client would break on truncated
  output and on tagged literals the client doesn't know about."
  (:require [nrepl.core :as nrepl]
            [nrepl.misc :as misc]
            [nrepl.transport :as transport])
  (:import (java.io Closeable)
           (java.net URI URISyntaxException)))

(def default-port
  "The port nREPL URLs without one point at, same as in nrepl.core."
  7888)

(defn parse-url
  "Connection options for an nREPL URL: nrepl://host[:port] or the
  nrepl+unix:/path form servers listening on a unix domain socket print."
  [^String url]
  (if-let [[_ path] (re-matches #"(?i)nrepl\+unix:(?!//)(.+)" url)]
    ;; Servers percent-encode the path when they print the URL.
    {:socket (try (.getSchemeSpecificPart (URI. url))
                  (catch URISyntaxException _ path))}
    (if-let [[_ ipv6 host port] (re-matches #"(?i)nrepl://(?:\[([^\]]+)\]|([^:/\[\]]+))(?::(\d+))?/?" url)]
      {:host (or ipv6 host)
       :port (if port (Long/parseLong port) default-port)}
      (throw (ex-info (str "Unsupported nREPL URL: " url) {:url url})))))

(defn status?
  "True when response carries status (a string such as \"done\")."
  [response status]
  (boolean (some #{status} (:status response))))

(defn done? [response]
  (status? response "done"))

(defn- read-responses
  "The reader thread's loop. Once the connection is gone, the pending handler
  map becomes nil and every handler that was still waiting gets a final
  response with the statuses done and disconnected."
  [{:keys [transport pending]} on-unclaimed on-error]
  (let [call (fn [handler response]
               ;; A broken handler mustn't take the connection down with it.
               (try (handler response) (catch Throwable e (on-error e))))]
    (try
      (doseq [{:keys [id] :as response} (nrepl/response-seq transport)]
        (if-let [handler (get @pending id)]
          (do (when (done? response) (swap! pending dissoc id))
              (call handler response))
          (call on-unclaimed response)))
      ;; The socket went away, the server sent garbage or close interrupted us.
      (catch Throwable _))
    (let [[waiting] (swap-vals! pending (constantly nil))]
      (doseq [[id handler] waiting]
        (call handler {:id id :status ["done" "disconnected"]})))))

(defn send!
  "Sends msg with a fresh :id and calls (handler response) for each of its
  responses, the last one being the one with status done. Handlers run on the
  reader thread, so they must not block. Returns the id."
  [{:keys [transport pending]} msg handler]
  (let [id (misc/uuid)]
    (when-not (swap! pending #(some-> % (assoc id handler)))
      (throw (ex-info "Not connected to the nREPL server" {:type ::disconnected})))
    (try
      (transport/send transport (assoc msg :id id))
      (catch Throwable e
        (swap! pending dissoc id)
        (throw e)))
    id))

(defn request
  "Sends msg and waits for all of its responses, returning them as a vector.
  Throws when the connection drops or when the responses don't arrive within
  :timeout-ms (10s by default). In the latter case the ex-data has the
  message :id, for interrupting it, and later responses are dropped."
  ([client msg] (request client msg {}))
  ([client msg {:keys [timeout-ms] :or {timeout-ms 10000}}]
   (let [responses (atom [])
         result (promise)
         id (send! client msg (fn [response]
                                (when-not (realized? result)
                                  (let [all (swap! responses conj response)]
                                    (when (done? response)
                                      (deliver result all))))))
         all (or (deref result timeout-ms nil)
                 ;; Too late: from now on the handler ignores everything,
                 ;; unless done slipped in just now.
                 (do (deliver result nil) @result))]
     (cond
       (nil? all)
       (throw (ex-info (format "No response to %s within %dms" (:op msg) timeout-ms)
                       {:type ::timeout :id id :msg msg :responses @responses}))

       (status? (peek all) "disconnected")
       (throw (ex-info "Lost the connection to the nREPL server"
                       {:type ::disconnected :msg msg :responses all}))

       :else all))))

(defn close
  "Closes the client's connection. Requests still waiting get the
  disconnected status."
  [{:keys [transport ^Thread reader]}]
  (.close ^Closeable transport)
  ;; Closing doesn't wake up a reader blocked in recv, but interrupting does.
  (.interrupt reader))

(defn- open [{:keys [host port socket]} on-unclaimed on-error]
  (let [client {:transport (if socket
                             (nrepl/connect :socket socket)
                             (nrepl/connect :host host :port port))
                :pending (atom {})}]
    (assoc client :reader (doto (Thread. ^Runnable #(read-responses client on-unclaimed on-error)
                                         "neorepl-nrepl-reader")
                            (.setDaemon true)
                            (.start)))))

(defn connect
  "Connects to an nREPL server and returns a client. Takes :host (127.0.0.1 by
  default) and :port, a unix domain socket path as :socket, or a :url.

  Optional callbacks, both called on the reader thread: :on-unclaimed gets
  every response no request claims, :on-error every exception a response
  handler throws (by default it's printed to stderr).

  The client carries the server's :ops (a set of op names) and :versions, as
  reported by describe, and its :address (host:port or the socket path)."
  [{:keys [url on-unclaimed on-error]
    :or {on-unclaimed (fn [_])
         on-error #(binding [*out* *err*] (println "neorepl: response handler failed:" (str %)))}
    :as opts}]
  (let [{:keys [host port socket] :or {host "127.0.0.1"} :as where} (if url (parse-url url) opts)
        address (or socket (str host ":" port))
        no-server #(ex-info (str "No nREPL server answering at " address) {:type ::no-server} %)
        client (try
                 (open (assoc where :host host) on-unclaimed on-error)
                 (catch Exception e (throw (no-server e))))]
    (try
      (let [{:keys [ops versions]} (apply merge (request client {:op "describe"}))]
        (assoc client
               :address address
               ;; The transport turns op names into keywords: cider/info into :cider/info.
               :ops (set (map (comp str symbol) (keys ops)))
               :versions versions))
      (catch Throwable e
        (close client)
        (throw (no-server e))))))

(defn clone-session
  "Creates a session, a copy of session when given one, and returns its id."
  ([client] (clone-session client nil))
  ([client session]
   (let [responses (request client (cond-> {:op "clone"}
                                     session (assoc :session session)))]
     (or (some :new-session responses)
         (throw (ex-info "Couldn't create a session" {:session session :responses responses}))))))

(defn close-session
  "Closes session. Returns false when the server didn't know it."
  [client session]
  (not-any? #(status? % "unknown-session") (request client {:op "close" :session session})))

(defn interrupt
  "Asks the server to interrupt the evaluation with message id in session, or
  whichever is running there when id is nil. Returns how that went:
  :interrupted, :idle when nothing was running, :unknown-session, :mismatch
  when an evaluation other than id was running, or :error. The interrupted
  evaluation reports the status interrupted to its own handler. opts are the
  same as for `request`."
  ([client session id] (interrupt client session id {}))
  ([client session id opts]
   (let [statuses (into #{} (mapcat :status)
                        (request client
                                 (cond-> {:op "interrupt" :session session}
                                   id (assoc :interrupt-id id))
                                 opts))]
     (cond
       (statuses "session-idle") :idle
       (statuses "unknown-session") :unknown-session
       (statuses "interrupt-id-mismatch") :mismatch
       (statuses "error") :error
       :else :interrupted))))
