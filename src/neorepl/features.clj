(ns neorepl.features
  "What neorepl asks a server for besides evaluations. Each feature goes for
  the best way the server supports: cider-nrepl's ops first, then the ones
  nREPL has built in, then evaluating code. Anything evaluated runs outside
  the user's sessions, so *1 and *e stay put.

  The functions take request options last, as nrepl/request does, for a
  :timeout-ms. They throw ::failed when the server reports an error, which
  isn't the same as finding nothing."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]))

(defn- supports? [client op]
  (contains? (:ops client) op))

(defn- ask
  "Sends msg and returns its responses, throwing ::failed when the server
  reports an error: a status ending in error, as eval-error and
  cider-nrepl's info-error do, other than its no-error."
  [client msg opts]
  (let [responses (nrepl/request client msg opts)
        statuses (into #{} (mapcat :status) responses)]
    (when (some #(and (str/ends-with? % "error") (not= "no-error" %)) statuses)
      (throw (ex-info (str "The server couldn't do " (:op msg)
                           (when-let [err (some-> (keep :err responses) seq str/join str/trim)]
                             (str ": " err)))
                      {:type ::failed :statuses statuses})))
    responses))

(defn- eval-data
  "Evaluates the code that (format template args...) makes and returns its
  value, which has to be plain data, read back. msg says where: its :ns, or
  its :session. The args go in as string literals, so they can't be code:
  the template turns them into what it needs, as with (symbol %s)."
  [client msg template args opts]
  (some->> (ask client
                (assoc msg :op "eval" :code (apply format template (map pr-str args)))
                opts)
           (some :value)
           edn/read-string))

(defn- truthy [x]
  (contains? #{true "true"} x))

(defn- arglists
  "The arglists in lookup's \"([x] [x y])\" or cider-nrepl's arglist per line,
  as a vector of strings."
  [s]
  (cond
    (str/blank? s) nil
    (str/starts-with? s "(") (lexer/forms (subs s 1 (dec (count s))))
    :else (str/split-lines s)))

(defn- form-text
  "A special form's shape the way lookup has it, a vector of strings and
  vectors, as text."
  [form]
  (if (string? form)
    form
    (str "[" (str/join " " (map form-text form)) "]")))

(defn- source-file
  "Where a server says a definition is, as a path when that's a file on this
  machine, nil when it came from a REPL rather than a file, and as it is
  otherwise, like a jar: URL for a file in a jar."
  [file]
  (cond
    (or (str/blank? file) (str/ends-with? file "NO_SOURCE_PATH")) nil
    (str/starts-with? file "file:") (str (java.io.File. (java.net.URI. file)))
    :else file))

(defn- normalize-info
  "info and lookup responses in one shape: :ns, :name, :doc, where it is as
  the :file, :line and :column, :arglists and a special form's :forms as
  vectors of strings, and :macro and :special-form."
  [{:keys [file arglists-str forms forms-str macro special-form] :as info}]
  (let [file (source-file file)]
    (cond-> (select-keys info [:ns :name :doc])
      file (merge {:file file} (select-keys info [:line :column]))
      (seq (arglists arglists-str)) (assoc :arglists (arglists arglists-str))
      (truthy macro) (assoc :macro true)
      (truthy special-form) (assoc :special-form true)
      (seq forms) (assoc :forms (mapv #(if (string? %) % (str "(" (str/join " " (map form-text %)) ")")) forms))
      forms-str (assoc :forms (str/split-lines forms-str)))))

(defn info
  "What the server knows about sym, a symbol's text, as seen from the
  namespace ns: a map with its :ns and :name, :arglists, :doc and where it
  is, :file, :line and :column, when it's in a file. Nil when sym means
  nothing there."
  ([client sym ns] (info client sym ns {}))
  ([client sym ns opts]
   (cond
     (supports? client "info")
     (let [info (apply merge (ask client {:op "info" :sym sym :ns ns} opts))]
       (when (:name info)
         (normalize-info info)))

     (supports? client "lookup")
     (let [info (some :info (ask client {:op "lookup" :sym sym :ns ns} opts))]
       ;; Nothing found comes back as an empty list.
       (when (map? info)
         (normalize-info info)))

     :else
     (some-> (eval-data client {:ns ns} "(when-let [m (meta (resolve (symbol %s)))]
                                    {:ns (str (:ns m))
                                     :name (str (:name m))
                                     :arglists-str (some->> (:arglists m) (map pr-str) (interpose \"\\n\") (apply str))
                                     :doc (:doc m)
                                     :file (:file m)
                                     :line (:line m)
                                     :column (:column m)
                                     :macro (:macro m)})"
                        [sym] opts)
             normalize-info))))

(defn complete
  "The completions for prefix in the namespace ns, as maps with the
  :candidate and, when the server says, its :type and :ns."
  ([client prefix ns] (complete client prefix ns {}))
  ([client prefix ns opts]
   (when-let [op (some (:ops client) ["complete" "completions"])]
     (->> (ask client {:op op :prefix prefix :ns ns} opts)
          (some :completions)
          (mapv #(select-keys % [:candidate :type :ns]))))))

(defn source
  "The source of the var sym names in the namespace ns, or nil when the
  server can't find it."
  ([client sym ns] (source client sym ns {}))
  ([client sym ns opts]
   (eval-data client {:ns ns} "(do (require 'clojure.repl) (clojure.repl/source-fn (symbol %s)))" [sym] opts)))

(defn apropos
  "The vars whose names match the regex, as maps with the :name and, when
  the server says, the :type and :doc."
  ([client regex] (apropos client regex {}))
  ([client regex opts]
   (if (supports? client "apropos")
     (->> (ask client {:op "apropos" :query regex} opts)
          (some :apropos-matches)
          (mapv #(select-keys % [:name :type :doc])))
     (->> (eval-data client {:ns "user"} "(do (require 'clojure.repl) (map str (clojure.repl/apropos (re-pattern %s))))"
                     [regex] opts)
          (mapv #(hash-map :name %))))))

(defn- one-form
  "Throws unless text is a single form the reader can take without
  evaluating anything, the way #= would. cider-nrepl evaluates what it
  expands with *read-eval* on."
  [text]
  (when-not (and (= 1 (count (lexer/forms text)))
                 (not (str/includes? text "#=")))
    (throw (ex-info "Give one form to expand, without #=" {:type ::failed}))))

(defn expand
  "The form in text with macros expanded in the namespace ns, pretty printed:
  just the form itself, the way macroexpand-1 does, or every macro in it with
  all?"
  ([client text ns all?] (expand client text ns all? {}))
  ([client text ns all? opts]
   (one-form text)
   (if (supports? client "macroexpand")
     (->> (ask client {:op "macroexpand"
                       :code text
                       :ns ns
                       :expander (if all? "macroexpand-all" "macroexpand-1")
                       ;; Without the namespaces of what the namespace refers.
                       :display-namespaces "tidy"}
               opts)
          (some :expansion))
     (some-> (eval-data client {:ns ns}
                        "(do (require 'clojure.pprint 'clojure.walk)
                             (with-out-str
                               (clojure.pprint/pprint
                                 ((if %s clojure.walk/macroexpand-all macroexpand-1)
                                  (binding [*read-eval* false] (read-string %s))))))"
                        [all? text] opts)
             str/trimr))))

(defn- blank->nil
  "cider-nrepl sends nil as an empty list, babashka as an empty string."
  [x]
  (when-not (or (and (string? x) (str/blank? x)) (and (coll? x) (empty? x)))
    x))

(defn- dups
  "Flags the frames that call on from one before them in the same function,
  the way cider-nrepl does, as dup."
  [frames]
  (let [fn-of #(first (str/split (str (:name %)) #"/"))]
    (into (vec (take 1 frames))
          (map (fn [previous frame]
                 (cond-> frame (= (fn-of previous) (fn-of frame)) (update :flags (fnil conj []) "dup")))
               frames (rest frames)))))

(defn last-error
  "The last error in session, as its causes, outermost first: maps with the
  :class, the :message, ex-data as :data text and the :stacktrace, frames with
  a :name, :file and :line, and :flags saying what kind of frame it is, like
  tooling for the frames running the code rather than of it. Nil when
  nothing's gone wrong yet."
  ([client session] (last-error client session {}))
  ([client session opts]
   (some->>
    (if (supports? client "analyze-last-stacktrace")
      (->> (ask client {:op "analyze-last-stacktrace" :session session} opts)
           (filter :class))
      ;; *e is in the session, so the code looking at it has to run there,
      ;; in a copy, so the session's *1 stays put.
      (let [copy (try
                   (nrepl/clone-session client session)
                   (catch clojure.lang.ExceptionInfo e
                     (throw (ex-info "The server couldn't copy the session" {:type ::failed} e))))
            ;; What runs the code rather than the code itself.
            tooling? #(re-find #"^(clojure\.lang\.Compiler|clojure\.main|nrepl\..+|java\.lang\.Thread)/" (:name %))]
        (try
          (some->> (eval-data client {:session copy}
                              ;; Printed here, with print settings of its own:
                              ;; the copy has the session's, which could cut it short.
                              "(when-let [e *e]
                                 (binding [*print-length* nil *print-level* nil *print-meta* false]
                                   (let [frame (fn [[class method file line]]
                                                 {:name (str class \"/\" method) :file file :line line})
                                         cause (fn [{:keys [type message data at]}]
                                                 {:class (str type)
                                                  :message message
                                                  :data (binding [*print-length* 20 *print-level* 5]
                                                          (some-> data pr-str))
                                                  :stacktrace (if at [(frame at)] [])})
                                         {:keys [via trace]} (Throwable->map e)]
                                     (pr-str (conj (mapv cause (pop via))
                                                   (assoc (cause (peek via)) :stacktrace (mapv frame trace)))))))"
                              [] opts)
                   edn/read-string
                   (map (fn [cause]
                          (update cause :stacktrace
                                  (partial mapv #(cond-> % (tooling? %) (assoc :flags ["tooling"])))))))
          (finally
            (nrepl/close-session client copy)))))
    (mapv (fn [{:keys [class message data stacktrace]}]
            {:class class
             :message (blank->nil message)
             :data (blank->nil data)
             :stacktrace (dups (mapv #(select-keys % [:name :file :line :flags]) stacktrace))}))
    not-empty)))
