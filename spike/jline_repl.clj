(ns jline-repl
  "Throwaway spike (see PLAN.md): a minimal JLine 4 + nrepl.core REPL client
  that runs unchanged on babashka 1.13.221+ and on the JVM (Java 11+).

    bb jline_repl.clj [port]
    clojure -M jline_repl.clj [port]

  Run from this directory. Without a port it reads .nrepl-port."
  (:require [clojure.string :as str]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport])
  (:import (java.util List UUID)
           (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit)
           (org.jline.keymap KeyMap)
           (org.jline.reader Candidate Completer EOFError EndOfFileException Highlighter
                              LineReader LineReader$Option LineReader$SuggestionType
                              LineReaderBuilder ParsedLine Parser Parser$ParseContext
                              Reference UserInterruptException Widget)
           (org.jline.reader.impl LineReaderImpl)
           (org.jline.terminal Terminal Terminal$Signal Terminal$SignalHandler TerminalBuilder)
           (org.jline.utils AttributedString AttributedStringBuilder AttributedStyle)))

;;; Lexer: just enough to find form boundaries, color tokens and locate symbols.

(def ^:private terminators #{\( \) \[ \] \{ \} \" \, \;})

(defn- atom-end? [c]
  (or (Character/isWhitespace (char c)) (contains? terminators c)))

(defn- scan-atom [^String s i]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (not (atom-end? (.charAt s j)))) (recur (inc j)) j))))

(defn- scan-string
  "Index just past the closing quote of the string opening at i, or nil."
  [^String s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (cond (>= j n) nil
            (= \\ (.charAt s j)) (recur (+ j 2))
            (= \" (.charAt s j)) (inc j)
            :else (recur (inc j))))))

(def ^:private specials
  #{"def" "defn" "defn-" "defmacro" "fn" "fn*" "let" "letfn" "loop" "recur" "if" "do"
    "when" "cond" "case" "ns" "quote" "try" "catch" "finally" "throw" "var" "new" "set!"})

(defn- atom-type [^String text]
  (cond (str/starts-with? text ":") :keyword
        (re-find #"^[+-]?\d" text) :number
        (#{"nil" "true" "false"} text) :constant
        (specials text) :special
        :else :symbol))

(defn- next-token
  "Returns [type end] for the token starting at i; type is nil for whitespace."
  [^String s i]
  (let [n (count s)
        c (.charAt s i)
        nxt (when (< (inc i) n) (.charAt s (inc i)))]
    (cond
      (or (Character/isWhitespace c) (= c \,)) [nil (inc i)]
      (= c \;) [:comment (or (str/index-of s "\n" i) n)]
      (= c \") (if-let [j (scan-string s i)] [:string j] [:open-string n])
      (= c \\) [:char (scan-atom s (min n (+ i 2)))]
      (#{\( \[ \{} c) [:open (inc i)]
      (#{\) \] \}} c) [:close (inc i)]
      (and (= c \#) (#{\( \{} nxt)) [:open (+ i 2)]
      (and (= c \#) (= \" nxt)) (if-let [j (scan-string s (inc i))] [:string j] [:open-string n])
      (and (= c \#) (= \_ nxt)) [:discard (+ i 2)]
      (= c \#) [:reader-macro (max (scan-atom s (inc i)) (inc i))]
      (#{\' \` \~ \@ \^} c) [:macro-char (inc i)]
      :else (let [j (scan-atom s i)] [(atom-type (subs s i j)) j]))))

(defn tokenize
  "Splits s into [type start end] tokens, skipping whitespace."
  [^String s]
  (loop [i 0 acc []]
    (if (< i (count s))
      (let [[type end] (next-token s i)]
        (recur end (cond-> acc type (conj [type i end]))))
      acc)))

(defn incomplete?
  "True when s has an unterminated string or more openers than closers."
  [s]
  (let [ts (tokenize s)]
    (boolean
     (or (some (fn [[type]] (= :open-string type)) ts)
         (pos? (reduce (fn [depth [type]]
                         (case type :open (inc depth) :close (max 0 (dec depth)) depth))
                       0 ts))))))

(defn- symbol-at
  "The [type start end] symbol or keyword token touching cursor, if any."
  [s cursor]
  (some (fn [[type start end :as tok]]
          (when (and (<= start cursor end) (#{:symbol :special :keyword} type)) tok))
        (tokenize s)))

(defn enclosing-fn
  "The operator of the innermost list around cursor, e.g. \"map\" for \"(map inc |\"."
  [^String s cursor]
  (let [ts (filterv (fn [[_ start]] (< start cursor)) (tokenize s))
        opens (reduce-kv (fn [stack idx [type]]
                           (case type
                             :open (conj stack idx)
                             :close (if (seq stack) (pop stack) stack)
                             stack))
                         [] ts)]
    (when-let [idx (peek opens)]
      (let [[_ start end] (ts idx)
            [type fstart fend] (get ts (inc idx))]
        (when (and (str/ends-with? (subs s start end) "(") (#{:symbol :special} type))
          (subs s fstart fend))))))

;;; Highlighting

(defn- fg ^AttributedStyle [color]
  (.foreground AttributedStyle/DEFAULT (int color)))

(def ^:private styles
  {:comment (.faint AttributedStyle/DEFAULT)
   :discard (.faint AttributedStyle/DEFAULT)
   :string (fg AttributedStyle/GREEN)
   :open-string (fg AttributedStyle/GREEN)
   :char (fg AttributedStyle/GREEN)
   :keyword (fg AttributedStyle/CYAN)
   :number (fg AttributedStyle/MAGENTA)
   :constant (fg AttributedStyle/MAGENTA)
   :special (.bold (fg AttributedStyle/BLUE))
   :reader-macro (fg AttributedStyle/YELLOW)
   :macro-char (fg AttributedStyle/YELLOW)})

(def ^:private err-style (fg AttributedStyle/RED))

(defn highlight ^AttributedString [^String s]
  (let [sb (AttributedStringBuilder.)
        pos (reduce (fn [pos [type start end]]
                      (.append sb (subs s pos start))
                      (if-let [style (styles type)]
                        (.styled sb ^AttributedStyle style (subs s start end))
                        (.append sb (subs s start end)))
                      end)
                    0 (tokenize s))]
    (.append sb (subs s pos))
    (.toAttributedString sb)))

;;; nREPL plumbing: one reader thread routes responses to per-request queues.
;;; Responses nobody waits for (output from futures after their eval finished,
;;; interrupt/stdin acks) go to the :orphan handler.

(defn- done? [msg] (some #{"done"} (:status msg)))

(defn- new-id [] (str (UUID/randomUUID)))

(defn- connect [port]
  (let [conn (nrepl/connect :host "127.0.0.1" :port port)
        client {:conn conn :pending (ConcurrentHashMap.) :orphan (atom (fn [_]))}]
    (future
      (try
        (loop []
          (let [msg (transport/recv conn)]
            (if-let [q (.get ^ConcurrentHashMap (:pending client) (:id msg))]
              (.put ^LinkedBlockingQueue q msg)
              (@(:orphan client) msg))
            (recur)))
        (catch Throwable _)))
    client))

(defn- fire! [{:keys [conn]} msg]
  (transport/send conn (assoc msg :id (new-id))))

(defn- send! [{:keys [conn pending]} msg]
  (let [id (new-id)
        q (LinkedBlockingQueue.)]
    (.put ^ConcurrentHashMap pending id q)
    (transport/send conn (assoc msg :id id))
    {:id id :queue q}))

(defn- request
  "Sends msg and returns its responses, up to and including done."
  [client msg]
  (let [{:keys [id queue]} (send! client msg)]
    (loop [acc []]
      (let [m (.poll ^LinkedBlockingQueue queue 10 TimeUnit/SECONDS)]
        (if (or (nil? m) (done? m))
          (do (.remove ^ConcurrentHashMap (:pending client) id)
              (cond-> acc m (conj m)))
          (recur (conj acc m)))))))

;;; Output: straight to the terminal while evaluating, above the prompt while reading.

(defn- emit! [{:keys [^Terminal terminal reader last-char]} ^AttributedString as]
  (let [^LineReader r @reader
        s (str as)]
    (when (seq s)
      (if (and r (.isReading r))
        (.printAbove r ^AttributedString (cond-> as
                                           (str/ends-with? s "\n") (.subSequence 0 (dec (count s)))))
        (do (doto (.writer terminal) (.print (.toAnsi as terminal)) (.flush))
            (reset! last-char (last s)))))))

(defn- fresh-line! [{:keys [^Terminal terminal last-char]}]
  (when-not (contains? #{nil \newline} @last-char)
    (doto (.writer terminal) (.println) (.flush))
    (reset! last-char \newline)))

(defn- print-response! [ctx {:keys [out err value]}]
  (when out (emit! ctx (AttributedString. ^String out)))
  (when err (emit! ctx (AttributedString. ^String err err-style)))
  (when value (emit! ctx (highlight (str value "\n")))))

;;; Tooling: completion, eldoc, doc

(defn- completions [{:keys [client tooling ns ops]} prefix]
  (let [op (if (ops "complete") "complete" "completions")]
    (some :completions (request client {:op op :prefix prefix :ns @ns :session tooling}))))

(defn- lookup [{:keys [client tooling ns lookups]} sym]
  (let [k [@ns sym]]
    (if (contains? @lookups k)
      (@lookups k)
      (let [info (not-empty (some :info (request client {:op "lookup" :sym sym :ns @ns :session tooling})))]
        (swap! lookups assoc k info)
        info))))

(defn- update-eldoc! [ctx ^LineReaderImpl reader]
  (let [buf (.getBuffer reader)
        tip (some->> (enclosing-fn (str buf) (.cursor buf))
                     (lookup ctx)
                     :arglists-str
                     (re-find #"^\((.*)\)$")
                     second)]
    (.setTailTip reader (or tip ""))))

(defn- show-doc! [ctx ^LineReader reader]
  (let [buf (.getBuffer reader)
        s (str buf)]
    (when-let [sym (or (when-let [[_ start end] (symbol-at s (.cursor buf))] (subs s start end))
                       (enclosing-fn s (.cursor buf)))]
      (when-let [{:keys [ns name arglists-str doc]} (lookup ctx sym)]
        (.printAbove reader (str/join "\n" (remove nil? ["-------------------------"
                                                         (str ns "/" name)
                                                         arglists-str
                                                         (some->> doc (str "  "))])))))
    true))

;;; JLine glue

(defn- parsed-line [^String line cursor]
  (let [[_ start] (symbol-at line cursor)
        start (or start cursor)
        word (subs line start cursor)]
    (reify ParsedLine
      (word [_] word)
      (wordCursor [_] (- cursor start))
      (wordIndex [_] 0)
      (words [_] [word])
      (line [_] line)
      (cursor [_] cursor))))

(defn- parser [{:keys [stdin-mode]}]
  (reify Parser
    (parse [_ line cursor context]
      (when (and (= context Parser$ParseContext/ACCEPT_LINE)
                 (not @stdin-mode)
                 (incomplete? line))
        (throw (EOFError. -1 -1 "Incomplete form")))
      (parsed-line line cursor))))

(defn- completer [ctx]
  (reify Completer
    (complete [_ _ line candidates]
      (let [prefix (.word ^ParsedLine line)]
        (when-not (str/blank? prefix)
          (doseq [{:keys [candidate type]} (completions ctx prefix)]
            (.add ^List candidates (Candidate. candidate candidate nil (some-> type name) nil nil false))))))))

(def ^:private highlighter
  (reify Highlighter
    (highlight [_ _ buffer] (highlight buffer))
    (refresh [_ _])
    (setErrorPattern [_ _])
    (setErrorIndex [_ _])))

(defn- widget ^Widget [f]
  (reify Widget (apply [_] (boolean (f)))))

(defn- add-widget! [^LineReader reader name keys f]
  (.put (.getWidgets reader) name (widget f))
  (.bind ^KeyMap (.get (.getKeyMaps reader) LineReader/EMACS) (Reference. name) ^CharSequence keys))

(defn- wrap-widget! [^LineReader reader name after]
  (let [^Widget orig (.get (.getWidgets reader) name)]
    (.put (.getWidgets reader) name (widget #(let [r (.apply orig)] (after) r)))))

(defn- line-reader [ctx terminal]
  (let [reader (-> (LineReaderBuilder/builder)
                   (.terminal terminal)
                   (.appName "neorepl-spike")
                   (.parser (parser ctx))
                   (.completer (completer ctx))
                   (.highlighter highlighter)
                   (.variable LineReader/HISTORY_FILE
                              (str (System/getProperty "java.io.tmpdir") "/neorepl-spike-history"))
                   (.variable LineReader/SECONDARY_PROMPT_PATTERN "%P #_=> ")
                   (.option LineReader$Option/DISABLE_EVENT_EXPANSION true)
                   (.build))]
    (.setAutosuggestion ^LineReaderImpl reader LineReader$SuggestionType/TAIL_TIP)
    (add-widget! reader "neorepl-doc" (str (KeyMap/ctrl \X) (KeyMap/ctrl \D)) #(show-doc! ctx reader))
    (doseq [w [LineReader/SELF_INSERT LineReader/BACKWARD_DELETE_CHAR]]
      (wrap-widget! reader w #(update-eldoc! ctx reader)))
    reader))

;;; The loop

(defn- read-stdin! [{:keys [client session reader stdin-mode]}]
  (let [line (try (reset! stdin-mode true)
                  (.readLine ^LineReader @reader "")
                  (catch EndOfFileException _ nil)
                  (catch UserInterruptException _ nil)
                  (finally (reset! stdin-mode false)))]
    (fire! client {:op "stdin" :session session :stdin (if line (str line "\n") "")})))

(defn- eval! [{:keys [client session ns eval-id] :as ctx} code]
  (let [{:keys [id queue]} (send! client {:op "eval" :code code :session session})]
    (reset! eval-id id)
    (try
      (loop []
        (let [{:keys [status] :as m} (.take ^LinkedBlockingQueue queue)]
          (print-response! ctx m)
          (when (:ns m) (reset! ns (:ns m)))
          (when (some #{"interrupted"} status)
            (emit! ctx (AttributedString. "Interrupted\n" err-style)))
          (when (some #{"need-input"} status)
            (read-stdin! ctx))
          (when-not (done? m) (recur))))
      (finally
        (reset! eval-id nil)
        (.remove ^ConcurrentHashMap (:pending client) id)
        (fresh-line! ctx)))))

(defn- interrupt! [{:keys [client session eval-id]}]
  (when-let [id @eval-id]
    (fire! client {:op "interrupt" :session session :interrupt-id id})))

(defn- runtime []
  (if-let [v (System/getProperty "babashka.version")]
    (str "babashka " v)
    (str "JVM " (System/getProperty "java.version") ", Clojure " (clojure-version))))

(defn -main [& [port]]
  (let [port (Long/parseLong (str/trim (or port (slurp ".nrepl-port"))))
        client (connect port)
        {:keys [versions ops]} (first (request client {:op "describe"}))
        terminal (-> (TerminalBuilder/builder) (.system true) (.build))
        ctx {:client client
             :session (some :new-session (request client {:op "clone"}))
             :tooling (some :new-session (request client {:op "clone"}))
             :ops (set (map name (keys ops)))
             :terminal terminal
             :reader (atom nil)
             :ns (atom "user")
             :eval-id (atom nil)
             :stdin-mode (atom false)
             :last-char (atom nil)
             :lookups (atom {})}
        reader (line-reader ctx terminal)]
    (reset! (:reader ctx) reader)
    (reset! (:orphan client) #(print-response! ctx %))
    (.handle terminal Terminal$Signal/INT (reify Terminal$SignalHandler (handle [_ _] (interrupt! ctx))))
    (println (format "neorepl spike on %s; terminal: %s (%s); server: %s; %d ops"
                     (runtime) (.getType terminal) (.getSimpleName (class terminal))
                     (str/join ", " (map (fn [[k v]] (str (name k) " " (:version-string v))) versions))
                     (count ops)))
    (loop []
      (.setTailTip ^LineReaderImpl reader "")
      (let [line (try (.readLine reader (str @(:ns ctx) "=> "))
                      (catch UserInterruptException _ "")
                      (catch EndOfFileException _ nil))]
        (cond
          (nil? line) nil
          (#{":repl/quit" ",quit"} (str/trim line)) nil
          (str/blank? line) (recur)
          :else (do (eval! ctx line) (recur)))))
    (.close terminal)
    (System/exit 0)))

(apply -main *command-line-args*)
