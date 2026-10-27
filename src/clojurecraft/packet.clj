(ns clojurecraft.packet
  "Packet specs as data, and one interpreter that reads and writes them.

   A packet is a flat map with a :packet/name; its fields are the wire's own names, unqualified,
   because they are scoped by the packet. A spec is a vector of [key type] pairs; a type is a
   keyword for a primitive, [:vec T] for a varint-counted sequence, or a vector of pairs for a
   nested struct. Ids come from resources/clojurecraft/packets.edn (generated from the vanilla
   reports). Decoding reads only the listed fields and ignores the tail of the frame: that is
   how big packets (chunks) skip the parts we do not model. Unknown ids decode to
   {:packet/name :unknown ...}; nothing here throws on foreign data."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.bytes :as b]))

(set! *warn-on-reflection* true)

(def ids
  "{state {:s2c {name id} :c2s {name id}}}"
  (edn/read-string (slurp (io/resource "clojurecraft/packets.edn"))))

(def names
  "{state {dir {id name}}}"
  (into {}
        (for [[state dirs] ids]
          [state (into {} (for [[dir m] dirs] [dir (into {} (map (fn [[n i]] [i n]) m))]))])))

(def values
  "The numbers some packet fields carry, by name: each enum as 26.1.2 numbers it (its ordinal in
   the game's own enum, named after it). Code writes (value :hand :main-hand) and the packet still
   carries the number, so a field says what it means without the wire changing."
  {:hand {:main-hand 0 :off-hand 1}                         ; InteractionHand
   :dig {:start-destroy 0 :abort-destroy 1 :stop-destroy 2} ; player-action's status
   :face {:down 0 :up 1 :north 2 :south 3 :west 4 :east 5}  ; Direction
   :next-state {:status 1 :login 2 :transfer 3}             ; intention, the handshake
   :client-command {:perform-respawn 0 :request-stats 1}    ; client-command's action
   :game-event {:win-game 4} ; game-event's event: the credits after the exit portal (the rest unread)
   :boss-event {:add 0 :remove 1 :update-health 2} ; boss-event's action (BossEvent operation ordinals; the rest unread)
   :chat-mode {:enabled 0 :commands-only 1 :hidden 2} ; ChatVisiblity
   :main-hand {:left 0 :right 1}                      ; HumanoidArm
   :particle-status {:all 0 :decreased 1 :minimal 2}  ; ParticleStatus
   :game-type {:none -1 :survival 0 :creative 1 :adventure 2 :spectator 3}}) ; GameType

(defn value
  "The number enum k (a key of values) gives name: (value :face :up) is 1. A name the enum does
   not have is a mistake in the code, so it throws rather than send a nil."
  [k name]
  (let [n (get-in values [k name])]
    (when (nil? n) (throw (ex-info "no such packet value" {:enum k :name name})))
    n))

(def all-skin-parts 0x7f)             ; client-information's skin parts: every bit, all shown

(def specs
  {;; handshake / login
   [:handshake :c2s :intention] [[:protocol-version :varint] [:host :string] [:port :u16]
                                 [:next-state :varint]]
   [:login :c2s :hello] [[:username :string] [:uuid :uuid]]
   [:login :s2c :login-compression] [[:threshold :varint]]
   [:login :s2c :login-finished] [[:uuid :uuid] [:username :string]]
   [:login :s2c :login-disconnect] [[:reason :string]]
   [:login :c2s :login-acknowledged] []
   ;; configuration
   [:configuration :s2c :select-known-packs] []
   [:configuration :c2s :select-known-packs]
   [[:packs [:vec [[:namespace :string] [:id :string] [:version :string]]]]]
   [:configuration :s2c :keep-alive] [[:id :i64]]
   [:configuration :c2s :keep-alive] [[:id :i64]]
   [:configuration :s2c :ping] [[:id :i32]]
   [:configuration :c2s :pong] [[:id :i32]]
   [:configuration :s2c :finish-configuration] []
   [:configuration :c2s :finish-configuration] []
   [:configuration :s2c :disconnect] [[:reason :rest]]
   ;; play, server → client
   ;; login: the player's id, the server's settings, then CommonPlayerSpawnInfo as respawn has it,
   ;; so a bot that joins in the Nether knows it; the death location and what follows are an
   ;; unmodelled tail. Field order read from the game's ClientboundLoginPacket.write (issue #11)
   [:play :s2c :login]
   [[:entity-id :i32] [:hardcore? :bool] [:levels [:vec :string]] [:max-players :varint]
    [:view-distance :varint] [:simulation-distance :varint] [:reduced-debug? :bool]
    [:death-screen? :bool] [:limited-crafting? :bool] [:dimension-type :varint] [:dimension :string]
    [:seed :i64] [:game-type :u8] [:previous-game-type :i8] [:debug? :bool] [:flat? :bool]]
   [:play :s2c :keep-alive] [[:id :i64]]
   ;; the clock, sent every 20 server ticks (MinecraftServer.tickChildren's synchronizeTime): its
   ;; arrival is what is read (game's :server/tick-ms), its fields are left undecoded
   [:play :s2c :set-time] []
   [:play :s2c :ping] [[:id :i32]]
   [:play :s2c :player-position] [[:teleport-id :varint] [:x :f64] [:y :f64] [:z :f64] [:dx :f64]
                                  [:dy :f64] [:dz :f64] [:yaw :f32] [:pitch :f32] [:flags :u32]]
   [:play :s2c :chunk-batch-finished] [[:batch-size :varint]]
   [:play :s2c :level-chunk-with-light]
   [[:x :i32] [:z :i32] [:heightmaps [:vec [[:type :varint] [:data [:vec :i64]]]]] [:data :bytes]]
   [:play :s2c :forget-level-chunk] [[:pos :i64]]
   [:play :s2c :block-update] [[:pos :position] [:state :varint]]
   [:play :s2c :section-blocks-update] [[:section :i64] [:blocks [:vec :varlong]]]
   [:play :s2c :set-health] [[:health :f32] [:food :varint] [:saturation :f32]]
   [:play :s2c :container-set-content] [[:window-id :varint] [:state-id :varint]
                                        [:items [:vec :slot]] [:carried :slot]]
   [:play :s2c :container-set-slot] [[:window-id :varint] [:state-id :varint] [:slot :i16]
                                     [:item :slot]]
   [:play :s2c :set-player-inventory] [[:slot :varint] [:item :slot]]
   ;; add-entity: then its velocity (an LpVec3), pitch, yaw and head yaw in 1/256 turns, and its
   ;; data (an item frame's facing, a falling block's state), field order read from the game's
   ;; ClientboundAddEntityPacket.write (probe run 38876275250, issue #258)
   [:play :s2c :add-entity] [[:entity-id :varint] [:uuid :uuid] [:type :varint] [:x :f64] [:y :f64]
                             [:z :f64] [:vel :lpvec3] [:pitch :i8] [:yaw :i8] [:head-yaw :i8]
                             [:data :varint]]
   ;; an entity's new velocity, blocks a tick (ClientboundSetEntityMotionPacket's codec: varint, LpVec3)
   [:play :s2c :set-entity-motion] [[:entity-id :varint] [:vel :lpvec3]]
   ;; an entity moved far: PositionMoveRotation (position, velocity, yaw, pitch), the Relative set
   ;; (as player-position's flags) and on-ground (ClientboundTeleportEntityPacket's codec)
   [:play :s2c :teleport-entity] [[:entity-id :varint] [:x :f64] [:y :f64] [:z :f64] [:dx :f64]
                                  [:dy :f64] [:dz :f64] [:yaw :f32] [:pitch :f32] [:flags :u32]
                                  [:on-ground :bool]]
   ;; an entity's status byte: 3 is a living entity's death (ClientboundEntityEventPacket: an int, a byte)
   [:play :s2c :entity-event] [[:entity-id :i32] [:status :i8]]
   ;; an entity's changed metadata, skimmed (read-metadata)
   [:play :s2c :set-entity-data] [[:entity-id :varint] [:metadata :metadata]]
   [:play :s2c :remove-entities] [[:ids [:vec :varint]]]
   [:play :s2c :move-entity-pos] [[:entity-id :varint] [:dx :i16] [:dy :i16] [:dz :i16]
                                  [:on-ground :bool]]
   ;; the moves' rotations in 1/256 turns, then on-ground (ClientboundMoveEntityPacket.PosRot and
   ;; .Rot); the sync's is PositionMoveRotation then on-ground, as teleport-entity's (issue #267:
   ;; the dragon's yaw, where its head is)
   [:play :s2c :move-entity-pos-rot] [[:entity-id :varint] [:dx :i16] [:dy :i16] [:dz :i16]
                                      [:yaw :i8] [:pitch :i8] [:on-ground :bool]]
   [:play :s2c :move-entity-rot] [[:entity-id :varint] [:yaw :i8] [:pitch :i8] [:on-ground :bool]]
   [:play :s2c :entity-position-sync] [[:entity-id :varint] [:x :f64] [:y :f64] [:z :f64] [:dx :f64]
                                       [:dy :f64] [:dz :f64] [:yaw :f32] [:pitch :f32]
                                       [:on-ground :bool]]
   [:play :s2c :take-item-entity] [[:collected :varint] [:collector :varint] [:count :varint]]
   [:play :s2c :block-changed-ack] [[:sequence :varint]]
   [:play :s2c :disconnect] [[:reason :rest]]
   [:play :s2c :open-screen] [[:window-id :varint] [:menu-type :varint] [:title :rest]] ; title is NBT, kept raw
   ;; a container's numbered properties (a furnace: 0 fuel left, 1 fuel total, 2 cook progress,
   ;; 3 cook total), field order read from the game's ClientboundContainerSetDataPacket
   [:play :s2c :container-set-data] [[:window-id :varint] [:property :i16] [:value :i16]]
   [:play :s2c :container-close] [[:window-id :varint]]
   [:play :s2c :set-cursor-item] [[:item :slot]]
   [:play :s2c :set-held-slot] [[:slot :varint]]
   [:play :s2c :start-configuration] []
   ;; respawn: CommonPlayerSpawnInfo first; the optional death location and what follows are an
   ;; unmodelled tail (the decoder ignores the rest of a frame). Field order read from the game's
   ;; own ClientboundRespawnPacket at datagen (issue #5)
   [:play :s2c :respawn] [[:dimension-type :varint] [:dimension :string] [:seed :i64]
                          [:game-type :u8] [:previous-game-type :i8] [:debug? :bool] [:flat? :bool]]
   [:play :s2c :damage-event] [[:entity-id :varint] [:source-type :varint] [:source-cause :varint]
                               [:source-direct :varint]]
   [:play :s2c :player-combat-kill] [[:player-id :varint]]
   [:play :s2c :game-event] [[:event :u8] [:param :f32]]
   ;; a boss bar shown or taken away: its id and the operation; what follows each operation (the
   ;; name, an NBT component) is an unmodelled tail (ClientboundBossEventPacket, issue #267)
   [:play :s2c :boss-event] [[:uuid :uuid] [:action :varint]]
   ;; play, client → server
   [:play :c2s :keep-alive] [[:id :i64]]
   [:play :c2s :pong] [[:id :i32]]
   [:play :c2s :accept-teleportation] [[:teleport-id :varint]]
   [:play :c2s :chunk-batch-received] [[:chunks-per-tick :f32]]
   [:play :c2s :configuration-acknowledged] []
   [:play :c2s :client-information] [[:locale :string] [:view-distance :i8] [:chat-mode :varint]
                                     [:chat-colors :bool] [:skin-parts :u8] [:main-hand :varint]
                                     [:text-filtering :bool] [:server-listing :bool]
                                     [:particle-status :varint]]
   [:play :c2s :player-loaded] []
   [:play :c2s :move-player-pos] [[:x :f64] [:y :f64] [:z :f64] [:flags :u8]]
   [:play :c2s :move-player-pos-rot] [[:x :f64] [:y :f64] [:z :f64] [:yaw :f32] [:pitch :f32]
                                      [:flags :u8]]
   [:play :c2s :move-player-rot] [[:yaw :f32] [:pitch :f32] [:flags :u8]]
   [:play :c2s :move-player-status-only] [[:flags :u8]]
   [:play :c2s :set-carried-item] [[:slot :i16]]
   [:play :c2s :player-action] [[:status :varint] [:pos :position] [:face :i8] [:sequence :varint]]
   [:play :c2s :swing] [[:hand :varint]]
   ;; an attack on an entity, its id alone: 775 split attacking out of interact, which is a
   ;; right-click now (ruststeve src/bot/mod.rs: an interact with mouse 1 dealt nothing, and with
   ;; no hand field got the bot kicked); ServerboundAttackPacket's codec, issue #259
   [:play :c2s :attack] [[:entity-id :varint]]
   ;; right-click with the held item, aimed by look: a bucket fills or pours here, not through
   ;; use-item-on; field order read from the game's ServerboundUseItemPacket
   [:play :c2s :use-item] [[:hand :varint] [:sequence :varint] [:y-rot :f32] [:x-rot :f32]]
   [:play :c2s :client-command] [[:action :varint]] ; 0: perform respawn
   ;; 775 clicks carry the client's prediction as hashed slots; we always predict nothing
   ;; (:changed [] :cursor nil) so the server answers every click with authoritative slots.
   [:play :c2s :container-click]
   [[:window-id :varint] [:state-id :varint] [:slot :i16] [:button :i8] [:mode :varint]
    [:changed [:vec [[:slot :i16] [:item :hashed-slot]]]] [:cursor :hashed-slot]]
   [:play :c2s :container-close] [[:window-id :varint]]
   ;; right-click on a block face: places the held block, or opens a container
   [:play :c2s :use-item-on] [[:hand :varint] [:pos :position] [:face :varint] [:cursor-x :f32]
                              [:cursor-y :f32] [:cursor-z :f32] [:inside-block :bool]
                              [:world-border-hit :bool] [:sequence :varint]]})

(def transitions
  "Protocol state after the client sends a packet: {[state name] next-state}."
  {[:handshake :intention] :login
   [:login :login-acknowledged] :configuration
   [:configuration :finish-configuration] :play
   [:play :configuration-acknowledged] :configuration})

(defn next-state
  "The protocol state after the client sends pkt-name in state (unchanged for most)."
  [state pkt-name]
  (get transitions [state pkt-name] state))

;; ---------------------------------------------------------------- reading

(defn struct?
  "Is a spec type a nested struct (a vector of [key type] pairs)?"
  [t]
  (and (vector? t) (vector? (first t))))

(defn truncating?
  "A slot with components ends what we can parse of a frame."
  [v]
  (boolean (or (and (map? v) (:components? v)) (and (vector? v) (some truncating? v)))))

(defn read-hashed-slot
  "option(item varint, count varint, [type varint, hash i32]..., [type varint]...)."
  [buf]
  (when (b/read-bool buf)
    (let [item (b/read-varint buf)
          n (b/read-varint buf)
          added (b/read-varint buf)
          _ (dotimes [_ added]
              (b/read-varint buf)
              (b/read-i32 buf))
          removed (b/read-varint buf)
          _ (dotimes [_ removed] (b/read-varint buf))]
      {:item item :count n})))

(defn write-hashed-slot
  "A slot as the server expects in a click: present flag, item, count, and no
   component hashes. nil is the empty slot."
  [out v]
  (b/write-bool out (some? v))
  (when v
    (b/write-varint out (:item v))
    (b/write-varint out (:count v))
    (b/write-varint out 0)
    (b/write-varint out 0)))

(def component-ids
  "{component-type-name id}: the registry minecraft:data_component_type, generated from the
   vanilla reports."
  (edn/read-string (slurp (io/resource "clojurecraft/components.edn"))))

(def component-codecs
  "How the components the bot can read are written on the wire, by type name, as the game's
   DataComponents registers their stream codecs (read in the 26.1.2 jar with javap): a varint,
   or :unit, nothing at all. A worn tool carries damage, and the game answers every use-item with
   the whole inventory, so a slot after a worn tool must still be read."
  {:max_stack_size :varint
   :max_damage :varint
   :damage :varint
   :unbreakable :unit
   :repair_cost :varint})

(def component-by-id
  "id → [type-name codec] for every component in component-codecs."
  (into {} (for [[k codec] component-codecs] [(component-ids k) [k codec]])))

(defn read-component
  "One component's value from buf by its codec: a varint, or true for :unit."
  [codec buf]
  (case codec
    :varint (b/read-varint buf)
    :unit true))

(defn read-slot
  "{:item :count} (plus :components {type-name value} when the slot carries any) or nil for an
   empty slot. The components patch is a count added, a count removed, each added type id with
   its value, then each removed type id (the game's DataComponentPatch.STREAM_CODEC). A component
   whose codec is not in component-codecs cannot be stepped over: the slot is marked :components?
   and ends what can be read of the frame."
  [buf]
  (let [n (b/read-varint buf)]
    (when (pos? n)
      (let [item (b/read-varint buf)
            added (b/read-varint buf)
            removed (b/read-varint buf)
            comps (reduce (fn [m _]
                            (if-let [[k codec] (component-by-id (b/read-varint buf))]
                              (assoc m k (read-component codec buf))
                              (reduced nil)))
                          {}
                          (range added))]
        (cond (nil? comps) {:item item :count n :components? true}
              :else
              (do (dotimes [_ removed] (b/read-varint buf))
                  (cond-> {:item item :count n} (seq comps) (assoc :components comps))))))))

(def metadata-end 255)                ; the index that ends an entity's metadata (the game's EOF_MARKER)

(def metadata-readers
  "How the metadata values the bot steps over are written, by serializer id: the game's
   EntityDataSerializers in registration order, read in the 26.1.2 jar (probe run 38876275250,
   issue #258). A byte (0) is read unsigned, since the one the bot reads is a bitfield (index 0,
   the shared flags: on fire, sneaking ...). Direction (12) and pose (20) are varint enums. An item
   stack (7) is read as a slot (read-slot): a dropped item's stack is its index 8, and a drop is
   known by what it holds (issue #262: a blaze rod picked out from the cobblestone). An optional
   block state (15) is one varint, 0 for none: an enderman's carried block (index 16) comes just
   before its creepy flag (17), which the pearl hunt reads (issue #263). The rest
   (components, particles, block positions, variants) are not here: a skim stops at the first of
   them."
  {0 b/read-u8      ; BYTE
   1 b/read-varint  ; INT
   2 b/read-varlong ; LONG
   3 b/read-f32     ; FLOAT: a living entity's health is one (index 9)
   4 b/read-string  ; STRING
   7 read-slot      ; ITEM_STACK: a dropped item's (index 8)
   8 b/read-bool    ; BOOLEAN
   12 b/read-varint ; DIRECTION
   15 b/read-varint ; OPTIONAL_BLOCK_STATE: 0, or a block state id
   20 b/read-varint}) ; POSE

(defn read-metadata
  "An entity's metadata as {index value}: entries of [index u8][serializer varint][value] up to
   the index metadata-end. Only serializers in metadata-readers are read; at the first other one
   the skim stops and keeps what it has, as a frame's unread tail is ignored. A living entity's
   health (index 9) comes after its flags, air, pose and frozen ticks, all readable, so only a
   custom name (index 2, a component) hides it. An item stack whose components could not be
   stepped over (read-slot's :components?) is kept and ends the skim: where the next entry starts
   is unknown."
  [buf]
  (loop [m {}]
    (let [i (b/read-u8 buf)
          read (when (not= i metadata-end) (metadata-readers (b/read-varint buf)))
          v (when read (read buf))]
      (cond (nil? read) m
            (:components? v) (assoc m i v)
            :else (recur (assoc m i v))))))

(defn int-in? "Is x an integer in [lo, hi]?" [lo hi x] (and (int? x) (<= lo x hi)))

(declare slot?)

(defn metadata?
  "A skimmed metadata map: {index value}, each index 0-254, each value a number, a boolean, a
   string or a slot (an item stack, nil when empty: issue #262)."
  [x]
  (and (map? x)
       (every? (fn [[k v]]
                 (and (int-in? 0 254 k) (or (number? v) (boolean? v) (string? v) (slot? v))))
               x)))

(defn vec3? "Three numbers in a vector." [x] (and (vector? x) (= 3 (count x)) (every? number? x)))

(defn slot?
  "A decoded slot: nil when empty, else at least {:item id :count n}."
  [x]
  (or (nil? x) (and (map? x) (int? (:item x)) (int? (:count x)))))

(def wire-types
  "Every primitive wire type the specs use: how to read one from a buffer, how to write one to a
   stream, and what a value of it must be (with the wire's own range). :slot and :metadata are
   read-only; a click writes :hashed-slot. One table, so the codec and the packet spec cannot disagree."
  {:bool {:read b/read-bool :write b/write-bool :valid? boolean?}
   :i8 {:read b/read-i8 :write b/write-i8 :valid? (partial int-in? -128 127)}
   :u8 {:read b/read-u8 :write b/write-u8 :valid? (partial int-in? 0 255)}
   :i16 {:read b/read-i16 :write b/write-i16 :valid? (partial int-in? -32768 32767)}
   :u16 {:read b/read-u16 :write b/write-u16 :valid? (partial int-in? 0 65535)}
   :i32 {:read b/read-i32
         :write b/write-i32
         :valid? (partial int-in? Integer/MIN_VALUE Integer/MAX_VALUE)}
   :u32 {:read b/read-u32 :write b/write-u32 :valid? (partial int-in? 0 4294967295)}
   :i64 {:read b/read-i64 :write b/write-i64 :valid? int?}
   :varint {:read b/read-varint
            :write b/write-varint
            :valid? (partial int-in? Integer/MIN_VALUE Integer/MAX_VALUE)}
   :varlong {:read b/read-varlong :write b/write-varlong :valid? int?}
   :f32 {:read b/read-f32 :write b/write-f32 :valid? number?}
   :f64 {:read b/read-f64 :write b/write-f64 :valid? number?}
   :string {:read b/read-string :write b/write-string :valid? string?}
   :uuid {:read b/read-uuid :write b/write-uuid :valid? #(or (nil? %) (uuid? %))}
   :position {:read b/read-position
              :write b/write-position
              :valid? #(and (vector? %) (= 3 (count %)) (every? int? %))}
   :bytes {:read #(b/read-bytes % (b/read-varint %)) ; a varint length, then the bytes
           :write (fn [out ^bytes v] (b/write-varint out (alength v)) (b/write-bytes out v))
           :valid? bytes?}
   :rest {:read b/read-rest :write b/write-bytes :valid? bytes?}
   :lpvec3 {:read b/read-lpvec3 :write b/write-lpvec3 :valid? vec3?}
   :metadata {:read read-metadata :valid? metadata?}
   :slot {:read read-slot :valid? slot?}
   :hashed-slot {:read read-hashed-slot :write write-hashed-slot :valid? slot?}})

(defn type-fn
  "The :read or :write function of primitive wire type t; throws for a type the table lacks
   (or a direction it has none for, writing a :slot)."
  [t which]
  (or (get-in wire-types [t which])
      (throw (ex-info "no such wire type" {:type t :direction which}))))

(declare read-fields)

(defn read-field
  "One value of type t from buf. A vector stops early after a truncating slot,
   because what follows it cannot be located."
  [t buf]
  (cond (struct? t) (read-fields buf t)
        (vector? t)
        (let [n (b/read-varint buf)
              et (second t)]
          (loop [i 0
                 acc (transient [])]
            (if (= i n)
              (persistent! acc)
              (let [v (read-field et buf)
                    acc (conj! acc v)]
                (if (truncating? v) (persistent! acc) (recur (inc i) acc))))))
        :else ((type-fn t :read) buf)))

(defn read-fields
  "The struct's fields in order into a map; stops at a truncating value and marks
   the map :truncated."
  [buf fields]
  (loop [fields fields
         m {}]
    (if-let [[k t] (first fields)]
      (let [v (read-field t buf)
            m (assoc m k v)]
        (if (truncating? v) (assoc m :truncated true) (recur (rest fields) m)))
      m)))

(defn decode
  "Bytes of one frame (after decompression) → packet map. Unknown or unmodelled ids decode to
   {:packet/name :unknown ...} so the caller can count them."
  [state dir ^bytes frame]
  (let [buf (b/buffer frame)
        id (b/read-varint buf)
        pkt-name (get-in names [state dir id])]
    (if-let [fields (and pkt-name (specs [state dir pkt-name]))]
      (let [m (assoc (read-fields buf fields) :packet/name pkt-name)]
        (cond-> m
          (and (= :boss-event pkt-name) (= 2 (:action m)) (>= (.remaining buf) 4))
          (assoc :health (b/read-f32 buf))))
      {:packet/name :unknown :packet/id id :packet/known pkt-name :packet/len (alength frame)})))

;; ---------------------------------------------------------------- writing

(declare write-fields)

(defn write-field
  "One value of type t to out. :slot is read-only; only :hashed-slot is written."
  [t out v]
  (cond (struct? t) (write-fields out t v)
        (vector? t) (do (b/write-varint out (count v)) (doseq [x v] (write-field (second t) out x)))
        :else ((type-fn t :write) out v)))

(defn write-fields
  "Each field of the struct from m, in spec order; throws when m lacks one."
  [out fields m]
  (doseq [[k t] fields]
    (when-not (contains? m k) (throw (ex-info "missing field" {:field k :packet m})))
    (write-field t out (get m k))))

(defn encode
  "Packet map → bytes of one frame (id + fields), for the client→server direction."
  ^bytes [state pkt]
  (let [pkt-name (:packet/name pkt)
        id (get-in ids [state :c2s pkt-name])
        fields (specs [state :c2s pkt-name])]
    (when-not (and id fields)
      (throw (ex-info "no c2s spec for packet" {:state state :packet/name pkt-name})))
    (b/with-out (fn [out] (b/write-varint out id) (write-fields out fields pkt)))))
