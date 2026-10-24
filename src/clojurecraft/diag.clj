(ns clojurecraft.diag
  "Temporary probe diagnosis for batch overworld2 (issues 574, 68, 62, 582, 161).
   Replays each snapshot like simrace/judged, then prints why place/spot finds
   nothing (per-candidate first-failing predicate) and, for a fight stall, why
   the targets are fought forever. Deleted before hand-off."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]))

(defn breakdown
  "Count of around-cells by first-failing spot predicate (full spot logic)."
  [world refused along?]
  (let [[px py pz] (:player/pos world)
        fx (long (math/floor px))
        fy (long (math/floor py))
        fz (long (math/floor pz))
        eye (game/eye world)]
    (frequencies
     (for [[dx dy dz] place/around
           :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                 support [(+ fx dx) (+ fy dy -1) (+ fz dz)]
                 t (terrain/block-at world target)
                 s (terrain/block-at world support)]]
       (cond (or (nil? t) (nil? s)) :unloaded
             (contains? refused target) :refused
             (not (blocks/replaceable? t)) :not-replaceable
             (not (terrain/confirmed? world target)) :unconfirmed
             (not (blocks/solid? s)) :no-support
             (contains? memory/placed-kinds (blocks/name-of s)) :placed-support
             (place/passage? world target) :passage
             (place/inside-player? world target) :inside-player
             (if along?
               (place/drop-beyond? world [fx fy fz] target)
               (place/drop-beside? world target))
             :drop
             (> (physics/distance eye (physics/centre target)) place/place-reach) :out-of-reach
             :else :ok)))))

(defn diag-place
  [world]
  (let [fr (try (place/fresh-rock world) (catch Exception e {:error (str e)}))]
    {:feet (try (intent/feet world) (catch Exception e {:error (str e)}))
     :spot-plain (try (place/spot world false #{}) (catch Exception e {:error (str e)}))
     :spot-passage (try (place/spot world true #{}) (catch Exception e {:error (str e)}))
     :spot-along (try (place/spot world false #{} true) (catch Exception e {:error (str e)}))
     :niche (try (place/niche world) (catch Exception e {:error (str e)}))
     :water-spot (try (place/water-spot world #{}) (catch Exception e {:error (str e)}))
     :fresh-rock-kind (:intent/kind fr)
     :fresh-rock-target (or (:intent/target fr) (:intent/to-y fr))
     :in-water? (boolean (:player/in-water? world))
     :breakdown-plain (try (breakdown world #{} false) (catch Exception e {:error (str e)}))
     :breakdown-along (try (breakdown world #{} true) (catch Exception e {:error (str e)}))}))

(defn diag-fight
  [world]
  (let [hostiles (try (entity/hostiles world) (catch Exception _ nil))]
    {:hostile-count (count hostiles)
     :nearest (into []
                    (comp (map (fn [[eid e]]
                                 {:eid eid
                                  :kind (try (entity/kind e) (catch Exception _ nil))
                                  :pos (:entity/pos e)}))
                          (take 4))
                    (sort-by (comp #(physics/distance (:player/pos world) %) :entity/pos val)
                             hostiles))
     :workup (into []
                   (take 4)
                   (for [[eid e] (take 4
                                       (sort-by (comp #(physics/distance (:player/pos world) %)
                                                      :entity/pos
                                                      val)
                                                hostiles))]
                     (try {:eid eid
                           :hittable? (combat/hittable? world e)
                           :strikable? (combat/strikable? world e)
                           :route (let [w (combat/route-walk world e)]
                                    (cond (nil? w) nil
                                          (= :here w) :here
                                          :else :walk))
                           :horizontal (combat/horizontal world e)}
                          (catch Exception ex {:eid eid :error (str ex)}))))}))

(defn diag-one
  [path]
  (try
    (let [snap (capture/read-world path)
          w (simrace/world-for! snap)
          until (:snapshot/until snap)
          stage (simrace/stage-of snap)
          m (simrace/moment-of snap w)
          stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
          r
          (sim/play-from simrace/step m stop? (simrace/window-of snap) (simrace/go-of w until) nil)
          world (:world r)
          i (:plan/intent world)]
      (println "DIAG" path)
      (println "DIAG signature" (pr-str (:snapshot/signature snap)))
      (println "DIAG plan"
               (pr-str {:status (:plan/status world)
                        :failed? (plan/failed? world)
                        :reached (:plan/reached world)}))
      (println "DIAG bot"
               (pr-str {:pos (:player/pos world)
                        :health (:player/health world)
                        :food (:player/food world)}))
      (println "DIAG intent"
               (pr-str (select-keys i
                                    [:intent/kind :intent/stage :intent/item :intent/target
                                     :intent/for :intent/goal :intent/since :intent/retreated?
                                     :intent/refused :intent/niche :intent/cell :intent/passage-ok?
                                     :intent/swings])))
      (println "DIAG place" (pr-str (diag-place world)))
      (println "DIAG fight" (pr-str (diag-fight world))))
    (catch Exception e (println "DIAG-ERROR" path (pr-str {:message (.getMessage e)})))))

(defn -main [& paths] (doseq [p paths] (diag-one p)) (shutdown-agents))
