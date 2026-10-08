(ns neorepl.render
  "What the features find, as text."
  (:require [clojure.string :as str]
            [neorepl.cli :as cli]))

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

(defn apropos-text
  "apropos matches, a line each: the name and the first line of the doc."
  [matches]
  (str/join "\n" (cli/columns (for [{:keys [name doc]} matches]
                                [name (str (some-> doc str/split-lines first))]))))

(defn- frame-text [{:keys [name file line]}]
  (str "    at " name " ("
       (cond
         (and file line) (str file ":" line)
         file file
         :else "Unknown Source")
       ")"))

(defn error-text
  "An error from last-error as text, the way Java prints stack traces, minus
  the frames of the tools around the code and the duplicates."
  [causes]
  (str/join "\n"
            (for [[i {:keys [class message data stacktrace]}] (map-indexed vector causes)
                  line (concat [(str (when (pos? i) "Caused by: ") class (some->> message (str ": ")))]
                               (some->> data str/trim str/split-lines (map #(str "  " %)))
                               (for [{:keys [flags] :as frame} stacktrace
                                     :when (not-any? #{"tooling" "dup"} flags)]
                                 (frame-text frame)))]
              line)))
