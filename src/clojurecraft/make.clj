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
  (:require [clojurecraft.craft]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.wood :as wood]))

(def table-search 24)                 ; how far a remembered crafting table counts as :near
(def open-reach 4.5)
(def max-depth 8)

;; ---------------------------------------------------------------- needs as data

(def tag-named
  "An item set → the shortest tag name with exactly that set, so rows read :tag/planks."
  (reduce (fn [m [t s]] (if (and (contains? m s) (<= (count (name (m s))) (count (name t)))) m (assoc m s t)))
          {} recipe/tags))

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
  "Every row that can provide something: the table's acting rows, then one per recipe."
  (into (filterv #(and (:goal/act %) (plan/live? %)) plan/goals) craft-rows))

(def by-item
  "item name → producer rows that provide it."
  (reduce (fn [m row]
            (reduce (fn [m k] (reduce #(update %1 %2 (fnil conj []) row) m (or (need-set k) [])))
                    m (keys (:goal/provides row))))
          {} producers))

(def by-block
  (group-by identity (for [row producers k (keys (:goal/provides row)) :when (= "block" (namespace k))] k)))

;; ---------------------------------------------------------------- the world as needs see it

(defn counts "Held items by name." [world] (recipe/counts (:player/inventory world)))

(defn near
  "The remembered position of a placed block of this name within table-search, or nil."
  [world block]
  (when (= block :crafting_table)
    (memory/nearest world (game/eye world) table-search memory/crafting-table?)))

(defn consume
  "Take up to n items from counts, drawing from the items of set s in sorted order; never
   below zero."
  [counts s n]
  (loop [counts counts [item & more] (sort s) n n]
    (if (or (zero? n) (nil? item))
      counts
      (let [k (min n (get counts item 0))]
        (recur (update counts item (fnil - 0) k) more (- n k))))))

;; ---------------------------------------------------------------- the planner

(declare resolve-need)

(defn resolve-row
  "Try to get times × row done: [counts row-to-act-on] (a producer whose needs are met, maybe
   this row), or [counts :stuck]."
  [world counts row times depth]
  (let [result (reduce (fn [[c _] [k n]]
                         (let [[c a] (resolve-need world c k (if (number? n) (* n times) n) (inc depth))]
                           (if a (reduced [c a]) [c nil])))
                       [counts nil] (:goal/needs row))
        [c a] result]
    (cond (= a :stuck) [counts :stuck]
          a [c a]
          :else [c row])))

(defn resolve-need
  "[counts' action] for a need: action nil when it is already met (counts' has it consumed), a
   producer row to act on, or :stuck when nothing in the table can provide it."
  [world counts k n depth]
  (if (= "block" (namespace k))
    (if (near world (keyword (name k)))
      [counts nil]
      (or (some (fn [row] (let [[c a] (resolve-row world counts row 1 depth)] (when (not= a :stuck) [c a])))
                (for [row producers :when (contains? (:goal/provides row) k)] row))
          [counts :stuck]))
    (let [s (need-set k)
          h (recipe/have counts s)]
      (cond
        (>= h n) [(consume counts s n) nil]
        (> depth max-depth) [counts :stuck]
        :else
        (let [counts (consume counts s h)
              missing (- n h)
              rows (->> (mapcat by-item s)
                        distinct
                        (sort-by (fn [row] [(- (reduce + (for [[nk nn] (:goal/needs row) :when (number? nn)]
                                                           (recipe/have counts (need-set nk)))))
                                            (contains? (:goal/needs row) :block/crafting_table)
                                            (- (val (first (:goal/provides row))))])))]
          (or (some (fn [row]
                      (let [per (val (first (:goal/provides row)))
                            times (long (Math/ceil (/ missing per)))
                            [c a] (resolve-row world counts row times depth)]
                        (when (not= a :stuck) [c a])))
                    rows)
              [counts :stuck]))))))

(defn next-row
  "The producer row to act on next for a goal, nil when its provides are all held, or :stuck."
  [world goal]
  (loop [counts (counts world) [[k n] & more] (seq (:goal/provides goal))]
    (if (nil? k)
      nil
      (let [[counts a] (resolve-need world counts k n 0)]
        (if a a (recur counts more))))))

(defn decide
  "The next intent toward goal: continue a log chain in flight, else act on the row the needs
   planner reaches."
  [world goal]
  (or (wood/continue-gather world)
      (let [row (next-row world goal)]
        (cond (nil? row) nil
              (= :stuck row) {:plan/wait :stuck}
              :else (plan/act world row)))))

(defmethod plan/next-intent :needs [world goal] (decide world goal))

(defmethod plan/done-by :provided? [world goal]
  (let [c (counts world)]
    (every? (fn [[k n]] (if (= "block" (namespace k))
                          (some? (near world (keyword (name k))))
                          (>= (recipe/have c (need-set k)) n)))
            (:goal/provides goal))))

;; ---------------------------------------------------------------- acts

(defmethod plan/act :gather [world _] (wood/gather-next world))

(defmethod plan/act :place [_ row] {:intent/kind :place :intent/item (:goal/item row)})

(defmethod plan/act :craft [world {:goal/keys [recipe]}]
  (if (recipe/fits? (recipe/by-id recipe) 2)
    {:intent/kind :craft :intent/recipe recipe :intent/window :inventory}
    (let [eye (game/eye world)
          table (near world :crafting_table)]
      (cond
        (= 12 (get-in world [:window/open :window/menu-type]))
        {:intent/kind :craft :intent/recipe recipe :intent/window :table}
        (and table (<= (physics/distance eye (mapv #(+ % 0.5) table)) open-reach))
        {:intent/kind :open-container :intent/target table}
        table {:intent/kind :walk :intent/target table}
        :else {:plan/wait :no-table}))))
