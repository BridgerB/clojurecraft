(ns clojurecraft.server-test
  "The server's properties file for a race: the default simulation distance, and a server's own
   from ci/race.edn's :race/server-overrides; a lane's sizes from its :race/lane-overrides, as
   race.yml reads them. Babashka runs these (bb test:bb)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojurecraft.lanes :as lanes]
            [clojurecraft.server :as server]
            [clojurecraft.servers :as servers]))

(def base {:port 25565 :rcon-port 25575 :rcon-pass "p" :level-seed "s" :difficulty "normal"})

(def config
  "A race config with server 2 overridden."
  {:race/server-overrides {2 {:simulation-distance 5}}})

(def race
  "A race config whose lane 1 carries one server with a bigger heap."
  {:race/servers 2
   :race/bots 10
   :race/landing-sets ["R1" "R2"]
   :race/server-heap-mb 3072
   :race/bot-heap-mb 2048
   :race/lane-overrides {1 {:race/servers 1 :race/server-heap-mb 6144}}})

(def shipped (edn/read-string (slurp "ci/race.edn")))

(defn distance
  "The simulation-distance line the properties for opts carry."
  [opts]
  (some (fn [[k v]] (when (= "simulation-distance" k) v)) (server/properties opts)))

(deftest a-server-with-no-override-keeps-the-default-distance
  (is (= 3 (distance base)) "no index: a world build, a gym trial, a local server")
  (is (= 3 (distance (server/with-overrides (assoc base :server-index "1") config)))
      "server 1 has no entry")
  (is (= 3 (distance (server/with-overrides (assoc base :server-index "2") {}))) "no overrides"))

(deftest an-override-reaches-the-properties
  (is (= 5 (distance (server/with-overrides (assoc base :server-index "2") config))))
  (is (re-find #"(?m)^simulation-distance=5$"
               (server/properties-text
                (server/properties (server/with-overrides (assoc base :server-index "2") config))))
      "as the file's line"))

(deftest the-shipped-config-runs-every-server-at-distance-three
  (is (= [3 3 3] (map #(servers/simulation-distance shipped %) [1 2 nil]))))

(deftest a-lane-override-is-merged-over-the-files-own
  (is (= 1 (servers/servers-of (servers/for-lane race 1))))
  (is (= 6144 (:race/server-heap-mb (servers/for-lane race 1))))
  (is (= race (servers/for-lane race 2)) "a lane with no entry")
  (is (= race (servers/for-lane race nil)) "no lane")
  (is (= 1 (servers/servers-of {})) "no :race/servers: one"))

(deftest the-outputs-are-the-lines-race-yml-appends
  (is (= ["servers=1" "sets=[\"R1\",\"R2\"]" "bots=10" "server-heap=6144" "bot-heap=2048"
          "landing-stride=20"]
         (servers/outputs race 1)))
  (is (= ["servers=2" "sets=[\"R1\",\"R2\"]" "bots=10" "server-heap=3072" "bot-heap=2048"
          "landing-stride=20"]
         (servers/outputs race 2))
      "the stride is the most bots any lane takes, whatever the lane's arm"))

(deftest the-stride-is-the-most-landings-any-lane-takes
  (let [bots-ab {:race/servers 1
                 :race/bots 10
                 :race/lane-overrides {1 {:race/bots 14} 2 {:race/servers 1}}}]
    (is (= 14 (servers/landing-stride bots-ab)) "a lane's 14 bots, over the file's 10")
    (is (= 10 (servers/landing-stride (dissoc bots-ab :race/lane-overrides))) "no lanes: its own")
    (is (= [14 10] (map #(servers/bots-of (servers/for-lane bots-ab %)) [1 2])))))

(deftest the-shipped-lanes-split-each-start-and-set-into-both-arms
  (let [lanes-cfg (edn/read-string (slurp "ci/lanes.edn"))
        gym (edn/read-string (slurp "ci/gym.edn"))
        sets (:race/landing-sets shipped)
        real (range 1 (inc (:race/runners shipped)))
        arm (fn [n] (servers/bots-of (servers/for-lane shipped n)))
        start (fn [n] (:lane/start (lanes/lane-of lanes-cfg :real n)))
        set-of (fn [n] (nth sets (mod (dec n) (count sets))))]
    (is (= #{10 14} (set (map arm real))) "ten-bot and fourteen-bot lanes")
    (doseq [[s ns] (group-by start real)] (is (= #{10 14} (set (map arm ns))) (str "start " s)))
    (doseq [[s ns] (group-by set-of real)] (is (= #{10 14} (set (map arm ns))) (str "set " s)))
    (doseq [n real
            :let [c (servers/for-lane shipped n)]]
      (is (= 1 (servers/servers-of c)) "every lane carries one server")
      (is (= 6144 (:race/server-heap-mb c)) "with the runner's server heap"))
    (doseq [[s ns] (group-by set-of real)]
      (is (<= (* (count ns) (servers/landing-stride shipped)) (get-in gym [:landing-sets s :n]))
          (str "set " s " has a landing for every bot of its lanes, none shared")))))
