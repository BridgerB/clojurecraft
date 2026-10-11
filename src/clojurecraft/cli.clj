(ns clojurecraft.cli
  "What every command-line entry point shares: reading --key value arguments, and one
   timestamped line on stderr (stdout is each process's own channel: RESULT for the bot, the :go
   event for the fixture, a report for the gym and the fleet)."
  (:require [clojure.string :as str]))

(defn options
  "--key value pairs → {:key \"value\"}; values stay strings, a trailing lone flag is dropped."
  [args]
  (into {} (map (fn [[k v]] [(keyword (subs k 2)) v])) (partition 2 args)))

(defn split-options
  "[options words]: the leading --key value pairs as options (see options), and every argument
   after them, in order (a command's own words, as rcon takes them)."
  [args]
  (let [[flags words] (split-with (fn [[k _]] (str/starts-with? k "--")) (partition-all 2 args))]
    [(options (apply concat flags)) (apply concat words)]))

;;;; I/O: stderr ;;;;

(defn log
  "One timestamped line on stderr."
  [& xs]
  (binding [*out* *err*]
    (println (str (java.time.LocalTime/now)) (str/join " " xs))
    (flush)))
