(ns neorepl.editor
  "Jolt editor backed by GNU Readline. Canonical input fallback for pipes.
  Readline owns editing, history search and completion display. Callbacks
  never let lookup failures escape into C. ANSI highlighting and arglists
  use a conservative single-row overlay; wrapped/Unicode input stays native."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [neorepl.lexer :as lexer]
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
(ffi/defcfn c-replace-line "rl_replace_line" [:string :int] :void)
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

(defn- cursor [text]
  ;; Readline reports bytes; lexer offsets use the managed string's indices.
  (count (String. (byte-array (take (ffi/read (global "rl_point") :int)
                                    (.getBytes text "UTF-8"))) "UTF-8")))

(defn highlight [text]
  (let [colors {:string "32" :regex "32" :char "32" :keyword "36"
                :number "35" :constant "35" :comment "2"}]
    (loop [[token & more] (:tokens (lexer/lex text)) offset 0 result ""]
      (if token
        (let [{:keys [start end type]} token
              color (colors type)
              value (subs text start end)]
          (recur more end (str result (subs text offset start)
                               (if color (str "\u001b[" color "m" value "\u001b[0m") value))))
        (str result (subs text offset))))))

(declare width)

(defn- redisplay [opts eldoc]
  (c-redisplay)
  (try
    (let [text (buffer)
          point (ffi/read (global "rl_point") :int)
          prompt (ffi/ptr->string (ffi/read (global "rl_display_prompt") :pointer))
          columns (width {:readline? true})
          used (+ (count prompt) (count text))]
      ;; Readline positions are UTF-8 byte offsets; do not assume byte count
      ;; equals display columns for Unicode or let an overlay wrap a row.
      (when (and (re-matches #"[\x20-\x7e]*" (str prompt text)) (< used (dec columns)))
        (let [hint (when (and eldoc (= point (count text)))
                     (eldoc text point))
              room (max 0 (- columns used 2))
              hint (when hint (subs hint 0 (min room (count hint))))
              color? (and (not= false (:color? opts)) (nil? (System/getenv "NO_COLOR")))]
          (print (str "\r\u001b[2K" prompt (if color? (highlight text) text)
                      (when (seq hint) (str " " (when color? "\u001b[2m") hint (when color? "\u001b[0m")))
                      "\r" (when (pos? (+ (count prompt) point))
                              (str "\u001b[" (+ (count prompt) point) "C"))))
          (flush))))
    (catch Exception _)))

(defn open [{:keys [complete doc dir eldoc] :as opts}]
  (if (and (= 1 (c-isatty 0)) (= 1 (c-isatty 1))
           (not= "dumb" (System/getenv "TERM")))
    (let [_ (c-init)
          install (requiring-resolve 'neorepl.terminal-bridge/install)
          restore (requiring-resolve 'neorepl.terminal-bridge/restore)
          input-mode (requiring-resolve 'neorepl.terminal-bridge/input-mode)
          poll (requiring-resolve 'neorepl.terminal-bridge/poll)
          bridge-handle (c-dlopen @(requiring-resolve 'neorepl.terminal-bridge/library) 2)
          _ (when-not (zero? (install))
              (throw (ex-info "Could not configure terminal input" {})))
          reading (atom false)
          cancelled (atom false)
          interrupt (atom nil)
          stopping (atom false)
          saved-globals (into {} (for [name ["rl_redisplay_function" "rl_getc_function"]]
                                   [name (ffi/read (global name) :pointer)]))
          old-catch-signals (ffi/read (global "rl_catch_signals") :int)
          redraw (ffi/foreign-callable #(redisplay opts eldoc) [] :void :collect-safe)
          cancel (ffi/foreign-callable
                  (fn [_count _key]
                    (reset! cancelled true)
                    (c-replace-line "" 0)
                    (ffi/write (global "rl_done") :int 1 0)
                    (print "\r\u001b[2K^C\n") (flush)
                    0)
                  [:int :int] :int :collect-safe)
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
                    (when-let [text (when doc (let [text (buffer)] (doc text (cursor text))))]
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
      (ffi/write (global "rl_redisplay_function") :pointer redraw 0)
      (ffi/write (global "rl_getc_function") :pointer
                 (c-symbol bridge-handle "neorepl_read_char") 0)
      (ffi/write (global "rl_catch_signals") :int 0 0)
      (c-bind "\u0003" cancel)
      (c-bind "\u0018\u0004" docs)
      (c-stifle-history 1000)
      (c-read-history history)
      (let [worker (doto (Thread.
                          (fn []
                            (try
                              (while (not @stopping)
                                (when (and (not @reading) (= 1 (poll)))
                                  (when-let [f @interrupt] (f)))
                                (Thread/sleep 25))
                              (catch InterruptedException _)))
                          "neorepl-control-c")
                     (.setDaemon true) (.start))]
        (assoc opts :readline? true :history history
               :callbacks [generator completer docs redraw cancel]
               :reading reading :cancelled cancelled :interrupt interrupt
               :worker worker :stopping stopping :install install :restore restore :input-mode input-mode
               :saved-globals saved-globals :old-catch-signals old-catch-signals
               :breaks breaks :old-completion old-completion :old-breaks old-breaks)))
    opts))

(defn close [{:keys [readline? history callbacks breaks old-completion old-breaks
                     stopping worker restore saved-globals old-catch-signals]}]
  (when readline?
    (reset! stopping true)
    (.interrupt worker)
    (.join worker 1000)
    (doseq [[name value] saved-globals] (ffi/write (global name) :pointer value 0))
    (ffi/write (global "rl_catch_signals") :int old-catch-signals 0)
    (restore)
    (c-bind "\u0003" ffi/null)
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
(defn suspended [{:keys [readline? reading input-mode restore install]} f]
  (if readline?
    (do
      (reset! reading true)
      (input-mode 1)
      (restore)
      (try (f)
           (finally
             (when-not (zero? (install))
               (throw (ex-info "Could not restore Readline terminal mode" {})))
             (input-mode 0)
             (reset! reading false))))
    (f)))
(defn on-interrupt [{:keys [interrupt]} f]
  (when interrupt (reset! interrupt f)))
(defn fresh-line [_] (flush))
(defn print-text [_ _ text] (print text) (flush))
(defn print-code [{:keys [readline? color?]} text]
  (print (if (and readline? (not= false color?) (nil? (System/getenv "NO_COLOR")))
           (highlight text) text))
  (flush))

(defn- read-prompt [{:keys [readline? reading cancelled input-mode]} prompt history?]
  (if readline?
    (let [prompt-ptr (c-strdup prompt)
          _ (reset! cancelled false)
          _ (reset! reading true)
          _ (input-mode 1)
          p (try (c-readline prompt-ptr)
                 (finally (input-mode 0) (reset! reading false) (c-free prompt-ptr)))]
      (when-not (ffi/null? p)
        (try
          (let [line (ffi/ptr->string p)]
            (when (and history? (not @cancelled) (not (str/blank? line))) (c-add-history line))
            (if @cancelled ::interrupted line))
          (finally (c-free p)))))
    (do (print prompt) (flush) (clojure.core/read-line))))

(defn read-line [editor] (read-prompt editor "" false))
(defn read-code [{:keys [incomplete?] :as editor} prompt]
  (loop [lines []]
    (if-let [line (read-prompt editor (if (seq lines) "... " prompt) true)]
      (if (= ::interrupted line)
        (recur [])
        (let [lines (conj lines line)
              code (str/join "\n" lines)]
          (if (incomplete? code) (recur lines) code)))
      (when (seq lines) (str/join "\n" lines)))))
