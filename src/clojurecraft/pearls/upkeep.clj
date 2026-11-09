(ns clojurecraft.pearls.upkeep
  "The pearl hunt's kit upkeep: the sword and the pickaxe made again, and the reserve of digs and stems."
  (:require [clojure.string :as str]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sword :as sword]
            [clojurecraft.window :as window]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

(defn pickaxe?
  "Does the bot hold any pickaxe, which every stair of a burrow through rock needs (a stair the
   held tool cannot drop is refused, stairs.clj)?"
  [world]
  (boolean (some (fn [[n c]] (and (pos? c) (str/ends-with? (name n) "_pickaxe")))
                 (recipe/counts (:player/inventory world)))))

(defn worn-out?
  "Has the hunt spent its pickaxe: a burrow cut this run (memory/last-done) and no pickaxe held
   now? A bot that never held one hunts in the open; one that wore its pickaxe out on burrows
   does not."
  [world]
  (boolean (and (not (pickaxe? world)) (memory/last-done world :burrow))))

(defn digs-left
  "The most digs any held pickaxe has left (window/swings-left: its tier's durability less the
   :damage the server sends on a worn one, or the model's counted-down :durability), or nil
   with none held."
  [world]
  (let [left (->> (vals (:player/inventory world))
                  (filter #(= :pickaxe (:kind (blocks/tool (:item %)))))
                  (keep window/swings-left))]
    (when (seq left) (apply max left))))

(defn spare?
  "Does a held pickaxe have spare-digs or more left, so that stairs or a tunnel for something
   other than a burrow may spend some? Netherrack makes no pickaxe, but a warped stem does
   (make-pickaxe): under spare-digs the hunt makes the next one before this one breaks."
  [world]
  (boolean (some-> (digs-left world)
                   (>= pparams/spare-digs))))

(defn log-counted?
  "Is a log already counted, in the inventory or laid in the crafting grid (sword/with-laid
   over recipe/counts)? Then a remake's wood is in hand and its log chain is finished:
   another trunk is not walked to, the log is crafted. A crafting table held is no wood
   (sword/pieces? counts one as pieces)."
  [world]
  (pos? (recipe/have (recipe/counts (:player/inventory (sword/with-laid world)))
                     (recipe/tags :logs))))

(defn for-sword-decide
  "The world a sword's needs are decided on: worn swords hidden (sword/drop-worn), the
   crafting grid visible (sword/with-laid), and a finished log chain not continued once a
   log is already counted (log-counted?), as for-pick-decide does for a pickaxe."
  [world]
  (cond-> (sword/with-laid (sword/drop-worn world))
    (and (log-counted? world) (= :log (:intent/for (:plan/last world)))) (dissoc :plan/last)))

(defn make-sword
  "The intent that makes the best sword the held items pay for (sword/payable) through the needs
   planner, when that craft can start from here: one of sword-acts, or the log chain to a
   remembered trunk (:intent/for :log) while no log is already counted (log-counted?).
   Nil when nothing is payable, or the planner would only wait, go looking for a tree, or chop
   another trunk with a log already in the grid. The Nether has no oak, and a wait there fails
   the plan. With no blade payable from the planks or cobblestone held but a log held, in the
   inventory or the grid (sword/with-laid: in the warped forest a stem, which counts as a log), a
   wooden sword is asked for instead: its planks come from the log, as a pickaxe's do
   (make-pickaxe). With no sword at all (combat/sword nil: the hands a death leaves empty), a
   wooden sword is asked for with nothing held either, and the log hunt for a trunk (:intent/for
   :log-hunt, wood/hunt) may start it. A worn sword still held is replaced only as before, never
   by a hunt for wood."
  [world goal table]
  (let [bare? (or (nil? (combat/sword world)) (not (sword/serviceable world)))]
    (when-let [k (or (sword/payable world)
                     (when (log-counted? world) :wooden_sword)
                     (when bare? :wooden_sword))]
      (let [i (make/decide (for-sword-decide world)
                           (assoc goal :goal/provides {(keyword "item" (name k)) 1})
                           table)]
        (when (and (:intent/kind i)
                   (not (and (log-counted? world) (= :log (:intent/for i))))
                   (or (= :log (:intent/for i))
                       (and bare? (= :log-hunt (:intent/for i)))
                       (contains? pparams/sword-acts (:intent/kind i))))
          i)))))

(defn worn-pick?
  "Is this stack a pickaxe under spare-digs digs (window/swings-left)?"
  [item]
  (boolean (and (= :pickaxe (:kind (blocks/tool (:item item))))
                (some-> (window/swings-left item)
                        (< pparams/spare-digs)))))

(defn drop-worn-picks
  "A world whose worn pickaxes (worn-pick?) keep their slots but are not pickaxes, as
   sword/drop-worn does for swords: make/counts treats any pickaxe as the one a row provides,
   worn or fresh, and would make nothing while the worn one still counts."
  [world]
  (update
   world
   :player/inventory
   (fn [inv]
     (into {} (map (fn [[p item]] [p (if (worn-pick? item) (dissoc item :item) item)])) inv))))

(defn for-pick-decide
  "The world a pickaxe's needs are decided on: worn pickaxes hidden (drop-worn-picks), the
   crafting grid visible (sword/with-laid), and a finished log chain not continued once a
   log is already counted (log-counted?), as sword/for-decide does for a sword. Not once
   the table's pieces are held (sword/pieces?): a table held counts there with no log and
   no plank beside it."
  [world]
  (cond-> (sword/with-laid (drop-worn-picks world))
    (and (log-counted? world) (= :log (:intent/for (:plan/last world)))) (dissoc :plan/last)))

(defn log-count
  "The logs and stems held (blocks/log-name?)."
  [world]
  (reduce +
          (keep (fn [[k n]] (when (blocks/log-name? k) n))
                (recipe/counts (:player/inventory world)))))

(defn reserve-gather
  "The next step of cutting stems (wood/continue-gather and wood/near-walk: the log chain to a
   remembered trunk, never a hunt for wood) while the best pickaxe held has under reserve-digs
   digs left and fewer than reserve-stems logs are held, with no angry enderman near: a pickaxe
   reserve before the last digs, so that the successor is made from stems (make-pickaxe, under
   spare-digs) and the hunt never stands in a rock pocket with six digs and no wood. Nil when
   none is held or the chain waits."
  [world]
  (let [d (digs-left world)]
    (when (and d
               (< d pparams/reserve-digs)
               (< (log-count world) pparams/reserve-stems)
               (not (psense/menaced? world)))
      (or (wood/continue-gather world) (wood/near-walk world)))))

(defn table-wood?
  "Can a crafting table be made from what is held: a log or stem (four planks), or four planks?"
  [world]
  (let [held (recipe/counts (:player/inventory world))]
    (or (log-counted? world) (>= (recipe/have held (recipe/tags :planks)) 4))))

(defn make-pickaxe
  "The intent that makes a pickaxe through the needs planner (a goal providing
   {:tag/pickaxes 1}, as blaze.clj's row asks), when that craft can start from here: one of
   pick-acts, or the log chain to a remembered trunk (:intent/for :log) while no log is
   already counted (log-counted?). Nil when the planner would only wait, or go out by stairs, a
   swim or a search for the wood. In a warped forest the trunk is a warped stem (blocks/log?
   counts stems, and the item tag :logs lists them beside the logs): stem to planks to sticks and
   a crafting table, then the pickaxe the held items reach, a stone one with cobblestone held, a
   wooden one without. With no pickaxe at all (pickaxe? false: the hands a death leaves empty),
   the log hunt for a trunk (:intent/for :log-hunt, wood/hunt) may start it too, as it may a
   bare-handed sword (make-sword). A worn pickaxe still held is replaced only as before, never by
   a hunt for wood (wood/hunt is nil underground, so the Nether remake is unchanged). A walk to a
   remembered table gives way to a new table when the wood for one is held (table-wood?); with
   none held and no pickaxe, the walk to it is taken."
  [world goal table]
  (let [bare? (not (pickaxe? world))
        decide
        (fn [w]
          (make/decide (for-pick-decide w) (assoc goal :goal/provides {:tag/pickaxes 1}) table))
        first-i (decide world)
        walk? (= :walk (:intent/kind first-i))
        ;; a remembered table is replaced by a new one, not walked at, when no route reaches it or
        ;; the wood for one is held: the walk to it is no craft, and the hunt refuses it (a rock
        ;; pocket stood still); a real stage-18 bot with no pickaxe, six warped stems, ten sticks
        ;; and 128 cobblestone, its table left behind, waited :no-pickaxe until the plan ended
        i (if (and walk? (or (not (intent/routable? world first-i)) (table-wood? world)))
            (decide (avoided/note world :blacklist (:intent/target first-i)))
            first-i)]
    (when (and (:intent/kind i)
               (not (and (log-counted? world) (= :log (:intent/for i))))
               (or (= :log (:intent/for i))
                   (and bare? (= :log-hunt (:intent/for i)))
                   (contains? pparams/pick-acts (:intent/kind i))
                   ;; with no pickaxe and no wood for a table, the walk to the one remembered:
                   ;; standing still ends the plan :no-pickaxe
                   (and bare? walk? (= :walk (:intent/kind i)) (not (table-wood? world)))))
      i)))
