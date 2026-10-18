(ns clojurecraft.probe263
  "A probe for issue #263 (temporary): fold a pearls-12 recording to its final world and print
   what the hunt saw there: the endermen tracked, whether each is provokable or routable, the
   roam's eight headings and the terrain round the feet as a route classifies it."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.physics :as physics]
            [clojurecraft.record :as record]
            [clojurecraft.terrain :as terrain]))

(def glyph {:solid \# :clear \. :water \~ :lava \L :awkward \a :unknown \?})

(defn slice
  "Print the cells dy over the feet [x y z], r round them, one glyph each; @ at the feet."
  [world [x y z] dy r]
  (doseq [dz (range (- r) (inc r))]
    (println (str/join (for [dx (range (- r) (inc r))]
                         (if (and (zero? dx) (zero? dz) (zero? dy))
                           \@
                           (glyph (path/classify world [(+ x dx) (+ y dy) (+ z dz)]))))))))

(defn plan-of
  "The route plan from the feet to a walk at target, summarised."
  [world target]
  (let [p (path/plan world
                     (path/feet-cell (:player/pos world))
                     (intent/goal-of {:intent/kind :walk :intent/target target})
                     {})]
    (assoc (select-keys p [:path/status :path/cost]) :wps (count (:path/waypoints p)))))

(defn report
  "Fold the recording at path and print the hunt's view of its final world."
  [path]
  (let [world0 (game/init {:host "replay" :port 0 :name "probe"})
        w (record/replay main/step world0 path)
        pos (:player/pos w)
        feet (intent/feet w)
        [x y z] feet]
    (println "==" path)
    (println "pos" pos "feet" feet "health" (:player/health w) "blacklist" (:plan/blacklist w))
    (println "last" (:plan/last w))
    (println "intent" (:plan/intent w))
    (println "shelter" (:pearls/shelter w) "mouth" (:pearls/mouth w))
    (println "chunks" (count (:world/chunks w)) "entities" (count (:world/entities w)))
    (doseq [[eid e] (:world/entities w)]
      (println " "
               eid
               (entity/kind e)
               (:entity/pos e)
               (format "%.1f" (physics/distance pos (:entity/pos e)))
               "creepy"
               (entity/creepy? e)
               "dead"
               (entity/dead? e)
               (when (= :enderman (entity/kind e))
                 (str "provokable " (pearls/provokable? w e)
                      " sight " (mob/sight? (pearls/sight-fn w) (game/eye w) (pearls/head e))
                      " plan " (plan-of w (pearls/cell (:entity/pos e)))))))
    (println "quarry" (first (pearls/quarry w)))
    (println "routable" (first (pearls/routable w (pearls/endermen w pearls/seek-radius))))
    (println "coming" (keys (pearls/coming w))
             "crowded" (pearls/crowded? w)
             "shelter now" (pearls/shelter w))
    (doseq [[eid e] (pearls/endermen (dissoc w :plan/blacklist) pearls/stare-range)]
      (println " enderman" eid "rel" (mapv - (:entity/pos e) pos) "creepy" (entity/creepy? e)))
    (println "under feet" (blocks/name-of (terrain/block-at w [x (dec y) z]))
             "feet" (blocks/name-of (terrain/block-at w feet))
             "head" (blocks/name-of (terrain/block-at w [x (inc y) z])))
    (println "blocks round the feet (dy -1..1, 5 round):")
    (doseq [dy [-1 0 1]]
      (println " dy"
               dy
               (frequencies (for [dx (range -5 6)
                                  dz (range -5 6)]
                              (blocks/name-of (terrain/block-at w [(+ x dx) (+ y dy) (+ z dz)]))))))
    (doseq [dy [-4 -3 -2 -1 0 1 2 3 4 5]]
      (println "-- slice dy" dy)
      (slice w feet dy 10))))

(defn -main
  "clojure -M -m clojurecraft.probe263 <recording>...: report on each."
  [& paths]
  (doseq [p paths] (report p))
  (shutdown-agents))
