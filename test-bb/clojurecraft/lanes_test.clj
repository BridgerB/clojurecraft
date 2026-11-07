(ns clojurecraft.lanes-test
  "The lanes file (ci/lanes.edn) as the workflows read it, through clojurecraft.lanes. Babashka
   runs these (bb test:bb)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojurecraft.lanes :as lanes]))

(def cfg (edn/read-string (slurp "ci/lanes.edn")))
(def starts (edn/read-string (slurp "resources/clojurecraft/starts.edn")))

(deftest a-lane-has-the-start-and-page-its-entry-names
  (is (= {:lane/start :rods :lane/page "real-18"}
         (select-keys (lanes/lane-of cfg :real 3) [:lane/start :lane/page])))
  (is (= {:lane/start :forest :lane/page "real-18f"}
         (select-keys (lanes/lane-of cfg :real 5) [:lane/start :lane/page])))
  (is (= {:lane/start :rods :lane/page "sim-18"}
         (select-keys (lanes/lane-of cfg :sim 1) [:lane/start :lane/page])))
  (is (= {:lane/start :forest :lane/page "sim-18f"}
         (select-keys (lanes/lane-of cfg :sim 8) [:lane/start :lane/page]))))

(deftest a-lane-no-entry-names-plays-the-default
  (is (= {:lane/page "real"} (lanes/lane-of cfg :real 9)))
  (is (= {:lane/page "sim"} (lanes/lane-of cfg :sim 9))))

(deftest the-outputs-are-the-lines-a-workflow-appends
  (is (= ["start=rods" "kind=real-18"] (lanes/outputs (lanes/lane-of cfg :real 1))))
  (is (= ["start=" "kind=real"] (lanes/outputs (lanes/lane-of cfg :real 9)))
      "no start: the process takes the table's default"))

(deftest every-lane-start-is-a-start-in-the-table
  (let [ids (set (map :start/id (:starts starts)))]
    (is (every? ids (keep :lane/start (:lanes cfg))))
    (is (contains? ids (:starts/default starts)))))

(deftest no-runner-is-in-two-lanes-of-a-kind
  (doseq [kind [:real :sim]
          :let [runners (mapcat :lane/runners (filter #(= kind (:lane/kind %)) (:lanes cfg)))]]
    (is (= (count runners) (count (set runners))))))
