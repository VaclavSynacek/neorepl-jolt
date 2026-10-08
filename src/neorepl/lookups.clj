(ns neorepl.lookups
  "The lookups neorepl's commands and the REPL's have in common, so both
  work and read the same: what each takes, how it finds its answer, how that
  reads as text and what it says when there's nothing to find."
  (:require [neorepl.features :as features]
            [neorepl.render :as render]))

(def all
  "Each lookup takes the :arg it names, unless it has none, and :code-arg?
  says that's code. Its :find takes the client, a context with the :ns to
  look in, the :session and the session's :session-name, if it has one, and
  the argument. :text turns what it finds into text, code when :code? says
  so, and :none says what finding nothing means."
  [{:name "doc" :arg "SYM" :help "Show the documentation of SYM"
    :find (fn [client {:keys [ns]} sym] (features/info client sym ns))
    :text render/doc-text
    :none (fn [{:keys [ns]} sym] (str "Nothing called " sym " in " ns))}
   {:name "source" :arg "SYM" :help "Show the source of SYM"
    :find (fn [client {:keys [ns]} sym]
            (some->> (features/source client sym ns) (hash-map :source)))
    :text :source
    :code? true
    :none (fn [{:keys [ns]} sym] (str "No source for " sym " in " ns))}
   {:name "apropos" :arg "REGEX" :help "List the vars whose names match REGEX"
    :find (fn [client _ regex] (not-empty (features/apropos client regex)))
    :text render/apropos-text
    :none (fn [_ regex] (str "Nothing matches " regex))}
   {:name "macroexpand" :arg "FORM" :code-arg? true :help "Expand the macro FORM calls"
    :find (fn [client {:keys [ns]} form] {:expansion (features/expand client form ns false)})
    :text :expansion
    :code? true}
   {:name "macroexpand-all" :arg "FORM" :code-arg? true :help "Expand every macro in FORM"
    :find (fn [client {:keys [ns]} form] {:expansion (features/expand client form ns true)})
    :text :expansion
    :code? true}
   {:name "where" :arg "SYM" :help "Show where SYM is defined"
    :find (fn [client {:keys [ns]} sym]
            (let [{:keys [file] :as info} (features/info client sym ns)]
              (when file
                (select-keys info [:file :line :column]))))
    :text render/location-text
    :none (fn [{:keys [ns]} sym] (str "Nowhere to find " sym " in " ns))}
   {:name "pst" :help "Show the stack trace of the last error"
    :find (fn [client {:keys [session]} _] (features/last-error client session))
    :text render/error-text
    :none (fn [{:keys [session-name]} _]
            (str "No error yet" (some->> session-name (str " in session "))))}])

(defn lookup [name]
  (some #(when (= name (:name %)) %) all))

(defn run
  "Does the lookup called name: {:found what-it-found :text as-text}, or
  {:none what-that-means} when it finds nothing."
  [name client ctx arg]
  (let [{:keys [find text none]} (lookup name)]
    (if-let [found (find client ctx arg)]
      {:found found :text (text found)}
      {:none (none ctx arg)})))
