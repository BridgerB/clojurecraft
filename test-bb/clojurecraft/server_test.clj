(ns clojurecraft.server-test
  "The server's properties file for a race: the default simulation distance, and a server's own
   from ci/race.edn's :race/server-overrides. Babashka runs these (bb test:bb)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojurecraft.server :as server]
            [clojurecraft.servers :as servers]))

(def base {:port 25565 :rcon-port 25575 :rcon-pass "p" :level-seed "s" :difficulty "normal"})

(def config
  "A race config with server 2 overridden."
  {:race/server-overrides {2 {:simulation-distance 3}}})

(defn distance
  "The simulation-distance line the properties for opts carry."
  [opts]
  (some (fn [[k v]] (when (= "simulation-distance" k) v)) (server/properties opts)))

(deftest a-server-with-no-override-keeps-the-default-distance
  (is (= 4 (distance base)) "no index: a world build, a gym trial, a local server")
  (is (= 4 (distance (server/with-overrides (assoc base :server-index "1") config)))
      "server 1 has no entry")
  (is (= 4 (distance (server/with-overrides (assoc base :server-index "2") {}))) "no overrides"))

(deftest an-override-reaches-the-properties
  (is (= 3 (distance (server/with-overrides (assoc base :server-index "2") config))))
  (is (re-find #"(?m)^simulation-distance=3$"
               (server/properties-text
                (server/properties (server/with-overrides (assoc base :server-index "2") config))))
      "as the file's line"))

(deftest the-shipped-config-gives-the-two-servers-different-distances
  (let [cfg (edn/read-string (slurp "ci/race.edn"))]
    (is (= 4 (servers/simulation-distance cfg 1)))
    (is (= 3 (servers/simulation-distance cfg 2)))
    (is (= 4 (servers/simulation-distance cfg nil)) "a runner with one server")))
