(ns clojurecraft.style-test
  "hickey.md style rules that a test can hold."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]))

(def if-family '#{if if-let if-not if-some})

(defn forms
  "Every top-level form of every .clj file under dir, as [file form]."
  [dir]
  (for [f (sort (map str (filter #(str/ends-with? (str %) ".clj") (file-seq (io/file dir)))))
        form (binding [*read-eval* false *ns* (create-ns 'clojurecraft.style-test.scratch)]
               (with-open [r (java.io.PushbackReader. (io/reader f))]
                 (doall (take-while #(not= % ::eof) (repeatedly #(read {:eof ::eof :read-cond :allow} r))))))]
    [f form]))

(defn nested-ifs
  "[file name] for each top-level form holding an if (or if-let, if-not, if-some) whose branch is
   itself one: cond reads better."
  [dir]
  (for [[f form] (forms dir)
        :when (seq? form)
        :let [hits (atom 0)]
        :when (do (walk/postwalk (fn [x]
                                   (when (and (seq? x) (if-family (first x))
                                              (some #(and (seq? %) (if-family (first %))) (drop 2 x)))
                                     (swap! hits inc))
                                   x)
                                 form)
                  (pos? @hits))]
    [f (second form)]))

(deftest cond-over-nested-if
  (is (= [] (vec (concat (nested-ifs "src/clojurecraft") (nested-ifs "dev/clojurecraft"))))))
