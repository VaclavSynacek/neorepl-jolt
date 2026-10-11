(ns neorepl.readline
  "Readline callback backend. One owner thread does all terminal/native work;
  evaluation and late-output threads communicate with it through a queue."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.lexer :as lexer]
            [neorepl.posix :as posix]
            [neorepl.terminal-bridge :as bridge]
            [jolt.ffi :as ffi])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(def library
  (:path (ffi/load-library
          (let [names ["libreadline.so.8" "libreadline.so" "libreadline.8.dylib" "libreadline.dylib"]]
            (concat (when-let [prefix (System/getenv "PREFIX")]
                      (map #(str prefix "/lib/" %) names)) names)))))
(ffi/defcfn c-dlopen "dlopen" [:string :int] :pointer)
(ffi/defcfn c-symbol "dlsym" [:pointer :string] :pointer)
(ffi/defcfn c-strdup "strdup" [:string] :pointer)
(ffi/defcfn c-free "free" [:pointer] :void)
(ffi/defcfn c-flush "fflush" [:pointer] :int)
(ffi/defcfn c-init "rl_initialize" [] :int)
(ffi/defcfn c-install "rl_callback_handler_install" [:string :pointer] :void)
(ffi/defcfn c-remove "rl_callback_handler_remove" [] :void)
(ffi/defcfn c-read-char "rl_callback_read_char" [] :void)
(ffi/defcfn c-newline "rl_newline" [:int :int] :int)
(ffi/defcfn c-insert "rl_insert_text" [:string] :int)
(ffi/defcfn c-replace "rl_replace_line" [:string :int] :void)
(ffi/defcfn c-redisplay "rl_redisplay" [] :void)
(ffi/defcfn c-force-redisplay "rl_forced_update_display" [] :int)
(ffi/defcfn c-clear "rl_clear_visible_line" [] :int)
(ffi/defcfn c-new-line "rl_on_new_line" [] :int)
(ffi/defcfn c-matches "rl_completion_matches" [:string :pointer] :pointer)
(ffi/defcfn c-bind "rl_bind_keyseq" [:string :pointer] :int)
(ffi/defcfn c-keymap "rl_get_keymap" [] :pointer)
(ffi/defcfn c-key-function "rl_function_of_keyseq" [:string :pointer :pointer] :pointer)
(ffi/defcfn c-add-history "add_history" [:string] :void)
(ffi/defcfn c-clear-history "clear_history" [] :void)
(ffi/defcfn c-stifle-history "stifle_history" [:int] :void)
(ffi/defcfn c-previous-line "rl_previous_screen_line" [:int :int] :int)
(ffi/defcfn c-next-line "rl_next_screen_line" [:int :int] :int)
(ffi/defcfn c-previous-history "rl_get_previous_history" [:int :int] :int)
(ffi/defcfn c-next-history "rl_get_next_history" [:int :int] :int)
(ffi/defcfn c-screen-size "rl_get_screen_size" [:pointer :pointer] :void)

(def ^:private handle (c-dlopen library 2))
(def ^:private bridge-handle (c-dlopen bridge/library 2))
(defonce ^:private claimed (atom false))

(defn- global [name]
  (let [p (c-symbol handle name)]
    (when (ffi/null? p) (throw (ex-info (str "Missing Readline symbol: " name) {})))
    p))
(defn- buffer [] (ffi/ptr->string (ffi/read (global "rl_line_buffer") :pointer)))
(defn- cursor [text]
  (count (String. (byte-array (take (ffi/read (global "rl_point") :int)
                                    (.getBytes text "UTF-8"))) "UTF-8")))
(defn- color? [ed]
  (and (not= false (:color? ed)) (str/blank? (System/getenv "NO_COLOR"))))
(defn- columns []
  (let [rows (ffi/alloc 4) cols (ffi/alloc 4)]
    (try (c-screen-size rows cols) (max 20 (ffi/read cols :int))
         (finally (ffi/free rows) (ffi/free cols)))))

(defn- history-entries [file]
  (try
    (let [entries (when (and file (.isFile (io/file file))) (edn/read-string (slurp file)))]
      (if (and (vector? entries) (every? string? entries)) entries []))
    (catch Exception _ [])))

(defn- save-history! [{:keys [history-file state]}]
  (when (and history-file (seq (:new-history @state)))
    (when (.isDirectory (io/file history-file)) (throw (ex-info "History path is a directory" {})))
    (posix/call-with-lock history-file
      #(do (io/make-parents history-file)
           (let [entries (vec (take-last 1000 (concat (history-entries history-file) (:new-history @state))))
                 file (io/file history-file)
                 tmp (java.io.File/createTempFile "history" ".edn" (.getParentFile file))]
             (try
               (spit tmp (pr-str entries))
               (java.nio.file.Files/move (.toPath tmp) (.toPath file)
                 (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/ATOMIC_MOVE java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
               (finally (.delete tmp))))))))

(defn- call!
  ([ed f] (call! ed f false))
  ([{:keys [queue closing submit-lock owner-id]} f control?]
   (if (= @owner-id (.getId (Thread/currentThread)))
     (f)
     (let [reply (promise)
           accepted? (locking submit-lock
                       (when-not @closing (.put queue {:f f :reply reply :control? control?}) true))]
       (when accepted?
         ;; Closing and enqueueing are serialized; cleanup drains every accepted command.
         (let [{:keys [value error]} @reply]
           (when error (throw error))
           value))))))

(defn- visible [text] (str/replace text #"\u001b\[[0-9;]*m" ""))

(defn- emit! [{:keys [state] :as ed} text]
  (let [{:keys [read above]} @state
        text (str above text)]
    (if read
      (if-let [end (str/last-index-of text "\n")]
        (do (c-clear) (c-flush ffi/null)
            (print (subs text 0 (inc end))) (flush)
            (swap! state assoc :above (subs text (inc end)))
            (c-new-line) (c-force-redisplay))
        (swap! state assoc :above text))
      (do (print text) (flush)
          (swap! state assoc :above ""
                 :line (let [text (visible text)]
                         (if-let [end (str/last-index-of text "\n")]
                           (subs text (inc end))
                           (str (:line @state) text))))))))

(defn- fresh! [{:keys [state] :as ed}]
  (when (seq (visible (if (:read @state) (:above @state) (:line @state)))) (emit! ed "\n")))

(defn- redraw! [{:keys [state highlight eldoc eldoc?] :as ed}]
  ;; A hint extends beyond the line Readline knows about. Remove it before
  ;; entering native multiline/Unicode layout, and invalidate its old display.
  (when (:overlay? @state)
    (let [text (buffer)
          prompt (ffi/ptr->string (ffi/read (global "rl_display_prompt") :pointer))]
      (when-not (and (re-matches #"[\x20-\x7e]*" (str prompt text))
                     (< (+ (count prompt) (count text)) (dec (columns))))
        (c-clear) (c-flush ffi/null) (c-new-line)
        (swap! state assoc :overlay? false))))
  (c-redisplay) (c-flush ffi/null)
  (try
    (when (and (:read @state) (not (get-in @state [:read :raw?])))
      (let [text (buffer)
            point (ffi/read (global "rl_point") :int)
            prompt (ffi/ptr->string (ffi/read (global "rl_display_prompt") :pointer))
            used (+ (count prompt) (count text))
            width (columns)]
        ;; Never overlay wrapped or Unicode input; Readline owns its layout.
        (when (and (re-matches #"[\x20-\x7e]*" (str prompt text)) (< used (dec width)))
          (let [hint (when (and eldoc (not= false eldoc?) (= point (count text))) (eldoc text point))
                hint (when hint (subs hint 0 (min (max 0 (- width used 2)) (count hint))))]
            (print (str "\r\u001b[2K" prompt (if (color? ed) (highlight text) text)
                        (when (seq hint) (str " " (when (color? ed) "\u001b[2m") hint (when (color? ed) "\u001b[0m")))
                        "\r" (when (pos? (+ (count prompt) point)) (str "\u001b[" (+ (count prompt) point) "C"))))
            (flush)
            (swap! state assoc :overlay? true)))))
    (catch Exception _)))

(defn- finish-read! [{:keys [state] :as ed} p]
  (try
    (when-let [{:keys [reply raw?]} (:read @state)]
      (let [text (when-not (ffi/null? p) (ffi/ptr->string p))
            value (if (:cancelled? @state) :neorepl.editor/interrupted text)]
        (c-remove)
        (swap! state assoc :handler? false)
        (bridge/input-mode 0)
        (swap! state assoc :read nil :line "" :cancelled? false :overlay? false)
        (when (and (not raw?) (string? value) (not (str/blank? value)))
          (c-add-history value)
          (swap! state update :new-history conj value))
        (deliver reply {:value value})))
    (catch Throwable e
      (when-let [reply (get-in @state [:read :reply])] (deliver reply {:error e}))
      (swap! state assoc :read nil :fatal e))
    (finally (when-not (ffi/null? p) (c-free p)))))

(defn- guarded [{:keys [state]} f default]
  (fn [& args]
    (try (apply f args)
         (catch Throwable e (swap! state assoc :fatal e) default))))

(defn- keep-callback! [{:keys [state]} callback]
  (swap! state update :callbacks conj callback)
  callback)

(defn- initialize! [{:keys [state complete doc incomplete? history-file] :as ed}]
  (c-init)
  (let [saved (into {} (for [[name type] [["rl_redisplay_function" :pointer]
                                         ["rl_getc_function" :pointer]
                                         ["rl_attempted_completion_function" :pointer]
                                         ["rl_completer_word_break_characters" :pointer]
                                         ["rl_catch_signals" :int]]]
                         [name [type (ffi/read (global name) type)]]))
        type-p (ffi/alloc 4)
        keys ["\r" "\n" "\u000a" "\u0003" "\u0018\u0004" "\u001b[A" "\u001b[B" "\u0010" "\u000e"]
        old-keys (try (into {} (for [key keys] [key (c-key-function key (c-keymap) type-p)]))
                      (finally (ffi/free type-p)))
        candidates (atom [])
        breaks (c-strdup " \t\n\"'()[]{};,`~@")]
    (swap! state assoc :saved saved :old-keys old-keys :breaks breaks)
    (let [line (keep-callback! ed (ffi/foreign-callable (guarded ed #(finish-read! ed %) nil) [:pointer] :void :collect-safe))
          redraw (keep-callback! ed (ffi/foreign-callable (guarded ed #(redraw! ed) nil) [] :void :collect-safe))
          generator (keep-callback! ed (ffi/foreign-callable
                      (guarded ed (fn [_text _index] (let [candidate (first @candidates)] (swap! candidates rest)
                                                     (if candidate (c-strdup candidate) ffi/null))) ffi/null)
                      [:string :int] :pointer :collect-safe))
          completion (keep-callback! ed (ffi/foreign-callable
                       (guarded ed (fn [prefix _start _end]
                         (try
                           (ffi/write (global "rl_attempted_completion_over") :int 1 0)
                           (ffi/write (global "rl_completion_suppress_append") :int 1 0)
                           (reset! candidates (when (and complete (seq prefix) (not (get-in @state [:read :raw?])))
                                                (map :candidate (complete prefix))))
                           (c-matches prefix generator)
                           (catch Exception _ ffi/null))) ffi/null)
                       [:string :int :int] :pointer :collect-safe))
          enter (keep-callback! ed (ffi/foreign-callable
                  (guarded ed (fn [n key]
                                (if (and (not (get-in @state [:read :raw?]))
                                         ((or incomplete? lexer/incomplete?) (buffer)))
                                  (do (c-insert "\n") 0)
                                  (c-newline n key))) 0) [:int :int] :int :collect-safe))
          newline (keep-callback! ed (ffi/foreign-callable
                     (guarded ed (fn [n key] (if (get-in @state [:read :raw?]) (c-newline n key) (do (c-insert "\n") 0))) 0)
                     [:int :int] :int :collect-safe))
          cancel (keep-callback! ed (ffi/foreign-callable
                   (guarded ed (fn [n key]
                                 (swap! state assoc :cancelled? true)
                                 (c-replace "" 0) (c-force-redisplay)
                                 (print "^C") (flush)
                                 (c-newline n key)) 0) [:int :int] :int :collect-safe))
          docs (keep-callback! ed (ffi/foreign-callable
                 (guarded ed (fn [_n _key]
                   (try (when (and doc (not (get-in @state [:read :raw?])))
                          (let [text (buffer)] (when-let [d (doc text (cursor text))] (emit! ed (str d "\n")))))
                        (catch Exception _)) 0) 0) [:int :int] :int :collect-safe))
          up (keep-callback! ed (ffi/foreign-callable
               (guarded ed (fn [n key] (if (str/includes? (buffer) "\n") (c-previous-line n key) (c-previous-history n key))) 0)
               [:int :int] :int :collect-safe))
          down (keep-callback! ed (ffi/foreign-callable
                 (guarded ed (fn [n key] (if (str/includes? (buffer) "\n") (c-next-line n key) (c-next-history n key))) 0)
                 [:int :int] :int :collect-safe))]
      (swap! state assoc :line-callback line)
      (doseq [[key callback] [["\r" enter] ["\n" enter] ["\u000a" newline]
                              ["\u0003" cancel] ["\u0018\u0004" docs]
                              ["\u001b[A" up] ["\u001b[B" down] ["\u0010" up] ["\u000e" down]]]
        (when-not (zero? (c-bind key callback)) (throw (ex-info "Cannot bind Readline key" {:key key}))))
      (doseq [[name value] [["rl_redisplay_function" redraw] ["rl_attempted_completion_function" completion]
                            ["rl_completer_word_break_characters" breaks]
                            ["rl_getc_function" (c-symbol bridge-handle "neorepl_read_char")]]]
        (ffi/write (global name) :pointer value 0))
      (ffi/write (global "rl_catch_signals") :int 0 0)
      (c-clear-history) (c-stifle-history 1000)
      (doseq [entry (history-entries history-file)] (c-add-history entry))
      ;; Terminal ownership is acquired last. Everything above is unwound on failure.
      (when-not (zero? (bridge/install)) (throw (ex-info "Cannot configure terminal input" {})))
      (swap! state assoc :installed? true))))

(defn- cleanup! [{:keys [state queue] :as ed} error]
  (when-let [reply (get-in @state [:read :reply])]
    (deliver reply (if error {:error error} {:value nil})))
  (try
    (when (:handler? @state) (c-remove))
    (finally
      (when (:installed? @state) (bridge/restore))
      (doseq [[name [type value]] (:saved @state)] (ffi/write (global name) type value 0))
      (doseq [[key value] (:old-keys @state)] (c-bind key value))
      (doseq [callback (:callbacks @state)] (ffi/free-callable callback))
      (when-let [breaks (:breaks @state)] (c-free breaks))
      (reset! claimed false)))
  (try (save-history! ed)
       (catch Exception e (binding [*out* *err*] (println "neorepl: Couldn't save history:" (ex-message e)))))
  (doseq [{:keys [reply]} (:deferred @state)]
    (deliver reply {:error (or error (ex-info "Editor closed" {}))}))
  (loop []
    (when-let [{:keys [reply]} (.poll queue)]
      (when reply (deliver reply {:error (or error (ex-info "Editor closed" {}))}))
      (recur))))

(defn- command! [{:keys [state] :as ed} {:keys [f reply stop? control?] :as command}]
  (cond
    stop? (swap! state assoc :stop? true)
    (and (:suspended? @state) (not control?)) (swap! state update :deferred conj command)
    :else (try (deliver reply {:value (f)}) (catch Throwable e (deliver reply {:error e})))))

(defn- owner! [{:keys [queue state ready stopped closing owner-id] :as ed}]
  (reset! owner-id (.getId (Thread/currentThread)))
  (let [error (atom nil)]
    (try
      (initialize! ed)
      (deliver ready {:value true})
      (loop []
        (loop [n 0]
          (when (and (< n 100) (not (:stop? @state)))
            (when-let [command (.poll queue)] (command! ed command) (recur (inc n)))))
        (when-let [fatal (:fatal @state)] (throw fatal))
        (when-not (:stop? @state)
          (if (:suspended? @state)
            (Thread/sleep 20)
            (let [flags (bridge/poll 20)]
              (if (:read @state)
                (when (pos? (bit-and flags 1)) (c-read-char) (c-flush ffi/null))
                (do
                  (when (pos? (bit-and flags 2))
                    (fresh! ed)
                    (when-let [interrupt (:interrupt @state)] (interrupt)))
                  (when (pos? (bit-and flags 8))
                    (emit! ed "\nneorepl: Discarded excess type-ahead input\n"))
                  (when (pos? (bit-and flags 4)) (Thread/sleep 20))))))
          (recur)))
      (catch Throwable e (reset! error e) (deliver ready {:error e}))
      (finally
        (locking (:submit-lock ed) (reset! closing true))
        (try (cleanup! ed @error)
             (finally (deliver stopped {:error @error})))))))

(defn- read! [{:keys [state] :as ed} prompt raw?]
  (let [reply (promise)
        started (call! ed
                  #(do
                     (when (:read @state) (throw (ex-info "Editor is already reading" {})))
                     (swap! state assoc :read {:reply reply :raw? raw?} :cancelled? false :line "")
                     (when-not (zero? (bridge/prepare-readline)) (throw (ex-info "Cannot prepare Readline terminal" {})))
                     (c-install prompt (:line-callback @state))
                     (swap! state assoc :handler? true)
                     (when-not (zero? (bridge/input-mode 1)) (throw (ex-info "Cannot enter terminal input mode" {})))
                     true))]
    (when started
      (let [{:keys [value error]} @reply] (when error (throw error)) value))))

(defn close [{:keys [closing queue stopped submit-lock]}]
  (locking submit-lock
    (when (compare-and-set! closing false true) (.put queue {:stop? true})))
  (when (= ::timeout (deref stopped 10000 ::timeout))
    ;; Never free native callbacks from another thread while the owner is using them.
    (throw (ex-info "Readline owner did not finish within 10 seconds" {}))))
(defn width [ed] (or (call! ed columns) 80))
(defn on-interrupt [{:keys [state] :as ed} f] (call! ed #(swap! state assoc :interrupt f)))
(defn fresh-line [ed] (call! ed #(fresh! ed)))
(defn print-text [ed style text]
  (call! ed #(emit! ed (if (and (color? ed) (#{:err :notice} style))
                        (str "\u001b[" (if (= style :err) "31" "2") "m" text "\u001b[0m") text))))
(defn print-code [{:keys [highlight] :as ed} text]
  (call! ed #(emit! ed (if (color? ed) (highlight text) text))))
(defn read-code [ed prompt] (read! ed prompt false))
(defn read-line [{:keys [state] :as ed}]
  (let [prompt (call! ed #(let [line (:line @state)] (when (seq line) (print "\r\u001b[2K") (flush)) line))]
    (read! ed (or prompt "") true)))
(defn suspended [{:keys [state] :as ed} f]
  (call! ed #(do
               (when (:read @state) (throw (ex-info "Cannot hand over the terminal while reading" {})))
               (bridge/restore)
               (swap! state assoc :suspended? true :installed? false)))
  (try (f)
       (finally
         (call! ed
           #(do (when-not (zero? (bridge/install))
                  (let [e (ex-info "Cannot restore terminal mode" {})]
                    (swap! state assoc :fatal e :suspended? false)
                    (throw e)))
                (swap! state assoc :suspended? false :installed? true)
                (let [commands (:deferred @state)]
                  (swap! state assoc :deferred [])
                  (doseq [command commands] (command! ed command))))
           true))))

(defn open [opts]
  (when-not (compare-and-set! claimed false true) (throw (ex-info "Readline editor is already open" {})))
  (let [ed (assoc opts :queue (LinkedBlockingQueue.)
                 :state (atom {:callbacks [] :new-history [] :above "" :line "" :deferred []})
                 :closing (atom false) :owner-id (atom nil) :submit-lock (Object.) :ready (promise) :stopped (promise)
                 :backend {:close close :width width :on-interrupt on-interrupt :fresh-line fresh-line
                           :print-text print-text :print-code print-code :read-code read-code
                           :read-line read-line :suspended suspended})
        thread (try
                 (doto (Thread. #(owner! ed) "neorepl-readline") (.setDaemon true) (.start))
                 (catch Throwable e (reset! claimed false) (throw e)))
        {:keys [error]} @(:ready ed)]
    (when error @(:stopped ed) (throw error))
    (assoc ed :thread thread)))
