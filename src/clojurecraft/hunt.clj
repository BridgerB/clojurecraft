(ns clojurecraft.hunt
  "The food producer (issue #259): hungry with nothing to eat, the bot kills the nearest animal
   that drops meat and picks the meat up; the :eat row eats it raw.

   Requiring this namespace registers the :hunt row's rules: an always row at priority 0, after
   the meal in the table. Done by :provisioned?: no meat wanted (wants-meat?: hungry with nothing
   edible held, or in the overworld with a stone pickaxe or the portal's iron and under provision-wanted foods, for
   the Nether's sake), or no animal to hunt and no kill to pick up. Planned by :hunt: the drop of a kill just made (the
   last intent a :fight for :hunt, done, where its target was last seen) is collected; else a
   walk to the nearest animal a route reaches, and from beside it a :fight; and with none, while
   provisioning on the surface, a :forage: a bounded search (forage-ms) of walked legs, a quarter
   turn right each, for animals past the 48 blocks the server shows (issue #501)."
  (:require [clojure.math :as math]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.eat :as eat]
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def game-kinds
  "The animals hunted, by type name, with the raw meat each drops (the game's loot tables).
   Chickens are left: raw chicken may make the eater hungry (eat/unsafe), and it is not cooked.
   A hoglin is the Nether's pig (a crimson forest). It hits back, so game refuses one under
   combat/retreat-health and the fight yields (issue #577)."
  {:cow :beef :mooshroom :beef :pig :porkchop :sheep :mutton :rabbit :rabbit :hoglin :porkchop})

(def meats "The item ids of what game-kinds drop." (set (map recipe/item-id (vals game-kinds))))

(def hunt-radius 24)                  ; blocks from the feet an animal is hunted within
(def starving-below 6)                ; food at or under this is starving: the bar is nearly empty and health no longer comes back
(def starving-radius 48)              ; blocks a starving bot hunts within (a server shows animals this far, and the model populates three chunks round the player)
(def strike-range 4.5)                ; blocks on the plane from which a hunt fights instead of walking: past a walk's reach (intent/reach 4), so a walk that arrived is never asked again
(def candidates 3)                    ; nearest animals asked for a route, as the other acts ask
(def provision-wanted 6)              ; edible items held before the portal is lit: health comes back in the Nether only from food 18, and a hoglin is hunted only when one is already near and health is at least retreat-health (issue #501: three raw meats were eaten to the bar's 18 before the portal, and the Nether leg began with none)
(def early-forage-ms 120000)          ; ms the search goes on before the descent, from its first leg: the stone pickaxe is made on the surface among animals, and the iron kit's search still has its own forage-ms
(def forage-ms 360000)                ; ms the search for animals goes on, from its first leg, however many legs: then the bot goes on with what it holds, never holding the portal up
(def forage-reach 48)                 ; blocks the first two forage legs walk from the feet: a server shows animals this far (starving-radius)
(def forage-far 96)                   ; blocks the farthest leg walks: a server loads six chunks round the bot
(def forage-near-reaches [48 32 16])  ; blocks a leg falls back to when none has a route farther, farthest first
(def forage-partial-min 12)           ; blocks on the plane a route that spent its budget (:partial) must still go from the feet to be walked
(def forage-retry-ms 10000)           ; ms after a look that found no route at any heading before the bot looks again
(def forage-wary 32)                  ; blocks round the bot within which a hostile stops the search: sim run R1/5 (issue #501) searched at night through creepers, zombies and skeletons, the defence backing it off a hundred times, until it died
(def forage-health 14)                ; health under which the bot does not go looking: hurt, it stays where it is
(def forage-headings [[1 0] [0 1] [-1 0] [0 -1]]) ; the four ways a forage leg goes, a quarter turn right of the last

(defn cell "The block cell a point is in." [p] (mapv #(long (math/floor %)) p))

(defn edible-held
  "How many edible items (eat/edible) are held in the hotbar and the main inventory."
  [world]
  (reduce +
          0
          (for [[p {:keys [item count]}] (:player/inventory world)
                :when (and (< -1 p eat/store-slots) (eat/edible (recipe/item-name item)))]
            count)))

(defn provision-phase
  "Which provisioning the bot is in, as the overworld bot's goods say: :kit with the portal's iron
   in hand (flint and steel and a water bucket), else :early with a stone pickaxe held (the
   descent for coal and iron is next, and the surface, where the animals are, is left behind
   for hours); nil in any other dimension or before the pickaxe. Sim race 39869307990 (issue
   #504): the kit was finished at y -29 and the bot never surfaced again, so the Nether leg began
   with no food."
  [world]
  (when (= dimension/overworld (dimension/of world))
    (cond (and (pos? (inventory/item-count world (recipe/item-id :flint_and_steel)))
               (pos? (inventory/item-count world (recipe/item-id :water_bucket))))
          :kit
          (pos? (inventory/item-count world (recipe/item-id :stone_pickaxe))) :early)))

(defn provisioning?
  "Is the bot in a provision-phase (:early with the stone pickaxe, :kit with the portal's iron in
   hand) and fewer than provision-wanted edible items held? Sim race 39712220957 (issue #501,
   world R1/2) reached a fortress hurt at food 16 with nothing to eat: health comes back only
   from food 18, and a rest in the Nether failed :no-heal."
  [world]
  (and (some? (provision-phase world)) (< (edible-held world) provision-wanted)))

(defn reach
  "Blocks from the feet within which an animal is hunted: hunt-radius, and starving-radius once the
   food the server sent is at or under starving-below (issues #69, #62, #135: bots at food 0 and
   one health, nothing to eat within 24 blocks, starving for hours), and while provisioning? (no
   hurry, but the Nether has no game: as far as a server shows animals)."
  [world]
  (if (or (some-> (:player/food world)
                  (<= starving-below))
          (provisioning? world))
    starving-radius
    hunt-radius))

(defn fightable-from-route?
  "Can a fight reach animal e, and its drop be picked up, from where a walk to it ends: the end of
   the planned route (the feet when it is already there) no more than intent/pickup-rise under
   its feet. A route that is not found yet (chunks still loading) is not held against it. Sim
   race 39768347249 (issue #515, R2/30): three sheep stood on a spruce crown at y 82; the route
   ended at 79 and the walk, in reach of a straight line, at 77, under a wall the bot pressed
   against: the first sheep fell to a blow and its meat lay above the pickup box, the other two
   fights failed :unreachable, and three failures ended the plan."
  [world e]
  (let [c (cell (:entity/pos e))
        {:path/keys [status waypoints]} (path/plan world
                                                   (path/feet-cell (:player/pos world))
                                                   (intent/goal-of {:intent/kind :walk
                                                                    :intent/target c}))
        end (or (last waypoints) (intent/feet world))]
    (or (not= :found status)
        (<= (- (physics/y-of (:entity/pos e)) (physics/y-of end)) intent/pickup-rise))))

(defn game
  "[eid entity] of the nearest animal of game-kinds, alive, not blacklisted, none a fight
   gave up as unreachable a while ago (combat/given-up?: an animal above the bot that no
   fight could strike was re-picked after every done fight, issues #582 and #161), within reach
   (hunt-radius, starving-radius when starving), the first of the candidates nearest that a route
   reaches (intent/routable?) and a fight can reach from its end (fightable-from-route?); nil
   when there is none, and none while the body is in water: no route is walked from there, the
   rows that swim out come first. Never a hoglin under combat/retreat-health: it hits back
   (issue #577). Never an animal standing in a cell of the blacklist, where a walk to it that
   failed :no-path left it (fail-intent blacklists the walk's cell, not the animal). Sim
   snapshot R1-41-1 (issue #162): the bot surfaced in open sea, a sheep stood on the far shore,
   and the walk at its cell failed :no-path every twelve seconds, twelve in a row ending the
   plan."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})
        radius (reach world)]
    (->> (:world/entities world)
         (filter (fn [[eid e]]
                   (and (not (:player/in-water? world))
                        (game-kinds (entity/kind e))
                        (or (not= :hoglin (entity/kind e))
                            (>= (:player/health world 20.0) combat/retreat-health))
                        (not (entity/dead? e))
                        (not (combat/given-up? world eid e))
                        (not (contains? bl eid))
                        (not (contains? bl (cell (:entity/pos e))))
                        (<= (physics/distance pos (:entity/pos e)) radius))))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]]
                 (when (and (intent/routable? world
                                              {:intent/kind :walk
                                               :intent/target (cell (:entity/pos e))})
                            (fightable-from-route? world e))
                   ee))))))

(defn fresh-kill
  "The hunt's kill whose drop is still to pick up: the last intent, when it is a :fight for :hunt
   that ended done with where its target was last seen, and the target is gone (entity/gone?: a
   fight that ended with the animal alive is no kill, whatever ended it); nil otherwise."
  [world]
  (let [l (:plan/last world)]
    (when (and (= :fight (:intent/kind l))
               (= :hunt (:intent/for l))
               (:intent/target-pos l)
               (entity/gone? world (:intent/target l)))
      l)))

(defn forage-goal
  "The path goal of a forage leg along heading [dx dz] from feet, reach blocks out: any cell more
   than twice reach, less one, on the plane from the point reach behind the feet (path's :afar),
   which the cells reach or more ahead satisfy at any height and the cells beside and behind do
   not, so a route finds the way (a pass, a bridge of land, round a lake) and the goal never
   names a cell in the air or the rock."
  [[x y z] [dx dz] reach]
  {:goal/kind :afar
   :goal/pos [(- x (* dx reach)) y (- z (* dz reach))]
   :goal/range (dec (* 2 reach))})

(defn forage-reaches
  "The reaches the leg-th leg tries, farthest first: forage-reach blocks for the first two legs and
   forage-reach more for each two after, up to forage-far, then forage-near-reaches: the legs
   go round a square that grows, as the fortress search's spiral does."
  [leg]
  (let [far (min forage-far (* forage-reach (inc (quot (long leg) 2))))]
    (distinct (cons far forage-near-reaches))))

(defn walkable-end
  "The cell a route r from feet is walked to: the last waypoint of a :found route, or of a :partial
   one (the budget spent) that still goes forage-partial-min blocks on the plane from the feet;
   nil otherwise."
  [feet {:path/keys [status waypoints]}]
  (let [to (last waypoints)]
    (when (and to
               (or (= :found status)
                   (and (= :partial status)
                        (>= (physics/horizontal-distance feet to) forage-partial-min))))
      to)))

(defn forage-leg
  "[to leg] of the next forage leg after state's ({:forage/since :forage/leg}): the end of the
   first route that reaches a forage-goal (walkable-end), along the heading the leg turns to
   (forage-headings, a quarter right of the last) and then the headings after it, at the leg's
   forage-reaches farthest first; nil when no heading has a route at any reach. leg is the one
   after the leg that found one. plan is the route finder, path/plan unless given: [world start
   goal opts] -> a route."
  ([world state] (forage-leg path/plan world state))
  ([plan world {:forage/keys [leg]}]
   (let [feet (intent/feet world)
         start (path/feet-cell (:player/pos world))]
     (some (fn [[reach k]]
             (let [heading (nth forage-headings (mod (+ (long leg) k) (count forage-headings)))
                   r (plan world start (forage-goal feet heading reach) {})]
               (when-let [to (walkable-end feet r)] [to (+ (long leg) k 1)])))
           (for [reach (forage-reaches leg) k (range (count forage-headings))] [reach k])))))

(defn forage-state
  "The :hunt/forage state of the provision-phase the bot is in now, nil when it is another's (or
   none yet): each phase has its own search and its own budget, so the surface search before the
   descent leaves the iron kit's whole forage-ms, hours of mining later."
  [world]
  (let [s (:hunt/forage world)] (when (and s (= (provision-phase world) (:forage/phase s))) s)))

(defn forage-budget
  "The ms a search of provision-phase p goes on from its first leg."
  [p]
  (if (= :early p) early-forage-ms forage-ms))

(defn afloat?
  "Is the body in water: :player/in-water?, or the box at :player/pos overlapping water
   (physics/touching-water?). The flag is written on a tick; a plan chooses on :go before any
   tick, and a pond's first intent was a forage ahead of the swim out (issue #504)."
  [world]
  (boolean (or (:player/in-water? world)
               (when-let [pos (:player/pos world)]
                 (physics/touching-water? (terrain/fluid-fn world) pos)))))

(defn foraging?
  "Does the bot go on looking for animals: provisioning?, the phase's forage-budget not past since its first leg, no look
   for a route lately that found none (:forage/retry-at), no failed intent in a row
   (:plan/attempts, and :plan/unwalkable for a walk that failed :no-path, which counts apart: a
   leg that failed ends the search for the moment, as twelve such walks in a row would end the
   plan), no :build running (a cast is never left half made), no :dig already begun (it is
   finished; a stairs or a tunnel is the descent and is not paused) and no :swim-out or :flee
   already begun (an escape is finished: the box leaves the water before the swim is done, and
   a forage there preempted it), not afloat? (in water the swim out comes first), the feet under
   the open sky (animals live on the surface: a bot deep in its mine has none to find), health
   at least forage-health and no hostile within forage-wary. Issue #501: bots went into the Nether with no food because
   nothing looked for animals past the 48 blocks the server shows, and herds are about one chunk
   in ten."
  [world]
  (let [{:forage/keys [since retry-at]} (forage-state world)]
    (boolean (and (provisioning? world)
                  (or (nil? retry-at) (>= (:time/now world 0) retry-at))
                  (zero? (:plan/attempts world 0))
                  (zero? (:plan/unwalkable world 0))
                  (not (#{:build :dig :swim-out :flee} (:intent/kind (:plan/intent world))))
                  (not (afloat? world))
                  (>= (:player/health world 20.0) forage-health)
                  (nil? (entity/nearest (entity/hostiles world) (:player/pos world) forage-wary))
                  (or (nil? since)
                      (< (- (:time/now world 0) since) (forage-budget (provision-phase world))))
                  (terrain/under-sky? world (intent/feet world))))))

(defn hunting?
  "Is the running intent the hunt's (:intent/for :hunt): a walk to an animal, its fight, the
   meat's collect?"
  [world]
  (= :hunt (:intent/for (:plan/intent world))))

(defn wants-meat?
  "Does the bot need a hunt: hungry with nothing edible held, or provisioning? for the Nether?"
  [world]
  (or (and (eat/hungry? world) (nil? (eat/food-held world))) (provisioning? world)))

(defmethod plan/done-by :provisioned?
  ;; never while a hunt intent runs: the hunt test's first run (feat/259-hunt 8ed7f50) killed its
  ;; cow, which left game with none, and the planner, the row done, abandoned the fight :goal-met
  ;; the tick the cow died, before the fight was done: no kill to collect, the beef left lying
  [world _]
  (or (not (wants-meat? world))
      (and (not (hunting? world))
           (nil? (fresh-kill world))
           (nil? (game world))
           (not (foraging? world)))))

(defmethod plan/next-intent :hunt
  ;; the drop of a kill, else the nearest animal, else the next leg of the search for one
  [world _ _]
  (let [k (fresh-kill world)
        [eid e] (when-not k (game world))]
    (cond k
          {:intent/kind :collect
           :intent/target (cell (:intent/target-pos k))
           :intent/drops meats
           :intent/for :hunt}
          e
          (if (<= (physics/horizontal-distance (:player/pos world) (:entity/pos e)) strike-range)
            (cond-> {:intent/kind :fight :intent/target eid :intent/for :hunt}
              (= :hoglin (entity/kind e)) (assoc :intent/yield? true))
            {:intent/kind :walk :intent/target (cell (:entity/pos e)) :intent/for :hunt})
          (foraging? world) {:intent/kind :forage :intent/for :hunt})))

(defmethod intent/run :forage
  ;; {:intent/kind :forage}: choose the next leg of the search, then walk it as a :walk (the
  ;; intent becomes the walk, so its arrival is a walk's: memory/arrived). The leg and the time the
  ;; search began are kept in :hunt/forage; none found at any heading and reach is done with
  ;; nothing walked, and the next look comes forage-retry-ms on (:forage/retry-at): the bot may
  ;; stand in a pit or a thicket a few blocks from open ground
  [world i event]
  (let [state
        (or (forage-state world)
            {:forage/since (:time/now world) :forage/leg 0 :forage/phase (provision-phase world)})
        found (forage-leg world state)]
    (cond (nil? found)
          (-> world
              (assoc :hunt/forage
                     (assoc state :forage/retry-at (+ (:time/now world 0) forage-retry-ms))
                     :player/controls {})
              intent/done)
          :else
          (let [[to leg] found
                walk (assoc i :intent/kind :walk :intent/target to)]
            (intent/run (assoc world
                               :hunt/forage
                               (-> state
                                   (assoc :forage/leg leg)
                                   (dissoc :forage/retry-at))
                               :plan/intent walk)
                        walk
                        event)))))
