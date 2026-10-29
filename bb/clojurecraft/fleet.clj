(ns clojurecraft.fleet
  "The fleet (ci/fleet.edn, fleet.yml): the account's runners kept full of race and sim-race lanes,
   and a runner given back the moment another job waits for one. GitHub queues a job behind the
   running ones with no priority and no preemption, so the fleet looks every minute and decides.

     bb fleet run     the controller loop, for :fleet/minutes, then it dispatches its successor
     bb fleet look    one look: the counts and what it would do, nothing done

   A lane is one run of race.yml or simrace.yml with input lane k (its run-name `race lane k`,
   `simrace lane k`): one job, the runner k of the old matrix. What a look decides (decide):
     1. a lane on a commit whose code main has replaced is cancelled once it has run
        :fleet/stale-after-minutes (a queued one at once; a docs-only merge, brain/ docs/ *.md,
        never): new code preempts, but not before the lane's runs have had time to reach the late
        stages. On 2026-10-17 the orchestrator merged a fix every half hour, and cancelling at
        every merge cut 52 of 60 real lanes at a median 26 minutes, while a real run needs 29 to
        reach the Nether: the late stages were never reached and cut runs are never filed;
     2. while another workflow's job is queued: queued lanes are cancelled, then the newest running
        lanes, as many as the queued jobs exceed the free runners, never a kind below its floor;
     3. while nothing waits: free runners get new lanes on main, the kind with fewer lanes first,
        the lowest free lane number, up to each kind's max; for :fleet/cooldown-s after a lane was
        given back for need, only up to each kind's floor. On 2026-10-28 gym plans and probes held
        most runners, and every runner they freed for a minute got a lane that the next queued job
        took back 3 to 20 minutes later, before its first 20-minute report: 20 real and 12 sim
        lanes in three hours that reported nothing.
   A cancelled lane still runs its last report and upload (race.yml's always() steps), so it is
   counted as holding its runner until GitHub lists it completed.

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def kinds
  "A lane's kind by its workflow file."
  {".github/workflows/race.yml" :real ".github/workflows/simrace.yml" :sim})
(def workflows "The workflow a kind dispatches." {:real "race.yml" :sim "simrace.yml"})

;; ---------------------------------------------------------------- a look, as values

(defn lane-number
  "The lane number in a lane run's title (`race lane 3` -> 3), or nil."
  [title]
  (some-> (re-find #"lane (\d+)" (str title))
          second
          parse-long))

(defn lanes
  "The lane runs among runs ({:id :path :title :sha :status :created}), each with :kind and :lane."
  [runs]
  (for [r runs
        :let [kind (kinds (:path r))]
        :when kind]
    (assoc r :kind kind :lane (lane-number (:title r)))))

(defn others "The runs that are not lanes." [runs] (remove #(kinds (:path %)) runs))

(defn counts
  "{:busy n :demand n}: runners held (each running lane one, every other run its running jobs)
   and jobs of other workflows waiting for a runner, from runs and jobs ({run-id {:running n
   :queued n}})."
  [runs jobs]
  (let [ls (lanes runs)
        os (others runs)]
    {:busy (+ (count (filter #(= "in_progress" (:status %)) ls))
              (reduce + (map #(get-in jobs [(:id %) :running] 0) os)))
     :demand (reduce + (map #(get-in jobs [(:id %) :queued] 0) os))}))

(defn victims
  "The running lanes to give back for need runners: the newest first, each taken from a kind still
   above its floor, never more than need."
  [running floor need]
  (:out (reduce (fn [{:keys [left n] :as acc} lane]
                  (cond (zero? n) (reduced acc)
                        (> (left (:kind lane)) (floor (:kind lane)))
                        (-> acc
                            (update :out conj lane)
                            (update-in [:left (:kind lane)] dec)
                            (update :n dec))
                        :else acc))
                {:left (merge {:real 0 :sim 0} (frequencies (map :kind running))) :n need :out []}
                (sort-by :created #(compare %2 %1) running))))

(defn refills
  "The lanes to dispatch into slots free runners, [[kind lane] ...]: each to the kind with fewer
   lanes (live counts {kind n}), real on a tie, at its lowest number not in used ({kind #{n}}),
   while the kind is under its max."
  [slots live used maxes]
  (:out (reduce (fn [{:keys [live used] :as acc} _]
                  (let [kind (->> [:real :sim]
                                  (filter #(< (live % 0) (maxes %)))
                                  (sort-by #(live % 0))
                                  first)
                        lane (when kind
                               (first (remove (used kind #{}) (range 1 (inc (maxes kind))))))]
                    (cond (nil? lane) (reduced acc)
                          :else
                          (-> acc
                              (update :out conj [kind lane])
                              (update-in [:live kind] (fnil inc 0))
                              (update-in [:used kind] (fnil conj #{}) lane)))))
                {:live live :used used :out []}
                (range slots))))

(defn stale?
  "Is lane l to be replaced for its code: a round of the old matrix (no lane number), or on a
   commit main has replaced (stale-shas) and either still queued (it holds no runner) or created
   at least stale-after-minutes before now (ms)."
  [l stale-shas stale-after-minutes now]
  (or (nil? (:lane l))
      (and (stale-shas (:sha l))
           (or (= "queued" (:status l))
               (>= (- now (.toEpochMilli (java.time.Instant/parse (:created l))))
                   (* 60000 stale-after-minutes))))))

(defn decide
  "What one look does: {:cancel [lane ...] :dispatch [[kind lane] ...] :why {...}}, from the runs
   and jobs listed, the commits whose code main has replaced (stale-shas), the fleet's memory
   ({:cancelling #{id} :dispatched [{:kind :lane :at}] :gave-back ms}), the config, now (ms) and
   whether the rounds continue. A lane with no number is a round of the old matrix: stale. Queued
   lanes hold no runner, so only running lanes are given back for need; cancelled ones still
   running count as runners on their way. :given-back is the lanes given back for need, which
   start the cooldown. Pure: rules 1 to 3 of the namespace doc."
  [{:keys [runs jobs stale-shas memory config now continuous]}]
  (let [{:fleet/keys [cap floor dispatch-grace-s stale-after-minutes cooldown-s]} config
        cooling? (boolean (some-> (:gave-back memory)
                                  (#(< (- now %) (* 1000 (or cooldown-s 0))))))
        maxes (cond cooling? (merge-with min (:fleet/max config) floor)
                    :else (:fleet/max config))
        {:keys [busy demand]} (counts runs jobs)
        free (- cap busy)
        ls (lanes runs)
        cancelling (:cancelling memory #{})
        live (remove #(cancelling (:id %)) ls)
        stale (filter #(stale? % stale-shas (or stale-after-minutes 0) now) live)
        current (remove (set stale) live)
        recent (filter #(< (- now (:at %)) (* 1000 dispatch-grace-s)) (:dispatched memory))
        unseen (remove (fn [d] (some #(and (= (:kind d) (:kind %)) (= (:lane d) (:lane %))) ls))
                       recent)
        queued-lanes (filter #(= "queued" (:status %)) current)
        running (filter #(= "in_progress" (:status %)) current)
        held (count (filter #(and (cancelling (:id %)) (= "in_progress" (:status %))) ls))
        need (- demand free held)
        live-counts
        (merge-with + (frequencies (map :kind current)) (frequencies (map :kind unseen)))
        used
        (reduce (fn [m l] (update m (:kind l) (fnil conj #{}) (:lane l))) {} (concat ls unseen))
        given-back (when (pos? need) (victims running floor need))]
    {:why {:busy busy
           :demand demand
           :free free
           :live live-counts
           :stale (count stale)
           :cooling? cooling?}
     :cancel (vec (concat stale (when (pos? demand) queued-lanes) given-back))
     :given-back (vec given-back)
     :dispatch (cond (or (pos? demand) (seq stale) (not continuous)) []
                     :else (refills (- free (count unseen)) live-counts used maxes))}))

(defn remember
  "The fleet's memory after a look's actions at now: cancelled ids kept until GitHub stops listing
   them, dispatches kept for the grace period, and the time a lane was last given back for need
   (:gave-back), which the cooldown counts from."
  [memory {:keys [cancel dispatch given-back]} runs config now]
  (let [listed (set (map :id runs))]
    (cond-> {:cancelling (into (set (filter listed (:cancelling memory #{}))) (map :id cancel))
             :dispatched (into (filterv #(< (- now (:at %))
                                            (* 1000 (:fleet/dispatch-grace-s config)))
                                        (:dispatched memory))
                               (map (fn [[kind lane]] {:kind kind :lane lane :at now}) dispatch))}
      (:gave-back memory) (assoc :gave-back (:gave-back memory))
      (seq given-back) (assoc :gave-back now))))

(defn line
  "One look as a log line."
  [{{:keys [busy demand free live stale cooling?]} :why :keys [cancel dispatch]}]
  (str "FLEET busy "
       busy
       " free "
       free
       " waiting "
       demand
       " lanes real "
       (live :real 0)
       " sim "
       (live :sim 0)
       (when (pos? stale) (str " stale " stale))
       (when cooling? " cooling")
       (when (seq cancel)
         (str " cancel " (str/join "," (map #(str (name (:kind %)) (:lane %) "/" (:id %)) cancel))))
       (when (seq dispatch)
         (str " dispatch " (str/join "," (map (fn [[k l]] (str (name k) l)) dispatch))))))

;;;; I/O: gh, git, the clock ;;;;

(defn repo
  "The repository, owner/name."
  []
  (or (System/getenv "GITHUB_REPOSITORY") "BridgerB/clojurecraft"))

(defn gh
  "gh with args, its stdout parsed as JSON with keyword keys; throws when gh fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "gh " (first args) " " (second args) ": " (str/trim (:err r))) {})))
    (when-not (str/blank? (:out r)) (json/parse-string (:out r) true))))

(defn runs-now
  "The repository's runs in progress or queued, as {:id :path :title :sha :status :created}."
  []
  (for [status ["in_progress" "queued"]
        r (:workflow_runs
           (gh "api" (str "repos/" (repo) "/actions/runs?status=" status "&per_page=100")))]
    {:id (:id r)
     :path (:path r)
     :title (:display_title r)
     :sha (:head_sha r)
     :status (:status r)
     :created (:created_at r)}))

(defn waited-s
  "Seconds since an ISO instant, at now (ms)."
  [iso now]
  (quot (- now (.toEpochMilli (java.time.Instant/parse iso))) 1000))

(defn jobs-of
  "{:running n :queued n}: a run's jobs running, and queued for at least patience seconds at now
   (a job that waits less is a burst the free runners absorb, not a reason to give a lane back)."
  [id patience now]
  (let [js (:jobs (gh "api" (str "repos/" (repo) "/actions/runs/" id "/jobs?per_page=100")))]
    {:running (count (filter #(= "in_progress" (:status %)) js))
     :queued (count (filter #(and (= "queued" (:status %))
                                  (>= (waited-s (:created_at %) now) patience))
                            js))}))

(def docs-only
  "Paths whose change does not restart the rounds (race.yml's old paths-ignore)."
  [#"^brain/" #"^docs/" #"\.md$"])

(defn code-changed?
  "True when origin/main changes code since sha: any path outside docs-only, or sha unknown here."
  [sha]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "git"
                   "diff"
                   "--name-only"
                   sha
                   "origin/main")]
    (or (not (zero? (:exit r)))
        (boolean (some (fn [f] (not-any? #(re-find % f) docs-only))
                       (remove str/blank? (str/split-lines (:out r))))))))

(defn stale-shas
  "The lanes' commits whose code origin/main has replaced, after fetching main."
  [runs]
  (p/shell {:out :string :err :string :continue true} "git" "fetch" "-q" "origin" "main")
  (into #{} (filter code-changed?) (distinct (map :sha (lanes runs)))))

(defn look
  "Everything one decision needs, read now."
  [config memory continuous?]
  (let [runs (vec (runs-now))
        now (System/currentTimeMillis)]
    {:runs runs
     :jobs
     (into {} (map (juxt :id #(jobs-of (:id %) (:fleet/patience-s config) now))) (others runs))
     :stale-shas (stale-shas runs)
     :memory memory
     :config config
     :now now
     :continuous continuous?}))

(defn act!
  "Cancel and dispatch as decided."
  [{:keys [cancel dispatch]}]
  (doseq [{:keys [id]} cancel]
    (p/shell {:continue true}
             "gh"
             "api" "-X"
             "POST" (str "repos/" (repo) "/actions/runs/" id "/cancel")))
  (doseq [[kind lane] dispatch]
    (p/shell {:continue true}
             "gh" "workflow"
             "run" (workflows kind)
             "--repo" (repo)
             "--ref" "main"
             "-f" (str "lane=" lane))))

(defn config "ci/fleet.edn." [] (edn/read-string (slurp (str (fs/path "ci" "fleet.edn")))))

(defn continuous?
  "False only when the repository variable RACE_CONTINUOUS is false (fleet.yml passes it in)."
  []
  (not= "false" (System/getenv "RACE_CONTINUOUS")))

(defn control!
  "Look, decide and act every :fleet/every-s seconds for :fleet/minutes, a failed look logged and
   skipped; then dispatch the next fleet run, which starts when this one ends."
  []
  (let [cfg (config)
        until (+ (System/currentTimeMillis) (* 60000 (:fleet/minutes cfg)))]
    (loop [memory {}]
      (when (< (System/currentTimeMillis) until)
        (let [memory (try (let [l (look cfg memory (continuous?))
                                d (decide l)]
                            (println (line d))
                            (act! d)
                            (remember memory d (:runs l) cfg (:now l)))
                          (catch Exception e (println "FLEET look failed:" (ex-message e)) memory))]
          (flush)
          (Thread/sleep (* 1000 (:fleet/every-s cfg)))
          (recur memory))))
    (p/shell {:continue true} "gh" "workflow" "run" "fleet.yml" "--repo" (repo) "--ref" "main")))

(defn look!
  "One look, printed with what it would do; nothing done."
  []
  (let [l (look (config) {} (continuous?))
        d (decide l)]
    (println (line d))
    (doseq [r (:runs l)]
      (println " "
               (:status r)
               (:path r)
               (pr-str (:title r))
               (subs (str (:sha r)) 0 7)
               (get (:jobs l) (:id r) "")))))

(defn -main
  "run | look."
  [& [cmd]]
  (case cmd
    "run" (control!)
    "look" (look!)
    (println "bb fleet run | look")))
