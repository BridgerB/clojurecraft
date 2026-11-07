(ns clojurecraft.pearls
  "Ender pearls by hunting endermen (issue #263), never by bartering: bartering takes a stack of
   gold the run does not have. The game's loot table (data/minecraft/loot_table/entities/
   enderman.json, probe run 38889277228) gives one roll of ender_pearl, its count uniform 0 to 1
   (plus 0 to 1 a level of looting): a kill drops a pearl one time in two, so twelve pearls are
   about twenty-four kills. A warped forest spawns endermen and nothing else hostile (its biome
   file's monster spawner, the same probe), so that is where the Nether leg hunts.

   Requiring this namespace registers the :pearls plan and the :provoke and :burrow intents. A
   row planned by :pearls (the :ender-pearls stage, the gym's :ender-pearl trial) takes, in order:
   the pearl of a kill just made (picked up by #262's :collect-drops, which reads what a drop
   holds, so a carried block's drop is left); the drop still to come while the body lies there (a
   wait); then the fight, from a burrow (hunt). An enderman hits for 7 a second on Normal, 5.74
   through iron, against forty health a sword takes seven hits to spend: in the open the bot
   loses (gym batch pearls-263a, plan run 38892130785: five live trials, five deaths). So the
   hunt digs a burrow first, three stairs down and a tunnel two high (:burrow); an enderman, 2.9
   tall, follows down the stairs and stops at the tunnel's mouth, two cells from the bot, where
   its reach (its box grown 0.83 sideways, never up or down) cannot touch the bot and the
   sword's can touch it (shelter?). From the burrow's mouth the bot provokes one with a stare,
   goes back to the end, and fights it there without a step (:fight :intent/hold?).

   An enderman is neutral (entity/neutral): it attacks only once looked in the face or hit. Its
   rules, read from the game's classes (EnderMan, EndermanLookForPlayerGoal, the same probe):
   stared at it turns on the player; an angry one stared at within 4 blocks teleports away, and
   one farther than 16 teleports toward the player; hit by a living entity it never teleports
   (only a projectile or a non-living source moves it). So the stare is from range only, and the
   fight's look is combat/aim, its legs, never its head. Water hurts it and it teleports out of
   rain and water: the trap rows already keep the bot from fighting while it stands in water."
  (:require [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.forage :as forage]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.sword :as sword]
            [clojurecraft.window :as window]))

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
  "The hunt's next step with no kill to pick up, in order: out of the burrow (shelter) with one
   coming (creepy), one at hand (within strike-range, blacklisted or not: one a held fight gave
   up on is still angry, and batch pearls-warped-b's bots, plan run 39135767198, walked out to
   it and were slain), under 10 health, or with another hostile within the :defend row's reach
   (threat: batch pearls-warped4-a's runs 3 and 4 were slain by mobs that row fought in the
   open at the forest's edge with a burrow cut), back to it (gym batch pearls-263b: a bot
   that met one in the open, or backed away hurt, died) when a route reaches it, else on as
   with no burrow, so that one coming at hand is fought (batch pearls-warped8-b, plan run
   39775345028, probe 39848848462: run 3's walk home from its first stair failed :no-path and
   it stood in :lookout while the enderman beside it killed it in three seconds); in it, the one coming, one at hand
   and angry (not one a held fight gave up on, blacklisted, unless within the sword's reach: sim probe 39890029962, worlds R1 and R4 seed 3, held three fights in a row on one by the mouth that never came, each timing out :unreachable, and the plan failed), or the threat fought where the bot stands (:fight :intent/hold?; at hand by the
   distance in three, never on the plane alone: batch pearls-warped-b's run 3 held a fight
   twenty minutes on one two blocks off on the plane and fourteen below, and batch
   pearls-warped-c's run 1 stood over one fourteen below for forty; a calm one at hand only
   within the sword's reach, combat/attack-reach, where the blow angers it, never one out of
   it, which does not come: batch pearls-warped4-b's arena run 3, plan run 39596287510, held
   one 3.8 off for 107 s from 21 s on, blacklisted it, and found the others gone); in the
   burrow with one standing in its stairs out of reach (blocking), a :lookout where it is,
   never a walk out through its body (batch pearls-warped4-a's run 2 failed :no-path three
   times to its mouth), and one given up on stood over for stale-lookouts (blocked-out?)
   gives the burrow up and the way out is cut up the other way from its end (cut-out: sim
   probe 40102602895's world R3 stood so from minute 26 to 60); with an angry one near the mouth, blacklisted or not (angry-near), in
   the burrow a :lookout and out of it the walk home, and the burrow never goes stale (batch
   pearls-warped5-a's run 4 gave its burrow up with one standing seven blocks off and was
   slain walking out); with no burrow, one at hand backed away
   from under 10 health, fought when it is coming or no pickaxe was ever held (worn-out?: with one spent on burrows, none struck) (a calm one given up on,
   a ghost, is left) (batch
   pearls-warped-a, plan run 39131478416: two landings in three walked up to a calm one, struck
   it in the open and died); hurt, a meal when the food is under hunger/heal-level (blaze/meal:
   health comes back only from there), and in the burrow a :lookout where it is, never out to
   the mouth (the same batch's run 3 went from the burrow's end to its mouth and back, hurt,
   for twelve minutes), and with no burrow and none coming a :lookout too, never a walk or a
   roam in the open (batch pearls-warped7-a, plan run 39714009099, probe 39723064504: run 5
   roamed the floor at 7 health with its burrow given up and no enderman within 32, and was
   slain at 36 minutes); in the burrow with an angry one near the mouth (angry-near) a
   :lookout, and after lure-lookouts of them since the last walk (lure?) a walk to the mouth to
   bring it, the angry one sending the bot straight back in from outside (batch
   pearls-warped7-a's run 2 stood thirty-two minutes over two that never came); no burrow near
   and one to provoke, coming or at hand, one dug there
   (:burrow, a pickaxe held) away from it, so the stare goes from its mouth, when one cut from
   the feet would end in a shelter (burrowable?), else a walk to the nearest cell one would
   (burrow-site; batch pearls-warped-e, plan run 39164914828, run 1: on broken ground the
   burrow failed :no-shelter six times where the bot stood, and it then provoked with no
   shelter and was slain); in the burrow, one
   provokable from where it stands provoked from there, and with one at the mouth (crowded?,
   blacklisted or not), a walk to a vantage within the burrow's reach from which it can be
   stared at (vantage), else a :lookout where it is; no provoke under provoke-health (in the
   burrow a :lookout, out of it the walk home, a meal first when the food will not heal), and one
   from a vantage away from the mouth only at full health; in the burrow with none coming, out to its mouth
   to look round (an angry one given up on that stands off the mouth never comes: batch
   pearls-warped-d, plan run 39160760074, run 1 took three pearls in six minutes, then stood in
   its burrow thirty-four over such ones); one not yet coming and provokable? from here within
   stare-range (quarry), provoked from the burrow's mouth, from anywhere within the burrow's
   reach with a route home (intent/routable?), or from anywhere with no pickaxe ever held to dig one
   (one worn out on burrows: next-intent waits :no-pickaxe instead; never one already coming: a provoke of it ends at once, and batch pearls-263b's bot, its
   pickaxe lost, provoked one 26 times in a row; with a burrow but no route home, a walk to the
   mouth first: the same run 1 provoked fifteen blocks from its burrow, and the walk home found
   no path); one coming and no burrow to meet it in, fought where the bot stands while a
   pickaxe is held (:fight :intent/hold?, as in the burrow: it teleports or rushes to ground
   the bot already stands on, and one that never comes times out :unreachable and is given
   up on; gym batch pearls-m10-a, plan run 40106206590, run 3 charged one across the open
   and fell into lava on the way, and a :lookout there instead stood the hunt still for as
   long as any angry one was tracked within stare-range, never given up on), charged only
   with no pickaxe ever held, as the arena row fights; with a burrow near and none
   provokable from here, a walk to a vantage within the burrow's reach from which one is
   (vantage), else a :lookout where it stands, and after stale-lookouts of them the burrow is
   given up (stale?): the hunt never walks out of its burrow's reach after an enderman (batch
   pearls-warped4-b's arena runs, plan run 39596287510, walked 16 and 55 blocks to ones hidden
   from the mouth, and run 2 met the dark's zombies, skeleton and creeper there with its burrow
   out of reach); given up over a calm enderman crowding its mouth (crowder), a walk to a cell a
   new burrow cut from would stare at it from its mouth (crowd-site), before any burrow cut
   where the bot stands: the one cut from the old end, or beside the crowder after the walk
   toward it, was the old burrow again (sim probe 39883402083); with no burrow, a walk to the nearest within seek-radius a
   route reaches (to a cell a burrow cut from would stare at it, stare-site, when a pickaxe is held and there is one: never up to it, where the burrow beside it provokes it point blank), or toward one as far as a route goes (toward); else a :lookout, standing where
   it is: no enderman yet is no failure (batch pearls-263c's warped forest landings failed
   :no-enderman and :no-route in 40 s); before the walk toward one, stairs down toward one far
   below within descend-radius (descend: batch pearls-warped-c's landings stood on ledges over
   the forest floor, and batch pearls-warped-d's run 2 walked toward one and another, the stairs
   down and up, between lookouts for forty minutes with none within seek-radius for six), and
   stairs up toward one as far above (ascend: batch pearls-warped5-b's run 5 descended to three
   endermen in a cave under the forest floor, killed them, and stood thirty-two minutes under
   the floor with every enderman 20 to 30 blocks over it; runs 2, 3 and 4 hunted under the
   floor the same way); after
   the walk toward, a tunnel toward the nearest on this level no route reaches (bore: the foot
   of a descent is inside the rock); after a lookout that found nothing, a roam somewhere else to
   look from, and with no end for a roam either, a way cut out of the pocket toward the nearest
   enderman (dig-out: batch pearls-warped8-a's runs 2 and 3 stood four and a half hours walled
   in, one in its own diggings, one on a ledge over a pit). The stairs down and the tunnel toward one are cut only with digs to spare past a
   burrow's (spare?): the burrow is what the pickaxe is for, and batch pearls-warped3-a's run 1
   wore its iron pickaxe out on them at fourteen minutes and had no burrow after. A walk toward
   one never ends where a walk already arrived (memory/arrived): the same batch's run 3 walked
   between two such ends for twenty-five minutes. One fight-tries fights have ended on without
   a kill is fought no more (fought-out?), in the burrow or out of it, and holds no burrow: gym
   batch pearls-w3 (plan run 40434266158) run 3 planned some sixty-four thousand fights on one
   enderman over five hours and took one pearl. With no end for a roam and no way cut toward
   one either, and a minute of lookouts since the last walk (stuck?), the hunt climbs out toward
   the floor (climb-out): the same batch's run 2 stood in lookouts its last four hours in a cave
   under the floor, five pearls held."
  [world]
  (let [pos (:player/pos world)
        [eid e] (psense/at-hand world)
        [ceid ce] (entity/nearest (into {}
                                        (filter (fn [[eid e]]
                                                  (and (entity/creepy? e)
                                                       (not (and
                                                             (psense/lured-out? world)
                                                             (psense/unfightable? world eid e))))))
                                        (psense/endermen world pparams/stare-range))
                                  pos)
        near? (and e (<= (physics/distance pos (:entity/pos e)) pparams/strike-range))
        angry? (and near? (entity/creepy? e) (not (contains? (:plan/blacklist world #{}) eid)))
        in-reach? (and near?
                       (<= (physics/distance (game/eye world) (combat/aim e)) combat/attack-reach))
        hurt? (< (:player/health world 20.0) pparams/danger-health)
        ;; an angry one at hand, given up on or not: no walk out in the open (angry-tracked?)
        tracked? (psense/angry-tracked? world pparams/hold-radius)
        rested? (>= (:player/health world 20.0) (psense/provoke-line world))
        meal (when-not rested? (blaze/meal world))
        home (pplace/shelter world)
        in? (and home (= home (intent/feet world)))
        at-end? (= (:pearls/shelter world) (intent/feet world))
        [tid te] (psense/threat world)
        [sid _] (psense/struck-by world)
        swarmed? (psense/swarmed? world)
        cornered? (and near? eid (contains? (:plan/cornered world #{}) eid))
        [bid _] (when at-end? (psense/blocking world))
        [aid _] (when home (psense/angry-near world))
        [quarry-id q] (psense/quarry world)
        ;; one enderman at a time: none is provoked while an angry one is coming (churn-a run 3,
        ;; issue #263: 1310, 1561 and 1691 provoked in 14 s, three at the bot at once)
        coming? (psense/coming? world)
        qid (when-not coming? quarry-id)
        ghost? (and near? (not angry?) (contains? (:plan/blacklist world #{}) eid))
        m (second (psense/menace world))
        dir (when (and (nil? home)
                       (pupkeep/pickaxe? world)
                       (or ce q m (and near? (not ghost?)) (and e (psense/at-site? world))))
              (pplace/away world (or q ce m e)))
        digs? (and dir (pplace/burrowable? world (intent/feet world) dir))
        site (when (and dir (not digs?)) (pplace/burrow-site world dir))
        csite (when (and (nil? home)
                         (pupkeep/pickaxe? world)
                         (not (psense/at-site? world))
                         (psense/stale? world)
                         (nil? m))
                (pplace/crowd-site world))
        mouth (:pearls/mouth world)
        way-home {:intent/kind :walk :intent/target home :intent/for :shelter}
        mouth-walk (delay (pmove/shelter-walk world mouth))
        travel-walk (delay (when (and (not hurt?)
                                      (not tracked?)
                                      (not swarmed?)
                                      (not (psense/recently-shot? world))
                                      (not (psense/menaced? world))
                                      (empty? (psense/endermen world pparams/seek-radius)))
                             (pmove/travel world)))
        relocate-now (delay
                      (when (and (:pearls/relocate world)
                                 (not hurt?)
                                 (not swarmed?)
                                 (not (psense/recently-shot? world))
                                 (not (and home (psense/angry-within? world pparams/hold-radius)))
                                 (not (if (< (- (:time/now world 0) (:at (:pearls/relocate world)))
                                             pparams/relocate-patience-ms)
                                        (psense/menaced? world)
                                        (psense/live-menaced? world))))
                        (pmove/relocate-walk world)))
        wander-walk (delay (when (and (not hurt?)
                                      (not tracked?)
                                      (not swarmed?)
                                      (not (and home
                                                (psense/angry-within? world pparams/hold-radius)))
                                      (not (psense/live-menaced? world))
                                      (not (psense/recently-shot? world))
                                      (pmove/wandering? world))
                             (pmove/wander world)))
        cage-break (delay
                    (when (and (not hurt?)
                               (not tracked?)
                               (not swarmed?)
                               (not (psense/live-menaced? world))
                               (not (psense/recently-shot? world))
                               (or (pmove/wandering? world) (pmove/travel-caged? world)))
                      (if (= dimension/nether (dimension/of world))
                        (or (pmove/dig-out world)
                            (pmove/tunnel-out world)
                            (pmove/climb-out world)
                            (pmove/uncage world))
                        (or (pmove/climb-out world) (pmove/dig-out world) (pmove/uncage world)))))
        csite-walk (delay (pmove/shelter-walk world csite))
        site-walk (delay (pmove/shelter-walk world site))
        overworld-death? (and (pos? (:stats/deaths world 0))
                              (= dimension/overworld (dimension/of world)))
        far (psense/endermen world pparams/seek-radius)
        fight-id (cond (and ce (not (psense/unfightable? world ceid ce))) ceid
                       (and (or angry? in-reach?) (not (psense/unfightable? world eid e))) eid
                       :else tid)]
    (cond
      (fexit/blaze-refuge world) (intent/why :hunt/blaze-refuge-world (fexit/blaze-refuge world))
      ;; off a pillar first: nothing else starts from one (perched?)
      (and (not hurt?) (pplace/come-down world))
      (intent/why :hunt/and-not-hurt?-come-down-world (pplace/come-down world))
      (and meal
           (or (empty? (filter #(combat/seen? world (val %)) (psense/shooters world)))
               (and (fexit/fortress-escaping? world) (not (psense/shot-at? world)))))
      (intent/why :hunt/and-meal-or-empty?-filter-combat-seen?-world-val meal)
      (fexit/pocket-bore world) (intent/why :hunt/pocket-bore-world (fexit/pocket-bore world))
      (fexit/tunnel-answer world) (intent/why :hunt/tunnel-answer (fexit/tunnel-answer world))
      (and (fexit/fortress-escaping? world)
           (fexit/can-tunnel-out? world)
           (psense/shot-at? world)
           (pcover/cover-from world))
      (intent/why :hunt/and-fortress-escaping?-world-can-tunnel-out?-wor (pcover/cover-from world))
      (and (fexit/fortress-escaping? world) (fexit/can-tunnel-out? world))
      (intent/why :hunt/and-fortress-escaping?-world-can-tunnel-out?-wor-2
                  (fexit/fortress-tunnel-out world))
      (and (not in?) (pcover/leave-sight world))
      (intent/why :hunt/and-not-in?-leave-sight-world (pcover/leave-sight world))
      (and (psense/shot-at? world) (not in?) (pcover/cover-from world))
      (intent/why :hunt/and-shot-at?-world-not-in?-cover-from-world (pcover/cover-from world))
      (and (not in?) (fexit/room-exit world))
      (intent/why :hunt/and-not-in?-room-exit-world (fexit/room-exit world))
      (and home
           (not in?)
           (or ce near? hurt? tid aid swarmed? (psense/angry-within? world pparams/hold-radius))
           (intent/routable? world way-home))
      (intent/why :hunt/and-home-not-in?-or-ce-near?-hurt?-tid-aid-swarm way-home)
      (and swarmed? (not in?) (nil? home) (pcover/cover-from world))
      (intent/why :hunt/and-swarmed?-not-in?-nil?-home-cover-from-world (pcover/cover-from world))
      (and swarmed? in?)
      (intent/why :hunt/and-swarmed?-in?
                  (or (when mouth (pcover/wall-toward world (physics/centre mouth) [0 1]))
                      {:intent/kind :lookout :intent/for :pearls}))
      (and hurt? in?)
      (intent/why :hunt/and-hurt?-in? (or meal {:intent/kind :lookout :intent/for :pearls}))
      (and cornered? hurt? (not in?) (or meal (pcover/wall-off world e)))
      (intent/why :hunt/and-cornered?-hurt?-not-in?-or-meal-wall-off-wor
                  (or meal (pcover/wall-off world e)))
      (and near? hurt? (not in?))
      (intent/why :hunt/and-near?-hurt?-not-in?
                  {:intent/kind :back-off :intent/target eid :intent/for :pearls})
      (and hurt? (not in?) tid (combat/back-off-pointless? world te) (pcover/cover-from world))
      (intent/why :hunt/and-hurt?-not-in?-tid-combat-back-off-pointless? (pcover/cover-from world))
      (and hurt? (not in?) tid)
      (intent/why :hunt/and-hurt?-not-in?-tid
                  {:intent/kind :back-off :intent/target tid :intent/for :pearls})
      (and hurt? (not in?))
      (intent/why :hunt/and-hurt?-not-in? (or meal {:intent/kind :lookout :intent/for :pearls}))
      (and in? fight-id)
      (intent/why :hunt/and-in?-fight-id
                  {:intent/kind :fight
                   :intent/target fight-id
                   :intent/hold? true
                   :intent/yield? true
                   :intent/for :pearls})
      (and sid (not hurt?) (not in?))
      (intent/why :hunt/and-sid-not-hurt?-not-in?
                  {:intent/kind :fight :intent/target sid :intent/for :pearls})
      (and tid (not hurt?) (not in?) (nil? home) (not swarmed?) (psense/weak? te))
      (intent/why :hunt/and-tid-not-hurt?-not-in?-nil?-home-not-swarmed?
                  {:intent/kind :fight :intent/target tid :intent/for :pearls})
      (and at-end? bid (psense/blocked-out? world))
      (intent/why :hunt/and-at-end?-bid-blocked-out?-world
                  (or (pmove/cut-out world) {:intent/kind :lookout :intent/for :pearls}))
      (and near?
           (not in?)
           (not (psense/unfightable? world eid e))
           (or (entity/creepy? e)
               (and (not (pupkeep/pickaxe? world))
                    (not (pupkeep/worn-out? world))
                    (not (contains? (:plan/blacklist world #{}) eid)))))
      (intent/why :hunt/and-near?-not-in?-not-unfightable?-world-eid-e-o
                  {:intent/kind :fight :intent/target eid :intent/for :pearls})
      meal (intent/why :hunt/meal meal)
      (or hurt? (and in? bid))
      (intent/why :hunt/or-hurt?-and-in?-bid {:intent/kind :lookout :intent/for :pearls})
      (and in? aid (not (psense/lured-out? world)) (psense/lure? world) @mouth-walk)
      (intent/why :hunt/and-in?-aid-not-lured-out?-world-lure?-world-mou @mouth-walk)
      (and in? (or aid (psense/angry-within? world pparams/hold-radius)))
      (intent/why :hunt/and-in?-or-aid-angry-within?-world-hold-radius
                  {:intent/kind :lookout :intent/for :pearls})
      @travel-walk (intent/why :hunt/travel-walk @travel-walk)
      @relocate-now (intent/why :hunt/relocate-now @relocate-now)
      @wander-walk (intent/why :hunt/wander-walk @wander-walk)
      @cage-break (intent/why :hunt/cage-break @cage-break)
      (and csite @csite-walk) (intent/why :hunt/and-csite-csite-walk @csite-walk)
      digs? (intent/why :hunt/digs? {:intent/kind :burrow :intent/dir dir :intent/for :pearls})
      (and site @site-walk) (intent/why :hunt/and-site-site-walk @site-walk)
      (and in? qid rested?)
      (intent/why :hunt/and-in?-qid-rested?
                  {:intent/kind :provoke :intent/target qid :intent/for :pearls})
      (and in? qid) (intent/why :hunt/and-in?-qid {:intent/kind :lookout :intent/for :pearls})
      (and in? (psense/crowded? world))
      (intent/why :hunt/and-in?-crowded?-world
                  (or (pplace/vantage world far) {:intent/kind :lookout :intent/for :pearls}))
      (and in? mouth (not swarmed?) (pcover/unseal world mouth))
      (intent/why :hunt/and-in?-mouth-not-swarmed?-unseal-world-mouth (pcover/unseal world mouth))
      (and in? mouth (nil? ce) (empty? (psense/swarm world (physics/centre mouth))) @mouth-walk)
      (intent/why :hunt/and-in?-mouth-nil?-ce-empty?-swarm-world-physics @mouth-walk)
      (and qid
           rested?
           (or (and (not (pupkeep/pickaxe? world)) (not (pupkeep/worn-out? world)))
               (= mouth (intent/feet world))
               (and home
                    (<= (physics/distance pos (physics/centre home)) pparams/provoke-home-reach))))
      (intent/why :hunt/and-qid-rested?-or-and-not-pickaxe?-world-not-wo
                  {:intent/kind :provoke :intent/target qid :intent/for :pearls})
      (and qid home rested? @mouth-walk)
      (intent/why :hunt/and-qid-home-rested?-mouth-walk @mouth-walk)
      (and qid home (intent/routable? world way-home))
      (intent/why :hunt/and-qid-home-intent-routable?-world-way-home way-home)
      (and (not hurt?) ce (not (psense/unfightable? world ceid ce)) (pupkeep/pickaxe? world))
      (intent/why :hunt/and-not-hurt?-ce-not-unfightable?-world-ceid-ce
                  {:intent/kind :fight
                   :intent/target ceid
                   :intent/hold? true
                   :intent/yield? true
                   :intent/for :pearls})
      (and (not hurt?) ce (not (psense/unfightable? world ceid ce)))
      (intent/why :hunt/and-not-hurt?-ce-not-unfightable?-world-ceid-ce-2
                  {:intent/kind :fight :intent/target ceid :intent/for :pearls})
      home
      (intent/why :hunt/home
                  (or (pplace/vantage world far)
                      (when (pmove/wandering? world) (pmove/wander world))
                      @cage-break
                      {:intent/kind :lookout :intent/for :pearls}))
      ;; no walk out to a new site, a route or a sighting while an angry one is at hand, however
      ;; the hunt has given it up: it strikes the bot in the open (angry-tracked?)
      (psense/angry-tracked? world pparams/hold-radius)
      (intent/why :hunt/angry-tracked?-world-hold-radius
                  {:intent/kind :lookout :intent/for :pearls})
      :else
      (intent/why
       :hunt/else
       (or (if (pupkeep/pickaxe? world)
             (some (fn [[_ e]]
                     (when-let [c (pplace/stare-site world e)]
                       {:intent/kind :walk :intent/target c :intent/for :shelter}))
                   (->>
                     far
                     (remove (fn [[eid e]]
                               (and (psense/lured-out? world) (psense/unfightable? world eid e))))
                     (sort-by (fn [[_ e]] (physics/distance (:player/pos world) (:entity/pos e))))
                     (take pparams/candidates)))
             (when-not (pupkeep/worn-out? world)
               (when-let [[_ e] (pmove/routable world far)]
                 {:intent/kind :walk
                  :intent/target (combat/cell (:entity/pos e))
                  :intent/for :pearls})))
           (when-not overworld-death?
             (pmove/descend world (psense/endermen world pparams/descend-radius)))
           (pmove/ascend world (psense/endermen world pparams/descend-radius))
           (pmove/toward world far)
           (when-not overworld-death?
             (pmove/bore world (psense/endermen world pparams/descend-radius)))
           (when (= :lookout (:intent/kind (:plan/last world)))
             (or (pmove/roam world)
                 (when (pmove/stuck? world) (pmove/climb-out world))
                 (when-not overworld-death? (pmove/dig-out world))
                 (when (pmove/stuck? world) (pmove/uncage world))))
           (when (pmove/wandering? world) (pmove/wander world))
           (pmove/go-toward-sighting world)
           {:intent/kind :lookout :intent/for :pearls})))))

(defn look-off
  "The look [yaw pitch], unless from the eye it stares at a calm enderman (not entity/creepy?;
   mob/stared?, the server's own cone and line test), then straight down at the same yaw."
  [world look]
  (let [eye (game/eye world)
        solid? (psense/sight-fn world)]
    (if (some (fn [[_ e]]
                (and (not (entity/creepy? e)) (mob/stared? solid? eye look (:entity/pos e))))
              (psense/endermen (dissoc world :plan/blacklist) pparams/stare-range))
      [(first look) 90.0]
      look)))

(defn uncovered-log
  "The world with one log line naming the nearest shooter in sight, its position and the bot's, when a
   shooter is shooting (shot-at?) and cover-from finds no cover, every hundred ticks at most (five
   seconds): the open-ground case of issue #263 shows in the audit and can be counted before a
   walk to cover is built. The world as it was otherwise."
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
        (psense/endermen (dissoc world :plan/blacklist) 128.0)))

(defn relocation
  "The world with the relocation (:pearls/relocate {:from feet :at ms :near ids}) begun when no
   pearl has been gained for relocate-ms, and ended, the burrow and the blacklist left behind and
   the clock started again, when the feet stand relocate-dist from where it began, or half that
   far and within cluster-radius of an enderman that was not near the start (arrived: the walk
   goes toward the densest, and the burrow is cut here, not beside them), or relocate-timeout-ms
   have passed: production as data, not the hunt's own signs of being stuck. A hunt that dithers
   beside endermen it cannot provoke counts none of the lookouts or walks wandering? asks for
   (pearl-fight-e job 4: 943 lookouts, 273 walks, one pearl in 102 minutes). Pearl-fight-g job 4
   (plan run 41166403154) walked the whole way to the cluster, 15 blocks from a calm one, and
   was slain in the open by it."
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
              (dissoc :pearls/relocate :pearls/shelter :pearls/mouth :plan/blacklist)
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
  ;; pitch is free. Sim probes 39887735653 and 39887752302 (seeds 4 and 5): four bots stared at
  ;; one two or three blocks off on a walk or a tunnel, out of the burrow, and were slain in the
  ;; open. In the end a stare by chance is what the hunt wants: the enderman comes to the sword
  [world _]
  (let [controls (:player/controls world)
        ;; a walk sets only :control/yaw: the look it leaves is that heading and the pitch the bot
        ;; had, which stares at an enderman straight ahead. Pearl-fight-p jobs 2 and 3 (plan run
        ;; 41256381552, probe 41268180994): 20 of 22 enderman angers came on a walk with the look in
        ;; the stare cone 8 to 13 ticks before, at 5 to 47 blocks
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
                                                                        world))))))))
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
   cutting i only starts it again: gym plan run 40860669235 job 5 (issue #263) planned the same
   :walk every 50 ms for three seconds under a ghast's fire, each plan cutting the last, and never
   moved."
  [i again]
  (= (select-keys i [:intent/kind :intent/target])
     (select-keys again [:intent/kind :intent/target])))

(defn held-idle?
  "Is intent i a held :fight (hold?) that has run hold-patience-ms or more with its target gone
   or past strike-range of the feet? Runs 4 and 5 of gym plan run 40825468783 (issue #263,
   pearls-12) held a fight a full minute (combat/hold-timeout-ms) on an enderman that did not
   come, and failed :unreachable."
  [world i]
  (boolean (and (= :fight (:intent/kind i))
                (:intent/hold? i)
                (:intent/since i)
                (> (- (:time/now world 0) (:intent/since i)) pparams/hold-patience-ms)
                (let [e (get-in world [:world/entities (:intent/target i)])]
                  (or (nil? e)
                      (> (physics/distance (:player/pos world) (:entity/pos e))
                         pparams/strike-range))))))

(defmethod plan/held-goal? :pearls
  ;; with an angry enderman within hold-radius and a burrow held, the hunt waits inside: the stuck
  ;; watchdog counts this as held, not stuck, and never forces an explore walk out of the burrow
  [world _]
  (boolean (and (pplace/shelter world) (psense/angry-within? world pparams/hold-radius))))

(defmethod plan/interrupts? :pearls
  ;; any running intent but an answer (answers-to-hand) or the walk home ends with an angry
  ;; enderman at hand (beset?), and the hunt plans again: home, the fight held there, or the
  ;; fight where the bot stands. Nothing else ends a running intent: sim probes 39887735653 and
  ;; 39887752302 had a walk and a tunnel go on while one struck the bot from 20 health to none in
  ;; six to ten seconds, the bot carried on away from it. Never in the burrow's end, where none
  ;; reaches: sim probe 39892490709 (worlds R1 and R4, seed 3) cut its lookout there every tick
  ;; for twenty minutes over one given up on by the mouth, and the lure never counted a lookout.
  ;; A walk out to anywhere but the mouth (out-walk?) ends as soon as an angry one is near the
  ;; mouth (angry-near), not only at hand: churn-a pearls-12 run 1 (plan run 41096292443) walked
  ;; 12 blocks up its stairs for 8 s after the hunt's own choice was the way home, the angry one
  ;; arrived with the bot 11 blocks from the end, and four hits killed it on the stairs
  [world goal i]
  (or (and (fexit/blaze-refuge world)
           (not (contains? #{:eat :fight} (:intent/kind i)))
           (not (same-choice? i (fexit/blaze-refuge world))))
      (held-idle? world i)
      (and (= :relocate (:intent/for i)) (nil? (:pearls/relocate world)))
      (and (not (contains? pparams/answers-to-hand (:intent/kind i)))
           (not (:intent/wall? i))
           (not (and (= :walk (:intent/kind i)) (= (:intent/target i) (:pearls/shelter world))))
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
           (not (same-choice? i (plan/next-intent world goal plan/goals))))))

(defmethod plan/fire-cover :pearls
  ;; a ghast or blaze in sight, or one that was within shot-hold-ms (recently-shot?): the cover
  ;; from it, never a retreat walk in the open. Stage 18 (b3bfbfd, b54f648, 59e528b): every death
  ;; in the open to ghast or blaze fire came during a :walk :for :retreat, 18 of 18 (issue #263).
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
  ;; hunt's: its first branch walks home. Churn-a pearls-12 run 3 and enderman-end-b runs 1 and 2
  ;; (plan runs 41096292443 and 41103285823) were hit once, 0.05 s later the :defend row began a
  ;; fight to the end where they stood, two to four blocks from the burrow's end, and four hits
  ;; at 5.74 killed each in 3 to 5 s, the walk home cut and the hunt unable to cut the fight
  ;; (answers-to-hand). Struck by a heavy hostile (blaze, ghast, wither skeleton, piglin) during
  ;; pearls stage, the hunt claims it so the :defend row does not fight it with a sword in the open.
  [world _ e]
  (or (and (= :enderman (entity/kind e))
           (some? (pplace/shelter world))
           (or (= (pplace/shelter world) (intent/feet world))
               (intent/routable?
                world
                {:intent/kind :walk :intent/target (pplace/shelter world) :intent/for :shelter})))
      (and (fexit/pearls-stage? world) (contains? pparams/heavy-kinds (entity/kind e)))))

(defmethod plan/next-intent :pearls
  ;; a fire the bot stands in put out first (a :dig: the server breaks fire on the first click,
  ;; as blaze/put-out does); then the kill's drop, unless another angry one is near (menaced?);
  ;; with no serviceable sword (none, or one under window/spare-uses swings), make the one the
  ;; held items pay for when that craft can start (make-sword). With none at all and no craft,
  ;; a wait that fails the plan, never a bare-hand hunt
  ;; (:no-sword): gym batch pearls-warped8-b (plan run 39775345028) runs 1 and 3 respawned with
  ;; nothing held after their first deaths and hunted on bare-handed, 39 and 26 deaths in five
  ;; hours. Sim race 39909643851 (issue #563, world R3/13 seed 2) held a stone sword with nine
  ;; swings left, fought until it broke, and the same wait failed the plan. A worn sword that
  ;; cannot be replaced yet is still hunted with.
  ;; With no pickaxe over spare-digs (spare?) and none angry near (menaced?), a new one is made
  ;; first when that craft can start (make-pickaxe): a burrow is about sixteen digs, and batch
  ;; pearls-warped9-a's run 2 had eleven left at five pearls and stood in lookouts for four
  ;; hours. With the pickaxe worn out (worn-out?) and no craft to start, no burrow near and
  ;; none angry near, no hunt either (:no-pickaxe): no burrow can be cut, and
  ;; sim probe 39890964793 (seed 5) wore its pickaxe out on burrows, then struck calm endermen in
  ;; the open in worlds R2 and R3 and was slain, R2 nine times
  [world goal table]
  (let [k (when-not (psense/menaced? world) (forage/fresh-kill world :pearls))
        [_ d] (when k (psense/drop-of world k))
        fire (psense/burning-at world)
        replacing (when-not (sword/serviceable world) (pupkeep/make-sword world goal table))
        remaking (when (and (not (pupkeep/spare? world)) (not (psense/menaced? world)))
                   (pupkeep/make-pickaxe world goal table))
        ;; the hands a death leaves empty at the overworld spawn: no trunk sighted yet is
        ;; no reason to fail (gym batch pearls-r3-w2 run 2, plan run 40475390313, died in
        ;; the forest at three minutes and failed :no-sword at a spawn with no log in the
        ;; chunks first loaded); looking and roaming load more, and the remake is asked
        ;; again after each. A sword merely broken where the bot hunts still waits.
        respawned? (and (pos? (:stats/deaths world 0)) (= dimension/overworld (dimension/of world)))
        seek-wood (when respawned?
                    (or (pmove/roam world) {:intent/kind :lookout :intent/for :pearls}))]
    (cond
      fire (intent/why :pearls/fire {:intent/kind :dig :intent/target fire})
      d
      (intent/why :pearls/d
                  {:intent/kind :collect-drops
                   :intent/item pparams/pearl
                   :intent/target (combat/cell (:entity/pos d))
                   :intent/for :pearls})
      (and k (psense/body? world k)) (intent/why :pearls/and-k-body?-world-k {:plan/wait :drop})
      (< (:player/health world 20.0) pparams/danger-health)
      (intent/why :pearls/player-health-world-20-0-danger-health (hunt world))
      replacing (intent/why :pearls/replacing replacing)
      (and seek-wood (nil? (combat/sword world)))
      (intent/why :pearls/and-seek-wood-nil?-combat-sword-world seek-wood)
      (nil? (combat/sword world))
      (intent/why :pearls/nil?-combat-sword-world {:plan/wait :no-sword})
      remaking (intent/why :pearls/remaking remaking)
      (pupkeep/reserve-gather world)
      (intent/why :pearls/reserve-gather-world (pupkeep/reserve-gather world))
      (and seek-wood
           (pupkeep/worn-out? world)
           (nil? (pplace/shelter world))
           (not (psense/menaced? world)))
      (intent/why :pearls/and-seek-wood-worn-out?-world-nil?-shelter-world seek-wood)
      (and (pupkeep/worn-out? world) (nil? (pplace/shelter world)) (not (psense/menaced? world)))
      (intent/why :pearls/and-worn-out?-world-nil?-shelter-world-not-menac {:plan/wait :no-pickaxe})
      ;; slots kept free for the drops (window/free-reserve), as the blaze row keeps them: a
      ;; pearl is picked up on contact only with a slot free. Sim R1-2-1 on the stage-18 rods
      ;; start (replay probe 42225282730) held 3 pearls with 0 slots free from 4145 s on, and
      ;; every pickup after failed :not-picked-up; the race kit fills all 36
      (and (not (psense/menaced? world)) (window/reserve-toss world))
      (intent/why :pearls/and-not-menaced?-world-window-reserve-toss-world
                  (window/reserve-toss world))
      :else (intent/why :pearls/else (or (hunt world) (pearl-fallback world))))))
