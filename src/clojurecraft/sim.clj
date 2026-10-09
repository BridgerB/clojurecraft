(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, and pickup when the player comes
   within the inflated pickup box after the 10-tick delay."
  (:refer-clojure :exclude [send])
  (:require [clojurecraft.blocks :as blocks]))

(def dig-ms 3000)
(def pickup-delay 500)
(def keep-alive-every 15000)

(defn init [{:keys [column spawn keep-alive-every] :or {keep-alive-every keep-alive-every}}]
  {:sim/phase :handshake
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
                    (send {:packet/name :set-player-inventory :slot 0 :item {:item (:oak_log blocks/items) :count 1}}))
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
   world) or max-ms. Sends :go once the bot is loaded. Returns the final world."
  [bot-step world0 sim0 stop? max-ms]
  (let [w (bot-step world0 {:event/kind :start})]
    (loop [world (assoc w :bot/effects []) sim sim0 pending (sends w) t 0 went? false]
      (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
            sim (step sim {:sim/kind :tick :sim/now t})
            [sim inbound] (drain sim)
            world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
            world (bot-step world {:event/kind :tick :event/now t :event/rand 0.5})
            go? (and (not went?) (:player/loaded? world))
            world (if go? (bot-step world {:event/kind :go}) world)
            out (sends world)
            world (assoc world :bot/effects [])]
        (if (or (stop? world) (> t max-ms))
          world
          (recur world sim out (+ t 50) (or went? go?)))))))
