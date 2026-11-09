(ns clojurecraft.table
  "The crafting table the bot placed, carried on once its crafting is done: a bot with the
   cobblestone and sticks of a stone pickaxe and no table to make it at is a bot that left its table
   behind.

   Requiring this namespace registers the rules of the :keep-table rows (:keep-table-2 today, its
   dig and pick-up committed so no other row but an escape cuts them): an always row in a chained run,
   after the crafting rows of the table. It is pending right after a craft at a table the bot placed
   itself (a :place of it ended :done on that very cell), when no table is held, the table is in
   reach and the bag has room, and the row that would be worked next does not craft again
   (queued-craft?). Planned by :keep-table: the dig of the table, then the pick-up of its drop.
   A table the bot did not place, a found one, is never taken."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.picks :as picks]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(def craft-gap-ms
  "ms after a craft at a table that the table still counts as just used: the bot leaves it as soon
   as it has the craft, so the table is taken in these or not at all."
  6000)

(defn own-table
  "The position of the remembered crafting table within reach that the bot placed itself, or nil: a
   :place intent ended :done on that cell. A table the bot only saw (a village's) has no such fact."
  [world]
  (let [pos (make/near world :crafting_table)]
    (when (and pos (memory/done-at? world :place pos)) pos)))

(defn crafted-just-now?
  "Did the intent that last ended finish a craft at a table (:craft in the table window), within craft-gap-ms?"
  [world]
  (let [e (:plan/ended world)
        l (:plan/last world)]
    (boolean (and (= :craft (:intent/kind l) (:intent/kind e))
                  (= :table (:intent/window l))
                  (<= (- (long (:time/now world 0)) (long (:ended/at e 0))) craft-gap-ms)))))

(defn queued-craft?
  "Does the row that would be worked next, without this one, craft again at once: its next intent
   a craft, the opening of a container, or a walk to the table? Then the table stays where it is."
  [world]
  (let [others (into [] (remove #(#{:keep-table :keep-table-2} (:goal/id %))) plan/goals)
        goal (plan/choose world others)
        i (when goal (plan/next-intent world goal others))]
    (boolean (and i
                  (or (contains? #{:craft :open-container} (:intent/kind i))
                      (and (= :walk (:intent/kind i))
                           (= (:intent/target i) (make/near world :crafting_table))))))))

(defn next-step
  "The intent that takes the placed table along, or nil. In a chained run only. The pick-up of a
   drop goes on from a dig that ended (the chain for :take-table), a table drop lying near is
   collected, and the dig starts right after a craft at a table the bot placed (own-table), with
   a slot for it (make/take-table-along) and no craft queued (queued-craft?)."
  [world]
  (when (:plan/chain? world)
    (or (intent/why :table/pickup-in-flight
                    (intent/chain-next (:plan/last world) :take-table make/table-drops {}))
        (when-let [[_ e] (entity/nearest (picks/table-drops-near world) (:player/pos world))]
          (intent/why :table/collect-dropped
                      {:intent/kind :collect-drops
                       :intent/item :crafting_table
                       :intent/target (picks/drop-cell e)
                       :intent/for :take-table}))
        (when (and (crafted-just-now? world) (own-table world) (not (queued-craft? world)))
          (let [i (make/take-table-along world)]
            (when (:intent/kind i) (intent/why :table/take-along i)))))))

(defmethod plan/done-by :table-kept? [world _] (nil? (next-step world)))

(defmethod plan/next-intent :keep-table [world _ _] (next-step world))
