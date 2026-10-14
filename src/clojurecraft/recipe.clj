(ns clojurecraft.recipe
  "Crafting as data. `recipes` is the vanilla crafting table (resources/clojurecraft/recipes.edn,
   tags already resolved to item-name sets); everything here is a pure function over it.

   - `match`: what a grid crafts (the server's rule, used by the sim).
   - `clicks`: the container clicks that lay one craft into the grid, from the inventory value.
   - `needs`: what one craft consumes; clojurecraft.make turns every recipe into a goal row from it.

   Grids are {slot item-name} with slot 1..size² in reading order (slot 0 is the result); the
   2x2 inventory grid is size 2, a crafting table is size 3."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.inventory :as inventory]))

(set! *warn-on-reflection* true)

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(def recipes "[recipe ...]" (resource "recipes"))
(def tags "{tag-name #{item-name}}" (resource "item-tags"))
(def by-id (into {} (map (juxt :recipe/id identity) recipes)))
(def smelting
  "[{:smelt/id :smelt/input #{item-name} :smelt/result :smelt/count :smelt/ticks} ...]: the
   furnace's recipes (resources/clojurecraft/smelting.edn)"
  (resource "smelting"))
(def smelt-by-id (into {} (map (juxt :smelt/id identity) smelting)))

(def item-names (into {} (map (fn [[n id]] [id n]) blocks/items)))
(defn item-name "The item keyword for a numeric item id, or nil." [id] (item-names id))
(defn item-id "The numeric item id for an item keyword, or nil." [name] (blocks/items name))

;; ---------------------------------------------------------------- shape

(defn cells
  "[[[row col] ingredient-set] ...] for a shaped recipe; nil for shapeless."
  [{:recipe/keys [kind pattern key]}]
  (when (= kind :shaped)
    (for [[r row] (map-indexed vector pattern)
          [c ch] (map-indexed vector row)
          :when (not= ch \space)]
      [[r c] (key (str ch))])))

(defn fits?
  "Can recipe r be crafted in a size×size grid? Shaped: its pattern fits; shapeless: it has no
   more ingredients than cells."
  [{:recipe/keys [kind width height ingredients]} size]
  (if (= kind :shaped)
    (and (<= width size) (<= height size))
    (<= (count ingredients) (* size size))))

(defn needs
  "{ingredient-set count} consumed by one craft."
  [{:recipe/keys [kind ingredients] :as r}]
  (if (= kind :shaped) (frequencies (map second (cells r))) (frequencies ingredients)))

(defn placement
  "{grid-slot ingredient-set} for one craft laid at the top-left of a size×size grid."
  [r size]
  (if (= :shaped (:recipe/kind r))
    (into {} (for [[[row col] s] (cells r)] [(+ 1 (* row size) col) s]))
    (zipmap (range 1 (inc (count (:recipe/ingredients r)))) (:recipe/ingredients r))))

;; ---------------------------------------------------------------- match

(defn shaped-match?
  "Does the occupied part of grid (non-empty, {slot item-name}) equal r's pattern, as drawn or
   mirrored left to right? The pattern may sit anywhere in the grid, as in vanilla."
  [r grid size]
  (let [occupied (keep (fn [[slot item]] (when item [(quot (dec slot) size) (rem (dec slot) size)]))
                       grid)
        rows (map first occupied)
        cols (map second occupied)
        r0 (apply min rows)
        c0 (apply min cols)
        h (inc (- (apply max rows) r0))
        w (inc (- (apply max cols) c0))
        {:recipe/keys [pattern key width height]} r
        at (fn [row col] (get grid (+ 1 (* (+ r0 row) size) c0 col)))
        fits (fn [mirror?]
               (every? true?
                       (for [row (range height)
                             col (range width)]
                         (let [ch (get-in pattern [row (if mirror? (- width 1 col) col)] \space)
                               item (at row col)]
                           (if (= ch \space) (nil? item) (contains? (key (str ch)) item))))))]
    (and (= [h w] [height width]) (or (fits false) (fits true)))))

(defn shapeless-match?
  "Can the grid's items be paired one-to-one with r's ingredient sets, in any order?"
  [r grid]
  (let [items (vec (keep val grid))
        sets (:recipe/ingredients r)]
    (and (= (count items) (count sets))
         (letfn [(assign [items sets]
                   (or (empty? items)
                       (let [[item & more] items]
                         (some (fn [i]
                                 (and (contains? (nth sets i) item)
                                      (assign more
                                              (vec (concat (subvec sets 0 i)
                                                           (subvec sets (inc i)))))))
                               (range (count sets))))))]
           (boolean (assign items (vec sets)))))))

(defn match
  "The recipe a grid crafts, or nil. grid is {slot item-name} for slots 1..size²."
  [grid size]
  (let [grid (into {} (filter val grid))]
    (when (seq grid)
      (first (filter (fn [r]
                       (and (fits? r size)
                            (if (= :shaped (:recipe/kind r))
                              (shaped-match? r grid size)
                              (shapeless-match? r grid))))
                     recipes)))))

;; ---------------------------------------------------------------- inventory

(defn counts
  "{item-name n} over a {slot {:item id :count n}} inventory."
  [inventory]
  (reduce (fn [m {:keys [item count]}] (update m (item-name item) (fnil + 0) count))
          {}
          (vals inventory)))

(defn have
  "How many items of any name in set s the counts hold."
  [counts s]
  (reduce + 0 (map #(get counts % 0) s)))

(defn consume
  "counts with n items taken from the names in set s, in name order; takes what there is when
   counts hold fewer than n."
  [counts s n]
  (first (reduce (fn [[counts n] item]
                   (if (zero? n)
                     (reduced [counts n])
                     (let [k (min n (get counts item 0))]
                       [(update counts item (fnil - 0) k) (- n k)])))
                 [counts n]
                 (sort s))))

;; ---------------------------------------------------------------- clicks

(defn sources
  "[[slot k] ...]: the stacks of inventory (window slots, slot-of) that lay n items of the
   ingredient set s, with what used already takes out of each: one stack that covers all n
   when there is one (the lowest slot), else the lowest slots in turn until n are drawn, so
   7 + 1 cobblestone make a furnace (race 38477665442 bot Clj_r02's pickup had started a
   second stack, and the craft found no single stack of eight: :no-ingredient three times).
   nil when the stacks do not hold n together."
  [inventory slot-of used s n]
  (let [stacks (for [[slot {:keys [item count]}] (sort-by key inventory)
                     :let [left (- count (get used slot 0))]
                     :when (and (slot-of slot) (contains? s (item-name item)) (pos? left))]
                 [slot left])
        whole (first (filter (fn [[_ left]] (>= left n)) stacks))
        [drawn short] (reduce (fn [[acc need] [slot left]]
                                (if (zero? need)
                                  (reduced [acc 0])
                                  [(conj acc [slot (min left need)]) (- need (min left need))]))
                              [[] n]
                              stacks)]
    (cond whole [[(first whole) n]]
          (zero? short) drawn
          :else nil)))

(defn lay-from
  "[used clicks cells-left] after laying k items of the stack in player slot source into the
   first k of cells (window slots): pick the stack up, right-click one into each, put the
   remainder back."
  [inventory slot-of [used acc cells] [source k]]
  (let [cs (slot-of source)
        left (- (get-in inventory [source :count]) (get used source 0) k)]
    [(update used source (fnil + 0) k)
     (-> acc
         (conj (inventory/left-click cs))
         (into (for [[g _] (take k cells)] (inventory/right-click g)))
         (cond-> (pos? left) (conj (inventory/left-click cs)))) (drop k cells)]))

(defn clicks
  "The clicks that lay one craft of r into the size×size grid of window 0, or nil when the
   inventory cannot cover it. Each ingredient group comes from one stack when one covers it,
   else from the lowest stacks in turn (sources): pick a stack up, right-click one item into
   each of its cells, put the remainder back (lay-from). Clicks are {:click/slot
   :click/button :click/mode}. slot-of maps a player-inventory slot to a slot of the window
   being clicked (window 0 by default)."
  ([inventory r size]
   (clicks inventory r size #(inventory/player->window-slot (inventory/layouts :inventory) %)))
  ([inventory r size slot-of]
   (some-> (reduce
            (fn [[used acc] [s cells]]
              (if-let [srcs (sources inventory slot-of used s (count cells))]
                (reduce (partial lay-from inventory slot-of) [used acc (sort-by key cells)] srcs)
                (reduced nil)))
            [{} []]
            (group-by val (placement r size)))
           second)))
