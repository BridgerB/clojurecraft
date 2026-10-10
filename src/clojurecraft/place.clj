(ns clojurecraft.place
  "Right-clicking blocks: placing a held block, and opening a container.

     {:intent/kind :place :intent/item :crafting_table}
     {:intent/kind :open-container :intent/target [x y z]}

   Both send use-item-on and judge success only by what the server reports: a block update
   that shows the block (placing), or an open-screen with the window's contents (opening).
   Nothing is predicted; a rejected placement comes back as a block update with the old state
   and an ack, and the intent fails rather than believing the block is there."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(def place-reach 4.0)                 ; eye → target centre
(def open-reach 4.5)                  ; eye → container centre for opening it; the server allows about 4.5
(def settle-ms 300)                   ; standing still this long before using an item on a block
(def answer-ms 3000)                  ; ms to wait for the server to answer a placement or an open

(defn- set-intent [world & kvs] (apply update world :plan/intent assoc kvs))
(defn- now [world] (:time/now world))

(def around
  "Candidate cells around the feet, nearest ring first, at feet level then one down then one
   up (an uneven forest floor): a CI run found no spot when only feet-level cells two away
   were tried."
  (for [r [1 2 3]
        dy [0 -1 1]
        dx (range (- r) (inc r))
        dz (range (- r) (inc r))
        :when (= r (max (abs dx) (abs dz)))]
    [dx dy dz]))

(defn inside-player?
  "Would a block at cell intersect the player's box? The server rejects such a placement."
  [world [x y z]]
  (let [[x0 y0 z0 x1 y1 z1] (physics/aabb (:player/pos world))]
    (and (< x0 (inc x)) (> x1 x) (< y0 (inc y)) (> y1 y) (< z0 (inc z)) (> z1 z))))

(defn spot
  "[target support] for placing a block near the player: target empty (air or a plant, not
   liquid), support solid, not inside the player, within reach. Pure; nil when there is none."
  [world]
  (let [[px py pz] (:player/pos world)
        fx (long (Math/floor px)) fy (long (Math/floor py)) fz (long (Math/floor pz))
        eye (game/eye world)]
    (first (for [[dx dy dz] around
                 :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                       support [(+ fx dx) (+ fy dy -1) (+ fz dz)]
                       t (game/block-at world target)
                       s (game/block-at world support)]
                 :when (and t s
                            (not (blocks/solid? t))
                            (not= :liquid (blocks/type-of t))
                            (blocks/solid? s)
                            (not (inside-player? world target))
                            (<= (physics/distance eye (physics/centre target)) place-reach))]
             [target support]))))

(defn use-item-on
  "Right-click face of the block at pos with the held item: emit use-item-on with a fresh
   :bot/sequence and a swing, and record the sequence and send time on the intent so the
   answer can be judged by the server's ack."
  [world pos face [cx cy cz]]
  (let [seq (inc (:bot/sequence world))]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item-on :hand 0 :pos pos :face face :cursor-x cx :cursor-y cy :cursor-z cz
                    :inside-block false :world-border-hit false :sequence seq})
        (game/emit {:packet/name :swing :hand 0})
        (set-intent :intent/sequence seq :intent/sent-at (now world)))))

(defn held-slot-of
  "The lowest player-inventory slot holding item-id, or nil."
  [world item-id]
  (some (fn [[p {:keys [item]}]] (when (= item item-id) p)) (sort-by key (:player/inventory world))))

;; ---------------------------------------------------------------- place

(defmulti place-stage
  "Advance a :place intent one tick in its :intent/stage (:equip :spot :settle :sent). Called
   only when no inventory click is unanswered."
  (fn [_world i] (:intent/stage i)))

(defmethod place-stage :equip [world {:intent/keys [item]}]
  (let [id (recipe/item-id item)
        p (held-slot-of world id)
        held (:player/held-slot world)]
    (cond
      (nil? p) (intent/fail world :not-held)
      (= p held) (set-intent world :intent/stage :spot)
      (<= 0 p 8) (-> world
                     (game/emit {:packet/name :set-carried-item :slot p})
                     (assoc :player/held-slot p)
                     (set-intent :intent/stage :spot))
      :else (window/click world (window/view world :inventory)            ; swap into the held slot
                          {:click/slot (recipe/player->container-slot p) :click/button held :click/mode 2}))))

(defmethod place-stage :spot [world _]
  (if-let [[target support] (spot world)]
    (set-intent world :intent/stage :settle :intent/target target :intent/against support
                :intent/since (now world) :intent/still 0)
    (intent/fail world :no-spot)))

(defmethod place-stage :settle [world {:intent/keys [target against since still]}]
  (let [look (physics/look-at (game/eye world) (physics/centre target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (if (and (>= still 3) (> (- (now world) since) settle-ms))
      (-> world (use-item-on against 1 [0.5 1.0 0.5]) (set-intent :intent/stage :sent))
      (set-intent world :intent/still still))))

(defmethod place-stage :sent [world {:intent/keys [item target sequence sent-at]}]
  (let [placed (game/block-at world target)]
    (cond
      (= placed (memory/placed-state item)) (intent/done world)
      (and (>= (or (:stats/last-ack world) -1) sequence) (> (- (now world) sent-at) 500))
      (intent/fail world :rejected)
      (> (- (now world) sent-at) answer-ms) (intent/fail world :no-answer)
      :else world)))

(defmethod intent/run :place [world i _event]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (intent/fail world :stale-window)
      :waiting world
      (place-stage world i))))

;; ---------------------------------------------------------------- open

(defmethod intent/run :open-container [world {:intent/keys [target stage sent-at] :as i} _event]
  (let [world (assoc world :plan/intent i :player/controls {})
        eye (game/eye world)
        open (:window/open world)]
    (cond
      (and open (:window/state-id open)) (intent/done world)
      (= stage :sent) (if (> (- (now world) sent-at) answer-ms) (intent/fail world :no-window) world)
      (> (physics/distance eye (physics/centre target)) open-reach) (intent/fail world :out-of-reach)
      :else (-> world
                (assoc :player/controls {:control/look (physics/look-at eye (physics/centre target))})
                (use-item-on target (intent/face-toward eye target) [0.5 0.5 0.5])
                (set-intent :intent/stage :sent)))))
