(ns clojurecraft.make
  "The needs planner and the acts of the goal table.

   A need is a key and an amount: :item/<name> n and :tag/<item-tag> n are counted in the
   inventory, :block/<name> :near is a remembered block within reach of a walk. A goal is met
   when its provides are held. When it is not, the planner walks back through producer rows -
   the table's own (gather a log, place a table) and one generated row per recipe - until it
   reaches a row whose needs are all met, and acts on it. Every tick this is re-derived from
   the inventory and the world, so a lost or consumed item is simply planned for again; needs
   are netted against one working inventory so two needs never count the same item twice.

   Requiring this namespace registers the :needs planner, the :provided? predicate and the
   :gather, :place and :craft acts."
  (:require [clojure.math :as math]
            [clojurecraft.craft]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

;; the :mine act lives in clojurecraft.stone, required by main (and by tests that plan for stone)

(def table-search 24)                 ; how far a remembered crafting table counts as :near
(def max-depth 8)                     ; producer rows the needs planner may chain before it is :stuck

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

(defn need-set
  "The item set a counted need key stands for (nil for a :block/ need)."
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

(def producers
  "table → every row that can provide something: the table's live acting rows, then one per
   recipe. Memoized: a table is a value, so its producers are too."
  (memoize (fn [table] (into (filterv #(and (:goal/act %) (plan/live? %)) table) craft-rows))))

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
  "The remembered position of a placed block of this name within table-search, or nil. A
   position the plan blacklisted (a walk to it failed) does not count, so an unreachable table
   is replaced by a new one instead of walked at until the plan fails."
  [world block]
  (when (= block :crafting_table)
    (memory/nearest-of (game/eye world)
                       table-search
                       (remove (or (:plan/blacklist world) #{})
                               (memory/positions-now world [memory/crafting-table])))))

;; ---------------------------------------------------------------- the planner

(declare resolve-need)

(defn resolve-holds
  "[counts action] for what a row must hold but does not spend (:goal/holds, a tool): each is
   resolved once, however many times the row runs, and the count is handed back unconsumed,
   so three cobblestone need one pickaxe, not three."
  [world table counts row depth]
  (reduce (fn [[c _] [k n]]
            (let [[_ a] (resolve-need world table c k n (inc depth))]
              (if a (reduced [c a]) [c nil])))
          [counts nil]
          (:goal/holds row)))

(defn resolve-row
  "Try to get times × row done: [counts row-to-act-on] (a producer whose needs are met, maybe
   this row), or [counts :stuck]. What the row holds is resolved once; what it needs, times
   over."
  [world table counts row times depth]
  (let [[c a] (resolve-holds world table counts row depth)
        [c a] (if a
                [c a]
                (reduce
                 (fn [[c _] [k n]]
                   (let [[c a]
                         (resolve-need world table c k (if (number? n) (* n times) n) (inc depth))]
                     (if a (reduced [c a]) [c nil])))
                 [c nil]
                 (:goal/needs row)))]
    (cond (= a :stuck) [counts :stuck]
          a [c a]
          :else [c row])))

(defn first-resolved
  "[counts action] for the first of rows that can be done (times-of row) times, trying them in
   order, or [counts :stuck] when none can."
  [world table counts rows times-of depth]
  (or (some (fn [row]
              (let [[c a] (resolve-row world table counts row (times-of row) depth)]
                (when (not= a :stuck) [c a])))
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
  [world table counts k depth]
  (if (near world (keyword (name k)))
    [counts nil]
    (first-resolved world
                    table
                    counts
                    (for [row (producers table) :when (contains? (:goal/provides row) k)] row)
                    (constantly 1)
                    depth)))

(defn resolve-item-need
  "[counts action] for a counted need (:item/<name> n or :tag/<tag> n): met (nil, the items
   consumed from counts) when counts hold n, else what is held is consumed and the producers
   of the rest are tried in producer-order, each for as many runs as the rest needs; :stuck
   past max-depth or when no producer can be done."
  [world table counts k n depth]
  (let [s (need-set k)
        h (recipe/have counts s)]
    (cond (>= h n) [(recipe/consume counts s n) nil]
          (> depth max-depth) [counts :stuck]
          :else
          (let [counts (recipe/consume counts s h)
                missing (- n h)]
            (first-resolved world
                            table
                            counts
                            (sort-by #(producer-order counts %)
                                     (distinct (mapcat (by-item table) s)))
                            (fn [row] (long (math/ceil (/ missing (yield row)))))
                            depth)))))

(defn resolve-need
  "[counts' action] for a need: action nil when it is already met (counts' has it consumed), a
   producer row to act on, or :stuck when nothing in the table can provide it."
  [world table counts k n depth]
  (if (= "block" (namespace k))
    (resolve-block-need world table counts k depth)
    (resolve-item-need world table counts k n depth)))

(defn next-row
  "The producer row of table to act on next for a goal, nil when its provides are all held, or
   :stuck."
  [world goal table]
  (second (reduce (fn [[counts _] [k n]]
                    (let [[counts a] (resolve-need world table counts k n 0)]
                      (if a (reduced [counts a]) [counts nil])))
                  [(counts world) nil]
                  (:goal/provides goal))))

(defn decide
  "The next intent toward goal: continue a log chain in flight, else act on the row the needs
   planner reaches through table."
  [world goal table]
  (or (wood/continue-gather world)
      (let [row (next-row world goal table)]
        (cond (nil? row) nil
              (= :stuck row) {:plan/wait :stuck}
              :else (plan/act world row)))))

(defmethod plan/next-intent :needs [world goal table] (decide world goal table))

(defmethod plan/done-by :provided?
  [world goal]
  (let [c (counts world)]
    (every? (fn [[k n]]
              (if (= "block" (namespace k))
                (some? (near world (keyword (name k))))
                (>= (recipe/have c (need-set k)) n)))
            (:goal/provides goal))))

;; ---------------------------------------------------------------- acts

(defmethod plan/act :gather [world _] (wood/gather-next world))

(defmethod plan/act :place [_ row] {:intent/kind :place :intent/item (:goal/item row)})

(defmethod plan/act :craft
  [world {:goal/keys [recipe]}]
  (if (recipe/fits? (recipe/by-id recipe) 2)
    {:intent/kind :craft :intent/recipe recipe :intent/window :inventory}
    (let [eye (game/eye world)
          table (near world :crafting_table)]
      (cond (= 12 (get-in world [:window/open :window/menu-type]))
            {:intent/kind :craft :intent/recipe recipe :intent/window :table}
            (and table (<= (physics/distance eye (mapv #(+ % 0.5) table)) place/open-reach))
            {:intent/kind :open-container :intent/target table}
            table {:intent/kind :walk :intent/target table}
            :else {:plan/wait :no-table}))))
