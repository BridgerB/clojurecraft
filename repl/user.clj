(ns user
  "Where a REPL starts: clojure -M:dev:nrepl (or bb nrepl), then evaluate the forms in the comment
   below one at a time. Everything the bot decides is a pure function of a world value, so the
   whole bot runs here against the server model, and any recorded run folds back into its worlds,
   with no Minecraft server."
  (:require [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.record :as record]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(set! *warn-on-reflection* true)

(def one-tree
  "A column with one oak trunk three logs high beside the spawn, on a stone floor."
  {[3 64 2] 136 [3 65 2] 136 [3 66 2] 136})

(defn wood-run
  "[world sim] of the whole bot against the server model in column (a {[lx y lz] state} map),
   until it holds a log or a minute of model time passes."
  [column]
  (sim/run main/step
           (game/init {:host "sim" :port 0 :name "Clj_repl"})
           (sim/init {:column (world/column-bytes column) :spawn [8.5 64.0 8.5]})
           plan/done?
           60000
           {:event/kind :go :go/goals [:wood]}))

(defn worlds
  "Every world a recording passes through, in order: (nth (worlds path) i) is the bot after
   event i, to inspect at any tick."
  [path]
  (reductions (fn [w e] (assoc (main/step w e) :bot/effects []))
              (game/init {:host "replay" :port 0 :name "Clj_replay"})
              (record/events path)))

(comment
  ;; the bot against the server model
  (def run (wood-run one-tree))
  (plan/summary (first run))
  (memory/intentions (first run))
  ;; a recorded gym run: its RESULT as a replay reaches it, and the world at any event
  (main/replayed "test/recordings/wood-a1-3/run.edn.gz")
  (def ws (vec (worlds "test/recordings/wood-a1-3/run.edn.gz")))
  (def walking (first (filter #(= :walk (:intent/kind (:plan/intent %))) ws)))
  (:plan/intent walking)
  (memory/intention-as-of (peek ws) (:time/now walking)))
