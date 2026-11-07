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
  "The hunt's next step with no kill to pick up, the first of these that applies (hunt's cond is
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
        ;; one enderman at a time: none is provoked while an angry one is coming
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
        (psense/endermen (dissoc world :plan/blacklist) 128.0)))

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

(defmethod plan/held-goal? :pearls
  ;; with an angry enderman within hold-radius and a burrow held, the hunt waits inside: the stuck
  ;; watchdog counts this as held, not stuck, and never forces an explore walk out of the burrow
  [world _]
  (boolean (and (pplace/shelter world) (psense/angry-within? world pparams/hold-radius))))

(defmethod plan/interrupts? :pearls
  ;; any running intent but an answer (answers-to-hand) or the walk home ends with an angry
  ;; enderman at hand (beset?), and the hunt plans again: home, the fight held there, or the
  ;; fight where the bot stands. Nothing else ends a running intent. Never in the burrow's end,
  ;; where none reaches. A walk out to anywhere but the mouth (out-walk?) ends as soon as an angry
  ;; one is near the mouth (angry-near), not only at hand
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
  ;; a fire the bot stands in put out first (a :dig: the server breaks fire on the first click,
  ;; as blaze/put-out does); then the kill's drop, unless another angry one is near (menaced?);
  ;; with no serviceable sword (none, or one under window/spare-uses swings), make the one the
  ;; held items pay for when that craft can start (make-sword). With none at all and no craft,
  ;; a wait that fails the plan, never a bare-hand hunt (:no-sword). A worn sword that cannot be
  ;; replaced yet is still hunted with.
  ;; With no pickaxe over spare-digs (spare?) and none angry near (menaced?), a new one is made
  ;; first when that craft can start (make-pickaxe): a burrow is about sixteen digs. With the
  ;; pickaxe worn out (worn-out?) and no craft to start, no burrow near and none angry near, no
  ;; hunt either (:no-pickaxe): no burrow can be cut.
  [world goal table]
  (let [k (when-not (psense/menaced? world) (forage/fresh-kill world :pearls))
        [_ d] (when k (psense/drop-of world k))
        fire (psense/burning-at world)
        replacing (when-not (sword/serviceable world) (pupkeep/make-sword world goal table))
        remaking (when (and (not (pupkeep/spare? world)) (not (psense/menaced? world)))
                   (pupkeep/make-pickaxe world goal table))
        ;; the hands a death leaves empty at the overworld spawn: no trunk sighted yet is
        ;; no reason to fail; looking and roaming load more, and the remake is asked again after
        ;; each. A sword merely broken where the bot hunts still waits.
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
      ;; pearl is picked up on contact only with a slot free; the race kit fills all 36
      (and (not (psense/menaced? world)) (window/reserve-toss world))
      (intent/why :pearls/and-not-menaced?-world-window-reserve-toss-world
                  (window/reserve-toss world))
      :else (intent/why :pearls/else (or (hunt world) (pearl-fallback world))))))
