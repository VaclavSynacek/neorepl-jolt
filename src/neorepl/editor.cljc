(ns neorepl.editor
  "The REPL's line editor, on JLine. This is the only namespace that touches
  JLine, so when its API moves again, the damage stays here."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [neorepl.lexer :as lexer])
  (:import #?(:bb (org.jline.reader ParsedLine)
              :clj (org.jline.reader CompletingParsedLine))
           (org.jline.reader EOFError EndOfFileException Highlighter LineReader
                             LineReader$Option LineReaderBuilder Parser
                             Parser$ParseContext UserInterruptException)
           (org.jline.terminal Size Terminal Terminal$Signal Terminal$SignalHandler
                               TerminalBuilder)
           (org.jline.utils AttributedString AttributedStringBuilder AttributedStyle)))

(defn- fg ^AttributedStyle [color]
  (.foreground AttributedStyle/DEFAULT (int color)))

(def ^:private colors
  "Styles for lexer token types, and for :err output and :notice messages."
  (let [faint (.faint AttributedStyle/DEFAULT)
        green (fg AttributedStyle/GREEN)
        magenta (fg AttributedStyle/MAGENTA)
        yellow (fg AttributedStyle/YELLOW)]
    {:comment faint
     :discard faint
     :string green
     :open-string green
     :regex green
     :char green
     :keyword (fg AttributedStyle/CYAN)
     :number magenta
     :constant magenta
     :special (.bold (fg AttributedStyle/BLUE))
     :reader-macro yellow
     :macro-char yellow
     :err (fg AttributedStyle/RED)
     :notice faint}))

(def ^:private specials
  "Symbols highlighted as :special: the special forms and the defining and
  binding macros that read like them."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol" "defrecord"
    "deftype" "definterface" "defonce" "fn" "fn*" "let" "letfn" "loop" "recur" "if"
    "if-let" "if-some" "if-not" "when" "when-let" "when-some" "when-not" "do" "cond"
    "condp" "case" "ns" "quote" "try" "catch" "finally" "throw" "var" "new" "set!"
    "binding" "doseq" "dotimes" "for"})

(defn- style ^AttributedStyle [styles k]
  (styles k AttributedStyle/DEFAULT))

(defn- highlight
  "code as an AttributedString, with its tokens styled."
  ^AttributedString [styles ^String code]
  (let [sb (AttributedStringBuilder.)
        end (reduce (fn [pos {:keys [type start end]}]
                      (let [text (subs code start end)]
                        (.append sb (subs code pos start))
                        (.styled sb (style styles (if (and (= :symbol type) (specials text)) :special type))
                                 text)
                        end))
                    0
                    (lexer/tokenize code))]
    (.append sb (subs code end))
    (.toAttributedString sb)))

(defn- parsed-line
  "A ParsedLine for line that's all one word, which is all JLine needs to know
  until there's completion. On the JVM it's a CompletingParsedLine, without
  which JLine warns, but babashka doesn't have that interface."
  [^String line cursor]
  (reify #?(:bb ParsedLine :clj CompletingParsedLine)
    (word [_] line)
    (wordCursor [_] cursor)
    (wordIndex [_] 0)
    (words [_] [line])
    (line [_] line)
    (cursor [_] cursor)
    #?@(:bb []
        :clj [(escape [_ candidate _] candidate)
              (rawWordCursor [_] cursor)
              (rawWordLength [_] (count line))])))

(defn- parser
  "Tells JLine to keep reading when the forms aren't complete, unless it's
  reading a raw line."
  [raw?]
  (reify Parser
    (parse [_ line cursor context]
      (when (and (= Parser$ParseContext/ACCEPT_LINE context)
                 (not @raw?)
                 (lexer/incomplete? (lexer/lex line)))
        (throw (EOFError. -1 -1 "Incomplete form")))
      (parsed-line line cursor))))

(defn- highlighter [styles raw?]
  (reify Highlighter
    (highlight [_ _ buffer]
      (if @raw?
        (AttributedString. buffer)
        (highlight styles buffer)))
    (setErrorPattern [_ _])
    (setErrorIndex [_ _])))

(defn- terminal
  "The system terminal, or one on input and output for tests."
  ^Terminal [{:keys [input output width type] :or {type "xterm-256color"}}]
  (if input
    (-> (TerminalBuilder/builder)
        (.system false)
        ;; The other providers wrap the streams in a pty.
        (.provider "exec")
        (.streams input output)
        (.type ^String type)
        (.size (Size. (int width) 24))
        (.build))
    (-> (TerminalBuilder/builder)
        (.system true)
        ;; Without a terminal, a dumb one rather than a warning.
        (.dumb true)
        (.build))))

(defn- maybe-terminal?
  "False when there's clearly no terminal to edit on, which is quicker to tell
  than having JLine find out. Since Java 22 there's always a console, so it's
  only quick before that."
  []
  (boolean (and (System/console)
                (not= "dumb" (System/getenv "TERM")))))

(defn open
  "A line editor on the terminal, or nil when there's no terminal worth
  editing on: when standard input or output isn't one, or it's a dumb one, as
  in Emacs' M-x shell. opts:

    :history-file  where to keep the history
    :color?        false for no colors
    :input, :output, :width and :type
                   a terminal on these streams instead, for tests"
  [{:keys [history-file color? input] :or {color? true} :as opts}]
  (when (or input (maybe-terminal?))
    (let [terminal (terminal opts)]
      (if (str/starts-with? (.getType terminal) "dumb")
        (do (.close terminal)
            nil)
        (let [raw? (atom false)
              styles (if color? colors {})
              builder (-> (LineReaderBuilder/builder)
                          (.terminal terminal)
                          (.appName "neorepl")
                          (.parser (parser raw?))
                          (.highlighter (highlighter styles raw?))
                          (.variable LineReader/SECONDARY_PROMPT_PATTERN "%P #_=> ")
                          ;; ! is just a character in Clojure code.
                          (.option LineReader$Option/DISABLE_EVENT_EXPANSION true))]
          (when history-file
            (.variable builder LineReader/HISTORY_FILE (str history-file)))
          {:terminal terminal
           :reader (.build builder)
           :raw? raw?
           :styles styles
           :lock (Object.)
           ;; What's been printed of the line the cursor's on.
           :line (atom "")
           :above (atom (AttributedString. ""))})))))

(defn close [{:keys [^Terminal terminal]}]
  (.close terminal))

(defn width
  "The terminal's width in columns."
  [{:keys [^Terminal terminal]}]
  (let [width (.getWidth terminal)]
    (if (pos? width) width 80)))

(defn- emit
  "Prints s right away, or above the prompt while a line is being read.
  Printing above the prompt ends the text with a newline, so that only gets
  whole lines, and the rest of a line waits for the newline ending it."
  [{:keys [^Terminal terminal ^LineReader reader lock line above]} ^AttributedString s]
  (locking lock
    (let [^AttributedString s (if (pos? (.length ^AttributedString @above))
                                (.toAttributedString (doto (AttributedStringBuilder.)
                                                       (.append ^AttributedString @above)
                                                       (.append s)))
                                s)
          text (str s)]
      (if (.isReading reader)
        (if-let [end (str/last-index-of text "\n")]
          (do (.printAbove reader (.subSequence s 0 (int end)))
              (reset! above (.subSequence s (int (inc end)) (count text))))
          (reset! above s))
        (do (reset! above (AttributedString. ""))
            (when (seq text)
              (.print s terminal)
              (.flush terminal)
              (swap! line #(if-let [end (str/last-index-of text "\n")]
                             (subs text (inc end))
                             (str % text)))))))))

(defn print-text
  "Prints text in the style k names, such as :err, or nil for none."
  [{:keys [styles] :as editor} k text]
  (emit editor (AttributedString. ^CharSequence text (style styles k))))

(defn print-code
  "Prints code highlighted."
  [{:keys [styles] :as editor} code]
  (emit editor (highlight styles code)))

(defn fresh-line
  "Ends the line output left unfinished, if any."
  [{:keys [line] :as editor}]
  (when (seq @line)
    (print-text editor nil "\n")))

(defn on-interrupt
  "Calls f on Ctrl-C, unless a line is being read: then Ctrl-C clears it."
  [{:keys [^Terminal terminal] :as editor} f]
  (.handle terminal Terminal$Signal/INT (reify Terminal$SignalHandler
                                          (handle [_ _]
                                            ;; Ends the line the terminal echoed ^C on.
                                            (print-text editor nil "\n")
                                            (f)))))

(defn- read-with
  "Reads a line with prompt. JLine always ends that line."
  [{:keys [^Terminal terminal ^LineReader reader line]} prompt]
  (try
    (.readLine reader ^String prompt)
    (catch UserInterruptException e
      ;; On babashka, Ctrl-C during a read arrives as a signal and as a
      ;; character too, and the character would interrupt the next read.
      (let [input (.reader terminal)]
        (when (= 3 (.peek input 20))
          (.read input 20)))
      (throw e))
    (finally
      (reset! line ""))))

(defn read-code
  "Reads code from the user with prompt. Enter only submits it once its forms
  are complete, and Ctrl-C starts over. Returns nil at the end of input, which
  is Ctrl-D on an empty line."
  [editor prompt]
  (loop []
    (let [line (try
                 (read-with editor prompt)
                 (catch UserInterruptException _ ::interrupted)
                 (catch EndOfFileException _ nil))]
      (if (= ::interrupted line)
        (recur)
        line))))

(defn read-line
  "Reads a line as typed, for code reading *in*, leaving it out of the
  history. Returns nil at the end of input and ::interrupted on Ctrl-C."
  [{:keys [^LineReader reader raw? line] :as editor}]
  (reset! raw? true)
  (.setVariable reader LineReader/DISABLE_HISTORY true)
  (try
    ;; JLine redraws the line it reads, so whatever's been printed on it,
    ;; like a question the code asks, goes in the prompt.
    (read-with editor @line)
    (catch EndOfFileException _ nil)
    (catch UserInterruptException _ ::interrupted)
    (finally
      (.setVariable reader LineReader/DISABLE_HISTORY false)
      (reset! raw? false))))
