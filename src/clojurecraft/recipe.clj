(ns clojurecraft.recipe
  "Crafting as data. `recipes` is the vanilla crafting table (resources/clojurecraft/recipes.edn,
   tags already resolved to item-name sets); everything here is a pure function over it.

   - `match`: what a grid crafts (the server's rule, used by the sim).
   - `clicks`: the container clicks that lay one craft into the grid, from the inventory value.
   - `next-action`: walk the recipe graph from what is wanted to the first thing to do now
     ({:action :craft :recipe r}, {:action :gather :want :logs}, :stuck, or nil when satisfied).

   Grids are {slot item-name} with slot 1..size² in reading order (slot 0 is the result); the
   2x2 inventory grid is size 2, a crafting table is size 3."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.blocks :as blocks]))

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(def recipes "[recipe ...]" (resource "recipes"))
(def tags "{tag-name #{item-name}}" (resource "item-tags"))
(def by-result (group-by :recipe/result recipes))
(def by-id (into {} (map (juxt :recipe/id identity) recipes)))

(def item-names (into {} (map (fn [[n id]] [id n]) blocks/items)))
(defn item-name [id] (item-names id))
(defn item-id [name] (blocks/items name))

(def raw?
  "Ingredient sets we gather rather than craft."
  (let [logs (tags :logs)] (fn [s] (every? logs s))))

;; ---------------------------------------------------------------- shape

(defn cells
  "[[[row col] ingredient-set] ...] for a shaped recipe; nil for shapeless."
  [{:recipe/keys [kind pattern key]}]
  (when (= kind :shaped)
    (for [[r row] (map-indexed vector pattern)
          [c ch] (map-indexed vector row)
          :when (not= ch \space)]
      [[r c] (key (str ch))])))

(defn fits? [{:recipe/keys [kind width height ingredients]} size]
  (if (= kind :shaped)
    (and (<= width size) (<= height size))
    (<= (count ingredients) (* size size))))

(defn needs
  "{ingredient-set count} consumed by one craft."
  [{:recipe/keys [kind ingredients] :as r}]
  (if (= kind :shaped)
    (frequencies (map second (cells r)))
    (frequencies ingredients)))

(defn placement
  "{grid-slot ingredient-set} for one craft laid at the top-left of a size×size grid."
  [r size]
  (if (= :shaped (:recipe/kind r))
    (into {} (for [[[row col] s] (cells r)] [(+ 1 (* row size) col) s]))
    (zipmap (range 1 (inc (count (:recipe/ingredients r)))) (:recipe/ingredients r))))

;; ---------------------------------------------------------------- match

(defn- shaped-match? [r grid size]
  (let [occupied (keep (fn [[slot item]] (when item [(quot (dec slot) size) (rem (dec slot) size)])) grid)
        rows (map first occupied) cols (map second occupied)
        r0 (apply min rows) c0 (apply min cols)
        h (inc (- (apply max rows) r0)) w (inc (- (apply max cols) c0))
        {:recipe/keys [pattern key width height]} r
        at (fn [row col] (get grid (+ 1 (* (+ r0 row) size) (+ c0 col))))
        fits (fn [mirror?]
               (every? true?
                       (for [row (range height) col (range width)]
                         (let [ch (get-in pattern [row (if mirror? (- width 1 col) col)] \space)
                               item (at row col)]
                           (if (= ch \space) (nil? item) (contains? (key (str ch)) item))))))]
    (and (= [h w] [height width]) (or (fits false) (fits true)))))

(defn- shapeless-match? [r grid]
  (let [items (vec (keep val grid))
        sets (:recipe/ingredients r)]
    (and (= (count items) (count sets))
         (letfn [(assign [items sets]
                   (or (empty? items)
                       (let [[item & more] items]
                         (some (fn [i] (and (contains? (nth sets i) item)
                                            (assign more (vec (concat (subvec sets 0 i) (subvec sets (inc i)))))))
                               (range (count sets))))))]
           (boolean (assign items (vec sets)))))))

(defn match
  "The recipe a grid crafts, or nil. grid is {slot item-name} for slots 1..size²."
  [grid size]
  (let [grid (into {} (filter val grid))]
    (when (seq grid)
      (first (filter (fn [r] (and (fits? r size)
                                  (if (= :shaped (:recipe/kind r)) (shaped-match? r grid size) (shapeless-match? r grid))))
                     recipes)))))

;; ---------------------------------------------------------------- inventory

(defn player->container-slot
  "Player-inventory slot → window-0 slot."
  [^long s]
  (cond (<= 0 s 8) (+ 36 s) (<= 9 s 35) s (= s 40) 45 :else nil))

(defn counts
  "{item-name n} over a {slot {:item id :count n}} inventory."
  [inventory]
  (reduce (fn [m {:keys [item count]}] (update m (item-name item) (fnil + 0) count)) {} (vals inventory)))

(defn have [counts s] (reduce + 0 (map #(get counts % 0) s)))

(defn- consume [counts s n]
  (loop [counts counts [item & more] (sort s) n n]
    (if (or (zero? n) (nil? item))
      counts
      (let [k (min n (get counts item 0))]
        (recur (update counts item (fnil - 0) k) more (- n k))))))

;; ---------------------------------------------------------------- clicks

(defn clicks
  "The clicks that lay one craft of r into the size×size grid of window 0, or nil when the
   inventory cannot cover it. Each ingredient group comes from a single stack: pick it up,
   right-click one item into each cell, put the remainder back. Clicks are
   {:click/slot :click/button :click/mode}. slot-of maps a player-inventory slot to a slot of
   the window being clicked (window 0 by default)."
  ([inventory r size] (clicks inventory r size player->container-slot))
  ([inventory r size slot-of]
   (let [groups (group-by val (placement r size))]
     (loop [[[s cells] & more] (seq groups) used {} acc []]
       (if (nil? s)
         acc
         (let [n (count cells)
               source (->> (sort-by key inventory)
                           (filter (fn [[slot {:keys [item count]}]]
                                     (and (slot-of slot)
                                          (contains? s (item-name item))
                                          (>= (- count (get used slot 0)) n))))
                           ffirst)]
           (when source
             (let [cs (slot-of source)
                   left (- (get-in inventory [source :count]) (get used source 0) n)]
               (recur more (update used source (fnil + 0) n)
                      (-> acc
                          (conj {:click/slot cs :click/button 0 :click/mode 0})
                          (into (for [[g _] (sort-by key cells)] {:click/slot g :click/button 1 :click/mode 0}))
                          (cond-> (pos? left) (conj {:click/slot cs :click/button 0 :click/mode 0}))))))))))))

;; ---------------------------------------------------------------- the graph

(def max-depth 6)

(defn- resolve-want
  "[counts' action] for wanting n of any item in set s. action nil means satisfied."
  [counts s n size depth]
  (let [h (have counts s)]
    (cond
      (>= h n) [(consume counts s n) nil]
      (> depth max-depth) [counts :stuck]
      :else
      (let [counts (consume counts s h)
            missing (- n h)]
        (if (raw? s)
          [counts {:action :gather :want :logs}]   ; any species: the next plan picks its recipe
          (let [candidates (->> (mapcat by-result s)
                                (filter #(fits? % size))
                                (sort-by (fn [r] [(- (reduce + (map (fn [[ns _]] (have counts ns)) (needs r))))
                                                  (- (:recipe/count r))])))]
            (or (some (fn [r]
                        (let [crafts (long (Math/ceil (/ missing (:recipe/count r))))
                              result (reduce (fn [[c _] [ns k]]
                                               (let [[c a] (resolve-want c ns (* k crafts) size (inc depth))]
                                                 (if a (reduced [c a]) [c nil])))
                                             [counts nil] (needs r))
                              [_ a] result]
                          (cond (= a :stuck) nil
                                a result
                                :else [counts {:action :craft :recipe (:recipe/id r)}])))
                      candidates)
                [counts :stuck])))))))

(defn next-action
  "The first thing to do toward wants ([[item-name n] ...], in order) given counts, crafting in
   a size×size grid: {:action :craft :recipe id}, {:action :gather :want :logs}, :stuck, or nil
   when every want is already held."
  [counts wants size]
  (loop [counts counts [[item n] & more] wants]
    (if (nil? item)
      nil
      (let [[counts a] (resolve-want counts #{item} n size 0)]
        (if a a (recur counts more))))))
