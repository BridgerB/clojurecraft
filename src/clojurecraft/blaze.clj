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
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
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
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]
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
(def stands-tried 2)                  ; stands beside one spawner whose tunnel ended :blocked before the spawner is given up
(def spawner-reach 2.0)                ; blocks from the feet cell's centre to the spawner's a tunnel to it ends within: in its room, at the blazes' floor
(def cover-range 16.0)                ; blazes this near that see the bot are the ones a hurt bot takes cover from (they shoot that far)
(def at-hand combat/defend-radius)    ; blocks within which a blaze is fought first and to the end: it follows the bot into any cover and round any retreat
(def eat-under 14.0)                  ; health under which a meal comes before the next fight, the food under the healing level: one volley takes 9
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
   a lava sea, and a collect of it would be done at once, the planner choosing it again."
  [world e]
  (and (not (drops/over-void? world e))
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

(defn nearest-spawner
  "The spawner on record in the bot's dimension nearest the eye, within spawner-radius and not
   blacklisted (no walk nor tunnel reached it) nor given-up?, or nil."
  [world]
  (let [bl (:plan/blacklist world #{})]
    (memory/nearest-of (game/eye world)
                       spawner-radius
                       (remove #(or (bl %) (given-up? world %))
                               (memory/positions-now world [spawner])))))

(defn level-with?
  "Are the feet within a block of spawner sp's height: its blazes' floor?"
  [world sp]
  (<= (abs (- (physics/y-of (intent/feet world)) (physics/y-of sp))) 1))

(defn walkable?
  "Can the bot walk to spawner sp: level-with? it (a route judges the plane only,
   intent/routable?) and a route to it."
  [world sp]
  (and (level-with? world sp) (intent/routable? world {:intent/kind :walk :intent/target sp})))

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
   floor, not in a tunnel beside its room) and its top-in-sight?. Gym blaze-fortress-b (run
   38893941350) tunnelled to within 6 of its spawner, four blocks over it in the wall, and
   camped there ten minutes: the blazes the spawner made in its room below were out of reach,
   every run; blaze-fortress-c (run 38895759229) run 3 came up under its spawner, three below
   its floor, killed eight blazes from there and every rod lay on the floor over it."
  [world sp]
  (or (and (fortress/arrived? world sp spawner-reach) (level-with? world sp))
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
   none is."
  [world sp]
  (let [bl (:plan/blacklist world #{})
        open (remove bl (filter #(open? world %) (cells-beside sp)))]
    (some (fn [dy]
            (memory/nearest-of (physics/centre (intent/feet world))
                               Double/POSITIVE_INFINITY
                               (filter #(= (+ (physics/y-of sp) dy) (physics/y-of %)) open)))
          stand-levels)))

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
        r (when c
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
    (cover world threats)))

(def rest-intent {:intent/kind :rest})

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
   found down there is not a survival. The rest is never chosen while a strike is fresh and the
   way away from that attacker has a landing: blaze-wall-b (plan 40624888909) blaze-fortress run
   1 rested at 8 health while two zombified piglins hit it. test.yml 40624888901: a watched void
   retreat is still a rest, in the open or not, or the act searches. Probe 40634461444: a rest
   at 9.3 with food 17 cannot heal, and holding it is not a plan."
  [world]
  (let [[weid e] (watcher world)
        [aid ae] (combat/striker world)
        away (hide world)
        food (delay (when-not (heals? world) (provisions world)))]
    (cond away away
          (meal world) (meal world)
          (and aid (not (combat/void-retreat? world ae)))
          {:intent/kind :back-off :intent/target aid}
          (and e (combat/void-retreat? world e)) rest-intent
          weid {:intent/kind :back-off :intent/target weid}
          @food @food
          (in-open? world) nil
          (heals? world) rest-intent
          :else nil)))

(defn wary?
  "Is the health under eat-under: a meal then comes before the next fight. Gym blaze-7b run 2 (run
   38893157426) fought on at 12.7 with the food at 17 and a blaze's volley took it to nothing in
   two seconds, under combat/retreat-health only on the way down."
  [world]
  (< (:player/health world 20.0) eat-under))

(defn warily
  "What a wary bot does before the next fight: out of the watching blazes' sight (hide); else
   the meal; else back away from whoever last struck, unless that line is a void; else food,
   when the rest cannot heal (provisions); else, when nothing has struck and not in the open
   and the rest can heal, a :rest until rested; else nil, and it fights on. A rest that cannot
   heal is not chosen. Gym blazefort-k run 3 (run 38913023560) fought on at 11.5, watched by
   the spawner's burst, and was under 10 a second later. blaze-wall-b run 1 chose :rest at 13
   health, over eat-under and not yet hurt, while the piglins that a blaze swing had hit were
   hitting back."
  [world]
  (let [away (hide world)
        [aid ae] (combat/striker world)
        food (delay (when-not (heals? world) (provisions world)))]
    (cond away away
          (meal world) (meal world)
          (and aid (not (combat/void-retreat? world ae)))
          {:intent/kind :back-off :intent/target aid}
          @food @food
          (and (heals? world) (nil? aid) (empty? (watchers world)) (not (in-open? world)))
          rest-intent
          :else nil)))

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

(defn to-spawner
  "The way to spawner sp: a walk when it is walkable?, else a tunnel by the fortress's steps
   (which never open a cell beside lava nor the spawner itself), climbing first, to its stand
   (into it), or with no stand known to within spawner-reach of it."
  [world sp]
  (let [walk {:intent/kind :walk :intent/target sp}
        at (stand world sp)]
    (cond (walkable? world sp) walk
          at
          {:intent/kind :tunnel-to
           :intent/target at
           :intent/steps fortress/steps-per-tunnel
           :intent/reach 0.5
           :intent/climb 0}
          :else
          {:intent/kind :tunnel-to
           :intent/target sp
           :intent/steps fortress/steps-per-tunnel
           :intent/reach spawner-reach
           :intent/climb 0})))

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
  "The fortress bricks on record that have not been visited and are not blacklisted."
  [world]
  (let [bl (:plan/blacklist world #{})
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

(defmethod plan/done-by :rods-held? [world _] (>= (rods world) rods-wanted))

(defmethod combat/answers :blaze-rods [_] #{:blaze})

(defn under-fire?
  "Is the bot hurt (hurt?, or wary? when the answer to that is something to do: warily) with blazes
   watching, in an intent that is not the answer (a fight, a meal, a rest, a back-off, the walk
   to cover)? Sim snapshot R4-31-1 (issue #502): a tunnel of 32 steps went on while two blazes
   10 off, past the at-hand radius, shot the bot from 16.8 health to none in ten seconds, since
   nothing ends a running intent but itself and the planner is asked only then."
  [world i]
  (and (not (contains? #{:fight :eat :rest :back-off} (:intent/kind i)))
       (not= :cover (:intent/for i))
       (seq (watchers world))
       (some? (cond (hurt? world) (healing world)
                    (wary? world) (warily world)))))

(defmethod plan/interrupts? :blaze-rods
  ;; any running intent but a fight ends when a blaze is at hand (at-hand-blaze), and the act
  ;; fights it when healthy: gym blaze-fire-b run 3 (run 39136853644) tunnelled on with one hitting it in melee
  ;; from 20 health to 13.8, since nothing else ends a running intent; never interrupt cover or healing when hurt (#577);
  ;; and one hurt and watched (under-fire?) by blazes further off, whatever the intent but the answer to it
  [world _ i]
  (or (and (not (hurt? world)) (not= :fight (:intent/kind i)) (some? (at-hand-blaze world)))
      (under-fire? world i)))

(defmethod plan/next-intent :blaze-rods
  ;; a tunnel to the spawner or on the search first makes the pickaxe it lacks from what is held
  ;; (the :fortress row's rule): sim race 39742401798 R4-6-2 (issue #504) tunnelled 25 blocks in
  ;; fifteen minutes by hand, each :no-pickaxe step ended the tunnel and the act asked again
  [world goal table]
  (let [[heid] (at-hand-blaze world)
        [beid] (quarry world)
        [_ lying] (rod-lying world)
        sp (nearest-spawner world)
        heal (delay (when (hurt? world) (healing world)))
        wary (delay (when (wary? world) (warily world)))
        away (delay (when (outnumbered? world) (hide world)))
        fire (delay (when (and sp (at-spawner? world sp)) (put-out world sp)))
        pick (delay (when (build/pickaxe-at-hand? world)
                      (make/decide world (assoc goal :goal/provides {:tag/pickaxes 1}) table)))]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          @heal @heal
          (and (not (hurt? world)) heid)
          {:intent/kind :fight :intent/target heid :intent/to-the-end? true :intent/yield? true}
          @wary @wary
          @away @away
          (and lying (drops/under-reach? world lying)) (fetch-walk lying)
          lying
          {:intent/kind :collect-drops
           :intent/item :blaze_rod
           :intent/target (hunt/cell (:entity/pos lying))}
          (and (not (hurt? world)) beid)
          {:intent/kind :fight :intent/target beid :intent/yield? true}
          @fire @fire
          (and (or (nil? sp) (not (at-spawner? world sp))) @pick) @pick
          (and sp (not (at-spawner? world sp))) (to-spawner world sp)
          (and sp (not (hurt? world))) {:intent/kind :camp :intent/spawner sp}
          :else (or (search world) {:plan/wait :no-blaze}))))

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
              (and (not (heals? world)) (not (void-hold? world)))
              (< now-health health)
              (seq (remove seen here))
              struck?
              open?)
          (intent/done world)
          (and (> (- now since) rest-ms) (heals? world)) (intent/fail world :no-heal)
          :else world)))
