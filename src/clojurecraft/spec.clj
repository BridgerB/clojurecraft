(ns clojurecraft.spec
  "Specs for the information model: world attributes, events, effects, intents, packets. Shape
   lives here (what an attribute is); what a function requires is stated by that function. Load
   this namespace and `instrument` in tests; nothing here runs in the hot loop."
  (:require [clojure.spec.alpha :as s]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

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
(s/def ::packet (s/keys :req [:packet/name]))
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
(s/def ::event (s/keys :req [:event/kind] :opt [:event/now :event/rand :event/packet :event/reason :go/goals
                                                :start/host :start/port :start/name]))

;; plan and intents
(s/def :intent/kind keyword?)
(s/def :intent/status #{:active :done :failed})
(s/def :intent/target ::block-pos)
(s/def ::intent (s/keys :req [:intent/kind] :opt [:intent/status :intent/target :intent/recipe]))
(s/def :plan/intent ::intent)
(s/def :plan/status #{:active :done :failed})
(s/def :plan/blacklist (s/coll-of ::block-pos :kind set?))
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
(s/def :goal/id keyword?)
(s/def :goal/priority int?)
(s/def ::goal (s/keys :req [:goal/id :goal/priority :goal/provides :goal/done?]
                      :opt [:goal/needs :goal/act :goal/target? :goal/doc]))

(s/def ::world
  (s/keys :req [:bot/phase :bot/effects :time/now :time/tick]
          :opt [:bot/name :bot/sequence :bot/closed :bot/disconnected
                :player/pos :player/vel :player/look :player/on-ground? :player/horizontal-collision?
                :player/jump-ticks :player/loaded? :player/entity-id :player/inventory :player/controls
                :world/chunks :world/blocks :world/facts :world/entities
                :window/state-id :window/grid :window/cursor :window/open :player/held-slot
                :plan/intent :plan/status :plan/blacklist]))

(s/fdef game/step :args (s/cat :world ::world :event ::event) :ret ::world)
(s/fdef plan/step :args (s/cat :world ::world :event ::event) :ret ::world)
(s/fdef intent/run :args (s/cat :world ::world :intent ::intent :event ::event) :ret ::world)
(s/fdef plan/choose :args (s/cat :world ::world) :ret (s/nilable ::goal))

(def goals-valid? (every? #(s/valid? ::goal %) plan/goals))

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
