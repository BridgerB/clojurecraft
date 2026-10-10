(ns clojurecraft.gym
  "The gym: real runs of one goal on GitHub runners, each on its own vanilla server and its own
   landing, judged twice, by the bot's RESULT and by the server's own answer to a truth
   command. Runs are processes speaking data, like everything else:

     clojure -M:gym land ...  | clojure -M:run ... --events stdin --record run.edn > bot.log
     clojure -M:gym judge ... (reads bot.log and landed.edn, asks the server, writes result.edn)
     clojure -M:gym report --dir artifacts/   (folds every result.edn into one summary)

   `gyms` is the registry, a table like plan/goals: which goal, the --until that runs it, what
   to give the bot first, how long it may take, and the truth commands that must all pass.
   Judging is one pure function from what the run left behind to an outcome; the report is a
   pure fold over results. A run that never got going (:harness) or lost its connection
   (:disconnect) says nothing about the goal and is not counted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.harness :as harness]
            [clojurecraft.landings :as landings]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(def gyms
  "The gym registry. Truth commands count items without removing any (`clear <name> <item> 0`
   answers \"Found N matching item(s)...\"); {name} is the bot's name; patterns are strings so
   the table stays EDN."
  [{:gym/goal :wood :gym/until "wood" :gym/prereqs [] :gym/timeout-ms 150000
    :gym/truth [{:truth/cmd "clear {name} #minecraft:logs 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :kit :gym/until "table" :gym/prereqs [] :gym/timeout-ms 240000
    :gym/truth [{:truth/cmd "clear {name} minecraft:crafting_table 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :pickaxe :gym/until "pickaxe" :gym/prereqs [] :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}])

(def counted
  "Outcomes that say something about the goal; :harness and :disconnect do not."
  #{:pass :fail :timeout :death})

(def z95 1.959964)                      ; the normal quantile for a two-sided 95% interval
(def judge-poll-ms 500)                 ; how often the judge looks for the RESULT line
(def judge-grace-ms 120000)             ; past the goal's timeout, how long the judge waits for RESULT
(def shard-overhead-minutes 8)          ; setup, server start and judging, on top of a goal's timeout

;; ---------------------------------------------------------------- data

(defn gym
  "The registry row for a goal, named by its keyword or its --until name, or nil."
  [goal]
  (let [n (name goal)]
    (some #(when (or (= n (name (:gym/goal %))) (= n (:gym/until %))) %) gyms)))

(defn truth-cmd "A row's truth command for the bot's name." [{:truth/keys [cmd]} bot] (str/replace cmd "{name}" bot))

(defn truth
  "A truth row's verdict on the server's reply: {:truth/cmd :truth/reply :truth/ok?}."
  [row bot reply]
  {:truth/cmd (truth-cmd row bot)
   :truth/reply (str reply)
   :truth/ok? (boolean (re-find (re-pattern (:truth/re row)) (str reply)))})

(defn parse-result
  "The RESULT map in a bot log's text, or nil when the bot never printed one."
  [log-text]
  (some (fn [line] (when (str/starts-with? line "RESULT ") (edn/read-string (subs line 7))))
        (str/split-lines (str log-text))))

(defn outcome
  "How a run ended, from what it left behind. :pass only when the bot says ok and every truth
   command agrees; the bot saying ok while the server disagrees is :fail, the most useful row."
  [{:gym/keys [landed? result truths]}]
  (cond
    (not landed?) :harness
    (nil? result) :disconnect
    (or (:disconnected result) (:closed result)) :disconnect
    (pos? (get-in result [:stats :stats/deaths] 0)) :death
    (= :timeout (:reason result)) :timeout
    (and (:ok result) (seq truths) (every? :truth/ok? truths)) :pass
    :else :fail))

(defn judged
  "The result row for one run: what it left behind, plus its outcome."
  [{:gym/keys [goal run landing landed? result truths ms commit] :as left}]
  {:gym/goal goal :gym/run run :gym/landing landing :gym/landed? (boolean landed?)
   :gym/outcome (outcome left) :gym/ms ms :gym/reason (:reason result) :gym/truths (vec truths)
   :gym/commit commit :gym/result result})

(defn wilson
  "The 95% Wilson score interval for k passes in n trials, [lo hi] in 0..1."
  [k n]
  (if (zero? n)
    [0.0 1.0]
    (let [p (/ (double k) n)
          z2 (* z95 z95)
          d (+ 1 (/ z2 n))
          c (/ (+ p (/ z2 (* 2 n))) d)
          m (/ (* z95 (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) d)]
      [(max 0.0 (- c m)) (min 1.0 (+ c m))])))

(defn pct "A fraction as a whole percent." [x] (Math/round (* 100.0 (double x))))

(defn summary-line
  "One goal's verdict: pass k/n with its interval, and the runs not counted."
  [rows]
  (let [scored (filter (comp counted :gym/outcome) rows)
        k (count (filter #(= :pass (:gym/outcome %)) scored))
        n (count scored)
        [lo hi] (wilson k n)
        uncounted (frequencies (map :gym/outcome (remove (comp counted :gym/outcome) rows)))]
    (str "pass " k "/" n " [" (pct lo) "%, " (pct hi) "%]"
         "; not counted: harness " (uncounted :harness 0) ", disconnect " (uncounted :disconnect 0))))

(defn row-line
  "One run as a markdown table row."
  [{:gym/keys [run landing outcome ms reason truths]}]
  (str "| " run " | " (str/join "," (take 2 landing)) " | " (name outcome) " | " (some-> ms (quot 1000))
       " | " (some-> reason name) " | " (str/join "; " (map :truth/reply truths)) " |"))

(defn tally
  "\"a 2, b 1\" for the values of xs, sorted by name."
  [xs]
  (str/join ", " (for [[x c] (sort-by (comp str key) (frequencies xs))] (str (if x (name x) "none") " " c))))

(defn report
  "The markdown summary of a batch: per goal, the pass line, a table of runs, the outcome
   tally and the tally of the bot's reasons."
  [rows]
  (str/join
   "\n"
   (for [[goal rs] (sort-by (comp name key) (group-by :gym/goal rows))
         :let [rs (sort-by :gym/run rs)]]
     (str/join "\n"
               (concat [(str "### " (name goal)) ""
                        (summary-line rs) ""
                        "| run | landing | outcome | s | reason | truth |"
                        "|---|---|---|---|---|---|"]
                       (map row-line rs)
                       ["" (str "outcomes: " (tally (map :gym/outcome rs)))
                        (str "reasons: " (tally (map :gym/reason rs))) ""])))))

(defn plan-matrix
  "The shard matrix for goals x runs as JSON, {\"include\": [{\"goal\", \"run\", \"minutes\"}]}.
   Only goals, run numbers and the job's minutes cross the job boundary; each shard recomputes
   the rest from the registry."
  [goals runs]
  (str "{\"include\":["
       (str/join "," (for [g goals
                           :let [row (gym g)]
                           run (range 1 (inc runs))]
                       (str "{\"goal\":\"" (:gym/until row) "\",\"run\":" run
                            ",\"minutes\":" (+ shard-overhead-minutes (quot (:gym/timeout-ms row) 60000)) "}")))
       "]}"))

(defn args->map
  "--key value pairs as {:key \"value\"}."
  [args]
  (into {} (map (fn [[k v]] [(keyword (str/replace k #"^--" "")) v])) (partition 2 args)))

;;;; I/O: RCON, files, stdout ;;;;

(defn land!
  "The fixture for one run: wait for the bot, reset it (survival, empty inventory), give the
   goal's prerequisites, land it on this run's landing of the set, pin its spawn there, write
   what happened to the landed file and print the :go event for the bot's stdin. A landing that
   fails still prints :go (without :go/at), and the judge will call the run :harness."
  [{:keys [goal run name host rcon-port rcon-pass landings out]}]
  (let [row (gym goal)
        [x z y :as landing] (landings/landing-for (landings/read-landings landings) (parse-long run))
        started (System/currentTimeMillis)
        landed (try
                 (rcon/with-rcon (or host "127.0.0.1") (parse-long rcon-port) rcon-pass
                   (fn [rc]
                     (when-not (harness/wait-online rc name harness/online-timeout)
                       (throw (ex-info "bot never came online" {:name name})))
                     (rcon/command rc (str "gamemode survival " name))
                     (rcon/command rc (str "clear " name))
                     (doseq [p (:gym/prereqs row)] (rcon/command rc (str "give " name " " p)))
                     (harness/teleport! rc name [x z])
                     (rcon/command rc (str "spawnpoint " name " " x " " y " " z))
                     (Thread/sleep (long harness/settle-ms))
                     {:gym/landed? true :gym/landing landing :gym/at (harness/position rc name) :gym/started started}))
                 (catch Throwable e
                   (harness/log "landing failed:" e)
                   {:gym/landed? false :gym/landing landing :gym/error (str e) :gym/started started}))]
    (spit out (pr-str landed))
    (harness/log "landed" (pr-str landed))
    (prn (harness/go-event (:gym/until row) (:gym/at landed)))
    (flush)))

(defn wait-result!
  "The RESULT map from the bot log once it appears, or nil after wait-ms."
  [log-path wait-ms]
  (let [until (+ (System/currentTimeMillis) wait-ms)]
    (loop []
      (let [r (when (.exists (io/file log-path)) (parse-result (slurp log-path)))]
        (cond r r
              (> (System/currentTimeMillis) until) nil
              :else (do (Thread/sleep (long judge-poll-ms)) (recur)))))))

(defn judge!
  "Judge one run: wait for the bot's RESULT, then read what the fixture left (a missing landed
   file is a landing that never happened), ask the server each truth command while the bot
   still holds, write the result row and print it as one GYMRESULT line. Returns the row."
  [{:keys [goal run name host rcon-port rcon-pass bot-log landed out wait-ms commit]}]
  (let [row (gym goal)
        result (wait-result! bot-log (if wait-ms (parse-long wait-ms) (+ (:gym/timeout-ms row) judge-grace-ms)))
        left (if (.exists (io/file landed))
               (edn/read-string (slurp landed))
               {:gym/landed? false :gym/error "no landed file"})
        truths (when result
                 (try (rcon/with-rcon (or host "127.0.0.1") (parse-long rcon-port) rcon-pass
                        (fn [rc] (mapv #(truth % name (rcon/command rc (truth-cmd % name))) (:gym/truth row))))
                      (catch Throwable e (harness/log "truth failed:" e) [])))
        judged-row (judged (assoc left
                                  :gym/goal (:gym/goal row) :gym/run (parse-long run) :gym/result result
                                  :gym/truths truths :gym/commit commit
                                  :gym/ms (- (System/currentTimeMillis) (:gym/started left 0))))]
    (spit out (pr-str judged-row))
    (prn 'GYMRESULT (dissoc judged-row :gym/result))
    (flush)
    judged-row))

(defn results-in
  "Every result.edn under dir, read."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(= "result.edn" (.getName ^java.io.File %)))
       (mapv #(edn/read-string (slurp %)))))

(defn -main
  "clojure -M:gym <command> --key value ...
     plan     --goals wood,table --runs N       the shard matrix as JSON
     env      --goal wood                       UNTIL= and TIMEOUT_MS= lines for $GITHUB_ENV
     landings --set A --plan ci/gym.edn --rcon-port P --rcon-pass S --out landings.edn
     land     --goal wood --run 3 --name N --rcon-port P --rcon-pass S --landings f --out landed.edn
     judge    --goal wood --run 3 --name N --rcon-port P --rcon-pass S --bot-log bot.log
              --landed landed.edn --out result.edn [--commit sha] [--strict true]
     report   --dir artifacts"
  [command & args]
  (let [{:keys [goals runs goal set plan rcon-port rcon-pass host out dir strict] :as opts} (args->map args)]
    (case command
      "plan" (println (plan-matrix (str/split goals #",") (parse-long runs)))
      "env" (let [row (gym goal)]
              (println (str "UNTIL=" (:gym/until row)))
              (println (str "TIMEOUT_MS=" (:gym/timeout-ms row))))
      "landings" (let [ls (rcon/with-rcon (or host "127.0.0.1") (parse-long rcon-port) rcon-pass
                            #(landings/landings! % (landings/read-set plan set)))]
                   (spit out (pr-str ls))
                   (println (count ls) "landings in set" set))
      "land" (land! opts)
      "judge" (let [{:gym/keys [outcome]} (judge! opts)]
                (shutdown-agents)
                (System/exit (if (and (= "true" strict) (#{:fail :timeout :death} outcome)) 1 0)))
      "report" (println (report (results-in dir))))
    (shutdown-agents)))
