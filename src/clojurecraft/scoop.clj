(ns clojurecraft.scoop
  "Filling a bucket with water: the :scoop act of the goal table and the :scoop intent.

     {:intent/kind :scoop :intent/target [x y z]}   ; a water source within reach

   The act looks for a surface water source in the loaded terrain near the bot (never deeper
   than memory/surface-water-below under the eye: cave water is the aquifer trap the siblings
   drowned in), scoops it when it is in reach, else walks to it, else walks to the nearest
   remembered pool (one per chunk, memory/pools), else waits :no-water.

   The intent puts the bucket in hand, looks at the source, and sends use-item with that look:
   a bucket is used by where the eyes point, not on a block face (use-item-on with a bucket does
   nothing). It is done only when the inventory holds one more water bucket than when it began;
   an unanswered scoop is tried again, and the last try fails :no-scoop, which blacklists the
   source. Nothing is remembered that the inventory can tell."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for water in the loaded terrain
(def pool-radius 96)                  ; blocks from the eye a remembered pool is walked to from
(def settle-ms 300)                   ; standing still this long, looking at the source, before using the bucket
(def answer-ms 3000)                  ; ms a scoop waits for the water bucket before it is tried again
(def max-tries 3)                     ; scoops sent at one source before it fails :no-scoop
(def surface-height 0.85)             ; where a source's surface is aimed at: a source stands 8/9 of a block

(defn- now [world] (:time/now world))

(def water-bucket (recipe/item-id :water_bucket))

;; ---------------------------------------------------------------- where the water is

(defn surface-source?
  "Is pos a water source block (plain water at level 0, not a waterlogged block) with no water
   above it?"
  [world [x y z :as pos]]
  (and (= blocks/water-base (terrain/block-at world pos))
       (not= :water (terrain/fluid-kind world [x (inc y) z]))))

(defn nearest-source
  "The nearest surface water source in the loaded terrain within search-radius of the eye, no
   more than memory/surface-water-below under it, not blacklisted; nil when none. Asked of the
   terrain through the block overlay, so a source just scooped is air."
  [world]
  (let [eye (game/eye world)
        lo (- (second eye) memory/surface-water-below)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-of eye
                       search-radius
                       (->> (chunk/find-blocks (stone/near-chunks world) #{blocks/water-base})
                            (map (fn [[x y z _]] [x y z]))
                            (filter (fn [[_ y _]] (>= y lo)))
                            (remove blacklist)
                            (filter #(surface-source? world %))))))

(defn nearest-pool
  "The nearest remembered water pool within pool-radius, no deeper than surface water, not
   blacklisted; nil when none."
  [world]
  (let [eye (game/eye world)
        lo (- (second eye) memory/surface-water-below)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-of eye
                       pool-radius
                       (->> (vals (memory/pools world :water))
                            (filter (fn [[_ y _]] (>= y lo)))
                            (remove blacklist)))))

(defn scoop-next
  "The next step toward a filled bucket: scoop the nearest surface source in reach, walk to the
   nearest one in sight, or to the nearest remembered pool; else wait :no-water."
  [world]
  (let [src (nearest-source world)
        pool (when-not src (nearest-pool world))]
    (cond (and src (<= (physics/distance (game/eye world) (physics/centre src)) intent/reach))
          {:intent/kind :scoop :intent/target src}
          src {:intent/kind :walk :intent/target src :intent/for :scoop}
          pool {:intent/kind :walk :intent/target pool :intent/for :scoop}
          :else {:plan/wait :no-water})))

(defmethod plan/act :scoop [world _] (scoop-next world))

;; ---------------------------------------------------------------- the scoop

(defmulti scoop-stage
  "Advance a :scoop intent one tick in its :intent/stage (:equip :aim :sent). Called only when
   no inventory click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod scoop-stage :equip [world _] (place/equip world :bucket :aim))

(defn use-item
  "Use the held item where the eyes look: emit use-item with a fresh :bot/sequence and the look
   it is aimed by, and record the sequence and the send time on the intent."
  [world [yaw pitch]]
  (let [seq (inc (:bot/sequence world))]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item :hand 0 :sequence seq :y-rot yaw :x-rot pitch})
        (game/emit {:packet/name :swing :hand 0})
        (intent/assoc-intent :intent/sequence seq :intent/sent-at (now world)))))

(defn aim-point
  "Where the eyes look to scoop the source at [x y z]: its surface's centre. Aimed at the block's
   centre from the bank, the ray clipped the corner of the bank block first (the sim's pond test)."
  [[x y z]]
  [(+ x 0.5) (+ y surface-height) (+ z 0.5)])

(defmethod scoop-stage :aim
  [world {:intent/keys [target since still] :or {still 0}}]
  (let [look (physics/look-at (game/eye world) (aim-point target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (cond (not (surface-source? world target)) (intent/fail world :target-gone)
          (and (>= still 3) (> (- (now world) since) settle-ms))
          (-> world
              (use-item look)
              (intent/assoc-intent :intent/stage :sent))
          :else (intent/assoc-intent world :intent/still still))))

(defmethod scoop-stage :sent
  [world {:intent/keys [before sent-at tries] :or {tries 1}}]
  (cond (> (inventory/item-count world water-bucket) before) (intent/done world)
        (<= (- (now world) sent-at) answer-ms) world
        (>= tries max-tries) (intent/fail world :no-scoop)
        :else
        (intent/assoc-intent world
                             :intent/stage :aim
                             :intent/since (now world)
                             :intent/tries (inc tries))))

(defmethod intent/run :scoop
  [world i _event]
  (let [i (cond-> i
            (nil? (:intent/stage i))
            (assoc :intent/stage :equip
                   :intent/since (now world)
                   :intent/before (inventory/item-count world water-bucket)))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (intent/fail world :stale-window)
      :waiting world
      (scoop-stage world i))))
