(ns clojurecraft.spec
  "Specs for the information model: world attributes, events, effects, intents, packets. Shape
   lives here (what an attribute is); what a function requires is stated by that function. Load
   this namespace and `instrument` in tests; nothing here runs in the hot loop."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.armour]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.build :as build]
            [clojurecraft.claims :as claims]
            [clojurecraft.combat]
            [clojurecraft.dragon :as dragon]
            [clojurecraft.drops :as drops]
            [clojurecraft.eat :as eat]
            [clojurecraft.end-portal :as end-portal]
            [clojurecraft.entity :as entity]
            [clojurecraft.eyes]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.hunt]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as p]
            [clojurecraft.path :as path]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.stronghold :as stronghold]
            [clojurecraft.sword]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

(s/def ::vec3 (s/coll-of double? :kind vector? :count 3))
(s/def ::block-pos (s/coll-of int? :kind vector? :count 3))

;; ---------------------------------------------------------------- build plans (issue #8)

(s/def :build/op #{:place :dig :pour :scoop :use-on :stand-in :verify})
(s/def :build/pos ::block-pos)
(s/def :build/stand ::block-pos)
(s/def :build/into ::block-pos)
(s/def :build/item keyword?)
(s/def :build/aim (s/coll-of number? :kind vector? :count 3))
(s/def :build/face keyword?)
(s/def :build/step keyword?)
(s/def :build/cell (s/tuple int? int?))
(s/def :build/done (s/and vector? #(keyword? (first %)))) ; a predicate as data, read by build/holds?
(s/def ::op
  (s/keys :req [:build/op :build/stand :build/done]
          :opt [:build/pos :build/into :build/item :build/aim :build/face :build/step :build/cell]))
(s/def ::plan (s/coll-of ::op :kind vector?))

(s/fdef fortress/step-plan
  :args
  (s/cat :feet ::block-pos
         :move :intent/move)
  :ret ::plan)
(s/fdef build/portal-plan
  :args
  (s/cat :origin ::block-pos
         :facing build/facings
         :h (s/? nat-int?))
  :ret ::plan)
(s/fdef build/cast-one-plan
  :args
  (s/cat :origin ::block-pos
         :facing build/facings
         :h (s/? nat-int?))
  :ret ::plan)
(s/fdef stronghold/triangulate
  :args
  (s/cat :a ::throw
         :b ::throw)
  :ret (s/nilable (s/tuple double? double?)))

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
(s/def :player/fall-distance (s/double-in :min 0.0 :NaN? false :infinite? false))
(s/def :player/in-water? boolean?)
(s/def :player/air (s/int-in 0 301))
(s/def :plan/wet-ticks nat-int?)
(s/def :plan/afloat-ticks nat-int?)
(s/def :plan/dry-ticks nat-int?)
(s/def :plan/trap #{:water :lava})
(s/def :plan/trap-at (s/tuple int? int?))
(s/def :trap/pos (s/tuple int? int?))
(s/def :trap/kind #{:water :lava})
(s/def :trap/at int?)
(s/def ::trap (s/keys :req [:trap/pos :trap/kind :trap/at]))
(s/def :world/traps (s/coll-of ::trap :kind vector?))
(s/def :intent/bank ::block-pos)
(s/def :intent/next-search int?)
(s/def :intent/shore ::block-pos)
(s/def :intent/dig-when-down ::block-pos)
(s/def :intent/water ::block-pos)
(s/def :intent/wet? boolean?)
(s/def :intent/from-dist number?)
(s/def :intent/exposes-ok? boolean?)
(s/def :intent/no-lay boolean?)
(s/def :intent/dug nat-int?)
(s/def :intent/drop-ok? boolean?)
(s/def :intent/passage-ok? boolean?)
(s/def :intent/retreated? boolean?)
(s/def :intent/niche ::block-pos)
(s/def :player/dead? boolean?)
(s/def :player/in-lava? boolean?)
(s/def :player/eye-in-water? boolean?)
(s/def :control/sneak? boolean?)
(s/def :player/jump-ticks int?)
(s/def :player/loaded? boolean?)
(s/def :player/dimension string?)
(s/def :player/entity-id int?)
(s/def :player/inventory (s/map-of int? (s/keys :req-un [::item ::count])))
(s/def ::item int?)
(s/def ::count int?)
(s/def :control/forward? boolean?)
(s/def :control/jump? boolean?)
(s/def :control/yaw double?)
(s/def :control/look :player/look)
(s/def :player/controls
  (s/keys :opt [:control/forward? :control/jump? :control/sneak? :control/yaw :control/look]))

;; window 0 (the player's own screen) and any open container
(s/def ::slot-item (s/keys :req-un [::item ::count]))
(s/def :window/state-id int?)
(s/def :window/grid (s/map-of #{0 1 2 3 4} ::slot-item))
(s/def :window/cursor ::slot-item)
(s/def :window/id int?)
(s/def :window/menu-type int?)
(s/def :window/slots (s/map-of int? ::slot-item))
(s/def :window/data (s/map-of int? int?))
(s/def :window/open
  (s/keys :req [:window/id :window/menu-type] :opt [:window/state-id :window/slots :window/data]))
(s/def :player/held-slot (s/int-in 0 9))

;; world
(s/def :world/chunks (s/map-of (s/coll-of int? :count 2) map?))
(s/def :world/blocks (s/map-of ::block-pos int?))
(s/def :world/unconfirmed (s/map-of ::block-pos int?))
(s/def :world/facts #(instance? datascript.db.DB %))
(s/def :block/state int?)
(s/def :block/seen-at int?)
(s/def :world/entities (s/map-of int? (s/keys :req [:entity/type :entity/pos])))
(s/def :entity/type int?)
(s/def :entity/pos ::vec3)
(s/def :entity/vel ::vec3)
(s/def :entity/health double?)
(s/def :entity/flags (s/int-in 0 256))
(s/def :entity/died-at int?)
(s/def :entity/item int?)
(s/def :entity/creepy? boolean?)
(s/def :entity/phase int?)
(s/def :entity/radius double?)
(s/def :entity/yaw double?)
(s/def :world/dragon-dead? boolean?)
(s/def :world/boss-bars (s/coll-of uuid? :kind set?))
(s/def :pearls/shelter ::block-pos)
(s/def :pearls/mouth ::block-pos)
(s/def :throw/from ::vec3)
(s/def :throw/dir (s/tuple double? double?))
(s/def :throw/at int?)
(s/def ::throw (s/keys :req [:throw/from :throw/dir :throw/at]))
(s/def :stronghold/throws (s/coll-of ::throw :kind vector?))
(s/def :search/from ::block-pos)
(s/def :search/heading (s/tuple #{-1 0 1} #{-1 0 1}))
(s/def :search/leg nat-int?)
(s/def :search/tunnels nat-int?)
(s/def :search/best number?)
(s/def :search/stalled nat-int?)
(s/def :fortress/search
  (s/keys :req [:search/from :search/heading :search/leg :search/tunnels]
          :opt [:search/best :search/stalled]))
(s/def :forage/since int?)
(s/def :forage/leg nat-int?)
(s/def :forage/retry-at int?)
(s/def :forage/phase #{:early :kit})
(s/def :hunt/forage (s/keys :req [:forage/since :forage/leg] :opt [:forage/retry-at :forage/phase]))
(s/def :trail/target (s/coll-of number? :kind vector? :count 3))
(s/def :trail/cells (s/coll-of ::block-pos :kind vector?))
(s/def :trail/dead (s/coll-of ::block-pos :kind set?))
(s/def :fortress/trail (s/keys :req [:trail/target :trail/cells :trail/dead]))

;; packets, effects, events
(s/def :packet/name keyword?)
(declare fields-ok?)

(defn value-ok?
  "Does v fit wire type t: a primitive, [:vec T], or a struct (a vector of [key type] pairs)?"
  [t v]
  (cond (and (vector? t) (vector? (first t))) (and (map? v) (fields-ok? t v))
        (vector? t) (and (sequential? v) (every? #(value-ok? (second t) %) v))
        (empty? (str t)) false
        :else ((:valid? (p/wire-types t)) v)))

(defn fields-ok?
  "Does map m carry the fields of a spec, each fitting its type: all of them, or the first few
   and none after, as a packet decoded before the spec grew carries (a spec only grows by
   appending, so an old packet is a prefix of today's: a recorded login has only :entity-id)?
   Extra keys are fine."
  [fields m]
  (let [[held missing] (split-with (fn [[k]] (contains? m k)) fields)]
    (and (every? (fn [[k t]] (value-ok? t (get m k))) held)
         (not-any? (fn [[k]] (contains? m k)) missing))))

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
(s/def :go/chain? boolean?)             ; a run that plays every step up to its target, with milestones
(s/def :start/host string?)
(s/def :start/port int?)
(s/def :start/name string?)
(s/def ::event
  (s/keys :req [:event/kind]
          :opt
          [:event/now :event/rand :event/packet :event/reason :go/goals :go/at :go/chain?
           :start/host :start/port :start/name]))

;; plan and intents
(s/def :intent/kind keyword?)
(s/def :intent/status #{:active :done :failed})
(s/def ::target ; a block position, or the entity id a :fight or :back-off is after (issue #259)
  (s/or :block ::block-pos
        :entity int?))
(s/def :intent/target ::target)
(s/def ::intent (s/keys :req [:intent/kind] :opt [:intent/status :intent/target :intent/recipe]))
(s/def :plan/intent ::intent)
(s/def :plan/status #{:landing :active :done :failed})
(s/def :plan/go-at ::vec3)
(s/def :go/at ::vec3)
(s/def :plan/blacklist (s/coll-of ::target :kind set?)) ; a ghost's entity id too
(s/def :plan/trunk-failures (s/map-of (s/tuple int? int?) pos-int?))
(s/def :plan/boxed-at (s/coll-of ::block-pos :kind set?)) ; feet cells where a staircase died :boxed
(s/def :plan/boxed-route (s/coll-of ::block-pos :kind set?))
(s/def :plan/retreat-unreachable (s/coll-of ::block-pos :kind set?))
(s/def :plan/unreached (s/coll-of ::block-pos :kind set?))
(s/def :plan/unwalkable nat-int?)
(s/def :plan/trail (s/coll-of ::block-pos :kind vector?))
(s/def :plan/failed map?)
(s/def :backed-off/times pos-int?)
(s/def :backed-off/pos (s/coll-of number? :count 3))
(s/def :plan/backed-off ; creeper entity id → where it stood when first backed away from, and how often
  (s/map-of int? (s/keys :req-un [:backed-off/pos :backed-off/times])))
(s/def :plan/cornered (s/coll-of int? :kind set?)) ; entity ids a back-off ended on its clock beside
(s/def :plan/given-up (s/map-of int? int?)) ; entity id → ms a fight gave it up as unreachable
(s/def :plan/surfaced (s/tuple ::block-pos int?)) ; where and when the last climb out came out
(s/def :plan/goals (s/coll-of keyword? :kind set?))
(s/def :plan/chain? boolean?)
(s/def :plan/reached (s/coll-of keyword? :kind set?))
(s/def :plan/reached-after (s/map-of keyword? int?))
(s/def :intent/recipe keyword?)
(s/def :intent/window #{:inventory :table})
(s/def :intent/smelt keyword?)
(s/def :intent/count pos-int?)
(s/def :intent/cook-by int?)
(s/def :intent/lost pos-int?)
(s/def :intent/tries pos-int?)
(s/def :intent/resync boolean?)
(s/def :intent/item keyword?)
(s/def ::need-key
  (s/or :named keyword?
        :set (s/coll-of keyword? :kind set?)))
(s/def ::amount
  (s/or :count pos-int?
        :near #{:near}))
(s/def :goal/needs (s/map-of ::need-key ::amount))
(s/def :goal/holds (s/map-of ::need-key ::amount))    ; needs a row keeps rather than spends (a tool)
(s/def :goal/spares (s/map-of ::need-key ::amount))
(s/def :goal/passage-ok? boolean?)
(s/def :goal/build #{:cast-one :portal :nether}) ; which plan a :build row works (build/plan-for)                   ; a block it places may stand in a passage   ; one more of a held tool, made only from what is held
(s/def :goal/provides (s/map-of ::need-key ::amount :min-count 1))
(s/def :goal/done? keyword?)
(s/def :goal/act keyword?)
(s/def :goal/target? boolean?)
(s/def :goal/until string?)                ; the --until name that selects a target row
(s/def :goal/always? boolean?)
(s/def :goal/aside? boolean?)              ; a trial of its own, never a step of a run (plan/aside?)
(s/def :goal/trap #{:water :lava})
(s/def :goal/plan keyword?)                ; which next-intent planner the row uses (default :needs)
(s/def :goal/superseded-by keyword?)       ; the row that replaced this one; this one is kept, not chosen
(s/def :goal/blocks (s/coll-of keyword? :kind vector? :min-count 1)) ; what a :mine row digs
(s/def :goal/depth int?)                   ; the lowest feet height a :mine row cuts stairs to
(s/def :goal/dimension string?)            ; the dimension a row is worked in; elsewhere it waits
(s/def :goal/id keyword?)
(s/def :goal/priority int?)
(s/def ::goal
  (s/keys :req [:goal/id :goal/priority :goal/done?]
          :opt
          [:goal/needs :goal/holds :goal/spares :goal/passage-ok? :goal/build :goal/act
           :goal/target? :goal/until :goal/doc :goal/superseded-by :goal/always? :goal/aside?
           :goal/trap :goal/plan :goal/blocks :goal/depth :goal/dimension]))

(s/def ::world
  (s/keys :req [:bot/phase :bot/effects :time/now :time/tick]
          :opt
          [:bot/name :bot/sequence :bot/closed :bot/disconnected :player/pos :player/vel
           :player/look :player/on-ground? :player/horizontal-collision? :player/jump-ticks
           :player/loaded? :player/dimension :player/entity-id :player/inventory :player/controls
           :world/chunks :world/blocks :world/unconfirmed :world/facts :world/entities
           :window/state-id :window/grid :window/cursor :window/open :player/held-slot :plan/intent
           :plan/status :plan/blacklist :plan/trunk-failures :plan/chain? :plan/reached
           :plan/boxed-at :plan/boxed-route :plan/retreat-unreachable :plan/unreached
           :plan/unwalkable :plan/backed-off :plan/cornered :plan/given-up]))

(s/fdef game/step
  :args
  (s/cat :world ::world
         :event ::event)
  :ret ::world)
(s/fdef plan/step
  :args
  (s/cat :world ::world
         :event ::event)
  :ret ::world)
(s/fdef intent/run
  :args
  (s/cat :world ::world
         :intent ::intent
         :event ::event)
  :ret ::world)
(s/fdef plan/choose
  :args
  (s/cat :world ::world
         :table (s/coll-of ::goal))
  :ret (s/nilable ::goal))

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
(s/def ::recipe
  (s/and (s/keys :req [:recipe/id :recipe/result :recipe/count :recipe/kind]
                 :opt
                 [:recipe/pattern :recipe/key :recipe/width :recipe/height :recipe/ingredients])
         #(if (= :shaped (:recipe/kind %))
            (every? % [:recipe/pattern :recipe/key :recipe/width :recipe/height])
            (contains? % :recipe/ingredients))))
(s/def :click/slot (s/int-in 0 46))
(s/def :click/button (s/int-in 0 9))
(s/def :click/mode #{0 1 2 4})
(s/def ::click (s/keys :req [:click/slot :click/button :click/mode]))

(def recipes-valid? (every? #(s/valid? ::recipe %) recipe/recipes))

(s/fdef recipe/clicks
  :args
  (s/cat :inventory :player/inventory
         :recipe ::recipe
         :size #{2 3}
         :slot-of (s/? fn?))
  :ret (s/nilable (s/coll-of ::click :kind vector?)))

;; ---------------------------------------------------------------- the rest of the model
;; Every attribute clojurecraft.model lists has a spec; model_test holds that. s/keys checks any
;; registered key a map carries, so these are checked wherever a world or intent is.

(s/def :bot/uuid uuid?)
(s/def :conn/host string?)
(s/def :conn/port int?)
(s/def :player/teleport-id int?)
(s/def :player/synced-at int?)
(s/def :server/time-at int?)
(s/def :server/tick-ms number?)
(s/def :player/health number?)
(s/def :player/hurt-at int?)
(s/def :player/attacker int?)
(s/def :world/boss-health (s/map-of uuid? number?))
(s/def :player/food int?)               ; 0-20 from a server; the wire allows any varint, and step is total over it
(s/def :player/saturation number?)
(s/def :intent/food-before int?)
(s/def :intent/eat-by number?)
(s/def :entity/seen-at int?)
(s/def :sight/pos ::block-pos)
(s/def :sight/state int?)
(s/def :sight/at int?)
(s/def :pool/chunk (s/coll-of int? :kind vector? :count 2))
(s/def :pool/kind #{:water :lava})
(s/def :pool/pos ::block-pos)
(s/def :pool/at int?)
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
(s/def :plan/waiting-ticks int?)
(s/def :plan/wait some?)
(s/def :intent/reason some?)
(s/def :intent/for keyword?)
(s/def :intent/enter? boolean?)
(s/def :intent/top int?)
(s/def :intent/under ::block-pos)
(s/def :intent/refusals nat-int?)
(s/def :intent/perched nat-int?)
(s/def :intent/pickup? boolean?)
(s/def :intent/step-down? boolean?)
(s/def :intent/cell ::block-pos)
(s/def :intent/click-face #{:down :up :north :south :west :east})
(s/def :intent/build #{:cast-one :portal :nether})
(s/def :intent/origin ::block-pos)
(s/def :intent/facing build/facings)
(s/def :intent/access nat-int?)
(s/def :intent/op ::op)
(s/def :intent/aim (s/coll-of number? :kind vector? :count 3))
(s/def :intent/expect :build/done)
(s/def :intent/fluid #{:water :lava})
(s/def :intent/move (s/coll-of #{-1 0 1} :kind vector? :count 3)) ; a :tunnel step's [dx dy dz]
(s/def :intent/from ::block-pos)
(s/def :intent/from-feet ::block-pos)
(s/def :intent/dead-end ::block-pos)
(s/def :intent/taken nat-int?)
(s/def :intent/tunnel-visited (s/coll-of ::block-pos :kind set?))
(s/def :intent/retreats nat-int?)
(s/def :intent/airborne nat-int?)
(s/def :intent/steps pos-int?)
(s/def :intent/reach (s/and number? pos?))
(s/def :intent/climb nat-int?)
(s/def :intent/dry? boolean?)
(s/def :intent/search :fortress/search)
(s/def :intent/bridge? boolean?)
(s/def :intent/trunk ::block-pos)
(s/def :intent/stage keyword?)
(s/def :intent/since int?)
(s/def :intent/started int?)
(s/def :intent/still nat-int?)
(s/def :intent/face int?)
(s/def :intent/next-swing number?)
(s/def :intent/swings pos-int?)
(s/def :intent/target-pos ::vec3)
(s/def :intent/spawner ::block-pos)
(s/def :intent/blazes (s/coll-of int? :kind set?))
(s/def :intent/health number?)
(s/def :intent/hold? boolean?)
(s/def :intent/yield? boolean?)
(s/def :intent/to-the-end? boolean?)
(s/def :intent/mouth ::block-pos)
(s/def :intent/eye-from ::vec3)
(s/def :intent/eye-pos ::vec3)
(s/def :intent/gone-at int?)
(s/def :intent/finish-at number?)
(s/def :intent/slot (s/int-in 0 9))
(s/def :intent/tool (s/nilable int?))
(s/def :intent/dig-ms nat-int?)
(s/def :intent/state int?)
(s/def :intent/confirm-left nat-int?)
(s/def :intent/to-y int?)
(s/def :intent/tall pos-int?)
(s/def :intent/floor-to int?)
(s/def :intent/to-height? boolean?)
(s/def :intent/dir (s/tuple int? int?))
(s/def :intent/turns nat-int?)
(s/def :intent/rejections pos-int?)
(s/def :intent/cells (s/coll-of ::block-pos :kind vector? :min-count 3))
(s/def :intent/level-cells (s/coll-of ::block-pos :kind vector? :count 2))
(s/def :intent/bore (s/coll-of ::block-pos :kind vector? :min-count 1))
(s/def :intent/lay (s/coll-of ::block-pos :kind vector? :min-count 1 :max-count 12)) ; a floor and its holder, a step, or a seal over the water beside three cells
(s/def :intent/level-steps pos-int?)
(s/def :intent/length pos-int?)
(s/def :intent/side (s/tuple int? int?))
(s/def :intent/level-dir (s/tuple int? int?))
(s/def :intent/rise #{-1 1})
(s/def :intent/visited (s/coll-of ::block-pos :kind set?))
(s/def :intent/child (s/keys :req [:intent/kind]))
(s/def :intent/started-y number?)
(s/def :intent/started-tick int?)
(s/def :intent/best-dist number?)
(s/def :intent/best-tick int?)
(s/def :intent/detour-until int?)
(s/def :intent/detour-yaw number?)
(s/def :intent/detours nat-int?)
(s/def :intent/waypoints (s/coll-of ::block-pos :kind vector?))
(s/def :intent/route #{:found :partial :none})
(s/def :intent/at nat-int?)
(s/def :intent/replans nat-int?)
(s/def :intent/max-nodes pos-int?)
(s/def :intent/planned-chunks nat-int?)
(s/def :path/waypoints (s/coll-of ::block-pos :kind vector?))
(s/def :path/cost double?)
(s/def :path/status #{:found :partial :none})
(s/def :path/max-nodes pos-int?)
(s/def :path/from ::block-pos)
(s/def ::route (s/keys :req [:path/waypoints :path/cost :path/status] :opt [:path/from]))
(s/def :goal/kind #{:near :block :xz :away :afar :unboxed-away :pickup :sky :surface :unseen}) ; :unseen is blaze.clj's: out of a blaze's sight
(s/def :goal/pos ::block-pos)
(s/def :goal/range number?)
(s/def :goal/rise nat-int?)
(s/def :goal/step-down? boolean?)
(s/def :goal/boxed (s/coll-of ::block-pos :kind set?))
(s/def :goal/cells (s/coll-of ::block-pos :kind vector?)) ; :unseen: the other blazes a cover is out of the sight of
(s/def ::path-goal
  ;; :sky and :surface have no :goal/pos: the mouth is wherever their terrain predicate holds
  (s/and (s/keys :req [:goal/kind]
                 :opt [:goal/pos :goal/range :goal/rise :goal/step-down? :goal/boxed :goal/cells])
         #(or (contains? #{:sky :surface} (:goal/kind %)) (contains? % :goal/pos))))
(s/def :intent/logs-before nat-int?)
(s/def :intent/drops (s/coll-of int? :kind set?))
(s/def :intent/drops-before nat-int?)
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
(s/def :intent/refused (s/coll-of ::block-pos :kind set?))
(s/def :intent/nudges nat-int?)
(s/def :intent/sequence int?)
(s/def :chunk/column map?)
(s/def :chunk/min-y #{-64 0})
(s/def :plan/intents pos-int?)
(s/def :intent/id pos-int?)
(s/def :intention/id pos-int?)
(s/def :intention/event #{:started :done :failed :abandoned})
(s/def :intention/kind keyword?)
(s/def :intention/at int?)
(s/def :intention/target ::target)
(s/def :intention/recipe keyword?)
(s/def :intention/reason some?)
(s/def :answer/kind #{:ack :pickup :death})
(s/def :answer/at int?)
(s/def :answer/sequence int?)
(s/def :answer/entity int?)
(s/def :answer/count int?)

;; problem statements (resources/clojurecraft/problems.edn), written before each stage is built
(s/def :claim/by string?)
(s/def :claim/cell (s/tuple int? int?))
(s/def :claim/granted? boolean?)
(s/def ::claim (s/keys :req [:claim/by :claim/cell] :opt [:claim/granted?]))
(s/fdef claims/claim
  :args
  (s/cat :claims (s/map-of :claim/cell :claim/by)
         :request ::claim)
  :ret (s/tuple (s/map-of :claim/cell :claim/by) ::claim))
(s/def :race/target (s/nilable keyword?))
(s/def :race/reached nat-int?)
(s/def :race/dimension (s/nilable string?))
(s/def :race/y (s/nilable int?))
(s/def :race/deaths nat-int?)
(s/def :race/status (s/nilable keyword?))
(s/def :race/bots nat-int?)
(s/def :race/ok nat-int?)
(s/def :race/reasons (s/map-of any? pos-int?))
(s/def :race/runs vector?)
(s/def :race/seed (s/nilable string?))
(s/def :race/commit (s/nilable string?))
(s/def :race/until string?)
(s/def :race/unplayed nat-int?)
(s/def :race/down? boolean?)
(s/def :sample/runner (s/nilable int?))
(s/def :sample/server (s/nilable int?))
(s/def :race/server int?)
(s/def :milestone/server int?)
(s/def :sample/at int?)
(s/def :sample/joined nat-int?)
(s/def :sample/landed nat-int?)
(s/def :sample/playing nat-int?)
(s/def :sample/ended nat-int?)
(s/def :sample/cut-off nat-int?)
(s/def :sample/tick-rate (s/map-of #{:median :p10 :min} (s/nilable number?)))
(s/def :sample/milestones nat-int?)
(s/def :sample/server-tick (s/map-of #{:avg :p50 :p95 :p99} number?))
(s/def :sample/bot-heap-mb nat-int?)
(s/def :sample/threads nat-int?)
(s/def :milestone/runner (s/nilable int?))
(s/def :milestone/seed (s/nilable string?))
(s/def :milestone/bot string?)
(s/def :milestone/goal keyword?)
(s/def :milestone/at int?)
(s/def :milestone/after-s int?)
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
(s/def :problem/decisions (s/coll-of ::text :kind vector? :min-count 1)) ; the open questions answered before the code
(s/def ::problem
  (s/keys :req
          [:problem/stage :problem/statement :problem/needs :problem/risks :problem/done
           :problem/last-failure :problem/sources]
          :opt [:problem/issue :problem/goals :problem/decisions]))
(s/def :intent/goal keyword?)

;; the server model (sim): a reducer too, so it is specced and instrumented like the bot's
(s/def :sim/kind #{:packet :tick})
(s/def :sim/packet ::packet)
(s/def :sim/now int?)
(s/def ::sim-event (s/keys :req [:sim/kind] :opt [:sim/packet :sim/now]))
(s/def :sim/phase #{:handshake :login :configuration :play})
(s/def :sim/out (s/coll-of ::packet :kind vector?))   ; what the model says is held to the packet table
(s/def :sim/inv (s/map-of int? p/slot?))
(s/def :sim/state-id int?)
(s/def :sim/held int?)
(s/def :sim/placed map?)
(s/def :sim/rng int?)
(s/def :sim/furnaces (s/map-of ::block-pos map?))
(s/def :sim/falls (s/map-of ::block-pos int?))
(s/def :sim/fluids (s/map-of ::block-pos int?)) ; a fluid cell → when its next tick is due, ms (sim/wake-fluids)
(s/def :sim/fluid-sources-ready? boolean?)
(s/def :sim/dimension string?)
(s/def :sim/portal-since int?)
(s/def :sim/kill-at int?)
(s/def :sim/nether ; the Nether a portal leads to: one column, or a real one's columns (issue #299)
  (s/and (s/keys :opt-un
                 [:sim.nether/at :sim.nether/column :sim.nether/columns :sim.nether/decoded
                  :sim.nether/view-distance])
         ;; whole columns may leave :at out: the arrival is then the portal's (sim/arrival, #353)
         #(or (:columns %) (and (:column %) (:at %)))))
(s/def :sim.nether/column bytes?)
(s/def :sim.nether/columns (s/map-of ::column-key bytes?))
(s/def :sim.nether/decoded map?)              ; the same columns decoded once, shared by every run of a set
(s/def :sim.nether/view-distance pos-int?)    ; chunks streamed round the player there, as a server's view
(s/def :sim.nether/at ::vec3)
(s/def :sim/falling (s/map-of ::block-pos map?))
(s/def :sim/next-window int?)
(s/def :sim/clicks nat-int?)
(s/def :sim/drop-clicks set?)
(s/def :sim/breaks nat-int?)
(s/def :sim/drop-breaks set?)
(s/def :sim/violations vector?)
(s/def :sim/keep-alive-every pos-int?)
(s/def :sim/lag-ticks nat-int?)
(s/def ::column-key (s/tuple int? int?))
(s/def :sim/columns (s/map-of ::column-key bytes?))   ; the world: each column as a server sent it
(s/def :sim/decoded (s/map-of ::column-key map?))     ; the same columns decoded once, shared by every run
(s/def :sim/view (s/coll-of ::column-key :kind set?)) ; the columns the client holds now
(s/def :sim/view-at ::column-key)                     ; the column the player was streamed from
(s/def :sim/view-distance pos-int?)
(s/def :sim/player-eid int?)                          ; a joined model's player: the real server's id (sim/joined)
(s/def :sim/tick-seed int?)                           ; where a run's tick randomness starts
(s/def :sim/spawn ::vec3)
(s/def :sim/broken (s/coll-of ::block-pos :kind set?))
(s/def :sim/items (s/map-of int? map?))
(s/def :sim/next-eid int?)
(s/def :sim/next-keep-alive int?)
(s/def :sim/keep-alives-pending set?)
;; hunger in the model (issue #260): the server's FoodData, its difficulty, eating, hurts
(s/def :sim/difficulty #{:peaceful :easy :normal :hard})
(s/def :sim/food (s/int-in 0 21))                         ; the hunger bar (#259)
(s/def :sim/saturation (s/double-in :min 0.0 :max 20.0 :NaN? false)) ; spent before the bar
(s/def :sim/exhaustion (s/double-in :min 0.0 :max 40.0 :NaN? false)) ; four of it cost a point
(s/def :sim/food-timer nat-int?)                         ; ticks on the heal or starve clock
(s/def :sim/sent-health (s/tuple number? int? boolean?)) ; health, food, saturation zero? last told
(s/def :sim/on-ground? boolean?)                         ; the last movement packet's flag
(s/def :sim/eaten nat-int?)                              ; foods the player finished eating
(s/def :sim.eating/slot int?)
(s/def :sim.eating/item int?)
(s/def :sim.eating/at int?)
(s/def :sim/eating (s/keys :req-un [:sim.eating/slot :sim.eating/item :sim.eating/at]))
(s/def :sim.hurt/at int?)
(s/def :sim.hurt/last number?)
(s/def :sim/hurt (s/keys :req-un [:sim.hurt/at :sim.hurt/last])) ; the last hurt: when, how hard
;; mobs in the model (issue #260): each one's kind, place, health and clocks (mob.clj, sim/tick-mobs)
(s/def :sim.mob/type (set (keys mob/kinds)))
(s/def :sim.mob/pos ::vec3)
(s/def :sim.mob/health (s/double-in :min 0.0 :NaN? false :infinite? false))
(s/def :sim.mob/hit-at int?)                   ; when it was last hurt (the hurt window)
(s/def :sim.mob/last-hit number?)              ; how hard
(s/def :sim.mob/attack-at int?)                ; when it may hit or shoot again
(s/def :sim.mob/fuse-at int?)                  ; when a creeper lit its fuse
(s/def :sim.mob/dead-at int?)                  ; when it died; removed once its death is over
(s/def :sim.mob/angry? boolean?)               ; a neutral one has turned on the player
(s/def :sim.mob/persistent? boolean?)          ; never despawned: a fixture's, an animal
(s/def :sim.mob/yaw double?)                   ; its body's yaw in degrees, told at add-entity: the dragon's says where its head is
(s/def :sim.mob/route (s/coll-of (s/tuple int? int? int?) :kind vector? :min-count 1)) ; the cells it walks round a wall (sim/trail, issue #263)
(s/def :sim.mob/route-at int?)                 ; when it last searched a route
(s/def :sim.mob/far-ticks nat-int?)            ; ticks an angry enderman has stood far from its target (sim/blink)
(s/def ::sim-mob
  (s/keys :req-un [:sim.mob/type :sim.mob/pos :sim.mob/health]
          :opt-un
          [:sim.mob/hit-at :sim.mob/last-hit :sim.mob/attack-at :sim.mob/fuse-at :sim.mob/dead-at
           :sim.mob/angry? :sim.mob/persistent? :sim.mob/yaw :sim.mob/route :sim.mob/route-at
           :sim.mob/far-ticks]))
(s/def :sim/mobs (s/map-of int? ::sim-mob))
(s/def :sim/fixture-mobs (s/coll-of (s/keys :req-un [:sim.mob/type :sim.mob/pos]) :kind vector?))
(s/def :sim/mob-rng int?)                      ; the mobs' own roll stream (sim/mob-roll)
(s/def :sim/wild-rng int?)                     ; the Nether's own spawns' roll stream (sim/wild-roll, issue #263)
(s/def :sim/day-time int?)                     ; game ticks into the days, night from 13000 of each 24000
(s/def :sim/populated (s/coll-of (s/tuple int? int?) :kind set?)) ; chunks whose animals were placed
(s/def :sim/populated-at (s/tuple int? int?))  ; the column the player was in when they last were
(s/def :sim/player-look (s/tuple double? double?)) ; [yaw pitch] the client last reported
;; eyes, the portal and the End in the model (issue #260; end.clj)
(s/def ::point (s/coll-of number? :kind vector? :count 3))
(s/def :sim/stronghold ::point)                ; where a thrown eye flies toward
(s/def :sim.eye/from ::point)
(s/def :sim.eye/pos ::point)
(s/def :sim.eye/target ::point)
(s/def :sim.eye/at int?)
(s/def :sim.eye/survives? boolean?)
(s/def :sim/eyes
  (s/map-of int?
            (s/keys :req-un
                    [:sim.eye/from :sim.eye/pos :sim.eye/target :sim.eye/at :sim.eye/survives?])))
(s/def :sim.end/columns (s/map-of ::column-key bytes?))
(s/def :sim.end/dragon ::point)
(s/def :sim.end/crystals (s/coll-of ::point))
(s/def :sim.end/dragon-yaw number?)
(s/def :sim/end
  (s/keys :opt-un [:sim.end/columns :sim.end/dragon :sim.end/crystals :sim.end/dragon-yaw]))
(s/def ::sim (s/keys :req [:sim/phase :sim/now :sim/out]))
(s/fdef sim/step
  :args
  (s/cat :sim ::sim
         :event ::sim-event)
  :ret ::sim)

;; ---------------------------------------------------------------- selection
;; Maybe Not: schema (what an attribute is, the specs above) and selection (which attributes a
;; given function needs) are different things. A selection only says which keys must be
;; present; their shapes come from the schema. Each function below states its selection as an
;; fdef on its world argument; selected lists them for instrumentation.

(defn requires
  "A spec for a map that holds every key in ks (selection only: shapes are the schema's)."
  [& ks]
  (s/and map? (fn has-every-key [m] (every? #(contains? m %) ks))))

(s/fdef game/eye
  :args
  (s/cat :world (requires :player/pos)))
(s/fdef physics/step
  :args
  (s/cat :solid? fn?
         :fluid-at (s/? fn?)
         :world (requires :player/pos)
         :controls map?))
(s/fdef terrain/fluid-fn
  :args
  (s/cat :world (requires :world/chunks)))
(s/fdef inventory/item-count
  :args
  (s/cat :world (requires :player/inventory)
         :id int?))
(s/fdef inventory/logs-held
  :args
  (s/cat :world (requires :player/inventory)))
(s/fdef terrain/block-at
  :args
  (s/cat :world (requires :world/chunks)
         :pos (s/coll-of number? :count 3)))
(s/fdef terrain/solid-fn
  :args
  (s/cat :world (requires :world/chunks)))
(s/fdef memory/positions-now
  :args
  (s/cat :world (requires :world/facts)
         :states coll?))
(s/fdef memory/nearest-surface-water
  :args
  (s/cat :world (requires :world/facts)
         :eye ::vec3
         :radius number?))
(s/fdef memory/nearest-log
  :args
  (s/cat :world (requires :world/facts)
         :eye ::vec3
         :radius number?
         :blacklist any?
         :bottom? (s/? ifn?)))
(s/fdef make/near
  :args
  (s/cat :world (requires :player/pos :world/facts)
         :block keyword?))
(s/fdef make/decide
  :args
  (s/cat :world (requires :player/inventory :player/pos :world/facts)
         :goal map?
         :table vector?))
(s/fdef wood/gather-next
  :args
  (s/cat :world (requires :player/pos :world/facts)))
(s/fdef wood/return-walk
  :args
  (s/cat :world (requires :player/pos :world/facts :world/chunks)))
(s/fdef place/spot
  :args
  (s/cat :world (requires :player/pos :world/chunks)
         :passage-ok? (s/? boolean?)
         :refused (s/? :intent/refused)
         :along? (s/? boolean?)))
(s/fdef place/ashore
  :args
  (s/cat :world (requires) ; reads :player/in-water?, absent before the first physics tick: dry
         :intent map?))
(s/fdef stone/redig-place
  :args
  (s/cat :world (requires :player/pos :world/chunks)
         :item keyword?))
(s/fdef stone/retreat
  :args
  (s/cat :world (requires :player/pos :world/chunks)))
(s/fdef path/plan
  :args
  (s/cat :world (requires :world/chunks)
         :from ::block-pos
         :goal ::path-goal
         :opts (s/? (s/keys :opt [:path/max-nodes])))
  :ret ::route)
(s/fdef path/classify
  :args
  (s/cat :world (requires :world/chunks)
         :pos ::block-pos))
(s/fdef entity/hostiles
  :args
  (s/cat :world (requires :world/entities)))
(s/fdef entity/gone?
  :args
  (s/cat :world (requires :world/entities)
         :eid int?))
(s/fdef pearls/endermen
  :args
  (s/cat :world (requires :world/entities :player/pos)
         :radius (s/? number?)))
(s/fdef eat/food-held
  :args
  (s/cat :world (requires :player/inventory)))
(s/fdef blaze/blazes
  :args
  (s/cat :world (requires :player/pos :world/entities)))
(s/fdef drops/lying
  :args
  (s/cat :world (requires :world/entities)
         :id int?
         :at ::vec3
         :radius number?))
(s/fdef window/view
  :args
  (s/cat :world (requires :window/grid)
         :which #{:inventory :table :furnace}))

(s/fdef stairs/down-safe?
  :args
  (s/cat :world (requires :world/chunks)
         :cells (s/coll-of ::block-pos)))
(s/fdef stairs/down-lay
  :args
  (s/cat :world (requires :world/chunks :player/inventory)
         :feet ::block-pos
         :cells (s/coll-of ::block-pos)))

(s/fdef build/safe-source?
  :args
  (s/cat :world (requires :world/chunks :player/pos)
         :src ::block-pos)
  :ret boolean?)

(s/fdef build/scoop-blocked?
  :args
  (s/cat :world (requires :world/chunks :player/pos)
         :child map?)
  :ret boolean?)

(s/fdef build/lit-entry
  :args
  (s/cat :world (requires :world/chunks)
         :plan (s/nilable ::plan))
  :ret (s/nilable ::op))

(s/fdef fortress/next-move
  :args
  (s/cat :world (requires :world/chunks)
         :feet ::block-pos
         :target ::block-pos
         :climb nat-int?
         :dry-only? boolean?
         :visited :intent/tunnel-visited
         :bridge-ok? (s/? boolean?))
  :ret (s/nilable :intent/move))

(s/fdef end-portal/next-op
  :args
  (s/cat :world (requires :player/pos :world/facts :world/chunks)
         :enter? boolean?)
  :ret
  (s/nilable (s/or :op ::op
                   :why keyword?)))

(s/fdef dragon/best-approach
  :args
  (s/cat :world (requires :player/pos :world/chunks)
         :pos ::vec3)
  :ret (s/nilable map?))

(s/fdef dragon/toward-crystals
  :args
  (s/cat :world (requires :player/pos :world/entities :world/chunks))
  :ret (s/nilable map?))

(def selected
  "Every function whose selection is stated above, for instrument."
  [`game/eye `physics/step `inventory/item-count `inventory/logs-held `terrain/block-at
   `terrain/solid-fn `terrain/fluid-fn `memory/positions-now `memory/nearest-surface-water
   `memory/nearest-log `make/near `make/decide `wood/gather-next `wood/return-walk `place/spot
   `place/ashore `stone/redig-place `window/view `path/plan `path/classify `entity/hostiles
   `entity/gone? `eat/food-held `drops/lying `blaze/blazes `pearls/endermen `stairs/down-safe?
   `stairs/down-lay `stone/retreat `build/safe-source? `build/scoop-blocked? `build/lit-entry
   `fortress/next-move `end-portal/next-op `dragon/best-approach `dragon/toward-crystals])
(s/def :stats/deaths pos-int?)
