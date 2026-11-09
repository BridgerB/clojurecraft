(ns clojurecraft.shield
  "The shield raised and lowered. A shield in the offhand (armour/part puts it there) blocks, while
   the use key holds it up, every melee hit and every shot from in front of the bot: the server
   decides it from the look it last had and the attacker's place (the shield's blocks_attacks,
   90 degrees either side of the look), from raise-ticks after the use. It is up from a use-item
   with the offhand (raise) to a player-action release-use-item (lower); the bot keeps the time it
   raised it in :player/shield-up-at.

   The :fight executor holds it up while it stands at its target and lowers it to swing
   (combat/guarded), and combat's :block, the cover from a shooter in sight, holds it up facing
   the shooter. Everything else is guard, run by the planner at the start of each tick before
   any intent: a shield up with no fight running is lowered before the next intent uses a hand,
   and while the planner waits, a shooter that struck a moment ago (struck-ms) is faced with the
   shield up. A raised shield is never carried on a walk: a vanilla client walks at a fifth of its
   speed with it up, and this bot's physics does not."
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
(def struck-ms 4000)                  ; ms a shooter's strike stays a reason to face it with the shield up while the planner waits

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

(defn guard
  "The world at the start of a planner tick with the shield as the moment asks: left to a running
   :fight or :block (combat holds it); while the planner waits with no intent, up and facing the shooter
   (shooter) that struck a moment ago, its middle looked at; otherwise down (hold false), so a
   shield left up by a fight that ended or was preempted is lowered before the next intent runs."
  [world]
  (let [i (:plan/intent world)
        [_ e] (when (and (nil? i) (:player/pos world)) (shooter world))]
    (cond (#{:fight :block} (:intent/kind i)) world
          (and e (held? world))
          (-> world
              (assoc :player/controls
                     {:control/look (physics/look-at (game/eye world)
                                                     (update (:entity/pos e) 1 + 0.9))})
              (hold true))
          :else (hold world false))))
