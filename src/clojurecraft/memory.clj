(ns clojurecraft.memory
  "What the bot has seen, as facts with time.

   The store is a DataScript database held as a value in the world (:world/facts). Each
   observation is one fact entity {:sight/pos [x y z] :sight/state id :sight/at ms}, appended
   only when what is seen at a position differs from the last observation there. Nothing is
   ever retracted: a block later seen as air is a newer fact, and the history stays queryable
   (`history`). The latest observation at a position is the one with the highest entity id.

   Only block kinds that `watched?` accepts start a record (logs and crafting tables today); a
   position already on record is followed through every change, so a dug log becomes air.

   The bot's own intentions are facts in the same store: {:intention/id n :intention/event
   :started|:done|:failed|:abandoned :intention/kind k :intention/at ms} plus the target, recipe
   or reason when there is one. `intention-as-of` answers what the bot was trying to do at any time t.

   The server's answers to the bot's own actions are facts too: {:answer/kind :ack|:pickup
   :answer/at ms} with the acknowledged :answer/sequence, or the picked-up :answer/entity and
   :answer/count. A broken block needs no answer fact: it is a sighting of air where it stood."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.physics :as physics]
            [datascript.core :as d]))

(def schema
  "Positions and states are indexed, so lookups by place and Datalog by kind are direct;
   intention ids too, so an intention's story is one lookup."
  {:sight/pos {:db/index true}
   :sight/state {:db/index true}
   :intention/id {:db/index true}
   :answer/kind {:db/index true}})

(defn empty-facts "A store with nothing seen yet." [] (d/empty-db schema))

(def crafting-table (first (keep (fn [[n _ lo]] (when (= n :crafting_table) lo)) blocks/table)))

(defn crafting-table? "Is this state a crafting table?" [id] (= id crafting-table))

(defn placed-state
  "The block state a placed item turns into, for the blocks the bot places today."
  [item]
  (case item :crafting_table crafting-table nil))

(def stone-states "Every state of stone, watched so cobblestone can be planned for." (blocks/states-where (fn [[n]] (= n :stone))))

(defn watched?
  "Does seeing this state start a record? Logs, crafting tables and stone."
  [id]
  (or (blocks/log? id) (crafting-table? id) (contains? stone-states id)))

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

(defn positions-now
  "Positions where one of states was seen and nothing seen there since is outside states: the
   essay's \"the nearest log I have ever seen that I have not confirmed gone\", as one Datalog
   query over the facts (no later fact at the place with a state outside the set)."
  [world states]
  (d/q '[:find [?pos ...] :in $ [?s ...] ?all
         :where [?e :sight/state ?s] [?e :sight/pos ?pos]
         (not-join [?e ?pos ?all]
                   [?later :sight/pos ?pos] [(> ?later ?e)] [?later :sight/state ?t]
                   [(contains? ?all ?t) ?kept] [(false? ?kept)])]
       (:world/facts world) states (set states)))

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

(defn intention
  "The fact for intent i reaching event (:started :done :failed :abandoned) at now."
  [i event now]
  (cond-> {:intention/id (:intent/id i) :intention/event event :intention/kind (:intent/kind i) :intention/at now}
    (:intent/target i) (assoc :intention/target (:intent/target i))
    (:intent/recipe i) (assoc :intention/recipe (:intent/recipe i))
    (:intent/reason i) (assoc :intention/reason (:intent/reason i))))

(defn remember-intention
  "Append what happened to intent i (event) as a fact; an intent with no id is not recorded."
  [world i event]
  (if (:intent/id i)
    (update world :world/facts d/db-with [(intention i event (:time/now world))])
    world))

(defn remember-answer
  "Append a server answer (a map of :answer/* keys) stamped with now."
  [world answer]
  (update world :world/facts d/db-with [(assoc answer :answer/at (:time/now world))]))

;; ---------------------------------------------------------------- questions

(defn answers
  "The server's answers of a kind (:ack, :pickup), oldest first."
  [world kind]
  (let [db (:world/facts world)]
    (->> (d/q '[:find [?e ...] :in $ ?k :where [?e :answer/kind ?k]] db kind)
         sort
         (mapv #(into {} (d/touch (d/entity db %)))))))

(defn intentions
  "The story of every intention, oldest fact first."
  [world]
  (let [db (:world/facts world)]
    (->> (d/datoms db :aevt :intention/id)
         (map :e)
         sort
         (mapv #(into {} (d/touch (d/entity db %)))))))

(defn intention-as-of
  "What the bot was trying to do at time t: the newest intention whose latest fact by then is
   :started, as that fact; nil when it was doing nothing. A Datalog over the facts, so the same
   question works for any moment of a run, e.g. the tick before a death."
  [world t]
  (let [db (:world/facts world)
        latest (d/q '[:find ?id (max ?e) :in $ ?t
                      :where [?e :intention/id ?id] [?e :intention/at ?at] [(<= ?at ?t)]]
                    db t)
        open (for [[id e] latest :let [f (d/entity db e)] :when (= :started (:intention/event f))] [id f])]
    (when (seq open) (into {} (d/touch (second (apply max-key first open)))))))

(defn log-at? "Is the last thing seen at pos a log?" [world pos] (boolean (some-> (remembered world pos) blocks/log?)))

(defn trunk-bottom?
  "A remembered log with no remembered log below it: where a trunk meets the ground."
  [world [x y z]]
  (and (log-at? world [x y z]) (not (log-at? world [x (dec y) z]))))

(defn nearest-of
  "Nearest of positions within radius of eye, or nil."
  [eye radius positions]
  (->> positions
       (map (fn [p] [(physics/distance eye (physics/centre p)) p]))
       (filter (fn [[d _]] (<= d radius)))
       (sort-by first)
       first
       second))

(defn nearest
  "Nearest position whose latest observed state satisfies pred, within radius of eye, or nil.
   Crafting tables are one Datalog query (positions-now); other predicates scan the latest view."
  [world eye radius pred]
  (nearest-of eye radius
              (if (= pred crafting-table?)
                (positions-now world [crafting-table])
                (for [[p {:block/keys [state]}] (latest world) :when (pred state)] p))))

(defn nearest-log
  "Nearest bottom log on record within radius of eye, skipping blacklisted positions and logs
   more than 12 blocks above or below the eye; bottom? (world pos → bool, trunk-bottom? by
   default) says which logs count. nil when none. Candidates come from one Datalog query,
   positions-now: a log was seen there and nothing since says it is gone."
  ([world eye radius blacklist] (nearest-log world eye radius blacklist trunk-bottom?))
  ([world eye radius blacklist bottom?]
   (let [ey (second eye)]
     (nearest-of eye radius
                 (->> (positions-now world blocks/log-states)
                      (remove blacklist)
                      (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
                      (filter #(bottom? world %)))))))
