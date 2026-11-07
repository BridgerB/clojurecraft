(ns clojurecraft.cli-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [clojurecraft.cli :as cli]))

(deftest options-are-keyword-keys-and-string-values
  (is (= {:port "25571" :until "wood"} (cli/options ["--port" "25571" "--until" "wood"])))
  (is (= {:port "25571"} (cli/options ["--port" "25571" "--hold-ms"])) "a lone trailing flag")
  (is (= {} (cli/options []))))

(deftest split-options-keeps-a-command's-own-words
  (is (= [{:port "25581" :pass "s"} ["data" "get" "entity" "Clj_wood" "Inventory"]]
         (cli/split-options ["--port" "25581" "--pass" "s" "data" "get" "entity" "Clj_wood"
                             "Inventory"])))
  (is (= [{} ["list"]] (cli/split-options ["list"]))))

(deftest a-checkpoint-value-is-one-map-or-a-vector-of-them
  ;; ci/race.edn and ci/simrace.edn: a lone map is the config written before there were two starts
  (let [rods {:from "rods" :kind "real-18" :lanes [1 2 3 4]}
        forest {:from "forest" :kind "real-18f" :lanes [5 6 7 8]}]
    (is (= [] (cli/checkpoints nil)))
    (is (= [rods] (cli/checkpoints rods)) "a lone map")
    (is (= [rods forest] (cli/checkpoints [rods forest])))
    (is (= rods (cli/checkpoint-for [rods forest] 3)))
    (is (= forest (cli/checkpoint-for [rods forest] 5)))
    (is (nil? (cli/checkpoint-for [rods forest] 9)) "a lane no checkpoint names is a plain lane")
    (is (= rods (cli/checkpoint-for rods 4)) "the old shape still picks its lanes")
    (is (nil? (cli/checkpoint-for nil 1)))))

(deftest the-lanes-split-between-rods-and-forest-in-the-race-configs
  (doseq [[file key kinds] [["ci/race.edn" :race/checkpoint ["real-18" "real-18f"]]
                            ["ci/simrace.edn" :simrace/checkpoint ["sim-18" "sim-18f"]]]]
    (let [cfg (clojure.edn/read-string (slurp file))
          at #(cli/checkpoint-for (get cfg key) %)]
      (is (every? #(= {:from "rods" :kind (first kinds)} (select-keys (at %) [:from :kind]))
                  [1 2 3 4])
          (str file ": lanes 1 to 4 start at the rods"))
      (is (every? #(= {:from "forest" :kind (second kinds)} (select-keys (at %) [:from :kind]))
                  [5 6 7 8])
          (str file ": lanes 5 to 8 start in the forest")))))
