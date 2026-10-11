(ns neorepl.editor
  "Jolt editor backed by GNU Readline. Canonical input fallback for pipes.
  Readline owns editing, history search and completion display. Callbacks
  never let lookup failures escape into C. Live highlighting/eldoc and
  evaluation interruption are not implemented yet."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [jolt.ffi :as ffi]))

(ffi/load-library "libreadline.so")
(ffi/defcfn c-readline "readline" [:pointer] :pointer :blocking)
(ffi/defcfn c-isatty "isatty" [:int] :int)
(ffi/defcfn c-strdup "strdup" [:string] :pointer)
(ffi/defcfn c-free "free" [:pointer] :void)
(ffi/defcfn c-symbol "dlsym" [:pointer :string] :pointer)
(ffi/defcfn c-init "rl_initialize" [] :int)
(ffi/defcfn c-matches "rl_completion_matches" [:string :pointer] :pointer)
(ffi/defcfn c-bind "rl_bind_keyseq" [:string :pointer] :int)
(ffi/defcfn c-new-line "rl_on_new_line" [] :int)
(ffi/defcfn c-redisplay "rl_redisplay" [] :void)
(ffi/defcfn c-add-history "add_history" [:string] :void)
(ffi/defcfn c-read-history "read_history" [:string] :int)
(ffi/defcfn c-write-history "write_history" [:string] :int)
(ffi/defcfn c-stifle-history "stifle_history" [:int] :void)
(ffi/defcfn c-screen-size "rl_get_screen_size" [:pointer :pointer] :void)

(ffi/defcfn c-dlopen "dlopen" [:string :int] :pointer)
(def ^:private readline-handle
  (let [h (c-dlopen (if-let [prefix (System/getenv "PREFIX")]
                      (str prefix "/lib/libreadline.so")
                      "libreadline.so") 2)]
    (when (ffi/null? h)
      (throw (ex-info "Cannot open GNU Readline" {})))
    h))
(defn- global [name]
  (let [p (c-symbol readline-handle name)]
    (when (ffi/null? p)
      (throw (ex-info "Missing Readline symbol" {:symbol name})))
    p))
(defn- buffer []
  (ffi/ptr->string (ffi/read (global "rl_line_buffer") :pointer)))

(defn open [{:keys [complete doc dir] :as opts}]
  (if (and (= 1 (c-isatty 0)) (= 1 (c-isatty 1))
           (not= "dumb" (System/getenv "TERM")))
    (let [_ (c-init)
          candidates (atom [])
          generator (ffi/foreign-callable
                     (fn [_text _state]
                       (let [candidate (first @candidates)]
                         (swap! candidates rest)
                         (if candidate (c-strdup candidate) ffi/null)))
                     [:string :int] :pointer :collect-safe)
          completer (ffi/foreign-callable
                     (fn [text _start _end]
                       (try
                         ;; Never fall back to Readline's filename completion.
                         (ffi/write (global "rl_attempted_completion_over") :int 1 0)
                         (ffi/write (global "rl_completion_suppress_append") :int 1 0)
                         (reset! candidates (when (and complete (seq text))
                                              (map :candidate (complete text))))
                         (c-matches text generator)
                         (catch Exception _ ffi/null)))
                     [:string :int :int] :pointer :collect-safe)
          docs (ffi/foreign-callable
                (fn [_count _key]
                  (try
                    (when-let [text (when doc (doc (buffer) (ffi/read (global "rl_point") :int)))]
                      (println)
                      (println text)
                      (flush)
                      (c-new-line)
                      (c-redisplay))
                    (catch Exception _))
                  0)
                [:int :int] :int :collect-safe)
          completion-slot (global "rl_attempted_completion_function")
          breaks-slot (global "rl_completer_word_break_characters")
          old-completion (ffi/read completion-slot :pointer)
          old-breaks (ffi/read breaks-slot :pointer)
          breaks (c-strdup " \t\n\"'()[]{};,`~@")
          history (str (io/file (or dir (System/getProperty "user.dir")) ".jolt" "neorepl-history"))]
      ;; Installed Jolt 0.8.20 uses (write pointer type value offset).
      (ffi/write completion-slot :pointer completer 0)
      (ffi/write breaks-slot :pointer breaks 0)
      (c-bind "\u0018\u0004" docs)
      (c-stifle-history 1000)
      (c-read-history history)
      (assoc opts :readline? true :history history
             :callbacks [generator completer docs]
             :breaks breaks :old-completion old-completion :old-breaks old-breaks))
    opts))

(defn close [{:keys [readline? history callbacks breaks old-completion old-breaks]}]
  (when readline?
    (io/make-parents history)
    (c-write-history history)
    (ffi/write (global "rl_attempted_completion_function") :pointer old-completion 0)
    (ffi/write (global "rl_completer_word_break_characters") :pointer old-breaks 0)
    (c-bind "\u0018\u0004" ffi/null)
    (doseq [callback callbacks] (ffi/free-callable callback))
    (c-free breaks)))

(defn width [{:keys [readline?]}]
  (if readline?
    (let [rows (ffi/alloc 4) columns (ffi/alloc 4)]
      (try (c-screen-size rows columns) (max 20 (ffi/read columns :int))
           (finally (ffi/free rows) (ffi/free columns))))
    80))
(defn suspended [_ f] (f))
(defn on-interrupt [_ _] nil)
(defn fresh-line [_] (flush))
(defn print-text [_ _ text] (print text) (flush))
(defn print-code [_ text] (print text) (flush))

(defn- read-prompt [{:keys [readline?]} prompt history?]
  (if readline?
    (let [prompt-ptr (c-strdup prompt)
          p (try (c-readline prompt-ptr) (finally (c-free prompt-ptr)))]
      (when-not (ffi/null? p)
        (try
          (let [line (ffi/ptr->string p)]
            (when (and history? (not (str/blank? line))) (c-add-history line))
            line)
          (finally (c-free p)))))
    (do (print prompt) (flush) (clojure.core/read-line))))

(defn read-line [editor] (read-prompt editor "" false))
(defn read-code [{:keys [incomplete?] :as editor} prompt]
  (loop [lines []]
    (if-let [line (read-prompt editor (if (seq lines) "... " prompt) true)]
      (let [lines (conj lines line)
            code (str/join "\n" lines)]
        (if (incomplete? code) (recur lines) code))
      (when (seq lines) (str/join "\n" lines)))))
