(ns neorepl.terminal-bridge
  "Loads a prebuilt bridge. Runtime startup never invokes a compiler."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]))

(def library
  (let [suffix (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
                 "dylib" "so")
        resource (io/resource (str "libneorepl-readline." suffix))
        path (or (System/getenv "NEOREPL_READLINE_BRIDGE")
                 (when resource (.getPath resource)))]
    (when-not (and path (.isFile (io/file path)))
      (throw (ex-info "Readline bridge missing: run bash build/jolt-native.sh, or set NEOREPL_READLINE_BRIDGE to a prebuilt library"
                      {:type :neorepl.editor/unavailable})))
    (.getCanonicalPath (io/file path))))

(ffi/load-library library)
(ffi/defcfn version "neorepl_bridge_version" [] :int)
(when-not (= 2 (version))
  (throw (ex-info "Readline bridge is outdated: rebuild with bash build/jolt-native.sh"
                  {:type :neorepl.editor/unavailable})))
(ffi/defcfn install "neorepl_terminal_install" [] :int)
(ffi/defcfn restore "neorepl_terminal_restore" [] :void)
(ffi/defcfn prepare-readline "neorepl_readline_prepare" [] :int)
(ffi/defcfn input-mode "neorepl_input_mode" [:int] :int)
(ffi/defcfn poll "neorepl_input_poll" [:int] :int)
