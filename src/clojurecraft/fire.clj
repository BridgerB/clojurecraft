(ns clojurecraft.fire
  "Fire on the player as the server keeps it, as values and pure functions for the server model
   (sim.clj): what vanilla's Entity.baseTick does with a player alight. A fire is nil (not
   burning) or {:until ms :next ms}: the ms the burning ends and the ms of its next hurt.
   Igniting (ignite) sets the burning to last ms from now and hurts at once, then every second
   while it lasts (burn-damage, on_fire: armour does not take any of it); a fire already burning is
   kept as it is unless the new one outlasts it. Water puts it out (tick). Standing in a fire
   block lights it again every tick (in-fire-ms). Lava is the server's lavaHurt: four points, and
   fifteen seconds of this same fire (lava-damage, lava-ms); the model applies that in sim/tick-lava.

   The real numbers: race f8b28e3, lane 2, 45 recordings, 3599 blazes in view (test/clojurecraft/real_blaze.edn): the burn was 1.0 per hurt in 1778 hurts, 45% of all health the
   blaze fights cost, and a small fireball's hit sets fire for five seconds (SmallFireball)."
  (:require [clojurecraft.blocks :as blocks]))

(set! *warn-on-reflection* true)

(def fireball-ms 5000)                ; a small fireball's hit sets the target on fire for this long
(def in-fire-ms 8000)                 ; standing in a fire block (in_fire: Entity.lavaIgnite / FireBlock.entityInside)
(def lava-ms 15000)                   ; standing in lava sets the player on fire for this long (Entity.lavaHurt, igniteForSeconds 15)
(def lava-damage 4.0)                 ; one lava hurt (Entity.lavaHurt); the hurt window lands it once a half-second
(def burn-damage 1.0)                 ; one hurt of burning (the on_fire damage type, 1.0 in every recorded hurt)
(def burn-every-ms 1000)              ; between two hurts of one burning (every 20th game tick)

(defn ignite
  "The fire after the player is set alight for ms at now: nil becomes {:until now+ms :next now}
   (a hurt at once), a burning one keeps its next hurt and the later end."
  [fire now ms]
  (let [until (+ now ms)] (if fire (update fire :until max until) {:until until :next now})))


(def block-ms 3000)                   ; a fire block on a floor that does not burn for ever goes out after about this long (FireBlock.tick: age past 3 with nothing to burn)
(def eternal-bases
  "The blocks a fire on top of them never leaves (the infiniburn_nether tag): netherrack and magma."
  #{:netherrack :magma_block})

(defn block-until
  "The ms a fire block lit at now over a floor of block name base goes out at, nil for ever: on
   netherrack and magma it burns for good (the fire a fireball leaves in a fortress's netherrack,
   which lights whoever walks through it again), else block-ms on."
  [base now]
  (when-not (contains? eternal-bases base) (+ now block-ms)))

(defn flame?
  "Is block id (a state id) a fire or soul fire, whoever stands in it burns?"
  [id]
  (contains? blocks/fire-states id))

(defn tick
  "[fire' event] after one game tick at now, for a player standing in water? and/or in a fire
   block (flame?): :hurt when a burn's hurt is due, :out when the burning ended or water put it
   out, else nil. A fire block lights the player first (in-fire-ms)."
  [fire now {:keys [water? flame?]}]
  (let [fire (cond-> fire flame? (ignite now in-fire-ms))]
    (cond (nil? fire) [nil nil]
          (or water? (>= now (:until fire))) [nil :out]
          (>= now (:next fire)) [(update fire :next + burn-every-ms) :hurt]
          :else [fire nil])))
