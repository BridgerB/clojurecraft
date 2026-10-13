(ns clojurecraft.dimension
  "The dimensions as data (resources/clojurecraft/dimensions.edn, generated from the server jar's
   dimension types): each one's name, registry id, lowest block y and height. Which one the bot
   is in is :player/dimension, set by login and respawn; before either it is the overworld."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def table
  "[{:dimension/name :dimension/id :dimension/min-y :dimension/height :dimension/sections ...}],
   in registry order."
  (edn/read-string (slurp (io/resource "clojurecraft/dimensions.edn"))))

(def overworld "minecraft:overworld")
(def nether "minecraft:the_nether")
(def end "minecraft:the_end")

(def by-name "{name row}." (into {} (map (juxt :dimension/name identity)) table))

(defn of
  "The dimension the bot is in: :player/dimension, or the overworld before any login named one."
  [world]
  (:player/dimension world overworld))

(defn min-y-of-sections
  "The lowest block y of a column n sections tall: the dimension types with that many sections
   all start at the same y (24: the overworld at -64; 16: the Nether and the End at 0), so a
   column says its own height by its size. nil when no dimension is n sections tall."
  [n]
  (some #(when (= n (:dimension/sections %)) (:dimension/min-y %)) table))
