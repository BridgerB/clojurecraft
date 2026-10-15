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
(def fresh-rock-distance 12)          ; blocks a place with nowhere to put its block walks off before it looks again
(def fresh-rock-climb 4)              ; stairs up a place with nowhere and no route away cuts before it looks again
(def max-refusals 3)                  ; spots the server refused with nothing in them before a placement fails :rejected

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
   table and furnace, found no spot for a gravel but the stair behind it). A cell in refused is
   passed over: the server answered a placement there with the cell still empty, a body
   standing in it (give-up). Pure; nil when there is none."
  ([world] (spot world false))
  ([world passage-ok?] (spot world passage-ok? #{}))
  ([world passage-ok? refused]
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
                             (not (contains? refused target))
                             (blocks/replaceable? t)
                             (terrain/confirmed? world target)
                             (blocks/solid? s)
                             (not (contains? memory/placed-kinds (blocks/name-of s)))
                             (or passage-ok? (not (passage? world target)))
                             (not (inside-player? world target))
                             (<= (physics/distance eye (physics/centre target)) place-reach))]
              [target support])))))

(defn ashore
  "intent, or, with the body in water, a :swim-out for :retreat first (done on dry land, swim):
   a spot is sought round the feet, and afloat at a pond's edge every cell round them is water
   and the bank is above the impulse's reach, so the place fails :no-spot the tick it starts,
   three times in half a second, before the trap can preempt it. Sim race 38515210080 world
   R4/41 (issue #95, 15 runs; four real bots of race 38515209959): the bucket filled, the bot
   was afloat in the column its source left, two under the bank, and the gravel to dig again
   for flint found no spot."
  [world intent]
  (if (:player/in-water? world) {:intent/kind :swim-out :intent/for :retreat} intent))

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

(defn droppable?
  "Can a held tool drop the block id: one in the hotbar (intent/choose-tool, not :needs-tool)
   or one stowed in the main inventory (intent/stowed-tool), which the niche's dig puts in the
   hand first. Equipping the furnace swaps the pickaxe out of the hand into the main inventory
   when the hotbar is full of what mining picked up, and a niche asked of the hotbar alone was
   then refused everywhere: sim race 38477665389 world R1/43 (issue #94, 11 runs) held two
   stone pickaxes and the furnace at the end of its iron tunnel at y 16, :no-spot three times."
  [world id]
  (boolean (or (intent/stowed-tool world id) (not= :needs-tool (intent/choose-tool world id)))))

(defn niche
  "A cell beside the feet at their level, or beside the head when none is there (the block
   goes into the wall at head height, still in reach to open: race 38477665442 bot Clj_r12 at
   the end of its tunnel had water beside both side walls and its table in the third, and
   found no spot for the furnace, :no-spot), that, dug out, is a spot no way runs through: solid,
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
    (first (for [dy [0 1]
                 [dx dz] (cond->> sides
                           home
                           (sort-by (fn [[dx dz]]
                                      (physics/horizontal-distance [(+ fx dx 0.5) 0 (+ fz dz 0.5)]
                                                                   home))))
                 :let [c [(+ fx dx) (+ fy dy) (+ fz dz)]]
                 :when (and (solid-at? world c)
                            (solid-at? world (update c 1 dec))
                            (solid-at? world (update c 1 inc))
                            (not (blocks/falls? (terrain/block-at world (update c 1 inc))))
                            (not (intent/exposes-fluid? world c))
                            (droppable? world (terrain/block-at world c))
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

(defn fresh-rock
  "The child walk to the end of a route fresh-rock-distance away from the feet (path's :away
   goal), where the walls are new, for a place that found nowhere here; with no route away,
   stairs up fresh-rock-climb (the stairs dig a way where none is walked); nil with the body in
   water (ashore is for that). Sim races 38660819204, 38662908438 and 38665691522 (issue #94,
   worlds R1/44, R1/13, R1/37): the furnace was to go down at the dead end of an iron tunnel at
   y 9-17 by an aquifer, every wall beside the feet touched water or had the passage over it,
   and the furnace failed :no-spot three times there. Sim race 38659291345 world R1/1 (issue
   #148): sand had fallen round and onto the bot at y 59 under a desert, every cell beside it
   sand or sandstone under sand, no route anywhere, and the place failed :no-spot."
  [world]
  (when-not (:player/in-water? world)
    (let [r (path/plan
             world
             (path/feet-cell (:player/pos world))
             {:goal/kind :away :goal/pos (intent/feet world) :goal/range fresh-rock-distance})]
      (if (= :found (:path/status r))
        {:intent/kind :walk
         :intent/target (last (:path/waypoints r))
         :intent/for :retreat
         :intent/status :active}
        {:intent/kind :stairs-up
         :intent/to-y (+ (second (intent/feet world)) fresh-rock-climb)
         :intent/for :retreat
         :intent/status :active}))))

(defmethod place-stage :spot
  ;; a given cell (:intent/cell, a build's) against a solid neighbour; else a plain spot, else a
  ;; niche cut in the wall, else (passage-ok?) a spot in the passage: a table or a gravel to dig
  ;; again leaves the way open when the bot goes on
  [world {:intent/keys [passage-ok? refused retreated?] at :intent/cell :or {refused #{}}}]
  (let [[against face :as given] (when at (support world at))
        found (when-not at (spot world false refused))
        n (when-not (or at found) (niche world))
        [_ stowed-item :as stowed] (when n (intent/stowed-tool world (terrain/block-at world n)))
        [target from] (or found
                          (when (and (nil? at) (nil? n) passage-ok?) (spot world true refused)))]
    (cond given (settle-at world at against face)
          at (intent/fail world :no-support)
          target (settle-at world target from :up)
          ;; the tool the niche needs is in the main inventory: in the hand first, then the dig
          stowed
          (intent/assoc-intent world
                               :intent/stage :niche
                               :intent/child
                               {:intent/kind :equip
                                :intent/item (recipe/item-name stowed-item)
                                :intent/status :active})
          n (intent/assoc-intent world :intent/stage :niche :intent/child (intent/child-dig n))
          :else
          (if-let [walk (when-not retreated? (fresh-rock world))]
            (intent/assoc-intent world
                                 :intent/stage :retreat
                                 :intent/retreated? true
                                 :intent/child walk)
            (intent/fail world :no-spot)))))

(defn walk-off
  "One tick of the walk to fresh rock (the child :walk of fresh-rock): done, look for a spot
   again from the equip; failed, fail :no-spot."
  [world {:intent/keys [child]} event]
  (cond (intent/done? child)
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/stage :equip)
            (intent/dissoc-intent :intent/child))
        (intent/failed? child) (intent/fail (assoc world :player/controls {}) :no-spot)
        :else (intent/run-child world event)))

(defn dig-niche
  "One tick of digging the niche (the child :dig, or first the child :equip of its tool from the
   main inventory, which done starts the dig of the niche found again, the tool now in the
   hotbar); done and the cell confirmed by the server
   (terrain/confirmed?), or the server silent for answer-ms since the dig's FINISH, equip again
   (the dig chose its own held slot) and look for the spot; failed, fail with its reason. A
   loaded server acks the FINISH at once and breaks the block seconds later, by its own clock
   (vanilla's delayed destroy): race 38477665442 bots Clj_r01 and Clj_r07 placed into the
   niche a second after the ack, the server answered with the stone still there, and three
   such placements failed the plan :rejected."
  [world {:intent/keys [child]} event]
  (cond (and (= :equip (:intent/kind child)) (intent/done? child))
        (if-let [n (niche world)]
          (intent/assoc-intent world :intent/child (intent/child-dig n))
          (intent/fail world :no-spot))
        (and (intent/done? child)
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

(defn give-up
  "Back to :spot with target among the cells refused (:intent/refused), so the next spot is
   another one: the server answered the placement with the cell still empty, which is a body
   standing in it, not a block (the game refuses a block into any player, its own included).
   Race 38515209959 runner 9 bot Clj_r17 (issue #106): bot Clj_r24, landed at the same cell,
   walked through the furnace's spot collecting the same drops, and the same spot was sent
   three times in two seconds, :rejected. A placement refused with a block in the cell (the
   stone of a niche put back) is still :rejected at once."
  [world target refused]
  (-> world
      (intent/assoc-intent :intent/stage :spot :intent/refused (conj refused target))
      (intent/dissoc-intent :intent/target
                            :intent/against :intent/click-face
                            :intent/sequence :intent/sent-at)
      (game/say (str "place: " target " refused with nothing in it, another spot"))))

(defmethod place-stage :sent
  ;; a chosen cell the server refused with nothing in it is given up for another (give-up), up
  ;; to max-refusals; a given cell (a build's) is judged by the server alone
  [world {:intent/keys [item target sequence sent-at refused] at :intent/cell :or {refused #{}}}]
  (let [placed (terrain/block-at world target)
        answered? (and (>= (or (:stats/last-ack world) -1) sequence)
                       (> (- (now world) sent-at) 500))]
    (cond (= item (blocks/name-of placed)) (intent/done world) ; any state of the block: a furnace faces as the server chose
          (and answered? (nil? at) (blocks/replaceable? placed) (< (count refused) max-refusals))
          (give-up world target refused)
          answered? (intent/fail world :rejected)
          (> (- (now world) sent-at) answer-ms) (intent/fail world :no-answer)
          :else world)))

(defmethod intent/run :place
  [world i event]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip))
        world (assoc world :plan/intent i)]
    (case (:intent/stage i)
      :niche (dig-niche world i event)
      :retreat (walk-off world i event)
      (case (window/waiting world i)
        :overdue (window/lost world i :equip)
        :waiting world
        (if (:intent/resync i)
          (window/resync world (window/view world :inventory))
          (place-stage world i))))))

;; ---------------------------------------------------------------- equip

(defmethod intent/run :equip
  ;; {:intent/kind :equip :intent/item :stone_pickaxe}: put the item in the hand (equip), done
  ;; once it is there; a tool in the main inventory is swapped into the held slot with a click
  [world {:intent/keys [item stage] :as i} _]
  (let [world (assoc world :plan/intent i)]
    (cond (= :held stage) (intent/done world)
          :else
          (case (window/waiting world i)
            :overdue (window/lost world i :equip)
            :waiting world
            (if (:intent/resync i)
              (window/resync world (window/view world :inventory))
              (equip world item :held))))))

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
