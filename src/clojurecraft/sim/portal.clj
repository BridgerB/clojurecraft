(ns clojurecraft.sim.portal
  "Placing blocks and the Nether portal in the model: a block placed against a face, the frame a flint and steel lights, the player carried through it, and the arrival (the platform the server forces when no portal is there)."
  (:refer-clojure :exclude [send])
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim.protocol :as sim-protocol :refer
             [faces leave-overworld overlaps-player? respawn stream]]
            [clojurecraft.sim.window :as sim-window :refer
             [full open-furnace open-window sync-window take-one]]
            [clojurecraft.sim.world :as sim-world :refer
             [block-at disturb held-item held-slot on-packet send set-cell]]))

(set! *warn-on-reflection* true)

(def portal-ms 4000)                  ; a survival player in a portal this long changes dimension (80 game ticks)

(def nether
  "The respawn the model sends for the Nether: the dimension change a portal makes."
  (assoc respawn
         :dimension-type (:dimension/id (dimension/by-name dimension/nether))
         :dimension dimension/nether))

(defn frame-around
  "The six interior cells of a complete portal frame round cell along axis (:x or :z), or nil: an
   interior two wide and three high, air (or fire) inside, obsidian on its four sides, the
   corners not needed (PortalShape)."
  [sim [x y z] axis]
  (let [along (fn [[a b c] d] (if (= axis :x) [(+ a d) b c] [a b (+ c d)]))
        obsidian? #(= :obsidian (blocks/name-of (block-at sim %)))
        open? #(contains? #{:air :fire} (blocks/name-of (block-at sim %)))]
    (first (for [da [0 -1]
                 dy [0 -1 -2]
                 :let [base (along [x (+ y dy) z] da)
                       inside (for [a [0 1] b [0 1 2]] (update (along base a) 1 + b))
                       frame (concat (for [a [0 1]] (update (along base a) 1 dec))
                                     (for [a [0 1]] (update (along base a) 1 + 3))
                                     (for [b [0 1 2]] (update (along base -1) 1 + b))
                                     (for [b [0 1 2]] (update (along base 2) 1 + b)))]
                 :when (and (every? open? inside) (every? obsidian? frame))]
             inside))))

(defn light
  "use-item-on with flint and steel: fire in the cell the clicked face looks into when it is air;
   fire inside a complete frame becomes the portal instead (BaseFireBlock.onPlace finds the
   PortalShape round it). Then the ack."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        inside (when (= :air (blocks/name-of (block-at sim dest)))
                 (or (some-> (frame-around sim dest :x)
                             (vector (blocks/default-state :nether_portal)))
                     (some-> (frame-around sim dest :z)
                             (vector (inc (blocks/default-state :nether_portal))))))
        fire (blocks/default-state :fire)]
    (cond (nil? (block-at sim dest)) (send sim ack)
          inside
          (let [[cells state] inside]
            (-> (reduce (fn [sim c]
                          (-> sim
                              (assoc-in [:sim/placed c] state)
                              (send {:packet/name :block-update :pos c :state state})))
                        sim
                        cells)
                (send ack)))
          (= :air (blocks/name-of (block-at sim dest)))
          (-> sim
              (assoc-in [:sim/placed dest] fire)
              (send {:packet/name :block-update :pos dest :state fire})
              (send ack))
          :else (send sim ack))))

(def exit-search 16)                  ; blocks round the exit point the server looks for a place to stand (PortalForcer: 16 in the Nether)

(def platform-min-y 70)               ; a forced platform's lowest feet y (PortalForcer.createPortal)

(def platform-below-top 10)           ; and its highest, this under the logical height (118 in the Nether)

(defn exit-point
  "The cell a portal whose player stands at pos [x y z] in the overworld leads to in the Nether:
   x and z scaled by the two dimensions' coordinate scales (1/8), floored, y kept, as
   NetherPortalBlock.getExitPortal scales the entity's position."
  [[x y z]]
  (let [scale (/ (:dimension/coordinate-scale (dimension/by-name dimension/overworld))
                 (:dimension/coordinate-scale (dimension/by-name dimension/nether)))]
    [(long (math/floor (* x scale))) (long (math/floor y)) (long (math/floor (* z scale)))]))

(defn standing?
  "Can a player stand with its feet in cell [x y z] of a world read by at (a cell → its state id,
   nil where nothing is loaded): a solid block under it, and air in it and the cell over it (never
   lava or water)."
  [at [x y z]]
  (let [floor (at [x (dec y) z])
        feet (at [x y z])
        head (at [x (inc y) z])]
    (boolean (and floor feet head (blocks/solid? floor) (blocks/air? feet) (blocks/air? head)))))

(defn portal-space?
  "Can a linked portal's frame fit at feet in either horizontal axis: four solid floor cells with
   four air cells over each, as PortalForcer.canHostFrame checks? A single standing? cell is not
   enough."
  [at [x y z :as feet]]
  (boolean (and (standing? at feet)
                (some (fn [[dx dz]]
                        (every? (fn [n]
                                  (let [p [(+ x (* n dx)) y (+ z (* n dz))]]
                                    (and (standing? at p)
                                         (every? #(blocks/air? (at (update p 1 + %))) [2 3]))))
                                (range -1 3)))
                      [[1 0] [0 1]]))))

(defn exit-spot
  "The cell a player arrives in through a portal whose exit point is target, in a Nether read by
   at: of the portal-space? cells within exit-search blocks of target across (x and z) whose frames
   are under top (the Nether's logical height, 128: over it is the bedrock roof), the nearest to
   target, by distance squared and then position, so a world and a target give one cell; nil
   when there is none. PortalForcer.createPortal's search, as the server places a new linked
   portal, with room for the frame's four by five, though no frame is built."
  [at [tx _ tz :as target] top]
  (let [d2 (fn [c] (reduce + (map #(let [d (- %1 %2)] (* d d)) c target)))]
    (reduce (fn [best c]
              (cond (not (portal-space? at c)) best
                    (or (nil? best) (neg? (compare (into [(d2 c)] c) (into [(d2 best)] best)))) c
                    :else best))
            nil
            (for [x (range (- tx exit-search) (inc (+ tx exit-search)))
                  z (range (- tz exit-search) (inc (+ tz exit-search)))
                  y (range 1 (- top 3))]
              [x y z]))))

(defn platform
  "Where the server puts a player when no cell near target can be stood in: target with its y
   held between platform-min-y and platform-below-top under top, on an obsidian floor two by
   three with three cells of air over it (PortalForcer.createPortal's forced platform). Returns
   {:feet cell :placed {cell state}}."
  [[tx ty tz] top]
  (let [y (max platform-min-y (min (long ty) (- top platform-below-top)))]
    {:feet [tx y tz]
     :placed (into {}
                   (for [x [(dec tx) tx]
                         z [(dec tz) tz (inc tz)]
                         dy [-1 0 1 2]]
                     [[x (+ y dy) z]
                      (if (neg? dy) (blocks/default-state :obsidian) blocks/air)]))}))

(defn arrival
  "Where a portal at pos leads in a Nether of decoded columns ({[cx cz] column}), a pure function
   of the two: {:at the feet's position :placed {cell state}}, the exit-spot nearest pos's
   exit-point with nothing changed, or, with none, the platform the server builds there."
  [decoded pos]
  (let [top (:dimension/logical-height (dimension/by-name dimension/nether))
        target (exit-point pos)
        spot (exit-spot #(chunk/block-at decoded %) target top)
        {[x y z] :feet placed :placed} (if spot {:feet spot :placed {}} (platform target top))]
    {:at [(+ x 0.5) (double y) (+ z 0.5)] :placed placed}))

(defn arrive
  "The model in the Nether: with a :sim/nether, its column becomes the world (the overworld's placed
   and broken blocks were the other dimension's) and the player stands at its :at; without one, the
   same column where the player stood (a one-column model of the portal alone). A :sim/nether with
   :columns is that whole Nether instead, decoded once on arrival unless it comes :decoded, and the
   overworld's items, mobs, fluids and falling blocks stay behind, as the client drops their
   entities. With no :at, the player stands where the server would place the portal linked to the
   one it stood in (arrival); with a :view-distance, the Nether's columns stream round it as a
   server's do, never all at once."
  [sim]
  (let [{:keys [column columns decoded at view-distance]} (:sim/nether sim)]
    (cond columns
          (let [decoded (or decoded (update-vals columns chunk/decode))
                landed (if at {:at at :placed {}} (arrival decoded (:sim/player-pos sim)))]
            (-> sim
                (assoc :sim/columns columns
                       :sim/decoded decoded
                       :sim/placed (:placed landed)
                       :sim/broken #{}
                       :sim/items {}
                       :sim/mobs {}
                       :sim/fluids {}
                       :sim/falls {}
                       :sim/falling {}
                       :sim/player-pos (:at landed)
                       :sim/view #{})
                (cond-> view-distance (assoc :sim/view-distance view-distance))
                (dissoc :sim/view-at :sim/spawners :sim/spawners-at)))
          column
          (-> sim
              (assoc :sim/columns {[0 0] column}
                     :sim/placed {}
                     :sim/broken #{}
                     :sim/player-pos at
                     :sim/view #{}) ; a dimension change drops every chunk the client held
              (dissoc :sim/view-at :sim/spawners :sim/spawners-at))
          :else (dissoc sim :sim/view-at))))

(defn through-portal
  "The model a tick on: a player whose feet stand in a nether portal portal-ms on end changes
   dimension (arrive, then the Nether's respawn, a teleport where it now stands and its column);
   out of a portal, the count starts over."
  [sim now]
  (let [pos (:sim/player-pos sim)
        in? (and pos
                 (= :nether_portal
                    (blocks/name-of (block-at sim
                                              (mapv #(long (math/floor %)) (update pos 1 + 0.1))))))
        since (:sim/portal-since sim)]
    (cond (not in?) (dissoc sim :sim/portal-since)
          (= dimension/nether (:sim/dimension sim)) sim
          (nil? since) (assoc sim :sim/portal-since now)
          (< (- now since) portal-ms) sim
          :else
          (let [sim (arrive (leave-overworld sim))
                [x y z] (:sim/player-pos sim)]
            (-> sim
                (assoc :sim/dimension dimension/nether)
                (dissoc :sim/portal-since)
                (send nether)
                (send {:packet/name :player-position
                       :teleport-id 3
                       :x x
                       :y y
                       :z z
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 0.0
                       :pitch 0.0
                       :flags 0})
                (assoc :sim/view #{}) ; the client dropped the old dimension's chunks
                stream)))))

(defn place
  "use-item-on that is not on a container: place the held block item at pos + face when the
   destination is one a block may take the place of (blocks/placeable-into?: air, a grass or a
   fluid, as the server's replaceable tag says; a flower refuses it), the clicked block is solid and
   the block would not overlap the player; else tell the client the destination's real state. Either
   way, then the ack."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        slot (held-slot sim)
        held (get-in sim [:sim/inv slot])
        d (block-at sim dest)
        overlap? (overlaps-player? sim dest)
        placing (some-> held
                        :item
                        recipe/item-name
                        blocks/default-state) ; the held item is a block: it is placed
        ok? (and placing
                 d
                 (blocks/placeable-into? d)
                 (blocks/solid? (or (block-at sim pos) blocks/air))
                 (not overlap?))
        sim (cond-> sim overlap? (update :sim/violations conj [:place-into-player dest]))]
    (if ok?
      (let [before (full (:sim/inv sim) (inventory/layouts :inventory))]
        (-> sim
            (assoc-in [:sim/placed dest] placing)
            (disturb dest)
            (update :sim/inv take-one slot)
            (send {:packet/name :block-update :pos dest :state placing})
            (sync-window :inventory before (:sim/cursor sim) false)
            (send ack)))
      (-> sim
          (send {:packet/name :block-update :pos dest :state (or d blocks/air)})
          (send ack)))))

(defn frame-takes-eye
  "use-item-on an end portal frame with no eye, an eye held: the frame takes it (its state with
   the eye), one eye taken, and when that closes a ring of twelve the portal fills its interior
   (end/lit); then the ack. With anything else held it is a placement against the frame's face
   (place); a frame that has its eye only acks."
  [sim pos face ack]
  (let [id (block-at sim pos)
        eye? (= (held-item sim) (recipe/item-id :ender_eye))]
    (cond (not eye?) (place sim pos face ack)
          (end/eyed? id) (send sim ack)
          :else
          (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
                sim (-> sim
                        (set-cell pos (end/with-eye id))
                        (update :sim/inv take-one (held-slot sim))
                        (sync-window :inventory before (:sim/cursor sim) false))]
            (->
              (reduce (fn [sim c] (set-cell sim c end/portal)) sim (end/lit #(block-at sim %) pos))
              (send ack))))))

(defmethod on-packet [:play :use-item-on]
  ;; a right-click on a container opens it (ack first, as with a placement); anything else is
  ;; a placement against the clicked face
  [sim {:keys [pos face sequence]}]
  (let [ack {:packet/name :block-changed-ack :sequence sequence}]
    (case (blocks/name-of (block-at sim pos))
      :crafting_table
      (-> sim
          (send ack)
          (open-window :table {:grid {}}))
      :furnace
      (-> sim
          (send ack)
          (open-furnace pos))
      :end_portal_frame (frame-takes-eye sim pos face ack)
      (if (= (:item (get-in sim [:sim/inv (held-slot sim)])) (get blocks/items :flint_and_steel))
        (light sim pos face ack)
        (place sim pos face ack)))))
