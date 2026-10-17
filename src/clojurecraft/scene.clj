(ns clojurecraft.scene
  "What the bot's world looked like at a moment, for a reader: the few facts a fix starts from
   (where it stood, its health and food, what it held, the intent it ran and the last one that
   ended) and the blocks round its feet as text. The judge prints both for a snapshot's kept
   moment and for where its play ended (simrace/judge!), and the Copilot session's brief carries
   them (bb brief), so a fix never begins with a throwaway probe that loads the snapshot to ask
   the same questions (2026-10-17: 46 such probes and 13 Python scripts in 32 sessions, each a JVM
   start polled at a session's full context). Values in, values out."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def radius 5)                 ; blocks each way the slice shows round the feet: 11 by 11
(def heights [-1 0 1 2])       ; the layers, feet-relative: the floor, the feet, the head, above
(def fixed-glyphs ; the blocks that read the same in every slice
  {:air "." :cave_air "." :void_air "." :water "~" :lava "L" nil "?"})
(def letters "abcdefghijkmnopqrstuvwxyz") ; the rest, most common first (no l: it reads as 1)

(def item-names "item id → name" (into {} (map (fn [[k v]] [v k])) blocks/items))

(defn scalar?
  "A keyword, number, string or boolean."
  [v]
  (or (keyword? v) (number? v) (string? v) (boolean? v)))

(defn small?
  "Is v a value a reader takes in at a glance: a scalar, or a vector of at most four scalars (a
   position, never a route)."
  [v]
  (cond (scalar? v) true
        (vector? v) (and (<= (count v) 4) (every? scalar? v))
        :else false))

(defn brief-map
  "m with only its small? values, or nil when none: an intent's kind, target and reason, never
   its plan or its route."
  [m]
  (not-empty (into (sorted-map) (filter (comp small? val)) m)))

(defn rounded
  "A position with one decimal."
  [pos]
  (mapv #(/ (math/round (* 10.0 (double %))) 10.0) pos))

(defn held
  "The player's items as {name count}, sorted: the inventory only, never the crafting grid."
  [world]
  (into (sorted-map)
        (reduce (fn [m [_ {:keys [item count]}]]
                  (update m (get item-names item item) (fnil + 0) count))
                {}
                (:player/inventory world))))

(defn facts
  "The facts a fix starts from in world, absent ones left out: :pos (one decimal), :dimension,
   :health, :food, :held ({name count}), :intent and :last (their small values: kind, target,
   reason) and :status (the plan's)."
  [world]
  (into {}
        (remove (comp nil? val))
        {:pos (some-> (:player/pos world)
                      rounded)
         :dimension (:player/dimension world)
         :health (:player/health world)
         :food (:player/food world)
         :held (not-empty (held world))
         :intent (brief-map (:plan/intent world))
         :last (brief-map (:plan/last world))
         :status (:plan/status world)}))

(defn names
  "The block names round the feet of world's player, [[dy [[name ...] ...]] ...]: a row per z,
   a name per x, nil where no chunk is loaded; nil when the player has no position."
  [world]
  (when-let [pos (:player/pos world)]
    (let [[fx fy fz] (mapv #(long (math/floor (double %))) pos)]
      (vec (for [dy heights]
             [dy
              (vec (for [dz (range (- radius) (inc radius))]
                     (vec (for [dx (range (- radius) (inc radius))]
                            (blocks/name-of
                             (terrain/block-at world [(+ fx dx) (+ fy dy) (+ fz dz)]))))))])))))

(defn legend
  "A glyph for every name in layers (names' output): fixed-glyphs first, then letters, the most
   common name first (ties by name), so a slice reads the same way every time it is printed."
  [layers]
  (let [counts (frequencies (for [[_ rows] layers row rows n row] n))
        rest-names (->> (keys counts)
                        (remove #(contains? fixed-glyphs %))
                        (sort-by (juxt (comp - counts) str)))]
    (merge (into {} (map (fn [n g] [n (str g)]) rest-names (concat letters (repeat "*"))))
           fixed-glyphs)))

(defn slice
  "The blocks round world's player as lines of text: a line per layer and row, \"y+0 z-5 ...\"
   with the player's feet column marked @ on its own layers (feet and head), then one legend line.
   Empty when the player has no position."
  [world]
  (let [layers (names world)
        glyph (legend layers)
        present (set (for [[_ rows] layers row rows n row] n))]
    (cond (nil? layers) []
          :else
          (conj (vec (for [[dy rows] layers
                           [dz row] (map vector (range (- radius) (inc radius)) rows)]
                       (str "y" (if (neg? dy) dy (str "+" dy))
                            " z" (if (neg? dz) dz (str "+" dz))
                            " "
                            (str/join (map-indexed (fn [i n]
                                                     (cond (and (zero? dz) (= i radius) (#{0 1} dy))
                                                           "@"
                                                           :else (glyph n)))
                                                   row)))))
                (str "legend "
                     (str/join " "
                               (for [[n g] (sort-by val glyph)
                                     :when (contains? present n)]
                                 (str g "=" (if n (name n) "unloaded")))))))))
