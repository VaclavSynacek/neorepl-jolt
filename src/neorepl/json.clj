(ns neorepl.json
  "Just enough JSON output for --format json, so we don't need a library."
  (:require [clojure.string :as str]))

(defn- escape [s]
  (str/replace s #"[\"\\\x00-\x1f]"
               #(case %
                  "\"" "\\\""
                  "\\" "\\\\"
                  "\n" "\\n"
                  "\r" "\\r"
                  "\t" "\\t"
                  (format "\\u%04x" (int (first %))))))

(defn- write-key [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn write-str
  "x as a JSON string. Maps become objects, with keywords as keys losing their
  colon, other collections become arrays, and keywords and symbols strings."
  [x]
  (cond
    (nil? x) "null"
    (or (boolean? x) (integer? x)) (str x)
    (string? x) (str \" (escape x) \")
    (or (keyword? x) (symbol? x)) (write-str (write-key x))
    (map? x) (str "{"
                  (str/join "," (for [[k v] x] (str (write-str (write-key k)) ":" (write-str v))))
                  "}")
    (coll? x) (str "[" (str/join "," (map write-str x)) "]")
    :else (throw (ex-info (str "Can't write as JSON: " (pr-str x)) {:value x}))))
