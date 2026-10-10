(ns clojurecraft.docs-test
  "hickey.md: docstrings on every public function, stating the invariant."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def namespaces
  "Every namespace under src/clojurecraft, by file name."
  (->> (file-seq (io/file "src/clojurecraft"))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".clj"))
       (map #(symbol (str "clojurecraft." (str/replace (subs % 0 (- (count %) 4)) "_" "-"))))
       sort))

(defn undocumented
  "Public functions and multimethods of ns without a docstring, as qualified symbols."
  [ns-sym]
  (require ns-sym)
  (for [[sym v] (ns-publics ns-sym)
        :let [x @v]
        :when (and (or (fn? x) (instance? clojure.lang.MultiFn x)) (str/blank? (:doc (meta v))))]
    (symbol (str ns-sym) (str sym))))

(deftest every-public-function-has-a-docstring
  (is (seq namespaces))
  (is (= [] (vec (sort (mapcat undocumented namespaces))))))
