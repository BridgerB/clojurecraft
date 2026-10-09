(ns clojurecraft.packet
  "Packet specs as data, and one interpreter that reads and writes them.

   A packet is a flat map with a :name. A spec is a vector of [key type] pairs; a type is a
   keyword for a primitive, [:vec T] for a varint-counted sequence, or a vector of pairs for a
   nested struct. Ids come from resources/clojurecraft/packets.edn (generated from the vanilla
   reports). Decoding reads only the listed fields and ignores the tail of the frame: that is
   how big packets (chunks) skip the parts we do not model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.bytes :as b]))

(def ids
  "{state {:s2c {name id} :c2s {name id}}}"
  (edn/read-string (slurp (io/resource "clojurecraft/packets.edn"))))

(def names
  "{state {dir {id name}}}"
  (into {} (for [[state dirs] ids]
             [state (into {} (for [[dir m] dirs] [dir (into {} (map (fn [[n i]] [i n]) m))]))])))

(def specs
  {;; handshake / login
   [:handshake :c2s :intention] [[:protocol-version :varint] [:host :string] [:port :u16] [:next-state :varint]]
   [:login :c2s :hello] [[:username :string] [:uuid :uuid]]
   [:login :s2c :login-compression] [[:threshold :varint]]
   [:login :s2c :login-finished] [[:uuid :uuid] [:username :string]]
   [:login :s2c :login-disconnect] [[:reason :string]]
   [:login :c2s :login-acknowledged] []
   ;; configuration
   [:configuration :s2c :select-known-packs] []
   [:configuration :c2s :select-known-packs] [[:packs [:vec [[:namespace :string] [:id :string] [:version :string]]]]]
   [:configuration :s2c :keep-alive] [[:id :i64]]
   [:configuration :c2s :keep-alive] [[:id :i64]]
   [:configuration :s2c :ping] [[:id :i32]]
   [:configuration :c2s :pong] [[:id :i32]]
   [:configuration :s2c :finish-configuration] []
   [:configuration :c2s :finish-configuration] []
   [:configuration :s2c :disconnect] [[:reason :rest]]
   ;; play, server → client
   [:play :s2c :login] [[:entity-id :i32]]
   [:play :s2c :keep-alive] [[:id :i64]]
   [:play :s2c :ping] [[:id :i32]]
   [:play :s2c :player-position] [[:teleport-id :varint] [:x :f64] [:y :f64] [:z :f64]
                                  [:dx :f64] [:dy :f64] [:dz :f64] [:yaw :f32] [:pitch :f32] [:flags :u32]]
   [:play :s2c :chunk-batch-finished] [[:batch-size :varint]]
   [:play :s2c :level-chunk-with-light] [[:x :i32] [:z :i32]
                                         [:heightmaps [:vec [[:type :varint] [:data [:vec :i64]]]]]
                                         [:data :bytes]]
   [:play :s2c :forget-level-chunk] [[:pos :i64]]
   [:play :s2c :block-update] [[:pos :position] [:state :varint]]
   [:play :s2c :section-blocks-update] [[:section :i64] [:blocks [:vec :varlong]]]
   [:play :s2c :set-health] [[:health :f32] [:food :varint] [:saturation :f32]]
   [:play :s2c :container-set-content] [[:window-id :varint] [:state-id :varint] [:items [:vec :slot]] [:carried :slot]]
   [:play :s2c :container-set-slot] [[:window-id :varint] [:state-id :varint] [:slot :i16] [:item :slot]]
   [:play :s2c :set-player-inventory] [[:slot :varint] [:item :slot]]
   [:play :s2c :add-entity] [[:entity-id :varint] [:uuid :uuid] [:type :varint] [:x :f64] [:y :f64] [:z :f64]]
   [:play :s2c :remove-entities] [[:ids [:vec :varint]]]
   [:play :s2c :move-entity-pos] [[:entity-id :varint] [:dx :i16] [:dy :i16] [:dz :i16] [:on-ground :bool]]
   [:play :s2c :move-entity-pos-rot] [[:entity-id :varint] [:dx :i16] [:dy :i16] [:dz :i16]]
   [:play :s2c :entity-position-sync] [[:entity-id :varint] [:x :f64] [:y :f64] [:z :f64]]
   [:play :s2c :take-item-entity] [[:collected :varint] [:collector :varint] [:count :varint]]
   [:play :s2c :block-changed-ack] [[:sequence :varint]]
   [:play :s2c :disconnect] [[:reason :rest]]
   [:play :s2c :start-configuration] []
   ;; play, client → server
   [:play :c2s :keep-alive] [[:id :i64]]
   [:play :c2s :pong] [[:id :i32]]
   [:play :c2s :accept-teleportation] [[:teleport-id :varint]]
   [:play :c2s :chunk-batch-received] [[:chunks-per-tick :f32]]
   [:play :c2s :configuration-acknowledged] []
   [:play :c2s :client-information] [[:locale :string] [:view-distance :i8] [:chat-mode :varint] [:chat-colors :bool]
                                     [:skin-parts :u8] [:main-hand :varint] [:text-filtering :bool]
                                     [:server-listing :bool] [:particle-status :varint]]
   [:play :c2s :player-loaded] []
   [:play :c2s :move-player-pos] [[:x :f64] [:y :f64] [:z :f64] [:flags :u8]]
   [:play :c2s :move-player-pos-rot] [[:x :f64] [:y :f64] [:z :f64] [:yaw :f32] [:pitch :f32] [:flags :u8]]
   [:play :c2s :move-player-rot] [[:yaw :f32] [:pitch :f32] [:flags :u8]]
   [:play :c2s :move-player-status-only] [[:flags :u8]]
   [:play :c2s :set-carried-item] [[:slot :i16]]
   [:play :c2s :player-action] [[:status :varint] [:pos :position] [:face :i8] [:sequence :varint]]
   [:play :c2s :swing] [[:hand :varint]]})

(def transitions
  "Protocol state after the client sends a packet: {[state name] next-state}."
  {[:handshake :intention] :login
   [:login :login-acknowledged] :configuration
   [:configuration :finish-configuration] :play
   [:play :configuration-acknowledged] :configuration})

(defn next-state [state pkt-name]
  (get transitions [state pkt-name] state))

;; ---------------------------------------------------------------- reading

(defn- struct? [t] (and (vector? t) (vector? (first t))))

(defn- truncating?
  "A slot with components ends what we can parse of a frame."
  [v]
  (boolean (or (and (map? v) (:components? v))
               (and (vector? v) (some truncating? v)))))

(defn- read-slot [buf]
  (let [n (b/read-varint buf)]
    (when (pos? n)
      (let [item (b/read-varint buf) added (b/read-varint buf) removed (b/read-varint buf)]
        (cond-> {:item item :count n}
          (pos? (+ added removed)) (assoc :components? true))))))

(declare read-fields)

(defn read-field [t buf]
  (cond
    (struct? t) (read-fields buf t)
    (vector? t) (let [n (b/read-varint buf) et (second t)]
                  (loop [i 0 acc (transient [])]
                    (if (= i n)
                      (persistent! acc)
                      (let [v (read-field et buf)
                            acc (conj! acc v)]
                        (if (truncating? v) (persistent! acc) (recur (inc i) acc))))))
    :else (case t
            :bool (b/read-bool buf) :i8 (b/read-i8 buf) :u8 (b/read-u8 buf)
            :i16 (b/read-i16 buf) :u16 (b/read-u16 buf) :i32 (b/read-i32 buf) :u32 (b/read-u32 buf)
            :i64 (b/read-i64 buf) :f32 (b/read-f32 buf) :f64 (b/read-f64 buf)
            :varint (b/read-varint buf) :varlong (b/read-varlong buf)
            :string (b/read-string buf) :uuid (b/read-uuid buf) :position (b/read-position buf)
            :bytes (b/read-bytes buf (b/read-varint buf)) :rest (b/read-rest buf)
            :slot (read-slot buf))))

(defn read-fields [buf fields]
  (loop [fields fields m {}]
    (if-let [[k t] (first fields)]
      (let [v (read-field t buf) m (assoc m k v)]
        (if (truncating? v) (assoc m :truncated true) (recur (rest fields) m)))
      m)))

(defn decode
  "Bytes of one frame (after decompression) → packet map. Unknown or unmodelled ids decode to
   {:name :unknown ...} so the caller can count them."
  [state dir ^bytes frame]
  (let [buf (b/buffer frame)
        id (b/read-varint buf)
        pkt-name (get-in names [state dir id])]
    (if-let [fields (and pkt-name (specs [state dir pkt-name]))]
      (assoc (read-fields buf fields) :name pkt-name)
      {:name :unknown :id id :packet pkt-name :len (alength frame)})))

;; ---------------------------------------------------------------- writing

(declare write-fields)

(defn write-field [t out v]
  (cond
    (struct? t) (write-fields out t v)
    (vector? t) (do (b/write-varint out (count v))
                    (doseq [x v] (write-field (second t) out x)))
    :else (case t
            :bool (b/write-bool out v) :i8 (b/write-i8 out v) :u8 (b/write-u8 out v)
            :i16 (b/write-i16 out v) :u16 (b/write-u16 out v) :i32 (b/write-i32 out v) :u32 (b/write-u32 out v)
            :i64 (b/write-i64 out v) :f32 (b/write-f32 out v) :f64 (b/write-f64 out v)
            :varint (b/write-varint out v) :varlong (b/write-varlong out v)
            :string (b/write-string out v) :uuid (b/write-uuid out v) :position (b/write-position out v)
            :bytes (do (b/write-varint out (alength ^bytes v)) (b/write-bytes out v))
            :rest (b/write-bytes out v))))

(defn write-fields [out fields m]
  (doseq [[k t] fields]
    (when-not (contains? m k) (throw (ex-info "missing field" {:field k :packet m})))
    (write-field t out (get m k))))

(defn encode
  "Packet map → bytes of one frame (id + fields), for the client→server direction."
  ^bytes [state pkt]
  (let [pkt-name (:name pkt)
        id (get-in ids [state :c2s pkt-name])
        fields (specs [state :c2s pkt-name])]
    (when-not (and id fields)
      (throw (ex-info "no c2s spec for packet" {:state state :name pkt-name})))
    (b/with-out (fn [out]
                  (b/write-varint out id)
                  (write-fields out fields pkt)))))
