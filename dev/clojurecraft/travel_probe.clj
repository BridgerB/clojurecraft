(ns clojurecraft.travel-probe
  "Why a stage-18 bot stands in lookouts (issue #263): one sim run from the rods checkpoint, as
   simrace plays it, and at its end the hunt's travel inputs (clojurecraft.why/answers) with the
   position, the held items and the chunks of the warped forests known. A probe script: it runs
   on a runner (bb probe run), never the Mac.

     clojure -M:dev -m clojurecraft.travel-probe <world file> <seed>"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojurecraft.capture :as capture]
            [clojurecraft.memory :as memory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.simrace :as simrace]
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
  [file seed]
  (let [config (edn/read-string (slurp "ci/simrace.edn"))
        [max-ms _] (simrace/cap-max-ms config "rods" (* 60000 simrace/default-max-minutes))
        r (simrace/run (world-of file) (parse-long seed) simrace/default-until max-ms)
        w (:run/end r)]
    (println "RESULT" (pr-str (select-keys r [:ok :reason :run/ms :run/signature])))
    (println "POS" (pr-str (:player/pos w)))
    (println "HELD" (pr-str (recipe/counts (:player/inventory w))))
    (println "FORESTS" (pr-str (memory/warped-chunks w)))
    (pp/pprint (why/answers w))
    (shutdown-agents)))
