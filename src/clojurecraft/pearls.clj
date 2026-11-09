(ns clojurecraft.pearls
  "Ender pearls by hunting endermen (issue #263), never by bartering: bartering takes a stack of
   gold the run does not have. The game's loot table (data/minecraft/loot_table/entities/
   enderman.json) gives one roll of ender_pearl, its count uniform 0 to 1
   (plus 0 to 1 a level of looting): a kill drops a pearl one time in two, so twelve pearls are
   about twenty-four kills. A warped forest spawns endermen and nothing else hostile (its biome
   file's monster spawner), so that is where the Nether leg hunts.

   Requiring this namespace registers the :pearls plan (and, through clojurecraft.pearls.place, the
   :provoke, :burrow and :lookout intents). A
   row planned by :pearls (the :ender-pearls stage, the gym's :ender-pearl trial) takes, in order:
   the pearl of a kill just made (picked up by #262's :collect-drops, which reads what a drop
   holds, so a carried block's drop is left); the drop still to come while the body lies there (a
   wait); then the fight, from a burrow (hunt). An enderman hits for 7 a second on Normal, 5.74
   through iron, against forty health a sword takes seven hits to spend: in the open the bot
   loses. So the
   hunt digs a burrow first, three stairs down and a tunnel two high (:burrow); an enderman, 2.9
   tall, follows down the stairs and stops at the tunnel's mouth, two cells from the bot, where
   its reach (its box grown 0.83 sideways, never up or down) cannot touch the bot and the
   sword's can touch it (shelter?). From the burrow's mouth the bot provokes one with a stare,
   goes back to the end, and fights it there without a step (:fight :intent/hold?).

   An enderman is neutral (entity/neutral): it attacks only once looked in the face or hit. Its
   rules, read from the game's classes (EnderMan, EndermanLookForPlayerGoal):
   stared at it turns on the player; an angry one stared at within 4 blocks teleports away, and
   one farther than 16 teleports toward the player; hit by a living entity it never teleports
   (only a projectile or a non-living source moves it). So the stare is from range only, and the
   fight's look is combat/aim, its legs, never its head. Water hurts it and it teleports out of
   rain and water: the trap rows already keep the bot from fighting while it stands in water."
  (:require [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.rules :as rules]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(defn pearl-fallback
  "The pearl stage's fallback when every hunt row is nil, so a safe bot never idles into
   :nothing-to-do (42 of 141 real rod bots on 0ace52e): the go toward the nearest
   remembered sighting (chase), else the forest search or exit the bot already has
   (travel, explore), else a lookout. Never a planner wait with nothing pending."
  [world]
  (or (pmove/go-toward-sighting world)
      (pmove/travel world)
      (pmove/explore world)
      {:intent/kind :lookout :intent/for :pearls}))

(defn hunt
  "The hunt's next step with no kill to pick up, the first of these that applies (the rows of pearls-rules.edn are
   the order; a rule above is never overridden by one below). Survive: the blaze row's refuge near
   the spawner; down off a pillar; a meal; the fortress exit (pocket-bore, tunnel-answer, the
   sealed tunnel out) and cover from shooters; the walk home when a burrow is held and an
   enderman is coming, at hand or angry-near, or the bot is hurt (under danger-health) or
   swarmed. Fight: in the burrow, the one coming, one at hand and angry, or the threat, fought
   where the bot stands (:fight :intent/hold?); a calm one at hand only within the sword's reach;
   hurt outside the burrow, back off, cover, or eat; with an angry one near the mouth, a
   :lookout (a lure walk to the mouth after lure-lookouts), and the burrow never goes stale. Get
   to the quarry: travel to a warped forest, relocate, wander or break out of a cage; cut a
   burrow away from the quarry (:burrow, a pickaxe held) or walk to a site for one; provoke the
   quarry from the mouth, from within provoke-home-reach of the burrow's end, or from anywhere
   with no pickaxe ever held; never under provoke-health and never one already coming. With a
   burrow near and nothing provokable, a walk to a vantage within its reach, else a :lookout, and
   after stale-lookouts the burrow is given up (stale?). With no burrow: a walk to the nearest
   enderman a route reaches (to a stare-site when a pickaxe is held, never up to it), or toward
   one as far as a route goes (toward); else stairs down or up (descend, ascend), a tunnel (bore),
   a roam, the cuts out of a pocket (dig-out, climb-out), the chase of a remembered sighting, and
   last a :lookout. Digs spent on stairs or tunnels leave a burrow's (spare?). One fought out
   (fought-out?) is fought no more. A walk toward one never ends where a walk already arrived
   (memory/arrived)."
  [world]
  (rules/decide world))

(defn look-off
  "The look [yaw pitch], unless from the eye it stares at a calm enderman (not entity/creepy?;
   mob/stared?, the server's own cone and line test), then straight down at the same yaw."
  [world look]
  (let [eye (game/eye world)
        solid? (psense/sight-fn world)]
    (if (some (fn [[_ e]]
                (and (not (entity/creepy? e)) (mob/stared? solid? eye look (:entity/pos e))))
              (psense/endermen (avoided/forget world :blacklist) pparams/stare-range))
      [(first look) 90.0]
      look)))

(defn uncovered-log
  "The world with one log line naming the nearest shooter in sight, its position and the bot's, when a
   shooter is shooting (shot-at?) and cover-from finds no cover, every hundred ticks at most (five
   seconds). The world as it was otherwise."
  [world]
  (if (and (zero? (mod (:time/tick world 0) 100))
           (psense/shot-at? world)
           (nil? (pcover/cover-from world)))
    (let [[eid e] (entity/nearest (into {}
                                        (filter (fn [[_ e]]
                                                  (and (contains? pparams/ranged-kinds
                                                                  (entity/kind e))
                                                       (combat/seen? world e))))
                                        (psense/swarm world))
                                  (:player/pos world))]
      (if (and eid e)
        (game/say world
                  (str (name (entity/kind e))
                       " "
                       eid
                       " at "
                       (combat/cell (:entity/pos e))
                       " shoots the bot at "
                       (intent/feet world)
                       ": no cover"))
        world))
    world))

(defn track-gain
  "The world with :pearls/gain {:held pearls held :at ms the count last rose}: new on the first
   tick a hunt runs, moved on when the count rises, kept when it holds or falls (pearls spent)."
  [world]
  (let [now (:time/now world 0)
        n (psense/pearls-held world)
        g (:pearls/gain world)]
    (cond (or (nil? g) (> n (:held g))) (assoc world :pearls/gain {:held n :at now})
          (not= n (:held g)) (assoc-in world [:pearls/gain :held] n)
          :else world)))

(defn near-ids
  "The ids of the remembered endermen (blacklisted or not) within cluster-radius of pos."
  [world pos]
  (into #{}
        (keep (fn [[eid e]]
                (when (<= (physics/distance pos (:entity/pos e)) pparams/cluster-radius) eid)))
        (psense/endermen (avoided/forget world :blacklist) 128.0)))

(defn relocation
  "The world with the relocation (:pearls/relocate {:from feet :at ms :near ids}) begun when no
   pearl has been gained for relocate-ms, and ended, the burrow and the blacklist left behind and
   the clock started again, when the feet stand relocate-dist from where it began, or half that
   far and within cluster-radius of an enderman that was not near the start (arrived: the walk
   goes toward the densest, and the burrow is cut here, not beside them), or relocate-timeout-ms
   have passed: production as data, not the hunt's own signs of being stuck."
  [world]
  (let [now (:time/now world 0)
        feet (intent/feet world)
        r (:pearls/relocate world)
        g (:pearls/gain world)
        moved (when r (physics/distance feet (:from r)))]
    (cond (and r
               (or (>= moved pparams/relocate-dist)
                   (>= (- now (:at r)) pparams/relocate-timeout-ms)
                   (and (>= moved (/ pparams/relocate-dist 2))
                        (seq (remove (:near r) (near-ids world (:player/pos world)))))))
          (-> world
              (dissoc :pearls/relocate :pearls/shelter :pearls/mouth)
              (avoided/forget :blacklist)
              (assoc-in [:pearls/gain :at] now))
          (and (nil? r)
               g
               (>= (- now (:at g)) pparams/relocate-ms)
               (not (psense/angry-within? world pparams/hold-radius)))
          (assoc world
                 :pearls/relocate
                 {:from feet :at now :near (near-ids world (:player/pos world))})
          :else world)))

(defmethod plan/steer :pearls
  ;; out of the burrow's end, every look but a provoke's turned from a calm enderman's eyes
  ;; (look-off): a walk steers by :control/yaw and a dig or a place is judged by distance, so the
  ;; pitch is free. In the end a stare by chance is what the hunt wants: the enderman comes to the
  ;; sword
  [world _]
  (let [controls (:player/controls world)
        ;; a walk sets only :control/yaw: the look it leaves is that heading and the pitch the bot
        ;; had, which stares at an enderman straight ahead
        [pyaw ppitch] (:player/look world)
        look (or (:control/look controls)
                 (when (:control/yaw controls) [(:control/yaw controls) (or ppitch 0.0)])
                 (when pyaw [pyaw (or ppitch 0.0)]))
        home (:pearls/shelter world)
        ;; after the rods, the blaze row's steer goes on with the hunt's: it forgets a
        ;; pocket the hits come through (leaky-pocket?) and notes the hurt in it, which
        ;; stopped at the goal flip when only this steer ran
        world (if (fexit/pearls-stage? world)
                (plan/steer world (first (filter #(= :blaze-rods (:goal/id %)) plan/goals)))
                world)
        world (cond-> (pmove/track-distant (fexit/track-refuge
                                            (relocation (track-gain (psense/track-angry
                                                                     (uncovered-log
                                                                      (fexit/fortress-exit-log
                                                                       (fexit/fortress-exit-hold
                                                                        (pmove/watch-progress
                                                                         (pmove/reach-heading-hold
                                                                          (pmove/track-seen
                                                                           (psense/track-brutes
                                                                            world))))))))))))
                (psense/shot-at? world) (assoc :pearls/shot-at-ms (:time/now world 0))
                (fexit/sealed-full? world) (assoc :pearls/refuge-done true))]
    (cond (or (nil? look)
              (= :provoke (:intent/kind (:plan/intent world)))
              (and home (= home (intent/feet world))))
          world
          :else
          (let [off (look-off world look)]
            (if (and (nil? (:control/look controls)) (= off look))
              world
              (assoc-in world [:player/controls :control/look] off))))))

; intents a running hunt keeps with an angry enderman at hand: the answers to it, a meal, a fire put out
(defn out-walk?
  "Is intent i a walk the hunt takes out of its burrow, to anywhere but the mouth (the lure's
   walk, which an angry enderman near the mouth is what it is for) and not the walk home?"
  [world i]
  (and (= :walk (:intent/kind i))
       (not= (:intent/target i) (:pearls/shelter world))
       (not= (:intent/target i) (:pearls/mouth world))))

(defn same-choice?
  "Are running intent i and the intent again the hunt would choose now of one kind and target? Then
   cutting i only starts it again."
  [i again]
  (= (select-keys i [:intent/kind :intent/target])
     (select-keys again [:intent/kind :intent/target])))

(defn held-idle?
  "Is intent i a held :fight (hold?) that has run hold-patience-ms or more with its target gone
   or past strike-range of the feet?"
  [world i]
  (boolean (and (= :fight (:intent/kind i))
                (:intent/hold? i)
                (:intent/since i)
                (> (- (:time/now world 0) (:intent/since i)) pparams/hold-patience-ms)
                (let [e (get-in world [:world/entities (:intent/target i)])]
                  (or (nil? e)
                      (> (physics/distance (:player/pos world) (:entity/pos e))
                         pparams/strike-range))))))

(defmethod intent/still-wanted? :hunt/fight-from-burrow [world i] (not (held-idle? world i)))

(defmethod intent/still-wanted? :hunt/fight-coming-held [world i] (not (held-idle? world i)))

(defn target-avoided?
  "Is the target of running intent i one the hunt has given up (avoided :blacklist: a failure, or
   pmove/watch-progress for want of progress)? Then the intent is stale."
  [world i]
  (boolean (and (:intent/target i) (avoided/noted? world :blacklist (:intent/target i)))))

(defmethod intent/still-wanted? :hunt/relocate
  ;; the relocation walk ends when the relocation does (arrived, far enough, or timed out), or its
  ;; target is given up for want of progress
  [world i]
  (and (some? (:pearls/relocate world)) (not (target-avoided? world i))))

(defmethod intent/still-wanted? :hunt/escape-tunnel-out [world i] (not (target-avoided? world i)))

(defmethod intent/still-wanted? :hunt/travel [world i] (not (target-avoided? world i)))

(defmethod intent/still-wanted? :hunt/heading [world i] (not (target-avoided? world i)))

(defmethod plan/held-goal? :pearls
  ;; with an angry enderman within hold-radius and a burrow held, the hunt waits inside: the stuck
  ;; watchdog counts this as held, not stuck, and never forces an explore walk out of the burrow
  [world _]
  (boolean (and (pplace/shelter world) (psense/angry-within? world pparams/hold-radius))))

(defmethod plan/interrupts? :pearls
  ;; the trigger x exemption matrix (see plan/interrupts? for why it is not ranks). Triggers: a
  ;; blaze refuge to take, an angry enderman at hand (beset?) or near the mouth for a walk out
  ;; (out-walk?, angry-near), the bot struck, a tunnel answer, a shot, a threat over a lookout.
  ;; Exemptions: what answers (answers-to-hand), a wall, the walk home, the burrow's end, an eat or
  ;; fight for the refuge, and a replacement that is the same choice or a hollow pocket. The two
  ;; stale cases, a held fight gone idle and a relocation walk after the relocation, are
  ;; still-wanted? methods on their rule ids. A lone foe at hand (pcover/lone-fight) cuts
  ;; anything but a fight, cover and digs too, when the hunt would fight it.
  [world goal i]
  (and (or (and (not= :fight (:intent/kind i))
                (some? (pcover/lone-fight world))
                (not (same-choice? i (plan/next-intent world goal plan/goals))))
           (and (fexit/blaze-refuge world)
                (not (contains? #{:eat :fight} (:intent/kind i)))
                (not (same-choice? i (fexit/blaze-refuge world))))
           (and (not (contains? pparams/answers-to-hand (:intent/kind i)))
                (not (:intent/wall? i))
                (not (and (= :walk (:intent/kind i))
                          (= (:intent/target i) (:pearls/shelter world))))
                (not= (:pearls/shelter world) (intent/feet world))
                (or (psense/beset? world)
                    (and (out-walk? world i)
                         (some? (pplace/shelter world))
                         (or (some? (psense/angry-near world))
                             (psense/angry-within? world pparams/hold-radius)))
                    (some? (first (psense/struck-by world)))
                    (some? (fexit/tunnel-answer world))
                    (psense/shot-at? world)
                    (and (= :lookout (:intent/kind i)) (some? (first (psense/threat world)))))
                (not (same-choice? i (plan/next-intent world goal plan/goals)))))
       (not (blaze/hollow-replacement? world goal i))))

(defmethod plan/fire-cover :pearls
  ;; a ghast or blaze in sight, or one that was within shot-hold-ms (recently-shot?): the cover
  ;; from it, never a retreat walk in the open.
  [world _]
  (when (or (psense/shot-at? world) (psense/recently-shot? world)) (pcover/cover-from world)))

(defmethod combat/answers :pearls
  ;; the hunt provokes endermen and fights them from its burrow; an angry one (entity/creepy?)
  ;; is a hostile the :defend row would otherwise walk out of the burrow after
  [_]
  #{:enderman})

(defmethod combat/answering :pearls
  ;; with a burrow near (shelter), every hostile tracked but the shunned kinds is the hunt's too
  ;; (threat): met from the burrow, where the :defend row would fight it in the open. With none,
  ;; the endermen and every hostile that is not weak? (a piglin, a hoglin): the hunt takes cover
  ;; from those (swarmed?) and the :defend row does not fight them in the open
  [world _]
  (cond-> (into (combat/answers :pearls)
                (if (fexit/pearls-stage? world) (remove pparams/shunned pparams/heavy-kinds) #{}))
    (pplace/shelter world)
    (into (comp (map (comp entity/kind val)) (remove pparams/shunned)) (entity/hostiles world))))

(defmethod combat/claims-attacker? :pearls
  ;; an enderman that hit the bot, with a burrow near and a way to it (or the bot in it), is the
  ;; hunt's: its first branch walks home. Struck by a heavy hostile (blaze, ghast, wither skeleton,
  ;; piglin) during the pearls stage, the hunt claims it so the :defend row does not fight it with a
  ;; sword in the open.
  [world _ e]
  (or (and (= :enderman (entity/kind e))
           (some? (pplace/shelter world))
           (or (= (pplace/shelter world) (intent/feet world))
               (intent/routable?
                world
                {:intent/kind :walk :intent/target (pplace/shelter world) :intent/for :shelter})))
      (and (fexit/pearls-stage? world) (contains? pparams/heavy-kinds (entity/kind e)))))

(defmethod plan/next-intent :pearls
  ;; the rows of pearls-rules.edn in order: a fire put out, then the hunt's survival and threat
  ;; rows, the kill's drop (picked up, or its body waited over), the sword and the pickaxe made
  ;; again and the slots kept free (not while hurt), then the rest of the hunt. A hurt bot gets
  ;; the rows' answer as it stands; otherwise nil from them falls to pearl-fallback.
  [world goal table]
  (let [i (rules/decide-all world goal table)]
    (cond (< (:player/health world 20.0) pparams/danger-health) i
          :else (or i (pearl-fallback world)))))
