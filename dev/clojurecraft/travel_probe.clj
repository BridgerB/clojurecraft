(ns clojurecraft.travel-probe
  "Why a stage-18 bot stands in lookouts (issue #263): one sim run from the rods checkpoint, as
   simrace plays it, and at its end the hunt's travel inputs (clojurecraft.why/answers) with the
   position, the held items and the chunks of the warped forests known. A probe script: it runs
   on a runner (bb probe run), never the Mac.

     clojure -M:dev -m clojurecraft.travel-probe <world file> <seed>"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.senses :as senses]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.why :as why]))

(set! *warn-on-reflection* true)

(defn world-of
  "The captured world at file as the rods checkpoint plays it (simrace/race!'s own preparation):
   the set's Nether, and a start at a fortress with the kit and seven rods."
  [file]
  (let [config (edn/read-string (slurp "ci/simrace.edn"))
        kit (or (:simrace/kit config) simrace/fortress-kit-default)
        from "rods"
        w (assoc (simrace/prepared (capture/read-world file)) :capture/file file)
        path (simrace/nether-file (io/file file))
        nether (simrace/read-nether path)
        w' (-> w
               (simrace/with-nether nether path)
               (simrace/with-fortress (merge-with + kit (:checkpoint/kit (simrace/starts from)))))]
    (assoc-in w' [:capture/fortress :checkpoint/goal] (:checkpoint/goal (simrace/starts from)))))

(defn -main
  "Play the world at file with seed from the rods checkpoint to its cap, then print the answers."
  [file seed & [minutes]]
  (let [config (edn/read-string (slurp "ci/simrace.edn"))
        [max-ms _] (simrace/cap-max-ms config "rods" (* 60000 simrace/default-max-minutes))
        max-ms (if minutes (* 60000 (parse-long minutes)) max-ms)
        r (simrace/run (world-of file) (parse-long seed) (:simrace/until config) max-ms)
        w (:run/end r)]
    (io/make-parents "out/events.edn")
    (senses/write-events! "out/events.edn" (:run/events r) 0)
    (println "RESULT" (pr-str (select-keys r [:ok :reason :run/ms :run/signature])))
    (println "POS" (pr-str (:player/pos w)))
    (println "HELD" (pr-str (recipe/counts (:player/inventory w))))
    (println "FORESTS" (pr-str (memory/warped-chunks w)))
    (pp/pprint (why/answers w))
    (println "HELD-EXIT" (pr-str (:pearls/exit-target w)) "HEADING" (pr-str (:pearls/heading w)))
    (println "PLAN"
             (pr-str (select-keys w
                                  [:plan/status :plan/attempts :plan/intent :plan/last
                                   :plan/blacklist :plan/boxed-at :plan/wait])))
    (let [feet (intent/feet w)
          target (or (:pearls/exit-target w) feet)]
      (println "REFUSALS feet" (pr-str feet) "target" (pr-str target))
      (doseq [m (fortress/moves feet target 0)]
        (println "MOVE" (pr-str m)
                 "opens"
                 (pr-str (mapv (fn [c] [c
                                        (some-> (terrain/block-at w c)
                                                blocks/name-of)])
                               (fortress/opens feet m)))
                 "lava-beside" (pr-str (fortress/lava-beside w feet m))
                 "safe?" (pr-str (fortress/safe? w feet m))
                 "infested" (pr-str (fortress/infested? w (mapv + feet m))))))
    (shutdown-agents)))
