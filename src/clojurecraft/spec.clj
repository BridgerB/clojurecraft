(ns clojurecraft.spec
  "Specs for the information model: world attributes, events, effects, intents, packets. Shape
   lives here (what an attribute is); what a function requires is stated by that function. Load
   this namespace and `instrument` in tests; nothing here runs in the hot loop."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.gym :as gym]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.path :as path]
            [clojurecraft.place :as place]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]
            [clojurecraft.wood :as wood]
            [clojurecraft.packet :as p]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]))

(s/def ::vec3 (s/coll-of double? :kind vector? :count 3))
(s/def ::block-pos (s/coll-of int? :kind vector? :count 3))

;; bot
(s/def :bot/phase #{:handshake :login :configuration :play})
(s/def :bot/name string?)
(s/def :bot/sequence int?)
(s/def :bot/effects (s/coll-of ::effect :kind vector?))
(s/def :bot/closed string?)
(s/def :bot/disconnected string?)

;; time
(s/def :time/now int?)
(s/def :time/tick int?)

;; player
(s/def :player/pos ::vec3)
(s/def :player/vel ::vec3)
(s/def :player/look (s/coll-of double? :kind vector? :count 2))
(s/def :player/on-ground? boolean?)
(s/def :player/horizontal-collision? boolean?)
(s/def :player/jump-ticks int?)
(s/def :player/loaded? boolean?)
(s/def :player/entity-id int?)
(s/def :player/inventory (s/map-of int? (s/keys :req-un [::item ::count])))
(s/def ::item int?)
(s/def ::count int?)
(s/def :control/forward? boolean?)
(s/def :control/jump? boolean?)
(s/def :control/yaw double?)
(s/def :control/look :player/look)
(s/def :player/controls (s/keys :opt [:control/forward? :control/jump? :control/yaw :control/look]))

;; window 0 (the player's own screen) and any open container
(s/def ::slot-item (s/keys :req-un [::item ::count]))
(s/def :window/state-id int?)
(s/def :window/grid (s/map-of #{0 1 2 3 4} ::slot-item))
(s/def :window/cursor ::slot-item)
(s/def :window/id int?)
(s/def :window/menu-type int?)
(s/def :window/slots (s/map-of int? ::slot-item))
(s/def :window/open (s/keys :req [:window/id :window/menu-type] :opt [:window/state-id :window/slots]))
(s/def :player/held-slot (s/int-in 0 9))

;; world
(s/def :world/chunks (s/map-of (s/coll-of int? :count 2) map?))
(s/def :world/blocks (s/map-of ::block-pos int?))
(s/def :world/facts #(instance? datascript.db.DB %))
(s/def :block/state int?)
(s/def :block/seen-at int?)
(s/def :world/entities (s/map-of int? (s/keys :req [:entity/type :entity/pos])))
(s/def :entity/type int?)
(s/def :entity/pos ::vec3)

;; packets, effects, events
(s/def :packet/name keyword?)
(defn int-in? "Is x an integer in [lo, hi]?" [lo hi x] (and (int? x) (<= lo x hi)))

(def slot? "A decoded slot: nil when empty, else at least {:item id :count n}." #(or (nil? %) (and (map? %) (int? (:item %)) (int? (:count %)))))

(def wire-types
  "What a value of each wire type in packet/specs must be, with the wire's own range."
  {:bool boolean?
   :i8 (partial int-in? -128 127) :u8 (partial int-in? 0 255)
   :i16 (partial int-in? -32768 32767) :u16 (partial int-in? 0 65535)
   :i32 (partial int-in? Integer/MIN_VALUE Integer/MAX_VALUE) :u32 (partial int-in? 0 4294967295)
   :i64 int? :varint (partial int-in? Integer/MIN_VALUE Integer/MAX_VALUE) :varlong int?
   :f32 number? :f64 number?
   :string string? :uuid #(or (nil? %) (uuid? %))
   :position #(and (vector? %) (= 3 (count %)) (every? int? %))
   :bytes bytes? :rest bytes?
   :slot slot? :hashed-slot slot?})

(declare fields-ok?)

(defn value-ok?
  "Does v fit wire type t: a primitive, [:vec T], or a struct (a vector of [key type] pairs)?"
  [t v]
  (cond
    (and (vector? t) (vector? (first t))) (and (map? v) (fields-ok? t v))
    (vector? t) (and (sequential? v) (every? #(value-ok? (second t) %) v))
    (empty? (str t)) false
    :else ((wire-types t) v)))

(defn fields-ok?
  "Does map m carry every field of a spec, each fitting its type? Extra keys are fine."
  [fields m]
  (every? (fn [[k t]] (and (contains? m k) (value-ok? t (get m k)))) fields))

(def shapes
  "packet name → every field vector the table gives it (a name can recur across states and
   directions, e.g. keep-alive)."
  (reduce (fn [m [[_ _ n] fields]] (update m n (fnil conj []) fields)) {} p/specs))

(defn packet-ok?
  "Is pkt a packet the table can account for? A name the table models must match one of its
   shapes; a name it does not model (:closed, :unknown, :decode-error) only needs the name."
  [pkt]
  (if-let [fs (shapes (:packet/name pkt))]
    (boolean (some #(fields-ok? % pkt) fs))
    true))

(s/def ::packet (s/and (s/keys :req [:packet/name]) packet-ok?))
(s/def :effect/kind #{:send :log})
(s/def :effect/packet ::packet)
(s/def :effect/message string?)
(s/def ::effect (s/keys :req [:effect/kind] :opt [:effect/packet :effect/message]))
(s/def :event/kind #{:start :packet :tick :go :closed})
(s/def :event/now int?)
(s/def :event/rand (s/double-in :min 0.0 :max 1.0 :NaN? false))
(s/def :event/packet ::packet)
(s/def :event/reason string?)
(s/def :go/goals (s/coll-of keyword?))
(s/def :start/host string?)
(s/def :start/port int?)
(s/def :start/name string?)
(s/def ::event (s/keys :req [:event/kind] :opt [:event/now :event/rand :event/packet :event/reason :go/goals :go/at
                                                :start/host :start/port :start/name]))

;; plan and intents
(s/def :intent/kind keyword?)
(s/def :intent/status #{:active :done :failed})
(s/def :intent/target ::block-pos)
(s/def ::intent (s/keys :req [:intent/kind] :opt [:intent/status :intent/target :intent/recipe]))
(s/def :plan/intent ::intent)
(s/def :plan/status #{:landing :active :done :failed})
(s/def :plan/go-at ::vec3)
(s/def :go/at ::vec3)
(s/def :plan/blacklist (s/coll-of ::block-pos :kind set?))
(s/def :plan/trunk-failures (s/map-of (s/tuple int? int?) pos-int?))
(s/def :plan/goals (s/coll-of keyword? :kind set?))
(s/def :intent/recipe keyword?)
(s/def :intent/window #{:inventory :table})
(s/def :intent/item keyword?)
(s/def ::need-key (s/or :named keyword? :set (s/coll-of keyword? :kind set?)))
(s/def ::amount (s/or :count pos-int? :near #{:near}))
(s/def :goal/needs (s/map-of ::need-key ::amount))
(s/def :goal/provides (s/map-of ::need-key ::amount :min-count 1))
(s/def :goal/done? keyword?)
(s/def :goal/act keyword?)
(s/def :goal/target? boolean?)
(s/def :goal/until string?)                ; the --until name that selects a target row
(s/def :goal/superseded-by keyword?)       ; the row that replaced this one; this one is kept, not chosen
(s/def :goal/id keyword?)
(s/def :goal/priority int?)
(s/def ::goal (s/keys :req [:goal/id :goal/priority :goal/provides :goal/done?]
                      :opt [:goal/needs :goal/act :goal/target? :goal/until :goal/doc :goal/superseded-by]))

(s/def ::world
  (s/keys :req [:bot/phase :bot/effects :time/now :time/tick]
          :opt [:bot/name :bot/sequence :bot/closed :bot/disconnected
                :player/pos :player/vel :player/look :player/on-ground? :player/horizontal-collision?
                :player/jump-ticks :player/loaded? :player/entity-id :player/inventory :player/controls
                :world/chunks :world/blocks :world/facts :world/entities
                :window/state-id :window/grid :window/cursor :window/open :player/held-slot
                :plan/intent :plan/status :plan/blacklist :plan/trunk-failures]))

(s/fdef game/step :args (s/cat :world ::world :event ::event) :ret ::world)
(s/fdef plan/step :args (s/cat :world ::world :event ::event) :ret ::world)
(s/fdef intent/run :args (s/cat :world ::world :intent ::intent :event ::event) :ret ::world)
(s/fdef plan/choose :args (s/cat :world ::world :table (s/coll-of ::goal)) :ret (s/nilable ::goal))

(def goals-valid?
  "Every row of the goal table conforms, ids are unique, and every :goal/superseded-by names a
   row that exists."
  (let [ids (map :goal/id plan/goals)]
    (and (every? #(s/valid? ::goal %) plan/goals)
         (apply distinct? ids)
         (every? (set ids) (keep :goal/superseded-by plan/goals)))))

;; recipes and clicks
(s/def ::item-set (s/coll-of keyword? :kind set? :min-count 1))
(s/def :recipe/id keyword?)
(s/def :recipe/result keyword?)
(s/def :recipe/count pos-int?)
(s/def :recipe/kind #{:shaped :shapeless})
(s/def :recipe/pattern (s/coll-of string? :kind vector? :min-count 1 :max-count 3))
(s/def :recipe/key (s/map-of string? ::item-set))
(s/def :recipe/width (s/int-in 1 4))
(s/def :recipe/height (s/int-in 1 4))
(s/def :recipe/ingredients (s/coll-of ::item-set :kind vector? :min-count 1 :max-count 9))
(s/def ::recipe (s/and (s/keys :req [:recipe/id :recipe/result :recipe/count :recipe/kind]
                               :opt [:recipe/pattern :recipe/key :recipe/width :recipe/height :recipe/ingredients])
                       #(if (= :shaped (:recipe/kind %))
                          (every? % [:recipe/pattern :recipe/key :recipe/width :recipe/height])
                          (contains? % :recipe/ingredients))))
(s/def :click/slot (s/int-in 0 46))
(s/def :click/button (s/int-in 0 9))
(s/def :click/mode #{0 1 2})
(s/def ::click (s/keys :req [:click/slot :click/button :click/mode]))

(def recipes-valid? (every? #(s/valid? ::recipe %) recipe/recipes))

(s/fdef recipe/clicks
  :args (s/cat :inventory :player/inventory :recipe ::recipe :size #{2 3} :slot-of (s/? fn?))
  :ret (s/nilable (s/coll-of ::click :kind vector?)))

;; ---------------------------------------------------------------- the rest of the model
;; Every attribute clojurecraft.model lists has a spec; model_test holds that. s/keys checks any
;; registered key a map carries, so these are checked wherever a world or intent is.

(s/def :bot/uuid uuid?)
(s/def :conn/host string?)
(s/def :conn/port int?)
(s/def :player/teleport-id int?)
(s/def :player/synced-at int?)
(s/def :player/health number?)
(s/def :entity/seen-at int?)
(s/def :sight/pos ::block-pos)
(s/def :sight/state int?)
(s/def :sight/at int?)
(s/def :stats/keep-alives nat-int?)
(s/def :stats/teleports nat-int?)
(s/def :stats/chunks nat-int?)
(s/def :stats/unknown (s/map-of vector? nat-int?))
(s/def :stats/last-ack int?)
(s/def :stats/pickups (s/coll-of map? :kind vector?))
(s/def :net/sent-pos ::vec3)
(s/def :net/sent-look (s/coll-of number? :kind vector? :count 2))
(s/def :net/sent-tick int?)
(s/def :plan/since int?)
(s/def :plan/attempts nat-int?)
(s/def :plan/last (s/keys :req [:intent/kind]))
(s/def :plan/reason some?)
(s/def :plan/waiting some?)
(s/def :plan/waiting-since int?)
(s/def :plan/wait some?)
(s/def :intent/reason some?)
(s/def :intent/for keyword?)
(s/def :intent/trunk ::block-pos)
(s/def :intent/stage keyword?)
(s/def :intent/since int?)
(s/def :intent/started int?)
(s/def :intent/still nat-int?)
(s/def :intent/face int?)
(s/def :intent/next-swing number?)
(s/def :intent/finish-at number?)
(s/def :intent/best-dist number?)
(s/def :intent/best-tick int?)
(s/def :intent/detour-until int?)
(s/def :intent/detour-yaw number?)
(s/def :intent/detours nat-int?)
(s/def :intent/waypoints (s/coll-of ::block-pos :kind vector?))
(s/def :intent/route #{:found :partial :none})
(s/def :intent/at nat-int?)
(s/def :intent/replans nat-int?)
(s/def :intent/planned-chunks nat-int?)
(s/def :path/waypoints (s/coll-of ::block-pos :kind vector?))
(s/def :path/cost double?)
(s/def :path/status #{:found :partial :none})
(s/def :path/max-nodes pos-int?)
(s/def ::route (s/keys :req [:path/waypoints :path/cost :path/status]))
(s/def :goal/kind #{:near :block :xz :away})
(s/def :goal/pos ::block-pos)
(s/def :goal/range number?)
(s/def ::path-goal (s/keys :req [:goal/kind :goal/pos] :opt [:goal/range]))
(s/def :intent/logs-before nat-int?)
(s/def :intent/blocked-by ::block-pos)
(s/def :intent/clears nat-int?)
(s/def :intent/resume (s/keys :req [:intent/kind]))
(s/def :intent/clicks (s/coll-of map? :kind sequential?))
(s/def :intent/result int?)
(s/def :intent/makes pos-int?)
(s/def :intent/before nat-int?)
(s/def :intent/awaiting int?)
(s/def :intent/awaiting-window int?)
(s/def :intent/sent-at int?)
(s/def :intent/against ::block-pos)
(s/def :intent/sequence int?)
(s/def :chunk/column map?)
(s/def :plan/intents pos-int?)
(s/def :intent/id pos-int?)
(s/def :intention/id pos-int?)
(s/def :intention/event #{:started :done :failed :abandoned})
(s/def :intention/kind keyword?)
(s/def :intention/at int?)
(s/def :intention/target ::block-pos)
(s/def :intention/recipe keyword?)
(s/def :intention/reason some?)
(s/def :answer/kind #{:ack :pickup})
(s/def :answer/at int?)
(s/def :answer/sequence int?)
(s/def :answer/entity int?)
(s/def :answer/count int?)

;; problem statements (resources/clojurecraft/problems.edn), written before each stage is built
(s/def :problem/stage keyword?)
(s/def :problem/issue pos-int?)
(s/def :problem/goals (s/coll-of keyword? :kind vector?))
(s/def ::text (s/and string? #(not (str/blank? %))))
(s/def :problem/statement ::text)
(s/def :problem/needs (s/coll-of ::text :kind vector? :min-count 1))
(s/def :problem/risks (s/coll-of ::text :kind vector? :min-count 1))
(s/def :problem/done ::text)
(s/def :problem/last-failure ::text)
(s/def :problem/sources (s/coll-of ::text :kind vector? :min-count 1))
(s/def ::problem (s/keys :req [:problem/stage :problem/statement :problem/needs :problem/risks :problem/done
                               :problem/last-failure :problem/sources]
                         :opt [:problem/issue :problem/goals]))
(s/def :intent/goal keyword?)

;; the server model (sim): a reducer too, so it is specced and instrumented like the bot's
(s/def :sim/kind #{:packet :tick})
(s/def :sim/packet ::packet)
(s/def :sim/now int?)
(s/def ::sim-event (s/keys :req [:sim/kind] :opt [:sim/packet :sim/now]))
(s/def :sim/phase #{:handshake :login :configuration :play})
(s/def :sim/out (s/coll-of ::packet :kind vector?))   ; what the model says is held to the packet table
(s/def :sim/inv (s/map-of int? slot?))
(s/def :sim/state-id int?)
(s/def :sim/held int?)
(s/def :sim/placed map?)
(s/def :sim/next-window int?)
(s/def :sim/clicks nat-int?)
(s/def :sim/drop-clicks set?)
(s/def :sim/violations vector?)
(s/def :sim/keep-alive-every pos-int?)
(s/def :sim/lag-ticks nat-int?)
(s/def :sim/column bytes?)
(s/def :sim/chunk map?)
(s/def :sim/spawn ::vec3)
(s/def :sim/broken (s/coll-of ::block-pos :kind set?))
(s/def :sim/items (s/map-of int? map?))
(s/def :sim/next-eid int?)
(s/def :sim/next-keep-alive int?)
(s/def :sim/keep-alives-pending set?)
(s/def ::sim (s/keys :req [:sim/phase :sim/now :sim/out]))
(s/fdef sim/step :args (s/cat :sim ::sim :event ::sim-event) :ret ::sim)

;; ---------------------------------------------------------------- selection
;; Maybe Not: schema (what an attribute is, the specs above) and selection (which attributes a
;; given function needs) are different things. A selection only says which keys must be
;; present; their shapes come from the schema. Each function below states its selection as an
;; fdef on its world argument; selected lists them for instrumentation.

(defn requires
  "A spec for a map that holds every key in ks (selection only: shapes are the schema's)."
  [& ks]
  (s/and map? (fn has-every-key [m] (every? #(contains? m %) ks))))

(s/fdef game/eye :args (s/cat :world (requires :player/pos)))
(s/fdef physics/step :args (s/cat :solid? fn? :world (requires :player/pos) :controls map?))
(s/fdef inventory/item-count :args (s/cat :world (requires :player/inventory) :id int?))
(s/fdef inventory/logs-held :args (s/cat :world (requires :player/inventory)))
(s/fdef terrain/block-at :args (s/cat :world (requires :world/chunks) :pos (s/coll-of number? :count 3)))
(s/fdef terrain/solid-fn :args (s/cat :world (requires :world/chunks)))
(s/fdef memory/positions-now :args (s/cat :world (requires :world/facts) :states coll?))
(s/fdef memory/nearest-log :args (s/cat :world (requires :world/facts) :eye ::vec3 :radius number? :blacklist any?
                                        :bottom? (s/? ifn?)))
(s/fdef make/near :args (s/cat :world (requires :player/pos :world/facts) :block keyword?))
(s/fdef make/decide :args (s/cat :world (requires :player/inventory :player/pos :world/facts) :goal map? :table vector?))
(s/fdef wood/gather-next :args (s/cat :world (requires :player/pos :world/facts)))
(s/fdef place/spot :args (s/cat :world (requires :player/pos :world/chunks)))
(s/fdef path/plan :args (s/cat :world (requires :world/chunks) :from ::block-pos :goal ::path-goal :opts (s/? (s/keys :opt [:path/max-nodes])))
  :ret ::route)
(s/fdef path/classify :args (s/cat :world (requires :world/chunks) :pos ::block-pos))
(s/fdef window/view :args (s/cat :world (requires :window/grid) :which #{:inventory :table}))

(def selected
  "Every function whose selection is stated above, for instrument."
  [`game/eye `physics/step `inventory/item-count `inventory/logs-held `terrain/block-at
   `terrain/solid-fn `memory/positions-now `memory/nearest-log `make/near `make/decide
   `wood/gather-next `place/spot `window/view `path/plan `path/classify])
(s/def :stats/deaths pos-int?)

;; ---------------------------------------------------------------- the gym
(s/def :gym/goal keyword?)
(s/def :gym/until string?)
(s/def :gym/prereqs (s/coll-of string? :kind vector?))
(s/def :gym/timeout-ms pos-int?)
(s/def :truth/cmd string?)
(s/def :truth/re string?)
(s/def :truth/reply string?)
(s/def :truth/ok? boolean?)
(s/def :gym/truth (s/coll-of (s/keys :req [:truth/cmd :truth/re]) :kind vector? :min-count 1))
(s/def ::gym (s/keys :req [:gym/goal :gym/until :gym/prereqs :gym/timeout-ms :gym/truth]))
(s/def :gym/run pos-int?)
(s/def :gym/landing (s/coll-of int? :kind vector? :count 3))
(s/def :gym/landed? boolean?)
(s/def :gym/outcome #{:pass :fail :timeout :death :disconnect :harness})
(s/def :gym/ms int?)
(s/def :gym/reason (s/nilable keyword?))
(s/def :gym/truths (s/coll-of (s/keys :req [:truth/cmd :truth/reply :truth/ok?]) :kind vector?))
(s/def :gym/commit (s/nilable string?))
(s/def :gym/result (s/nilable map?))
(s/def ::gym-result (s/keys :req [:gym/goal :gym/run :gym/landed? :gym/outcome :gym/truths]
                            :opt [:gym/landing :gym/ms :gym/reason :gym/commit :gym/result]))

;; ---------------------------------------------------------------- the fleet
(s/def :fleet/label string?)
(s/def :fleet/workers (s/int-in 1 257))              ; a matrix holds at most 256 jobs
(s/def :fleet/max-parallel (s/int-in 1 21))
(s/def :fleet/max-worker-s pos-int?)
(s/def :fleet/issue pos-int?)
(s/def :fleet/salt string?)                         ; a repeated plan's run, so its sims sample fresh worlds
(s/def :exp/name string?)
(s/def :exp/kind #{:gym :sim})
(s/def :exp/goal string?)
(s/def :exp/set string?)
(s/def :exp/runs pos-int?)
(s/def :exp/bots pos-int?)
(s/def :exp/est-s pos-int?)
(s/def :arm/name string?)
(s/def :arm/ref string?)
(s/def :exp/arms (s/coll-of (s/keys :req [:arm/name] :opt [:arm/ref]) :kind vector? :min-count 1))
(s/def :exp/property string?)
(s/def :exp/shards pos-int?)
(s/def :exp/worlds pos-int?)
(s/def ::experiment
  (s/and (s/keys :req [:exp/name :exp/kind :exp/est-s]
                 :opt [:exp/goal :exp/set :exp/runs :exp/bots :exp/arms :exp/property :exp/shards :exp/worlds])
         #(case (:exp/kind %)
            :gym (and (:exp/goal %) (:exp/runs %))
            :sim (and (:exp/property %) (:exp/shards %) (:exp/worlds %)))))
(s/def :fleet/experiments (s/coll-of ::experiment :kind vector? :min-count 1))
(s/def ::fleet-plan (s/keys :req [:fleet/label :fleet/workers :fleet/experiments]
                            :opt [:fleet/max-parallel :fleet/max-worker-s :fleet/issue :fleet/salt]))
