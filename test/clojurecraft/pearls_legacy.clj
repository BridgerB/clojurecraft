(ns clojurecraft.pearls-legacy
  "The pearl hunt's cond as it was before pearls-rules.edn, kept so a test can check the rule table chooses what the cond chose."
  (:require [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]))

(set! *warn-on-reflection* true)

(defn legacy-hunt
  "The pearl hunt's next step as the cond it was before the rules table, kept to check the table against."
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
