(ns clojurecraft.game
  "The world as one value, and the protocol as a pure reducer over it.

     (step state event) → {:state state' :effects [[:send pkt] [:log s] ...]}

   Events are [:start], [:packet pkt], [:tick now-ms] and [:closed reason]. Handlers are plain
   functions (state, packet) → state' that queue outgoing packets with `send`; step strips the
   queue into effects and folds the protocol phase through the transitions table so the phase
   has one source of truth (clojurecraft.packet/transitions)."
  (:refer-clojure :exclude [send])
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]))

(def protocol-version 775)

(defn init [{:keys [name] :as opts}]
  {:phase :handshake
   :opts (assoc opts :uuid (b/offline-uuid name))
   :now 0
   :tick 0
   :player {:pos nil :vel [0.0 0.0 0.0] :look [0.0 0.0] :on-ground? false
            :horizontal-collision? false :jump-ticks 0 :teleport-id nil :synced-at nil :loaded? false}
   :controls {}
   :chunks {}
   :blocks {}
   :inventory {}
   :entities {}
   :sequence 0
   :sent {}
   :stats {:unknown {} :keep-alives 0 :teleports 0 :chunks 0}})

;; ---------------------------------------------------------------- effects

(defn send [state pkt] (update state ::out (fnil conj []) [:send pkt]))
(defn log [state msg] (update state ::out (fnil conj []) [:log msg]))

;; ---------------------------------------------------------------- queries

(defn block-at
  "State id at [x y z]: local overlay first, then the chunk; nil when unloaded."
  [state pos]
  (or (get (:blocks state) pos) (chunk/block-at (:chunks state) pos)))

(defn solid-fn
  "Solidity oracle for physics. Unloaded is solid so the bot never falls through the world."
  [state]
  (fn [x y z]
    (let [id (block-at state [x y z])]
      (if (nil? id) true (blocks/solid? id)))))

(defn eye [state] (physics/eye (get-in state [:player :pos])))

(defn logs-held [state]
  (reduce + 0 (for [[_ {:keys [item count]}] (:inventory state) :when (blocks/log-item? item)] count)))

(defn chunk-loaded? [state [x _ z]]
  (contains? (:chunks state) [(bit-shift-right (long (Math/floor x)) 4) (bit-shift-right (long (Math/floor z)) 4)]))

(defn container->player-slot
  "Window-0 slot → player-inventory slot (the key space of :inventory), or nil."
  [^long s]
  (cond (<= 36 s 44) (- s 36)
        (<= 9 s 35) s
        (<= 5 s 8) (+ s 31)
        (= s 45) 40
        :else nil))

(defn- set-slot [state slot item]
  (if item (assoc-in state [:inventory slot] item) (update state :inventory dissoc slot)))

;; ---------------------------------------------------------------- handlers

(defn- on-ground-flag [state] (if (get-in state [:player :on-ground?]) 1 0))

(defn- position-packet [state]
  (let [{:keys [pos look]} (:player state)
        [x y z] pos [yaw pitch] look]
    {:name :move-player-pos-rot :x x :y y :z z :yaw yaw :pitch pitch :flags (on-ground-flag state)}))

(defn- send-position [state]
  (-> (send state (position-packet state))
      (assoc :sent {:pos (get-in state [:player :pos]) :look (get-in state [:player :look]) :tick (:tick state)})))

(defn- relative [flags bit old new] (if (pos? (bit-and flags bit)) (+ old new) new))

(defn- apply-teleport [state {:keys [teleport-id x y z yaw pitch flags]}]
  (let [[ox oy oz] (or (get-in state [:player :pos]) [0.0 0.0 0.0])
        [oyaw opitch] (get-in state [:player :look])
        pos [(relative flags 1 ox x) (relative flags 2 oy y) (relative flags 4 oz z)]
        look [(relative flags 8 oyaw yaw) (relative flags 16 opitch pitch)]
        loaded? (get-in state [:player :loaded?])]
    (-> state
        (update :player assoc :pos pos :look look :vel [0.0 0.0 0.0] :on-ground? false
                :teleport-id teleport-id :synced-at (:now state))
        (update-in [:stats :teleports] inc)
        (send {:name :accept-teleportation :teleport-id teleport-id})
        send-position
        (cond-> (not loaded?) (-> (send {:name :player-loaded})
                                  (assoc-in [:player :loaded?] true))))))

(defn- chunk-key [v] [(long (unchecked-int v)) (long (unchecked-int (bit-shift-right v 32)))])

(defn- section-update [state {:keys [section blocks]}]
  (let [sx (bit-shift-right section 42)
        sz (bit-shift-right (bit-shift-left section 22) 42)
        sy (bit-shift-right (bit-shift-left section 44) 44)]
    (reduce (fn [state v]
              (let [id (unsigned-bit-shift-right v 12)
                    lx (bit-and (bit-shift-right v 8) 15)
                    lz (bit-and (bit-shift-right v 4) 15)
                    ly (bit-and v 15)]
                (assoc-in state [:blocks [(+ (* 16 sx) lx) (+ (* 16 sy) ly) (+ (* 16 sz) lz)]] id)))
            state blocks)))

(defn- load-chunk [state {:keys [x z data]}]
  (let [key [x z]]
    (-> state
        (assoc-in [:chunks key] (chunk/decode data))
        (update :blocks (fn [m] (into {} (remove (fn [[[bx _ bz] _]] (= key [(bit-shift-right bx 4) (bit-shift-right bz 4)])) m))))
        (update-in [:stats :chunks] inc))))

(def handlers
  {[:login :login-finished] (fn [s _] (send s {:name :login-acknowledged}))
   [:login :login-disconnect] (fn [s p] (assoc s :disconnected (:reason p)))
   [:configuration :select-known-packs] (fn [s _] (send s {:name :select-known-packs :packs []}))
   [:configuration :keep-alive] (fn [s p] (-> s (send {:name :keep-alive :id (:id p)}) (update-in [:stats :keep-alives] inc)))
   [:configuration :ping] (fn [s p] (send s {:name :pong :id (:id p)}))
   [:configuration :finish-configuration] (fn [s _] (send s {:name :finish-configuration}))
   [:configuration :disconnect] (fn [s p] (assoc s :disconnected (String. ^bytes (:reason p) "ISO-8859-1")))
   [:play :login] (fn [s p] (-> s (assoc :entity-id (:entity-id p))
                                (send {:name :client-information :locale "en_US" :view-distance 6 :chat-mode 0
                                       :chat-colors true :skin-parts 0x7f :main-hand 1 :text-filtering false
                                       :server-listing true :particle-status 0})))
   [:play :keep-alive] (fn [s p] (-> s (send {:name :keep-alive :id (:id p)}) (update-in [:stats :keep-alives] inc)))
   [:play :ping] (fn [s p] (send s {:name :pong :id (:id p)}))
   [:play :player-position] apply-teleport
   [:play :chunk-batch-finished] (fn [s _] (send s {:name :chunk-batch-received :chunks-per-tick 20.0}))
   [:play :level-chunk-with-light] load-chunk
   [:play :forget-level-chunk] (fn [s p] (update s :chunks dissoc (chunk-key (:pos p))))
   [:play :block-update] (fn [s p] (assoc-in s [:blocks (:pos p)] (:state p)))
   [:play :section-blocks-update] section-update
   [:play :set-health] (fn [s p] (assoc s :health (:health p)))
   [:play :container-set-content]
   (fn [s {:keys [window-id items]}]
     (if (zero? window-id)
       (reduce (fn [s [i item]] (if-let [slot (container->player-slot i)] (set-slot s slot item) s))
               s (map-indexed vector items))
       s))
   [:play :container-set-slot]
   (fn [s {:keys [window-id slot item]}]
     (if-let [slot (and (zero? window-id) (container->player-slot slot))] (set-slot s slot item) s))
   [:play :set-player-inventory] (fn [s {:keys [slot item]}] (set-slot s slot item))
   [:play :add-entity] (fn [s {:keys [entity-id type x y z]}]
                         (if (= type blocks/item-entity-type)
                           (assoc-in s [:entities entity-id] {:type type :pos [x y z] :seen (:now s)})
                           s))
   [:play :move-entity-pos] (fn [s {:keys [entity-id dx dy dz]}]
                              (if (get-in s [:entities entity-id])
                                (update-in s [:entities entity-id :pos]
                                           (fn [[x y z]] [(+ x (/ dx 4096.0)) (+ y (/ dy 4096.0)) (+ z (/ dz 4096.0))]))
                                s))
   [:play :move-entity-pos-rot] (fn [s {:keys [entity-id dx dy dz]}]
                                  (if (get-in s [:entities entity-id])
                                    (update-in s [:entities entity-id :pos]
                                               (fn [[x y z]] [(+ x (/ dx 4096.0)) (+ y (/ dy 4096.0)) (+ z (/ dz 4096.0))]))
                                    s))
   [:play :entity-position-sync] (fn [s {:keys [entity-id x y z]}]
                                   (if (get-in s [:entities entity-id]) (assoc-in s [:entities entity-id :pos] [x y z]) s))
   [:play :remove-entities] (fn [s {:keys [ids]}] (update s :entities #(apply dissoc % ids)))
   [:play :take-item-entity] (fn [s p] (update-in s [:stats :pickups] (fnil conj []) p))
   [:play :block-changed-ack] (fn [s p] (assoc-in s [:stats :last-ack] (:sequence p)))
   [:play :start-configuration] (fn [s _] (send s {:name :configuration-acknowledged}))
   [:play :disconnect] (fn [s p] (assoc s :disconnected (String. ^bytes (:reason p) "ISO-8859-1")))})

(defn- on-packet [state pkt]
  (case (:name pkt)
    :unknown (update-in state [:stats :unknown [(:phase state) (:id pkt)]] (fnil inc 0))
    :decode-error (log state (str "decode error " pkt))
    :closed (assoc state :closed (:reason pkt))
    (if-let [h (handlers [(:phase state) (:name pkt)])]
      (h state pkt)
      state)))

;; ---------------------------------------------------------------- ticks

(defn- physics-ready? [state]
  (let [{:keys [pos loaded?]} (:player state)]
    (and (= :play (:phase state)) pos loaded? (chunk-loaded? state pos))))

(defn- movement-packets
  "pos-rot when something changed, status-only once a second otherwise (vanilla's rule)."
  [state]
  (let [{:keys [pos look]} (:player state)
        {:keys [tick] :as sent} (:sent state)]
    (cond
      (or (not= pos (:pos sent)) (not= look (:look sent))) (send-position state)
      (>= (- (:tick state) (or tick 0)) 20)
      (-> (send state {:name :move-player-status-only :flags (on-ground-flag state)})
          (assoc-in [:sent :tick] (:tick state)))
      :else state)))

(defn- on-tick [state now]
  (let [state (-> state (assoc :now now) (update :tick inc))]
    (if (physics-ready? state)
      (let [controls (:controls state)
            player (physics/step (solid-fn state) (:player state) controls)
            look (if-let [l (:look controls)] l (get-in state [:player :look]))]
        (-> state
            (update :player merge player)
            (assoc-in [:player :look] look)
            movement-packets))
      state)))

;; ---------------------------------------------------------------- step

(defn- fold-phase [state effects]
  (reduce (fn [s [kind pkt]] (if (= kind :send) (update s :phase p/next-state (:name pkt)) s)) state effects))

(defn step [state [kind x :as event]]
  (let [state (case kind
                :start (-> state
                           (send {:name :intention :protocol-version protocol-version
                                  :host (get-in state [:opts :host]) :port (get-in state [:opts :port]) :next-state 2})
                           (send {:name :hello :username (get-in state [:opts :name]) :uuid (get-in state [:opts :uuid])}))
                :packet (on-packet state x)
                :tick (on-tick state x)
                :closed (assoc state :closed x)
                state)
        effects (or (::out state) [])]
    {:state (fold-phase (dissoc state ::out) effects)
     :effects effects}))

(defn compose
  "Several reducers with the step signature into one; effects concatenate."
  [& steps]
  (fn [state event]
    (reduce (fn [{:keys [state effects]} f]
              (let [r (f state event)]
                {:state (:state r) :effects (into effects (:effects r))}))
            {:state state :effects []}
            steps)))

(defn summary [state]
  (let [{:keys [pos on-ground? loaded?]} (:player state)]
    {:phase (:phase state) :pos pos :on-ground? on-ground? :loaded? loaded?
     :chunks (count (:chunks state)) :logs (logs-held state) :inventory (count (:inventory state))
     :entities (count (:entities state)) :stats (:stats state)
     :disconnected (:disconnected state) :closed (:closed state)}))
