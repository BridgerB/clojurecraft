(ns clojurecraft.blaze
  "Blaze rods (issue #262): the :blaze-rods goal, done when rods-wanted are held: six give the
   twelve blaze powder twelve eyes of ender take, and one is spare. Worked in the Nether only.

   Requiring this namespace registers the row's rules and the :camp executor. Planned by
   :blaze-rods, in order: hurt under combat/retreat-health, back away from a blaze at hand, or eat
   when the food is under the healing level, or wait for the health to come back (ruststeve broke
   off under 10 health: a run that fought on killed the blaze and died with it); a rod lying near
   is picked up (:collect-drops: a blaze drops one a kill in two, so nothing lying there is no
   failure); the nearest blaze within fight-radius is fought (combat's :fight, attacks 650 ms
   apart, aimed at its body as it hovers); else the remembered spawner (a spawner seen in the
   Nether is a fortress's, memory/nether-landmark-kinds) is walked to, or tunnelled to by the
   fortress's steps when no route reaches it, and camped by until its blazes come; with no spawner
   known, a wait :no-blaze. The :defend row still answers anything hostile within 4 first.

   Fireballs are not dodged: ruststeve never needed it with its break-off at 10 health; the
   damage-event log is where to look if they kill."
  (:require [clojurecraft.blocks :as blocks]
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
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def rods-wanted 7)                   ; six for the twelve blaze powder twelve eyes take, one spare
(def fight-radius 12.0)               ; a blaze this near the feet is fought
(def camp-range 8.0)                  ; blocks from the spawner's centre to the eye where the bot waits for its blazes (a spawner wakes for a player within 16)
(def camp-ms 60000)                   ; a camp with no blaze this long fails :no-blaze (a natural spawner waits 10 to 40 s between spawns)
(def spawner-radius 256)              ; how far off a remembered spawner may be and still be gone to
(def candidates 3)                    ; nearest blazes asked for a route, as the hunt asks of its animals
(def side-steps [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]]) ; the cells beside a spawner a stand is looked for in
(def spawner-reach 2.0)                ; blocks from the feet cell's centre to the spawner's a tunnel to it ends within: in its room, at the blazes' floor
(def cover-range 16.0)                ; blazes this near that see the bot are the ones a hurt bot takes cover from (they shoot that far)
(def cover-distance 10)               ; blocks on the plane a walk into cover puts between the bot and the blaze
(def eat-under 14.0)                  ; health under which a meal comes before the next fight, the food under the healing level: one volley takes 9

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
   sight, its feet no more than three over the bot's nor two under), and a route reaching within
   a walk's range of its cell on the plane (intent/routable?, which judges the plane only). Gym
   blaze-fortress-a (run 38892386220) tunnelled to its spawner and failed three fights in a row
   :unreachable on blazes seen through the fortress's walls, every run."
  [world e]
  (and (combat/hittable? world e)
       (intent/routable? world {:intent/kind :walk :intent/target (hunt/cell (:entity/pos e))})))

(defn quarry
  "[eid entity] of the blaze to fight: the first of the candidates nearest (blazes) that is
   reachable?, or nil."
  [world]
  (let [pos (:player/pos world)]
    (->> (blazes world)
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]] (when (reachable? world e) ee))))))

(defn rod-lying
  "[eid entity] of the nearest drop that may be a rod (drops/holds?) within drops/drop-radius of
   the feet, not in a cell a pickup failed in (the cell is blacklisted), or nil."
  [world]
  (let [bl (:plan/blacklist world #{})]
    (entity/nearest (into {}
                          (remove (fn [[_ e]] (contains? bl (hunt/cell (:entity/pos e)))))
                          (drops/lying world rod (:player/pos world) drops/drop-radius))
                    (:player/pos world))))

(defn nearest-spawner
  "The spawner on record in the bot's dimension nearest the eye, within spawner-radius and not
   blacklisted (no walk nor tunnel reached it), or nil."
  [world]
  (let [bl (:plan/blacklist world #{})]
    (memory/nearest-of (game/eye world)
                       spawner-radius
                       (remove bl (memory/positions-now world [spawner])))))

(defn level-with?
  "Are the feet within a block of spawner sp's height: its blazes' floor?"
  [world sp]
  (<= (abs (- (physics/y-of (intent/feet world)) (physics/y-of sp))) 1))

(defn walkable?
  "Can the bot walk to spawner sp: level-with? it (a route judges the plane only,
   intent/routable?) and a route to it."
  [world sp]
  (and (level-with? world sp) (intent/routable? world {:intent/kind :walk :intent/target sp})))

(defn at-spawner?
  "Is the bot where it camps by spawner sp: the feet within spawner-reach of it and level-with?
   it (where a tunnel to it ends), or the eye within camp-range and the spawner walkable? (on its
   floor, not in a tunnel beside its room). Gym blaze-fortress-b (run 38893941350) tunnelled to
   within 6 of its spawner, four blocks over it in the wall, and camped there ten minutes: the
   blazes the spawner made in its room below were out of reach, every run; blaze-fortress-c
   (run 38895759229) run 3 came up under its spawner, three below its floor, killed eight blazes
   from there and every rod lay on the floor over it."
  [world sp]
  (or (and (fortress/arrived? world sp spawner-reach) (level-with? world sp))
      (and (<= (physics/distance (game/eye world) (physics/centre sp)) camp-range)
           (walkable? world sp))))

(defn open?
  "Are cell c and the one over it loaded and not solid: room for the body?"
  [world [x y z :as c]]
  (every? #(some-> (terrain/block-at world %)
                   blocks/solid?
                   not)
          [c [x (inc y) z]]))

(defn stand
  "The cell beside spawner sp, at its height, with room for the body (open?), nearest the feet:
   where a tunnel to it ends, on its blazes' floor; nil when none is."
  [world sp]
  (memory/nearest-of (physics/centre (intent/feet world))
                     Double/POSITIVE_INFINITY
                     (filter #(open? world %) (map #(fortress/plus sp %) side-steps))))

(defn hurt?
  "Is the health the server last told under combat/retreat-health?"
  [world]
  (< (:player/health world 20.0) combat/retreat-health))

(defn meal
  "An :eat of the food held when the food is under hunger/heal-level (health comes back only from
   there, and fast while the meal's saturation lasts), or nil."
  [world]
  (when-let [food (and (< (:player/food world hunger/heal-level) hunger/heal-level)
                       (eat/food-held world))]
    {:intent/kind :eat :intent/item food}))

(defn watcher
  "[eid entity] of the nearest blaze alive within cover-range that has the bot in sight
   (combat/seen?: a clear line from the eye to it), or nil: what a hurt bot hides from."
  [world]
  (let [pos (:player/pos world)]
    (entity/nearest (into {}
                          (filter (fn [[_ e]]
                                    (and (= :blaze (entity/kind e))
                                         (not (entity/dead? e))
                                         (combat/seen? world e))))
                          (:world/entities world))
                    pos
                    cover-range)))

(defn cover
  "A walk out of the sight of blaze e: to the end of a route at least cover-distance away from it
   on the plane (path's :away goal; back along the tunnel the bot came by), or nil when there is
   none. Gym blaze-fortress-e (run 38900794167) stood by its spawner under 10 health, waiting
   to heal while burning and shot, and every run died there."
  [world e]
  (let [r (path/plan
           world
           (path/feet-cell (:player/pos world))
           {:goal/kind :away :goal/pos (hunt/cell (:entity/pos e)) :goal/range cover-distance})]
    (when (= :found (:path/status r))
      {:intent/kind :walk :intent/target (last (:path/waypoints r)) :intent/for :cover})))

(defn healing
  "What a hurt bot does: the meal; else out of a watching blaze's sight (watcher, cover); else
   back away from the nearest blaze within combat/back-off-to; else wait :healing. The meal
   first: gym blaze-7 run 1 (run 38891910129) backed away three times round a room seven square
   with the food at 17, where no back-off gets away, and burned to death without eating."
  [world]
  (let [[eid e] (quarry world)
        [_ w] (watcher world)
        hide (when w (cover world w))]
    (cond (meal world) (meal world)
          hide hide
          (and e (< (physics/distance (:player/pos world) (:entity/pos e)) combat/back-off-to))
          {:intent/kind :back-off :intent/target eid}
          :else {:plan/wait :healing})))

(defn wary?
  "Is the health under eat-under: a meal then comes before the next fight. Gym blaze-7b run 2 (run
   38893157426) fought on at 12.7 with the food at 17 and a blaze's volley took it to nothing in
   two seconds, under combat/retreat-health only on the way down."
  [world]
  (< (:player/health world 20.0) eat-under))

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

(defmethod plan/done-by :rods-held? [world _] (>= (rods world) rods-wanted))

(defmethod plan/next-intent :blaze-rods
  [world _ _]
  (let [[beid] (quarry world)
        [_ lying] (rod-lying world)
        sp (nearest-spawner world)]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          (hurt? world) (healing world)
          (and (wary? world) (meal world)) (meal world)
          (and (wary? world) (nil? (watcher world))) {:plan/wait :healing}
          lying
          {:intent/kind :collect-drops
           :intent/item :blaze_rod
           :intent/target (hunt/cell (:entity/pos lying))}
          beid {:intent/kind :fight :intent/target beid}
          (and sp (not (at-spawner? world sp))) (to-spawner world sp)
          sp {:intent/kind :camp :intent/spawner sp}
          :else {:plan/wait :no-blaze})))

;; ---------------------------------------------------------------- the :camp intent

(defmethod intent/run :camp
  ;; {:intent/kind :camp :intent/spawner cell}: stand by the spawner until a blaze comes within
  ;; fight-radius that was not there when the camp began (:intent/blazes: one there then could not
  ;; be fought, or the act would have chosen it) or a rod lies near (done), failed :no-blaze after
  ;; camp-ms. A camp is an intent, not a planner wait: a wait fails the plan after
  ;; plan/wait-ticks (20 s), shorter than a natural spawner's delay. Its spawner is not its
  ;; :intent/target, so a camp that saw nothing never blacklists it; three in a row fail the plan
  [world {:intent/keys [since] seen :intent/blazes} _]
  (let [now (:time/now world)
        here (set (keys (blazes world)))
        seen (or seen here)
        since (or since now)
        world (-> world
                  (assoc :player/controls {})
                  (intent/assoc-intent :intent/since since :intent/blazes seen))]
    (cond (or (seq (remove seen here)) (rod-lying world)) (intent/done world)
          (> (- now since) camp-ms) (intent/fail world :no-blaze)
          :else world)))
