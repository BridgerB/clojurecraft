(ns clojurecraft.shield
  "The shield raised and lowered. A shield in the offhand (armour/part puts it there) blocks, while
   the use key holds it up, every melee hit and every shot from in front of the bot: the server
   decides it from the look it last had and the attacker's place (the shield's blocks_attacks,
   90 degrees either side of the look), from raise-ticks after the use. It is up from a use-item
   with the offhand (raise) to a player-action release-use-item (lower); the bot keeps the time it
   raised it in :player/shield-up-at.

   The :fight executor holds it up while it stands at its target and lowers it to swing
   (combat/guarded), and combat's :block, the cover from a shooter in sight, holds it up facing
   the shooter. Everything else is guard, run by the planner after each tick's intent: while the
   bot stands still (no intent, or one of still-kinds, no dig in progress) it is up toward the
   threat (threat: what struck a moment ago, else the nearest hostile within threat-reach), faced
   when the intent leaves the look free (look-free), else only when the threat is already in front
   (in-front?); in a tick whose intent used a hand (hand-packets: a dig, a place, a use, a swing) it
   is let go just before that packet (lowered-before-hand), and otherwise it is down. guard changes
   only the look and the offhand, never where the bot goes or what it does. A raised shield is
   never carried on a walk: a vanilla client walks at a fifth of its speed with it up, and this
   bot's physics does not."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def raise-ticks 5)                   ; server ticks from the use to a shield that blocks (blocks_attacks' block_delay_seconds 0.25)
(def struck-ms 4000)                  ; ms a shooter's strike stays a reason to face it with the shield up
(def melee-struck-ms 2000)            ; ms any other strike stays a reason to raise the shield toward the striker
(def threat-reach 4.0)                ; blocks within which a hostile is a threat the shield goes up toward unstruck
(def front-degrees 75.0)              ; degrees off the look a threat may be and count as in front (the server's cone is 90)

(def still-kinds
  "Intent kinds the bot stands still in between uses of its hand, so the shield goes up in them."
  #{:lookout :pillar :burrow :dig :pocket-hold :rest})

(def look-free
  "Intent kinds whose look the shield may turn toward a threat: a lookout's turning and a rest's
   stare decide nothing the threat does not."
  #{:lookout :pocket-hold :rest})

(def hand-packets
  "Packets that use the main hand: a vanilla client sends none with its use key held."
  #{:player-action :use-item-on :use-item :attack :swing :interact})

(defn held?
  "Is a shield in the offhand (inventory/offhand-slot)?"
  [world]
  (= :shield
     (some-> (get-in world [:player/inventory inventory/offhand-slot])
             :item
             recipe/item-name)))

(defn raised?
  "Has the bot raised its shield and not lowered it (:player/shield-up-at)?"
  [world]
  (some? (:player/shield-up-at world)))

(defn blocking?
  "Does the raised shield block by now: raise-ticks of the server's ticks (stretched by its
   slowdown, intent/slowdown) since it went up, the shield still in the offhand?"
  [world]
  (boolean (when-let [at (:player/shield-up-at world)]
             (and (held? world)
                  (>= (- (:time/now world 0) at)
                      (* raise-ticks game/server-tick-ms (intent/slowdown world)))))))

(defn raise
  "The world with the shield raised: one use-item with the offhand along the look, its sequence
   counted (:bot/sequence), and :player/shield-up-at now."
  [world]
  (let [seq (inc (:bot/sequence world 0))
        [yaw pitch] (:player/look world [0.0 0.0])]
    (-> world
        (assoc :bot/sequence seq :player/shield-up-at (:time/now world 0))
        (game/emit {:packet/name :use-item
                    :hand (packet/value :hand :off-hand)
                    :sequence seq
                    :y-rot yaw
                    :x-rot pitch}))))

(defn lower
  "The world with the shield lowered: the use key let go, a player-action release-use-item at the
   origin facing down as the vanilla client sends it, and :player/shield-up-at dropped."
  [world]
  (-> world
      (game/emit {:packet/name :player-action
                  :status (packet/value :dig :release-use-item)
                  :pos [0 0 0]
                  :face (packet/value :face :down)
                  :sequence 0})
      (dissoc :player/shield-up-at)))

(defn hold
  "The world with the shield up when up? and a shield is held (raise, unless it is up already),
   else down (lower, when it is up); unchanged when it already is as asked."
  [world up?]
  (let [up? (and up? (held? world))]
    (cond (and up? (not (raised? world))) (raise world)
          (and (not up?) (raised? world)) (lower world)
          :else world)))

(defn standing?
  "Do controls c leave the feet where they are: no walk and no jump?"
  [c]
  (not (or (:control/forward? c) (:control/jump? c) (:control/sneak? c))))

(def projectiles
  "Entity kinds a shot is: the damage-event names the arrow or the fireball as the attacker, not
   the mob that loosed it."
  #{:arrow :spectral_arrow :small_fireball :fireball})

(defn shoots?
  "Does entity e's kind shoot (mob/kinds :shoot)?"
  [e]
  (boolean (:shoot (mob/kinds (entity/kind e)))))

(defn shooter
  "[eid entity] of what shot the bot, while the strike (:player/struck-at) is under struck-ms old:
   the attacker (:player/attacker) when it shoots, or, when the attacker was a shot (projectiles,
   or already gone, as an arrow is once it lands), the nearest living entity whose kind shoots
   within its :range of the feet. Nil otherwise."
  [world]
  (let [id (:player/attacker world)
        at (:player/struck-at world)
        e (get-in world [:world/entities id])
        pos (:player/pos world)]
    (cond (not (and at pos (<= (- (:time/now world 0) at) struck-ms))) nil
          (and e (shoots? e)) [id e]
          (or (nil? e) (contains? projectiles (entity/kind e)))
          (->> (:world/entities world)
               (filter (fn [[_ x]]
                         (and (shoots? x)
                              (not (entity/dead? x))
                              (<= (physics/distance pos (:entity/pos x))
                                  (:range (mob/kinds (entity/kind x)))))))
               (sort-by (fn [[_ x]] (physics/distance pos (:entity/pos x))))
               first)
          :else nil)))

(defn striker
  "[eid entity] of the living entity that struck the bot (:player/attacker) under melee-struck-ms
   ago, when it is no shot (projectiles); nil otherwise."
  [world]
  (let [id (:player/attacker world)
        at (:player/struck-at world)
        e (get-in world [:world/entities id])]
    (when (and e
               at
               (<= (- (:time/now world 0) at) melee-struck-ms)
               (not (entity/dead? e))
               (not (contains? projectiles (entity/kind e))))
      [id e])))

(defn threat
  "[eid entity] the shield goes up toward: the shooter (shooter), else the striker (striker), else
   the nearest hostile (entity/hostiles) within threat-reach of the feet; nil when none is."
  [world]
  (or (shooter world)
      (striker world)
      (entity/nearest (entity/hostiles world) (:player/pos world) threat-reach)))

(defn aim-of
  "The point a shield faces on entity e: its middle."
  [e]
  (update (:entity/pos e) 1 + 0.9))

(defn in-front?
  "Is entity e within front-degrees of the look's yaw (:player/look)?"
  [world e]
  (let [[yaw] (physics/look-at (game/eye world) (aim-of e))
        d (mod (- yaw (first (:player/look world [0.0 0.0]))) 360.0)]
    (<= (min d (- 360.0 d)) front-degrees)))

(defn digging?
  "Is a dig in progress in the running intent or any child of it (a :dig at its :digging stage)?"
  [world]
  (boolean (some #(and (= :dig (:intent/kind %)) (= :digging (:intent/stage %)))
                 (take-while some? (iterate :intent/child (:plan/intent world))))))

(defn hand-effect?
  "Is effect f a packet that uses the main hand (hand-packets), the shield's own use and release
   aside?"
  [{:effect/keys [kind packet]}]
  (and (= :send kind)
       (contains? hand-packets (:packet/name packet))
       (not (and (= :use-item (:packet/name packet))
                 (= (packet/value :hand :off-hand) (:hand packet))))
       (not (and (= :player-action (:packet/name packet))
                 (= (packet/value :dig :release-use-item) (:status packet))))))

(defn lowered-before-hand
  "The world with a raised shield let go (lower's packet) just before the first effect of this
   tick that uses the hand (hand-effect?), :player/shield-up-at dropped; unchanged when it is
   down."
  [world]
  (if-not (raised? world)
    world
    (let [fx (vec (:bot/effects world))
          n (count (take-while (complement hand-effect?) fx))
          down (:bot/effects (lower (assoc world :bot/effects [])))]
      (-> world
          (assoc :bot/effects (into (into (subvec fx 0 n) down) (subvec fx n)))
          (dissoc :player/shield-up-at)))))

(defn guard
  "The world after a planner tick with the shield as the moment asks: left to a running :fight or
   :block (combat holds it); let go before the hand's packet in a tick that used the hand
   (lowered-before-hand); up toward the threat while the bot stands still in no intent or one of
   still-kinds with no dig in progress (digging?), the look turned to the threat's middle when the
   intent leaves it free (no intent, or look-free), else only when the threat is in front
   (in-front?); down otherwise."
  [world]
  (let [i (:plan/intent world)
        kind (:intent/kind i)]
    (cond (#{:fight :block} kind) world
          (some hand-effect? (:bot/effects world)) (lowered-before-hand world)
          :else
          (let [still? (and (:player/pos world)
                            (or (nil? i) (contains? still-kinds kind))
                            (not (digging? world))
                            (standing? (:player/controls world)))
                [_ e] (when (and still? (held? world)) (threat world))
                face? (and e (or (nil? i) (contains? look-free kind)))
                up? (boolean (and e (or face? (in-front? world e))))]
            (cond-> world
              face?
              (assoc :player/controls {:control/look (physics/look-at (game/eye world) (aim-of e))})
              true (hold up?))))))
