(ns clojurecraft.race-facts
  "A race as values: what its board, its samples, its milestones and its summary say, when a slot
   may start its next bot, when a bot is cut or capped, and what a server's words about an ending
   mean. Pure functions over the worlds, RESULTs and texts the race (race.clj) collects; the race
   is the part that talks to the server."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(def quick-ms 30000)                  ; a bot that crashed or was cut off sooner than this never played: the server is gone or failing

(def backoff-max-ms 60000)            ; the longest a slot waits before its next bot after quick ends

(def dead-after 5)                    ; bots in a row that never got into play before the race calls its server down

(def start-gap-ms 1000)               ; the least time between two bots starting on one server, whatever the slots do

(def recovered {:race/unplayed 0 :race/down? false}) ; a server's health when it plays, or accepts again

(defn bot-name
  "The name of racer k (from 1) with prefix: a server name, sixteen characters at most."
  [prefix k]
  (format "%s%02d" prefix k))

(defn standing
  "Where one bot is in the race, read from its world value: the target it works now, how many of
   the chain's targets it has reached (a chained run's milestones), its dimension, its height,
   its deaths and the plan's status."
  [world]
  {:race/target (some-> (and (:plan/status world) (plan/choose world plan/goals))
                        :goal/id)
   :race/reached (count (:plan/reached world))
   :race/dimension (some-> (dimension/of world)
                           (str/replace "minecraft:" ""))
   :race/y (some-> (:player/pos world)
                   second
                   long)
   :race/deaths (:stats/deaths world 0)
   :race/status (:plan/status world)})

(defn board-line
  "One bot's line on the board."
  [name world]
  (let [{:race/keys [target reached dimension y deaths status]} (standing world)]
    (str/join " "
              [name (or status :waiting) (str "at " (or target "-")) (str "reached " reached)
               dimension (str "y " (or y "?")) (str "deaths " deaths)])))

(defn start-at
  "When racer i (from 0) joins, ms after the race begins. With a ramp ({:ramp/start s :ramp/step n
   :ramp/every-ms e}), the first s join at once and n more every e, so one runner measures the
   load from s bots up; without, every racer joins at once. Within a batch, stagger-ms apart."
  [{:race/keys [ramp stagger-ms]} i]
  (let [{:ramp/keys [start step every-ms]} ramp]
    (cond (or (nil? ramp) (< i start)) (* i stagger-ms)
          :else
          (+ (* every-ms (inc (quot (- i start) step))) (* stagger-ms (rem (- i start) step))))))

(defn backoff-ms
  "How long a slot waits before its next bot: none after a bot that played; after one that crashed
   or was cut off (failure/infra-reasons) within quick-ms, double the last wait (1 s the first
   time), at most backoff-max-ms. A server that stopped or fails would otherwise have every slot
   start bot after bot at once, each with its threads, until the machine can fork no more."
  [wait life-ms {:keys [reason]}]
  (cond (and (< life-ms quick-ms) (or (= :crashed reason) (contains? failure/infra-reasons reason)))
        (min backoff-max-ms (max 1000 (* 2 wait)))
        :else 0))

(defn health
  "A server's health ({:race/unplayed :race/down?}) after one of its bots ended with RESULT r:
   :race/unplayed counts the bots in a row that never got into play (failure/unplayed?), and a bot
   that played sets it back to 0; :race/down? turns true at dead-after of them and stays true until
   a probe finds the server accepting again (recovered). Backing off alone still let a dead server's
   15 slots start one bot a minute each for three hours."
  [h r]
  (let [n (if (failure/unplayed? r) (inc (:race/unplayed h 0)) 0)]
    {:race/unplayed n :race/down? (boolean (or (:race/down? h) (>= n dead-after)))}))

(defn next-start
  "When the next bot may start on a server, ms: now, or start-gap-ms after the last start (last),
   whichever is later. Every slot of a server takes its start from one clock, so however its bots
   end, the server sees at most one new bot a start-gap-ms."
  [last now]
  (max now (+ (or last 0) start-gap-ms)))

(defn run-ms
  "How long a race plays, ms: :race/deadline-ms (the capacity ramp's one deadline), else
   :race/run-minutes (the churn's job, up to GitHub's cap), else :race/timeout-ms."
  [{:race/keys [deadline-ms run-minutes timeout-ms]}]
  (or deadline-ms
      (some-> run-minutes
              (* 60000))
      timeout-ms))

(defn bot-timeout-ms
  "How long a bot may play, ms, started at now: until the job's deadline, or its own cap (ms, nil
   for none) when that comes first; at least 1000."
  [deadline now cap]
  (max 1000 (min (- deadline now) (or cap Long/MAX_VALUE))))

(defn capped
  "A RESULT r of a bot that was stopped by its own cap (cap-ms, nil for none; started at start,
   the job ending at deadline) rather than the job's end: a :timeout becomes :bot-timeout, a
   stage's failure (no pearls in the time) that is filed. Any other RESULT is unchanged."
  [r deadline start cap]
  (cond-> r
    (and (= :timeout (:reason r)) cap (< (+ start cap) deadline)) (assoc :reason :bot-timeout)))

(defn marked
  "A RESULT as the race prints it: marked for bb failures (failure/mark), cut (failure/cut) when
   the job's end stopped the bot (a :timeout, the only timeout a churn bot has), and :run/infra?
   when the bot never got into play (failure/unplayed?: its server refused it or was gone)."
  [r]
  (cond-> (failure/mark r)
    (= :timeout (:reason r)) failure/cut
    (failure/unplayed? r) (assoc :run/infra? true)))

(defn tick-times
  "The server's tick times in ms, {:avg :p50 :p95 :p99}, from its tick query reply; {} when the
   reply has none."
  [reply]
  (let [ms (fn [re]
             (some->> (re-find re (str reply))
                      second
                      parse-double))]
    (into {}
          (remove (comp nil? val))
          {:avg (ms #"Average time per tick: ([\d.]+)ms")
           :p50 (ms #"P50: ([\d.]+)ms")
           :p95 (ms #"P95: ([\d.]+)ms")
           :p99 (ms #"P99: ([\d.]+)ms")})))

(defn quantile
  "The q quantile (0 to 1) of numbers xs, nearest rank; nil when there are none."
  [q xs]
  (let [v (vec (sort xs))] (when (seq v) (nth v (min (dec (count v)) (long (* q (count v))))))))

(defn sample
  "The race at one moment as a fact, from every bot's world now ({name world}) and the tick each
   was at dt-ms before ({name tick}): how many bots have joined, landed, are playing (in the world
   and still racing: a bot that ended stops ticking), ended or were cut off; each playing bot's ticks per second (a bot whose machine is short of CPU falls below
   20 first), as its median, tenth percentile and least; milestones reached in all; the server's
   tick times and the bots' JVM heap."
  [race worlds before dt-ms tick heap-mb now]
  (let [playing (filter (fn [[_ w]]
                          (and (= :play (:bot/phase w))
                               (:player/loaded? w)
                               (not (:bot/closed w))
                               (not (:bot/disconnected w))
                               (not (#{:done :failed} (:plan/status w)))))
                        worlds)
        rates (keep (fn [[name w]]
                      (when-let [t0 (get before name)]
                        (/ (* 1000.0 (- (:time/tick w 0) t0)) (max 1 dt-ms))))
                    playing)]
    {:sample/runner (:runner race)
     :sample/server (:server race)
     :sample/at now
     :sample/joined (count (filter (comp :player/pos val) worlds))
     :sample/landed (count (filter (comp :plan/go-at val) worlds))
     :sample/playing (count playing)
     :sample/ended (count (filter (comp #{:done :failed} :plan/status val) worlds))
     :sample/cut-off (count (filter (fn [[_ w]] (or (:bot/closed w) (:bot/disconnected w))) worlds))
     :sample/tick-rate
     {:median (quantile 0.5 rates) :p10 (quantile 0.1 rates) :min (quantile 0 rates)}
     :sample/milestones (reduce + (map (comp count :plan/reached val) worlds))
     :sample/server-tick tick
     :sample/bot-heap-mb heap-mb}))

(defn reached-new
  "The targets world reached that before did not: what a chained run's step just added to
   :plan/reached, in no particular order."
  [before world]
  (remove (set (:plan/reached before)) (:plan/reached world)))

(defn milestone
  "The fact that bot name reached goal in world: on which runner (and which of its servers, when
   it carries more than one) and seed, when (:time/now, ms), and how long after its plan began
   (its landing)."
  [{:keys [runner server seed]} name world goal]
  (cond-> {:milestone/runner runner
           :milestone/seed seed
           :milestone/bot name
           :milestone/goal goal
           :milestone/at (:time/now world)}
    server (assoc :milestone/server server)
    (:plan/since world)
    (assoc :milestone/after-s (quot (- (:time/now world) (:plan/since world)) 1000))))

(defn summary
  "The race as a value, from each bot's {:name :result :truth}: how many bots, how many reached
   the target (RESULT :ok), why the others stopped, and every bot's reason and milestones."
  [results]
  {:race/bots (count results)
   :race/ok (count (filter (comp :ok :result) results))
   :race/reasons (frequencies (map (comp :reason :result) results))
   :race/runs (mapv (fn [{:keys [name result truth]}]
                      {:name name
                       :ok (:ok result)
                       :reason (:reason result)
                       :reached (:plan/reached (:plan result))
                       :truth truth})
                    results)})

(defn slot-index
  "The index racer i (from 0) lands with at the fortress: i mod slots, so a bot that replaces one
   that ended takes the landing and the pad of its slot, which prepare! built, and no new Nether
   region is generated while bots are connected. (Racer serials grow with every replacement)"
  [i slots]
  (if (pos? (or slots 0)) (mod i slots) i))

(defn spawn-command
  "The RCON command that pins bot name's spawn at the cell of at ([x y z]) in the dimension dim:
   `execute in` it, because spawnpoint takes the dimension of the command's source, the server's
   overworld: pinned without it, a bot that died at the fortress respawned in the overworld at the
   Nether's coordinates."
  [name [x y z] dim]
  (str "execute in " dim
       " run spawnpoint " name
       " " (long (math/floor (double x)))
       " " (long (math/floor (double y)))
       " " (long (math/floor (double z)))))

(def truth-radius 32)                  ; blocks round where a bot ended the server is asked about (near-truth!)

(def truth-limit 12)                   ; the nearest this many entities asked about

(defn near-commands
  "The RCON commands that ask the server what stood round where bot name ended (pos, [x y z] in
   dimension dim): every entity but items and orbs within truth-radius, nearest first, at most
   truth-limit, whole (data get entity: type, Pos, Health, AngerTime, AngryAt, Target, HurtTime and
   the rest), and the bot's own entity whole (health, effects, equipment, fire, fall distance). The
   ground truth beside what the bot saw (the observability plan, part E)."
  [name [x y z] dim]
  [(str "execute in "
        dim
        " positioned "
        x
        " "
        y
        " "
        z
        " as @e[distance=.."
        truth-radius
        ",type=!item,type=!experience_orb,type=!player,sort=nearest,limit="
        truth-limit
        "] run data get entity @s") (str "data get entity " name)])

(defn death-class
  "The class of a server death line, a keyword: :blaze, :enderman, :piglin (a piglin, zombified or
   brute), :hoglin, :wither (a wither skeleton), :ghast, :lava, :fire, :fall, else :other (also a
   nil line). The mob is asked first: \"burned to a crisp while fighting Blaze\" is a blaze's, not
   fire's. A death at a stage is filed by its class, so a blaze's and a lava's get different issues
   and owners."
  [msg]
  (let [m (str msg)]
    (condp re-find m
      #"Blaze" :blaze
      #"Enderman" :enderman
      #"Piglin" :piglin
      #"Hoglin" :hoglin
      #"Wither Skeleton" :wither
      #"Ghast" :ghast
      #"lava" :lava
      #"burned|fire|flames" :fire
      #"fell from|hit the ground" :fall
      :other)))

(def serverlog-tail 400)                ; the newest this many lines of the server log are read for a bot's ending

(def serverlog-notable
  "What in the server log is worth keeping beside a bot's ending, whoever it is about: the server
   falling behind, a connection lost or kicked, a timeout, a warning or an error."
  #"Can't keep up|Overloaded|lost connection|Disconnected|kicked|timed out|moved too quickly|moved wrongly|\[Server thread/(?:WARN|ERROR)\]")

(defn serverlog-lines
  "The lines of the server log text (its newest serverlog-tail) that bear on bot name's ending: any
   that names the bot (its join, its death line, its leaving, whole word) and any that
   serverlog-notable matches, in order, as a vector. Pure over the text."
  [text name]
  (let [mine (re-pattern (str "\\b" (java.util.regex.Pattern/quote name) "\\b"))]
    (filterv #(or (re-find mine %) (re-find serverlog-notable %))
             (take-last serverlog-tail (str/split-lines (str text))))))
