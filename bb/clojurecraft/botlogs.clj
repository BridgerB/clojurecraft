(ns clojurecraft.botlogs
  "Every ended real bot's log, readable within one batch (LOOP.md section 4): a lane uploads its
   recordings only when it ends, five and a half hours on, so a batch's deaths and endings were
   counts until then. Each batch now digests the recording of every run that ended in it into a
   text log (the tick time, then each log line the bot said, each health change and each hit it
   took) and uploads one archive a batch to the release botlogs-<UTC day>-<hour>, asset
   <run>-r<runner>-b<batch>.tar.gz, beside the race.log lines of those runs.

   A recording is EDN lines, an event then the record of what it produced: #:event{:kind :tick
   :now ms ...}, #:event{:kind :packet :packet {...}}, #:record{:effects [...]}. The digest keeps
   the time of the last tick and writes a line for every :log effect, every :set-health packet and
   every :damage-event aimed at the bot's own entity."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def release-prefix "botlogs-")

(defn tag
  "The release a batch reported at now (an ISO instant) uploads its digests to:
   botlogs-<UTC day>-<hour>, so no release nears GitHub's 1000 assets."
  [now]
  (str release-prefix (subs now 0 10) "-" (subs now 11 13)))

(defn asset
  "The archive name of a batch: <run>-r<runner>-b<batch>.tar.gz, unique to one lane's batch."
  [run runner batch]
  (str run "-r" runner "-b" batch ".tar.gz"))

(defn field
  "The value after key k in an EDN line s, as text up to the next comma or closing brace, or nil."
  [s k]
  (some-> (re-find (re-pattern (str (java.util.regex.Pattern/quote k) " ([^,}]+)")) s)
          second
          str/trim))

(defn messages
  "The :log messages of a #:record line, in order."
  [s]
  (map second (re-seq #":kind :log, :message \"((?:[^\"\\]|\\.)*)\"" s)))

(defn table
  "{id name} of the generated {name id} table resources/clojurecraft/<n>.edn under the checkout
   root (the churn runs there), or {} when it cannot be read."
  [n]
  (or (try (into {}
                 (map (fn [[k v]] [v (name k)]))
                 (edn/read-string (slurp (str "resources/clojurecraft/" n ".edn"))))
           (catch Exception _ nil))
      {}))

(def damage-names
  "{id name} of the damage types (registry.clj's table)"
  (delay (table "damage-types")))
(def entity-names "{id name} of the entity types" (delay (table "entity-types")))

(defn hit-text
  "A hit on the bot as the digest says it: the damage type by name and the attacker, its kind and
   entity id (a damage-event's source ids are the entity id plus one, 0 for none), e.g. \"hit
   mob_attack by hoglin#1724\". types is {eid entity-type-id} of the entities added so far."
  [types s]
  (let [n (fn [k]
            (some-> (field s k)
                    parse-long))
        t (n ":source-type")
        by (let [d (n ":source-direct")
                 c (n ":source-cause")]
             (cond (and d (pos? d)) (dec d)
                   (and c (pos? c)) (dec c)))
        kind (some->> (get types by)
                      (get @entity-names))]
    (str "hit "
         (get @damage-names t (str "source-type " t))
         (when by (str " by " (or kind "?") "#" by)))))

(defn digest-line
  "[state out] after one recording line: state {:now ms :self eid} keeps the last tick's time and
   the bot's entity id (from :login) and :types, {eid type} of every entity added; out gains a text line for each log message, health change
   and hit on the bot itself."
  [[state out] s]
  (let [now (:now state)
        stamp (fn [txt] (str (or now "-") " " txt))]
    (cond (str/includes? s ":kind :tick") [(assoc state :now (field s ":now")) out]
          (str/includes? s ":packet/name :login,") [(assoc state :self (field s ":entity-id")) out]
          (str/includes? s ":packet/name :add-entity")
          [(assoc-in state
            [:types
             (some-> (field s ":entity-id")
                     parse-long)]
            (some-> (field s ":type")
                    parse-long)) out]
          (str/includes? s ":packet/name :set-health")
          [state (conj out (stamp (str "hp " (field s ":health") " food " (field s ":food"))))]
          (and (str/includes? s ":packet/name :damage-event")
               (= (:self state) (field s ":entity-id")))
          [state (conj out (stamp (hit-text (:types state) s)))]
          (str/includes? s ":kind :log") [state (into out (map stamp) (messages s))]
          :else [state out])))

(defn digest
  "The text lines of a recording's lines (digest-line over each, in order)."
  [lines]
  (second (reduce digest-line [{} []] lines)))

;; ---------------------------------------------------------------- what a batch saw: coverage, packets unheard

(def deliberate
  "Inbound packets the bot does not decode on purpose, by name, each with the reason: what a
   batch's unknown counts drop before the page flags the rest (unheard). A packet not here and not
   decoded is a fact the server sent and the bot threw away (the observability plan, part A)."
  {:bundle-delimiter "brackets a bundle of packets; carries nothing"
   :rotate-head "a mob's head yaw; its body's facing comes with its moves"
   :light-update "sky and block light; the bot reads no light"
   :chunk-batch-start "brackets a batch of chunks; chunk-batch-finished is answered"
   :recipe-book-add "the client's recipe book; recipes come from datagen"
   :recipe-book-remove "the client's recipe book"
   :recipe-book-settings "the client's recipe book"
   :update-recipes "the client's recipe display; recipes come from datagen"
   :update-advancements "advancement toasts"
   :commands "the command tree for tab completion"
   :server-data "the server's motd and icon"
   :initialize-border "the world border, thirty million blocks out"
   :change-difficulty "the difficulty; fixed by the server's properties"
   :ticking-state "the tick rate; the bot's clock is its own"
   :ticking-step "the tick rate's frozen steps"
   :waypoint "locator-bar waypoints of other players"
   :player-info-update "the tab list's players"
   :player-info-remove "the tab list's players"
   :set-player-team "scoreboard teams"
   :set-chunk-cache-center "the chunk the client centres its cache on"
   :set-default-spawn-position "the world spawn's compass target"
   :player-abilities "flying and creative flags; survival never changes them"
   :set-experience "experience points; nothing the run spends"
   :set-passengers "riders (a strider's, a jockey's)"
   :level-particles "particles drawn"
   :custom-payload "plugin channels (brand)"
   :registry-data "registry contents; the order of each (damage types) comes from datagen"
   :update-enabled-features "feature flags"
   :update-tags "block and item tags; datagen reads them"
   :select-known-packs "data packs known"})

(defn packet-names
  "{[phase id] name} of every inbound (server to client) packet in packets, the generated
   {phase {:s2c {name id}}} table: how a run's :stats/unknown keys are named."
  [packets]
  (into {} (for [[phase dirs] packets [n id] (:s2c dirs)] [[phase id] n])))

(defn unheard
  "{name n} of the packets the runs' RESULTs counted unknown (:stats/unknown, {[phase id] n}),
   named by names (packet-names; an id with no name keeps its [phase id]), less the deliberate
   ones: what the server said that the bot threw away."
  [names runs]
  (reduce (fn [m [k n]]
            (let [nm (get names k k)]
              (cond (contains? deliberate nm) m
                    :else (update m (if (keyword? nm) (name nm) (pr-str nm)) (fnil + 0) n))))
          {}
          (for [r runs kn (get-in r [:stats :stats/unknown])] kn)))

(defn packets
  "[decoded total] of the packets the runs' servers sent them: decoded is every packet a RESULT
   counted by name (:stats/heard), total adds the unknown ones (:stats/unknown, the deliberate
   included). A run from before :stats/heard counts no decoded packet, so a batch's share reads 0."
  [runs]
  (let [sum (fn [k] (reduce + 0 (for [r runs n (vals (get-in r [:stats k]))] n)))
        decoded (sum :stats/heard)]
    [decoded (+ decoded (sum :stats/unknown))]))

(def observed-kinds
  "The files a run's coverage counts, each [key needs], where needs names the runs that should
   have it: :all every run, :failed-real a real run that failed (its recording), :truth every run
   (a real one's server.txt, a sim one's truth.edn)."
  [[:senses :all] [:events :all] [:moment :all] [:ring :all] [:truth :all]
   [:recording :failed-real]])

(defn observed
  "A batch's observability row (the observability plan, part G) from facts, one map a run:
   {:ok? :real? :senses? :events? :moment? :ring? :truth? :recording? :intents n :whys n
   :dropped n}. Returns {kind [have want]} for each of observed-kinds, :why [intents with a why,
   intents], :dropped (ledger events lost, summed) and :unheard (unheard). Every pair whole and
   :dropped 0 is full sight; anything less is the first thing fixed (LOOP.md section 4)."
  [facts unheard-counts]
  (let [wants (fn [need f]
                (case need
                  :all true
                  :failed-real (and (:real? f) (not (:ok? f)))))]
    (into {:why [(reduce + 0 (keep :whys facts)) (reduce + 0 (keep :intents facts))]
           :dropped (reduce + 0 (keep :dropped facts))
           :unheard unheard-counts}
          (for [[k need] observed-kinds
                :let [want (filter #(wants need %) facts)
                      have-key (keyword (str (name k) "?"))]]
            [k [(count (filter have-key want)) (count want)]]))))

;;;; I/O: the recordings, the archive, the release ;;;;

(defn senses-file
  "The senses of a run, or nil when absent: a sim run's names its file (:run/senses-file,
   simrace/race-one!), a real run's sits beside its recording under data-dir
   (race/play-one!: <run/dir>/<bot>.senses.edn)."
  [data-dir r]
  (let [f (if-let [p (:run/senses-file r)]
            (fs/path p)
            (fs/path data-dir (or (:run/dir r) "") (str (:run/bot r) ".senses.edn")))]
    (when (fs/exists? f) (str f))))

(defn beside
  "A run's file of kind ext (\".moment.edn\", \".ring.edn\") beside its senses or its ledger, or
   nil when absent: a sim run's sits beside the file :run/moment-file names, a real run's beside its
   recording (<run/dir>/<bot><ext>)."
  [data-dir r ext]
  (let [f (if-let [p (:run/moment-file r)]
            (fs/path (str/replace (str p) #"\.moment\.edn$" ext))
            (fs/path data-dir (or (:run/dir r) "") (str (:run/bot r) ext)))]
    (when (fs/exists? f) (str f))))

(defn events-file
  "The ledger of a run (senses/ledger!, every change), or nil when absent: a sim run's names its file
   (:run/events-file), a real run's sits beside its senses (<run/dir>/<bot>.events.edn)."
  [data-dir r]
  (let [f (if-let [p (:run/events-file r)]
            (fs/path p)
            (fs/path data-dir (or (:run/dir r) "") (str (:run/bot r) ".events.edn")))]
    (when (fs/exists? f) (str f))))

(defn senses-summary
  "What a batch's senses say, for its row on the page: :coverage, the runs with a senses file over
   the runs, and :senses, {class n} counted over the runs that failed (each file's first line holds
   its classes, senses/classes). Coverage under one is a blind spot: LOOP.md says fix it first."
  [data-dir runs]
  (let [files (keep #(when-let [f (senses-file data-dir %)] [% f]) runs)
        head (fn [f]
               (try (with-open [r (io/reader f)] (edn/read-string (first (line-seq r))))
                    (catch Exception _ nil)))
        classes (for [[r f] files :when (not (:ok r)) c (:senses/class (head f))] (name c))]
    {:coverage [(count files) (count runs)] :senses (frequencies classes)}))

(defn senses-classes
  "The classes a senses file's first line holds (senses/write!), a set of keywords; #{} when
   there is no file or it cannot be read."
  [sf]
  (or (try (with-open [r (io/reader sf)] (:senses/class (edn/read-string (first (line-seq r)))))
           (catch Exception _ nil))
      #{}))

(defn keep-recording?
  "Is a run's recording worth shipping whole beside its digest: its senses show a freeze (an
   event of the loop over senses/freeze-ms), which only a replay of the recording under a
   profiler can name (bb probe, dev profile)? A lane's recordings upload only when it ends, five
   hours on: the freezes of the pearl stage's travel on f9c021c (157 frozen seconds under
   :tunnel-to :for :pearls, up to 9 s, one :stairs-down 30 s) waited on that. Or idle (waiting
   with nothing to do), which the digest cannot explain either: real-18 bots on 6b5d6fa ended
   :nothing-to-do 17 s after their tunnel out, the planner answering nothing, and only a replay
   names the branch. With the run r, every run that failed ships it too (deaths above all): a death
   is replayed, never guessed from the one-second lines (the observability plan, section F)."
  ([sf] (keep-recording? sf nil))
  ([sf r] (boolean (or (some #{:freeze :idle} (senses-classes sf)) (and r (not (:ok r)))))))

(defn pack-recording!
  "Write recording rec to out as zstd (a plain .edn compressed, a .zst copied); true when written."
  [rec out]
  (if (str/ends-with? rec ".zst")
    (do (fs/copy rec out {:replace-existing true}) true)
    (zero? (:exit
            (p/shell {:continue true :out :string :err :string} "zstd" "-q" "-f" "-o" out rec)))))

(defn recording
  "The recording of a real run under data-dir: <run/dir>/<bot>.edn while the lane plays (the
   recordings are packed to .edn.zst only at the job's end), or .edn.zst after; nil when absent.
   Lane 8 of adec45c uploaded nothing from a batch of 8 ended runs while it read only .zst."
  [data-dir r]
  (some (fn [ext]
          (let [f (fs/path data-dir (or (:run/dir r) "") (str (:run/bot r) ext))]
            (when (fs/exists? f) (str f))))
        [".edn" ".edn.zst"]))

(defn digest-file!
  "Write the digest of recording rec to out: a plain .edn read as it is, a .zst decompressed with
   zstd as a stream; true when written."
  [rec out]
  (if (str/ends-with? rec ".zst")
    (let [proc (p/process {:err :string} "zstd" "-dc" rec)]
      (with-open [rdr (io/reader (:out proc))]
        (spit out (str (str/join "\n" (digest (line-seq rdr))) "\n")))
      (zero? (:exit @proc)))
    (with-open [rdr (io/reader rec)]
      (spit out (str (str/join "\n" (digest (line-seq rdr))) "\n"))
      true)))

(defn ledger-facts
  "{:intents n :whys n :dropped n} of a ledger file: its :intent events, how many carry a :why,
   and the drops its head line counts (#:ledger{:events n :dropped n}); {} when unreadable."
  [ef]
  (or (try (with-open [r (io/reader ef)]
             (let [[head & lines] (remove str/blank? (line-seq r))
                   intents (filter #(str/includes? % ":ev :intent") lines)]
               {:intents (count intents)
                :whys (count (filter #(str/includes? % ":why ") intents))
                :dropped (:ledger/dropped (edn/read-string head) 0)}))
           (catch Exception _ nil))
      {}))

(defn run-facts
  "What a batch holds of run r under data-dir, for observed: which of its files exist and its
   ledger's counts (ledger-facts). A real run is one named by its bot (:run/bot)."
  [data-dir r]
  (let [real? (some? (:run/bot r))
        ef (events-file data-dir r)]
    (merge {:ok? (boolean (:ok r))
            :real? real?
            :senses? (some? (senses-file data-dir r))
            :events? (some? ef)
            :moment? (some? (beside data-dir r ".moment.edn"))
            :ring? (some? (beside data-dir r ".ring.edn"))
            :truth? (some? (beside data-dir r (if real? ".server.txt" ".truth.edn")))
            :recording? (some? (recording data-dir r))}
           (when ef (ledger-facts ef)))))

(defn observed-of
  "The observability row of a batch's runs under data-dir (observed over run-facts, with the
   unknown packets named from resources/clojurecraft/packets.edn under the checkout root), and
   :packets, [decoded total] (packets)."
  [data-dir runs]
  (let [names (packet-names (or (try (edn/read-string (slurp "resources/clojurecraft/packets.edn"))
                                     (catch Exception _ nil))
                                {}))]
    (assoc (observed (map #(run-facts data-dir %) runs) (unheard names runs))
           :packets
           (packets runs))))

;; ---------------------------------------------------------------- the case: how a run ended, read for you

(def case-window-ms 60000)             ; the last minute of a run a case tells: its final fight

(defn read-events
  "The events of a ledger file (senses/write-events!: a head line, then an event a line), or []."
  [f]
  (or (try (with-open [r (io/reader f)]
             (mapv edn/read-string (rest (remove str/blank? (line-seq r)))))
           (catch Exception _ nil))
      []))

(defn intent-at
  "The newest :intent event of es at or before t, or nil: what the bot was doing then, and why."
  [es t]
  (last (filter #(and (= :intent (:ev %)) (<= (:t % 0) t)) es)))

(def fire-hit-ms 2000)                 ; a hit this near an unexplained ignition is what lit it

(defn cause-of-fire
  "What lit the bot in the last window of ledger events w, the newest ignition (:alight): {:cause
   kw :by kind :s-before-end s}, or nil when it never caught fire. An ignition the ledger could not
   explain (:unknown) takes the nearest hit within fire-hit-ms either side (a damage-event can
   arrive after the flag)."
  [w end]
  (when-let [a (last (filter #(= :alight (:ev %)) w))]
    (let [near (->> w
                    (filter #(and (= :hit (:ev %))
                                  (not= :on_fire (:type %))
                                  (<= (abs (- (:t % 0) (:t a 0))) fire-hit-ms)))
                    (sort-by #(abs (- (:t % 0) (:t a 0))))
                    first)
          known? (not= :unknown (:cause a))]
      (cond-> {:cause (cond known? (:cause a)
                            near (:type near)
                            :else :unknown)
               :s-before-end (/ (- end (:t a 0)) 1000.0)}
        (and known? (:by a)) (assoc :by (:by a))
        (and (not known?) near (:by-kind near)) (assoc :by (:by-kind near))))))

(defn items-moved
  "{item delta} of the :item events of w: what the bot gained (positive) and lost (negative) of the
   items the stages count, summed; items whose changes cancelled are left out."
  [w]
  (into
   (sorted-map)
   (remove (fn [[_ n]] (zero? n)))
   (reduce (fn [m e] (update m (:item e) (fnil + 0) (:n e))) {} (filter #(= :item (:ev %)) w))))

(defn sight-of
  "What the ledger saw of the creatures in window w (the last minute's events) out of all of es:
   {:faced-by {kind n}} the hostiles that turned to face the bot (:facing events), and :hit-by a
   vector of {:eid :kind :holding} for each distinct attacker of a :hit, :holding the main hand of
   the newest :equip event for that eid in es (nil when none was seen). A pure function of values."
  [es w]
  (let [gear (reduce (fn [m e] (if (= :equip (:ev e)) (assoc m (:eid e) (:equipment e)) m)) {} es)
        hits (filter #(= :hit (:ev %)) w)]
    {:faced-by (frequencies (keep #(when (= :facing (:ev %)) (:kind %)) w))
     :hit-by (vec (for [[eid by-kind] (distinct (map (juxt :by :by-kind) hits))
                        :when eid]
                    {:eid eid :kind by-kind :holding (get-in gear [eid :mainhand])}))}))

(defn sight-text
  "The sight line of a case: `faced by: blaze x3, enderman x1; hit by wither_skeleton#1203 holding
   stone_sword`, or that nothing faced the bot and no attacker's gear was seen."
  [{:keys [faced-by hit-by]}]
  (str "sight: faced by "
       (if (seq faced-by)
         (str/join ", " (map (fn [[k n]] (str (name k) " x" n)) (sort-by (comp name key) faced-by)))
         "none")
       "; hit by "
       (if (seq hit-by)
         (str/join ", "
                   (map (fn [{:keys [eid kind holding]}]
                          (str (some-> kind
                                       name)
                               "#"
                               eid
                               (some->> holding
                                        name
                                        (str " holding "))))
                        hit-by))
         "nothing")))

(defn case-of
  "How run r ended, from its ledger es (the observability plan, part G): its ending, the killer (the
   death text the bot heard, else the server log's death line, else the last hit's attacker or damage type), the last case-window-ms of the
   run (the final fight): its first hit (when, by what, the health then, and what the bot was doing
   and why), what lit it (cause-of-fire), the items it gained and lost (items-moved), what it was
   doing at the end, the hits taken by attacker, the swings sent and how many
   landed (a hurt flash on a swung-at entity), and the replay command (replay, a string). A pure
   function of values; the signature [ending killer doing why] is what a batch counts."
  [r es replay]
  (let [end (:t (peek es) 0)
        w (filterv #(>= (:t % 0) (- end case-window-ms)) es)
        hits (filterv #(= :hit (:ev %)) w)
        first-hit (first hits)
        last-hit (peek hits)
        swung (set (keep #(when (= :swing (:ev %)) (:eid %)) w))
        landed (count (filter #(and (= :hurt (:ev %)) (contains? swung (:eid %))) w))
        doing-first (when first-hit (intent-at es (:t first-hit)))
        doing-end (intent-at es end)
        heard (last (filter #(and (contains? #{:death :death-text} (:ev %)) (:text %)) es))
        killer (or (:text heard)
                   (:death-message r)
                   (when last-hit (name (or (:by-kind last-hit) (:type last-hit))))
                   "none")]
    {:bot (:run/bot r)
     :ending (:reason r)
     :killer killer
     :end end
     :first-hit (when first-hit
                  {:s-before-end (/ (- end (:t first-hit)) 1000.0)
                   :type (:type first-hit)
                   :by (:by-kind first-hit)
                   :eid (:by first-hit)
                   :hp (:hp first-hit)
                   :doing (:kind doing-first)
                   :why (:why doing-first)})
     :at-end {:doing (:kind doing-end) :why (:why doing-end) :target (:target doing-end)}
     :cause-of-fire (cause-of-fire w end)
     :items (items-moved w)
     :hits (count hits)
     :hits-by (frequencies (map #(or (:by-kind %) (:type %)) hits))
     :swings (count (filter #(= :swing (:ev %)) w))
     :landed landed
     :sight (sight-of es w)
     :signature [(:reason r)
                 (some-> last-hit
                         (#(or (:by-kind %) (:type %)))) (:kind doing-end) (:why doing-end)]
     :timeline w
     :replay replay}))

(defn event-text
  "One ledger event as a timeline line, its time in seconds before end."
  [end e]
  (str (format "%7.1f " (/ (- (:t e 0) end) 1000.0)) (name (:ev e)) " " (pr-str (dissoc e :ev :t))))

(defn case-text
  "A case (case-of) as text a person or an agent reads in ten seconds."
  [c]
  (let [{:keys [bot ending killer first-hit at-end hits hits-by swings landed replay timeline end
                cause-of-fire items sight]}
        c]
    (str/join
     "\n"
     (concat
      [(str "CASE " bot "  ending " ending "  killer " killer)
       (if first-hit
         (str "first hit of the last minute "
              (:s-before-end first-hit)
              " s before the end: "
              (name (:type first-hit))
              " by "
              (or (some-> (:by first-hit)
                          name)
                  "-")
              "#"
              (:eid first-hit)
              " at hp "
              (:hp first-hit)
              ", doing "
              (:doing first-hit)
              " (why "
              (:why first-hit)
              ")")
         "no hit in the last minute")
       (if cause-of-fire
         (str "cause of fire "
              (:s-before-end cause-of-fire)
              " s before the end: "
              (name (:cause cause-of-fire))
              (some->> (:by cause-of-fire)
                       name
                       (str " by ")))
         "never caught fire in the last minute") (sight-text sight)
       (str "items in the last minute: " (if (seq items) (pr-str items) "no change"))
       (str "at the end: doing " (:doing at-end)
            " (why " (:why at-end)
            ") target " (:target at-end))
       (str "last minute: "
            hits
            " hits taken "
            (pr-str hits-by)
            ", "
            swings
            " swings sent, "
            landed
            " landed") (str "replay: " replay) "timeline (seconds before the end):"]
      (map #(str "  " (event-text end %)) timeline)))))

(defn upload!
  "Digest every run of a batch that has a recording under data-dir (a real one) into dir, beside
   every run's senses file (real and sim), archive them with the batch's lines (lines-dir), and
   upload the archive to the hour's release (made, a prerelease, by the hour's first batch to need
   it). Returns the asset name, or nil when there was nothing to pack or the upload failed; never
   throws, a batch's report goes on without it."
  [{:keys [data-dir run sha runner]} batch dir lines-dir runs]
  (try
    (let [logs (fs/path dir "botlogs")
          recs (keep (fn [r]
                       (let [rec (recording data-dir r)
                             sf (senses-file data-dir r)]
                         (when (or rec sf) [r rec sf])))
                     runs)]
      (when (seq recs)
        (fs/create-dirs logs)
        (let [name (asset run runner batch)
              t (tag (str (java.time.Instant/now)))
              archive (str (fs/absolutize (fs/path dir name))) ; tar runs in the logs dir: a relative path would name a file under it
              cases
              (vec
               (for [[r rec sf] recs
                     :let [stem (if (:run/bot r)
                                  (str (:run/dir r "s") "-" (:run/bot r))
                                  (str/replace (fs/file-name sf) #"\.senses\.edn$" ""))
                           ef (events-file data-dir r)]]
                 (do
                   (when rec (digest-file! rec (str (fs/path logs (str stem ".log")))))
                   (when (and rec (or (and sf (keep-recording? sf r)) (and r (not (:ok r)))))
                     (pack-recording! rec (str (fs/path logs (str stem ".edn.zst")))))
                   (when sf
                     (fs/copy sf (fs/path logs (str stem ".senses.edn")) {:replace-existing true}))
                   (when ef
                     (fs/copy ef (fs/path logs (str stem ".events.edn")) {:replace-existing true}))
                   (doseq [ext [".moment.edn" ".ring.edn" ".server.txt" ".truth.edn"]]
                     (when-let [mf (beside data-dir r ext)]
                       (fs/copy mf (fs/path logs (str stem ext)) {:replace-existing true})))
                   ;; the case: how it ended, read for the reader (case-of), beside its evidence
                   (let [es (if ef (read-events ef) [])]
                     (when (seq es)
                       (let [c (case-of
                                (cond-> r (nil? (:run/bot r)) (assoc :run/bot stem)) ; a sim run is named by its world and seed
                                es
                                (str "bb probe why " t " " name " " stem " " (:t (peek es) 0)))]
                         (spit (str (fs/path logs (str stem ".case.edn")))
                               (prn-str (dissoc c :timeline)))
                         (spit (str (fs/path logs (str stem ".case.txt"))) (str (case-text c) "\n"))
                         (dissoc c :timeline)))))))]
          (spit (str (fs/path logs "cases.edn"))
                (str/join (map prn-str (sort-by (comp str :signature) (remove nil? cases)))))
          (when (fs/exists? lines-dir) (fs/copy-tree lines-dir (fs/path logs "lines")))
          (p/shell {:dir (str logs)} "tar" "-czf" archive ".")
          (p/shell
           {:continue true :out :string :err :string}
           "gh" "release"
           "create" t
           "--repo" (System/getenv "GITHUB_REPOSITORY")
           "--prerelease" "--latest=false"
           "--target" sha
           "--title" t
           "--notes"
           "Each ended real bot's log, one archive a lane's batch (bb/clojurecraft/botlogs.clj).")
          (when (zero? (:exit (p/shell {:continue true}
                                       "gh" "release"
                                       "upload" t
                                       "--repo" (System/getenv "GITHUB_REPOSITORY")
                                       "--clobber" archive)))
            (println "botlogs:" (count recs) "bot logs in" t name)
            name))))
    (catch Exception e (println "botlogs: failed:" (ex-message e)) nil)))
