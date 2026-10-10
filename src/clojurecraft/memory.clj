(ns clojurecraft.memory
  "What the bot has seen, as facts with time.

   The store is a DataScript database held as a value in the world (:world/facts). Each
   observation is one fact entity {:sight/pos [x y z] :sight/state id :sight/at ms}, appended
   only when what is seen at a position differs from the last observation there. Nothing is
   ever retracted: a block later seen as air is a newer fact, and the history stays queryable
   (`history`). The latest observation at a position is the one with the highest entity id.

   Only block kinds that `watched?` accepts start a record (logs and crafting tables today); a
   position already on record is followed through every change, so a dug log becomes air."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.physics :as physics]
            [datascript.core :as d]))

(def schema
  "Positions and states are indexed, so lookups by place and Datalog by kind are direct."
  {:sight/pos {:db/index true}
   :sight/state {:db/index true}})

(defn empty-facts "A store with nothing seen yet." [] (d/empty-db schema))

(def crafting-table (first (keep (fn [[n _ lo]] (when (= n :crafting_table) lo)) blocks/table)))

(defn crafting-table? "Is this state a crafting table?" [id] (= id crafting-table))

(defn placed-state
  "The block state a placed item turns into, for the blocks the bot places today."
  [item]
  (case item :crafting_table crafting-table nil))

(defn watched? "Does seeing this state start a record?" [id] (or (blocks/log? id) (crafting-table? id)))

(def log-states (vec (filter blocks/log? (range 0 30000))))

;; ---------------------------------------------------------------- reading

(defn latest-entity
  "The newest observation at pos, as a DataScript entity, or nil."
  [db pos]
  (when-let [datoms (seq (d/datoms db :avet :sight/pos pos))]
    (d/entity db (apply max (map :e datoms)))))

(defn remembered "The last state seen at pos, or nil." [world pos]
  (:sight/state (latest-entity (:world/facts world) pos)))

(defn history
  "Every observation at pos, oldest first: [{:block/state id :block/seen-at ms} ...]."
  [world pos]
  (let [db (:world/facts world)]
    (->> (d/datoms db :avet :sight/pos pos)
         (map :e)
         sort
         (mapv #(let [e (d/entity db %)] {:block/state (:sight/state e) :block/seen-at (:sight/at e)})))))

(defn latest
  "The latest observation at every position on record: {pos {:block/state id :block/seen-at ms}}."
  [world]
  (let [db (:world/facts world)]
    (into {} (for [[pos e] (d/q '[:find ?pos (max ?e) :where [?e :sight/pos ?pos]] db)
                   :let [ent (d/entity db e)]]
               [pos {:block/state (:sight/state ent) :block/seen-at (:sight/at ent)}]))))

(defn positions-ever
  "Positions where any of the states was ever observed (a Datalog query over the facts)."
  [world states]
  (d/q '[:find [?pos ...] :in $ [?s ...] :where [?e :sight/state ?s] [?e :sight/pos ?pos]]
       (:world/facts world) states))

;; ---------------------------------------------------------------- writing

(defn observation
  "The fact to append for seeing id at pos, or nil when it adds nothing (same as the last)."
  [db pos id now]
  (let [last (latest-entity db pos)]
    (when (and (not= id (:sight/state last)) (or last (watched? id)))
      {:sight/pos pos :sight/state id :sight/at now})))

(defn remember-column
  "Record every watched block in a freshly loaded chunk column, and any change at a position
   already on record within it."
  [world key column]
  (let [db (:world/facts world)
        now (:time/now world)
        tx (keep (fn [[x y z id]] (observation db [x y z] id now)) (chunk/find-blocks {key column} watched?))]
    (cond-> world (seq tx) (assoc :world/facts (d/db-with db tx)))))

(defn observe
  "A single block changed: append the fact if it is watched or the position is on record."
  [world pos id]
  (let [db (:world/facts world)]
    (if-let [fact (observation db pos id (:time/now world))]
      (assoc world :world/facts (d/db-with db [fact]))
      world)))

;; ---------------------------------------------------------------- questions

(defn log-at? "Is the last thing seen at pos a log?" [world pos] (boolean (some-> (remembered world pos) blocks/log?)))

(defn trunk-bottom?
  "A remembered log with no remembered log below it: where a trunk meets the ground."
  [world [x y z]]
  (and (log-at? world [x y z]) (not (log-at? world [x (dec y) z]))))

(defn centre "The centre of a block position." [[x y z]] [(+ x 0.5) (+ y 0.5) (+ z 0.5)])

(defn nearest-of
  "Nearest of positions within radius of eye, or nil."
  [eye radius positions]
  (->> positions
       (map (fn [p] [(physics/distance eye (centre p)) p]))
       (filter (fn [[d _]] (<= d radius)))
       (sort-by first)
       first
       second))

(defn nearest
  "Nearest position whose latest observed state satisfies pred, within radius of eye, or nil.
   The common case, crafting tables, is answered from the state index; other predicates scan
   the latest view."
  [world eye radius pred]
  (nearest-of eye radius
              (if (= pred crafting-table?)
                (filter #(crafting-table? (remembered world %)) (positions-ever world [crafting-table]))
                (for [[p {:block/keys [state]}] (latest world) :when (pred state)] p))))

(defn nearest-log
  "Nearest trunk-bottom log on record within radius of eye, skipping blacklisted positions and
   logs more than 12 blocks above or below the eye. nil when none. Candidates come from a
   Datalog query (positions where a log was ever seen); each is checked against its latest
   observation, so a log since seen as air does not count."
  [world eye radius blacklist]
  (let [ey (second eye)]
    (nearest-of eye radius
                (->> (positions-ever world log-states)
                     (remove blacklist)
                     (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
                     (filter #(trunk-bottom? world %))))))
