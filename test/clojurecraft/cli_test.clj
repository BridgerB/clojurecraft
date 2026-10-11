(ns clojurecraft.cli-test
  (:require [clojure.test :refer [deftest is]]
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
