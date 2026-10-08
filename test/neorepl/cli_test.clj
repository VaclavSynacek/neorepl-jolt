(ns neorepl.cli-test
  (:require [clojure.test :refer [are deftest is testing]]
            [neorepl.cli :as cli]))

(def ^:private specs
  [{:short "-p" :long "--port" :arg "PORT" :parse #(Long/parseLong %) :doc "The port"}
   {:long "--format" :arg "FORMAT" :parse #{"text" "edn"} :default "text" :doc "Output format"}
   {:short "-h" :long "--help" :doc "Help"}])

(deftest parse-test
  (testing "options and arguments in any order"
    (is (= {:options {:port 7888 :format "edn" :help true} :args ["a" "b"]}
           (cli/parse specs ["a" "-p" "7888" "--format=edn" "b" "--help"]))))
  (testing "-- ends the options and - is an argument"
    (is (= {:options {:port 1 :format "text"} :args ["-" "-1" "--help"]}
           (cli/parse specs ["--port=1" "-" "--" "-1" "--help"]))))
  (testing "values can look like options"
    (is (= {:options {:port -1 :format "text"} :args []}
           (cli/parse specs ["-p" "-1"]))))
  (testing "defaults"
    (is (= {:options {:format "text"} :args []} (cli/parse specs []))))
  (testing "the last one wins"
    (is (= 2 (:port (:options (cli/parse specs ["-p" "1" "--port" "2"]))))))
  (testing "errors"
    (are [args error] (= {:error error} (cli/parse specs args))
      ["--nope"] "Unknown option: --nope"
      ["-x" "--port" "1"] "Unknown option: -x"
      ["--port"] "--port needs a value"
      ["--port" "x"] "Invalid value for --port: x"
      ["--format=yaml"] "Invalid value for --format: yaml"
      ["--help=yes"] "--help takes no value")))

(deftest summary-test
  (is (= (str "  -p, --port PORT      The port\n"
              "      --format FORMAT  Output format\n"
              "  -h, --help           Help")
         (cli/summary specs))))
