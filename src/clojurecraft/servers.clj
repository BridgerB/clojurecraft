(ns clojurecraft.servers
  "What differs between the servers of a race, as data: ci/race.edn's :race/server-overrides maps
   a server's number (the 1-based --server k of race.yml, SERVER_INDEX of `bb server`) to the
   settings it takes instead of the default. Pure and dependency-free, so the server's start
   (bb/clojurecraft/server.clj) and the race process that records it read the same answer.")

(set! *warn-on-reflection* true)

(def defaults "The settings a server with no override takes." {:simulation-distance 4})

(defn settings
  "The settings of server k (a number, or nil for a runner with one server) under config
   (ci/race.edn's value): defaults with :race/server-overrides' entry for k merged over them."
  [config k]
  (merge defaults (get-in config [:race/server-overrides k])))

(defn simulation-distance
  "The simulation distance, in chunks, server k runs with under config."
  [config k]
  (:simulation-distance (settings config k)))
