(ns clojurecraft.memory
  "What the bot has seen, as facts with time.

   The store is a DataScript database held as a value in the world (:world/facts). Each
   observation is one fact entity {:sight/pos [x y z] :sight/state id :sight/at ms}, appended
   only when what is seen at a position differs from the last observation there. Nothing is
   ever retracted: a block later seen as air is a newer fact, and the history stays queryable
   (`history`). The latest observation at a position is the one with the highest entity id.

   A position is a place in one dimension: each observation also says where, :sight/dimension
   (a fact without one, written before the bot knew dimensions, is the overworld's), and every
   question is asked in the dimension the bot is in now (dimension/of), so the Nether's [4 61 147]
   is never the overworld's (ruststeve read Nether brick 64 blocks off through overworld columns).
   Pools likewise carry :pool/dimension.

   Only block kinds that `watched?` accepts start a record (logs, tables and furnaces, a
   fortress's bricks, and in the Nether a spawner); a position already on record is followed
   through every change, so a dug log becomes air.

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
            [clojurecraft.dimension :as dimension]
            [clojurecraft.memo :as memo]
            [clojurecraft.physics :as physics]
            [datascript.core :as d]))

(set! *warn-on-reflection* true)

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

(def placed-kinds
  "The blocks the bot places and comes back to (a table to craft at, a furnace to smelt in):
   remembered wherever they are seen, like logs."
  #{:crafting_table :furnace})

(def landmark-kinds
  "Blocks that say where something is: a fortress (its bricks, fences and stairs), a lit portal,
   the way back (issue #11), and an end portal frame, the stronghold's portal room (issue #265:
   the room is found in the chunk data as its chunk loads, however deep), and the lit end
   portal, the way to the End (issue #266: the :end-portal goal is done when one is on record).
   Only blocks a forest never generates, so watching them changes nothing a forest run
   remembers (obsidian and spawners do: the wood recording's 1098 sightings became 1121); a
   frame is only ever in a stronghold."
  #{:nether_bricks :nether_brick_fence :nether_brick_stairs :nether_portal :end_portal_frame
    :end_portal})

(def nether-landmark-kinds
  "Blocks that say where something is only in the Nether: a spawner there is a fortress's, a blaze
   spawner (issue #262: the rods come from it), or a bastion's rare magma cube one. In the
   overworld a spawner is a dungeon's or a mineshaft's, and watching it there would change what
   a forest run remembers (landmark-kinds: the wood recording's sightings)."
  #{:spawner})

(defn watched?
  "Does seeing this state start a record? Logs, the placed-kinds and the landmark-kinds: things
   rare enough to remember after their chunk is gone. Stone is not: ten chunks hold 120,000 of it, and
   recording them made every chunk load and every planner tick take seconds (gym batch
   cobble-a1, 2026-10-10: eleven of twelve bots timed out). What is exposed now is the
   terrain's question, not memory's. Seen in the dimension dim, the nether-landmark-kinds too
   when it is the Nether."
  ([id]
   (or (blocks/log? id)
       (let [n (blocks/name-of id)] (or (contains? placed-kinds n) (contains? landmark-kinds n)))))
  ([dim id]
   (or (watched? id)
       (and (= dimension/nether dim) (contains? nether-landmark-kinds (blocks/name-of id))))))

;; ---------------------------------------------------------------- reading

(defn sight-dimension
  "The dimension an observation entity e was seen in: the overworld for one written before
   observations said."
  [e]
  (:sight/dimension e dimension/overworld))

(defn sightings-at
  "The observations at pos in dimension dim, as entity ids, oldest first."
  [db dim pos]
  (->> (d/datoms db :avet :sight/pos pos)
       (map :e)
       (filter #(= dim (sight-dimension (d/entity db %))))
       sort))

(defn latest-entity
  "The newest observation at pos in dimension dim, as a DataScript entity, or nil."
  [db dim pos]
  (some->> (seq (sightings-at db dim pos))
           last
           (d/entity db)))

(defn remembered
  "The last state seen at pos in the bot's dimension, or nil."
  [world pos]
  (:sight/state (latest-entity (:world/facts world) (dimension/of world) pos)))

(defn history
  "Every observation at pos in the bot's dimension, oldest first:
   [{:block/state id :block/seen-at ms} ...]."
  [world pos]
  (let [db (:world/facts world)]
    (mapv #(let [e (d/entity db %)] {:block/state (:sight/state e) :block/seen-at (:sight/at e)})
          (sightings-at db (dimension/of world) pos))))

(def in-dimension
  "The Datalog rule (in-dimension ?e ?dim): observation ?e was seen in ?dim, an observation
   without :sight/dimension in the overworld."
  '[[(in-dimension ?e ?dim) [(get-else $ ?e :sight/dimension "minecraft:overworld") ?d]
     [(= ?d ?dim)]]])

(defn latest
  "The latest observation at every position on record in the bot's dimension:
   {pos {:block/state id :block/seen-at ms}}."
  [world]
  (let [db (:world/facts world)]
    (into {}
          (for [[pos e] (d/q '[:find ?pos (max ?e) :in $ % ?dim :where [?e :sight/pos ?pos]
                               (in-dimension ?e ?dim)]
                             db
                             in-dimension
                             (dimension/of world))
                :let [ent (d/entity db e)]]
            [pos {:block/state (:sight/state ent) :block/seen-at (:sight/at ent)}]))))

(def positions-in
  "[facts states dimension] → the positions-now of those facts: a Datalog query over every
   sighting, remembered by the facts' identity (memo/by): facts change only when a sighting is
   added, and the planner asked this every tick (a race's profile: a fifth of all its CPU)."
  (memo/by
   [:identical :equal :equal]
   64
   (fn [facts states dim]
     (d/q '[:find [?pos ...] :in $ % [?s ...] ?all ?dim :where [?e :sight/state ?s]
            [?e :sight/pos ?pos] (in-dimension ?e ?dim)
            (not-join
             [?e ?pos ?all ?dim]
             [?later :sight/pos ?pos]
             [(> ?later ?e)]
             (in-dimension ?later ?dim)
             [?later :sight/state ?t]
             [(contains? ?all ?t) ?kept]
             [(false? ?kept)])]
          facts
          in-dimension
          states
          (set states)
          dim))))

(defn positions-now
  "Positions where one of states was seen and nothing seen there since is outside states: the
   essay's \"the nearest log I have ever seen that I have not confirmed gone\", as one Datalog
   query over the facts (no later fact at the place with a state outside the set), remembered by
   the facts (positions-in)."
  [world states]
  (positions-in (:world/facts world) states (dimension/of world)))

;; ---------------------------------------------------------------- writing

(defn observation
  "The fact to append for seeing id at pos in dimension dim, or nil when it adds nothing (same as
   the last there)."
  [db dim pos id now]
  (let [last (latest-entity db dim pos)]
    (when (and (not= id (:sight/state last)) (or last (watched? dim id)))
      {:sight/pos pos :sight/state id :sight/at now :sight/dimension dim})))

(defn remember-column
  "Record every watched block in a freshly loaded chunk column, and any change at a position
   already on record within it."
  [world key column]
  (let [db (:world/facts world)
        now (:time/now world)
        dim (dimension/of world)
        tx (keep (fn [[x y z id]] (observation db dim [x y z] id now))
                 (chunk/find-blocks {key column} #(watched? dim %)))]
    (cond-> world (seq tx) (assoc :world/facts (d/db-with db tx)))))

(def source-states "The states of a water or a lava source." #{blocks/water-base blocks/lava-base})

(defn surface-fluid?
  "Is the fluid at [x y z] exposed: nothing of its kind directly above it, as at (a cell → its
   state) reads the column?"
  [at [x y z] kind]
  (not= kind (:fluid/kind (blocks/fluid (at [x (inc y) z])))))

(defn pool-of
  "The pool fact for one fluid kind in a freshly loaded column, from its sources ([x y z id])
   and at (a cell → its state): its highest surface source,
   nearest the chunk's centre among those, or nil when the chunk holds none. Highest first
   because the pool under the sky is the one to walk to; cave water is the aquifer trap."
  [[cx cz] sources at kind now dim]
  (let [[mx mz] [(+ (* 16 cx) 8) (+ (* 16 cz) 8)]
        cells (for [[x y z id] sources
                    :let [pos [x y z]]
                    :when (and (= kind (:fluid/kind (blocks/fluid id)))
                               (surface-fluid? at pos kind))]
                {:rank [(- y) (+ (abs (- x mx)) (abs (- z mz)))] :pos pos})]
    (when (seq cells)
      {:pool/chunk [cx cz]
       :pool/kind kind
       :pool/pos (:pos (first (sort-by :rank cells)))
       :pool/at now
       :pool/dimension dim})))

(def pool-in-dimension
  "The Datalog rule (pool-in-dimension ?e ?dim): pool fact ?e is of ?dim, one without
   :pool/dimension of the overworld."
  '[[(pool-in-dimension ?e ?dim) [(get-else $ ?e :pool/dimension "minecraft:overworld") ?d]
     [(= ?d ?dim)]]])

(defn latest-pool
  "The newest pool fact for a chunk and kind in dimension dim, as an entity, or nil."
  [db dim key kind]
  (let [es (d/q '[:find [?e ...] :in $ % ?c ?k ?dim :where [?e :pool/chunk ?c] [?e :pool/kind ?k]
                  (pool-in-dimension ?e ?dim)]
                db
                pool-in-dimension
                key
                kind
                dim)]
    (when (seq es) (d/entity db (apply max es)))))

(defn remember-pools
  "Append a pool fact per fluid kind the loaded column holds, when the chunk's pool changed."
  [world key column]
  (let [db (:world/facts world)
        sources (chunk/find-blocks {key column} source-states) ; the sources alone: an ocean's every water cell was a map entry
        at #(chunk/block-at {key column} %)
        dim (dimension/of world)
        tx (for [kind [:water :lava]
                 :let [pool (pool-of key sources at kind (:time/now world) dim)]
                 :when (and pool (not= (:pool/pos pool) (:pool/pos (latest-pool db dim key kind))))]
             pool)]
    (cond-> world (seq tx) (assoc :world/facts (d/db-with db tx)))))

(defn observe
  "A single block changed: append the fact if it is watched or the position is on record."
  [world pos id]
  (let [db (:world/facts world)]
    (if-let [fact (observation db (dimension/of world) pos id (:time/now world))]
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

(defn remember-site
  "Append the site of a build, its frame origin and facing, as a fact stamped with now: chosen once,
   the latest is the one a build resumes at (#8)."
  [world origin facing]
  (update world
          :world/facts
          d/db-with
          [{:site/origin origin :site/facing facing :site/at (:time/now world)}]))

(defn give-up-site
  "Append that the build site whose frame origin is origin is given up, stamped with now: no way
   to it was found, and the next site is chosen elsewhere (build/given-up?)."
  [world origin]
  (update world :world/facts d/db-with [{:site/given-up origin :site/at (:time/now world)}]))

;; ---------------------------------------------------------------- questions

(defn site
  "The latest build site remembered, {:site/origin [x y z] :site/facing f}, or nil."
  [world]
  (let [db (:world/facts world)]
    (when-let [datoms (seq (d/datoms db :aevt :site/origin))]
      (select-keys (d/entity db (apply max (map :e datoms))) [:site/origin :site/facing]))))


(defn given-up-sites
  "The frame origins of every build site given up (give-up-site), as a set; empty with no facts."
  [world]
  (if-let [db (:world/facts world)]
    (set (d/q '[:find [?o ...] :where [_ :site/given-up ?o]] db))
    #{}))

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

(defn last-done
  "The newest :done fact of an intention of kind k (:walk), as a map of :intention/* keys; nil
   when none is. A Datalog over the facts: where the last walk that arrived ended is ground the
   bot stood on."
  [world k]
  (let [db (:world/facts world)
        e (d/q '[:find (max ?e) . :in $ ?k :where [?e :intention/kind ?k]
                 [?e :intention/event :done]]
               db
               k)]
    (when e (into {} (d/touch (d/entity db e))))))

(defn newest-dones
  "The newest n :done facts of an intention of kind k, newest first, as maps of :intention/*
   keys (last-done is the first): where the bot's last walks arrived, in the order it arrived
   there, is where it stands and where it just came from (pearls/roam's revisit)."
  [world k n]
  (let [db (:world/facts world)]
    (->> (d/q '[:find [?e ...] :in $ ?k :where [?e :intention/kind ?k] [?e :intention/event :done]]
              db
              k)
         (sort >)
         (take n)
         (mapv #(into {} (d/touch (d/entity db %)))))))

(defn arrived
  "The set of targets of every walk that arrived (the :done facts of :walk intentions): the
   cells the bot has already walked to. A walk back to one changes nothing the first did not
   (pearls/toward: gym batch pearls-warped3-a, plan run 39529384355, walked between the ends of
   two routes for twenty-five minutes)."
  [world]
  (set (d/q '[:find [?t ...] :where [?e :intention/kind :walk] [?e :intention/event :done]
              [?e :intention/target ?t]]
            (:world/facts world))))

(defn done-since
  "How many intentions of kind k ended :done after the newest :done of an intention of kind
   since (0 when none have), or nil when no intention of kind since has ever ended :done. The
   facts are appended in order, so a later fact is a greater entity: the lookouts stood since a
   burrow was cut are the :lookout dones after the :burrow done (pearls/stale?)."
  [world k since]
  (let [db (:world/facts world)
        b (d/q '[:find (max ?e) . :in $ ?k :where [?e :intention/kind ?k]
                 [?e :intention/event :done]]
               db
               since)]
    (when b
      (or (d/q '[:find (count ?e) . :in $ ?k ?b :where [?e :intention/kind ?k]
                 [?e :intention/event :done] [(> ?e ?b)]]
               db
               k
               b)
          0))))

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
               {:id id :fact f})]
    (when (seq open) (into {} (d/touch (:fact (apply max-key :id open)))))))

(defn log-at?
  "Is the last thing seen at pos a log?"
  [world pos]
  (boolean (some-> (remembered world pos)
                   blocks/log?)))

(defn trunk-bottom?
  "A remembered log with no remembered log below it: where a trunk meets the ground."
  [world [x y z]]
  (and (log-at? world [x y z]) (not (log-at? world [x (dec y) z]))))

(defn nearest-first
  "The positions within radius of eye, nearest first."
  [eye radius positions]
  (->> positions
       (map (fn [p] [(physics/distance eye (physics/centre p)) p]))
       (filter (fn [[d _]] (<= d radius)))
       (sort-by first)
       (map second)))

(defn nearest-of
  "Nearest of positions within radius of eye, or nil."
  [eye radius positions]
  (first (nearest-first eye radius positions)))

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
  "Every chunk's latest pool of a kind in the bot's dimension: {[cx cz] [x y z]}."
  [world kind]
  (let [db (:world/facts world)]
    (into {}
          (for [[c e] (d/q '[:find ?c (max ?e) :in $ % ?k ?dim :where [?e :pool/kind ?k]
                             [?e :pool/chunk ?c] (pool-in-dimension ?e ?dim)]
                           db
                           pool-in-dimension
                           kind
                           (dimension/of world))]
            [c (:pool/pos (d/entity db e))]))))

(def surface-water-below 5)           ; a pool deeper under the eye than this is cave water: never dive for it (steve's aquifer trap)

(defn nearest-surface-water
  "The nearest remembered water pool within radius of eye and no more than surface-water-below
   under it, or nil: where a bucket could be filled without diving for it."
  [world eye radius]
  (let [ey (physics/y-of eye)]
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
   (let [ey (physics/y-of eye)]
     (nearest-of eye
                 radius
                 (->> (positions-now world blocks/log-states)
                      (remove blacklist)
                      (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
                      ;; the radius before bottom?: it reads the log's column, a scan of the
                      ;; sightings, and a bot with thousands of logs remembered paid it for each
                      (filter #(<= (physics/distance eye (physics/centre %)) radius))
                      (filter #(bottom? world %)))))))
