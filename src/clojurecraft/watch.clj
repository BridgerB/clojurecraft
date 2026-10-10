(ns clojurecraft.watch
  "Observers of the world atom. docs/hickey.md: \"Telemetry becomes a watcher that diffs
   successive values and writes facts. Neither can slow the loop or see a torn state, and
   neither is mentioned in the game code at all.\" A watch sees two whole values (old and new),
   never a half-written one. The diff is a pure function; the watch only offers it to a bounded
   channel and moves on, dropping (and counting) when the writer falls behind, so perception never
   interferes with the process. Nothing in game, plan or intent knows this namespace exists."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]))

(def ignored
  "Attributes not worth a telemetry line: they change every tick by construction (time), are
   large values with their own record (chunks, facts, the block overlay), or are drained by the
   loop (effects)."
  #{:time/now :time/tick :bot/effects :world/chunks :world/facts :world/blocks :net/sent-tick})

(def buffer-size 1024)                 ; diffs in flight before the watcher starts dropping

(defn entry-changes
  "{key new-value} for the entries that differ between two maps; a removed key maps to nil."
  [old new]
  (into {} (for [k (into (set (keys old)) (keys new))
                 :let [v (get new k)]
                 :when (not= (get old k) v)]
             [k v])))

(defn changes
  "What differs between two world values, minus the ignored attributes:
   {:telemetry/changed {attr new-value}} for plain values (nil when it disappeared) and
   {:telemetry/patched {attr {key new-value}}} for an attribute that is a map before and after,
   so a counter map that ticks once costs one entry, not the whole map. Empty when nothing worth
   noting changed."
  [old new]
  (reduce (fn [acc k]
            (let [a (get old k) b (get new k)]
              (cond (= a b) acc
                    (and (map? a) (map? b)) (assoc-in acc [:telemetry/patched k] (entry-changes a b))
                    :else (assoc-in acc [:telemetry/changed k] b))))
          {}
          (remove ignored (into (set (keys old)) (keys new)))))

(defn line
  "The telemetry fact for one change of the world: when, and what changed."
  [old new]
  (let [c (changes old new)]
    (when (seq c) (merge {:telemetry/at (:time/now new) :telemetry/tick (:time/tick new)} c))))

;;;; I/O: the watch and its writer ;;;;

(defn telemetry!
  "Watch world* and write one EDN line per change to path on a thread of its own. Returns
   {:dropped (fn [] n) :close (fn [])}; :close stops watching and finishes the file."
  [world* path]
  (let [ch (a/chan buffer-size)
        dropped (atom 0)
        w (io/writer path)
        done (a/thread (loop []
                         (when-let [l (a/<!! ch)]
                           (binding [*out* w] (prn l))
                           (recur)))
                       (.close w))]
    (add-watch world* ::telemetry
               (fn [_ _ old new]
                 (when-let [l (line old new)]
                   (when-not (a/offer! ch l) (swap! dropped inc)))))
    {:dropped (fn [] @dropped)
     :close (fn [] (remove-watch world* ::telemetry) (a/close! ch) (a/<!! done))}))
