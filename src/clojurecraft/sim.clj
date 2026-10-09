(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, pickup when the player comes
   within the inflated pickup box after the 10-tick delay, and window 0 (the player's
   inventory with its 2x2 crafting grid) with vanilla click rules and state ids.

   Fault knobs are inputs, not hidden state: :sim/drop-clicks is a set of click ordinals
   (0-based) the server silently loses. :sim/violations records anything a real server would
   punish or a careful client must never do: a click on an empty result slot, a close with a
   loaded cursor."
  (:refer-clojure :exclude [send])
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.recipe :as recipe]))

(def dig-ms 3000)
(def pickup-delay 500)
(def keep-alive-every 15000)

(defn init [{:keys [column spawn keep-alive-every drop-clicks inventory] :or {keep-alive-every keep-alive-every}}]
  {:sim/phase :handshake
   :sim/inv (or inventory {})
   :sim/state-id 1
   :sim/clicks 0
   :sim/drop-clicks (or drop-clicks #{})
   :sim/violations []
   :sim/keep-alive-every keep-alive-every
   :sim/now 0
   :sim/out []
   :sim/column column
   :sim/spawn spawn
   :sim/broken #{}
   :sim/items {}
   :sim/next-eid 100
   :sim/next-keep-alive keep-alive-every
   :sim/keep-alives-pending #{}})

(defn- send [sim pkt] (update sim :sim/out conj pkt))

(defmulti on-packet (fn [sim pkt] [(:sim/phase sim) (:packet/name pkt)]))
(defmethod on-packet :default [sim _] sim)

(defmethod on-packet [:handshake :intention] [sim _] (assoc sim :sim/phase :login))
(defmethod on-packet [:login :hello] [sim {:keys [username uuid]}]
  (send sim {:packet/name :login-finished :uuid uuid :username username}))
(defmethod on-packet [:login :login-acknowledged] [sim _]
  (-> sim (assoc :sim/phase :configuration) (send {:packet/name :select-known-packs})))
(defmethod on-packet [:configuration :select-known-packs] [sim _]
  (send sim {:packet/name :finish-configuration}))
(defmethod on-packet [:configuration :finish-configuration] [sim _]
  (let [[x y z] (:sim/spawn sim)]
    (-> sim
        (assoc :sim/phase :play)
        (send {:packet/name :login :entity-id 1})
        (send {:packet/name :player-position :teleport-id 1 :x x :y y :z z :dx 0.0 :dy 0.0 :dz 0.0
               :yaw 0.0 :pitch 0.0 :flags 0})
        (send {:packet/name :container-set-content :window-id 0 :state-id (:sim/state-id sim)
               :items (mapv #(get-in sim [:sim/inv %]) (range 46)) :carried nil})
        (send {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data (:sim/column sim)})
        (send {:packet/name :chunk-batch-finished :batch-size 1}))))

(defmethod on-packet [:play :move-player-pos-rot] [sim {:keys [x y z]}] (assoc sim :sim/player-pos [x y z]))
(defmethod on-packet [:play :move-player-pos] [sim {:keys [x y z]}] (assoc sim :sim/player-pos [x y z]))
(defmethod on-packet [:play :keep-alive] [sim {:keys [id]}] (update sim :sim/keep-alives-pending disj id))

(defmethod on-packet [:play :player-action] [sim {:keys [status pos sequence]}]
  (case (long status)
    0 (assoc sim :sim/dig {:pos pos :at (:sim/now sim)})
    2 (let [{:keys [at] dpos :pos} (:sim/dig sim)
            sim (send sim {:packet/name :block-changed-ack :sequence sequence})]
        (if (and (= dpos pos) (>= (- (:sim/now sim) at) dig-ms) (not (contains? (:sim/broken sim) pos)))
          (let [eid (:sim/next-eid sim)
                [x y z] pos
                item-pos [(+ x 0.5) (+ y 0.25) (+ z 0.5)]]
            (-> sim
                (update :sim/broken conj pos)
                (dissoc :sim/dig)
                (assoc :sim/next-eid (inc eid))
                (assoc-in [:sim/items eid] {:pos item-pos :spawned (:sim/now sim)})
                (send {:packet/name :add-entity :entity-id eid :uuid nil :type blocks/item-entity-type
                       :x (first item-pos) :y (second item-pos) :z (nth item-pos 2)})))
          (dissoc sim :sim/dig)))
    sim))

;; ---------------------------------------------------------------- window 0

(def max-stack 64)

(defn- result-slot
  "What slot 0 shows: the match of grid slots 1-4, per the recipe table."
  [inv]
  (let [grid (into {} (for [s [1 2 3 4] :let [it (get inv s)] :when it] [s (recipe/item-name (:item it))]))]
    (when-let [r (recipe/match grid 2)]
      {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)})))

(defn- slots [sim] (assoc (:sim/inv sim) 0 (result-slot (:sim/inv sim))))

(defn- insert
  "Put a stack into window-0 inventory slots 9-44, stacking first. Returns [inv leftover]."
  [inv {:keys [item count]}]
  (let [order (concat (filter #(= item (:item (get inv %))) (range 9 45)) (filter #(nil? (get inv %)) (range 9 45)))]
    (loop [inv inv [s & more] order n count]
      (if (or (zero? n) (nil? s))
        [inv (when (pos? n) {:item item :count n})]
        (let [have (:count (get inv s) 0) k (min n (- max-stack have))]
          (recur (assoc inv s {:item item :count (+ have k)}) more (- n k)))))))

(defn- take-one [inv s]
  (let [{:keys [count] :as it} (get inv s)]
    (if (> count 1) (assoc inv s (assoc it :count (dec count))) (dissoc inv s))))

(defn- craft-once
  "Consume one item from each grid cell; returns inv'."
  [inv]
  (reduce (fn [inv s] (if (get inv s) (take-one inv s) inv)) inv [1 2 3 4]))

(defn- click [{:sim/keys [inv cursor] :as sim} {:keys [slot button mode]}]
  (let [at (get inv slot)]
    (cond
      (= slot 0)
      (if-let [out (result-slot inv)]
        (if (= mode 1)
          (loop [inv inv]
            (if-let [out (result-slot inv)]
              (let [[inv' left] (insert inv out)]
                (if left inv (recur (craft-once inv'))))
              (assoc sim :sim/inv inv)))
          (if (or (nil? cursor) (and (= (:item cursor) (:item out)) (<= (+ (:count cursor) (:count out)) max-stack)))
            (assoc sim :sim/inv (craft-once inv) :sim/cursor (update out :count + (:count cursor 0)))
            sim))
        (update sim :sim/violations conj [:click-on-empty-result mode]))

      (= mode 1)
      (if (and at (<= 1 slot 4))
        (let [[inv' left] (insert (dissoc inv slot) at)] (assoc sim :sim/inv (cond-> inv' left (assoc slot left))))
        sim)

      (= button 0)
      (cond (nil? cursor) (assoc sim :sim/inv (dissoc inv slot) :sim/cursor at)
            (nil? at) (assoc sim :sim/inv (assoc inv slot cursor) :sim/cursor nil)
            (= (:item at) (:item cursor))
            (let [k (min (:count cursor) (- max-stack (:count at)))
                  left (- (:count cursor) k)]
              (assoc sim :sim/inv (assoc inv slot (update at :count + k))
                     :sim/cursor (when (pos? left) (assoc cursor :count left))))
            :else (assoc sim :sim/inv (assoc inv slot cursor) :sim/cursor at))

      :else
      (cond (nil? cursor) (if at
                            (let [half (long (Math/ceil (/ (:count at) 2)))
                                  rest (- (:count at) half)]
                              (assoc sim :sim/cursor (assoc at :count half)
                                     :sim/inv (if (pos? rest) (assoc inv slot (assoc at :count rest)) (dissoc inv slot))))
                            sim)
            (or (nil? at) (and (= (:item at) (:item cursor)) (< (:count at) max-stack)))
            (let [cursor' (when (> (:count cursor) 1) (update cursor :count dec))]
              (assoc sim :sim/inv (assoc inv slot {:item (:item cursor) :count (inc (:count at 0))}) :sim/cursor cursor'))
            :else sim))))

(defn- sync-window
  "Answer a click the way vanilla does. The click's own changed-slots and cursor fields are the
   client's prediction: the server records them as what the client believes, then sends only
   what differs from that belief. We always predict no changed slots (so every changed slot is
   sent) and an empty cursor (so the cursor is sent only when it is not empty). A stale state
   id gets the full window instead. predicted-cursor is the cursor the client last claimed."
  [sim before-slots predicted-cursor stale?]
  (let [after (slots sim)]
    (if stale?
      (let [sim (update sim :sim/state-id inc)]
        (send sim {:packet/name :container-set-content :window-id 0 :state-id (:sim/state-id sim)
                   :items (mapv #(get after %) (range 46)) :carried (:sim/cursor sim)}))
      (as-> sim sim
        (reduce (fn [sim s]
                  (if (= (get before-slots s) (get after s))
                    sim
                    (let [sim (update sim :sim/state-id inc)]
                      (send sim {:packet/name :container-set-slot :window-id 0 :state-id (:sim/state-id sim)
                                 :slot s :item (get after s)}))))
                sim (range 46))
        (if (= predicted-cursor (:sim/cursor sim)) sim (send sim {:packet/name :set-cursor-item :item (:sim/cursor sim)}))))))

(defmethod on-packet [:play :container-click] [sim {:keys [window-id state-id] :as pkt}]
  (let [n (:sim/clicks sim)
        sim (update sim :sim/clicks inc)]
    (if (or (not= 0 window-id) (contains? (:sim/drop-clicks sim) n))
      sim
      (let [before (slots sim)]
        (-> (click sim pkt) (sync-window before (:cursor pkt) (not= state-id (:sim/state-id sim))))))))

(defmethod on-packet [:play :container-close] [sim _]
  (cond-> sim (:sim/cursor sim) (update :sim/violations conj [:close-with-cursor (:sim/cursor sim)])))

(defn- give
  "An item entity reaches the inventory: stack it into window 0 and tell the client."
  [sim item]
  (let [before (slots sim)
        [inv _] (insert (:sim/inv sim) item)]
    (sync-window (assoc sim :sim/inv inv) before (:sim/cursor sim) false)))

(defn- within-pickup? [[px py pz] [ix iy iz]]
  (and (< (abs (- px ix)) 1.3) (< (abs (- pz iz)) 1.3) (< -0.5 (- iy py) 2.3)))

(defn- tick [sim now]
  (let [sim (assoc sim :sim/now now)
        sim (if (>= now (:sim/next-keep-alive sim))
              (-> sim
                  (send {:packet/name :keep-alive :id now})
                  (update :sim/keep-alives-pending conj now)
                  (assoc :sim/next-keep-alive (+ now (:sim/keep-alive-every sim))))
              sim)]
    (reduce (fn [sim [eid {:keys [pos spawned]}]]
              (if (and (:sim/player-pos sim)
                       (>= (- now spawned) pickup-delay)
                       (within-pickup? (:sim/player-pos sim) pos))
                (-> sim
                    (update :sim/items dissoc eid)
                    (send {:packet/name :take-item-entity :collected eid :collector 1 :count 1})
                    (send {:packet/name :remove-entities :ids [eid]})
                    (give {:item (:oak_log blocks/items) :count 1}))
                sim))
            sim (:sim/items sim))))

(defn step [sim {:sim/keys [kind packet now]}]
  (case kind
    :packet (on-packet sim packet)
    :tick (tick sim now)
    sim))

(defn drain
  "[sim' packets-for-the-client]"
  [sim]
  [(assoc sim :sim/out []) (:sim/out sim)])

(defn- sends [world]
  (mapv :effect/packet (filter #(= :send (:effect/kind %)) (:bot/effects world))))

(defn run
  "Run a bot reducer against the model from a fresh handshake, 50 ms per tick, until (stop?
   world) or max-ms. Sends go (default {:event/kind :go}) once the bot is loaded. Returns
   [world sim]."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [w (bot-step world0 {:event/kind :start})]
    (loop [world (assoc w :bot/effects []) sim sim0 pending (sends w) t 0 went? false]
      (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
            sim (step sim {:sim/kind :tick :sim/now t})
            [sim inbound] (drain sim)
            world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
            world (bot-step world {:event/kind :tick :event/now t :event/rand 0.5})
            go? (and (not went?) (:player/loaded? world))
            world (if go? (bot-step world (or go {:event/kind :go})) world)
            out (sends world)
            world (assoc world :bot/effects [])]
        (if (or (stop? world) (> t max-ms))
          [world sim]
          (recur world sim out (+ t 50) (or went? go?)))))))
