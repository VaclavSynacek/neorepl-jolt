(ns neorepl.repl
  "The REPL, with a line editor on a terminal and without one elsewhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.editor :as editor]
            [neorepl.evaluation :as evaluation]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl])
  (:import (java.security MessageDigest)
           (sun.misc Signal SignalHandler)))

(defn- eval-msg
  "The eval message for code, with values pretty printed width columns wide.
  Servers without nREPL's printer say so and print the plain way."
  [session code width]
  {:op "eval"
   :code code
   :session session
   :nrepl.middleware.print/print "nrepl.util.print/pprint"
   :nrepl.middleware.print/options {:right-margin width}})

(defn- read-input
  "Reads lines from *in* until they hold complete forms, or nil at the end of
  the input. Incomplete forms at the end go to the server as they are, so
  it can report them."
  []
  (loop [code nil]
    (if-let [line (read-line)]
      (let [code (if code (str code "\n" line) line)]
        (if (lexer/incomplete? (lexer/lex code))
          (recur code)
          code))
      code)))

(defn- on-sigint
  "Calls f on SIGINT, returning the handler it replaces."
  ^SignalHandler [f]
  (Signal/handle (Signal. "INT") (reify SignalHandler
                                   (handle [_ _] (f)))))

(defn- interruptions
  "What Ctrl-C needs in a REPL on session: the id of the evaluation
  :running, the id of the one :interrupted last, and an :interrupt function
  that interrupts the running evaluation and returns its id, or nil when
  nothing's running."
  [client session]
  (let [running (atom nil)
        interrupted (atom nil)]
    {:running running
     :interrupted interrupted
     :interrupt #(when-let [id @running]
                   (reset! interrupted id)
                   ;; Not on the thread that delivers signals and keystrokes.
                   (future (nrepl/interrupt client session id))
                   id)}))

(defn- run-loop
  "Reads code with read-code, a function of the prompt, and evaluates it in
  session until the input ends or says :repl/quit. Responses go to
  print-response, late ones too, and result messages to print-message.

  What an interrupted evaluation says from then on is left out, since it
  comes from it dying: an InterruptedException, or code carrying on after a
  read from *in* got cut short."
  [client session {:keys [running interrupted]} {:keys [read-code read-line width print-response print-message]}]
  (let [print-response #(when-not (= (:id %) @interrupted)
                          (print-response %))]
    (nrepl/on-unclaimed! client print-response)
    (loop [ns "user"]
      (let [code (read-code (str ns "=> "))]
        (cond
          (or (nil? code) (= ":repl/quit" (str/trim code))) nil
          (str/blank? code) (recur ns)
          :else
          (let [_ (reset! interrupted nil)
                {:keys [status message] :as result}
                (evaluation/evaluate client
                                     (eval-msg session code (width))
                                     (cond-> {:on-send #(reset! running %)
                                              :on-response print-response
                                              :collect? false}
                                       read-line (assoc :read-line read-line)))]
            (reset! running nil)
            ;; There's no point in reading more input then.
            (when (= "disconnected" status)
              (throw (ex-info message {:type ::nrepl/disconnected})))
            (some-> message print-message)
            (recur (or (:ns result) ns))))))))

(defn- plain
  "A REPL without a line editor, for when there's no terminal to edit on:
  pipes, Emacs' M-x shell and the like. Reads from *in* and prints prompts and
  results to *out*, the way clojure.main's REPL does."
  [client session]
  (let [{:keys [interrupt] :as interruptions} (interruptions client session)
        ;; With nothing to interrupt, Ctrl-C is for the REPL.
        previous (on-sigint #(when-not (interrupt) (System/exit 130)))]
    (try
      (run-loop client session interruptions
                {:read-code (fn [prompt]
                              (print prompt)
                              (flush)
                              (read-input))
                 :width (constantly 80)
                 :print-response (evaluation/text-printer)
                 :print-message #(binding [*out* *err*]
                                   (println %))})
      ;; Ends the line the last prompt is on.
      (newline)
      (finally
        (Signal/handle (Signal. "INT") previous)))))

(defn- printer
  "A response handler printing output and values on editor."
  [editor]
  (let [stream (atom nil)
        ;; stdout and stderr share the terminal, so each gets lines of its own.
        switch-to #(when-not (= % (first (reset-vals! stream %)))
                     (editor/fresh-line editor))]
    (fn [{:keys [out err value]}]
      (when out
        (switch-to :out)
        (editor/print-text editor nil out))
      (when err
        (switch-to :err)
        (editor/print-text editor :err err))
      (when value
        (editor/fresh-line editor)
        (editor/print-code editor (str value "\n"))))))

(defn- banner [{:keys [address versions]}]
  (let [{:keys [babashka clojure]} versions]
    (str "neorepl connected to " address
         (cond
           babashka (str " (babashka " babashka ")")
           clojure (str " (Clojure " clojure ")"))
         ". Ctrl-D or :repl/quit exits.\n")))

(defn- interactive
  "The REPL with a line editor."
  [client session editor]
  (let [{:keys [interrupt] :as interruptions} (interruptions client session)]
    (editor/on-interrupt editor interrupt)
    (editor/print-text editor :notice (banner client))
    (run-loop client session interruptions
              {:read-code (fn [prompt]
                            (editor/fresh-line editor)
                            (editor/read-code editor prompt))
               :read-line #(let [line (editor/read-line editor)]
                             (if (= ::editor/interrupted line)
                               (do (interrupt) :interrupted)
                               line))
               :width #(editor/width editor)
               :print-response (printer editor)
               :print-message #(do (editor/fresh-line editor)
                                   (editor/print-text editor :notice (str % "\n")))})))

(defn- history-file
  "The history for the project in dir: a file in history-dir named after a
  hash of dir's path, which could be too long for a file name itself."
  [history-dir dir]
  (let [path (.getCanonicalPath (io/file dir))
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes path "UTF-8"))]
    (io/file history-dir (format "%064x" (BigInteger. 1 digest)))))

(defn start
  "Runs a REPL in session until the user ends it: the interactive kind when
  the line editor can open the terminal, the plain kind otherwise. opts:

    :terminal     options for editor/open, nil for the plain REPL regardless
    :history-dir  where to keep the history, per project
    :dir          the project's directory when the client doesn't say"
  [client session {:keys [terminal history-dir dir]}]
  (if-let [editor (some-> terminal
                          (assoc :history-file (history-file history-dir (or (:project-dir client) dir)))
                          editor/open)]
    (try
      (interactive client session editor)
      (finally
        (editor/close editor)))
    (plain client session)))
