(ns clojurecraft.blaze
  "Blaze rods (issue #262): the :blaze-rods goal, done when rods-wanted are held: six give the
   twelve blaze powder twelve eyes of ender take, and one is spare. Worked in the Nether only.

   Requiring this namespace registers the row's rules and the :camp and :rest executors. Planned
   by :blaze-rods, in order: hurt under combat/retreat-health, into a cell out of the watching
   blazes' sight, or eat when the food is under the healing level, or back away from the nearest
   watcher, or rest out of sight until the health is back (ruststeve broke off under 10 health: a
   run that fought on killed the blaze and died with it); wary under eat-under, the same cover,
   meal or rest before the next fight; watched by a volley of blazes at once, cover whatever the
   health; a rod lying near is picked up (:collect-drops: a blaze drops one a kill in two, so
   nothing lying there is no failure); the nearest blaze within fight-radius is fought (combat's
   :fight, attacks 650 ms apart, aimed at its body as it hovers); a fire by the spawner is put
   out (a lit floor stops its blazes); else the remembered spawner (a spawner seen in the Nether
   is a fortress's, memory/nether-landmark-kinds) is walked to, or tunnelled to by the fortress's
   steps when no route reaches it, and camped by until its blazes come; with no spawner known, a
   wait :no-blaze. The :defend row answers anything else hostile within 4 first; blazes it
   leaves to this row while it is worked (combat/answers), or its fights would override the
   cover. A fight this row starts ends under combat/retreat-health (:intent/yield?), and the
   cover, the meal or the rest follows, not combat's straight retreat.

   Fireballs are not dodged: ruststeve never needed it with its break-off at 10 health; the
   damage-event log is where to look if they kill. The bot does not put itself out: no water
   works in the Nether, and the gym rows give Fire Resistance until potions exist."
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
(def eat-under 14.0)                  ; health under which a meal comes before the next fight, the food under the healing level: one volley takes 9
(def volley 3)                        ; blazes watching at once that send the bot to cover whatever its health: a natural spawner's burst is four, each hit 3.12 through iron armour
(def rested 18.0)                     ; health a rest out of sight ends at: two volleys' hits over the wary line
(def rest-ms 90000)                   ; a rest with no health coming back this long fails :no-heal (the slow heal is one point every four seconds)
(def fire-radius 4)                   ; cells from a spawner a fire is put out in: a fire lights its cells past 11, where a spawner's blaze no longer spawns

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
   comes to it (combat/approachable?: near on the plane, or the approach's own route). Gym
   blaze-fortress-a (run 38892386220) tunnelled to its spawner and failed three fights in a row
   :unreachable on blazes seen through the fortress's walls, every run; blaze-fire-a runs 2 and
   4 (run 39131023637) did again on blazes a walk's route came within three of, the approach's
   route, within two, finding none."
  [world e]
  (and (combat/hittable? world e) (combat/approachable? world e)))

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
  [world {:goal/keys [pos cells]} node]
  (every? #(unseen-from? world node %) (cons pos cells)))

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
        [c & cs] (map #(hunt/cell (:entity/pos %))
                      (sort-by #(physics/distance pos (:entity/pos %)) es))
        r (when c
            (path/plan world
                       (path/feet-cell pos)
                       {:goal/kind :unseen :goal/pos c :goal/cells (vec cs)}))
        to (last (:path/waypoints r))]
    (when (and r (= :found (:path/status r)) to (not= to (intent/feet world)))
      {:intent/kind :walk :intent/target to :intent/for :cover})))

(defn hide
  "The cover walk from every watcher, or nil: none watching, or no cell out of their sight."
  [world]
  (cover world (vals (watchers world))))

(def rest-intent {:intent/kind :rest})

(defn healing
  "What a hurt bot does: out of the watching blazes' sight (hide); else the meal; else back away
   from the nearest watcher; else, with none watching, a :rest. Cover before the meal where
   there is cover: a meal takes a second and a half standing still, two hits from a burst, and
   gym blazefort-k run 3 (run 38913023560) ate at 9.4 by its spawner and was dead before the
   food came. The meal before the back-off where there is none: gym blaze-7 run 1 (run
   38891910129) backed away three times round a room seven square with the food at 17, where no
   back-off gets away, and burned to death without eating."
  [world]
  (let [[weid] (watcher world)
        away (hide world)]
    (cond away away
          (meal world) (meal world)
          weid {:intent/kind :back-off :intent/target weid}
          :else rest-intent)))

(defn wary?
  "Is the health under eat-under: a meal then comes before the next fight. Gym blaze-7b run 2 (run
   38893157426) fought on at 12.7 with the food at 17 and a blaze's volley took it to nothing in
   two seconds, under combat/retreat-health only on the way down."
  [world]
  (< (:player/health world 20.0) eat-under))

(defn warily
  "What a wary bot does before the next fight: out of the watching blazes' sight (hide); else
   the meal; else, with no blaze watching, a :rest until rested; else nil, and it fights on.
   Gym blazefort-k run 3 (run 38913023560) fought on at 11.5, watched by the spawner's burst,
   and was under 10 a second later."
  [world]
  (let [away (hide world)]
    (cond away away
          (meal world) (meal world)
          (empty? (watchers world)) rest-intent
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

(defmethod plan/done-by :rods-held? [world _] (>= (rods world) rods-wanted))

(defmethod combat/answers :blaze-rods [_] #{:blaze})

(defmethod plan/next-intent :blaze-rods
  [world _ _]
  (let [[beid] (quarry world)
        [_ lying] (rod-lying world)
        sp (nearest-spawner world)
        wary (delay (when (wary? world) (warily world)))
        away (delay (when (outnumbered? world) (hide world)))
        fire (delay (when (and sp (at-spawner? world sp)) (put-out world sp)))]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          (hurt? world) (healing world)
          @wary @wary
          @away @away
          lying
          {:intent/kind :collect-drops
           :intent/item :blaze_rod
           :intent/target (hunt/cell (:entity/pos lying))}
          beid {:intent/kind :fight :intent/target beid :intent/yield? true}
          @fire @fire
          (and sp (not (at-spawner? world sp))) (to-spawner world sp)
          sp {:intent/kind :camp :intent/spawner sp}
          :else {:plan/wait :no-blaze})))

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
  ;; :intent/target, so a camp that saw nothing never blacklists it; three in a row fail the plan
  [world {:intent/keys [since health] seen :intent/blazes} _]
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
          (> (- now since) camp-ms) (intent/fail world :no-blaze)
          :else world)))

;; ---------------------------------------------------------------- the :rest intent

(defmethod intent/run :rest
  ;; {:intent/kind :rest}: stand still out of sight until the health is back to rested, or a
  ;; blaze watches the bot that did not when the rest began (:intent/blazes), or the health
  ;; falls under what it was then (:intent/health: shot from somewhere unseen) (done, and the
  ;; act looks again), failed :no-heal after rest-ms (no food in the bar, nothing comes back).
  ;; An intent, not a planner wait: the slow heal brings a point every four seconds, and a wait
  ;; fails the plan after plan/wait-ticks (20 s)
  [world {:intent/keys [since health] seen :intent/blazes} _]
  (let [now (:time/now world)
        here (set (keys (watchers world)))
        seen (or seen here)
        since (or since now)
        health (or health (:player/health world 20.0))
        now-health (:player/health world 20.0)
        world
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/since since :intent/blazes seen :intent/health health))]
    (cond (or (>= now-health rested) (< now-health health) (seq (remove seen here)))
          (intent/done world)
          (> (- now since) rest-ms) (intent/fail world :no-heal)
          :else world)))
