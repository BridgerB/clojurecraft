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

(defn digest-line
  "[state out] after one recording line: state {:now ms :self eid} keeps the last tick's time and
   the bot's entity id (from :login); out gains a text line for each log message, health change
   and hit on the bot itself."
  [[state out] s]
  (let [now (:now state)
        stamp (fn [txt] (str (or now "-") " " txt))]
    (cond (str/includes? s ":kind :tick") [(assoc state :now (field s ":now")) out]
          (str/includes? s ":packet/name :login,") [(assoc state :self (field s ":entity-id")) out]
          (str/includes? s ":packet/name :set-health")
          [state (conj out (stamp (str "hp " (field s ":health") " food " (field s ":food"))))]
          (and (str/includes? s ":packet/name :damage-event")
               (= (:self state) (field s ":entity-id")))
          [state
           (conj out
                 (stamp (str "hit source-type " (field s ":source-type")
                             " by " (field s ":source-cause"))))]
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
   :tunnel-to :for :pearls, up to 9 s, one :stairs-down 30 s) waited on that."
  [sf]
  (contains? (senses-classes sf) :freeze))

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
          (when (and rec sf (keep-recording? sf))
            (pack-recording! rec (str (fs/path logs (str stem ".edn.zst")))))
          (when sf (fs/copy sf (fs/path logs (str stem ".senses.edn")) {:replace-existing true})))
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
