(ns clojurecraft.make
  "The needs planner and the acts of the goal table.

   A need is a key and an amount: :item/<name> n and :tag/<item-tag> n are counted in the
   inventory, :block/<name> :near is a remembered block within reach of a walk, and :fuel/ticks
   n is fuel that burns n game ticks (a smelt's), counted from what the inventory would burn.
   A goal is met when its provides are held. When it is not, the planner walks back through
   producer rows - the table's own (gather a log, place a table) and one generated row per
   recipe and per furnace recipe - until it reaches a row whose needs are all met, and acts on
   it. Every tick this is re-derived from the inventory and the world, so a lost or consumed
   item is simply planned for again; needs are netted against one working inventory so two
   needs never count the same item twice.

   Requiring this namespace registers the :needs planner, the :provided? predicate and the
   :gather, :place, :craft and :smelt acts."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.craft]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.smelt :as smelt]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

;; the :mine act lives in clojurecraft.stone, required by main (and by tests that plan for stone)

(def table-search 24)                 ; how far a remembered crafting table counts as :near
(def block-window {:crafting_table :table :furnace :furnace}) ; the window a placed block opens
(def max-depth 12)                    ; item needs the needs planner may chain before it is :stuck
(def fetching-acts #{:gather :mine :scoop}) ; acts that go out for what is not held: never done for a spare

;; ---------------------------------------------------------------- needs as data

(def tag-named
  "An item set → the shortest tag name with exactly that set, so rows read :tag/planks."
  (reduce (fn [m [t s]]
            (if (and (contains? m s) (<= (count (name (m s))) (count (name t)))) m (assoc m s t)))
          {}
          recipe/tags))

(defn need-key
  "The data key for an ingredient set: :tag/<name>, :item/<name>, or the set itself."
  [s]
  (cond (tag-named s) (keyword "tag" (name (tag-named s)))
        (= 1 (count s)) (keyword "item" (name (first s)))
        :else s))

(defn block-need?
  "Is need key k a :block/<name> :near need (not a counted item, tag or item set)?"
  [k]
  (and (keyword? k) (= "block" (namespace k))))

(defn fuel-need? "Is need key k a :fuel/ticks need?" [k] (= k :fuel/ticks))

(defn need-set
  "The item set a counted need key stands for (nil for a :block/ or :fuel/ticks need)."
  [k]
  (cond (set? k) k
        (= "item" (namespace k)) #{(keyword (name k))}
        (= "tag" (namespace k)) (recipe/tags (keyword (name k)))
        :else nil))

(defn craft-row
  "A recipe as a goal row: its ingredients as needs, its result as what it provides, and a
   crafting table nearby when it does not fit the 2x2 grid."
  [r]
  {:goal/id (keyword "craft" (name (:recipe/id r)))
   :goal/priority 10
   :goal/needs (cond-> (into (array-map) (for [[s n] (recipe/needs r)] [(need-key s) n]))
                 (not (recipe/fits? r 2)) (assoc :block/crafting_table :near))
   :goal/provides {(keyword "item" (name (:recipe/result r))) (:recipe/count r)}
   :goal/done? :provided?
   :goal/act :craft
   :goal/recipe (:recipe/id r)})

(def craft-rows (mapv craft-row recipe/recipes))

(defn smelt-row
  "A furnace recipe as a goal row: its input and the fuel to cook it as needs (after the input,
   so the input is never counted as its own fuel), a furnace nearby, its result as what it
   provides."
  [r]
  {:goal/id (keyword "smelt" (name (:smelt/id r)))
   :goal/priority 10
   :goal/needs
   (array-map (need-key (:smelt/input r)) 1 :fuel/ticks (:smelt/ticks r) :block/furnace :near)
   :goal/provides {(keyword "item" (name (:smelt/result r))) (:smelt/count r)}
   :goal/done? :provided?
   :goal/act :smelt
   :goal/smelt (:smelt/id r)})

(def smelt-rows (mapv smelt-row recipe/smelting))

(def producers
  "table → every row that can provide something: the table's live acting rows, then one per
   recipe and one per furnace recipe. Memoized: a table is a value, so its producers are too."
  (memoize (fn [table]
             (-> (filterv #(and (:goal/act %) (plan/live? %)) table)
                 (into craft-rows)
                 (into smelt-rows)))))

(def by-item
  "table → {item name → producer rows that provide it}. Memoized like producers."
  (memoize (fn [table]
             (reduce (fn [m row]
                       (reduce (fn [m k]
                                 (reduce #(update %1 %2 (fnil conj []) row) m (or (need-set k) [])))
                               m
                               (keys (:goal/provides row))))
                     {}
                     (producers table)))))

;; ---------------------------------------------------------------- the world as needs see it

(defn counts "Held items by name." [world] (recipe/counts (:player/inventory world)))

(defn near
  "The remembered position of a placed block of this name (a table, a furnace: memory's
   placed-kinds) within table-search, or nil. A position the plan blacklisted (a walk to it
   failed) does not count, so an unreachable table is replaced by a new one instead of walked
   at until the plan fails."
  [world block]
  (when (contains? memory/placed-kinds block)
    (memory/nearest-of (game/eye world)
                       table-search
                       (remove (or (:plan/blacklist world) #{})
                               (memory/positions-now world (blocks/states-of block))))))

;; ---------------------------------------------------------------- the planner

(declare resolve-need)

(defn resolve-holds
  "[counts action] for what a row must hold but does not spend (:goal/holds, a tool): each is
   resolved once, however many times the row runs, and the count is handed back unconsumed,
   so three cobblestone need one pickaxe, not three."
  [world table counts row path]
  (reduce (fn [[c _] [k n]]
            (let [[_ a] (resolve-need world table c k n path)] (if a (reduced [c a]) [c nil])))
          [counts nil]
          (:goal/holds row)))

(defn fetch?
  "Would action send the bot out for something: :stuck, or a row whose act gathers, mines or
   scoops? A scoop goes out as far as a mine does: the bucket is crafted at the furnace down the
   mine and the water is up at the surface."
  [action]
  (or (= :stuck action) (contains? fetching-acts (:goal/act action))))

(defn spare-worth?
  "Is action worth doing to keep a spare tool? Not :stuck, and not a mine. A gather is worth it
   when a log can be started now (gather-next returns an intent): a walk to a trunk, or the
   stairs up to the trees, while a pickaxe is still held. A walk up the shaft already dug
   (:intent/for :opening) is not: it reaches the sky, not a tree, and from that mouth the trunk
   may still have no route. Full run f277605 held one stone pickaxe, flint and steel and a water
   bucket, and spent five hours walking out for the spare and cutting back down. A gather that
   would wait :no-log is not either, which is the open sky with no tree remembered. Gym batch
   iron-a7 runs 1-3, deep in the mine with a working pickaxe and no wood, waited :no-log for a
   second pickaxe. Full run 068485c kept mining on its last stone pickaxe until it broke at y 16,
   then the wooden one broke on the stairs and three :no-pickaxe failures ended the plan."
  [world action]
  (cond (or (nil? action) (= :stuck action) (= :mine (:goal/act action))) false
        (= :gather (:goal/act action))
        (let [n (wood/gather-next world)] (and (:intent/kind n) (not= :opening (:intent/for n))))
        :else true))

(defn resolve-spares
  "[counts action] for the tools a row keeps one more of than it holds (:goal/spares, beside
   :goal/holds): planned when spare-worth? says the producer can be done. A mine is never one.
   A gather is one only when a log can be started now."
  [world table counts row path]
  (reduce (fn [[c _] [k n]]
            (let [[_ a] (resolve-need world table c k (+ n (get (:goal/holds row) k 0)) path)]
              (if (and a (spare-worth? world a)) (reduced [c a]) [c nil])))
          [counts nil]
          (:goal/spares row)))

(defn resolve-row
  "Try to get times × row done: [counts row-to-act-on] (a producer whose needs are met, maybe
   this row), or [counts :stuck]. What the row holds is resolved once, then its spares
   (resolve-spares); what it needs, times over."
  [world table counts row times path]
  (let [[c a] (resolve-holds world table counts row path)
        [c a] (if a [c a] (resolve-spares world table counts row path))
        [c a] (if a
                [c a]
                (reduce (fn [[c _] [k n]]
                          (let [[c a]
                                (resolve-need world table c k (if (number? n) (* n times) n) path)]
                            (if a (reduced [c a]) [c nil])))
                        [c nil]
                        (:goal/needs row)))]
    (cond (= a :stuck) [counts :stuck]
          a [c a]
          :else [c row])))

(defn craft-now
  "[counts row] when a craft row asked for n runs (n > 1) would first send the bot elsewhere
   (action, a producer further down) but one run of it can be made from what is held: craft what
   can be crafted now, then fetch the rest. nil otherwise. Only crafts: a smelt is batched on
   purpose, one trip to the furnace for all the ore."
  [world table counts row n action path]
  (when (and (= :craft (:goal/act row)) (> n 1) (map? action) (not= action row))
    (let [[c a] (resolve-row world table counts row 1 path)] (when (= a row) [c a]))))

(defn first-resolved
  "[counts action] for the first of rows that can be done (times-of row) times, trying them in
   order (a craft that can run once now goes first, craft-now), or [counts :stuck] when none can."
  [world table counts rows times-of path]
  (or (some (fn [row]
              (let [n (times-of row)
                    [c a] (resolve-row world table counts row n path)]
                (when (not= a :stuck) (or (craft-now world table counts row n a path) [c a]))))
            rows)
      [counts :stuck]))

(defn yield
  "How many items one run of a producer row provides."
  [row]
  (val (first (:goal/provides row))))

(defn producer-order
  "How the producers of an item are tried: the one whose own needs the working counts already
   cover most first, then one that needs no crafting table, then the larger yield."
  [counts row]
  [(- (reduce +
              (for [[k n] (:goal/needs row) :when (number? n)] (recipe/have counts (need-set k)))))
   (contains? (:goal/needs row) :block/crafting_table) (- (yield row))])

(defn resolve-block-need
  "[counts action] for a :block/<name> :near need: met (nil) when such a block is remembered
   near, else the first producer row that provides it and can be done once, or :stuck."
  [world table counts k path]
  (if (near world (keyword (name k)))
    [counts nil]
    (first-resolved world
                    table
                    counts
                    (for [row (producers table) :when (contains? (:goal/provides row) k)] row)
                    (constantly 1)
                    path)))

(defn cycle?
  "Would resolving the item set s go round in a circle: is one of its items already being
   resolved higher up the path (an ingot from a block made of ingots, from nuggets cut from an
   ingot)?"
  [path s]
  (boolean (some (fn [above] (some above s)) path)))

(defn resolve-item-need
  "[counts action] for a counted need (:item/<name> n or :tag/<tag> n): met (nil, the items
   consumed from counts) when counts hold n, else what is held is consumed and the producers
   of the rest are tried in producer-order, each for as many runs as the rest needs. path is
   the item sets being resolved above this one; :stuck past max-depth of them, when this need
   is one of them (cycle?), or when no producer can be done."
  [world table counts k n path]
  (let [s (need-set k)
        h (recipe/have counts s)]
    (cond (>= h n) [(recipe/consume counts s n) nil]
          (or (> (count path) max-depth) (cycle? path s)) [counts :stuck]
          :else
          (let [counts (recipe/consume counts s h)
                missing (- n h)]
            (first-resolved world
                            table
                            counts
                            (sort-by #(producer-order counts %)
                                     (distinct (mapcat (by-item table) s)))
                            (fn [row] (long (math/ceil (/ missing (yield row)))))
                            (conj path s))))))

(def coal-ticks (smelt/burn-ticks :coal))       ; game ticks one coal burns
(def plank-ticks (smelt/burn-ticks :oak_planks)) ; game ticks one plank burns

(def fuel-sources
  "Where fuel the inventory lacks comes from, in order, as [need ticks-one-burns]: coal (mined
   beside the iron), then planks (from logs). Charcoal is not made: it burns wood to save wood."
  [[:item/coal coal-ticks] [:tag/planks plank-ticks]])

(defn burn
  "[counts' ticks-left]: the fuel in counts that burns ticks taken out in the order the smelt
   intent burns it (smelt/burn-order, coal first), whole items only; ticks-left is what it
   could not cover."
  [counts ticks]
  (let [[counts left] (reduce (fn [[counts left] [n c]]
                                (if (<= left 0)
                                  (reduced [counts left])
                                  (let [b (smelt/burn-ticks n)
                                        k (min c (long (math/ceil (/ left b))))]
                                    [(update counts n - k) (- left (* k b))])))
                              [counts ticks]
                              (smelt/fuels counts nil))]
    [counts (max 0 left)]))

(def planks-per-log 4)                ; a log crafts into four planks
(def log-reserve 1)                   ; logs never counted as fuel: the sticks and table a new pickaxe needs

(defn wood-covers?
  "Do the logs in counts, but log-reserve, make the planks that burn ticks? Then they are the fuel
   to use before any coal is mined: the twelfth live run held two logs, eight planks' worth of
   fire, and tunnelled thirteen minutes at y 0 for coal until the trial timed out. The reserve
   is wood for a new pickaxe: gym batch iron-a6 runs 2 and 5 broke theirs climbing out with no
   wood left."
  [counts ticks]
  (let [planks (long (math/ceil (/ ticks plank-ticks)))]
    (>= (- (recipe/have counts (recipe/tags :logs)) log-reserve)
        (long (math/ceil (/ planks planks-per-log))))))

(defn resolve-fuel-need
  "[counts action] for a :fuel/ticks n need: met (nil) when the fuel in counts burns n ticks,
   taken out as the smelt will burn it; else what is held is taken and the rest is planned from
   the first of fuel-sources that can be done (planks first when held logs make them,
   wood-covers?), or :stuck."
  [world table counts n path]
  (let [[counts left] (burn counts n)]
    (if (<= left 0)
      [counts nil]
      (or (some (fn [[k b]]
                  (let [[c a]
                        (resolve-item-need world table counts k (long (math/ceil (/ left b))) path)]
                    (when (not= a :stuck) [c a])))
                (cond-> fuel-sources (wood-covers? counts left) reverse))
          [counts :stuck]))))

(defn resolve-need
  "[counts' action] for a need: action nil when it is already met (counts' has it consumed), a
   producer row to act on, or :stuck when nothing in the table can provide it. path is the
   item sets being resolved above it, [] for a goal's own provides."
  [world table counts k n path]
  (cond (block-need? k) (resolve-block-need world table counts k path)
        (fuel-need? k) (resolve-fuel-need world table counts n path)
        :else (resolve-item-need world table counts k n path)))

(defn next-row
  "The producer row of table to act on next for a goal, nil when its provides are all held, or
   :stuck."
  [world goal table]
  (second (reduce (fn [[counts _] [k n]]
                    (let [[counts a] (resolve-need world table counts k n [])]
                      (if a (reduced [counts a]) [counts nil])))
                  [(counts world) nil]
                  (:goal/provides goal))))

(def table-drops #{(recipe/item-id :crafting_table)}) ; what digging a placed table drops: the table

(defn take-table
  "A dig of the bot's placed crafting table, to carry it on, when row goes out for something
   (fetch?), no table is held and the remembered one is within reach; nil otherwise. A table
   left behind is a log spent on the next: gym batch iron-a7 run 1 crafted three on its way
   down the mine and had no wood left for a pickaxe. Race 38477665442 bots Clj_r19, Clj_r23 and
   Clj_r06 crafted the bucket at the furnace, climbed out for the water and left the table
   there; with sticks and cobblestone in hand and no table, none could make the pickaxe the
   climb or the gravel tunnels wore out (issues #39 and #67)."
  [world row]
  (let [pos (near world :crafting_table)]
    (when (and pos
               (fetch? row)
               (zero? (get (counts world) :crafting_table 0))
               (<= (physics/distance (game/eye world) (physics/centre pos)) intent/reach))
      {:intent/kind :dig :intent/target pos :intent/for :take-table})))

(defn decide
  "The next intent toward goal: continue a log chain or a table pick-up in flight, else take the
   table along (take-table) or act on the row the needs planner reaches through table."
  [world goal table]
  (or (wood/continue-gather world)
      (intent/chain-next (:plan/last world) :take-table table-drops {})
      (let [row (next-row world goal table)]
        (cond (nil? row) nil
              (= :stuck row) {:plan/wait :stuck}
              :else (or (take-table world row) (plan/act world row))))))

(defmethod plan/next-intent :needs [world goal table] (decide world goal table))

(defmethod plan/done-by :provided?
  [world goal]
  (let [c (counts world)]
    (every? (fn [[k n]]
              (if (block-need? k)
                (some? (near world (keyword (name k))))
                (>= (recipe/have c (need-set k)) n)))
            (:goal/provides goal))))

;; ---------------------------------------------------------------- acts

(defmethod plan/act :gather [world _] (wood/gather-next world))

(defmethod plan/act :place
  [_ row]
  (cond-> {:intent/kind :place :intent/item (:goal/item row)}
    (:goal/passage-ok? row) (assoc :intent/passage-ok? true)))

(defn at-container
  "The intent i once the window of the placed block (a table, a furnace) is open; before that,
   open the remembered one within reach, walk to it, or wait for one (reason wait)."
  [world block wait i]
  (let [pos (near world block)]
    (cond (= (:menu-type (inventory/layouts (block-window block)))
             (get-in world [:window/open :window/menu-type]))
          i
          (and pos (<= (physics/distance (game/eye world) (physics/centre pos)) place/open-reach))
          {:intent/kind :open-container :intent/target pos}
          pos {:intent/kind :walk :intent/target pos}
          :else {:plan/wait wait})))

(defmethod plan/act :craft
  [world {:goal/keys [recipe]}]
  (if (recipe/fits? (recipe/by-id recipe) 2)
    {:intent/kind :craft :intent/recipe recipe :intent/window :inventory}
    (at-container world
                  :crafting_table
                  :no-table
                  {:intent/kind :craft :intent/recipe recipe :intent/window :table})))

(defmethod plan/act :smelt
  [world {:goal/keys [smelt]}]
  (let [n (smelt/batch (counts world) (recipe/smelt-by-id smelt))]
    (if (pos? n)
      (at-container world
                    :furnace
                    :no-furnace
                    {:intent/kind :smelt :intent/smelt smelt :intent/count n})
      {:plan/wait :no-fuel})))
