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
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def rods-wanted 7)                   ; six for the twelve blaze powder twelve eyes take, one spare
(def fight-radius 12.0)               ; a blaze this near the feet is fought
(def camp-range 8.0)                  ; blocks from the spawner's centre to the eye where the bot waits for its blazes (a spawner wakes for a player within 16)
(def camp-ms 60000)                   ; a camp with no blaze this long fails :no-blaze (a natural spawner waits 10 to 40 s between spawns)
(def spawner-radius 256)              ; how far off a remembered spawner may be and still be gone to
(def over-feet 3.0)                   ; blocks a blaze's feet may hover over the bot's and be fought (its body, aim-height up, comes within reach)
(def under-feet 2.0)                  ; blocks under the bot's feet a blaze may be and be fought
(def candidates 3)                    ; nearest blazes asked for a route, as the hunt asks of its animals

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
  "Can blaze e be fought from where the bot can walk: its feet no more than over-feet over the
   bot's nor under-feet under them, and a route reaching within a walk's range of its cell on the
   plane (intent/routable?, which judges the plane only). Gym blaze-fortress-a (run 38892386220)
   tunnelled to its spawner and failed three fights in a row :unreachable on blazes seen through
   the fortress's walls, every run."
  [world e]
  (let [dy (- (physics/y-of (:entity/pos e)) (physics/y-of (:player/pos world)))]
    (and (<= (- under-feet) dy over-feet)
         (intent/routable? world {:intent/kind :walk :intent/target (hunt/cell (:entity/pos e))}))))

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

(defn at-spawner?
  "Is the eye within camp-range of the spawner's centre?"
  [world sp]
  (<= (physics/distance (game/eye world) (physics/centre sp)) camp-range))

(defn hurt?
  "Is the health the server last told under combat/retreat-health?"
  [world]
  (< (:player/health world 20.0) combat/retreat-health))

(defn healing
  "What a hurt bot does: eat when the food is under hunger/heal-level (health comes back only from
   there, and fast while the meal's saturation lasts) and food is held; else back away from the
   nearest blaze within combat/back-off-to; else wait :healing. The meal first: gym blaze-7 run 1
   (run 38891910129) backed away three times round a room seven square with the food at 17, where
   no back-off gets away, and burned to death without eating."
  [world]
  (let [[eid e] (quarry world)
        food (eat/food-held world)]
    (cond (and food (< (:player/food world hunger/heal-level) hunger/heal-level))
          {:intent/kind :eat :intent/item food}
          (and e (< (physics/distance (:player/pos world) (:entity/pos e)) combat/back-off-to))
          {:intent/kind :back-off :intent/target eid}
          :else {:plan/wait :healing})))

(defn to-spawner
  "The way to spawner sp: a walk when a route reaches it (intent/routable?), else a tunnel by
   the fortress's steps, which never open a cell beside lava."
  [world sp]
  (let [walk {:intent/kind :walk :intent/target sp}]
    (if (intent/routable? world walk)
      walk
      {:intent/kind :tunnel-to :intent/target sp :intent/steps fortress/steps-per-tunnel})))

(defmethod plan/done-by :rods-held? [world _] (>= (rods world) rods-wanted))

(defmethod plan/next-intent :blaze-rods
  [world _ _]
  (let [[beid] (quarry world)
        [_ lying] (rod-lying world)
        sp (nearest-spawner world)]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          (hurt? world) (healing world)
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
