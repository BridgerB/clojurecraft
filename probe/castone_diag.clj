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

(defn child-of
  "c as a value to print: a map shortened, anything else as it is."
  [c]
  (if (map? c) (brief c) c))

(defn build-questions
  "What the running :build intent i asks of world: its open ops, the op worked and each way to a
   stand for it. Every answer is asked on its own, so one that throws names itself."
  [world i]
  (let [{:intent/keys [build origin facing access]} i
        plan (build/plan-for build origin facing (or access 0))
        open (remove #(build/satisfied? world %) plan)
        nxt (build/next-op world plan)
        {:build/keys [stand pos]} nxt
        feet (intent/feet world)
        placed (build/place-cells build origin facing)
        kept (build/kept origin facing)
        ask (fn [label f] (say label (safely f)))]
    (say
     "PLAN"
     {:ops (count plan) :open (count open) :feet feet :origin origin :facing facing :access access})
    (doseq [op (take 14 open)]
      (ask "OPEN"
           #(assoc (select-keys op [:build/op :build/step :build/pos :build/stand :build/item])
                   :block
                   (named (some->> (:build/pos op)
                                   (terrain/block-at world))))))
    (say "NEXT" (select-keys nxt [:build/op :build/step :build/pos :build/stand]))
    (when pos
      (ask "NEXTCELL"
           #(hash-map :placed? (contains? placed pos)
                      :kept? (contains? kept pos)
                      :block (named (terrain/block-at world pos)))))
    (when stand
      (ask "STAND"
           #(hash-map
             :standable? (path/standable? world stand)
             :routable?
             (intent/routable? world {:intent/kind :walk :intent/target stand :intent/for :build})
             :stance-block (build/stance-block world stand build origin facing)
             :clear-stand (child-of (build/clear-stand world stand build origin facing))
             :round-corner (build/round-corner world stand origin facing)
             :floor-gap (build/floor-gap world stand)
             :bridge (child-of (build/bridge world stand))
             :toward-stand (child-of (build/toward-stand world stand build origin facing))
             :stand-walk (child-of (build/stand-walk world stand)))))
    (when pos
      (ask "POS"
           #(hash-map :reachable-stand (build/reachable-stand world stand pos)
                      :room-stand (build/room-stand world i pos)
                      :nearest-stand-cell (build/nearest-stand-cell world i pos)
                      :way-back (child-of (build/way-back world i))
                      :within-reach? (build/within-reach? world pos))))
    (ask "APPROACH" #(child-of (build/approach-stand world i nxt)))
    (ask "OPCHILD" #(child-of (build/op-child world i nxt)))
    (ask "STATION" #(select-keys (build/station world origin facing access) [:stand :h]))
    (ask "REFILL" #(child-of (build/refill world i)))
    (doseq [op (take 40 (filter #(#{:landing :room :floor} (:build/step %)) plan))]
      (ask "OPS"
           #(vector (:build/step op)
                    (:build/op op)
                    (:build/pos op)
                    (named (terrain/block-at world (:build/pos op)))
                    (build/satisfied? world op))))))

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
    (when (= :build (:intent/kind i)) (build-questions world i))
    (cells-line world sim cells)
    (doseq [line (scene/slice world)] (println "SLICE" line))))

(defn -main
  "Diagnose each path given, each with an EDN vector of cells (or nil) after it."
  [& args]
  (doseq [[p cells] (partition 2 args)] (diagnose! p (edn/read-string cells)))
  (shutdown-agents))
