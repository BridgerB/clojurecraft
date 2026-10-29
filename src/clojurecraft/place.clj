(ns clojurecraft.place
  "Right-clicking blocks: placing a held block, and opening a container.

     {:intent/kind :place :intent/item :crafting_table}
     {:intent/kind :open-container :intent/target [x y z]}

   Both send use-item-on and judge success only by what the server reports: a block update
   that shows the block (placing), or an open-screen with the window's contents (opening).
   Nothing is predicted; a rejected placement comes back as a block update with the old state
   and an ack, and the intent fails rather than believing the block is there."
  (:require [clojure.math :as math]
            [clojure.string :as str]
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
(def max-nudges 40)                   ; ticks a placement into a given cell steps off its edge for before it asks the server anyway
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

(defn open-drop?
  "Is cell n clear over a drop: no floor under it and no landing within path/max-fall? Pure."
  [world n]
  (and (= :clear (path/classify world n))
       (not (path/floor? world (update n 1 dec)))
       (nil? (path/landing world n))))

(defn drop-beside?
  "Is a cell beside cell [x y z] at its height open over a drop (open-drop?)? A block placed at
   cell and dug again drops its item there to roll off. Sim world R2/12 (issue #338) placed the
   table on a one-block pillar over a cavern, dug it to carry it on, and the bot walked off the
   pillar after the drop: thirty blocks, the plan failed :no-footing."
  [world [x y z]]
  (boolean (some (fn [[dx dz]] (open-drop? world [(+ x dx) y (+ z dz)])) sides)))

(defn drop-beyond?
  "Is the cell past [x y z] on the line from the feet [fx fy fz] through it open over a drop
   (open-drop?): where a body that walks onto the cell to pick up what is dug there carries on.
   A cell beside a drop only at its sides is safe to pick up from along a floor that goes on: a
   one-block bridge over lava in a fortress's tunnel had a drop beside every cell of it, and with
   drop-beside? alone a table, a furnace or a gravel found no spot on it (issues #559, #560:
   :no-spot at the stage that crafts the pickaxe). Pure; false for a cell straight over or
   under the feet."
  [world [fx _ fz] [x y z]]
  (let [sx (Long/signum (long (- x fx)))
        sz (Long/signum (long (- z fz)))]
    (boolean (or (and (not (zero? sx)) (open-drop? world [(+ x sx) y z]))
                 (and (not (zero? sz)) (open-drop? world [x y (+ z sz)]))))))

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
   standing in it (give-up), and a cell with a drop beside it (drop-beside?; with along? only
   one with a drop past it on the line from the feet, drop-beyond?: the fallback when every
   cell has a drop beside it). Pure; nil when there is none."
  ([world] (spot world false))
  ([world passage-ok?] (spot world passage-ok? #{}))
  ([world passage-ok? refused] (spot world passage-ok? refused false))
  ([world passage-ok? refused along?]
   (let [[px py pz] (:player/pos world)
         fx (long (math/floor px))
         fy (long (math/floor py))
         fz (long (math/floor pz))
         eye (game/eye world)]
     (first
      (for [[dx dy dz] around
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
                       (not (if along?
                              (drop-beyond? world [fx fy fz] target)
                              (drop-beside? world target)))
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

(defn tool-slot?
  "Does hotbar slot p hold a tool or a sword (blocks/tool, blocks/tool-kinds' sword)? What a swap
   into the hand must not push out of the hotbar."
  [world p]
  (let [item (some-> (get-in world [:player/inventory p :item])
                     recipe/item-name
                     name)]
    (boolean (and item
                  (or (blocks/tool (get-in world [:player/inventory p :item]))
                      (str/ends-with? item "_sword"))))))

(defn swap-slot
  "The hotbar slot a stack from the main inventory is swapped into to be held: the held slot when
   it holds no tool, else the first hotbar slot with none, else the held slot. A meal swapped into
   the hand of a bot holding its pickaxe sent the pickaxe to the main inventory, where no dig looks:
   gym chain-w9 run 3 (plan run 40851746139) dug the way down a 28-high pillar by hand, ten seconds
   a block, and the dragon killed it on the way."
  [world]
  (let [held (:player/held-slot world)]
    (if (tool-slot? world held)
      (or (first (remove #(tool-slot? world %) (range inventory/hotbar-slots))) held)
      held)))

(defn equip
  "One step toward holding item (a name) in the hand, for an intent whose next stage is then:
   already held, go on; in the hotbar, select it and go on; in the main inventory, swap it into
   a hotbar slot (swap-slot: the held one unless that holds a tool) with a window-0 click and stay
   (the answer moves it); held nowhere, fail
   :not-held. With a container open, the swap waits a tick for it to close: the server ignores a
   window-0 click while a container is its open menu, as a craft in window 0 closes one first.
   Race 38805920624 runner 5 (issues #131, #132): 20 bots whose smelt failed :not-cooked left
   the furnace open, and every swap into the hand after it went unanswered until :stale-window
   ended the plan."
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
          (:window/open world)
          (-> world
              (game/emit {:packet/name :container-close
                          :window-id (get-in world [:window/open :window/id])})
              (dissoc :window/open))
          :else
          (window/click world
                        (window/view world :inventory) ; swap into a hotbar slot
                        (inventory/swap-click
                         (inventory/player->window-slot (inventory/layouts :inventory) p)
                         (swap-slot world))))))

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

(defn powder-snow-at?
  "Is the block at pos loaded powder snow: no collision for the player, so it is not solid, yet
   no block can be placed into it, and the bare hand breaks it?"
  [world pos]
  (= :powder_snow
     (some-> (terrain/block-at world pos)
             blocks/name-of)))

(defn unplaceable-in?
  "Does the loaded cell hold a block that is not solid and that no block is placed into
   (blocks/placeable-into?): powder snow, a flower, a bush? The server refuses a placement into
   it, the hand breaks it, and a given cell holding one is dug first. Real race 39807290314 bot
   Clj_r18 (issue #279) stood on a mountainside to cast a portal; the cell of its first floor
   held powder snow, and the cobblestone for it was refused three times, :rejected."
  [world cell]
  (let [id (terrain/block-at world cell)]
    (boolean (and id (not (blocks/solid? id)) (not (blocks/placeable-into? id))))))

(defn dug-spot
  "[target support] for the niche the bot has cut (the intent's :intent/niche) once it is empty:
   the cell itself, against the solid under it, where spot would refuse it as a passage (a niche
   under powder snow is one the feet could stand in, and a way through nothing). nil when no niche
   was cut, the cell is not yet air the server confirmed, a body or the refused cells are in the
   way, or it is out of reach."
  [world cell refused]
  (let [support (some-> cell
                        (update 1 dec))
        t (some->> cell
                   (terrain/block-at world))
        s (some->> support
                   (terrain/block-at world))]
    (when (and t
               s
               (not (contains? refused cell))
               (blocks/replaceable? t)
               (terrain/confirmed? world cell)
               (blocks/solid? s)
               (not (contains? memory/placed-kinds (blocks/name-of s)))
               (not (inside-player? world cell))
               (<= (physics/distance (game/eye world) (physics/centre cell)) place-reach))
      [cell support])))

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
   tunnel dug through it). Powder snow may be the niche itself or lie over it: no block can be
   placed into it, so it is dug out first like stone (race 39031393203 bot Clj_r12, issue #94: a
   pocket under a powder-snow field, every cell beside the feet powder snow or stone under
   powder snow, and the table failed :no-spot three times)."
  [world]
  (let [[fx fy fz] (intent/feet world)
        home (:plan/go-at world)]
    (first
     (for [dy [0 1]
           [dx dz] (cond->> sides
                     home
                     (sort-by (fn [[dx dz]]
                                (physics/horizontal-distance [(+ fx dx 0.5) 0 (+ fz dz 0.5)]
                                                             home))))
           :let [c [(+ fx dx) (+ fy dy) (+ fz dz)]]
           :when (and (or (solid-at? world c) (powder-snow-at? world c))
                      (solid-at? world (update c 1 dec))
                      (or (solid-at? world (update c 1 inc))
                          (powder-snow-at? world (update c 1 inc)))
                      (not (blocks/falls? (terrain/block-at world (update c 1 inc))))
                      (not (intent/exposes-fluid? world c))
                      (droppable? world (terrain/block-at world c))
                      (not (contains? (into intent/never-dug memory/placed-kinds)
                                      (blocks/name-of (terrain/block-at world c)))))]
       c))))

(defn covered-spot
  "A cell round the feet to dig clear for a spot (the niche machinery digs it, dug-spot
   places into it): a snow layer (:snow) or leaves over a solid support that is not a block
   a click opens, not a passage, with no drop beside it, within reach, not refused and not
   inside the player, where spot finds only the cover (neither is replaceable) and niche
   finds no wall. Real race 40028266922 bot Clj_r04 (issue #68) stood on snow-covered
   grass and its table found no spot; race 40029764849 bot Clj_r03 (issue #574) stood on a
   birch crown, every cell round it leaves or air over air, and its furnace found none.
   Pure; nil when there is none."
  [world refused]
  (let [[px py pz] (:player/pos world)
        fx (long (math/floor px))
        fy (long (math/floor py))
        fz (long (math/floor pz))
        eye (game/eye world)]
    (first
     (for [[dx dy dz] around
           :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                 support [(+ fx dx) (+ fy dy -1) (+ fz dz)]
                 t (terrain/block-at world target)
                 s (terrain/block-at world support)]
           :when (and t
                      s
                      (not (contains? refused target))
                      (or (= :snow (blocks/name-of t)) (blocks/leaves? t))
                      (blocks/solid? s)
                      (not (contains? memory/placed-kinds (blocks/name-of s)))
                      (not (passage? world target))
                      (not (inside-player? world target))
                      (not (drop-beside? world target))
                      (droppable? world t)
                      (<= (physics/distance eye (physics/centre target)) place-reach))]
       target))))

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

(defn water-spot
  "The water cell beside the feet (the ring of cells touching them, around) to place a block into
   when there is no dry spot: a water cell (a block placed into a source or a flow takes its place,
   blocks/placeable-into?) with a solid neighbour to click (support), not refused, not inside
   the player, within reach. nil when none. Sim race 39601680523 world R1/10 (issue #148): the
   bot stood on the one cobblestone it had pillared up in the open sea, water on every side
   and no pickaxe, and its table failed :no-spot three times, a pickaxe short of the bucket it
   held for the water all round it."
  [world refused]
  (let [[px py pz] (:player/pos world)
        fx (long (math/floor px))
        fy (long (math/floor py))
        fz (long (math/floor pz))
        eye (game/eye world)]
    (first (for [[dx dy dz] around
                 :when (= 1 (max (abs dx) (abs dz)))
                 :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                       t (terrain/block-at world target)]
                 :when (and t
                            (not (contains? refused target))
                            (= :liquid (blocks/type-of t))
                            (= :water (terrain/fluid-kind world target))
                            (terrain/confirmed? world target)
                            (not (inside-player? world target))
                            (<= (physics/distance eye (physics/centre target)) place-reach)
                            (support world target))]
             target))))

(defn nudge-off
  "The world with one tick's controls toward the middle of the feet's cell and :intent/nudges one
   more: a given cell (a stair's seal against the water beside the feet) that the body clips at the
   edge, the feet a few hundredths over the line, is a placement the server refuses with the cell
   empty (the game refuses a block into any player), as often as it is tried. Race 39095700271
   runner 3 bot Clj_r04 (issue #430) stood at x 2838.74 and sealed the water at 2839 three times
   in turn, each stair :boxed."
  [world nudges]
  (-> world
      (assoc :player/controls (intent/toward world (physics/centre (intent/feet world)) 0.0))
      (intent/assoc-intent :intent/nudges (inc nudges))))

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
  ;; a given cell (:intent/cell, a build's) against a solid neighbour (dug first when it holds powder
  ;; snow or the like, unplaceable-in?); else the niche already cut
  ;; (:intent/niche, dug-spot), else a plain spot, else a niche cut in the wall, else (passage-ok?)
  ;; a spot in the passage: a table or a gravel to dig again leaves the way open when the bot goes on;
  ;; a spot with a drop beside it but none past it on the line from the feet (drop-beyond?);
  ;; else snow or leaves over solid ground dug clear (covered-spot, by the niche machinery)
  [world
   {:intent/keys [passage-ok? refused retreated? nudges]
    at :intent/cell
    cut :intent/niche
    :or {refused #{} nudges 0}}]
  (let [[against face :as given] (when at (support world at))
        found (when-not at (or (dug-spot world cut refused) (spot world false refused)))
        n (when-not (or at found) (niche world))
        [_ stowed-item :as stowed] (when n (intent/stowed-tool world (terrain/block-at world n)))
        [target from] (or found
                          (when (and (nil? at) (nil? n) passage-ok?) (spot world true refused))
                          ;; every cell has a drop beside it and no wall to cut: along a floor
                          (when (and (nil? at) (nil? n))
                            (spot world (boolean passage-ok?) refused true)))
        covered (when (and (nil? at) (nil? n) (nil? target)) (covered-spot world refused))
        wet (when (and retreated? (nil? at) (nil? n) (nil? target)) (water-spot world refused))
        [wet-against wet-face] (when wet (support world wet))]
    (cond (and at
               (unplaceable-in? world at)
               (<= (physics/distance (game/eye world) (physics/centre at)) place-reach))
          (intent/assoc-intent world
                               :intent/stage :niche
                               :intent/niche at
                               :intent/child (intent/child-dig at))
          (and given (inside-player? world at) (< nudges max-nudges)) (nudge-off world nudges)
          given (settle-at world at against face)
          at (intent/fail world :no-support)
          target (settle-at world target from :up)
          ;; the tool the niche needs is in the main inventory: in the hand first, then the dig
          stowed
          (intent/assoc-intent world
                               :intent/stage :niche
                               :intent/niche n
                               :intent/child
                               {:intent/kind :equip
                                :intent/item (recipe/item-name stowed-item)
                                :intent/status :active})
          n
          (intent/assoc-intent world
                               :intent/stage :niche
                               :intent/niche n
                               :intent/child (intent/child-dig n))
          ;; snow or leaves over solid ground, dug clear and placed into (covered-spot)
          covered
          (intent/assoc-intent world
                               :intent/stage :niche
                               :intent/niche covered
                               :intent/child (intent/child-dig covered))
          ;; the water cell is a given cell from here: the server judges it, as it does a build's
          wet
          (-> world
              (settle-at wet wet-against wet-face)
              (intent/assoc-intent :intent/cell wet))
          :else
          (if-let [walk (when-not retreated? (fresh-rock world))]
            (intent/assoc-intent world
                                 :intent/stage :retreat
                                 :intent/retreated? true
                                 :intent/child walk)
            (intent/fail world :no-spot)))))

(defn walk-off
  "One tick of the walk to fresh rock (the child :walk or :stairs-up of fresh-rock): done or
   failed, look for a spot again from the equip, this once (:intent/retreated?), where a spot
   in the water beside the feet counts (water-spot) and the next failure is :no-spot. A walk
   that failed used to fail the place at once: sim race 39601680523 world R1/10 (issue #148)
   stood on a pillar in the sea, the stairs up refused toward every heading, and the table
   failed :no-spot three times with the water beside it to be placed into."
  [world {:intent/keys [child]} event]
  (cond (and (intent/failed? child) (:player/in-water? world) (not= :swim-out (:intent/kind child)))
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent
             :intent/child
             {:intent/kind :swim-out :intent/for :retreat :intent/status :active}))
        (or (intent/done? child) (intent/failed? child))
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/stage :equip)
            (intent/dissoc-intent :intent/child))
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
   {:intent/keys [target against click-face since still nudges]
    at :intent/cell
    :or {click-face :up nudges 0}}]
  (let [look (physics/look-at (game/eye world) (physics/centre target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (cond (and (nil? at) (not (blocks/replaceable? (terrain/block-at world target))))
          (-> world
              (intent/assoc-intent :intent/stage :spot)
              (intent/dissoc-intent :intent/target :intent/against :intent/click-face))
          (and at (inside-player? world at) (< nudges max-nudges))
          (intent/assoc-intent world :intent/stage :spot)
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
