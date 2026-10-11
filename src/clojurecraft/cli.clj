(ns clojurecraft.cli
  "What every command-line entry point shares: reading --key value arguments, and one
   timestamped line on stderr (stdout is each process's own channel: RESULT for the bot, the :go
   event for the fixture, a report for the gym and the fleet), and starting another process."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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

;;;; I/O: stderr and other processes ;;;;

(defn log
  "One timestamped line on stderr."
  [& xs]
  (binding [*out* *err*]
    (println (str (java.time.LocalTime/now)) (str/join " " xs))
    (flush)))

(defn exec!
  "Run a command to completion in dir with extra env; its output goes to log (a file), or to
   this process's when there is none. Returns the exit code."
  [cmd {:keys [dir env log]}]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))]
    (when dir (.directory pb (io/file dir)))
    (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
    (.redirectErrorStream pb true)
    (if log
      (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/appendTo (io/file log)))
      (.redirectOutput pb java.lang.ProcessBuilder$Redirect/INHERIT))
    (.waitFor (.start pb))))

(defn classpath!
  "The bot's classpath in dir (`clojure -Spath`), computed once so each bot starts as a plain
   java process."
  [dir]
  (let [f (java.io.File/createTempFile "classpath" ".txt")]
    (exec! ["clojure" "-Spath"] {:dir dir :log f})
    (str/trim (last (str/split-lines (slurp f))))))
