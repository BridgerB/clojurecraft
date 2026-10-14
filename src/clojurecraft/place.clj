(ns clojurecraft.place
  "Right-clicking blocks: placing a held block, and opening a container.

     {:intent/kind :place :intent/item :crafting_table}
     {:intent/kind :open-container :intent/target [x y z]}

   Both send use-item-on and judge success only by what the server reports: a block update
   that shows the block (placing), or an open-screen with the window's contents (opening).
   Nothing is predicted; a rejected placement comes back as a block update with the old state
   and an ack, and the intent fails rather than believing the block is there."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def place-reach 4.0)                 ; eye → target centre
(def open-reach 4.5)                  ; eye → container centre for opening it; the server allows about 4.5
(def settle-ms 300)                   ; standing still this long before using an item on a block
(def answer-ms 3000)                  ; ms to wait for the server to answer a placement or an open

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

(def sides [[1 0] [-1 0] [0 1] [0 -1]]) ; the four horizontal neighbours of a cell

(defn solid-at?
  "Is the block at pos loaded and solid?"
  [world pos]
  (boolean (some-> (terrain/block-at world pos)
                   blocks/solid?)))

(defn passage?
  "Is cell part of a way through: the feet could stand in it (path/standable?) and two or more
   of its horizontal neighbours are solid, a corridor (a staircase, a tunnel) rather than open
   ground. A block placed there plugs the way the bot came: gym batch iron-a1 run 2 put its
   table on the stair behind it, then found no spot for the furnace."
  [world [x y z :as cell]]
  (and (path/standable? world cell)
       (<= 2 (count (filter (fn [[dx dz]] (solid-at? world [(+ x dx) y (+ z dz)])) sides)))))

(defn spot
  "[target support] for placing a block near the player: target replaceable (air or a grass
   the server lets a block take the place of, blocks/replaceable?: a flower is not, race
   38477665442 bot Clj_r23 was refused three times on wildflowers) and one the server has
   confirmed (terrain/confirmed?: not a cell the bot's own dig emptied before the server said
   so), no passage, support solid and not a block a click opens (a table, a furnace:
   right-clicked, it opens instead of taking the block; gym batch iron-a5 run 4 clicked its
   table three times to place a gravel and failed :rejected), not inside the player, within
   reach. A passage is allowed when passage-ok? (a block placed only to be dug again at once,
   which leaves the way open: gym batch iron-a6 run 1, at the foot of its stairs between its
   table and furnace, found no spot for a gravel but the stair behind it). Pure; nil when there
   is none."
  ([world] (spot world false))
  ([world passage-ok?]
   (let [[px py pz] (:player/pos world)
         fx (long (math/floor px))
         fy (long (math/floor py))
         fz (long (math/floor pz))
         eye (game/eye world)]
     (first (for [[dx dy dz] around
                  :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                        support [(+ fx dx) (+ fy dy -1) (+ fz dz)]
                        t (terrain/block-at world target)
                        s (terrain/block-at world support)]
                  :when (and t
                             s
                             (blocks/replaceable? t)
                             (terrain/confirmed? world target)
                             (blocks/solid? s)
                             (not (contains? memory/placed-kinds (blocks/name-of s)))
                             (or passage-ok? (not (passage? world target)))
                             (not (inside-player? world target))
                             (<= (physics/distance eye (physics/centre target)) place-reach))]
              [target support])))))

(defn use-item-on
  "Right-click face of the block at pos with the held item: emit use-item-on with a fresh
   :bot/sequence and a swing, and record the sequence and send time on the intent so the
   answer can be judged by the server's ack."
  [world pos face [cx cy cz]]
  (let [seq (inc (:bot/sequence world))]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item-on
                    :hand (packet/value :hand :main-hand)
                    :pos pos
                    :face face
                    :cursor-x cx
                    :cursor-y cy
                    :cursor-z cz
                    :inside-block false
                    :world-border-hit false
                    :sequence seq})
        (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
        (intent/assoc-intent :intent/sequence seq :intent/sent-at (now world)))))

(defn held-slot-of
  "The lowest player-inventory slot holding item-id, or nil."
  [world item-id]
  (some (fn [[p {:keys [item]}]] (when (= item item-id) p))
        (sort-by key (:player/inventory world))))

;; ---------------------------------------------------------------- place

(defmulti place-stage
  "Advance a :place intent one tick in its :intent/stage (:equip :spot :settle :sent). Called
   only when no inventory click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defn equip
  "One step toward holding item (a name) in the hand, for an intent whose next stage is then:
   already held, go on; in the hotbar, select it and go on; in the main inventory, swap it into
   the held slot with a window-0 click and stay (the answer moves it); held nowhere, fail
   :not-held."
  [world item then]
  (let [p (held-slot-of world (recipe/item-id item))
        held (:player/held-slot world)]
    (cond (nil? p) (intent/fail world :not-held)
          (= p held) (intent/assoc-intent world :intent/stage then)
          (inventory/hotbar? p)
          (-> world
              (game/emit {:packet/name :set-carried-item :slot p})
              (assoc :player/held-slot p)
              (intent/assoc-intent :intent/stage then))
          :else
          (window/click world
                        (window/view world :inventory) ; swap into the held slot
                        (inventory/swap-click
                         (inventory/player->window-slot (inventory/layouts :inventory) p)
                         held)))))

(defmethod place-stage :equip [world {:intent/keys [item]}] (equip world item :spot))

(defn niche
  "A cell beside the feet at their level that, dug out, is a spot no way runs through: solid,
   with solid under and over it, nothing over it that falls (gravel or sand fill the niche
   again as soon as it is dug: race 38477665442 bot Clj_r14 dug two such niches seven times in
   turn and was refused), no fluid beside it (the dig would refuse it), not a block the held
   tools cannot drop (batch iron-a3 run 5 failed placing its furnace :needs-tool three times
   on one), neither a block
   that is never dug nor one the bot placed (a table in the last niche); nil when none. Where a
   staircase has no spot, the wall gives one: the side nearest the landing (:plan/go-at), never
   the wall ahead, where the way goes on (batch iron-a2 run 1 put its table there, and its
   tunnel dug through it)."
  [world]
  (let [[fx fy fz] (intent/feet world)
        home (:plan/go-at world)]
    (first (for [[dx dz] (cond->> sides
                           home
                           (sort-by (fn [[dx dz]]
                                      (physics/horizontal-distance [(+ fx dx 0.5) 0 (+ fz dz 0.5)]
                                                                   home))))
                 :let [c [(+ fx dx) fy (+ fz dz)]]
                 :when (and (solid-at? world c)
                            (solid-at? world (update c 1 dec))
                            (solid-at? world (update c 1 inc))
                            (not (blocks/falls? (terrain/block-at world (update c 1 inc))))
                            (not (intent/exposes-fluid? world c))
                            (not= :needs-tool (intent/choose-tool world (terrain/block-at world c)))
                            (not (contains? (into intent/never-dug memory/placed-kinds)
                                            (blocks/name-of (terrain/block-at world c)))))]
             c))))

(def face-cursors
  "Where on each face of the clicked block a placement clicks: its middle."
  {:up [0.5 1.0 0.5]
   :down [0.5 0.0 0.5]
   :north [0.5 0.5 0.0]
   :south [0.5 0.5 1.0]
   :west [0.0 0.5 0.5]
   :east [1.0 0.5 0.5]})

(defn support
  "[block face] to click to place a block into cell: a solid neighbour (under it first, then the
   four sides, then over it) that a click does not open, and its face toward cell; nil when cell
   has none."
  [world cell]
  (first (for [[face step] [[:up [0 -1 0]] [:south [0 0 -1]] [:north [0 0 1]] [:east [-1 0 0]]
                            [:west [1 0 0]] [:down [0 1 0]]]
               :let [s (mapv + cell step)
                     id (terrain/block-at world s)]
               :when (and id
                          (blocks/solid? id)
                          (not (contains? memory/placed-kinds (blocks/name-of id))))]
           [s face])))

(defn settle-at
  "On to :settle to place into target by a click on face of against."
  [world target against face]
  (intent/assoc-intent world
                       :intent/stage :settle
                       :intent/target target
                       :intent/against against
                       :intent/click-face face
                       :intent/since (now world)
                       :intent/still 0))

(defmethod place-stage :spot
  ;; a given cell (:intent/cell, a build's) against a solid neighbour; else a plain spot, else a
  ;; niche cut in the wall, else (passage-ok?) a spot in the passage: a table or a gravel to dig
  ;; again leaves the way open when the bot goes on
  [world {:intent/keys [passage-ok?] at :intent/cell}]
  (let [[against face :as given] (when at (support world at))
        found (when-not at (spot world))
        n (when-not (or at found) (niche world))
        [target from] (or found (when (and (nil? at) (nil? n) passage-ok?) (spot world true)))]
    (cond given (settle-at world at against face)
          at (intent/fail world :no-support)
          target (settle-at world target from :up)
          n (intent/assoc-intent world :intent/stage :niche :intent/child (intent/child-dig n))
          :else (intent/fail world :no-spot))))

(defn dig-niche
  "One tick of digging the niche (the child :dig); done and the cell confirmed by the server
   (terrain/confirmed?), or the server silent for answer-ms since the dig's FINISH, equip again
   (the dig chose its own held slot) and look for the spot; failed, fail with its reason. A
   loaded server acks the FINISH at once and breaks the block seconds later, by its own clock
   (vanilla's delayed destroy): race 38477665442 bots Clj_r01 and Clj_r07 placed into the
   niche a second after the ack, the server answered with the stone still there, and three
   such placements failed the plan :rejected."
  [world {:intent/keys [child]} event]
  (cond (and (intent/done? child)
             (or (terrain/confirmed? world (:intent/target child))
                 (> (- (now world) (:intent/since child)) answer-ms)))
        (-> world
            (intent/assoc-intent :intent/stage :equip)
            (intent/dissoc-intent :intent/child))
        (intent/done? child) world
        (intent/failed? child) (intent/fail world (:intent/reason child))
        :else (intent/run-child world event)))

(defmethod place-stage :settle
  ;; a chosen target the server filled meanwhile (gravel that fell into the niche, the block it
  ;; put back) is given up for another spot before anything is sent; a given cell (a build's)
  ;; is sent and judged by the server
  [world
   {:intent/keys [target against click-face since still] at :intent/cell :or {click-face :up}}]
  (let [look (physics/look-at (game/eye world) (physics/centre target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (cond (and (nil? at) (not (blocks/replaceable? (terrain/block-at world target))))
          (-> world
              (intent/assoc-intent :intent/stage :spot)
              (intent/dissoc-intent :intent/target :intent/against :intent/click-face))
          (and (>= still 3) (> (- (now world) since) settle-ms))
          (-> world
              (use-item-on against (packet/value :face click-face) (face-cursors click-face))
              (intent/assoc-intent :intent/stage :sent))
          :else (intent/assoc-intent world :intent/still still))))

(defmethod place-stage :sent
  [world {:intent/keys [item target sequence sent-at]}]
  (let [placed (terrain/block-at world target)]
    (cond (= item (blocks/name-of placed)) (intent/done world) ; any state of the block: a furnace faces as the server chose
          (and (>= (or (:stats/last-ack world) -1) sequence) (> (- (now world) sent-at) 500))
          (intent/fail world :rejected)
          (> (- (now world) sent-at) answer-ms) (intent/fail world :no-answer)
          :else world)))

(defmethod intent/run :place
  [world i event]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip))
        world (assoc world :plan/intent i)]
    (cond (= :niche (:intent/stage i)) (dig-niche world i event)
          :else
          (case (window/waiting world i)
            :overdue (intent/fail world :stale-window)
            :waiting world
            (place-stage world i)))))

;; ---------------------------------------------------------------- equip

(defmethod intent/run :equip
  ;; {:intent/kind :equip :intent/item :stone_pickaxe}: put the item in the hand (equip), done
  ;; once it is there; a tool in the main inventory is swapped into the held slot with a click
  [world {:intent/keys [item stage] :as i} _]
  (let [world (assoc world :plan/intent i)]
    (cond (= :held stage) (intent/done world)
          :else
          (case (window/waiting world i)
            :overdue (intent/fail world :stale-window)
            :waiting world
            (equip world item :held)))))

;; ---------------------------------------------------------------- open

(defmethod intent/run :open-container
  [world {:intent/keys [target stage sent-at] :as i} _event]
  (let [world (assoc world :plan/intent i :player/controls {})
        eye (game/eye world)
        open (:window/open world)]
    (cond (and open (:window/state-id open)) (intent/done world)
          (= stage :sent)
          (if (> (- (now world) sent-at) answer-ms) (intent/fail world :no-window) world)
          (> (physics/distance eye (physics/centre target)) open-reach)
          (intent/fail world :out-of-reach)
          :else
          (-> world
              (assoc :player/controls {:control/look (physics/look-at eye (physics/centre target))})
              (use-item-on target (intent/face-toward eye target) [0.5 0.5 0.5])
              (intent/assoc-intent :intent/stage :sent)))))
