(ns clojurecraft.pearls.pit
  "The pit: the answer to an angry enderman at hand under danger-health with no burrow to walk
   to. Two cells dug straight down from the feet put the head under the enderman's feet: its
   attack box rises from its feet and never reaches below them, and it cannot follow into a hole
   one wide and two deep (it stands 2.9 high). In the pit the bot eats and heals and never swings
   under danger-health (#577); back at resume-hp it fights the enderman's legs from there.

   Real bots answered an enderman at hand by eating, laying a wall or a pillar beside it: seven of
   twenty-two died eating with it adjacent. A roofed cell takes five blocks at 0.6 s each under
   its hits, and its body stands in one of them; two digs take half a second."
  (:require [clojure.string :as str]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]))

(set! *warn-on-reflection* true)

(def hold-ms
  "ms after the last strike, or the last angry enderman at hand, that the bot stays in its pit."
  5000)

(defn sides
  "The four cells beside cell [x y z] on the plane."
  [[x y z]]
  (for [[dx dz] stairs/directions] [(+ x dx) y (+ z dz)]))

(defn walled?
  "Are the four cells beside cell c solid (path/classify)?"
  [world c]
  (every? #(= :solid (path/classify world %)) (sides c)))

(defn in-pit?
  "Does the bot stand in a hole one wide and two deep: the four cells beside its feet's cell and
   beside its head's cell solid?"
  [world]
  (let [[x y z] (intent/feet world)] (and (walled? world [x y z]) (walled? world [x (inc y) z]))))

(defn diggable?
  "May cell c be dug for the pit: solid, walled on its four sides, a solid floor under it, and
   nothing in or round it that stairs/clear-way? refuses (water, lava, an unloaded chunk, lava
   beside or under)?"
  [world [x y z :as c]]
  (and (= :solid (path/classify world c))
       (walled? world c)
       (= :solid (path/classify world [x (dec y) z]))
       (stairs/clear-way? world [c])))

(defn pickaxe?
  "Does the bot hold a pickaxe of any material?"
  [world]
  (boolean (some #(str/ends-with? (name %) "_pickaxe")
                 (keys (recipe/counts (:player/inventory world))))))

(defn enderman-at-hand
  "[eid entity] of the nearest angry enderman (entity/creepy?), blacklisted or not, within
   strike-range of the feet, or nil."
  [world]
  (entity/nearest (into {}
                        (filter (fn [[_ e]]
                                  (and (entity/creepy? e)
                                       (<= (physics/distance (:player/pos world) (:entity/pos e))
                                           pparams/strike-range))))
                        (psense/endermen (avoided/forget world :blacklist) pparams/strike-range))
                  (:player/pos world)))

(defn pressed?
  "Is an angry enderman at hand (enderman-at-hand), or did one strike within hold-ms?"
  [world]
  (let [at (:player/struck-at world)
        e (get-in world [:world/entities (:player/attacker world)])]
    (boolean (or
              (enderman-at-hand world)
              (and at e (= :enderman (entity/kind e)) (<= (- (:time/now world 0) at) hold-ms))))))

(defn dig
  "The dig of the cell under the feet that makes the pit, or nil: under danger-health with an
   angry enderman at hand, not yet in a pit, a pickaxe held, and the cells to dig safe (diggable?):
   the two under the feet from level ground, the one under them from a hole already a block deep
   (the feet's sides solid). Never a drop and never toward water or lava: nil then, and the
   older answers (the walk home, the back-off) go on."
  [world]
  (let [[x y z] (intent/feet world)
        below [x (dec y) z]]
    (when (and (< (:player/health world 20.0) pparams/danger-health)
               (enderman-at-hand world)
               (not (in-pit? world))
               (pickaxe? world)
               (diggable? world below)
               (or (walled? world [x y z]) (diggable? world [x (- y 2) z])))
      {:intent/kind :dig
       :intent/target below
       :intent/drop-ok? true
       :intent/wall? true
       :intent/for :pearls})))

(defn hold
  "The intent of a bot in its pit while an enderman presses it (pressed?), or nil (then it climbs
   out by the hunt's way on): at resume-hp or more a held fight on the one at hand that the sword
   reaches from here (combat/hittable?), yielding under retreat-health; under it the meal it can
   take (blaze/meal), else standing and looking round. Never a swing under resume-hp."
  [world]
  (when (and (in-pit? world) (pressed? world))
    (let [[eid e] (enderman-at-hand world)]
      (or (when (and eid
                     (>= (:player/health world 20.0) pparams/resume-hp)
                     (combat/hittable? world e))
            {:intent/kind :fight
             :intent/target eid
             :intent/hold? true
             :intent/yield? true
             :intent/for :pearls})
          (some-> (blaze/meal world)
                  (assoc :intent/for :pearls))
          {:intent/kind :lookout :intent/for :pearls}))))
