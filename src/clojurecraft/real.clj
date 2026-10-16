(ns clojurecraft.real
  "Real failures as snapshots (issue #299): a real race's recording folded with the code that
   made it, a moment kept from before the choice that failed exactly as a sim run keeps one
   (simrace/decision, simrace/chosen-moment), and the server model joined to the bot's world
   there (sim/joined), so a real failure is the same :snapshot/* value a sim run's is and a fix's
   check judges it the same way (simrace/judged, simrace/recurs?).

     clojure -M:simrace --from-recording s2/Clj_r17.edn[.gz|.zst] [--signature '[:fortress :no-path]']
       [--out data/real] [--name real-Clj_r17.edn.gz] [--seed 1] [--round 38929353563]

   The recording is the evidence: every event the bot folded, and so every column the server
   sent it, byte for byte (a captured world is nothing more, capture.clj), and every block that
   changed since (the bot's own overlay, :world/blocks). A fold is the run again only with the
   reducer that made the recording (issue #111): the race's churn converts on the runner at the
   commit that played the race, so the fold is exact there. What the bot never heard the model
   cannot know: the time of day (set-time is decoded fieldless; day-time-of guesses), what a
   closed furnace holds, the hunger clock, where a death respawns, the entities it does not model.
   So a real snapshot is kept only when it recurs against the model with the same code; one that
   does not is a model gap, printed with what the bot did instead (gap), never filed as evidence."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.failure :as failure]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.record :as record]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]
            [datascript.core :as d]))

(set! *warn-on-reflection* true)

(def radius capture/default-radius)   ; chunks round the bot the model holds, on each axis: a captured world's square
(def default-seed 1)                  ; the model's loot and tick rolls at a real moment (the bot's own came from the real clock)
(def noon 6000)                       ; the time of day a moment is joined at when nothing says night (day-time-of)
(def midnight 18000)                  ; the time of day when an undead stands unburnt under the open sky
(def default-out "data/real")         ; where --from-recording writes a snapshot that recurs (gaps under gaps/)

;; ---------------------------------------------------------------- the fold

(defn start
  "A fold before the recording's first line: the bot's world as a replay begins it
   (main/replayed: the :start event names the connection), no columns, no moments."
  []
  {:world (game/init {:host "replay" :port 0 :name "Clj_replay"}) :columns {} :moments []})

(defn joinable?
  "Can the model be joined to the bot at world: in play, given its goals (:go came: a plan
   began), loaded, placed, alive, the socket open, no container open and nothing on the cursor?
   A container's window lives in the server the model never was, so a moment with one open is not
   kept: the model could not answer its clicks."
  [world]
  (boolean (and (= :play (:bot/phase world))
                (:plan/status world)
                (:player/loaded? world)
                (:player/pos world)
                (not (:player/dead? world))
                (not (:bot/closed world))
                (nil? (:window/open world))
                (nil? (:window/cursor world)))))

(defn fold-step
  "The fold one line of a recording further: an event folded into the bot's world (simrace/step,
   the live run's reducer, its effects dropped as record/replay drops them); the bytes of every
   column the server sent since the bot came into its dimension, the latest of each (a dimension
   change drops them, as it drops the bot's columns); after a tick the bot is joinable? at, a
   moment {:t :world :columns} kept every sim/moment-every-ms, the last sim/moment-keep of them
   (sim/kept, six minutes, as a sim run keeps its moments), :t the next tick's time; the
   recording's header commit as :commit and its RESULT as :result."
  [{:keys [world columns moments] :as acc} line]
  (cond (:record/commit line) (assoc acc :commit (:record/commit line))
        (:record/result line) (assoc acc :result (:record/result line))
        (nil? (:event/kind line)) acc
        :else
        (let [w (assoc (simrace/step world line) :bot/effects [])
              p (:event/packet line)
              columns (cond (not= (dimension/of w) (dimension/of world)) {}
                            (= :level-chunk-with-light (:packet/name p))
                            (assoc columns [(:x p) (:z p)] (:data p))
                            :else columns)
              m (when (and (= :tick (:event/kind line)) (joinable? w))
                  {:t (+ (:time/now w) physics/tick-ms) :world w :columns columns})]
          (assoc acc
                 :world w
                 :columns columns
                 :moments (cond-> moments m (sim/kept m sim/moment-every-ms))))))

(defn fold
  "A recording's lines (events, effects lines, the header and the RESULT) folded from start
   (fold-step): {:world the last :columns :moments :commit :result}."
  [lines]
  (reduce fold-step (start) lines))

;; ---------------------------------------------------------------- the server at a moment, from the bot's world

(defn worn
  "An item as the model wears it (sim/wear counts :durability down): a tool the server said is
   damaged (its :damage component) has that much less than its maximum left."
  [{:keys [item components] :as it}]
  (let [tool (blocks/tool item)
        dmg (:damage components)]
    (cond (and tool dmg)
          (assoc it
                 :durability
                 (- (or (:max_damage components) (:durability (blocks/materials (:tier tool))))
                    dmg))
          :else it)))

(defn window-0
  "Window 0 as the server holds it, {slot item}, from what the bot was told: each inventory item
   at the slot window 0 shows it (inventory/player->window-slot; the armour head to feet from
   the layout's :armour) and the crafting grid's cells (never its result: the server derives it)."
  [world]
  (let [layout (inventory/layouts :inventory)
        slot-of (fn [p]
                  (or (inventory/player->window-slot layout p)
                      (when (<= (- inventory/head-slot 3) p inventory/head-slot)
                        (+ (:armour layout) (- inventory/head-slot p)))))]
    (merge (dissoc (:window/grid world) inventory/result-slot)
           (into {}
                 (keep (fn [[p it]] (when-let [s (slot-of p)] [s (worn it)])))
                 (:player/inventory world)))))

(defn mobs-of
  "The mobs the bot tracks that the model carries (mob/kinds), alive, {eid {:type :pos :health}}:
   where the server last put them and their health as it told it, else their kind's full health."
  [world]
  (into {}
        (keep (fn [[eid e]]
                (let [k (entity/kind e)]
                  (when (and (mob/kinds k) (not (entity/dead? e)) (:entity/pos e))
                    [eid
                     {:type k
                      :pos (mapv double (:entity/pos e))
                      :health (double (or (:entity/health e) (:health (mob/kinds k))))}]))))
        (:world/entities world)))

(defn items-of
  "The dropped items the bot tracks whose stack it was told (:entity/item), {eid {:pos :item}},
   one of each: the model hands them over as it would its own drops."
  [world]
  (into {}
        (keep (fn [[eid e]]
                (when (and (entity/item? e) (:entity/item e) (:entity/pos e))
                  [eid
                   {:pos (mapv double (:entity/pos e)) :item {:item (:entity/item e) :count 1}}])))
        (:world/entities world)))

(defn cell-of "The block a point is in." [pos] (mapv #(long (math/floor %)) pos))

(defn day-time-of
  "The time of day to join the model at, which the bot never decodes (set-time's spec is
   fieldless): midnight when an undead the bot tracks stands under the open sky with its flags
   told and not burning (the sun would have lit it), else noon. A guess the failure must survive:
   a real snapshot that does not recur is a gap, never evidence."
  [world]
  (if (some (fn [e]
              (and (:undead (mob/kinds (entity/kind e)))
                   (not (entity/dead? e))
                   (contains? e :entity/flags)
                   (not (entity/on-fire? e))
                   (:entity/pos e)
                   (terrain/under-sky? world (cell-of (:entity/pos e)))))
            (vals (:world/entities world)))
    midnight
    noon))

(defn reach
  "How many chunks round here the bot holds columns: the farthest held column on either axis, at
   least 1; the model streams by it as the real server streamed them (sim/stream)."
  [held [hx hz]]
  (reduce max 1 (map (fn [[x z]] (max (abs (- x hx)) (abs (- z hz)))) held)))

(defn landing-of
  "The landing the bot was given (:plan/go-at, race/land-bot!'s :go/at), in blocks as a captured
   world names one ([x z y]); where it stands when it was given none."
  [world]
  (let [[x y z] (or (:plan/go-at world) (:player/pos world))] (cell-of [x z y])))

(defn model
  "The server model joined to the bot at moment m (sim/joined): the columns the recording had
   sent by then within radius chunks of the bot (the bot's own held ones as it decoded them, the
   rest decoded here), the overlay's blocks the server said (a guess of the bot's own dig,
   :world/unconfirmed, is not its word) as its changes since, window 0, health, food, saturation
   and air, the held place and the state id as last told, the mobs and items it carries under the
   bot's own ids and the rest of what the bot tracks told gone, in the bot's dimension at
   difficulty, the time of day guessed (day-time-of), rolling from seed, a death respawning on the
   landing (the real spawn is not in the recording)."
  [{:keys [world columns]} {:keys [seed difficulty]}]
  (let [pos (mapv double (:player/pos world))
        [x _ z] (cell-of pos)
        cols (capture/within columns [x z] radius)
        held (:world/chunks world)
        decoded (into {} (map (fn [[k bytes]] [k (or (get held k) (sim/decode-once bytes))])) cols)
        mobs (mobs-of world)
        items (items-of world)
        [lx lz ly] (landing-of world)
        landing [(+ lx 0.5) (double ly) (+ lz 0.5)]
        here (sim/column-key pos)]
    (sim/joined
     (cond-> {:columns cols
              :decoded decoded
              :spawn landing
              :respawn landing
              :seed seed
              :tick-seed seed
              :difficulty difficulty
              :day-time (day-time-of world)
              :dimension (dimension/of world)
              :inventory (window-0 world)}
       (:player/food world) (assoc :food (long (:player/food world))))
     {:now (:time/now world)
      :pos pos
      :look (mapv double (:player/look world))
      :on-ground? (:player/on-ground? world)
      :held (:player/held-slot world 0)
      :state-id (:window/state-id world 0)
      :health (double (:player/health world sim/max-health))
      :saturation (some-> (:player/saturation world)
                          double)
      :air (:player/air world sim/max-air)
      :player-eid (:player/entity-id world)
      :placed (into {}
                    (filter (fn [[p _]]
                              (and (terrain/confirmed? world p)
                                   (contains? cols (sim/column-key p)))))
                    (:world/blocks world))
      :items items
      :mobs mobs
      :view (into #{} (filter #(contains? cols %)) (keys held))
      :view-distance (reach (keys held) here)
      :gone (remove #(or (contains? mobs %) (contains? items %)) (keys (:world/entities world)))})))

;; ---------------------------------------------------------------- the snapshot

(defn snapshot
  "A real failure's moment m (simrace/chosen-moment's, with its :moment) as the value a sim run's
   is (simrace/snapshot): the bot's world (its chunks reattached from the columns on reading,
   the facts d/serializable), the joined model sim without its columns, what the next tick
   depends on (nothing pending: the real server had taken the bot's packets; go long sent; the
   tick seed at seed), the seed, the target, the code, the signature and the moment; and, in
   place of a captured world's file, the model's columns themselves (:snapshot/columns, bytes as
   the server sent them) and the landing, so it plays with nothing restored (simrace/own-world).
   :snapshot/source :real, with the recording, bot and round it came from."
  [{:keys [world moment t]} sim {:keys [seed until commit signature recording bot round]}]
  (cond-> {:snapshot/source :real
           :snapshot/landing (landing-of world)
           :snapshot/columns (:sim/columns sim)
           :snapshot/seed seed
           :snapshot/until until
           :snapshot/commit commit
           :snapshot/signature signature
           :snapshot/moment moment
           :snapshot/next {:pending [] :t t :rand seed :went? true :inflight []}
           :snapshot/chunks (set (keys (:world/chunks world)))
           :snapshot/world (-> world
                               (dissoc :world/chunks)
                               (update :world/facts d/serializable))
           :snapshot/sim (dissoc sim :sim/columns :sim/decoded)}
    recording (assoc :snapshot/recording recording)
    bot (assoc :snapshot/bot bot)
    round (assoc :snapshot/round round)))

(defn convert
  "A folded recording (fold) as a real snapshot of the failure with signature in opts: its moment
   chosen as a sim run's is (simrace/decision over the last world, simrace/chosen-moment over the
   moments kept: the newest at least decision-lead-ms before the choice that failed), the model
   joined there (model). nil when no moment was kept (the bot was never joinable)."
  [{:keys [world moments]} {:keys [signature] :as opts}]
  (when (seq moments)
    (let [m (simrace/chosen-moment moments
                                   (peek moments)
                                   (simrace/decision world signature)
                                   (:time/now world))]
      (snapshot m (model m opts) opts))))

(defn gap
  "How a real snapshot that does not recur (simrace/recurs?) got past its failure against the
   model, a kind: :halted (the judge ran out of wall time: the check counts that as recurring,
   but it is no sight of the failure); :crashed; :reached (the plan done, or the signature's
   stage reached); :other (it failed, with another signature: the model answered otherwise);
   :active (still playing when the window ended); nil when it recurs."
  [snap played]
  (cond (:run/halted? played) :halted
        (simrace/recurs? snap played) nil
        (= :crashed (:reason played)) :crashed
        (or (:ok played)
            (= :done (get-in played [:plan :plan/status]))
            (contains? (set (get-in played [:plan :plan/reached])) (simrace/stage-of snap)))
        :reached
        (= :failed (get-in played [:plan :plan/status])) :other
        :else :active))

;;;; I/O: the recording, the snapshot file, the lines ;;;;

(defn bot-of
  "The bot a recording's file is: its name before the first dot (Clj_r17.edn.zst is Clj_r17)."
  [path]
  (first (str/split (.getName (io/file (str path))) #"\.")))

(defn from-recording!
  "Fold the recording at path a line at a time (record/reduce-entries, fold), keep its failure's
   moment as a real snapshot (convert), write it, read it back as the check will, and judge it at
   once against the model with this code (simrace/judged, with judge!'s wall-clock budget): the
   code that played the race, when the race's churn runs it on the runner (issue #299). When it
   recurs it stays at <out>/<name> and a REAL line says so; when it does not, it moves to
   <out>/gaps/<name> and a GAP line says how it got past (gap): a failure the model does not
   reproduce is a finding about the model, never evidence. STORY lines say what the bot did over
   the window either way. o: the command line's options, --signature (else the recording's
   RESULT's), --out, --name, --seed, --round, --bot. Returns {:path :recurs? :gap}."
  [path o]
  (let [t0 (System/currentTimeMillis)
        folded (record/reduce-entries fold-step (start) path)
        result (:result folded)
        bot (or (:bot o) (bot-of path))
        signature (or (some-> (:signature o)
                              edn/read-string)
                      (some-> result
                              failure/mark
                              :run/signature))
        file-name (or (:name o) (str "real-" bot ".edn.gz"))
        out (or (:out o) default-out)
        file (str (io/file out file-name))
        snap (when signature
               (convert folded
                        {:seed (or (some-> (:seed o)
                                           parse-long)
                                   default-seed)
                         :until (:until result simrace/default-until)
                         :commit (:commit folded)
                         :signature signature
                         :difficulty simrace/difficulty
                         :recording (str path)
                         :bot bot
                         :round (:round o)}))
        t1 (System/currentTimeMillis)
        line {:recording (str path) :bot bot :signature signature :fold-ms (- t1 t0)}]
    (cond (nil? snap)
          (let [kind (if signature :no-moment :no-signature)]
            (println "GAP" (pr-str (assoc line :gap kind)))
            {:path nil :recurs? false :gap kind})
          :else
          (let [_ (capture/write-world! file snap)
                back (capture/read-world file)
                r (simrace/judged back
                                  (simrace/own-world back)
                                  #(> (- (System/currentTimeMillis) t1) simrace/judge-wall-ms))
                kind (gap back r)
                kept (if kind (str (io/file out "gaps" file-name)) file)]
            (when kind (io/make-parents kept) (.renameTo (io/file file) (io/file kept)))
            (println (if kind "GAP" "REAL")
                     (pr-str (cond-> (assoc line
                                            :path kept
                                            :bytes (.length (io/file kept))
                                            :moment (:snapshot/moment back)
                                            :recurs? (nil? kind)
                                            :run/signature (:run/signature r)
                                            :plan/status (get-in r [:plan :plan/status])
                                            :sim-ms (- (:run/ms r) (:run/from r))
                                            :wall-ms (- (System/currentTimeMillis) t0))
                               kind (assoc :gap kind))))
            (doseq [s (:run/story r)] (println "STORY" kept (pr-str s)))
            (flush)
            {:path kept :recurs? (nil? kind) :gap kind}))))
