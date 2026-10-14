(ns clojurecraft.gym
  "The gym: real runs of one goal on GitHub runners, each on its own vanilla server and its own
   landing, judged twice, by the bot's RESULT and by the server's own answer to a truth
   command. Runs are processes speaking data, like everything else:

     clojure -M:gym trial ...   start the bot as its own JVM, land it over RCON, send its :go
                                through its stdin, judge it from its log and the server; one
                                more attempt on the same landing if it lost its connection
     clojure -M:gym report --dir artifacts/   (folds every result.edn into one summary)

   gym.yml runs one trial per runner; a plan runs several on one server, tagging each row.

   `gyms` is the registry, a table like plan/goals: which goal, the --until that runs it, what
   to give the bot first, how long it may take, and the truth commands that must all pass.
   Judging is one pure function from what the run left behind to an outcome; the report is a
   pure fold over results. A run that never got going (:harness) or lost its connection
   (:disconnect) says nothing about the goal and is not counted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.cli :as cli]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.landings :as landings]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(set! *warn-on-reflection* true)

;; the shape of a registry row and of a judged result row
(s/def :gym/goal keyword?)
(s/def :gym/until string?)
(s/def :gym/prereqs (s/coll-of string? :kind vector?))
(s/def :gym/timeout-ms pos-int?)
(s/def :truth/cmd string?)
(s/def :truth/re string?)
(s/def :truth/reply string?)
(s/def :truth/ok? boolean?)
(s/def :gym/truth (s/coll-of (s/keys :req [:truth/cmd :truth/re]) :kind vector? :min-count 1))
(s/def :gym/setup (s/coll-of string? :kind vector?)) ; RCON commands that build the row's arena round the landing (fill-in)
(s/def :gym/land keyword?)               ; how the bot is landed (land!): :surface when absent
(s/def ::row
  (s/keys :req [:gym/goal :gym/until :gym/prereqs :gym/timeout-ms :gym/truth]
          :opt [:gym/setup :gym/land]))
(s/def :gym/run pos-int?)
(s/def :gym/landing (s/coll-of int? :kind vector? :count 3))
(s/def :gym/landed? boolean?)
(s/def :gym/outcome #{:pass :fail :timeout :death :disconnect :harness})
(s/def :gym/ms int?)
(s/def :gym/reason (s/nilable keyword?))
(s/def :gym/truths (s/coll-of (s/keys :req [:truth/cmd :truth/reply :truth/ok?]) :kind vector?))
(s/def :gym/commit (s/nilable string?))
(s/def :gym/result (s/nilable map?))
(s/def ::result
  (s/keys :req [:gym/goal :gym/run :gym/landed? :gym/outcome :gym/truths]
          :opt [:gym/landing :gym/ms :gym/reason :gym/commit :gym/result]))

(def arena-reach 16)                  ; blocks round the landing an arena's setup is forceloaded over
(def fortress-offset 48)              ; blocks west of a located fortress the Nether pad is carved
(def pad-y 72)                        ; the Nether pad's feet height: over the lava sea (y 31), under the roof

(def portal-arena
  "The portal rows' arena round the landing (issue #8): a stone floor two thick and air over it,
   and a trench of lava sources one wide east of where any frame footprint near the landing can
   reach, every source with stone beside it to refill from (a scoop takes a source for good)."
  ["fill {x-8} {y-2} {z-8} {x+16} {y-1} {z+16} minecraft:stone"
   "fill {x-8} {y} {z-8} {x+16} {y+14} {z+16} minecraft:air"
   "fill {x+14} {y-1} {z-6} {x+14} {y-1} {z+6} minecraft:lava"])

(def portal-kit
  "What a portal row gives: two buckets of lava, one of water, an empty one, three stacks of
   cobblestone (the mold places about 150), flint and steel, and the stone pickaxe the rows
   hold for the mold's digs."
  ["minecraft:lava_bucket 2" "minecraft:water_bucket 1" "minecraft:bucket 1"
   "minecraft:cobblestone 192" "minecraft:flint_and_steel 1" "minecraft:stone_pickaxe 1"])

(defn block-count
  "A truth that counts the blocks named block in the arena: a clone of the arena filtered to them,
   to air high over it, whose reply says how many (re, a pattern of that reply)."
  [block re]
  {:truth/cmd (str "clone {x-8} {y-1} {z-8} {x+16} {y+12} {z+16} {x-8} {y+60} {z-8} filtered "
                   block)
   :truth/re re})

(def gyms
  "The gym registry. Truth commands count items without removing any (`clear <name> <item> 0`
   answers \"Found N matching item(s)...\"); {name} is the bot's name; patterns are strings so
   the table stays EDN."
  [{:gym/goal :wood
    :gym/until "wood"
    :gym/prereqs []
    :gym/timeout-ms 150000
    :gym/truth [{:truth/cmd "clear {name} #minecraft:logs 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :kit
    :gym/until "table"
    :gym/prereqs []
    :gym/timeout-ms 240000
    :gym/truth [{:truth/cmd "clear {name} minecraft:crafting_table 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :pickaxe
    :gym/until "pickaxe"
    :gym/prereqs []
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the pickaxe is given, so the row measures stone, not the wood and crafting it already measures
   {:gym/goal :cobblestone
    :gym/until "cobblestone"
    :gym/prereqs ["minecraft:wooden_pickaxe 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0"
                 :truth/re "Found ([3-9]|[1-9][0-9])"}
                {:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the wooden pickaxe, sticks and a table are given, so the row measures stone and the table craft
   {:gym/goal :stone-pickaxe
    :gym/until "stone-pickaxe"
    :gym/prereqs ["minecraft:wooden_pickaxe 1" "minecraft:stick 2" "minecraft:crafting_table 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:stone_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; issue #6, one row per stage of the iron kit, each given what the stages before it make.
   ;; The furnace: a placed block is not in the inventory, so the server's word is that the
   ;; cobblestone was spent and no furnace is held; the bot's RESULT needs the placed block seen.
   {:gym/goal :furnace
    :gym/until "furnace"
    :gym/prereqs ["minecraft:cobblestone 8" "minecraft:crafting_table 1"]
    :gym/timeout-ms 180000
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0" :truth/re "No items were found"}
                {:truth/cmd "clear {name} minecraft:furnace 0" :truth/re "No items were found"}]}
   {:gym/goal :ingots
    :gym/until "ingots"
    :gym/prereqs ["minecraft:furnace 1" "minecraft:raw_iron 4" "minecraft:coal 1"]
    :gym/timeout-ms 240000
    :gym/truth [{:truth/cmd "clear {name} minecraft:iron_ingot 0"
                 :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :bucket
    :gym/until "bucket"
    :gym/prereqs ["minecraft:iron_ingot 3" "minecraft:crafting_table 1"]
    :gym/timeout-ms 180000
    :gym/truth [{:truth/cmd "clear {name} minecraft:bucket 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :water-bucket
    :gym/until "water-bucket"
    :gym/prereqs ["minecraft:bucket 1" "minecraft:stone_pickaxe 2"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}]}
   ;; a gravel is given, so the row measures digging it again for flint, not finding gravel
   {:gym/goal :flint-and-steel
    :gym/until "flint-and-steel"
    :gym/prereqs ["minecraft:iron_ingot 1" "minecraft:gravel 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   ;; the whole stage from what #9 ends with, as the sim's end-to-end run starts. Forty minutes:
   ;; down to y 16, iron, coal, gravel and the smelts there, then the stairs back up to water; batch
   ;; iron-a5's three runs that held flint and steel and the bucket were still climbing at twenty
   {:gym/goal :iron-kit
    :gym/until "iron-kit"
    :gym/prereqs ["minecraft:stone_pickaxe 1" "minecraft:crafting_table 1" "minecraft:cobblestone 8"
                  "minecraft:oak_log 4"]
    :gym/timeout-ms 2400000
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   ;; issue #8: the portal cast in an arena built round the landing, from the kit
   {:gym/goal :cast-one
    :gym/until "cast-one"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 600000
    :gym/truth [(block-count "minecraft:obsidian" "cloned [1-9]")]}
   {:gym/goal :portal
    :gym/until "portal"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 2400000
    :gym/truth [(block-count "minecraft:obsidian" "cloned (1[0-9]|[2-9][0-9]) block")
                (block-count "minecraft:nether_portal" "cloned [1-9]")]}
   {:gym/goal :nether
    :gym/until "nether"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 2400000
    :gym/truth [{:truth/cmd "data get entity {name} Dimension" :truth/re "the_nether"}]}
   ;; issue #5: wade in, get out; the server must see the bot on dry land (steve's onDryLand judge)
   ;; issue #11: from a pad carved in the Nether 48 blocks west of a located fortress, tunnel to
   ;; it; judged in the bot's own dimension: the Nether, and fortress bricks within 6 (a filtered
   ;; clone of the cube round it counts them)
   {:gym/goal :fortress
    :gym/until "fortress"
    :gym/land :nether-fortress
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64"]
    :gym/timeout-ms 900000
    :gym/truth
    [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_nether"
      :truth/re "Test passed"}
     {:truth/cmd
      "execute as {name} at @s run clone ~-6 ~-6 ~-6 ~6 ~6 ~6 ~-6 ~20 ~-6 filtered minecraft:nether_bricks"
      :truth/re "cloned [1-9]"}]}
   {:gym/goal :shore
    :gym/until "shore"
    :gym/prereqs []
    :gym/timeout-ms 180000
    :gym/truth
    [{:truth/cmd
      "execute as {name} at @s unless block ~ ~ ~ minecraft:water unless block ~ ~1 ~ minecraft:water"
      :truth/re "Test passed"} {:truth/cmd "data get entity {name} OnGround" :truth/re "1b"}]}])

(def counted
  "Outcomes that say something about the goal; :harness and :disconnect do not."
  #{:pass :fail :timeout :death})

(def strict-failures #{:fail :timeout :death}) ; the outcomes that fail a strict (push) trial
(def bot-heap "-Xmx1g")                 ; each bot is its own JVM; one fits several on a runner
(def hold-ms 60000)                     ; how long an ok bot stays online for the judge's truth commands

(def z95 1.959964)                      ; the normal quantile for a two-sided 95% interval
(def judge-grace-ms 120000)             ; past the goal's timeout, how long the judge waits for RESULT
(def trial-overhead-minutes 8)          ; setup, server start and judging, on top of a goal's timeout

;; ---------------------------------------------------------------- data

(defn gym
  "The registry row for a goal, named by its keyword or its --until name, or nil."
  [goal]
  (let [n (name goal)] (some #(when (or (= n (name (:gym/goal %))) (= n (:gym/until %))) %) gyms)))

(defn fill-in
  "cmd with {name} the bot's name and {x} {y} {z} the landing's coordinates, each with an offset
   when written {x+14} or {z-8}: an arena or a truth command placed relative to the landing."
  [cmd bot [x z y]]
  (-> cmd
      (str/replace "{name}" bot)
      (str/replace #"\{([xyz])([+-]\d+)?\}"
                   (fn [[_ axis off]]
                     (str (+ ({"x" x "y" y "z" z} axis) (if off (parse-long off) 0)))))))

(defn truth-cmd
  "A row's truth command for the bot's name and the run's landing (fill-in)."
  [{:truth/keys [cmd]} bot landing]
  (fill-in cmd bot landing))

(defn truth
  "A truth row's verdict on the server's reply to cmd (the row's command, filled in):
   {:truth/cmd :truth/reply :truth/ok?}."
  [row cmd reply]
  {:truth/cmd cmd
   :truth/reply (str reply)
   :truth/ok? (boolean (re-find (re-pattern (:truth/re row)) (str reply)))})

(defn result-line
  "The RESULT map a whole line of the bot's output carries, or nil for any other line."
  [line]
  (when (str/starts-with? line "RESULT ") (edn/read-string (subs line 7))))

(defn outcome
  "How a run ended, from what it left behind. :pass only when the bot says ok and every truth
   command agrees; the bot saying ok while the server disagrees is :fail, the most useful row."
  [{:gym/keys [landed? result truths]}]
  (cond (not landed?) :harness
        (or (nil? result) (:disconnected result) (:closed result)) :disconnect
        (pos? (get-in result [:stats :stats/deaths] 0)) :death
        (= :timeout (:reason result)) :timeout
        (and (:ok result) (seq truths) (every? :truth/ok? truths)) :pass
        :else :fail))

(defn judged
  "The result row for one run: what it left behind, plus its outcome."
  [{:gym/keys [goal run landing landed? result truths ms commit] :as left}]
  {:gym/goal goal
   :gym/run run
   :gym/landing landing
   :gym/landed? (boolean landed?)
   :gym/outcome (outcome left)
   :gym/ms ms
   :gym/reason (:reason result)
   :gym/truths (vec truths)
   :gym/commit commit
   :gym/result result})

(defn wilson
  "The 95% Wilson score interval for k passes in n trials, [lo hi] in 0..1."
  [k n]
  (if (zero? n)
    [0.0 1.0]
    (let [p (/ (double k) n)
          z2 (* z95 z95)
          d (inc (/ z2 n))
          c (/ (+ p (/ z2 (* 2 n))) d)
          m (/ (* z95 (math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) d)]
      [(max 0.0 (- c m)) (min 1.0 (+ c m))])))

(defn pct "A fraction as a whole percent." [x] (math/round (* 100.0 (double x))))

(defn summary-line
  "One goal's verdict: pass k/n with its interval, and the runs not counted."
  [rows]
  (let [scored (filter (comp counted :gym/outcome) rows)
        k (count (filter #(= :pass (:gym/outcome %)) scored))
        n (count scored)
        [lo hi] (wilson k n)
        uncounted (frequencies (map :gym/outcome (remove (comp counted :gym/outcome) rows)))]
    (str "pass "
         k
         "/"
         n
         " ["
         (pct lo)
         "%, "
         (pct hi)
         "%]"
         "; not counted: harness " (uncounted :harness 0)
         ", disconnect " (uncounted :disconnect 0))))

(defn row-line
  "One run as a markdown table row."
  [{:gym/keys [run landing outcome ms reason truths]}]
  (str "| "
       run
       " | "
       (str/join "," (take 2 landing))
       " | "
       (name outcome)
       " | "
       (some-> ms
               (quot 1000))
       " | "
       (some-> reason
               name)
       " | "
       (str/join "; " (map :truth/reply truths))
       " |"))

(defn tally
  "\"a 2, b 1\" for the values of xs, sorted by name."
  [xs]
  (str/join ", "
            (for [[x c] (sort-by (comp str key) (frequencies xs))]
              (str (if x (name x) "none") " " c))))

(defn report
  "The markdown summary of a batch: per goal, the pass line, a table of runs, the outcome
   tally and the tally of the bot's reasons."
  [rows]
  (str/join "\n"
            (for [[goal rs] (sort-by (comp name key) (group-by :gym/goal rows))
                  :let [rs (sort-by :gym/run rs)]]
              (str/join "\n"
                        (concat [(str "### " (name goal)) "" (summary-line rs) ""
                                 "| run | landing | outcome | s | reason | truth |"
                                 "|---|---|---|---|---|---|"]
                                (map row-line rs)
                                ["" (str "outcomes: " (tally (map :gym/outcome rs)))
                                 (str "reasons: " (tally (map :gym/reason rs))) ""])))))

(defn plan-matrix
  "The trial matrix for goals x runs as JSON, {\"include\": [{\"goal\", \"run\", \"minutes\"}]}.
   Only goals, run numbers and the job's minutes cross the job boundary; each trial recomputes
   the rest from the registry."
  [goals runs]
  (str "{\"include\":["
       (str/join ","
                 (for [g goals
                       :let [row (gym g)]
                       run (range 1 (inc runs))]
                   (str "{\"goal\":\""
                        (:gym/until row)
                        "\",\"run\":"
                        run
                        ",\"minutes\":"
                        (+ trial-overhead-minutes (quot (:gym/timeout-ms row) 60000))
                        "}")))
       "]}"))

;;;; I/O: RCON, files, stdout ;;;;

(defmulti land!
  "Put the bot where row starts, given this run's landing [x z y] of the set: build the row's
   arena and teleport it there. Returns the landing its truths are filled in with. Keyed by
   :gym/land (:surface when absent); a new kind of start is a defmethod."
  {:arglists '([rc row name landing])}
  (fn [_rc row _name _landing] (:gym/land row :surface)))

(defmethod land! :surface
  ;; the row's arena round the landing, the teleport onto its surface, the spawn pinned there
  [rc row name [x z y :as landing]]
  (when-let [cmds (seq (:gym/setup row))]
    (fixture/with-forceload rc
                            [(- x arena-reach) (- z arena-reach) (+ x arena-reach)
                             (+ z arena-reach)]
                            #(doseq [c cmds] (rcon/command! rc (fill-in c name landing)))))
  (fixture/teleport! rc name [x z])
  (rcon/command! rc (str "spawnpoint " name " " x " " y " " z))
  landing)

(defn in-nether "cmd run in the Nether." [cmd] (str "execute in minecraft:the_nether run " cmd))

(defmethod land! :nether-fortress
  ;; the fortress nearest the landing's Nether coordinates (an eighth of the overworld's, so each
  ;; landing has its own), a pocket of netherrack fortress-offset blocks west of it at pad-y
  ;; (a hollow box: rock round three cells of air), and the bot in it
  [rc _row name [x z _]]
  (let [reply (rcon/command! rc
                             (str "execute in minecraft:the_nether positioned "
                                  (quot x 8)
                                  " 64 "
                                  (quot z 8)
                                  " run locate structure minecraft:fortress"))
        [fx fz] (or (fixture/parse-located reply)
                    (throw (ex-info "no fortress located" {:reply reply})))
        [px pz] [(- fx fortress-offset) fz]
        where (str px " " pz)]
    (rcon/command! rc (in-nether (str "forceload add " where)))
    (try (loop [i 0]
           (when (and (< i 150)
                      (not (fixture/passed? (rcon/command!
                                             rc
                                             (str "execute in minecraft:the_nether if loaded " px
                                                  " 0 " pz)))))
             (Thread/sleep 200)
             (recur (inc i))))
         (rcon/command! rc
                        (in-nether (str "fill "
                                        (- px 2)
                                        " "
                                        (dec pad-y)
                                        " "
                                        (- pz 2)
                                        " "
                                        (+ px 2)
                                        " "
                                        (+ pad-y 3)
                                        " "
                                        (+ pz 2)
                                        " minecraft:netherrack hollow")))
         (cli/log "tp:"
                  (rcon/command! rc (in-nether (str "tp " name " " px ".5 " pad-y " " pz ".5"))))
         (finally (rcon/command! rc (in-nether (str "forceload remove " where)))))
    [px pz pad-y]))

(defn landing!
  "The fixture's work for one run: wait for the bot, reset it (survival, empty inventory), give
   the goal's prerequisites and land it (land!: on this run's landing of the set, or where the
   row starts from it). Returns what happened: {:gym/landed? true :gym/landing :gym/at
   :gym/started}, or {:gym/landed? false :gym/error ...}; never throws."
  [{:keys [goal run name host rcon-port rcon-pass landings]}]
  (let [row (gym goal)
        landing (landings/landing-for (landings/read-landings landings) (parse-long (str run)))
        started (System/currentTimeMillis)]
    (try (rcon/with-rcon (or host "127.0.0.1")
                         (parse-long (str rcon-port))
                         rcon-pass
                         (fn [rc]
                           (when-not (fixture/wait-online! rc name fixture/online-timeout)
                             (throw (ex-info "bot never came online" {:name name})))
                           (rcon/command! rc (str "gamemode survival " name))
                           (rcon/command! rc (str "clear " name))
                           (doseq [p (:gym/prereqs row)]
                             (rcon/command! rc (str "give " name " " p)))
                           (let [landing (land! rc row name landing)]
                             (Thread/sleep (long fixture/settle-ms))
                             {:gym/landed? true
                              :gym/landing landing
                              :gym/at (fixture/position rc name)
                              :gym/started started})))
         (catch Exception e
           (cli/log "landing failed:" e)
           {:gym/landed? false :gym/landing landing :gym/error (str e) :gym/started started}))))

(defn tee-output!
  "Read the bot process p's output as it is written, a whole line at a time: copy each to log
   (for people and the artifacts) and deliver the value of the RESULT line to result (a promise),
   or nil when the output ends without one. The bot's answer comes back through the pipe its
   :go went down, never through a file another process is writing: polling the log, live
   iron-kit run 19's judge read its 44 KB RESULT half written and failed the trial on the
   reader's error. Runs on a thread of its own; returns it."
  [^Process p log result]
  (doto (Thread. ^Runnable
                 (fn []
                   (try (with-open [r (io/reader (.getInputStream p))
                                    w (io/writer log)]
                          (doseq [line (line-seq r)]
                            (.write w (str line "\n"))
                            (.flush w)
                            (when-let [v (result-line line)] (deliver result v))))
                        (finally (deliver result nil)))))
    (.setDaemon true)
    (.start)))

(defn judge!
  "Judge one run: wait for the bot's RESULT (result, a promise tee-output! delivers), then read what the fixture left (a missing landed
   file is a landing that never happened), ask the server each truth command while the bot
   still holds, write the result row and print it as one GYMRESULT line. Returns the row."
  [{:keys [goal run name host rcon-port rcon-pass result landed out wait-ms commit]}]
  (let [row (gym goal)
        result (deref result
                      (if wait-ms (parse-long wait-ms) (+ (:gym/timeout-ms row) judge-grace-ms))
                      nil)
        left (if (.exists (io/file landed))
               (edn/read-string (slurp landed))
               {:gym/landed? false :gym/error "no landed file"})
        truths (when result
                 (try (rcon/with-rcon (or host "127.0.0.1")
                                      (parse-long (str rcon-port))
                                      rcon-pass
                                      (fn [rc]
                                        (mapv #(let [c (truth-cmd % name (:gym/landing left))]
                                                 (truth % c (rcon/command! rc c)))
                                              (:gym/truth row))))
                      (catch Exception e (cli/log "truth failed:" e) [])))
        judged-row (judged (assoc left
                                  :gym/goal (:gym/goal row)
                                  :gym/run (parse-long (str run))
                                  :gym/result result
                                  :gym/truths truths
                                  :gym/commit commit
                                  :gym/ms (- (System/currentTimeMillis) (:gym/started left 0))))]
    (spit out (pr-str judged-row))
    (prn 'GYMRESULT (dissoc judged-row :gym/result))
    (flush)
    judged-row))

(defn attempt!
  "One bot on a running server: start it as its own JVM (classpath cp, in bot-dir), land it
   (landing!) and send its :go through its stdin, judge it from its log and the server
   (judge!), and leave result.edn, landed.edn, bot.log and run.edn in dir. Returns the result
   row with tags merged in (the plan's experiment, arm and unit)."
  [{:keys [goal run name dir cp bot-dir port rcon-port pass landings commit tags]
    :or {bot-dir "." port 25565 rcon-port 25575}}]
  (.mkdirs (io/file dir))
  (let [row (gym goal)
        bot-log (io/file dir "bot.log")
        cmd ["java" bot-heap "-cp" cp "clojure.main" "-m" "clojurecraft.main" "--host" "127.0.0.1"
             "--port" (str port) "--name" name "--until" (:gym/until row) "--events" "stdin"
             "--timeout-ms" (str (:gym/timeout-ms row)) "--hold-ms" (str hold-ms) "--record"
             (str (io/file dir "run.edn"))]
        ^java.util.List cmd (cond-> cmd commit (into ["--commit" commit])) ; the recording names its code
        p (.start
           (doto (ProcessBuilder. cmd) (.directory (io/file bot-dir)) (.redirectErrorStream true)))
        result (promise)
        tee (tee-output! p bot-log result)
        landed
        (landing!
         {:goal goal :run run :name name :rcon-port rcon-port :rcon-pass pass :landings landings})
        landed-file (io/file dir "landed.edn")]
    (spit landed-file (pr-str landed))
    (cli/log "landed" (pr-str landed))
    (with-open [w (io/writer (.getOutputStream p))]
      (.write w (str (pr-str (fixture/go-event (:gym/until row) (:gym/at landed))) "\n")))
    (let [tagged (merge (judge! {:goal goal
                                 :run run
                                 :name name
                                 :rcon-port rcon-port
                                 :rcon-pass pass
                                 :result result
                                 :landed (str landed-file)
                                 :out (str (io/file dir "result.edn"))
                                 :commit commit})
                        tags)]
      (.destroy p)
      (.waitFor p)
      (.join ^Thread tee 5000) ; the log whole before it is uploaded
      (spit (io/file dir "result.edn") (pr-str tagged))
      tagged)))

(defn trial!
  "One trial: an attempt!, and when it lost its connection (which says nothing about the goal)
   one more on the same landing, under dir/retry. A trial that throws leaves a :harness row,
   never nothing. Returns the row that counts."
  [{:keys [goal run dir tags] :as t}]
  (try
    (let [row (attempt! t)]
      (if (= :disconnect (:gym/outcome row)) (attempt! (assoc t :dir (io/file dir "retry"))) row))
    (catch Exception e
      (cli/log "trial failed:" e)
      (let [row (merge {:gym/goal (:gym/goal (gym goal))
                        :gym/run run
                        :gym/outcome :harness
                        :gym/reason :harness-error
                        :gym/error (ex-message e)}
                       tags)]
        (.mkdirs (io/file dir))
        (spit (io/file dir "result.edn") (pr-str row))
        row))))

(defn located-in
  "Every result file under dir whose name is in names, read, with the directory it came from:
   [{:row :path}]. A retried trial keeps only its retry: the first attempt lost its connection
   and says nothing about the goal."
  [dir names]
  (let [files (filter #(names (.getName ^java.io.File %)) (file-seq (io/file dir)))
        retried (set (for [^java.io.File f files
                           :when (= "retry" (.getName (.getParentFile f)))]
                       (str (.getParentFile (.getParentFile f)))))]
    (vec (for [^java.io.File f files
               :let [d (str (.getParentFile f))]
               :when (not (retried d))]
           {:row (edn/read-string (slurp f)) :path d}))))

(defn results-in
  "Every result.edn under dir that counts, read (a retried trial's retry, not its first attempt)."
  [dir]
  (mapv :row (located-in dir #{"result.edn"})))

(defn -main
  "clojure -M:gym <command> --key value ...
     plan     --goals wood,table --runs N       the trial matrix as JSON
     landings --set A --plan ci/gym.edn --rcon-port P --rcon-pass S --out landings.edn
     trial    --goal wood --run 3 --name N --rcon-pass S --landings f --dir trial
              [--port 25565] [--rcon-port 25575] [--commit sha] [--strict true]
     report   --dir artifacts"
  [command & args]
  (let [{:keys [goals runs goal run set plan port rcon-port rcon-pass host out dir strict landings
                commit]
         :as opts}
        (cli/options args)]
    (case command
      "plan" (println (plan-matrix (str/split goals #",") (parse-long runs)))
      "landings"
      (let [ls (rcon/with-rcon (or host "127.0.0.1")
                               (parse-long (str rcon-port))
                               rcon-pass
                               #(landings/landings! % (landings/read-set plan set)))]
        (spit out (pr-str ls))
        (println (count ls) "landings in set" set))
      "trial"
      (let [{:gym/keys [outcome]} (trial! {:goal goal
                                           :run (parse-long run)
                                           :name (:name opts)
                                           :dir (or dir "trial")
                                           :cp (cli/classpath! ".")
                                           :port (parse-long (or port "25565"))
                                           :rcon-port (parse-long (or rcon-port "25575"))
                                           :pass rcon-pass
                                           :landings landings
                                           :commit commit})]
        (shutdown-agents)
        (System/exit (if (and (= "true" strict) (strict-failures outcome)) 1 0)))
      "report" (println (report (results-in dir))))
    (shutdown-agents)))
