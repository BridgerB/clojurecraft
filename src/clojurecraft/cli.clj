(ns clojurecraft.cli
  "What every command-line entry point shares: reading --key value arguments, and one
   timestamped line on stderr (stdout is each process's own channel: RESULT for the bot, the :go
   event for the fixture, a report for the gym and a plan), and starting another process."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn unknown-options
  "The keys of options (a map from options) that known (a set of keywords) does not name, sorted."
  [options known]
  (vec (sort (remove known (keys options)))))

(defn options
  "--key value pairs → {:key \"value\"}; values stay strings, a trailing lone flag is dropped.
   With known, a set of the keywords an entry point reads, any other --key throws an ex-info that
   lists the keys it knows, so a misspelt or retired option is never ignored in silence."
  ([args] (into {} (map (fn [[k v]] [(keyword (subs k 2)) v])) (partition 2 args)))
  ([args known]
   (let [o (options args)
         unknown (unknown-options o known)]
     (when (seq unknown)
       (throw (ex-info (str "unknown option " (str/join ", " (map #(str "--" (name %)) unknown))
                            "; known: " (str/join " " (map #(str "--" (name %)) (sort known))))
                       {:cli/unknown unknown :cli/known (vec (sort known))})))
     o)))

(defn split-options
  "[options words]: the leading --key value pairs as options (see options, with known when given),
   and every argument after them, in order (a command's own words, as rcon takes them)."
  ([args] (split-options args nil))
  ([args known]
   (let [[flags words] (split-with (fn [[k _]] (str/starts-with? k "--")) (partition-all 2 args))]
     [(if known (options (apply concat flags) known) (options (apply concat flags)))
      (apply concat words)])))

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
