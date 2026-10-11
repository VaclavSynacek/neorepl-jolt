(ns neorepl.editor
  "Jolt editor facade. Pipes never load Readline or the native bridge."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [neorepl.lexer :as lexer]))

(defn highlight [text]
  (let [colors {:string "32" :open-string "32" :regex "32" :char "32"
                :keyword "36" :number "35" :constant "35" :comment "2" :discard "2"}]
    (loop [[token & more] (:tokens (lexer/lex text)) offset 0 result ""]
      (if token
        (let [{:keys [start end type]} token
              color (colors type)
              value (subs text start end)]
          (recur more end (str result (subs text offset start)
                               (if color (str "\u001b[" color "m" value "\u001b[0m") value))))
        (str result (subs text offset))))))

(defn history-file
  "A stable project key without MessageDigest, which Jolt does not implement.
  This non-cryptographic 63-bit hash is for filenames, not security."
  [history-dir dir]
  (let [path (.getCanonicalPath (io/file dir))
        key (reduce (fn [h c] (mod (+ (* h 65599N) (int c)) 9223372036854775783N)) 0 path)]
    (io/file history-dir (format "%016x" (long key)))))

(defn open [opts]
  (if ((requiring-resolve 'neorepl.posix/terminal?))
    (try
      (let [opts (cond-> (assoc opts :highlight highlight)
                   (and (:history-dir opts) (not (:history-file opts)))
                   (assoc :history-file (history-file (:history-dir opts) (:dir opts))))]
        ((requiring-resolve 'neorepl.readline/open) opts))
      (catch Exception e
        (throw (ex-info (str "Cannot open Readline editor: " (ex-message e))
                        {:type ::unavailable} e))))
    (assoc opts :line (atom "") :lock (Object.))))

(defn- invoke [editor op & args]
  (apply (get-in editor [:backend op]) editor args))

(defn- plain-emit [{:keys [line lock]} text]
  (locking lock
    (print text) (flush)
    (swap! line #(if-let [end (str/last-index-of text "\n")]
                  (subs text (inc end))
                  (str % text)))))

(defn fresh-line [{:keys [backend line] :as editor}]
  (if backend (invoke editor :fresh-line)
      (when (seq @line) (plain-emit editor "\n"))))

(defn close [{:keys [backend] :as editor}]
  (if backend (invoke editor :close) (fresh-line editor)))

(defn width [{:keys [backend] :as editor}]
  (if backend (invoke editor :width) 80))

(defn suspended [{:keys [backend] :as editor} f]
  (if backend (invoke editor :suspended f) (f)))

(defn on-interrupt [{:keys [backend] :as editor} f]
  (when backend (invoke editor :on-interrupt f)))

(defn print-text [{:keys [backend] :as editor} style text]
  (if backend (invoke editor :print-text style text) (plain-emit editor text)))

(defn print-code [{:keys [backend] :as editor} text]
  (if backend (invoke editor :print-code text) (plain-emit editor text)))

(defn read-line [{:keys [backend line] :as editor}]
  (if backend (invoke editor :read-line)
      (let [text (clojure.core/read-line)] (reset! line "") text)))

(defn read-code [{:keys [backend incomplete? line] :or {incomplete? lexer/incomplete?} :as editor} prompt]
  (if backend
    (loop []
      (let [text (invoke editor :read-code prompt)]
        (if (= ::interrupted text) (recur) text)))
    (loop [lines []]
      (plain-emit editor (if (seq lines) "... " prompt))
      (if-let [text (clojure.core/read-line)]
        (let [_ (reset! line "")
              lines (conj lines text)
              code (str/join "\n" lines)]
          (if (incomplete? code) (recur lines) code))
        (when (seq lines) (str/join "\n" lines))))))
