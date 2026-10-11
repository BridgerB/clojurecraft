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

   Water and lava are remembered as pools, one fact per chunk and fluid kind: {:pool/chunk
   [cx cz] :pool/kind :water|:lava :pool/pos [x y z] :pool/at ms}, the highest surface source
   nearest the chunk's centre (a real forest recording held 600,000 water cells and 65,000 at the
   surface in 213 chunks; a fact per cell would be the stone freeze again, see watched?). A
   pool fact is appended only when the chunk's answer changes. Where water or lava is beside
   the body right now is the terrain's question, never memory's.

   The server's answers to the bot's own actions are facts too: {:answer/kind :ack|:pickup|:death
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
   :answer/kind {:db/index true}
   :pool/chunk {:db/index true}
   :pool/kind {:db/index true}})

(defn empty-facts "A store with nothing seen yet." [] (d/empty-db schema))

(def crafting-table (first (keep (fn [[n _ lo]] (when (= n :crafting_table) lo)) blocks/table)))

(defn crafting-table? "Is this state a crafting table?" [id] (= id crafting-table))

(defn placed-state
  "The block state a placed item turns into, for the blocks the bot places today."
  [item]
  (case item
    :crafting_table crafting-table
    nil))

(defn watched?
  "Does seeing this state start a record? Logs and crafting tables: things rare enough to
   remember after their chunk is gone. Stone is not: ten chunks hold 120,000 of it, and
   recording them made every chunk load and every planner tick take seconds (gym batch
   cobble-a1, 2026-10-10: eleven of twelve bots timed out). What is exposed now is the
   terrain's question, not memory's."
  [id]
  (or (blocks/log? id) (crafting-table? id)))

;; ---------------------------------------------------------------- reading

(defn latest-entity
  "The newest observation at pos, as a DataScript entity, or nil."
  [db pos]
  (when-let [datoms (seq (d/datoms db :avet :sight/pos pos))]
    (d/entity db (apply max (map :e datoms)))))

(defn remembered
  "The last state seen at pos, or nil."
  [world pos]
  (:sight/state (latest-entity (:world/facts world) pos)))

(defn history
  "Every observation at pos, oldest first: [{:block/state id :block/seen-at ms} ...]."
  [world pos]
  (let [db (:world/facts world)]
    (->> (d/datoms db :avet :sight/pos pos)
         (map :e)
         sort
         (mapv #(let [e (d/entity db %)]
                  {:block/state (:sight/state e) :block/seen-at (:sight/at e)})))))

(defn latest
  "The latest observation at every position on record: {pos {:block/state id :block/seen-at ms}}."
  [world]
  (let [db (:world/facts world)]
    (into {}
          (for [[pos e] (d/q '[:find ?pos (max ?e) :where [?e :sight/pos ?pos]] db)
                :let [ent (d/entity db e)]]
            [pos {:block/state (:sight/state ent) :block/seen-at (:sight/at ent)}]))))

(defn positions-now
  "Positions where one of states was seen and nothing seen there since is outside states: the
   essay's \"the nearest log I have ever seen that I have not confirmed gone\", as one Datalog
   query over the facts (no later fact at the place with a state outside the set)."
  [world states]
  (d/q '[:find [?pos ...] :in $ [?s ...] ?all :where [?e :sight/state ?s] [?e :sight/pos ?pos]
         (not-join
          [?e ?pos ?all]
          [?later :sight/pos ?pos]
          [(> ?later ?e)]
          [?later :sight/state ?t]
          [(contains? ?all ?t) ?kept]
          [(false? ?kept)])]
       (:world/facts world)
       states
       (set states)))

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
        tx (keep (fn [[x y z id]] (observation db [x y z] id now))
                 (chunk/find-blocks {key column} watched?))]
    (cond-> world (seq tx) (assoc :world/facts (d/db-with db tx)))))

(defn surface-fluid?
  "Is the fluid at [x y z] of a column (its fluid states, {[x y z] id}) exposed: nothing of its
   kind directly above it?"
  [fluids [x y z] kind]
  (not= kind (:fluid/kind (blocks/fluid (get fluids [x (inc y) z])))))

(defn pool-of
  "The pool fact for one fluid kind in a freshly loaded column: its highest surface source,
   nearest the chunk's centre among those, or nil when the chunk holds none. Highest first
   because the pool under the sky is the one to walk to; cave water is the aquifer trap."
  [[cx cz] fluids kind now]
  (let [[mx mz] [(+ (* 16 cx) 8) (+ (* 16 cz) 8)]
        cells (for [[[x y z :as pos] id] fluids
                    :when (and (= kind (:fluid/kind (blocks/fluid id)))
                               (blocks/source? id)
                               (surface-fluid? fluids pos kind))]
                [[(- y) (+ (abs (- x mx)) (abs (- z mz)))] pos])]
    (when (seq cells)
      {:pool/chunk [cx cz]
       :pool/kind kind
       :pool/pos (second (first (sort-by first cells)))
       :pool/at now})))

(defn latest-pool
  "The newest pool fact for a chunk and kind, as an entity, or nil."
  [db key kind]
  (let [es (d/q '[:find [?e ...] :in $ ?c ?k :where [?e :pool/chunk ?c] [?e :pool/kind ?k]]
                db
                key
                kind)]
    (when (seq es) (d/entity db (apply max es)))))

(defn remember-pools
  "Append a pool fact per fluid kind the loaded column holds, when the chunk's pool changed."
  [world key column]
  (let [db (:world/facts world)
        fluids (into {}
                     (for [[x y z id] (chunk/find-blocks {key column} blocks/fluid)] [[x y z] id]))
        tx (for [kind [:water :lava]
                 :let [pool (pool-of key fluids kind (:time/now world))]
                 :when (and pool (not= (:pool/pos pool) (:pool/pos (latest-pool db key kind))))]
             pool)]
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
  (cond-> {:intention/id (:intent/id i)
           :intention/event event
           :intention/kind (:intent/kind i)
           :intention/at now}
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
        latest (d/q '[:find ?id (max ?e) :in $ ?t :where [?e :intention/id ?id]
                      [?e :intention/at ?at] [(<= ?at ?t)]]
                    db
                    t)
        open (for [[id e] latest
                   :let [f (d/entity db e)]
                   :when (= :started (:intention/event f))]
               [id f])]
    (when (seq open) (into {} (d/touch (second (apply max-key first open)))))))

(defn log-at?
  "Is the last thing seen at pos a log?"
  [world pos]
  (boolean (some-> (remembered world pos)
                   blocks/log?)))

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
  (nearest-of eye
              radius
              (if (= pred crafting-table?)
                (positions-now world [crafting-table])
                (for [[p {:block/keys [state]}] (latest world) :when (pred state)] p))))

(defn pools
  "Every chunk's latest pool of a kind: {[cx cz] [x y z]}."
  [world kind]
  (let [db (:world/facts world)]
    (into {}
          (for [[c e] (d/q '[:find ?c (max ?e) :in $ ?k :where [?e :pool/kind ?k]
                             [?e :pool/chunk ?c]]
                           db
                           kind)]
            [c (:pool/pos (d/entity db e))]))))

(def surface-water-below 5)           ; a pool deeper under the eye than this is cave water: never dive for it (steve's aquifer trap)

(defn nearest-surface-water
  "The nearest remembered water pool within radius of eye and no more than surface-water-below
   under it, or nil: where a bucket could be filled without diving for it."
  [world eye radius]
  (let [ey (second eye)]
    (nearest-of eye
                radius
                (filter (fn [[_ y _]] (>= y (- ey surface-water-below)))
                        (vals (pools world :water))))))

(defn nearest-log
  "Nearest bottom log on record within radius of eye, skipping blacklisted positions and logs
   more than 12 blocks above or below the eye; bottom? (world pos → bool, trunk-bottom? by
   default) says which logs count. nil when none. Candidates come from one Datalog query,
   positions-now: a log was seen there and nothing since says it is gone."
  ([world eye radius blacklist] (nearest-log world eye radius blacklist trunk-bottom?))
  ([world eye radius blacklist bottom?]
   (let [ey (second eye)]
     (nearest-of eye
                 radius
                 (->> (positions-now world blocks/log-states)
                      (remove blacklist)
                      (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
                      (filter #(bottom? world %)))))))
