(ns clojurecraft.blaze
  "Blaze rods (issue #262): the :blaze-rods goal, done when rods-wanted are held: six give the
   twelve blaze powder twelve eyes of ender take, and one is spare. Worked in the Nether only.

   Requiring this namespace registers the row's rules and the :camp and :rest executors. Planned
   by :blaze-rods, in order: hurt under combat/retreat-health, into a cell out of the watching
   blazes' sight, or eat when the food is under the healing level, or back away from the nearest
   watcher, or rest out of sight until the health is back when that rest can heal (heals?; one
   that cannot is never chosen or kept: probe 40634461444 rested 4.4 h at 9.3 health and food 17).
   Food is picked up, crafted from what is held, or taken from a hoglin only at or above
   retreat-health (ruststeve broke off under 10 health: a run that fought on killed the blaze and
   died with it); wary under eat-under, the same cover,
   meal or rest before the next fight; watched by a volley of blazes at once, cover whatever the
   health; a rod lying near is picked up (:collect-drops: a blaze drops one a kill in two, so
   nothing lying there is no failure); the nearest blaze within fight-radius is fought (combat's
   :fight, attacks 650 ms apart, aimed at its body as it hovers); a fire by the spawner is put
   out (a lit floor stops its blazes); else the remembered spawner (a spawner seen in the Nether
   is a fortress's, memory/nether-landmark-kinds) is walked to, or tunnelled to by the fortress's
   steps when no route reaches it, and camped by until its blazes come; with no spawner known (or each one
   given-up?: stands beside it blocked), the next leg of the fortress row's spiral search, and a wait :no-blaze
   only when no heading has a way to go. Before all of it, a blaze at hand (within 4, at-hand-blaze) is fought when
   the bot is not hurt, and ends any other running intent (plan/interrupts?): it follows into any cover. The
   fight carries :intent/yield?, so under combat/retreat-health it ends and the cover, the meal or the rest
   follows, not combat's straight retreat. The :defend row answers anything else hostile within 4 first; blazes it
   leaves to this row while it is worked (combat/answers), or its fights would override the
   cover. A zombified piglin that has struck (combat/striker) is backed away from by :defend, and this row
   never rests beside it.

   Fireballs are not dodged: ruststeve never needed it with its break-off at 10 health; the
   damage-event log is where to look if they kill. The bot does not put itself out: no water
   works in the Nether, and the gym rows give Fire Resistance until potions exist."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.dodge :as dodge]
            [clojurecraft.drops :as drops]
            [clojurecraft.eat :as eat]
            [clojurecraft.entity :as entity]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]
            [datascript.core :as d]))

(set! *warn-on-reflection* true)

(def rods-wanted 7)                   ; six for the twelve blaze powder twelve eyes take, one spare
(def fight-radius 12.0)               ; a blaze this near the feet is fought
(def camp-range 8.0)                  ; blocks from the spawner's centre to the eye where the bot waits for its blazes (a spawner wakes for a player within 16)
(def camp-ms 60000)                   ; a camp with no blaze this long fails :no-blaze (a natural spawner waits 10 to 40 s between spawns)
(def spawner-radius 256)              ; how far off a remembered spawner may be and still be gone to
(def candidates 3)                    ; nearest blazes asked for a route, as the hunt asks of its animals
(def side-steps [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]]) ; the cells beside a spawner a stand is looked for in
(def stand-levels [0 -1 1])           ; the heights from the spawner's own a stand is looked for at, in this order: its blazes' floor first
(def stand-memory 3)                  ; tunnels last done whose target is looked for among the stands, before the nearest
(def stands-stalled 2)                ; stands beside one spawner whose tunnels stalled (stalled) before the spawner's site is given up: the cells beside it are one place, not twelve tries
(def stands-tried 2)                  ; stands beside one spawner whose tunnel ended :blocked before the spawner is given up
(def spawner-reach 2.0)                ; blocks from the feet cell's centre to the spawner's a tunnel to it ends within: in its room, at the blazes' floor
(def cover-range 16.0)                ; blazes this near that see the bot are the ones a hurt bot takes cover from (they shoot that far)
(def at-hand combat/defend-radius)    ; blocks within which a blaze is fought first and to the end: it follows the bot into any cover and round any retreat
(def engage-health 18.0)              ; health a fight with a blaze not at hand, and so the next after a rest, is begun at: one fireball and the fire it lights take 5 to 7, two blazes twice that (blaze-race-f geared run 1 began a fight at 10.5 and died in it)
(def volley 3)                        ; blazes watching at once that send the bot to cover whatever its health: a natural spawner's burst is four, each hit 3.12 through iron armour
(def rested 18.0)                     ; health a rest out of sight ends at: two volleys' hits over the wary line
(def rest-ms 90000)                   ; a rest with no health coming back this long fails :no-heal (the slow heal is one point every four seconds)
(def fire-radius 4)                   ; cells from a spawner a fire is put out in: a fire lights its cells past 11, where a spawner's blaze no longer spawns
(def corridor-reach 48.0)             ; bricks within this of the feet are walked or tunnelled to; farther is another fortress, not this leg
(def corridor-nodes 800)              ; nodes one corridor walk may spend finding its route (the cover search's budget)

(def rod (recipe/item-id :blaze_rod))
(def spawner (blocks/default-state :spawner))

(defn rods "Blaze rods held." [world] (inventory/item-count world rod))

(defn blazes
  "{eid entity} of the blazes alive within fight-radius of the feet, none blacklisted (a ghost)."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (= :blaze (entity/kind e))
                         (not (entity/dead? e))
                         (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e)) fight-radius))))
          (:world/entities world))))

(defn reachable?
  "Can blaze e be fought from where the bot can walk: hittable from here (combat/hittable?: in
   sight, its feet no more than three over the bot's nor two under), and the fight's approach
   comes to it (combat/approachable?: the sword reaches from here or from a press that has a
   place to stand, or the approach's own route walks there). Gym blaze-fortress-a (run
   38892386220) tunnelled to its spawner and failed three fights in a row :unreachable on blazes
   seen through the fortress's walls, every run; blaze-fire-a runs 2 and 4 (run 39131023637) did
   again on blazes a walk's route came within three of, the approach's route, within two,
   finding none. A blaze within approach-range below a wall the press would have to occupy, with
   no route down, is not (issue #564)."
  [world e]
  (and (combat/hittable? world e) (combat/approachable? world e)))

(defn quarry
  "[eid entity] of the blaze to fight: the first blaze within fight-radius (blazes) that is
   reachable?, or nil."
  [world]
  (let [pos (:player/pos world)]
    (->> (blazes world)
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]] (when (reachable? world e) ee))))))

(defn at-hand-blaze
  "[eid entity] of the nearest blaze within at-hand of the feet that is reachable?, or nil: fought
   before any cover, meal or rest, and to the end. A blaze that loses sight of the bot flies to
   where it last saw it, and one within two blocks hits in melee: gym blaze-fire-b run 3 (run
   39136853644) walked into four covers in eight seconds with blazes half a block to two off
   following it into each, from 14 health to none, and never swung."
  [world]
  (let [pos (:player/pos world)]
    (->> (blazes world)
         (filter (fn [[_ e]] (<= (physics/distance pos (:entity/pos e)) at-hand)))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]] (when (reachable? world e) ee))))))

(defn fetch-walk
  "The walk onto the cell drop e lies in: the feet there are in the pickup box of a drop in a pit
   under the floor (:intent/for :rod ends standing in the cell)."
  [e]
  {:intent/kind :walk :intent/target (hunt/cell (:entity/pos e)) :intent/for :rod})

(defn fetchable?
  "Can the bot pick drop e up: its cell is not solid (a drop a block was placed over, no
   contact reaches), and it lies in reach of the pickup box from the floor, or a route walks
   onto its cell (under-reach?)? Never one hanging over a drop (drops/over-void?): a rod lost to
   a lava sea, and a collect of it would be done at once, the planner choosing it again. Nor one
   the walk to it would step off a ledge toward (intent/ledge-ahead?): :collect-drops is done at
   once there too (blaze-race-c job 1, 3a082f7b7: `:collect-drops [2375 70 2338]` started 4060
   times in 3 min 23 s, 50 ms apart, the bot standing in fire the whole while, `HIT type 21`, and
   burned to death)."
  [world e]
  (and (not (drops/over-void? world e))
       (not (intent/ledge-ahead? world (:entity/pos e)))
       (not (some-> (terrain/block-at world (hunt/cell (:entity/pos e)))
                    blocks/solid?))
       (or (not (drops/under-reach? world e)) (intent/routable? world (fetch-walk e)))))

(defn rod-lying
  "[eid entity] of the nearest drop that may be a rod (drops/holds?) within drops/drop-radius of
   the feet, not in a cell a pickup failed in (the cell is blacklisted), the first of the
   candidates nearest that is fetchable?, or nil. Sim race 39712220957 (issue #500): drops of the
   tunnel's own digs, lying in pits the bot's floor was over, were gone to and stood over until
   the collect failed :not-picked-up, three times, and the plan with it."
  [world]
  (let [bl (:plan/blacklist world #{})
        pos (:player/pos world)]
    (->> (drops/lying world rod pos drops/drop-radius)
         (remove (fn [[_ e]] (contains? bl (hunt/cell (:entity/pos e)))))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]] (when (fetchable? world e) ee))))))

(defn rod-room
  "A :toss of junk when a rod lies within reach of a pickup and every slot is full with no rod
   held to merge into, else nil: a drop is picked up on contact, and with no slot free it never
   is. Lane 1 of race 41181938317 (c996f54), s2 Clj_r02: five blazes killed, seven rods dropped,
   :collect-drops [333 77 300] failed :not-picked-up four times, rods held none, all 36 slots
   full (877 cobblestone, 128 diorite, 64 cobbled deepslate, 64 netherrack, gravel, dirt ...).
   Lane 4's Clj_r06 saw fifteen drops and held three."
  [world]
  (when (and (some? (rod-lying world))
             (zero? (rods world))
             (nil? (:window/open world))
             (window/tossable world (window/view world :inventory)))
    {:intent/kind :toss}))

(defn cells-beside
  "Every cell a stand beside spawner sp may be: its side-steps at each of stand-levels, level
   by level."
  [sp]
  (for [dy stand-levels s side-steps] (fortress/plus sp (update s 1 + dy))))

(defn given-up?
  "Have stands-tried of spawner sp's stand cells been blacklisted (a tunnel to each ended
   :blocked)? Real race s2 Clj_r06 (issue #502) tunnelled four times in a row to the same stand
   beside a spawner 50 blocks off and 14 up, each one :blocked: nothing blacklisted the spawner,
   and the stand it chose did not depend on the blacklist."
  [world sp]
  (let [bl (:plan/blacklist world #{})] (>= (count (filter bl (cells-beside sp))) stands-tried)))

(declare stand)

(def spawner-switch-margin 16.0) ; blocks nearer another spawner must be for the tunnel's own to be left

(defn tunnelled-spawner
  "The spawner among candidates that the running :tunnel-to, else the last one done, is for (its
   target the spawner or a stand beside it), or nil."
  [world candidates]
  (let [i (:plan/intent world)
        target (or (when (= :tunnel-to (:intent/kind i)) (:intent/target i))
                   (:intention/target (memory/last-done world :tunnel-to)))]
    (when target (first (filter #(or (= % target) (some #{target} (cells-beside %))) candidates)))))

(defn nearest-spawner
  "The spawner on record in the bot's dimension nearest the eye, within spawner-radius and not
   given-up?, or nil; one blacklisted (a walk to it failed) is kept while a stand beside it is
   open, to be tunnelled to. Race lane 4 (run 40980342194) s1 r15, at its fortress, had
   `:walk [544 57 203]` fail :no-path with the spawner a block off, lost the spawner to the
   blacklist and searched far spirals for 4 hours ([-330 88 483] ...); s1 r16 `:walk failed :stuck`
   there; s2 r08 and r09 never arrived."
  [world]
  (let [bl (:plan/blacklist world #{})
        eye (game/eye world)
        candidates (remove #(or (and (bl %) (nil? (stand world %))) (given-up? world %))
                           (memory/positions-now world [spawner]))
        nearest (memory/nearest-of eye spawner-radius candidates)
        kept (tunnelled-spawner world candidates)]
    ;; the spawner the running or last-done :tunnel-to is for is kept unless another is nearer by more than
    ;; spawner-switch-margin: two spawners ~67 blocks off, a block or two of the bot's pacing between tunnels,
    ;; sent each new tunnel to the other (blaze-race-z9 job 5: stands [2224 60 1726] and [2209 60 1794]
    ;; by turns every 25 s for 32 minutes)
    (if (and kept
             nearest
             (not= kept nearest)
             (<= (physics/distance eye (physics/centre kept))
                 (+ (physics/distance eye (physics/centre nearest)) spawner-switch-margin)))
      kept
      nearest)))

(defn level-with?
  "Are the feet within a block of spawner sp's height: its blazes' floor?"
  [world sp]
  (<= (abs (- (physics/y-of (intent/feet world)) (physics/y-of sp))) 1))

(defn walkable?
  "Can the bot walk to spawner sp: level-with? it (a route judges the plane only,
   intent/routable?), a route to it, and no walk to it failed (blacklisted)."
  [world sp]
  (and (level-with? world sp)
       (not (contains? (:plan/blacklist world #{}) sp))
       (intent/routable? world {:intent/kind :walk :intent/target sp})))

(defn top-in-sight?
  "Is the line from the eye to the point a block over spawner sp's centre, where its blazes
   appear, clear (combat/clear?)? A camp in a cell the spawner's top is not seen from is a camp
   its blazes never see: gym blazefort-k (run 38913023560) hid from a volley in its tunnel,
   and a camp there would wait on blazes hovering unseen in the room."
  [world sp]
  (combat/clear? world (game/eye world) (update (physics/centre sp) 1 + 1.0)))

(defn at-spawner?
  "Is the bot where it camps by spawner sp: the feet within spawner-reach of it and level-with?
   it (where a tunnel to it ends), or the eye within camp-range with the spawner walkable? (on its
   floor, not in a tunnel beside its room) and its top-in-sight?, or the eye within intent/reach
   of it with the spawner walkable? (a walk to it is done in its first tick and chosen again every
   tick: snapshot test/issues/514/b10231346-R4-21-1, the bot 3.96 from the spawner at
   [544 57 203]; race lane 4 s2 r08 and r09 flipped :tunnel-to between [543 57 203] and
   [544 57 203] at that spawner). Gym blaze-fortress-b (run
   38893941350) tunnelled to within 6 of its spawner, four blocks over it in the wall, and
   camped there ten minutes: the blazes the spawner made in its room below were out of reach,
   every run; blaze-fortress-c (run 38895759229) run 3 came up under its spawner, three below
   its floor, killed eight blazes from there and every rod lay on the floor over it."
  [world sp]
  (or (and (fortress/arrived? world sp spawner-reach) (level-with? world sp))
      (and (<= (physics/distance (game/eye world) (physics/centre sp)) intent/reach)
           (walkable? world sp))
      (and (<= (physics/distance (game/eye world) (physics/centre sp)) camp-range)
           (walkable? world sp)
           (top-in-sight? world sp))))

(defn open?
  "Are cell c and the one over it loaded and not solid: room for the body?"
  [world [x y z :as c]]
  (every? #(some-> (terrain/block-at world %)
                   blocks/solid?
                   not)
          [c [x (inc y) z]]))

(defn stand
  "The cell beside spawner sp with room for the body (open?) and not blacklisted (a tunnel to it
   ended :blocked), at its own height when one is there, else a level under or over it
   (stand-levels), nearest the feet: where a tunnel to it ends, on its blazes' floor; nil when
   none is. The target of a tunnel last done, when it is still one of those cells, before the
   nearest: the feet moved a cell or two in the pocket the tunnel dug and the nearest of two
   stands side by side changed with them, so the tunnel's trail was kept for the other target and
   the same cells were dug again, 140 minutes (blaze-race-a run 5, 727b4c2b2: :tunnel-to
   [2209 60 1794] and [2210 60 1793] by turns every 14.5 s, 0 attacks to the five-hour cap; the
   same on blaze-race-c, 3a082f7b7). So is the target of the :tunnel-to running now: race lane 4
   (run 40980342194) s2 r08 and r09 sent [543 57 203] and [544 57 203] by turns every 12 s, 539
   and 681 intents, none ever done, each abandoned for the other."
  [world sp]
  (let [bl (:plan/blacklist world #{})
        open (remove bl (filter #(open? world %) (cells-beside sp)))
        running (let [i (:plan/intent world)]
                  (when (= :tunnel-to (:intent/kind i)) (:intent/target i)))
        kept (->> (cons running
                        (keep :intention/target
                              (memory/newest-dones world :tunnel-to stand-memory)))
                  (filter (set open))
                  first)]
    (or kept
        (some (fn [dy]
                (memory/nearest-of (physics/centre (intent/feet world))
                                   Double/POSITIVE_INFINITY
                                   (filter #(= (+ (physics/y-of sp) dy) (physics/y-of %)) open)))
              stand-levels))))

(defn hurt?
  "Is the health the server last told under combat/retreat-health?"
  [world]
  (< (:player/health world 20.0) combat/retreat-health))

(defn meal
  "An :eat of the food held (rotten flesh when it is all there is, eat/scrap-held) when the food is under hunger/heal-level (health comes back only from
   there, and fast while the meal's saturation lasts), or nil."
  [world]
  (when-let [food (and (< (:player/food world hunger/heal-level) hunger/heal-level)
                       (or (eat/food-held world) (eat/scrap-held world)))]
    {:intent/kind :eat :intent/item food}))

(defn heals?
  "Can a rest bring the health back: the food the server last sent at or over hunger/heal-level
   (natural regeneration), or edible food held (the meal first)? Sim race 39712220957 (issue
   #501, world R1/2) rested three times ninety seconds at food 16 with nothing to eat, health
   falling, and the run ended :no-heal. A rest that cannot heal is never chosen and a running
   one ends (probe 40634461444, sim R1/17, rest 360: health 9.3, food 17, nothing held, 4.4 h).
   The exception is a watched void retreat, which stays on the lip (issue #514)."
  [world]
  (or (>= (:player/food world hunger/heal-level) hunger/heal-level)
      (some? (or (eat/food-held world) (eat/scrap-held world)))))

(def food-drop-ids
  "Item ids a dropped stack is worth picking up so a rest can heal: everything eat/edible, and
   rotten flesh (the meal eats it when it is all there is)."
  (set (map recipe/item-id (conj (set (keys eat/edible)) :rotten_flesh))))

(def meal-crafts
  "Edible recipes the inventory's 2×2 grid can craft, the most nourishing first. A recipe that
   needs the 3×3 table is not started from here: looking for a table is not a meal."
  (->> recipe/recipes
       (filter (fn [r]
                 (and (seq (recipe/needs r)) (eat/edible (:recipe/result r)) (recipe/fits? r 2))))
       (sort-by (fn [r] (- (long (eat/edible (:recipe/result r))))))
       vec))

(defn ingredients-held?
  "Does counts hold every ingredient one craft of recipe r consumes?"
  [counts r]
  (every? (fn [[s n]] (>= (recipe/have counts s) n)) (recipe/needs r)))

(defn craft-meal
  "A :craft of the most nourishing edible recipe whose ingredients are already held, or nil.
   Never a gather and never a search for a table: at 9 health the fortress is not left to find
   wheat."
  [world]
  (let [counts (recipe/counts (:player/inventory world))]
    (when-let [r (some #(when (ingredients-held? counts %) %) meal-crafts)]
      {:intent/kind :craft :intent/recipe (:recipe/id r) :intent/window :inventory})))

(defn food-lying
  "[eid entity] of the nearest edible drop within hunt/hunt-radius that the eye can see
   (combat/clear?), that can be picked up (fetchable?), or nil."
  [world]
  (let [pos (:player/pos world)
        eye (game/eye world)
        bl (:plan/blacklist world #{})]
    (->> (:world/entities world)
         (filter (fn [[eid e]]
                   (and (entity/item? e)
                        (not (entity/dead? e))
                        (contains? food-drop-ids (:entity/item e))
                        (not (contains? bl eid))
                        (not (contains? bl (hunt/cell (:entity/pos e))))
                        (<= (physics/distance pos (:entity/pos e)) hunt/hunt-radius)
                        (combat/clear? world eye (:entity/pos e)))))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]] (when (fetchable? world e) ee))))))

(defn food-intent
  "The pickup of edible drop e: a walk onto its cell when it lies under the pickup box, else
   :collect-drops of what it holds."
  [world e]
  (let [n (recipe/item-name (:entity/item e))
        cell (hunt/cell (:entity/pos e))]
    (if (drops/under-reach? world e)
      {:intent/kind :walk :intent/target cell :intent/for :food}
      {:intent/kind :collect-drops :intent/item n :intent/target cell})))

(defn hoglin-intent
  "A walk or a yielding fight of the nearest hoglin hunt/game would take, or nil. hunt/game
   already refuses one under combat/retreat-health: a hoglin hits back, and 9.3 is under 10
   (issue #577), so it is not approached. The fight yields, so a hit under that line ends it."
  [world]
  (when-let [[eid e] (hunt/game world)]
    (when (= :hoglin (entity/kind e))
      (if (<= (physics/horizontal-distance (:player/pos world) (:entity/pos e)) hunt/strike-range)
        {:intent/kind :fight :intent/target eid :intent/for :hunt :intent/yield? true}
        {:intent/kind :walk :intent/target (hunt/cell (:entity/pos e)) :intent/for :hunt}))))

(def crimson-ground #{:crimson_nylium}) ; the floor a hoglin is found on
(def food-sight 16)                     ; blocks of crimson ground crossed to find a hoglin, only at or above retreat-health

(defn crimson-intent
  "A walk to the nearest standable cell on crimson nylium within food-sight, or nil. Only at or
   above combat/retreat-health: under it, walking into a crimson forest is walking into a hoglin
   (issue #577). One route of corridor-nodes; a stand with none is left, not failed :no-path."
  [world]
  (when (>= (:player/health world 20.0) combat/retreat-health)
    (let [[x y z] (intent/feet world)
          stands (for [dx (range (- food-sight) (inc food-sight))
                       dz (range (- food-sight) (inc food-sight))
                       dy [-1 0 1]
                       :let [floor [(+ x dx) (+ y dy) (+ z dz)]
                             stand (update floor 1 inc)]
                       :when (and (not= stand [x y z])
                                  (contains? crimson-ground
                                             (some-> (terrain/block-at world floor)
                                                     blocks/name-of))
                                  (path/standable? world stand))]
                   stand)
          to (memory/nearest-of (:player/pos world) (double food-sight) stands)
          walk
          {:intent/kind :walk :intent/target to :intent/for :food :intent/max-nodes corridor-nodes}]
      (when (and to (intent/routable? world walk)) walk))))

(defn provisions
  "How to get the food a rest needs when heals? is false: an edible drop in sight, a meal crafted
   from what is held, or (only at or above combat/retreat-health) a nearby hoglin or the crimson
   ground one lives on. nil when none of that can be had. The hunt row itself does not run in
   the Nether at food 17 (eat/hungry? is food under 14, and provisioning? is the overworld), so
   this asks hunt/game directly."
  [world]
  (or (when-let [[_ e] (food-lying world)] (food-intent world e))
      (craft-meal world)
      (hoglin-intent world)
      (crimson-intent world)))

(defn watchers
  "{eid entity} of the blazes alive within cover-range of the feet that have the bot in sight
   (combat/seen?: a clear line from the eye to each): what shoots at it, and what a bot in
   cover is out of the sight of."
  [world]
  (let [pos (:player/pos world)]
    (into {}
          (filter (fn [[_ e]]
                    (and (= :blaze (entity/kind e))
                         (not (entity/dead? e))
                         (<= (physics/distance pos (:entity/pos e)) cover-range)
                         (combat/seen? world e))))
          (:world/entities world))))

(defn watcher
  "[eid entity] of the nearest of the watchers, or nil: what a hurt bot hides from first."
  [world]
  (entity/nearest (watchers world) (:player/pos world)))

(def portal-states "Every state of the Nether portal block." (blocks/states-of :nether_portal))

(defn home-portal
  "The Nether portal block on record in the bot's dimension nearest the feet, or nil: the way
   back to the overworld, where the animals are (memory/positions-now; portals are remembered
   whenever they are seen lit)."
  [world]
  (memory/nearest-of (physics/centre (intent/feet world))
                     Double/POSITIVE_INFINITY
                     (memory/positions-now world portal-states)))

(defn home-op
  "The op that stands in the portal block p until the dimension is the overworld."
  [p]
  {:build/op :stand-in
   :build/pos p
   :build/stand p
   :build/step :enter
   :build/done [:dimension dimension/overworld]})

(defn food-trip
  "The intent to go home through the portal for food, or nil when no portal is on record: asked
   of a bot hurt under combat/retreat-health that cannot heal (heals? false) with nothing to take
   in the Nether (provisions). Sim R1/17 reached the blazes at 9.3 health, food 17, nothing edible,
   and no fight is allowed there; the overworld has animals (hunt/provisioning? stocks
   hunt/provision-wanted again with the portal kit held) and the :return-nether row brings it back."
  [world]
  (when (home-portal world) {:intent/kind :portal-home}))

(def food-reserve 4) ; edible items held before the stage fights: a heal from under 10 to 20 takes more than one volley's worth, and the Nether has little to eat (issue #262, R1/17)

(defn stocking
  "The food to take before the stage starts, or nil: fewer than food-reserve edible items held,
   no blaze watching, and a source at hand (an edible drop in sight, a meal craftable from what
   is held, a hoglin hunt/game would take, which it refuses under combat/retreat-health). Never
   a search for crimson ground: the stage is not held up for a forest that may not be near.
   Sim R1/17 reached the blazes at 9.3 health with food 17 and nothing edible, where nothing
   heals and no fight is allowed (follow-up 1:15 AM)."
  [world]
  (when (and (< (hunt/edible-held world) food-reserve) (empty? (watchers world)))
    (or (when-let [[_ e] (food-lying world)] (food-intent world e))
        (craft-meal world)
        (hoglin-intent world))))

(defn collectable?
  "May a rod lying by the spawner be gone for: no blaze has the bot in sight, or the health is at
   least rested? Sim R1/1 (probe 40779151074) went for the drops beside the spawner at 13.4, 13.6
   and 8.0 health with blazes alive, was hit on each and died twice. A rod left is a rod the next
   blaze drops again."
  [world]
  (or (empty? (watchers world)) (>= (:player/health world 20.0) rested)))

(defn outnumbered?
  "Do volley blazes or more watch the bot: more volleys than its armour and its sword answer,
   whatever its health. Gym blazefort-k (run 38913023560) runs 3 and 4 died at the spawner from
   over 14 health in four seconds, a hit a second from the natural spawner's burst of four."
  [world]
  (>= (count (watchers world)) volley))

(def body-over-cell 0.4)               ; blocks over its cell's centre a blaze's body is looked for (its aim, combat/aim-height over its feet)

(defn unseen-from?
  "Is the line from the eye height over cell [x y z] to the body of a blaze in cell c blocked:
   the cell out of its sight?"
  [world [x y z] c]
  (not (combat/clear? world
                      [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)]
                      (update (physics/centre c) 1 + body-over-cell))))

(defmethod path/goal-done? :unseen
  ;; a cell from whose eye height the line to the body of the blaze in the cell :goal/pos, and
  ;; of each in :goal/cells, is blocked: out of all their sight
  [world {:goal/keys [pos cells exclude]} node]
  (and (not (contains? exclude node)) (every? #(unseen-from? world node %) (cons pos cells))))

(defmethod path/heuristic :unseen [_ _] 0.0)

(def spawn-range 4)   ; cells each way on x and z from the spawner a blaze is placed in (BaseSpawner SpawnRange, vanilla's default; mob/spawner :box [9 3 9]); the gym arena spawner sets 2
(def spawn-height 1)  ; cells over or under the spawner's own a blaze is placed at (nextInt(3) - 1)
(def spawner-wake 16) ; blocks from the spawner's centre a player keeps it running within (RequiredPlayerRange, default 16; mob/spawner :wake)

(defn in-spawn-volume?
  "Is cell c in the volume a spawner at cell sp places its blazes in: spawn-range each way on x and
   z and spawn-height on y? A bot there has them appear at its elbow: gym blaze-fortress-race run 4
   had 497 and 498 added 1.5 and 0.9 blocks from it (the bot 3 blocks from the spawner), the first
   hit in the same tenth of a minute."
  [[sx sy sz] [x y z]]
  (and (<= (abs (- x sx)) spawn-range)
       (<= (abs (- z sz)) spawn-range)
       (<= (abs (- y sy)) spawn-height)))

(defn awake-from?
  "Is cell c within spawner-wake of the centre of the spawner at cell sp: its blazes still come?"
  [[sx sy sz] [x y z]]
  (<= (physics/distance [(+ x 0.5) (+ y 0.5) (+ z 0.5)] [(+ sx 0.5) (+ sy 0.5) (+ sz 0.5)])
      spawner-wake))

(defn walled?
  "Is cell c, or the one over it, a loaded solid block? An unloaded cell is not a wall: nothing is
   known of it, and the edge of the loaded world is no tunnel."
  [world [x y z]]
  (boolean (some #(some-> (terrain/block-at world %)
                          blocks/solid?)
                 [[x y z] [x (inc y) z]])))

(defn exposure
  "How many of the four horizontal neighbours of cell c are not walled (walled?): 2 in a tunnel
   one wide, 3 or 4 in a room, and at the edge of the loaded chunks an unloaded neighbour counts
   as open (the first cut of it found false nooks at a chunk's edge)."
  [world [x y z]]
  (count (remove (fn [[dx dz]] (walled? world [(+ x dx) y (+ z dz)])) [[1 0] [-1 0] [0 1] [0 -1]])))

(def nook-steps 12) ; cells of walk from the feet a nook is looked for within
(def nook-nodes 400) ; cells one search for a nook visits at most

(def idle-camps 3)       ; camps done in a row with no fight between, after which the nook is left for the room
(def idle-ms 600000)     ; ms those camps are counted over, and the fight is looked for within

(defn sees-spawner?
  "Is the line from the eye over cell c to a point a block over the spawner at cell sp clear
   (combat/clear?)? A blaze goes for a player it sees: out of its sight the bot is not a target."
  [world sp c]
  (let [[x y z] c]
    (combat/clear? world
                   [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)]
                   (update (physics/centre sp) 1 + 1.0))))

(defn idle?
  "Have idle-camps camps been done within idle-ms of now and no fight in that time? Blaze-race-c
   jobs 2 and 6 (3a082f7b7, real) sat in the nook 4.9 hours: the bot at [2338.6 60.0 2290.5], the
   blazes (six, the spawner's cap) at [2346..2350 60..61 2280..2284], 12 to 13 blocks off and
   never in its line, 29 added in 300 minutes, 0 attacks."
  [world]
  (let [now (:time/now world 0)
        within? #(<= (- now (:intention/at %)) idle-ms)
        camps (memory/newest-dones world :camp idle-camps)
        fight (memory/last-done world :fight)]
    (and (= idle-camps (count camps)) (every? within? camps) (not (and fight (within? fight))))))

(defn nook
  "The cell, for the spawner at cell sp, to camp and take cover in instead of the room: the
   nearest by walking from the feet (within nook-steps, nook-nodes visited) that the body stands in
   (path/standable?), with an exposure of 2 or less (a tunnel one wide: the bot's own dug one, not
   a fortress's corridor three wide), outside the spawner's volume (in-spawn-volume?) and within its
   wake (awake-from?), or nil. In a corridor one wide a blaze reaches the bot from the room along
   one line, one at a time. Gym blaze-fortress-race run 4 (run 40770939167) camped at the tunnel's
   end [2347 60 2282], in the room: blazes 497 and 498 were added 1.5 and 0.9 blocks from it and
   took 18.3 health in 5 s. Searched from the feet and not read off the fortress trail, which is
   kept for one tunnel target and starts afresh with every retreat and re-tunnel (blaze-race-b
   runs re-tunnelled to [2346 60 2281] and [2347 60 2282] by turns)."
  [world sp]
  (let [at (path/cells world)
        ok? (fn [c]
              (and (<= (exposure world c) 2)
                   (not (in-spawn-volume? sp c))
                   (awake-from? sp c)
                   (sees-spawner? world sp c)))
        start (intent/feet world)]
    (when-not (idle? world)
      (loop [frontier [start]
             seen #{start}
             depth 0
             visited 0]
        (let [hit (some #(when (ok? %) %) frontier)]
          (cond hit hit
                (or (empty? frontier) (>= depth nook-steps) (>= visited nook-nodes)) nil
                :else
                (let [next (into []
                                 (comp (mapcat (fn [[x y z]]
                                                 (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
                                                       dy [0 -1 1]]
                                                   [(+ x dx) (+ y dy) (+ z dz)])))
                                       (remove seen)
                                       (filter #(path/standable? at %))
                                       (distinct))
                                 frontier)]
                  (recur next (into seen next) (inc depth) (+ visited (count next))))))))))

(defn at-nook?
  "Are the feet in the cell nook names for the spawner at cell sp?"
  [world sp]
  (let [n (nook world sp)] (and (some? n) (= n (intent/feet world)))))

(def cover-churn 3)      ; walks done within cover-churn-ms after which the cover is no longer asked: it is not holding
(def cover-churn-ms 8000) ; ms the walks are counted over

(defn churning?
  "Have cover-churn walks been done within cover-churn-ms? Each cover walk is a few cells, the next
   cover excludes the last two cells walked to (`recent`), and the blazes move: the bot walked
   between [2344 57 2279] and [2344 57 2294] every half second under fire (blaze-race-b runs 1, 2
   and 3, bot.log 01:58:50 to 01:58:55). The cover that is not holding is not asked again for a
   spell; what follows is the fight, the meal or the rest."
  [world]
  (let [now (:time/now world 0)]
    (>= (count (filter #(<= (- now (:intention/at %)) cover-churn-ms)
                       (memory/newest-dones world :walk cover-churn)))
        cover-churn)))

(defn cover
  "A walk into the nearest cell out of the sight of every blaze in es, the nearest first
   (path's :unseen goal; back round the tunnel's corner the bot came by), stood in, or nil when
   no route finds one or es is empty. Gym blaze-fortress-e (run 38900794167) stood by its spawner
   under 10 health, waiting to heal while burning and shot, and every run died there;
   blaze-fortress-f (run 38902306126) walked to a cell ten off on the plane that the blazes
   still saw, the walk done at once in reach of it, twenty times a second. A route found with
   no step (the feet's own cell counted unseen, its line a touch apart from the eye's to the
   blaze) is none: blaze-fortress-g run 3 (run 38904048480) walked to nothing and the run threw."
  [world es]
  (let [pos (:player/pos world)
        recent (into #{} (map :intention/target) (memory/newest-dones world :walk 2))
        [c & cs] (map #(if (vector? %) % (hunt/cell (:entity/pos %)))
                      (sort-by #(if (vector? %)
                                  (physics/distance pos (physics/centre %))
                                  (physics/distance pos (:entity/pos %)))
                               es))
        r (when (and c (not (churning? world)))
            (path/plan world
                       (path/feet-cell pos)
                       {:goal/kind :unseen :goal/pos c :goal/cells (vec cs) :goal/exclude recent}
                       {:path/max-nodes 800}))
        to (last (:path/waypoints r))]
    (when (and r (= :found (:path/status r)) to (not= to (intent/feet world)))
      {:intent/kind :walk :intent/target to :intent/for :cover})))

(defn in-open?
  "Is the bot in the open where blazes or the active spawner see it: watchers watching, or an active spawner in sight?"
  [world]
  (let [sp (nearest-spawner world)]
    (boolean (or (seq (watchers world))
                 (and sp
                      (<= (physics/distance (physics/centre (intent/feet world))
                                            (physics/centre sp))
                          cover-range)
                      (top-in-sight? world sp))))))

(defn nook-cover
  "A walk to the nook (nook) when it is another cell than the feet and out of the sight of every
   blaze in threats (the spawner's top, a cell, is not asked: a spawner shoots nothing), else nil:
   the tunnel the bot dug is the cover it knows."
  [world sp threats]
  (let [blazes (remove vector? threats)]
    (when-let [n (and sp (seq blazes) (not (churning? world)) (nook world sp))]
      (when (and (not= n (intent/feet world))
                 (every? (fn [e] (unseen-from? world n (hunt/cell (:entity/pos e)))) blazes))
        {:intent/kind :walk
         :intent/target n
         :intent/for :cover
         :intent/max-nodes corridor-nodes}))))

(defn hide
  "The cover walk from every watcher, and from the remembered spawner if within sight: none watching,
   or no cell out of their sight."
  [world]
  (let [sp (nearest-spawner world)
        sp-threat? (and sp
                        (<= (physics/distance (physics/centre (intent/feet world))
                                              (physics/centre sp))
                            cover-range)
                        (top-in-sight? world sp))
        threats (concat (vals (watchers world)) (when sp-threat? [(update sp 1 inc)]))]
    (or (nook-cover world sp threats) (cover world threats))))

(def ambush-ms 45000)      ; ms an ambush holds a cover for the swarm to come to the bot
(def ambush-cooldown 180000) ; ms after an ambush ended before the next: a swarm that does not come is gone to, as before

(defn swarm
  "{eid entity} of the blazes alive within cover-range of the feet, in sight or not."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (= :blaze (entity/kind e))
                         (not (entity/dead? e))
                         (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e)) cover-range))))
          (:world/entities world))))

(defn ambush
  "An :ambush of the cell the feet stand in, or nil: volley blazes or more about (swarm), none of
   them with the bot in sight (not in-open?), the bot not hurt, and no ambush ended within
   ambush-cooldown. A blaze that loses sight of its target flies to where it last saw it, so the
   swarm comes to the bot one at a time, and each one that gets within at-hand is fought. Sim R1/1
   (probe 40761589784, TRACE) left its cover for the spawner each time it was healed, was shot
   from 18 to 4 health in the open, and went back: twenty-five minutes, sixteen foods, no kill."
  [world]
  (let [last-end (:intention/at (memory/last-done world :ambush))]
    (when (and (>= (count (swarm world)) volley)
               (not (in-open? world))
               (not (hurt? world))
               (not (:player/on-fire? world))
               (or (nil? last-end) (> (- (:time/now world 0) last-end) ambush-cooldown)))
      {:intent/kind :ambush})))

(defn unhealing
  "The food trip when the bot cannot heal (heals? false), else nil: where a rest would hold for
   ever, the way out."
  [world]
  (when-not (heals? world) (food-trip world)))

(def rest-intent {:intent/kind :rest})

(def rest-churn 3)       ; rests done within rest-churn-ms after which a rest is not chosen again
(def rest-churn-ms 2000)

(defn rest-churning?
  "Have rest-churn rests been done within rest-churn-ms? A rest that ends in the tick it begins
   and is chosen again is no rest: blaze-race-f geared run 1 began :rest five times in 0.2 s at
   6.01 min, in the blazes' sight, and was dead at 6.04."
  [world]
  (let [now (:time/now world 0)]
    (>= (count (filter #(<= (- now (:intention/at %)) rest-churn-ms)
                       (memory/newest-dones world :rest rest-churn)))
        rest-churn)))

(defn rest-here
  "Where a bot that needs a rest takes it: nil when rests are churning; a walk into the nook
   (nook, a cell out of the room, in the tunnel it dug) when there is one it is not in, else a
   rest where it stands. Resting in the room is taking the next blaze's fireball: blaze-race-f
   geared run 1 rested at the room's mouth to 10.5."
  [world]
  (cond
    (rest-churning? world) nil
    :else
    (let [sp (nearest-spawner world)
          n (and sp (nook world sp))]
      (if (and n (not= n (intent/feet world)))
        {:intent/kind :walk :intent/target n :intent/for :cover :intent/max-nodes corridor-nodes}
        rest-intent))))

(defn void-hold?
  "Is the retreat from a watcher a step off a lip (combat/void-retreat?): that rest stays even
   when it cannot heal. test.yml 40624888901: treating the open lip as no rest sent the search
   off the lip."
  [world]
  (let [[_ e] (watcher world)] (boolean (and e (combat/void-retreat? world e)))))

(defn healing
  "What a hurt bot does: out of the watching blazes' sight (hide); else the meal; else back away
   from whoever last struck (combat/striker), and from the nearest watcher, unless that line
   steps off a drop no landing survives (combat/void-retreat?); else a :rest on that lip. Else,
   when the rest cannot heal, food (provisions: a drop, a craft, a hoglin only at or above
   retreat-health). Else a :rest when it can heal and the bot is not in the open, else nil: the
   rods go on, and no fight is chosen under retreat-health. Cover before the meal where there
   is cover: a meal takes a second and a half standing still, two hits from a burst, and gym
   blazefort-k run 3 (run 38913023560) ate at 9.4 by its spawner and was dead before the food
   came. The meal before the back-off where there is none: gym blaze-7 run 1 (run 38891910129)
   backed away three times round a room seven square with the food at 17, where no back-off gets
   away, and burned to death without eating. The rest before a back-off off a lip: issue #514
   (sim R3-5-1) jumped the nether brick one cell north and fell 31 into lava, and a footing
   found down there is not a survival. Never a back-off from a watcher already back-off-to
   (8 blocks) away: it ends in the tick it begins and is chosen again, every 50 ms (gym
   blaze-fortress-race run 3, `:back-off 485` a tick, on fire). The rest is never chosen while a strike is fresh and the
   way away from that attacker has a landing: blaze-wall-b (plan 40624888909) blaze-fortress run
   1 rested at 8 health while two zombified piglins hit it. test.yml 40624888901: a watched void
   retreat is still a rest, in the open or not, or the act searches. Probe 40634461444: a rest
   at 9.3 with food 17 cannot heal, and holding it is not a plan. With provide? false (the
   per-tick under-fire? asks so) the food is not looked for: provisions scans a 33 by 33 patch
   and routes, and asked every tick it made a replay of sim R2/12 run past the 60-minute limit
   (probe 40640227015) where 21cec97f3 took six."
  ([world] (healing world true))
  ([world provide?]
   (let [[weid e] (watcher world)
         [aid ae] (combat/striker world)
         away (hide world)
         food (delay (when (and provide? (not (heals? world))) (provisions world)))]
     (cond away away
           (meal world) (meal world)
           (and aid (not (combat/void-retreat? world ae)))
           {:intent/kind :back-off :intent/target aid}
           ;; a watched void retreat is a rest on the lip, but not a rest in the line of fire: the
           ;; cover first (a block laid, a pillar, a nook), the rest only when cover has nothing
           ;; (sim fortress probe 41215805587: :rest at 14.6 hp with 2 to 5 blazes 7 off, dead)
           (and e (combat/void-retreat? world e))
           (let [c (combat/cover world e)] (if (:intent/kind c) c rest-intent))
           (and weid (< (combat/horizontal world e) combat/back-off-to))
           {:intent/kind :back-off :intent/target weid}
           @food @food
           (and (empty? (watchers world)) (unhealing world)) (unhealing world)
           (in-open? world) nil
           (and (heals? world) (not (:player/on-fire? world))) (rest-here world)
           :else nil))))

(defn crowded?
  "Is the bot outnumbered? (a volley of blazes watching) with the health under rested and no
   blaze at hand? Then the cover comes before the swing at the one coming: sim R1/1 (probe
   40751212155) was at 7.2 health six seconds after a fight begun at 15.0 with three blazes about.
   Never with one at hand (within at-hand): it follows into any cover and hits in melee, and gym
   blaze-fire-b run 3 (run 39136853644) died from 14 health in four covers without a swing; sim
   three-of-the-models-blazes-at-hand-the-nearest-is-killed holds that."
  [world]
  (and (outnumbered? world) (< (:player/health world 20.0) rested) (nil? (at-hand-blaze world))))

(defn wary?
  "Is the health under engage-health: a meal then comes before the next fight, and cover and a rest
   when there is no meal. Gym blaze-7b run 2 (run 38893157426) fought on at 12.7 with the food at
   17 and a blaze's volley took it to nothing in two seconds, under combat/retreat-health only on
   the way down; blaze-race-f (real, 6e703362a) geared run 1 rested to 10.5, was walked out and
   began :fight 524 at 10.5, and was dead at 6.04 min."
  [world]
  (< (:player/health world 20.0) engage-health))

(defn warily
  "What a wary bot does before the next fight: out of the watching blazes' sight (hide); else
   the meal; else back away from whoever last struck, unless that line is a void; else food,
   when the rest cannot heal (provisions); else, when nothing has struck and not in the open
   and the rest can heal, a :rest until rested; else nil, and it fights on. A rest that cannot
   heal is not chosen. Gym blazefort-k run 3 (run 38913023560) fought on at 11.5, watched by
   the spawner's burst, and was under 10 a second later. blaze-wall-b run 1 chose :rest at 13
   health, over eat-under and not yet hurt, while the piglins that a blaze swing had hit were
   hitting back. provide? is as for healing."
  ([world] (warily world true))
  ([world provide?]
   (let [away (hide world)
         [aid ae] (combat/striker world)
         food (delay (when (and provide? (not (heals? world))) (provisions world)))]
     (cond away away
           (meal world) (meal world)
           (and aid (not (combat/void-retreat? world ae)))
           {:intent/kind :back-off :intent/target aid}
           @food @food
           (and (empty? (watchers world)) (unhealing world)) (unhealing world)
           (and (heals? world)
                (not (:player/on-fire? world))
                (nil? aid)
                (empty? (watchers world))
                (not (in-open? world)))
           (rest-here world)
           :else nil))))

(def fire-blocks #{:fire :soul_fire})
(def fire-out-reach 3) ; cells round the feet a burning bot looks for ground without fire in

(defn body-cells
  "Every cell the player's box (physics/aabb at its exact position, shrunk a hair) touches: the
   feet's and the head's, and a neighbour a fractional x or z reaches into."
  [world]
  (let [[x0 y0 z0 x1 y1 z1] (physics/aabb (:player/pos world))
        lo #(long (math/floor (+ (double %) 1e-4)))
        hi #(long (math/floor (- (double %) 1e-4)))]
    (for [x (range (lo x0) (inc (hi x1)))
          y (range (lo y0) (inc (hi y1)))
          z (range (lo z0) (inc (hi z1)))]
      [x y z])))

(defn burning-here?
  "Does a cell the body touches (body-cells) hold fire? A bot in a fire block is lit again as it
   burns out. Blaze-race-z9 job 3 took 39 in_fire hits at [2349.2 58.1 2289.3], the box reaching
   into the neighbour's fire at x 2348.9, with the feet's and the head's cells clear."
  [world]
  (boolean (some (fn [c]
                   (contains? fire-blocks
                              (some-> (terrain/block-at world c)
                                      blocks/name-of)))
                 (body-cells world))))

(defn punch-fire-here
  "A :dig of the fire block in the feet or the head cell, or nil: a punched fire goes out. What a
   bot sealed in its pocket with a fire at its feet does when no cell is clear to walk to
   (blaze-race-k race 1 stood 15 s in a fire it had sealed in: 30 in_fire hits)."
  [world]
  (let [[x y z] (intent/feet world)]
    (some (fn [c]
            (when (contains? fire-blocks
                             (some-> (terrain/block-at world c)
                                     blocks/name-of))
              {:intent/kind :dig :intent/target c}))
          [[x y z] [x (inc y) z]])))

(defn leave-fire
  "A walk to the nearest standable cell within fire-out-reach with no fire in it or over it, or
   nil: a burning bot in a fire block steps out of it before anything else (the flag :player/on-fire?
   clears seconds after it leaves the flames; there is no water here to put it out). One route of
   corridor-nodes."
  [world]
  (let [[x y z] (intent/feet world)
        clear? (fn [c]
                 (not-any? #(contains? fire-blocks
                                       (some-> (terrain/block-at world %)
                                               blocks/name-of))
                           [c (update c 1 inc)]))
        stands (for [dx (range (- fire-out-reach) (inc fire-out-reach))
                     dz (range (- fire-out-reach) (inc fire-out-reach))
                     dy [-1 0 1]
                     :let [c [(+ x dx) (+ y dy) (+ z dz)]]
                     :when (and (not= c [x y z]) (path/standable? world c) (clear? c))]
                 c)
        to (memory/nearest-of (:player/pos world) (double (inc fire-out-reach)) stands)
        walk {:intent/kind :walk
              :intent/target to
              :intent/for :fire-out
              :intent/max-nodes corridor-nodes}]
    (or (when (and to (intent/routable? world walk)) walk) (punch-fire-here world))))

(defn fires
  "The cells within fire-radius of spawner sp on the plane, from a block under its height to two
   over, that hold fire (fire or soul fire), none blacklisted (a dig of it failed): a fire lights
   its cells past 11, and a spawner's blaze spawns only at 11 or less (gym blaze-7a run 3, run
   38891910129, made none for three minutes once the floor by it burned)."
  [world [sx sy sz]]
  (let [bl (:plan/blacklist world #{})]
    (for [x (range (- sx fire-radius) (+ sx fire-radius 1))
          y (range (dec sy) (+ sy 3))
          z (range (- sz fire-radius) (+ sz fire-radius 1))
          :let [c [x y z]]
          :when (and (not (contains? bl c))
                     (contains? #{:fire :soul_fire}
                                (some-> (terrain/block-at world c)
                                        blocks/name-of)))]
      c)))

(defn put-out
  "The way to put out the fire by spawner sp nearest the feet, or nil when none burns: a :dig of
   it (a left click, the server breaks fire on the first) when its cell is within intent/reach of
   the eye, else a walk to it with a route, else nil (the fire is left)."
  [world sp]
  (when-let [c (memory/nearest-of (physics/centre (intent/feet world))
                                  Double/POSITIVE_INFINITY
                                  (fires world sp))]
    (let [walk {:intent/kind :walk :intent/target c :intent/for :fire}]
      (cond (<= (physics/distance (game/eye world) (physics/centre c)) intent/reach)
            {:intent/kind :dig :intent/target c}
            (intent/routable? world walk) walk
            :else nil))))

(def stalled-looked 40) ; the newest done tunnels asked where they ended: a stalled target stays so for some minutes of legs, not four

(def stall-memory-ms 600000) ; ms a stalled stand is remembered: the stalls were of one approach, then it is tried again

(defn stalled
  "The set of targets of which the two newest done :tunnel-to facts (of the stalled-looked newest)
   ended in the same cell (:intention/ended-at): a tunnel begun where the last one ended, ended
   there again. R1/1 (probe 40640225673) tunnelled to the same stand from the same place until the
   window closed, each one :done at the step cap. Not a tunnel's one done fact against the feet:
   the decision after a done is made where it ended, so that excluded every target the moment its
   tunnel ended, and the two stands beside a spawner were taken by turns, each tunnel a new one
   with a new trail (blaze-race-d job 5, 9e111c23b, story-brd5: ids 2 to 5 alternated [2209 60
   1794] and [2210 60 1793], each ended-at lower down the column). Remembered over the newest
   stalled-looked: with four, a search leg between them pushed a stalled stand out of the window
   and it was chosen again, the three stands taken by turns for 35 minutes (blaze-race-w job 5,
   41161244206, fd5ae5a: 89 legs within a 12-block box)."
  [world]
  (->> (memory/newest-dones world :tunnel-to stalled-looked)
       (filter #(< (- (:time/now world 0) (:intention/at %)) stall-memory-ms))
       (group-by :intention/target)
       (keep (fn [[target [latest before]]]
               (when (and before
                          (some? (:intention/ended-at latest))
                          (= (:intention/ended-at latest) (:intention/ended-at before)))
                 target)))
       set))

(defn site-stalled
  "stalled, with the spawner and every cell beside it added when stands-stalled of those cells
   have stalled: tunnels that went nowhere to two stands of one spawner went nowhere to the
   site, and the twelve cells (four sides at three levels) are not twelve tries. Blaze-race-z4
   job 1 (41194927191, landing 5) took the cells beside [2210 60 1794] by turns for 13 minutes
   (x 2209-2211, y 60-61, z 1793-1795), each needing two legs to be given up."
  [world sp]
  (let [dead (stalled world)
        around (cells-beside sp)]
    (if (>= (count (filter dead around)) stands-stalled) (into dead (cons sp around)) dead)))

(defn to-spawner
  "The way to spawner sp: a walk when it is walkable?, else a tunnel by the fortress's steps
   (which never open a cell beside lava nor the spawner itself), climbing first, to its stand
   (into it), or with no stand known to within spawner-reach of it. A target a tunnel already
   ended on this very cell for (stalled) is not chosen again: another stand, else the spawner
   itself, else nil, and the act goes on with the search."
  [world sp]
  (let [dead (site-stalled world sp)
        walk {:intent/kind :walk :intent/target sp}
        at (stand (update world :plan/blacklist (fnil into #{}) dead) sp)]
    (cond (walkable? world sp) walk
          at
          {:intent/kind :tunnel-to
           :intent/target at
           :intent/steps fortress/steps-per-tunnel
           :intent/reach 0.5
           :intent/climb 0
           :intent/bridge? true}
          (contains? dead sp) nil
          :else
          {:intent/kind :tunnel-to
           :intent/target sp
           :intent/steps fortress/steps-per-tunnel
           :intent/reach spawner-reach
           :intent/climb 0
           :intent/bridge? true})))

(defn visited-locations
  "The set of cell locations visited by completed walks or tunnels, plus the current feet."
  [world]
  (let [targets (d/q '[:find [?t ...] :where [?e :intention/kind ?k]
                       [(contains? #{:tunnel-to :walk} ?k)] [?e :intention/event :done]
                       [?e :intention/target ?t]]
                     (:world/facts world))]
    (conj (set targets) (intent/feet world))))

(defn unvisited-brick?
  "Is brick b further than fortress/reach from every visited location?"
  [visited b]
  (not-any? (fn [vp] (<= (physics/distance (physics/centre vp) (physics/centre b)) fortress/reach))
            visited))

(defn unvisited-fortress-bricks
  "The fortress bricks on record that have not been visited, not blacklisted, and not the target
   of a tunnel that ended on this cell (stalled)."
  [world]
  (let [bl (into (:plan/blacklist world #{}) (stalled world))
        visited (visited-locations world)]
    (->> (fortress/bricks world)
         (remove bl)
         (filter #(unvisited-brick? visited %)))))

(defn brick-tunnel
  "The fortress's own tunnel to brick b. The lava rules are the :tunnel-to executor's
   (fortress/safe?: never a cell beside lava, never the spawner), not a second tunneller."
  [b]
  {:intent/kind :tunnel-to
   :intent/target b
   :intent/steps fortress/steps-per-tunnel
   :intent/bridge? true})

(defn corridor-walk
  "One walk to the nearest unvisited standable fortress brick within corridor-reach, or the
   fortress's tunnel to that brick when the walk has no route, or nil when no such brick is.
   The cells are read once (path/cells). The route is one search of corridor-nodes, asked here
   so a stand with no route is never proposed as a walk. Asking routable? for each of 16 bricks
   at path/default-max-nodes (6000) cancelled probe 40624590128 at the 60-minute limit. A walk
   that fails :no-path blacklists its stand cell (y+1), and that stand is not proposed again.
   Probe 40634456698 (sim R2/12) reached the fortress and twelve such walks failed in 4.2 s
   (6,095,500 to 6,099,700 ms), and plan/max-unwalkable killed the plan seven blocks from the
   brick."
  [world]
  (let [feet (intent/feet world)
        at (path/cells world)
        bl (:plan/blacklist world #{})
        near (->> (unvisited-fortress-bricks world)
                  (remove #(contains? bl (update % 1 inc)))
                  (filter #(<= (physics/distance (physics/centre feet) (physics/centre %))
                               corridor-reach))
                  (sort-by #(physics/distance (physics/centre feet) (physics/centre %))))]
    (when-let [b (some (fn [brick] (when (path/standable? at (update brick 1 inc)) brick)) near)]
      (let [walk {:intent/kind :walk
                  :intent/target (update b 1 inc)
                  :intent/for :corridor
                  :intent/max-nodes corridor-nodes}]
        (if (intent/routable? world walk) walk (brick-tunnel b))))))

(defn fortress-bounds
  "The bounding box {:min-x :max-x :min-y :max-y :min-z :max-z} of fortress bricks on record,
   or nil when none."
  [world]
  (let [bs (fortress/bricks world)]
    (when (seq bs)
      {:min-x (reduce min (map first bs))
       :max-x (reduce max (map first bs))
       :min-y (reduce min (map second bs))
       :max-y (reduce max (map second bs))
       :min-z (reduce min (map #(nth % 2) bs))
       :max-z (reduce max (map #(nth % 2) bs))})))

(defn corridor-extension
  "When all known fortress bricks have been visited, extend the search outward along the fortress
   corridor axes from the bounds at corridor height."
  [world]
  (when-let [{:keys [min-x max-x min-z max-z]} (fortress-bounds world)]
    (let [feet (intent/feet world)
          y (fortress/explore-y feet)
          mid-x (long (/ (+ min-x max-x) 2))
          mid-z (long (/ (+ min-z max-z) 2))
          candidates [[(inc max-x) y mid-z] [(dec min-x) y mid-z] [mid-x y (inc max-z)]
                      [mid-x y (dec min-z)]]
          visited (visited-locations world)
          unvisited (remove #(contains? visited %) candidates)]
      (when-let [target (first (sort-by #(physics/distance (physics/centre feet) (physics/centre %))
                                        (or (seq unvisited) candidates)))]
        (brick-tunnel target)))))

(defn fortress-search
  "Search the fortress: walk along open corridors (corridor-walk) toward unexplored bricks, or
   tunnel/bridge to the nearest unvisited brick within corridor-reach when no walk reaches one,
   or extend corridors from the fortress bounds when all known bricks are visited. A brick
   further off is not a tunnel: sight-radius (512) sent the search hundreds of blocks out of the
   corridors (probe 40580596249, sim R2/12 s2, 310 blocks; probe 40602224561, 430 blocks north)."
  [world]
  (or (corridor-walk world)
      (when-let [b (memory/nearest-of (physics/centre (intent/feet world))
                                      corridor-reach
                                      (unvisited-fortress-bricks world))]
        (brick-tunnel b))
      (corridor-extension world)))

(defn explore-away
  "A walk of plan/explore-distance (48) toward the farthest cell of the trail the bot came by, away
   from the stuck spot (plan/explore-cell), for the fallback of the walk on to the spawner when every
   stand is stalled and the search has no heading: an intent, never a wait (a plan wait ends the
   plan after wait-ticks). Blaze-race-z4 landing 5 ended :nothing-to-do with time left."
  [world]
  {:intent/kind :walk :intent/target (plan/explore-cell world 0.0) :intent/for :explore})

(defn search
  "The search for a spawner: explore known fortress corridors and bricks first (fortress-search),
   else the next leg of the spiral search (fortress/exploring) when no fortress is known, or nil when no heading has a way to go."
  [world]
  (or (fortress-search world)
      (when-not (or (contains? (:plan/reached world) :fortress) (seq (fortress/bricks world)))
        (when-let [s (fortress/exploring world)]
          {:intent/kind :tunnel-to
           :intent/target (fortress/leg-target s)
           :intent/steps fortress/steps-per-tunnel
           :intent/search s}))))

;; ---------------------------------------------------------------- the pocket

(def hold-ms 30000) ; ms a hold in the pocket lasts before the planner is asked again

(defn reach-blaze
  "[eid entity] of the nearest blaze whose body is within combat/attack-reach of the eye, or nil:
   what a bot in its pocket strikes, and the only blaze it leaves the hold for. A blaze that comes
   to melee does so through the opening."
  [world]
  (let [eye (game/eye world)]
    (->> (blazes world)
         (filter (fn [[_ e]] (<= (physics/distance eye (combat/aim e)) combat/attack-reach)))
         (sort-by (fn [[_ e]] (physics/distance eye (combat/aim e))))
         first)))

(defn sealed?
  "Is the bot in its pocket with the opening sealed (pocket/sealed?)?"
  [world]
  (and (pocket/in? world) (pocket/sealed? world (:pocket/site world))))

(defn fightable-blaze
  "[eid entity] of the blaze fought before anything else: in the pocket (pocket/in?) the nearest
   within reach (reach-blaze), none when the opening is sealed (a blaze at the block is no target:
   the seal is opened first), elsewhere the one at hand (at-hand-blaze)."
  [world]
  (cond (sealed? world) nil
        (pocket/in? world) (reach-blaze world)
        :else (at-hand-blaze world)))

(defn pocket-ok?
  "The predicate of a pocket cell for the spawner at cell sp (nil: any cell): outside the volume
   its blazes spawn in (in-spawn-volume?), within its wake (awake-from?), and, when see? (the
   camp's, so the blazes come), with a clear line to it (sees-spawner?). A refuge asks no line
   (see? false): the nearest pocket is the one to get into, sighted or not."
  [world sp see?]
  (fn [c]
    (and (not (plan/avoiding? world c))
         (not (contains? (:pocket/failed world #{}) c))
         (or (nil? sp)
             (and (not (in-spawn-volume? sp c))
                  (awake-from? sp c)
                  (or (not see?) (sees-spawner? world sp c)))))))

(defn pocket-go
  "A :pocket intent for the nearest site (pocket/current, with the line to the spawner asked when
   see?), or nil when the feet are in the pocket already or none is found: a pocket that exists
   is walked into, a wall is dug into."
  [world sp see?]
  (when-not (pocket/in? world)
    (when-let [site (pocket/current world (intent/feet world) (pocket-ok? world sp see?))]
      {:intent/kind :pocket :intent/site site})))

(defn wakes-spawner?
  "Is the eye within spawner-wake of spawner sp (nil: no)? A pocket farther off than that is a
   refuge, not a camp: blaze-race-u job 5 sealed itself in 13 s after landing, far from any
   spawner, and its camp failed :no-blaze three times, which ends the plan."
  [world sp]
  (boolean (and sp (<= (physics/distance (game/eye world) (physics/centre sp)) spawner-wake))))

(def pocket-approach 14.0) ; blocks from the spawner within which a pocket is made ready before the tunnel goes on: close, so the tunnel's own steps do not break it (24 dug a pocket per tunnel step)
(def prepare-after-pocket-ms 120000) ; ms after a pocket was entered before another is made ahead
(def prepare-cooldown-ms 300000) ; ms after a pocket was begun ahead before another is: blaze-race-r jobs 1 and 4 dug one a step, ten of them, 250 netherrack, and wore the pickaxe out
(def approach-nodes 150)   ; cells the search for it visits at most when asked every tick (interrupts?)

(def seen-by-at-most 2) ; blazes a pocket's mouth may face: one draws a blaze to fight at the wall, a room of them is a volley

(defn count-blazes-seen
  "How many blazes alive within cover-range of cell c are in the sight of the eye over it
   (unseen-from?)? A blaze goes for a player it sees, so a pocket none of them sees draws none:
   blaze-race-w jobs 1, 2, 3 and 6 camped 30 minutes at [2338 60 2291], 12.8 from the spawner
   with 7 to 10 blazes hovering at it, the nearest 9.4 off and 4.6 under the eye for the whole
   time, 0 shots."
  [world c]
  (count (filter (fn [e]
                   (and (= :blaze (entity/kind e))
                        (not (entity/dead? e))
                        (<= (physics/distance (physics/centre c) (:entity/pos e)) cover-range)
                        (not (unseen-from? world c (hunt/cell (:entity/pos e))))))
                 (vals (:world/entities world)))))

(defn sees-a-blaze?
  "Does cell c face one blaze, or at most seen-by-at-most: the mouth toward one that closes and is
   fought at the wall, the others blind to the bot?"
  [world c]
  (<= 1 (count-blazes-seen world c) seen-by-at-most))

(def advance-step 4.0) ; blocks nearer the spawner an advanced pocket is at least
(def advance-wait-ms 120000) ; ms in a pocket before another nearer the spawner is made

(defn advance-intent
  "A :pocket intent for a pocket at least advance-step nearer the spawner sp than the feet, or nil:
   the bot in a pocket that no blaze comes to (idle?) for advance-wait-ms, a site found within
   approach-nodes. Blaze-race-u jobs 3 and 6 camped 45 s at [2338 60 2291], 11 to 13 blocks from six
   hovering blazes, then crossed the room to the spawner in the open: nearest blaze 8.4, 4.1, 1.7,
   20 hp to 0.7 in 8 s, burning 3 of the 7 hits. The blazes hover where they have no line to the
   bot (blaze-race-c: 4.9 hours, 0 attacks), so the pocket moves nearer a few blocks at a time, each
   one a refuge to heal in and a mouth for them to come to."
  [world sp]
  (let [feet (intent/feet world)
        here (physics/distance (physics/centre feet) (physics/centre sp))
        entered (:intention/at (memory/last-done world :pocket))]
    (when (and sp
               (or (nil? entered) (> (- (:time/now world 0) entered) advance-wait-ms))
               (> here advance-step))
      (when-let [site (pocket/site-near world
                                        feet
                                        (let [ok? (pocket-ok? world sp false)]
                                          (fn [c]
                                            (and (ok? c)
                                                 (not= c feet)
                                                 (or (<= (physics/distance (physics/centre c)
                                                                           (physics/centre sp))
                                                         (- here advance-step))
                                                     (sees-a-blaze? world c)))))
                                        approach-nodes)]
        {:intent/kind :pocket :intent/site site}))))
(defn bait-ok?
  "Is cell c a place to be seen from: in a corridor (exposure 2 or less), outside the spawner's
   volume, within its wake, and facing one or two blazes (sees-a-blaze?)?"
  [world sp c]
  (and (not (plan/avoiding? world c))
       (<= (exposure world c) 2)
       (not (in-spawn-volume? sp c))
       (awake-from? sp c)
       (sees-a-blaze? world c)))

(defn bait-here?
  "Are the feet in a bait cell (bait-ok?): the bot camps where it stands there, a corridor one
   wide with the wall at its back, and is neither walked on to the spawner nor back to the nook?"
  [world sp]
  (boolean (and sp (bait-ok? world sp (intent/feet world)))))

(defn bait-walk
  "A walk to the nearest bait cell (bait-ok?) by walking from the feet within nook-steps and
   nook-nodes, or nil. Blaze-race-y jobs 1, 2, 3 and 6 camped 39 to 48 minutes in a pocket that
   sees the spawner's top and no blaze, 7 to 10 hovering 9 blocks off and none shot, and no pocket
   nearer was found (advance-intent): the corridor to the room is where one or two see the bot,
   come to its mouth one at a time, and are fought with the wall at its back."
  [world sp]
  (let [at (path/cells world)
        start (intent/feet world)
        ok? (fn [c] (and (not= c start) (bait-ok? world sp c)))]
    (loop [frontier [start]
           seen #{start}
           depth 0
           visited 0]
      (let [hit (some #(when (ok? %) %) frontier)]
        (cond
          hit
          {:intent/kind :walk :intent/target hit :intent/for :nook :intent/max-nodes corridor-nodes}
          (or (empty? frontier) (>= depth nook-steps) (>= visited nook-nodes)) nil
          :else
          (let [next (into []
                           (comp (mapcat (fn [[x y z]]
                                           (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
                                                 dy [0 -1 1]]
                                             [(+ x dx) (+ y dy) (+ z dz)])))
                                 (remove seen)
                                 (filter #(path/standable? at %))
                                 (distinct))
                           frontier)]
            (recur next (into seen next) (inc depth) (+ visited (count next)))))))))

(def press-ms 8000)        ; ms after a hit a blaze in line but out of reach still presses the bot

(defn prepare-intent
  "A :pocket intent that makes a pocket ready ahead (:intent/prepare?: dug, not entered), or nil:
   a spawner within pocket-approach, the bot not in a pocket and none held that is valid?, and a
   site found within approach-nodes. The tunnel's last blocks are opened only after it: the first
   volley came at the tunnel's end in 6 of blaze-race-j's 10 runs (+150 s, 4 blazes within 8).
   Never a pocket toward the spawner, which is the tunnel's own next blocks: blaze-race-k dug one
   ahead, the tunnel dug through it and the next was dug ahead, a pocket and a tunnel step every
   3 s (geared 4: 85 s lost)."
  [world sp]
  (when (and sp
             (not (pocket/in? world))
             (nil? (:sortie/since world))
             ;; not within 2 minutes of a pocket entered: the sortie's first dig opens a wall of the held
             ;; pocket (valid? false) one tick before steer notes :sortie/since, and a pocket ahead made
             ;; then cut the leg after 0.7 s, every minute (blaze-race-z9 jobs 1 and 6: 17:15:42,
             ;; 17:16:44, 17:19:33 ..., :tunnel-to -> :pocket -> :camp)
             (let [d (memory/last-done world :pocket)]
               (or (nil? d) (> (- (:time/now world 0) (:intention/at d)) prepare-after-pocket-ms)))
             (nil? (pocket/held-valid world))
             (let [at (:pocket/prepared-at world)]
               (or (nil? at) (> (- (:time/now world 0) at) prepare-cooldown-ms)))
             (<= (physics/distance (physics/centre (intent/feet world)) (physics/centre sp))
                 pocket-approach))
    (when-let [site (pocket/current
                     world
                     (intent/feet world)
                     (let [ok? (pocket-ok? world sp false)
                           here (physics/distance (physics/centre (intent/feet world))
                                                  (physics/centre sp))]
                       (fn [c]
                         (and (ok? c)
                              (>= (physics/distance (physics/centre c) (physics/centre sp))
                                  (- here 1.0)))))
                     approach-nodes)]
      {:intent/kind :pocket :intent/site site :intent/prepare? true})))

(defn pressed?
  "Does a blaze in line press the bot out of reach: the bot burns, or blazes watch it and none is
   in reach and it was struck within press-ms? Blaze-race-j race 3 held 18 s with the nearest at
   4.0 against a reach of 3.2, race 1 37 s with the nearest at 5.4: fireballs through the
   opening."
  [world]
  (boolean (or (:player/on-fire? world)
               (and (seq (watchers world))
                    (nil? (reach-blaze world))
                    (<= (- (:time/now world 0) (:player/struck-at world -1000000)) press-ms)))))

(def full-health 20.0) ; the health a hold in the pocket stays until when a rest can heal
(def low-health 12.0)  ; under it the bot goes to its pocket to heal, whoever is in sight
(def reignite-ms 30000) ; a second ignition within this of the first sends it too

(def unseal-health 12.0) ; the health a hold stays until when a rest cannot heal: low-health, the line under which a blaze in reach is sealed out. 13 held blaze-race-x job 4 at 12.2 for four minutes with its food gone, in the spawner room, until a fireball and the burn finished it

(defn hold-to
  "The health a hold in the pocket stays until: full-health when a rest can heal (heals?), else
   unseal-health, low-health (the line under which a blaze in reach is sealed out). It was
   combat/retreat-health, 10, under low-health, 12: blaze-race-p job 4 at 22:42:01 unsealed at
   10.5 and sealed again at once, :unseal and :seal flipping every 0.6 s, four times, and died.
   A human banks health between fights; blaze-race-k's ten fought until they died."
  [world]
  (if (heals? world) full-health unseal-health))

(defn reignited?
  "Did the bot catch fire twice within reignite-ms (:player/ignitions)?"
  [world]
  (let [[a b] (take-last 2 (:player/ignitions world))]
    (boolean (and a b (<= (- b a) reignite-ms)))))

(declare near-rod)

(defn hold-intent
  "What a bot in its pocket does, or nil: SEALED, while it burns or is under hold-to, eat when the
   food is under what heals (meal) else a :pocket-hold, until the fire is out, the health is up and the
   last hit older than press-ms, then an :unseal; OPEN, a blaze in reach is struck (nil here) unless
   the health is under low-health (then sealed out: it hits for 3.6 a second; when the seal cannot
   be laid or is marked unsealable, combat/cover: a meal out of reach, a block laid toward it, a
   pillar, a nook dug; never a fight under danger-health, #577, and a plain back-off only when
   cover has nothing: it flipped with the seal every 0.2 s, blaze-race-n jobs 2, 4 and 6),
   while it is pressed? and can seal a :seal (a block in the opening, at the feet and the head,
   over the head when it has no roof), else while it burns or is under hold-to a meal or a :pocket-hold
   in the open (no filler held). Blaze-race-j race 3 and race 1 held open and died in it."
  [world]
  (when (pocket/in? world)
    (let [site (:pocket/site world)
          ;; a bot that cannot heal (heals? false) gains nothing by waiting: hp cannot rise, so the hold
          ;; is for the fire only (blaze-race-z6 landing 2 held 96 minutes at hp 6.2, food 17, none held)
          need? (or (:player/on-fire? world)
                    (and (heals? world) (< (:player/health world 20.0) (hold-to world))))
          struck? (<= (- (:time/now world 0) (:player/struck-at world -1000000)) press-ms)]
      (cond (pocket/sealed? world site)
            (if (and (or need? struck?)
                     (not (and (near-rod world) (not (:player/on-fire? world)) (not struck?))))
              (or (meal world) {:intent/kind :pocket-hold :intent/heal-to (hold-to world)})
              ;; sealed, cannot heal, under low-health: opened, the next blaze in reach seals it again
              ;; (the flip of blaze-race-p job 4), so it stays shut and the row goes on without a hold
              (when (or (heals? world) (>= (:player/health world 20.0) low-health))
                {:intent/kind :unseal}))
            (and (reach-blaze world) (>= (:player/health world 20.0) low-health)) nil
            (and (or (pressed? world) (reach-blaze world)) (pocket/can-seal? world site))
            {:intent/kind :seal}
            (reach-blaze world)
            (let [[_ e] (reach-blaze world) c (combat/cover world e)] (when (:intent/kind c) c))
            (and (pressed? world)
                 (watcher world)
                 (:intent/kind (combat/cover world (second (watcher world)))))
            (combat/cover world (second (watcher world)))
            (and (near-rod world) (not (:player/on-fire? world)) (not (pressed? world))) nil
            (and (pressed? world) (hide world)) (hide world)
            need? (or (meal world) {:intent/kind :pocket-hold :intent/heal-to (hold-to world)})))))

(defn refuge-needed?
  "Is a pocket wanted now: the bot burns or caught fire twice within reignite-ms (reignited?), is
   under low-health with a blaze about (swarm), a volley of blazes watches it (outnumbered?), or it
   is hurt (under retreat-health) with blazes watching? Not merely under engage-health: the pocket
   and a rod to pick up flipped every 0.35 s for 13 s (blaze-race-p job 4, 22:41:40, 37 times) and
   five rods lay till they despawned. Leave the fight to heal, as a human does."
  [world]
  (boolean (or (:player/on-fire? world)
               (reignited? world)
               (and (< (:player/health world 20.0) low-health) (seq (swarm world)))
               (outnumbered? world)
               (and (seq (watchers world)) (hurt? world)))))

(defn here-intent
  "A :pocket intent for the cell the feet stand in (pocket/here-site), sealed in place by the
   :seal that follows, or nil when it is not standable: the refuge when no pocket is to be had."
  [world]
  (when-let [site (pocket/here-site world)] {:intent/kind :pocket :intent/site site}))

(defn burning-twice?
  "Is the bot alight or just was twice (reignited?): the fire emergency that seals it in where it
   stands when no pocket is to be had (here-intent)."
  [world]
  (boolean (or (:player/on-fire? world) (reignited? world))))

(def refuge-kinds
  "Intents that are the answer to a refuge: none of them is cut for one."
  #{:pocket :seal :unseal :pocket-hold :eat :fight :back-off :rest :dodge})

(defn dodge-intent
  "A :dodge intent out of the line of the fireballs in flight toward the bot (dodge/intent-for), or
   nil: none when it stands sealed in its pocket (a block stops them), is under low-health or alight
   (the refuge comes first: blaze-race-m race 4 at 17:30:30 alternated :fight, :dodge, :pocket and
   :seal in six seconds, a dodge cutting the seal each time, and died), or no cell is clear in time.
   Blaze-race-k and -l: 3.5 to 4 fireball hits a kill, each lighting the bot, half of all damage the
   burning after; the lines are known the tick a fireball spawns."
  [world]
  (when (and (not (sealed? world))
             (>= (:player/health world 20.0) low-health)
             (not (:player/on-fire? world)))
    (dodge/intent-for world)))

(def no-dodge-kinds
  "Intents a dodge is never cut for: the refuge's own and a meal."
  #{:pocket :seal :unseal :pocket-hold :eat :dodge})

(def grab-range 3.0) ; blocks on the plane within which a rod lying is picked up before the next swing

(def long-shot-place-ms 3000) ; ms a long-shot wall's :place may run before it is cut (:intent/until)
(def long-shot-ms 4000) ; ms after a hit within which a shooter out of reach is answered with a wall, not carried on under
(def shooter-range 64.0) ; blocks within which a blaze in sight of the bot is a shooter (they fire from 48)

(defn shooter
  "The nearest blaze alive within shooter-range that has the bot in sight (combat/seen?), or nil:
   what shoots it from beyond cover-range. Blaze-race-x job 5 and y job 5 (and r, v job 5 before
   them) land at one place every round, tunnel at y 72 to 67 along a ledge in sight of the fortress
   across the chasm, and are fireballed by blazes at 24 to 48 blocks (shooters at [2185 57 1731]
   and [2191 50 1740], 39 to 49 off): 47 to 53 hits in 4 minutes, 0 attacks, no blaze in reach."
  [world]
  (let [pos (:player/pos world)]
    (->> (vals (:world/entities world))
         (filter (fn [e]
                   (and (= :blaze (entity/kind e))
                        (not (entity/dead? e))
                        (<= (physics/distance pos (:entity/pos e)) shooter-range)
                        (combat/seen? world e))))
         (sort-by #(physics/distance pos (:entity/pos %)))
         first)))

(defn nearby-blazes
  "{eid entity} of the blazes alive within r blocks of the bot."
  [world r]
  (let [pos (:player/pos world)]
    (into {}
          (filter (fn [[_ e]]
                    (and (= :blaze (entity/kind e))
                         (not (entity/dead? e))
                         (<= (physics/distance pos (:entity/pos e)) r))))
          (:world/entities world))))

(defn long-shot-cover
  "A :place of a filler block in the wall cell toward the shooter (combat/wall-cell: the feet's,
   then the eye's), or nil: the bot outside a pocket, hit within long-shot-ms, no blaze within
   at-hand, a shooter in sight (shooter), a cell to lay (not one that just failed) and a filler
   held. A fireball stops at the first block on its line, so two blocks beside the bot toward the
   shooter hide it, where a rest (combat/cover's wait for a threat past back-off-to) stood and
   burned."
  [world]
  (when (and (not (pocket/in? world))
             ;; a long-shot place that has run past its :intent/until is not planned again at once: the row
             ;; planning the same place would make plan/same-choice? keep it running (walk-restart's
             ;; fix/577-same-choice ignores :until), and the deadline would never cut it
             (not (let [i (:plan/intent world)]
                    (and (= :place (:intent/kind i))
                         (:intent/until i)
                         (> (:time/now world 0) (:intent/until i)))))
             (<= (- (:time/now world 0) (:player/struck-at world -1000000)) long-shot-ms)
             (nil? (at-hand-blaze world))
             ;; one within 8 blocks is fought, not walled off: blaze-race-z8 job 6 flipped :fight,
             ;; :place x4, :dodge, :pocket, :pillar, :dig and :seal against two blazes at 0.7 to 3 blocks
             ;; and sent one attack in 9 s
             (empty? (nearby-blazes world 8.0)))
    (when-let [e (shooter world)]
      (let [cell (combat/wall-cell world e)
            ;; over open air the cell has nothing to click: the block under it, which has, goes first
            ;; (build/foundation), unless that is in the body
            target (when cell
                     (let [f (build/foundation world cell)]
                       (when (and f (not (place/inside-player? world f))) f)))
            filler (stairs/filler world combat/wall-fill)]
        (when (and target filler (not (combat/wall-failed? world target)))
          {:intent/kind :place
           :intent/item filler
           :intent/cell target
           :intent/until (+ (:time/now world 0) long-shot-place-ms)})))))

(def rod-grab-health 10.0) ; health at which a rod a step or two away is still picked up: a second's walk, not a fight (#577 danger-health)

(defn near-rod
  "The rod entity lying within grab-range of the feet (a step or two), the health at low-health or
   more, or nil, blazes watching or not. Blaze-race-m race 4 (22 min, 26 kills) held at most 4 of
   about 11 rods dropped: eight lay within 2 to 3 blocks of its camp [2347 60 2280] until they
   despawned at 300 s, 39 :collect-drops begun and each cut within a second by the next :fight
   (six to eight blazes hovering in reach), which also comes before a rod in the act's order."
  [world]
  (when (>= (:player/health world 20.0) rod-grab-health)
    (let [[_ e] (rod-lying world)
          p (:player/pos world)]
      (when (and e
                 (<= (physics/horizontal-distance p (:entity/pos e)) grab-range)
                 (<= (abs (- (second (:entity/pos e)) (second p))) 2.0))
        e))))

(defn rod-intent
  "The way to the rod entity e lying: a walk onto it when a :collect-drops cannot reach
   (drops/under-reach?), else the :collect-drops of its cell."
  [world e]
  (if (drops/under-reach? world e)
    (fetch-walk e)
    {:intent/kind :collect-drops
     :intent/item :blaze_rod
     :intent/target (hunt/cell (:entity/pos e))}))

(defn refuge-now
  "What the bot does for its refuge now, or nil: the hold, seal or unseal in the pocket (hold-intent),
   else out of it the nearest site (pocket-go) or the cell it stands in (here-intent)."
  [world sp]
  (when (refuge-needed? world)
    (if (pocket/in? world)
      (hold-intent world)
      (or (pocket-go world sp false) (when (burning-twice? world) (here-intent world))))))

(defmethod plan/done-by :rods-held? [world _] (>= (rods world) rods-wanted))

(defmethod combat/answers :blaze-rods [_] #{:blaze})

(defn under-fire?
  "Is the bot hurt (hurt?, or wary? when the answer to that is something to do: warily) with blazes
   watching, in an intent that is not the answer (a fight, a meal, a rest, a back-off, the walk
   to cover)? Sim snapshot R4-31-1 (issue #502): a tunnel of 32 steps went on while two blazes
   10 off, past the at-hand radius, shot the bot from 16.8 health to none in ten seconds, since
   nothing ends a running intent but itself and the planner is asked only then."
  [world i]
  (and (not (contains? #{:fight :eat :rest :back-off :pocket-hold :pocket :seal :unseal :dodge}
                       (:intent/kind i)))
       (not= :cover (:intent/for i))
       (seq (watchers world))
       (some? (cond (hurt? world) (healing world false)
                    (wary? world) (warily world false)))))

(def sortie-zone 16.0) ; blocks from the spawner within which a bot out of its pocket is on a sortie
(def sortie-line 14.0) ; health under which a sortie is over: back to the pocket to heal
(def sortie-fights 2) ; fights ended on a sortie after which it is over
(def sortie-ms 90000) ; ms a sortie may last
(def sortie-crowd 3) ; blazes within at-reach (8) of the bot beyond which a sortie is over: the room is on it
(def sortie-start-health 19.0) ; health a sortie is begun at
(def sortie-rest-ms 45000) ; ms in the pocket with no fight before another sortie

(defn in-sortie-zone?
  "Is the bot out of a pocket and within sortie-zone of the spawner sp (nil: no)?"
  [world sp]
  (boolean (and sp
                (not (pocket/in? world))
                (<= (physics/distance (game/eye world) (physics/centre sp)) sortie-zone))))

(defn fights-since
  "How many :fight intentions ended :done at or after ms t."
  [world t]
  (count (filter #(>= (:intention/at %) t) (memory/newest-dones world :fight 20))))

(defn sortie-over?
  "Is the sortie (:sortie/since, noted by steer when the bot left its pocket for the spawner's zone)
   over: sortie-fights fights ended since, or sortie-ms spent, or the health under sortie-line, or
   burning, or more than sortie-crowd blazes within 8 (the room is on the bot: blaze-race-z4
   landing 2 died among 13 to 14)? Then
   the bot goes back to the pocket behind it, heals and goes again. Blaze-race-z2 landing 2 killed 4
   blazes in 3.4 minutes in the room at [2346 57 2288] and stayed there until dead; landing 1 stood
   at the tunnel's end among 6 and 7 blazes and flipped dodge, pocket and walk until dead."
  [world]
  (let [since (:sortie/since world)]
    (boolean (and since
                  (or (>= (fights-since world since) sortie-fights)
                      (> (- (:time/now world 0) since) sortie-ms)
                      (< (:player/health world 20.0) sortie-line)
                      (:player/on-fire? world)
                      (> (count (nearby-blazes world 8.0)) sortie-crowd))))))

(defn sortie-due?
  "Is it time for a sortie: in the pocket, healed to sortie-start-health, not burning, and no fight
   nor pocket entered within sortie-rest-ms?"
  [world]
  (let [now (:time/now world 0)
        recent? (fn [k]
                  (when-let [d (memory/last-done world k)]
                    (< (- now (:intention/at d)) sortie-rest-ms)))]
    (boolean (and (pocket/in? world)
                  (>= (:player/health world 20.0) sortie-start-health)
                  (not (:player/on-fire? world))
                  (not (recent? :fight))
                  (not (recent? :pocket))))))

(declare rod-grab?)

(defmethod plan/steer :blaze-rods
  ;; notes where a sortie began (:sortie/since) when the bot leaves its pocket within sortie-zone of
  ;; the spawner, and forgets it in a pocket or out of the zone: sortie-over? reads it
  [world _]
  (let [sp (nearest-spawner world)
        world (if (rod-grab? world (:plan/intent world))
                (update world :rod-grab/since #(or % (:time/now world 0)))
                (dissoc world :rod-grab/since))]
    (cond (and (pocket/in? world) (pocket/unlayable? world (:pocket/cell (:pocket/site world))))
          ;; an unsealable pocket is no refuge: forgotten, and its cell remembered as failed
          (-> world
              (update :pocket/failed (fnil conj #{}) (:pocket/cell (:pocket/site world)))
              (dissoc :pocket/site :sortie/since))
          (pocket/in? world) (dissoc world :sortie/since)
          (and (in-sortie-zone? world sp) (nil? (:sortie/since world)))
          (assoc world :sortie/since (:time/now world 0))
          (and sp (not (in-sortie-zone? world sp))) (dissoc world :sortie/since)
          :else world)))

(defn rod-grab?
  "Is running intent i the pick-up of a rod lying within grab-range (near-rod)? A dodge or a meal
   does not cut it: blaze-race-x job 4 left rod 607 lying at [2349 60 2283] for 300 s with the bot
   2.2 blocks off at the nearest, :collect-drops cut by :dodge, :eat and :walk twenty times in
   0.5 s steps (04:03:53 to 04:05:14), three of its eight rods lost."
  [world i]
  (boolean (and (or (= :collect-drops (:intent/kind i)) (= :rod (:intent/for i)))
                (some? (near-rod world)))))

(def rod-grab-ms 2000) ; ms a rod pick-up may hold the refuge off: one step and the pick-up

(defn grab-over-refuge?
  "Does a rod lying within a step (near-rod) hold the refuge off: the bot not alight and its pick-up
   (:rod-grab/since, noted by steer) under rod-grab-ms old? Then the refuge wins: a grab is a second's
   walk, never a stand in the swarm (blaze-race-z9 job 4 lost 7 of 9 rods to the refuge/grab flip)."
  [world]
  (boolean (and (near-rod world)
                (not (:player/on-fire? world))
                (let [since (:rod-grab/since world)]
                  (or (nil? since) (< (- (:time/now world 0) since) rod-grab-ms))))))

(defn commit-fight
  "A :fight on the blaze at hand (at-hand-blaze) while the health is at low-health (12) or more,
   the line under which a pocket seals a blaze out and the retreat is one, or nil: committed for its whole duration, to the end (:intent/to-the-end?), yielding under
   retreat-health (:intent/yield?) so the retreat that follows is one. Blaze-race-z8 job 6 (landing 6):
   two blazes at 0.7 to 3.1 blocks and hp 20 to 3.6 in 9 s, one attack sent, because :fight, :place
   x4, :dodge, :pocket, :pillar, :dig and :seal took turns every 0.1 to 0.3 s: with a blaze in melee
   range no row held."
  [world]
  (when (and (>= (:player/health world 20.0) low-health)
             ;; the sortie's cap: more than sortie-crowd blazes within 8, or a volley watching, is the room on
             ;; the bot, not a fight to commit to the end (the spawner-room deaths of z4 and z5)
             (<= (count (nearby-blazes world 8.0)) sortie-crowd)
             (not (outnumbered? world))
             (not (burning-here? world))
             ;; a rod within a step is picked up before the next swing (near-rod, blaze-race-m race 4)
             (nil? (near-rod world)))
    (when-let [[eid] (at-hand-blaze world)]
      {:intent/kind :fight :intent/target eid :intent/to-the-end? true :intent/yield? true})))

(defn committed-fight?
  "Is running intent i a :fight with a blaze at hand and the health at low-health or more?"
  [world i]
  (and (= :fight (:intent/kind i)) (some? (commit-fight world))))

(defmethod plan/holds? :fight
  ;; a blaze-rods fight is not cut by another row (the default keeps a :defend row's fight
  ;; cuttable, as a fight ends by its own break-off); the row's own interrupts? commits it
  [i]
  (= :blaze-rods (:intent/goal i)))

(defmethod plan/interrupts? :blaze-rods
  ;; any running intent but a fight ends when a blaze is at hand (at-hand-blaze), and the act
  ;; fights it when healthy: gym blaze-fire-b run 3 (run 39136853644) tunnelled on with one hitting it in melee
  ;; from 20 health to 13.8, since nothing else ends a running intent; never interrupt cover or healing when hurt (#577);
  ;; and one hurt and watched (under-fire?) by blazes further off, whatever the intent but the answer to it
  [world _ i]
  (and (not (committed-fight? world i))
       (or ;; a fire block lit in the feet's cell or the head's while an intent runs (a pick-up of a rod
           ;; lying in it, a hold): the row's @out is asked only when none runs. Blaze-race-z8 job 3
           ;; stood 19 s at [2349.2 58.1 2289.3] in a fire with in_fire hits every 2 s on a :collect-drops
        (and (burning-here? world)
             (not= :fire-out (:intent/for i))
             (not= :dig (:intent/kind i))
             (some? (leave-fire world)))
        (and (not (contains? no-dodge-kinds (:intent/kind i)))
             (not (rod-grab? world i))
             (some? (dodge-intent world)))
        (and (= :tunnel-to (:intent/kind i)) (some? (prepare-intent world (nearest-spawner world))))
        (and (:intent/until i) (> (:time/now world 0) (:intent/until i)))
        (and (= :pocket-hold (:intent/kind i)) (= :seal (:intent/kind (hold-intent world))))
        (and (= :tunnel-to (:intent/kind i)) (some? (long-shot-cover world)))
        (and (not (contains? refuge-kinds (:intent/kind i)))
             (not= :fight (:intent/kind i))
             (not (rod-grab? world i))
             (sortie-over? world))
        (and (not (contains? refuge-kinds (:intent/kind i)))
             (not (and (rod-grab? world i) (grab-over-refuge? world)))
             (some? (refuge-now world (nearest-spawner world))))
        (and (not (hurt? world))
             (not= :fight (:intent/kind i))
             (not (and (= :cover (:intent/for i)) (crowded? world)))
             (not (and (= :collect-drops (:intent/kind i)) (some? (near-rod world))))
             (some? (fightable-blaze world)))
        (under-fire? world i))))

(defmethod plan/next-intent :blaze-rods
  ;; a tunnel to the spawner or on the search first makes the pickaxe it lacks from what is held
  ;; (the :fortress row's rule): sim race 39742401798 R4-6-2 (issue #504) tunnelled 25 blocks in
  ;; fifteen minutes by hand, each :no-pickaxe step ended the tunnel and the act asked again
  ;; then takes the table it placed along, as the :fortress row does: race lane 4 (run 40980342194)
  ;; s1 r15 made its pickaxe at a table, tunnelled on and left it, held 598 cobblestone and 13 sticks
  ;; with no table when the pickaxe broke, and dug 4,100 netherrack by hand at 2.05 s each (8 of 9
  ;; stuck bots dug by hand)
  [world goal table]
  (let [in-pocket? (pocket/in? world)
        sp (nearest-spawner world)
        ;; a camp in the pocket that no blaze comes to (idle?) moves to a pocket nearer the spawner
        ;; (advance-intent), never out across the room to it
        camping-in-pocket? (and in-pocket? (wakes-spawner? world sp))
        [heid] (fightable-blaze world)
        [beid] (when-not in-pocket? (quarry world))
        [_ lying] (when (collectable? world) (rod-lying world))
        heal (delay (when (hurt? world) (healing world)))
        wary (delay (when (and (not in-pocket?) (wary? world)) (warily world)))
        away (delay (when (and (not in-pocket?) (outnumbered? world)) (hide world)))
        held (delay (hold-intent world))
        commit (delay (commit-fight world))
        shot-cover (delay (long-shot-cover world))
        ;; not while a rod lies within a step and the bot is not alight: the pick-up is a second's walk
        ;; (blaze-race-z9 job 4: 7 of 9 rods lost, :collect-drops and :pocket alternating at hp 10 to 12)
        refuge
        (delay (when (and (not in-pocket?) (refuge-needed? world) (not (grab-over-refuge? world)))
                 (or (pocket-go world sp false) (when (burning-twice? world) (here-intent world)))))
        prepare (delay (prepare-intent world sp))
        ;; the stuck watchdog fired here with a hostile near (no explore walk planned): leave through the
        ;; search, never back to the pocket or the camp it dropped (blaze-race-z6: 12 of 12 firings undone
        ;; by pocket-go within 46 ms)
        leave (delay (when (and (plan/avoiding? world (intent/feet world))
                                (nil? (:plan/explore world)))
                       (or (search world) (explore-away world))))
        dodging (delay (dodge-intent world))
        room (delay (rod-room world))
        ;; a rod with no slot to be picked up into is not grabbed: the junk goes first (rod-room)
        grab (delay (when-let [e (near-rod world)] (when-not @room (rod-intent world e))))
        retreat (delay (when (and (not in-pocket?) (sortie-over? world))
                         (pocket-go world sp false)))
        waiting (delay (ambush world))
        stock (delay (when-not (hurt? world) (stocking world)))
        out (delay (when (burning-here? world) (leave-fire world)))
        fire (delay (when (and sp (at-spawner? world sp)) (put-out world sp)))
        table-along (delay (when (or (nil? sp) (not (at-spawner? world sp)))
                             (or
                              (intent/chain-next (:plan/last world) :take-table make/table-drops {})
                              (make/take-table-along world))))
        pick (delay (when (build/pickaxe-at-hand? world)
                      (make/decide world (assoc goal :goal/provides {:tag/pickaxes 1}) table)))]
    (cond
      (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
      @out @out
      @dodging @dodging
      @commit @commit
      @held @held
      @shot-cover @shot-cover
      @refuge @refuge
      @heal @heal
      @grab @grab
      @retreat @retreat
      (and (not (hurt? world)) heid (not (crowded? world)))
      {:intent/kind :fight :intent/target heid :intent/to-the-end? true :intent/yield? true}
      @wary @wary
      @away @away
      (window/reserve-toss world) (window/reserve-toss world)
      @room @room
      (and lying (drops/under-reach? world lying)) (fetch-walk lying)
      lying
      {:intent/kind :collect-drops
       :intent/item :blaze_rod
       :intent/target (hunt/cell (:entity/pos lying))}
      (and (not (hurt? world)) beid) {:intent/kind :fight :intent/target beid :intent/yield? true}
      @waiting @waiting
      @stock @stock
      @fire @fire
      (and (hurt? world) (not (heals? world)) (food-trip world)) (food-trip world)
      (and (or (nil? sp) (not (at-spawner? world sp))) @pick) @pick
      @table-along @table-along
      @leave @leave
      @prepare @prepare
      (and sp
           (not camping-in-pocket?)
           (not (at-spawner? world sp))
           (not (at-nook? world sp))
           (not (bait-here? world sp)))
      (or (to-spawner world sp) (search world) (explore-away world))
      (and sp
           (or camping-in-pocket? (at-spawner? world sp) (at-nook? world sp) (bait-here? world sp))
           (not (hurt? world))
           (not (:player/on-fire? world)))
      (or (when (and in-pocket? (sortie-due? world)) (to-spawner world sp))
          (when (and in-pocket? (idle? world)) (or (advance-intent world sp) (bait-walk world sp)))
          (when-not (idle? world) (pocket-go world sp true))
          (when-let [n (and (not in-pocket?)
                            (not (at-nook? world sp))
                            (not (bait-here? world sp))
                            (nook world sp))]
            {:intent/kind :walk
             :intent/target n
             :intent/for :nook
             :intent/max-nodes corridor-nodes})
          {:intent/kind :camp :intent/spawner sp})
      :else (or (search world) {:plan/wait :no-blaze}))))

;; ---------------------------------------------------------------- the :ambush intent

(defmethod intent/run :ambush
  ;; {:intent/kind :ambush}: stand still in cover until a blaze comes within at-hand (done, and
  ;; the act fights it), the swarm is thinner than a volley, the cell is seen (in-open?), the
  ;; health is hurt, or ambush-ms is spent (done, and ambush's cooldown lets the act go on)
  [world {:intent/keys [since]} _]
  (let [now (:time/now world)
        since (or since now)
        world (-> world
                  (assoc :player/controls {})
                  (intent/assoc-intent :intent/since since))]
    (cond (or (some? (at-hand-blaze world))
              (:player/on-fire? world)
              (< (count (swarm world)) volley)
              (in-open? world)
              (hurt? world)
              (> (- now since) ambush-ms))
          (intent/done world)
          :else world)))

;; ---------------------------------------------------------------- the :portal-home intent

(defmethod intent/run :portal-home
  ;; {:intent/kind :portal-home}: work the stand in the nearest remembered Nether portal block
  ;; through build/work-op (its child a walk to it, the await); done in the overworld, failed
  ;; :no-portal when none is on record
  [world i event]
  (let [p (home-portal world)]
    (cond (not= dimension/nether (dimension/of world))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (nil? p)
          (-> world
              (assoc :player/controls {})
              (intent/fail :no-portal))
          :else (build/work-op world i (home-op p) event))))

;; ---------------------------------------------------------------- the :camp intent

(defmethod intent/run :camp
  ;; {:intent/kind :camp :intent/spawner cell}: stand by the spawner until a blaze comes within
  ;; fight-radius that was not there when the camp began (:intent/blazes: one there then could not
  ;; be fought, or the act would have chosen it), a rod lies near, or the health falls under what
  ;; it was when the camp began (:intent/health: something shoots the bot, and the act must look
  ;; again; gym blazefort-k run 3, run 38913023560, camped on from 11.5 health to none in four
  ;; seconds, since nothing ends a running intent but itself) (done), failed :no-blaze after
  ;; camp-ms. A camp is an intent, not a planner wait: a wait fails the plan after
  ;; plan/wait-ticks (20 s), shorter than a natural spawner's delay. Its spawner is not its
  ;; :intent/target, so plan/fail-intent blacklists nothing for it; a camp that saw nothing
  ;; blacklists its spawner itself, so the act goes to another spawner or on the search
  ;; instead of camping the same silent one until the plan fails (issue #507: two camps in a
  ;; row at one spawner that made no blaze in two minutes, after two rests, failed the plan)
  [world {:intent/keys [since health spawner] seen :intent/blazes} _]
  (let [now (:time/now world)
        here (set (keys (blazes world)))
        seen (or seen here)
        since (or since now)
        health (or health (:player/health world 20.0))
        world
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/since since :intent/blazes seen :intent/health health))]
    (cond (or (seq (remove seen here)) (rod-lying world) (< (:player/health world 20.0) health))
          (intent/done world)
          (> (- now since) camp-ms)
          (intent/fail (cond-> world spawner (update :plan/blacklist (fnil conj #{}) spawner))
                       :no-blaze)
          :else world)))

;; ---------------------------------------------------------------- the :rest intent

(defmethod intent/run :rest
  ;; {:intent/kind :rest}: stand still out of sight until the health is back to rested, or the
  ;; rest cannot heal and this is not a watched void retreat (void-hold?: the lip stays), or a
  ;; blaze watches the bot that did not when the rest began (:intent/blazes), or the health falls
  ;; under what it was then (:intent/health: shot from somewhere unseen), or someone who has
  ;; struck is still there and the way away from them has a landing (combat/striker: a rest
  ;; beside an angry zombified piglin, blaze-wall-b run 1), or exposed in the open (done, and
  ;; the act looks again), failed :no-heal after rest-ms when food could heal.
  ;; A rest that cannot heal used to run until the window: probe 40634461444 rest 360, health
  ;; 9.3, food 17, from 72.78 min to the 330 min stop.
  ;; An intent, not a planner wait: the slow heal brings a point every four seconds, and a wait
  ;; fails the plan after plan/wait-ticks (20 s)
  [world {:intent/keys [since health] seen :intent/blazes} _]
  (let [now (:time/now world)
        here (set (keys (watchers world)))
        seen (or seen here)
        since (or since now)
        health (or health (:player/health world 20.0))
        now-health (:player/health world 20.0)
        open? (in-open? world)
        [_ struck] (combat/striker world)
        struck? (and struck (not (combat/void-retreat? world struck)))
        world
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/since since :intent/blazes seen :intent/health health))]
    (cond (or (>= now-health rested)
              (:player/on-fire? world)
              (and (not (heals? world)) (not (void-hold? world)))
              (< now-health health)
              (seq (remove seen here))
              struck?
              open?)
          (intent/done world)
          (and (> (- now since) rest-ms) (heals? world)) (intent/fail world :no-heal)
          :else world)))

;; ---------------------------------------------------------------- the :pocket-hold intent

(defmethod intent/run :pocket-hold
  ;; {:intent/kind :pocket-hold :intent/heal-to h}: stand still in the pocket, out of every line but the
  ;; opening's, until the fire is out and the health is at h (and, sealed, the last hit older than
  ;; press-ms: done), a blaze comes within reach of an open pocket (done: it is struck), the cell is
  ;; no pocket now or burns (done: the planner chooses), or hold-ms have passed (done: it is asked
  ;; again)
  [world {:intent/keys [since heal-to]} _]
  (let [now (:time/now world)
        since (or since now)
        world (-> world
                  (assoc :player/controls {})
                  (intent/assoc-intent :intent/since since))]
    (cond (or (and (not (:player/on-fire? world))
                   (>= (:player/health world 20.0) (or heal-to engage-health))
                   (or (not (sealed? world))
                       (> (- now (:player/struck-at world -1000000)) press-ms)))
              (and (some? (reach-blaze world))
                   (>= (:player/health world 20.0) low-health)
                   (not (sealed? world)))
              (not (pocket/in? world))
              (burning-here? world)
              (> (- now since) hold-ms))
          (intent/done world)
          :else world)))
