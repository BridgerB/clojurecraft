(ns clojurecraft.sim.window
  "Windows in the model: the player's inventory and the containers a right-click opens, the click rules (click-view) and state ids, crafting, and the furnace that cooks by the game's smelting and fuel tables every tick."
  (:refer-clojure :exclude [send])
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim.world :as sim-world :refer [max-stack on-packet send stack-size]]))

(set! *warn-on-reflection* true)

(defn result-of
  "What the grid of view crafts, {:item id :count n}, or nil when it matches no recipe."
  [view layout]
  (let [grid (into {}
                   (for [s (inventory/grid-slots layout)
                         :let [it (get view s)]
                         :when it]
                     [s (recipe/item-name (:item it))]))]
    (when-let [r (recipe/match grid (:size layout))]
      {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)})))

(defn own-path
  "Where the sim keeps the own slots of the open container of kind: the table's grid on its
   window (it empties when the window closes), a furnace's slots at its position (they stay)."
  [sim kind]
  (case kind
    :table [:sim/window :grid]
    :furnace [:sim/furnaces (get-in sim [:sim/window :pos]) :slots]))

(defn view-of
  "The slots of window kind as the click rules see them, {slot item}, never a crafting result:
   :inventory is window 0; a container is its own slots plus the player's where it shows them
   (inventory/layouts)."
  [sim kind]
  (if (= kind :inventory)
    (:sim/inv sim)
    (let [layout (inventory/layouts kind)]
      (merge (get-in sim (own-path sim kind))
             (into {}
                   (for [[s it] (:sim/inv sim)
                         :let [t (inventory/translate (inventory/layouts :inventory) layout s)]
                         :when t]
                     [t it]))))))

(defn with-view
  "Write a clicked view of window kind back into the sim; the inverse of view-of. A crafting
   result slot is dropped, since it is always derived."
  [sim kind view]
  (let [layout (inventory/layouts kind)
        view (cond-> view (inventory/crafting? layout) (dissoc inventory/result-slot))]
    (if (= kind :inventory)
      (assoc sim :sim/inv view)
      (let [inv (inventory/layouts :inventory)]
        (->
          sim
          (assoc-in (own-path sim kind) (select-keys view (inventory/own-slots layout)))
          (assoc
           :sim/inv
           (merge
            (into {} (remove (fn [[s _]] (inventory/translate inv layout s)) (:sim/inv sim)))
            (into
             {}
             (for [[s it] view :let [w (inventory/translate layout inv s)] :when w] [w it])))))))))

(defn full
  "view with its result slot set to what its grid crafts, when the window crafts."
  [view layout]
  (cond-> view (inventory/crafting? layout) (assoc inventory/result-slot (result-of view layout))))

(defn insert
  "Put a stack into the given slots, stacking first. Returns [view leftover]."
  [view slots {:keys [item count]}]
  (let [order (concat (filter #(= item (:item (get view %))) slots)
                      (filter #(nil? (get view %)) slots))
        [view n] (reduce (fn [[view n] s]
                           (if (zero? n)
                             (reduced [view n])
                             (let [have (:count (get view s) 0)
                                   k (min n (- (stack-size item) have))]
                               [(assoc view s {:item item :count (+ have k)}) (- n k)])))
                         [view count]
                         order)]
    [view (when (pos? n) {:item item :count n})]))

(defn take-one
  "view with one item removed from slot s; the slot empties at zero. s must hold an item."
  [view s]
  (let [{:keys [count] :as it} (get view s)]
    (if (> count 1) (assoc view s (assoc it :count (dec count))) (dissoc view s))))

(defn craft-once
  "view after one craft: one item taken from every occupied grid slot."
  [view layout]
  (reduce (fn [view s] (if (get view s) (take-one view s) view))
          view
          (inventory/grid-slots layout)))

(defn craft-all
  "Shift-click on the result: craft and move results into the store until the grid matches
   nothing or the store is full."
  [view layout]
  (loop [view view]
    (let [out (result-of view layout)
          [view' left] (when out (insert view (inventory/store-slots layout) out))]
      (cond (or (nil? out) left) view
            :else (recur (craft-once view' layout))))))

(defn burns?
  "Does a furnace burn this item name (the game's fuel table)?"
  [n]
  (contains? blocks/fuel-by-name n))

(defn accepts?
  "May a click put item into slot of a window with layout? A furnace's output takes nothing and
   its fuel slot only what burns or an empty bucket (vanilla's FurnaceResultSlot and
   FurnaceFuelSlot); every other slot takes anything."
  [layout slot {:keys [item]}]
  (let [n (recipe/item-name item)]
    (cond (= slot (:output layout)) false
          (= slot (:fuel layout)) (or (burns? n) (= n :bucket))
          :else true)))

(defn take-onto
  "A loaded cursor clicks a slot that refuses it: vanilla moves what fits of the same item from
   the slot onto the cursor (taking from a furnace's output), and does nothing otherwise."
  [view cursor slot]
  (let [at (get view slot)]
    (if (and at (= (:item at) (:item cursor)))
      (let [k (min (:count at) (- (stack-size (:item cursor)) (:count cursor)))
            left (- (:count at) k)]
        {:view (if (pos? left) (assoc view slot (assoc at :count left)) (dissoc view slot))
         :cursor (update cursor :count + k)})
      {:view view :cursor cursor})))

(defn click-kind
  "Which vanilla rule a click in a window with layout falls under: :result (the result slot of a
   window that crafts), :swap (with a hotbar slot), :shift (a quick move), :throw (out of the
   window), :left (the left button) or :right."
  [layout {:keys [slot button mode]}]
  (cond (and (inventory/crafting? layout) (= slot inventory/result-slot)) :result
        (= mode (inventory/click-modes :swap)) :swap
        (= mode (inventory/click-modes :throw)) :throw
        (= mode (inventory/click-modes :quick-move)) :shift
        (= button inventory/left-button) :left
        :else :right))

(defmulti click-view
  "Vanilla click rules over one window's slots, one method per kind of click (click-kind).
   Returns {:view :cursor :violation}."
  {:arglists '([layout view cursor click])}
  (fn [layout _view _cursor click] (click-kind layout click)))

(defmethod click-view :result
  ;; vanilla crafts onto the cursor, or with a shift-click into the store; a click when the grid
  ;; matches nothing is recorded as a violation
  [layout view cursor {:keys [mode]}]
  (let [out (result-of view layout)]
    (cond (nil? out) {:view view :cursor cursor :violation [:click-on-empty-result mode]}
          (= mode (inventory/click-modes :quick-move))
          {:view (craft-all view layout) :cursor cursor}
          (or (nil? cursor)
              (and (= (:item cursor) (:item out))
                   (<= (+ (:count cursor) (:count out)) (stack-size (:item out)))))
          {:view (craft-once view layout) :cursor (update out :count + (:count cursor 0))}
          :else {:view view :cursor cursor})))

(defmethod click-view :swap
  ;; the slot and hotbar slot (hotbar + button) trade places, unless the slot refuses the
  ;; hotbar's item
  [layout view cursor {:keys [slot button]}]
  (let [at (get view slot)
        h (+ (inventory/hotbar-start layout) button)
        other (get view h)]
    (if (and other (not (accepts? layout slot other)))
      {:view view :cursor cursor}
      {:view (-> view
                 (dissoc slot h)
                 (cond-> other (assoc slot other)
                         at (assoc h at)))
       :cursor cursor})))

(defmethod click-view :shift
  ;; a container's own stack (a grid cell, a furnace slot) moves into the store, what does not
  ;; fit stays; anywhere else nothing
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (if (and at (some #{slot} (inventory/own-slots layout)))
      (let [[view' left] (insert (dissoc view slot) (inventory/store-slots layout) at)]
        {:view (cond-> view' left (assoc slot left)) :cursor cursor})
      {:view view :cursor cursor})))

(defmethod click-view :throw
  ;; with nothing on the cursor, the stack (or one item of it) leaves the window onto the ground;
  ;; with a loaded cursor nothing happens (vanilla's doClick). The model makes no item entity of
  ;; it, so nothing picks it up again
  [_layout view cursor {:keys [slot button]}]
  (let [at (get view slot)]
    (cond (or cursor (nil? at)) {:view view :cursor cursor}
          (or (= button inventory/whole-stack) (= 1 (:count at)))
          {:view (dissoc view slot) :cursor cursor}
          :else {:view (assoc view slot (update at :count dec)) :cursor cursor})))

(defmethod click-view :left
  ;; pick up, put down, merge up to a full stack (the rest stays on the cursor), or swap; a slot
  ;; that refuses the cursor's item gives what fits of the same item instead
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (nil? cursor) {:view (dissoc view slot) :cursor at}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (nil? at) {:view (assoc view slot cursor) :cursor nil}
          (= (:item at) (:item cursor))
          (let [k (min (:count cursor) (- (stack-size (:item at)) (:count at)))
                left (- (:count cursor) k)]
            {:view (assoc view slot (update at :count + k))
             :cursor (when (pos? left) (assoc cursor :count left))})
          :else {:view (assoc view slot cursor) :cursor at})))

(defmethod click-view :right
  ;; an empty cursor picks up half (rounded up); a loaded one places one into an empty or
  ;; same-item slot that is not full, or takes from a slot that refuses it as a left click does
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (and (nil? cursor) at)
          (let [half (long (math/ceil (/ (:count at) 2)))
                rest (- (:count at) half)]
            {:cursor (assoc at :count half)
             :view (if (pos? rest) (assoc view slot (assoc at :count rest)) (dissoc view slot))})
          (nil? cursor) {:view view :cursor nil}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (or (nil? at) (and (= (:item at) (:item cursor)) (< (:count at) (stack-size (:item at)))))
          {:view (assoc view slot {:item (:item cursor) :count (inc (:count at 0))})
           :cursor (when (> (:count cursor) 1) (update cursor :count dec))}
          :else {:view view :cursor cursor})))

(defn state-id-path
  "Where the sim keeps the state id of window kind: window 0's, or the open container's."
  [kind]
  (if (= kind :inventory) [:sim/state-id] [:sim/window :state-id]))

(defn window-id
  "The protocol window id of window kind: 0, or the open container's."
  [sim kind]
  (if (= kind :inventory) 0 (get-in sim [:sim/window :id])))

(defn sync-window
  "Answer a click the way vanilla does. The click's own changed-slots and cursor fields are the
   client's prediction: the server records them as what the client believes, then sends only
   what differs from that belief. We always predict no changed slots (so every changed slot is
   sent) and an empty cursor (so the cursor is sent only when it is not empty). A stale state
   id gets the full window instead. predicted-cursor is the cursor the client last claimed."
  [sim kind before predicted-cursor stale?]
  (let [layout (inventory/layouts kind)
        slots (range (inventory/slot-count layout))
        after (full (view-of sim kind) layout)
        id (window-id sim kind)
        path (state-id-path kind)]
    (if stale?
      (let [sim (update-in sim path inc)]
        (send sim
              {:packet/name :container-set-content
               :window-id id
               :state-id (get-in sim path)
               :items (mapv #(get after %) slots)
               :carried (:sim/cursor sim)}))
      (as-> sim sim
        (reduce (fn [sim s]
                  (if (= (get before s) (get after s))
                    sim
                    (let [sim (update-in sim path inc)]
                      (send sim
                            {:packet/name :container-set-slot
                             :window-id id
                             :state-id (get-in sim path)
                             :slot s
                             :item (get after s)}))))
                sim
                slots)
        (if (= predicted-cursor (:sim/cursor sim))
          sim
          (send sim {:packet/name :set-cursor-item :item (:sim/cursor sim)}))))))

(defn window-kind
  "Which window a protocol window id names: :inventory, the open container's kind (:table,
   :furnace) when it is that window's id, else nil."
  [sim window-id]
  (cond (zero? window-id) :inventory
        (= window-id (get-in sim [:sim/window :id])) (get-in sim [:sim/window :kind])))

(defmethod on-packet [:play :container-click]
  [sim {:keys [window-id state-id] :as pkt}]
  (let [n (:sim/clicks sim)
        sim (update sim :sim/clicks inc)
        kind (window-kind sim window-id)
        ;; with a container open, the player's own window is not the open menu: vanilla
        ;; ignores the click, and a careful client never sends it
        sim (cond-> sim
              (and (= kind :inventory) (:sim/window sim))
              (update :sim/violations conj [:click-inventory-while-open window-id]))
        kind (when-not (and (= kind :inventory) (:sim/window sim)) kind)]
    (if (or (nil? kind) (contains? (:sim/drop-clicks sim) n))
      sim
      (let [layout (inventory/layouts kind)
            before (full (view-of sim kind) layout)
            {:keys [view cursor violation]}
            (click-view layout (view-of sim kind) (:sim/cursor sim) pkt)
            sim (cond-> (-> sim
                            (with-view kind view)
                            (assoc :sim/cursor cursor))
                  violation (update :sim/violations conj violation))]
        (sync-window sim
                     kind
                     before
                     (:cursor pkt)
                     (not= state-id (get-in sim (state-id-path kind))))))))

(defn close-window
  "The open container closes: a table's grid goes back into the inventory (vanilla), a
   furnace's slots stay in the furnace; window 0 is synced with what came back."
  [sim]
  (let [store (inventory/store-slots (inventory/layouts :inventory))
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        inv (reduce (fn [inv it] (first (insert inv store it)))
                    (:sim/inv sim)
                    (vals (get-in sim [:sim/window :grid])))]
    (-> sim
        (assoc :sim/inv inv)
        (dissoc :sim/window)
        (sync-window :inventory before (:sim/cursor sim) false))))

(defmethod on-packet [:play :container-close]
  [sim {:keys [window-id]}]
  (let [sim (cond-> sim
              (:sim/cursor sim)
              (update :sim/violations conj [:close-with-cursor (:sim/cursor sim)]))]
    (if (= window-id (get-in sim [:sim/window :id])) (close-window sim) sim)))

(defn give
  "An item entity reaches the inventory: stack it into window 0 and tell the client."
  [sim item]
  (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
        [inv _] (insert (:sim/inv sim) (inventory/store-slots (inventory/layouts :inventory)) item)]
    (sync-window (assoc sim :sim/inv inv) :inventory before (:sim/cursor sim) false)))

(defn open-window
  "Open a container window of kind for the client, closing any open one first as vanilla does:
   a fresh window id with state id 1 (extra carries the kind's own keys: a table's empty grid,
   a furnace's position), then open-screen and the whole window with the real cursor."
  [sim kind extra]
  (let [sim (if-let [old (get-in sim [:sim/window :id])]
              (-> sim
                  close-window
                  (send {:packet/name :container-close :window-id old}))
              sim)
        id (:sim/next-window sim)
        layout (inventory/layouts kind)
        sim (-> sim
                (assoc :sim/window (merge {:id id :kind kind :state-id 1} extra))
                (update :sim/next-window inc))
        view (full (view-of sim kind) layout)]
    (-> sim
        (send {:packet/name :open-screen
               :window-id id
               :menu-type (:menu-type layout)
               :title (byte-array 0)})
        (send {:packet/name :container-set-content
               :window-id id
               :state-id 1
               :items (mapv #(get view %) (range (inventory/slot-count layout)))
               :carried (:sim/cursor sim)}))))

(def fresh-furnace
  "A furnace nothing has been put in: no slots, unlit, no progress (vanilla's data 0-3)."
  {:slots {} :lit 0 :lit-total 0 :cook 0 :cook-total 0})

(def default-cook-ticks 200)          ; vanilla's cook total when no recipe takes the input

(def smelt-by-input
  "item name → the furnace recipe that takes it (smelting.edn)."
  (into {} (for [r recipe/smelting n (:smelt/input r)] [n r])))

(defn smelt-of
  "The furnace recipe that takes a slot's item, or nil."
  [it]
  (some-> it
          :item
          recipe/item-name
          smelt-by-input))

(def furnace-input (inventory/slot-in :furnace [:input])) ; a furnace's own slots, as its window numbers them

(def furnace-fuel (inventory/slot-in :furnace [:fuel]))

(def furnace-output (inventory/slot-in :furnace [:output]))

(defn furnace-data
  "A furnace's properties as container-set-data sends them, {property-id value}: the fire left
   and lit (:lit, :lit-total), the cooking done and to do (:cook, :cook-total), numbered by the
   furnace layout's :properties."
  [f]
  (into {} (for [[k id] (:properties (inventory/layouts :furnace))] [id (get f k)])))

(defn can-burn?
  "Can the furnace's input cook now: a recipe takes it, and the output is empty or holds the
   result with room for one more batch."
  [{:keys [slots]}]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (boolean (and r
                  (or (nil? out)
                      (and (= (:item out) (recipe/item-id (:smelt/result r)))
                           (<= (+ (:count out) (:smelt/count r)) max-stack)))))))

(defn smelt-once
  "A furnace's slots after one batch: one input taken, its result added to the output."
  [slots]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (-> slots
        (take-one furnace-input)
        (assoc furnace-output
               {:item (recipe/item-id (:smelt/result r))
                :count (+ (:count out 0) (:smelt/count r))}))))

(defn reset-on-new-input
  "Vanilla resets the progress, and sets the cook total from the recipe, when the input slot is
   set to a different item; :cooking remembers which item the progress is for."
  [{:keys [slots cooking] :as f}]
  (let [item (:item (get slots furnace-input))]
    (if (= item cooking)
      f
      (cond-> (assoc f
                     :cook 0
                     :cook-total
                     (:smelt/ticks (smelt-of (get slots furnace-input)) default-cook-ticks))
        item (assoc :cooking item)
        (nil? item) (dissoc :cooking)))))

(defn cook
  "One game tick of a furnace, as vanilla's AbstractFurnaceBlockEntity.serverTick: a lit furnace
   burns down one tick; one that is out lights when its input can cook, taking one fuel for
   the fuel's burn ticks (fuel.edn); while lit and able to cook, progress grows, and at the
   cook total one input becomes the result; lit but unable, progress resets; out with no fuel
   or input, progress unwinds two a tick. (A lava bucket's empty bucket is not modelled.)"
  [f]
  (let [f (cond-> (reset-on-new-input f) (pos? (:lit f)) (update :lit dec))
        fuel (get-in f [:slots furnace-fuel])]
    (cond (or (pos? (:lit f)) (and fuel (get-in f [:slots furnace-input])))
          (let [f (if (and (zero? (:lit f)) (can-burn? f))
                    (let [t (get blocks/fuel-by-name (recipe/item-name (:item fuel)) 0)]
                      (-> f
                          (assoc :lit t :lit-total t)
                          (update :slots take-one furnace-fuel)))
                    f)]
            (cond (not (and (pos? (:lit f)) (can-burn? f))) (assoc f :cook 0)
                  (= (inc (:cook f)) (:cook-total f))
                  (-> f
                      (assoc :cook 0)
                      (update :slots smelt-once))
                  :else (update f :cook inc)))
          (pos? (:cook f)) (update f :cook #(max 0 (- % 2)))
          :else f)))

(defn send-data
  "container-set-data for every property of the open window that changed between before and
   after (vanilla sends a property only when it differs from what the client was last told)."
  [sim before after]
  (reduce (fn [sim p]
            (if (= (get before p) (get after p))
              sim
              (send sim
                    {:packet/name :container-set-data
                     :window-id (get-in sim [:sim/window :id])
                     :property p
                     :value (get after p)})))
          sim
          (sort (keys after))))

(defn open-furnace
  "Open the furnace at pos (one is made there if the model has none yet): its window, then
   every property, as vanilla sends a menu's whole state on opening."
  [sim pos]
  (let [sim (update-in sim [:sim/furnaces pos] #(or % fresh-furnace))]
    (-> sim
        (open-window :furnace {:pos pos})
        (send-data {} (furnace-data (get-in sim [:sim/furnaces pos]))))))

(defn tick-furnaces
  "One game tick of every furnace, open or not (a furnace cooks with nobody watching); the one
   whose window is open tells the client what changed: slots with a state id each, then the
   properties."
  [sim]
  (reduce (fn [sim [pos f]]
            (let [open? (= pos (get-in sim [:sim/window :pos]))
                  before (when open? (view-of sim :furnace))
                  f' (cook f)
                  sim (assoc-in sim [:sim/furnaces pos] f')]
              (cond-> sim
                open?
                (-> (sync-window :furnace before (:sim/cursor sim) false)
                    (send-data (furnace-data f) (furnace-data f'))))))
          sim
          (:sim/furnaces sim)))
