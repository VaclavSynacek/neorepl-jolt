(ns neorepl.terminal-bridge
  "Native terminal input bridge; requires an existing C compiler on first use."
  (:require [clojure.java.io :as io]
            [jolt.ffi :as ffi]))

(def library
  (let [source (io/file (.getPath (io/resource "neorepl/readline_bridge.c")))
        output (io/file (.getParentFile source) "../../.jolt/neorepl-readline.so")]
    (when (or (not (.isFile output)) (> (.lastModified source) (.lastModified output)))
      (io/make-parents output)
      (let [process (-> (ProcessBuilder. ["cc" "-shared" "-fPIC" "-Wall" "-Wextra" "-O2"
                                         (.getPath source) "-o" (.getPath output)])
                        .inheritIO .start)]
        (when-not (zero? (.waitFor process))
          (throw (ex-info "Could not build Readline terminal bridge" {})))))
    (.getCanonicalPath output)))

(ffi/load-library library)
(ffi/defcfn install "neorepl_terminal_install" [] :int)
(ffi/defcfn restore "neorepl_terminal_restore" [] :void)
(ffi/defcfn input-mode "neorepl_input_mode" [:int] :void)
(ffi/defcfn poll "neorepl_key_poll" [] :int)
