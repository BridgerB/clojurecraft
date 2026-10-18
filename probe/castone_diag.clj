(ns probe.castone-diag
  "A probe for the cast-one batch (issues #135 #130 #74 #45 #44 #41): each snapshot played on from
   its moment to where its plan ends, then the build's questions asked of the world one tick before
   the end: the plan's open ops, the op worked, each way the build looks for a stand, the stand's
   own cells, and the cells named on the command line in the world and in the model. Run on a runner
   (probe.yml), never the Mac:

     clojure -M -i probe/castone_diag.clj -e '(probe.castone-diag/-main \"x.edn.gz\" \"[[1 2 3]]\")'"
  (:require [clojure.edn :as edn]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(defn safely
  "(f), or the text of what it threw."
  [f]
  (try (f) (catch Exception e (str "ERR " (ex-message e)))))

(defn named
  "The block name of state id, or nil."
  [id]
  (some-> id
          blocks/name-of))

(defn brief
  "m with its small values only."
  [m]
  (some-> m
          scene/brief-map))

(defn say "Print a labelled value." [label v] (println label (pr-str v)))

(defn cells-line
  "Each cell's block in the bot's world and in the model."
  [world sim cells]
  (doseq [c cells]
    (say "CELL"
         {:cell c
          :world (named (terrain/block-at world c))
          :model (named (sim/block-at sim c))
          :class (path/classify world c)
          :standable? (path/standable? world c)})))

(defn build-questions
  "What the running :build intent i asks of world: its open ops, the op worked and each way to a
   stand for it."
  [world i]
  (let [{:intent/keys [build origin facing access]} i
        plan (build/plan-for build origin facing (or access 0))
        open (remove #(build/satisfied? world %) plan)
        nxt (build/next-op world plan)
        {:build/keys [stand pos]} nxt
        feet (intent/feet world)]
    (say
     "PLAN"
     {:ops (count plan) :open (count open) :feet feet :origin origin :facing facing :access access})
    (doseq [op (take 10 open)]
      (say "OPEN"
           (assoc (select-keys op [:build/op :build/step :build/pos :build/stand :build/item])
                  :block
                  (named (terrain/block-at world (:build/pos op))))))
    (say "NEXT" (select-keys nxt [:build/op :build/step :build/pos :build/stand]))
    (when stand
      (say "STAND"
           {:standable? (safely #(path/standable? world stand))
            :routable? (safely #(intent/routable?
                                 world
                                 {:intent/kind :walk :intent/target stand :intent/for :build}))
            :stance-block (safely #(build/stance-block world stand build origin facing))
            :clear-stand (safely #(brief (build/clear-stand world stand build origin facing)))
            :round-corner (safely #(build/round-corner world stand origin facing))
            :floor-gap (safely #(build/floor-gap world stand))
            :bridge (safely #(brief (build/bridge world stand)))
            :toward-stand (safely #(let [c (build/toward-stand world stand build origin facing)]
                                     (if (map? c) (brief c) c)))
            :stand-walk (safely #(brief (build/stand-walk world stand)))}))
    (when pos
      (say "POS"
           {:reachable-stand (safely #(build/reachable-stand world stand pos))
            :room-stand (safely #(build/room-stand world i pos))
            :nearest-stand-cell (safely #(build/nearest-stand-cell world i pos))
            :way-back (safely #(let [c (build/way-back world i)] (if (map? c) (brief c) c)))
            :within-reach? (safely #(build/within-reach? world pos))}))
    (say "APPROACH"
         (safely #(let [c (build/approach-stand world i nxt)] (if (map? c) (brief c) c))))
    (say "OPCHILD" (safely #(let [c (build/op-child world i nxt)] (if (map? c) (brief c) c))))))

(defn diagnose!
  "Play the snapshot at path to where its plan ends and ask the questions of the world before."
  [path cells]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        stage (simrace/stage-of snap)
        r (sim/play-from simrace/step
                         m
                         #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
        [world sim] (:before r)
        i (:plan/intent world)]
    (println "DIAG" path (pr-str (:snapshot/signature snap)))
    (say "AT" (select-keys (scene/facts world) [:pos :health :food :status]))
    (say "INTENT" (brief i))
    (say "LAST" (brief (:plan/last world)))
    (say "RETREAT" (safely #(brief (stone/retreat world))))
    (when (= :build (:intent/kind i)) (safely #(build-questions world i)))
    (cells-line world sim cells)
    (doseq [line (scene/slice world)] (println "SLICE" line))))

(defn -main
  "Diagnose each path given, each with an EDN vector of cells (or nil) after it."
  [& args]
  (doseq [[p cells] (partition 2 args)] (diagnose! p (edn/read-string cells)))
  (shutdown-agents))
