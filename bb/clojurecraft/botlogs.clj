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
        (doseq [[r rec sf] recs
                :let [stem (if (:run/bot r)
                             (str (:run/dir r "s") "-" (:run/bot r))
                             (str/replace (fs/file-name sf) #"\.senses\.edn$" ""))]]
          (when rec (digest-file! rec (str (fs/path logs (str stem ".log")))))
          (when (and rec (or (and sf (keep-recording? sf r)) (and r (not (:ok r)))))
            (pack-recording! rec (str (fs/path logs (str stem ".edn.zst")))))
          (when sf (fs/copy sf (fs/path logs (str stem ".senses.edn")) {:replace-existing true}))
          (when-let [ef (events-file data-dir r)]
            (fs/copy ef (fs/path logs (str stem ".events.edn")) {:replace-existing true}))
          ;; the server's word on what stood round the bot where it ended (race/near-truth!)
          (let [st (fs/path data-dir (or (:run/dir r) "") (str (:run/bot r) ".server.txt"))]
            (when (fs/exists? st)
              (fs/copy st (fs/path logs (str stem ".server.txt")) {:replace-existing true}))))
        (when (fs/exists? lines-dir) (fs/copy-tree lines-dir (fs/path logs "lines")))
        (let [name (asset run runner batch)
              archive (str (fs/absolutize (fs/path dir name))) ; tar runs in the logs dir: a relative path would name a file under it
              t (tag (str (java.time.Instant/now)))]
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
