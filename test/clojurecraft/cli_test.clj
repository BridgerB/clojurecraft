(ns clojurecraft.cli-test
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.capture :as capture]
            [clojurecraft.capture-nether :as capture-nether]
            [clojurecraft.cli :as cli]
            [clojurecraft.gym :as gym]
            [clojurecraft.harness :as harness]
            [clojurecraft.main :as main]
            [clojurecraft.race :as race]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.simrace :as simrace]))

(deftest options-are-keyword-keys-and-string-values
  (is (= {:port "25571" :until "wood"} (cli/options ["--port" "25571" "--until" "wood"])))
  (is (= {:port "25571"} (cli/options ["--port" "25571" "--hold-ms"])) "a lone trailing flag")
  (is (= {} (cli/options []))))

(deftest split-options-keeps-a-command's-own-words
  (is (= [{:port "25581" :pass "s"} ["data" "get" "entity" "Clj_wood" "Inventory"]]
         (cli/split-options ["--port" "25581" "--pass" "s" "data" "get" "entity" "Clj_wood"
                             "Inventory"])))
  (is (= [{} ["list"]] (cli/split-options ["list"]))))

(deftest options-with-a-known-set-reject-any-other
  (is (= {:port "1"} (cli/options ["--port" "1"] #{:port :until})))
  (let [e (try (cli/options ["--port" "1" "--from" "rods"] #{:port :until})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= [:from] (:cli/unknown (ex-data e))))
    (is (re-find #"unknown option --from; known: --port --until" (ex-message e))
        "the message names the option and lists the known ones"))
  (is (= [:a :b] (cli/unknown-options {:b 1 :c 2 :a 3} #{:c})))
  (is (thrown? clojure.lang.ExceptionInfo (cli/split-options ["--nope" "x" "words"] #{:host}))))

(deftest every-entry-point-rejects-the-retired-from-option
  ;; --from was the start's name before --start; an entry point that ignored it ran from its
  ;; default start without a word
  (doseq [[name main] {"simrace" simrace/-main
                       "race" race/-main
                       "main" main/-main
                       "capture" capture/-main
                       "capture-nether" capture-nether/-main
                       "harness" harness/-main
                       "gym" gym/-main
                       "rcon" rcon/-main}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unknown option --from; known: "
                          (if (= "gym" name) (main "plan" "--from" "rods") (main "--from" "rods")))
        name)))
