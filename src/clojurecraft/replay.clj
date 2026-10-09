(ns clojurecraft.replay
  "clojure -M:replay run.edn → fold the whole bot over a recording, print RESULT. No server."
  (:require [clojurecraft.main :as main])
  (:gen-class))

(defn -main [& [path]] (main/replay path))
