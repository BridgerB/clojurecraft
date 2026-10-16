(ns clojurecraft.hunt
  "The food producer (issue #259): hungry with nothing to eat, the bot kills the nearest animal
   that drops meat and picks the meat up; the :eat row eats it raw.

   Requiring this namespace registers the :hunt row's rules: an always row at priority 0, after
   the meal in the table. Done by :provisioned?: not hungry (eat/hungry?), or food held, or no
   animal to hunt and no kill to pick up. Planned by :hunt: the drop of a kill just made (the
   last intent a :fight for :hunt, done, where its target was last seen) is collected; else a
   walk to the nearest animal a route reaches, and from beside it a :fight."
  (:require [clojure.math :as math]
            [clojurecraft.eat :as eat]
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def game-kinds
  "The animals hunted, by type name, with the raw meat each drops (the game's loot tables).
   Chickens are left: raw chicken may make the eater hungry (eat/unsafe), and it is not cooked."
  {:cow :beef :mooshroom :beef :pig :porkchop :sheep :mutton :rabbit :rabbit})

(def meats "The item ids of what game-kinds drop." (set (map recipe/item-id (vals game-kinds))))

(def hunt-radius 24)                  ; blocks from the feet an animal is hunted within
(def strike-range 4.5)                ; blocks on the plane from which a hunt fights instead of walking: past a walk's reach (intent/reach 4), so a walk that arrived is never asked again
(def candidates 3)                    ; nearest animals asked for a route, as the other acts ask

(defn cell "The block cell a point is in." [p] (mapv #(long (math/floor %)) p))

(defn game
  "[eid entity] of the nearest animal of game-kinds, alive, not blacklisted, within hunt-radius,
   the first of the candidates nearest that a route reaches (intent/routable?); nil when there is
   none."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (->> (:world/entities world)
         (filter (fn [[eid e]]
                   (and (game-kinds (entity/kind e))
                        (not (entity/dead? e))
                        (not (contains? bl eid))
                        (<= (physics/distance pos (:entity/pos e)) hunt-radius))))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]]
                 (when (intent/routable? world
                                         {:intent/kind :walk :intent/target (cell (:entity/pos e))})
                   ee))))))

(defn fresh-kill
  "The hunt's kill whose drop is still to pick up: the last intent, when it is a :fight for :hunt
   that ended done with where its target was last seen; nil otherwise."
  [world]
  (let [l (:plan/last world)]
    (when (and (= :fight (:intent/kind l)) (= :hunt (:intent/for l)) (:intent/target-pos l)) l)))

(defn hunting?
  "Is the running intent the hunt's (:intent/for :hunt): a walk to an animal, its fight, the
   meat's collect?"
  [world]
  (= :hunt (:intent/for (:plan/intent world))))

(defmethod plan/done-by :provisioned?
  ;; never while a hunt intent runs: the hunt test's first run (feat/259-hunt 8ed7f50) killed its
  ;; cow, which left game with none, and the planner, the row done, abandoned the fight :goal-met
  ;; the tick the cow died, before the fight was done: no kill to collect, the beef left lying
  [world _]
  (or (not (eat/hungry? world))
      (some? (eat/food-held world))
      (and (not (hunting? world)) (nil? (fresh-kill world)) (nil? (game world)))))

(defmethod plan/next-intent :hunt
  [world _ _]
  (if-let [k (fresh-kill world)]
    {:intent/kind :collect
     :intent/target (cell (:intent/target-pos k))
     :intent/drops meats
     :intent/for :hunt}
    (when-let [[eid e] (game world)]
      (if (<= (physics/horizontal-distance (:player/pos world) (:entity/pos e)) strike-range)
        {:intent/kind :fight :intent/target eid :intent/for :hunt}
        {:intent/kind :walk :intent/target (cell (:entity/pos e)) :intent/for :hunt}))))
