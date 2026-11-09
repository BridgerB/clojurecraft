(ns clojurecraft.servers
  "What differs between the servers of a race, and between its lanes, as data. ci/race.edn's
   :race/server-overrides maps a server's number (the 1-based --server k of race.yml, SERVER_INDEX
   of `bb server`) to the settings it takes instead of the default; :race/lane-overrides maps a
   lane (race.yml's runner number) to race keys (:race/servers, :race/server-heap-mb ...) it takes
   instead of the file's own, so lanes can carry different sizes as an A/B. Pure and
   dependency-free, so the workflow, the server's start (bb/clojurecraft/server.clj) and the race
   process that records it read the same answer."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def defaults "The settings a server with no override takes." {:simulation-distance 3})

(defn settings
  "The settings of server k (a number, or nil for a runner with one server) under config
   (ci/race.edn's value): defaults with :race/server-overrides' entry for k merged over them."
  [config k]
  (merge defaults (get-in config [:race/server-overrides k])))

(defn simulation-distance
  "The simulation distance, in chunks, server k runs with under config."
  [config k]
  (:simulation-distance (settings config k)))

(defn for-lane
  "config (ci/race.edn's value) as lane n (a number, or nil) plays it: :race/lane-overrides' entry
   for n merged over the file's keys. A lane with no entry, or no lane, is config unchanged."
  [config n]
  (merge config (get-in config [:race/lane-overrides n])))

(defn servers-of
  "How many servers a runner carries under config (for-lane's value): :race/servers, else 1."
  [config]
  (:race/servers config 1))

(defn outputs
  "The race's sizes for lane n under config, as the lines race.yml's plan job appends to
   $GITHUB_OUTPUT: servers= sets= bots= server-heap= bot-heap= (sets as a JSON array of strings)
   and landing-stride=, the landings each lane on a set takes under the file's own sizes (not the
   lane's), so a lane plays the same landings whichever arm of an A/B it is in."
  [config n]
  (let [c (for-lane config n)]
    [(str "servers=" (servers-of c))
     (str "sets=[" (str/join "," (map pr-str (:race/landing-sets c))) "]")
     (str "bots=" (:race/bots c)) (str "server-heap=" (:race/server-heap-mb c))
     (str "bot-heap=" (:race/bot-heap-mb c))
     (str "landing-stride=" (* (servers-of config) (:race/bots config)))]))

;;;; I/O: the file and the command line ;;;;

(defn -main
  "bb race-sizes <lane>: print ci/race.edn's sizes for the lane, one key=value a line, for
   $GITHUB_OUTPUT."
  [& [n]]
  (let [config (edn/read-string (slurp "ci/race.edn"))]
    (run! println
          (outputs config
                   (some-> n
                           parse-long)))))
