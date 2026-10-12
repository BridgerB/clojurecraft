(ns clojurecraft.probe
  "Look inside a recorded run: fold the bot over its recording to any moment and ask the world
   what it saw. A recording is open-loop, so fold it with the code that made it (a git worktree
   at the run's commit); folded with newer code the planner decides otherwise and the bot ends
   up elsewhere.

     clojure -M:dev -m clojurecraft.probe story run.edn          ; every intent's start and end
     clojure -M:dev -m clojurecraft.probe around run.edn <ms>    ; the cells round the feet at ms

   From the REPL: (fold path stop?) is the world at the first moment stop? holds, and the rest
   are questions about a world."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.record :as record]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(defn fold
  "The world of the recording at path at the first event after which stop? holds of it, or at
   its end."
  [path stop?]
  (reduce (fn [w e] (let [w (assoc (main/step w e) :bot/effects [])] (if (stop? w) (reduced w) w)))
          (game/init {:host "replay" :port 0 :name "Clj_replay"})
          (record/events path)))

(defn at-ms
  "The world of the recording at path at its clock ms."
  [path ms]
  (fold path #(>= (:time/now %) ms)))

(defn story
  "Every intent of the recording at path, as memory kept it: one map per start, end, failure or
   abandonment, with its time, kind, target and reason, in order. What the bot did and why it
   stopped, without the bot log."
  [path]
  (memory/intentions (fold path (constantly false))))

(defn cell-char
  "One character for what a route makes of a cell: # solid, . clear, W water, L lava, ~ awkward,
   ? unloaded, g a block that falls."
  [w c]
  (let [id (terrain/block-at w c)]
    (cond (and id (blocks/falls? id)) "g"
          :else
          (case (path/classify w c)
            :solid "#"
            :clear "."
            :water "W"
            :lava "L"
            :awkward "~"
            "?"))))

(defn around
  "Two vertical slices through the feet, r cells each way, from r above to 2 below: along x (z
   fixed) left of the bar, along z (x fixed) right of it."
  [w r]
  (let [[fx fy fz] (intent/feet w)]
    (for [y (range (+ fy r) (- fy 3) -1)]
      (str y
           " " (str/join (for [x (range (- fx r) (+ fx r 1))] (cell-char w [x y fz])))
           "  |  " (str/join (for [z (range (- fz r) (+ fz r 1))] (cell-char w [fx y z])))))))

(defn -main
  "story path, or around path ms: print it."
  [cmd path & [ms]]
  (case cmd
    "story" (doseq [r (story path)] (prn r))
    "around"
    (let [w (at-ms path (parse-long ms))]
      (prn :feet (intent/feet w) :intent (dissoc (:plan/intent w) :intent/child))
      (doseq [line (around w 8)] (println line))))
  (shutdown-agents))
