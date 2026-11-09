(ns clojurecraft.pearls.facts
  "What the pearl hunt's rules ask of the world: a table from a fact key to a function of the
   fact getter and the world. A fact is computed at most once per call, and only when a rule's
   condition or builder names it (context)."
  (:require [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.forage :as forage]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.sword :as sword]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def lookout
  "The intent of standing where the bot is and looking round."
  {:intent/kind :lookout :intent/for :pearls})

(def facts
  "Fact key -> (fn [f world]): f is the getter of other facts. Every function is pure over the
   world; nil and false both mean the fact does not hold."
  {:pos (fn [_ w] (:player/pos w))
   :at-hand (fn [_ w] (psense/at-hand w))
   :eid (fn [f _] (first (f :at-hand)))
   :e (fn [f _] (second (f :at-hand)))
   :nearest-creepy (fn [_ w]
                     (entity/nearest (into {}
                                           (filter (fn [[eid e]]
                                                     (and (entity/creepy? e)
                                                          (not (and
                                                                (psense/lured-out? w)
                                                                (psense/unfightable? w eid e))))))
                                           (psense/endermen w pparams/stare-range))
                                     (:player/pos w)))
   :ceid (fn [f _] (first (f :nearest-creepy)))
   :ce (fn [f _] (second (f :nearest-creepy)))
   :near? (fn [f _]
            (let [e (f :e)]
              (and e (<= (physics/distance (f :pos) (:entity/pos e)) pparams/strike-range))))
   :angry? (fn [f w]
             (and (f :near?)
                  (entity/creepy? (f :e))
                  (not (contains? (avoided/all-of w :blacklist) (f :eid)))))
   :in-reach? (fn [f w]
                (and (f :near?)
                     (<= (physics/distance (game/eye w) (combat/aim (f :e))) combat/attack-reach)))
   :hurt? (fn [_ w] (< (:player/health w 20.0) pparams/danger-health))
   :tracked? (fn [_ w] (psense/angry-tracked? w pparams/hold-radius))
   :rested? (fn [_ w] (>= (:player/health w 20.0) (psense/provoke-line w)))
   :meal (fn [f w] (when-not (f :rested?) (blaze/meal w)))
   :home (fn [_ w] (pplace/shelter w))
   :in? (fn [f w] (and (f :home) (= (f :home) (intent/feet w))))
   :at-end? (fn [_ w] (= (:pearls/shelter w) (intent/feet w)))
   :threat (fn [_ w] (psense/threat w))
   :tid (fn [f _] (first (f :threat)))
   :te (fn [f _] (second (f :threat)))
   :struck-by (fn [_ w] (psense/struck-by w))
   :sid (fn [f _] (first (f :struck-by)))
   :swarmed? (fn [_ w] (psense/swarmed? w))
   :cornered? (fn [f w] (and (f :near?) (f :eid) (avoided/noted? w :cornered (f :eid))))
   :blocking (fn [f w] (when (f :at-end?) (psense/blocking w)))
   :bid (fn [f _] (first (f :blocking)))
   :angry-near (fn [f w] (when (f :home) (psense/angry-near w)))
   :aid (fn [f _] (first (f :angry-near)))
   :quarry (fn [_ w] (psense/quarry w))
   :quarry-id (fn [f _] (first (f :quarry)))
   :q (fn [f _] (second (f :quarry)))
   ;; one enderman at a time: none is provoked while an angry one is coming
   :coming? (fn [_ w] (psense/coming? w))
   :qid (fn [f _] (when-not (f :coming?) (f :quarry-id)))
   :ghost? (fn [f w]
             (and (f :near?) (not (f :angry?)) (contains? (avoided/all-of w :blacklist) (f :eid))))
   :m (fn [_ w] (second (psense/menace w)))
   :dir (fn [f w]
          (when (and (nil? (f :home))
                     (pupkeep/pickaxe? w)
                     (or (f :ce)
                         (f :q)
                         (f :m)
                         (and (f :near?) (not (f :ghost?)))
                         (and (f :e) (psense/at-site? w))))
            (pplace/away w (or (f :q) (f :ce) (f :m) (f :e)))))
   :digs? (fn [f w] (and (f :dir) (pplace/burrowable? w (intent/feet w) (f :dir))))
   :site (fn [f w] (when (and (f :dir) (not (f :digs?))) (pplace/burrow-site w (f :dir))))
   :csite (fn [f w]
            (when (and (nil? (f :home))
                       (pupkeep/pickaxe? w)
                       (not (psense/at-site? w))
                       (psense/stale? w)
                       (nil? (f :m)))
              (pplace/crowd-site w)))
   :mouth (fn [_ w] (:pearls/mouth w))
   :way-home (fn [f _] {:intent/kind :walk :intent/target (f :home) :intent/for :shelter})
   :mouth-walk (fn [f w] (pmove/shelter-walk w (f :mouth)))
   :travel-walk (fn [f w]
                  (when (and (not (f :hurt?))
                             (not (f :tracked?))
                             (not (f :swarmed?))
                             (not (psense/recently-shot? w))
                             (not (psense/menaced? w))
                             (empty? (psense/endermen w pparams/seek-radius)))
                    (pmove/travel w)))
   :relocate-walk (fn [f w]
                    (when (and (:pearls/relocate w)
                               (not (f :hurt?))
                               (not (f :swarmed?))
                               (not (psense/recently-shot? w))
                               (not (and (f :home) (psense/angry-within? w pparams/hold-radius)))
                               (not (if (< (- (:time/now w 0) (:at (:pearls/relocate w)))
                                           pparams/relocate-patience-ms)
                                      (psense/menaced? w)
                                      (psense/live-menaced? w))))
                      (pmove/relocate-walk w)))
   :wander-walk (fn [f w]
                  (when (and (not (f :hurt?))
                             (not (f :tracked?))
                             (not (f :swarmed?))
                             (not (and (f :home) (psense/angry-within? w pparams/hold-radius)))
                             (not (psense/live-menaced? w))
                             (not (psense/recently-shot? w))
                             (pmove/wandering? w))
                    (pmove/wander w)))
   :cage-break
   (fn [f w]
     (when (and (not (f :hurt?))
                (not (f :tracked?))
                (not (f :swarmed?))
                (not (psense/live-menaced? w))
                (not (psense/recently-shot? w))
                (or (pmove/wandering? w) (pmove/travel-caged? w)))
       (if (= dimension/nether (dimension/of w))
         (or (pmove/dig-out w) (pmove/tunnel-out w) (pmove/climb-out w) (pmove/uncage w))
         (or (pmove/climb-out w) (pmove/dig-out w) (pmove/uncage w)))))
   :heading-walk (fn [f w]
                   (when (and (not (f :hurt?))
                              (not (f :tracked?))
                              (not (f :swarmed?))
                              (not (psense/recently-shot? w))
                              (not (psense/menaced? w))
                              (empty? (psense/endermen w pparams/seek-radius))
                              (= dimension/nether (dimension/of w))
                              (not (psense/in-forest? w)))
                     (pmove/reach-heading w)))
   :csite-walk (fn [f w] (pmove/shelter-walk w (f :csite)))
   :site-walk (fn [f w] (pmove/shelter-walk w (f :site)))
   :overworld-death? (fn [_ w]
                       (and (pos? (:stats/deaths w 0)) (= dimension/overworld (dimension/of w))))
   :far (fn [_ w] (psense/endermen w pparams/seek-radius))
   :fight-id (fn [f w]
               (cond (and (f :ce) (not (psense/unfightable? w (f :ceid) (f :ce)))) (f :ceid)
                     (and (or (f :angry?) (f :in-reach?))
                          (not (psense/unfightable? w (f :eid) (f :e))))
                     (f :eid)
                     :else (f :tid)))
   ;; conditions of single rules
   :blaze-refuge (fn [_ w] (fexit/blaze-refuge w))
   :come-down (fn [_ w] (pplace/come-down w))
   :blaze-fight (fn [_ w] (pcover/blaze-fight w))
   :meal-unshot (fn [f w]
                  (and (f :meal)
                       (or (empty? (filter #(combat/seen? w (val %)) (psense/shooters w)))
                           (and (fexit/fortress-escaping? w) (not (psense/shot-at? w))))))
   :pocket-bore (fn [_ w] (fexit/pocket-bore w))
   :tunnel-answer (fn [_ w] (fexit/tunnel-answer w))
   :fortress-escaping? (fn [_ w] (fexit/fortress-escaping? w))
   :can-tunnel-out? (fn [_ w] (fexit/can-tunnel-out? w))
   :shot-at? (fn [_ w] (psense/shot-at? w))
   :cover-from (fn [_ w] (pcover/cover-from w))
   :fortress-tunnel-out (fn [_ w] (fexit/fortress-tunnel-out w))
   :leave-sight (fn [_ w] (pcover/leave-sight w))
   :room-exit (fn [_ w] (fexit/room-exit w))
   :home-pressed? (fn [f w]
                    (or (f :ce)
                        (f :near?)
                        (f :hurt?)
                        (f :tid)
                        (f :aid)
                        (f :swarmed?)
                        (psense/angry-within? w pparams/hold-radius)))
   :home-routable? (fn [f w] (intent/routable? w (f :way-home)))
   :swarm-in-burrow (fn [f w]
                      (or (when (f :mouth) (pcover/wall-toward w (physics/centre (f :mouth)) [0 1]))
                          lookout))
   :meal-or-lookout (fn [f _] (or (f :meal) lookout))
   :meal-or-wall-off (fn [f w] (or (f :meal) (pcover/wall-off w (f :e))))
   :back-off-pointless? (fn [f w] (combat/back-off-pointless? w (f :te)))
   :weak-threat? (fn [f _] (psense/weak? (f :te)))
   :blocked-out? (fn [_ w] (psense/blocked-out? w))
   :cut-out-or-lookout (fn [_ w] (or (pmove/cut-out w) lookout))
   :at-hand-unfightable? (fn [f w] (psense/unfightable? w (f :eid) (f :e)))
   :at-hand-fightable-kind? (fn [f w]
                              (or (entity/creepy? (f :e))
                                  (and (not (pupkeep/pickaxe? w))
                                       (not (pupkeep/worn-out? w))
                                       (not (contains? (avoided/all-of w :blacklist) (f :eid))))))
   :hurt-or-blocked (fn [f _] (or (f :hurt?) (and (f :in?) (f :bid))))
   :lured-out? (fn [_ w] (psense/lured-out? w))
   :lure? (fn [_ w] (psense/lure? w))
   :aid-or-angry-within? (fn [f w] (or (f :aid) (psense/angry-within? w pparams/hold-radius)))
   :crowded? (fn [_ w] (psense/crowded? w))
   :vantage-or-lookout (fn [f w] (or (pplace/vantage w (f :far)) lookout))
   :unseal (fn [f w] (pcover/unseal w (f :mouth)))
   :mouth-clear? (fn [f w] (empty? (psense/swarm w (physics/centre (f :mouth)))))
   :provoke-place-ok? (fn [f w]
                        (or (and (not (pupkeep/pickaxe? w)) (not (pupkeep/worn-out? w)))
                            (= (f :mouth) (intent/feet w))
                            (and (f :home)
                                 (<= (physics/distance (f :pos) (physics/centre (f :home)))
                                     pparams/provoke-home-reach))))
   :ce-fightable? (fn [f w] (not (psense/unfightable? w (f :ceid) (f :ce))))
   :pickaxe? (fn [_ w] (pupkeep/pickaxe? w))
   :home-fallback (fn [f w]
                    (or (pplace/vantage w (f :far))
                        (when (pmove/wandering? w) (pmove/wander w))
                        (f :cage-break)
                        lookout))
   :in-forest-or-overworld (fn [_ w]
                             (or (not= dimension/nether (dimension/of w)) (psense/in-forest? w)))
   :set-up-done? (fn [f w] (boolean (and (f :home) (sword/serviceable w) (pupkeep/spare? w))))
   ;; the rows of next-intent that came before the hunt: the kill's drop, fire, the kit
   :menaced? (fn [_ w] (psense/menaced? w))
   :fire (fn [_ w] (psense/burning-at w))
   :fresh-kill (fn [f w] (when-not (f :menaced?) (forage/fresh-kill w :pearls)))
   :drop (fn [f w] (when (f :fresh-kill) (second (psense/drop-of w (f :fresh-kill)))))
   :collect-drop (fn [f _]
                   {:intent/kind :collect-drops
                    :intent/item pparams/pearl
                    :intent/target (combat/cell (:entity/pos (f :drop)))
                    :intent/for :pearls})
   :over-body? (fn [f w] (and (f :fresh-kill) (psense/body? w (f :fresh-kill))))
   :replacing (fn [f w]
                (when-not (sword/serviceable w) (pupkeep/make-sword w (f :goal) (f :table))))
   :remaking (fn [f w]
               (when (and (not (pupkeep/spare? w)) (not (f :menaced?)))
                 (pupkeep/make-pickaxe w (f :goal) (f :table))))
   :respawned? (fn [_ w] (and (pos? (:stats/deaths w 0)) (= dimension/overworld (dimension/of w))))
   :seek-wood (fn [f w] (when (f :respawned?) (or (pmove/roam w) lookout)))
   :no-sword? (fn [_ w] (nil? (combat/sword w)))
   :reserve-gather (fn [_ w] (pupkeep/reserve-gather w))
   :worn-out-homeless? (fn [f w]
                         (and (pupkeep/worn-out? w) (nil? (pplace/shelter w)) (not (f :menaced?))))
   :reserve-toss (fn [f w] (and (not (f :menaced?)) (window/reserve-toss w)))
   :angry-tracked-hold? (fn [_ w] (psense/angry-tracked? w pparams/hold-radius))
   :search
   (fn [f w]
     (or
      (if (pupkeep/pickaxe? w)
        (some (fn [[_ e]]
                (when-let [c (pplace/stare-site w e)]
                  {:intent/kind :walk :intent/target c :intent/for :shelter}))
              (->> (f :far)
                   (remove (fn [[eid e]] (and (psense/lured-out? w) (psense/unfightable? w eid e))))
                   (sort-by (fn [[_ e]] (physics/distance (:player/pos w) (:entity/pos e))))
                   (take pparams/candidates)))
        (when-not (pupkeep/worn-out? w)
          (when-let [[_ e] (pmove/routable w (f :far))]
            {:intent/kind :walk :intent/target (combat/cell (:entity/pos e)) :intent/for :pearls})))
      (when-not (f :overworld-death?) (pmove/descend w (psense/endermen w pparams/descend-radius)))
      (pmove/ascend w (psense/endermen w pparams/descend-radius))
      (pmove/toward w (f :far))
      (when-not (f :overworld-death?) (pmove/bore w (psense/endermen w pparams/descend-radius)))
      (when (= :lookout (:intent/kind (:plan/last w)))
        (or (pmove/roam w)
            (when (pmove/stuck? w) (pmove/climb-out w))
            (when-not (f :overworld-death?) (pmove/dig-out w))
            (when (pmove/stuck? w) (pmove/uncage w))))
      (when (pmove/wandering? w) (pmove/wander w))
      (pmove/go-toward-sighting w)
      lookout))})

(defn context
  "The fact getter for world: (f k) is the value of fact k, computed on its first ask and kept for
   the rest of the call; extra (a map of fact key to value, e.g. :goal and :table) is given. Throws
   on a key facts lacks."
  ([world] (context world {}))
  ([world extra]
   (let [cache (volatile! extra)]
     (fn get-fact [k]
       (let [c @cache]
         (if (contains? c k)
           (c k)
           (let [v ((or (facts k) (throw (ex-info "no such fact" {:fact k}))) get-fact world)]
             (vswap! cache assoc k v)
             v)))))))
