(ns clojurecraft.fluid
  "Water and lava as the server moves them: the rules of vanilla's FlowingFluid, WaterFluid,
   LavaFluid and LiquidBlock (26.1.2), as values and pure functions over a cell lookup.

   A fluid state is a map, the server's FluidState: {:fluid/kind :water|:lava :fluid/amount 1-8
   :fluid/source? bool :fluid/falling? bool}. A source has amount 8; a flow has 1-7, less by the
   kind's drop for every block from where it is fed; a falling flow (fluid of its kind above it)
   has 8. A block state holds one (block-state, state-of: vanilla's legacy level, 0 for a source,
   8 - amount for a flow, plus 8 when falling).

   Every function here takes at, a function of a cell [x y z] to the block state there (nil where
   nothing is loaded, which holds nothing and passes nothing), and a dimension's rules (rules):
   what a fluid does is a function of the cells round it, nothing else. Time is the caller's: a
   cell whose neighbour changed is ticked delay-ms later (the server's scheduled tick), and the
   tick asks next-state and spread for what to write. Mixing (mix) is not ticked: the server turns
   lava to stone the moment water touches it (LiquidBlock.shouldSpreadLiquid on placement and on
   a neighbour change).

   Not modelled: waterlogged blocks as flows (a waterlogged block holds water but here never
   spreads it), lava's random slow-down when it rises (LavaFluid.getSpreadDelay), fire from lava,
   and the blocks a flow cannot enter besides solids (doors, signs, ladders, portals)."
  (:require [clojurecraft.blocks :as blocks]))

;; ---------------------------------------------------------------- the rules, as data

(def kinds
  "Each fluid's numbers in a dimension that is not ultrawarm (the Overworld, the End) and in one
   that is (the Nether): how much a flow loses a block (getDropOff), how far a flow looks for a
   way down (getSlopeFindDistance), ms between its ticks (getTickDelay: 5 game ticks for water,
   30 for lava, 10 for the Nether's lava), and whether two sources make a third
   (canConvertToSource, the water_source_conversion game rule's default)."
  {[:water false] {:drop 1 :slope 4 :delay-ms 250 :converts? true}
   [:water true] {:drop 1 :slope 4 :delay-ms 250 :converts? true}
   [:lava false] {:drop 2 :slope 2 :delay-ms 1500 :converts? false}
   [:lava true] {:drop 1 :slope 4 :delay-ms 500 :converts? false}})

(defn rule
  "The numbers for kind in a dimension, ultrawarm? the Nether."
  [kind ultrawarm?]
  (kinds [kind (boolean ultrawarm?)]))

(def sides "The four horizontal neighbours, as offsets." [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])
(def up [0 1 0])
(def down [0 -1 0])

(def touches
  "The cells round a lava block whose water turns it to stone: above and the four sides, never
   below (LiquidBlock.POSSIBLE_FLOW_DIRECTIONS, each one's opposite)."
  (into [up] sides))

(def mixed
  "What lava touched by water becomes: a source obsidian, a flow cobblestone; and what water
   becomes when lava flows down into it: stone (LavaFluid.spreadTo)."
  {:source :obsidian :flow :cobblestone :under-lava :stone})

;; ---------------------------------------------------------------- fluid states and block states

(defn state-of
  "The fluid state a block state holds, or nil: water and lava blocks only (a waterlogged block is
   not a flow here)."
  [id]
  (let [kind (condp contains? id blocks/water-states :water blocks/lava-states :lava nil)
        level (when kind (- id (if (= kind :water) blocks/water-base blocks/lava-base)))]
    (cond (nil? kind) nil
          (zero? level) {:fluid/kind kind :fluid/amount 8 :fluid/source? true :fluid/falling? false}
          (>= level 8)
          {:fluid/kind kind :fluid/amount (- 16 level) :fluid/source? false :fluid/falling? true}
          :else
          {:fluid/kind kind :fluid/amount (- 8 level) :fluid/source? false :fluid/falling? false})))

(defn block-state
  "The block state that holds fluid state fs (LiquidBlock's legacy level): a source 0, a flow
   8 - amount, a falling flow 8 more."
  [{:fluid/keys [kind amount source? falling?]}]
  (+ (if (= kind :water) blocks/water-base blocks/lava-base)
     (cond source? 0
           :else (+ (- 8 (min amount 8)) (if falling? 8 0)))))

(defn flowing
  "A flow of kind, amount, falling or not."
  [kind amount falling?]
  {:fluid/kind kind :fluid/amount amount :fluid/source? false :fluid/falling? falling?})
(defn source
  "A source of kind."
  [kind]
  {:fluid/kind kind :fluid/amount 8 :fluid/source? true :fluid/falling? false})
(defn kind-at "The kind of fluid in the cell, or nil." [at p] (:fluid/kind (state-of (at p))))
(defn offset "The cell at d from p." [p d] (mapv + p d))

;; ---------------------------------------------------------------- where a fluid may go

(defn holds?
  "Can the cell take a fluid (FlowingFluid.canHoldFluid): loaded and not solid; air, a plant (the
   flow drops it as an item) or a fluid."
  [at p]
  (let [id (at p)] (boolean (and id (not (blocks/solid? id))))))

(defn enters?
  "May a flow spread into the cell (canSpreadTo): it holds a fluid and has none, as a flow never
   replaces a flow of its own kind (each cell sets itself on its own tick) and the other kind is
   met by mix first."
  [at p]
  (and (holds? at p) (nil? (state-of (at p)))))

(defn passes?
  "May kind's flow pass through the cell when it looks for a way (canMaybePassThrough): it holds
   a fluid and is not a source of kind."
  [at p kind]
  (let [fs (state-of (at p))]
    (and (holds? at p) (not (and (= kind (:fluid/kind fs)) (:fluid/source? fs))))))

(defn hole?
  "Is there a way down under the cell (FlowingFluid.isWaterHole): the cell below holds a fluid,
   any, or nothing that stops one."
  [at p]
  (holds? at (offset p down)))

;; ---------------------------------------------------------------- what a cell becomes

(defn new-liquid
  "The fluid the cell at p would hold now, fed by its neighbours (FlowingFluid.getNewLiquid), or
   nil: a source when two or more sources of a converting kind are beside it and it stands on a
   solid block or a source of its kind; else falling 8 when its kind is above; else the most of
   its kind beside it less the kind's drop, nil at none left."
  [at p kind r]
  (let [beside (keep #(let [fs (state-of (at (offset p %)))] (when (= kind (:fluid/kind fs)) fs))
                     sides)
        sources (count (filter :fluid/source? beside))
        most (reduce max 0 (map :fluid/amount beside))
        below (at (offset p down))
        amount (- most (:drop r))]
    (cond (and (:converts? r)
               (>= sources 2)
               (or (and below (blocks/solid? below)) (:fluid/source? (state-of below))))
          (source kind)
          (= kind (kind-at at (offset p up))) (flowing kind 8 true)
          (pos? amount) (flowing kind amount false)
          :else nil)))

(defn next-state
  "What the cell at p holds after its tick (FlowingFluid.tick): a source keeps itself; a flow
   becomes what its neighbours feed it (new-liquid), air when they feed it nothing. nil when the
   cell holds no fluid."
  [at p ultrawarm?]
  (let [fs (state-of (at p))]
    (cond (nil? fs) nil
          (:fluid/source? fs) fs
          :else (new-liquid at p (:fluid/kind fs) (rule (:fluid/kind fs) ultrawarm?)))))

;; ---------------------------------------------------------------- where it spreads

(defn slope-distance
  "How many blocks from p, moving on the level and never back the way it came (from), the nearest
   way down is, searched no further than the kind's slope; 1000 when none is found
   (FlowingFluid.getSlopeDistance)."
  [at p depth from kind r]
  (reduce (fn [best d]
            (let [n (offset p d)]
              (cond (or (= d from) (not (passes? at n kind))) best
                    (hole? at n) (reduced (min best depth))
                    (< depth (:slope r))
                    (min best (slope-distance at n (inc depth) (mapv - d) kind r))
                    :else best)))
          1000
          sides))

(defn side-spread
  "Where a flow at p of fs spreads on the level (FlowingFluid.spreadToSides, getSpread): only when
   what it passes on is more than nothing (7 from a falling flow); every side it may pass is
   weighed by how near it is to a way down (0 over a hole, else slope-distance), flows already
   there included; it spreads into the nearest ones that hold no fluid, each the state that side
   would hold from its own neighbours. [[cell block-state] ...]."
  [at p fs r]
  (let [kind (:fluid/kind fs)
        passed (if (:fluid/falling? fs) 7 (- (:fluid/amount fs) (:drop r)))
        ways (for [d sides
                   :let [n (offset p d)]
                   :when (passes? at n kind)
                   :let [fed (new-liquid at n kind r)]
                   :when fed]
               {:cell n
                :state (block-state fed)
                :distance (if (hole? at n) 0 (slope-distance at n 1 (mapv - d) kind r))})
        nearest (reduce min 1000 (map :distance ways))]
    (cond (not (pos? passed)) []
          :else
          (vec (for [w ways
                     :when (and (= nearest (:distance w)) (enters? at (:cell w)))]
                 [(:cell w) (:state w)])))))

(defn spread
  "What a flow at p writes into the cells round it on its tick (FlowingFluid.spread): down first,
   a falling flow, when the cell below may take it (lava falling into water makes that water
   stone instead); and on the level only when it cannot go down (or three sources stand beside
   it), and only a source or a flow with no hole of its own kind under it. [[cell block-state] ...]."
  [at p ultrawarm?]
  (let [fs (state-of (at p))
        kind (:fluid/kind fs)
        r (rule kind ultrawarm?)
        b (offset p down)
        sources (count (filter #(:fluid/source? (state-of (at (offset p %)))) sides))]
    (cond (nil? fs) []
          (and (= kind :lava) (= :water (kind-at at b)))
          [[b (blocks/default-state (mixed :under-lava))]]
          (enters? at b)
          (into [[b (block-state (new-liquid at b kind r))]]
                (when (>= sources 3) (side-spread at p fs r)))
          (or (:fluid/source? fs) (not (hole? at p))) (side-spread at p fs r)
          :else [])))

;; ---------------------------------------------------------------- lava meeting water

(defn mix
  "The block a lava cell at p turns into at once because water touches it above or beside
   (LiquidBlock.shouldSpreadLiquid): obsidian for a source, cobblestone for a flow; nil when it
   is not lava or no water touches it."
  [at p]
  (let [fs (state-of (at p))]
    (cond (or (not= :lava (:fluid/kind fs))
              (not-any? #(= :water (kind-at at (offset p %))) touches))
          nil
          :else (blocks/default-state (if (:fluid/source? fs) (mixed :source) (mixed :flow))))))

(defn delay-ms
  "Ms after a neighbour changed that the cell at p ticks (getTickDelay), or nil when it holds no
   fluid."
  [at p ultrawarm?]
  (some-> (kind-at at p)
          (rule ultrawarm?)
          :delay-ms))
