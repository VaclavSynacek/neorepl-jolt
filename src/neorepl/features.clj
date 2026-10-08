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
  reports an error: a status ending in error, as eval-error and cider-nrepl's
  info-error do."
  [client msg opts]
  (let [responses (nrepl/request client msg opts)
        statuses (into #{} (mapcat :status) responses)]
    (when (some #(str/ends-with? % "error") statuses)
      (throw (ex-info (str "The server couldn't do " (:op msg)
                           (when-let [err (some-> (keep :err responses) seq str/join str/trim)]
                             (str ": " err)))
                      {:type ::failed :statuses statuses})))
    responses))

(defn- eval-data
  "Evaluates the code that (format template args...) makes, in ns, and returns
  its value, which has to be plain data, read back. The args go in as string
  literals, so they can't be code: the template turns them into what it
  needs, as with (symbol %s)."
  [client ns template args opts]
  (some->> (ask client
                {:op "eval" :ns ns :code (apply format template (map pr-str args))}
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

(defn- normalize-info
  "info and lookup responses in one shape: :ns, :name, :doc, :file, :line,
  :column, :arglists and a special form's :forms as vectors of strings, and
  :macro and :special-form."
  [{:keys [arglists-str forms forms-str macro special-form] :as info}]
  (cond-> (select-keys info [:ns :name :doc :file :line :column])
    (seq (arglists arglists-str)) (assoc :arglists (arglists arglists-str))
    (truthy macro) (assoc :macro true)
    (truthy special-form) (assoc :special-form true)
    (seq forms) (assoc :forms (mapv #(if (string? %) % (str "(" (str/join " " (map form-text %)) ")")) forms))
    forms-str (assoc :forms (str/split-lines forms-str))))

(defn info
  "What the server knows about sym, a symbol's text, as seen from the
  namespace ns: a map with its :ns and :name, :arglists, :doc and where it
  is, :file, :line and :column. Nil when sym means nothing there."
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
     (some-> (eval-data client ns "(when-let [m (meta (resolve (symbol %s)))]
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

(defn doc-text
  "info as text, the way clojure.repl/doc prints it, minus the dashes."
  [{:keys [ns name arglists forms macro special-form doc]}]
  (str/join "\n" (concat [(str (some-> ns (str "/")) name)]
                         (if forms
                           (map #(str "  " %) forms)
                           (some->> arglists (str/join " ") (format "(%s)") vector))
                         (cond
                           special-form ["Special Form"]
                           macro ["Macro"])
                         (some->> doc (str "  ") vector))))

(defn eldoc-text
  "What to show while the function info describes is being called: its
  arglists, or a special form's shapes. Nil when there are none."
  [{:keys [arglists forms]}]
  (some->> (or forms arglists) seq (str/join " ")))

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
   (eval-data client ns "(do (require 'clojure.repl) (clojure.repl/source-fn (symbol %s)))" [sym] opts)))

(defn apropos
  "The vars whose names match the regex, as maps with the :name and, when
  the server says, the :type and :doc."
  ([client regex] (apropos client regex {}))
  ([client regex opts]
   (if (supports? client "apropos")
     (->> (ask client {:op "apropos" :query regex} opts)
          (some :apropos-matches)
          (mapv #(select-keys % [:name :type :doc])))
     (->> (eval-data client "user" "(do (require 'clojure.repl) (map str (clojure.repl/apropos (re-pattern %s))))"
                     [regex] opts)
          (mapv #(hash-map :name %))))))
