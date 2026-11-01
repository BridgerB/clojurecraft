(ns clojurecraft.race
  "A race (issue #17): many bots on one server, from where each lands to the race's target, the
   server always full (issue #127): :race/bots slots, each starting a fresh bot the moment its bot
   ends, for the job's length (run-ms), so no bot waits for another and a slow one plays as long
   as it likes; a bot the job's end stops is cut (failure/cut), never a failure.

     clojure -M:race --config ci/race.edn --port 25565 --rcon-port 25575 --rcon-pass S \\
       [--record-dir data/race] [--commit sha] [--seed s] [--runner n] [--server k]
       [--landings cache/worlds/R1/landings.edn --landing-offset n] [--from spawn|fortress]

   --from (else ci/race.edn's :race/from) is where each bot starts: spawn (a forest, every stage)
   or fortress (the Nether, at the open pad beside its landing's fortress with :race/kit, playing
   only the stages from the blaze rods: stages 17 and 18, issue #262).

   --server names which of a runner's servers this race is on, when a runner carries more than
   one (:race/servers): each server is its own world, seed and race process, and its facts say
   which (:milestone/server, :sample/server). A runner stays the machine.

   --landings races on a landing set's pregenerated world (ci/gym.edn, world.yml): bot i lands on
   the set's landing offset + i, wrapping (landing-of), and nothing is searched or claimed. Every
   commit then races the same worlds from the same landings, so two commits compare landing by
   landing, and the server neither generates a fresh world round each bot nor runs a locate
   biome per bot in one of its ticks. Without it, the forest search and the claims below.

   docs/RICH-HICKEY.md, Conveyance: each bot is its own atom and loop (main/run-bot!, the same life a
   lone bot lives), and bots share nothing but one bounded channel, the claims of landing cells,
   folded by the pure claims reducer. The fixture lands each bot on the cell it claimed for it,
   over RCON, and speaks to that bot only through the bot's own bounded event queue (its :go).
   The board is an observer: once a minute it takes each bot's world as it is and prints a line;
   it never touches a loop. Every bot's run is its own recording and replays alone. The race's
   settings are data (ci/race.edn). Milestones are facts: an observer on each bot's atom turns
   each target newly reached into one {:milestone/bot :milestone/goal :milestone/at ...} line
   (MILESTONE on stdout, milestones.edn), which the churn's reports read (bb churn)."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.claims :as claims]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.game :as game]
            [clojurecraft.gym :as gym]
            [clojurecraft.landings :as landings]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon]))

(set! *warn-on-reflection* true)

(def board-every-ms 60000)            ; how often the board prints every bot's line
(def claim-tries 12)                  ; searches a landing makes before the bot starts where it stands
(def search-stride 3)                 ; cells between the points a landing searches from, so searches find different forests
(def forests "#minecraft:is_forest")  ; every forest kind: nearer than plain forest, and so a shorter search
(def claims-buffer 16)                ; requests the claims loop holds before a claimant waits
(def events-buffer 16)                ; events a bot's queue holds before the fixture waits
(def sample-every-ms 15000)            ; how often the sampler records the race as one fact
(def milestones-buffer 256)           ; milestone facts waiting for the writer; a bot reaches at most a chain's worth
(def min-play-ms 300000)              ; a slot starts no bot with less of the job left than this: it would only be cut
(def quick-ms 30000)                  ; a bot that crashed or was cut off sooner than this never played: the server is gone or failing
(def backoff-max-ms 60000)            ; the longest a slot waits before its next bot after quick ends
(def dead-after 5)                    ; bots in a row that never got into play before the race calls its server down
(def probe-every-ms 30000)            ; how often a slot asks a down server whether it accepts again
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
  "How long a slot waits before its next bot: none after a bot that played; after one that
   crashed or was cut off (failure/infra-reasons) within quick-ms, double the last wait (1 s the
   first time), at most backoff-max-ms. A server that stopped or fails would otherwise have every
   slot start bot after bot at once, each with its threads, until the machine can fork no more
   (issue #145)."
  [wait life-ms {:keys [reason]}]
  (cond (and (< life-ms quick-ms) (or (= :crashed reason) (contains? failure/infra-reasons reason)))
        (min backoff-max-ms (max 1000 (* 2 wait)))
        :else 0))

(defn health
  "A server's health ({:race/unplayed :race/down?}) after one of its bots ended with RESULT r:
   :race/unplayed counts the bots in a row that never got into play (failure/unplayed?), and a bot
   that played sets it back to 0; :race/down? turns true at dead-after of them and stays true
   until a probe finds the server accepting again (recovered). Backing off alone still let a dead
   server's 15 slots start one bot a minute each for three hours (race 39817745908: 2,864 runs
   that never connected)."
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

;;;; I/O: the claims loop, the fixture, the board, the bots ;;;;

(defn claims!
  "Start the claims loop: one bounded channel of [request reply-channel], folded by claims/claim
   in arrival order. Returns the channel."
  []
  (let [ch (async/chan claims-buffer)]
    (async/go-loop [held {}]
      (when-let [[request reply] (async/<! ch)]
        (let [[held answer] (claims/claim held request)]
          (async/>! reply answer)
          (recur held))))
    ch))

(defn claim!
  "Ask the claims loop for a cell; blocks for the answer."
  [ch request]
  (let [reply (async/chan 1)]
    (async/>!! ch [request reply])
    (async/<!! reply)))

(defn land!
  "Land the bot named name on a cell of its own: down the spiral from centre, search-stride cells
   apart, the forest nearest each point (any forest kind) is claimed for it by the cell it lies in;
   the first granted is where it lands (fixture/land-at!). Returns where it stands, or nil when no
   cell was granted or the landing failed. Racer offset starts that far down the spiral, so the
   fortieth bot does not search the first bots' points again. Each search is a locate biome, which the server runs
   in one tick: a race with plain forest searched 40 times per bot from points with none near,
   one search took 60 s and the watchdog killed the server."
  [rc ch name centre offset]
  (reduce (fn [_ cell]
            (let [forest (fixture/locate-biome! rc
                                                forests
                                                (claims/middle centre
                                                               (mapv #(* search-stride %) cell)))
                  mine (when forest (claims/cell-of centre forest))]
              (cond (nil? forest) nil
                    (:claim/granted? (claim! ch {:claim/by name :claim/cell mine}))
                    (do (cli/log name "claimed" mine "forest" forest)
                        (reduced (fixture/land-at! rc name forest)))
                    :else nil)))
          nil
          (take claim-tries (drop offset (claims/spiral)))))

(defn fortress?
  "Does :race/from name the fortress start?"
  [from]
  (contains? #{"fortress" :fortress} from))

(def fortress-from :blaze-rods)       ; the first target a race that starts at the fortress plays (issue #262): the stages before it are the road to the fortress

(defn from-goals
  "The targets a race to until plays when it starts at (:race/from) from: the whole chain to until
   from spawn (nil or :spawn), else the chain from the fortress's stage (fortress-from) on, so the
   run is only stages 17 and 18 (blaze rods, ender pearls) and its failures are filed at those."
  [from until]
  (let [chain (plan/through until)]
    (if (fortress? from) (vec (drop-while #(not= fortress-from %) chain)) chain)))

(defn landing-of
  "The [x z] racer i (from 0) lands on in a landing set ([[x z y] ...]), offset into it: the
   set's landing offset + i, wrapping, so a runner's servers and the runners sharing a set start
   their bots on different landings, and the same racer always on the same one."
  [set offset i]
  (let [[x z _] (landings/landing-for set (inc (+ offset i)))] [x z]))

(def team
  "The scoreboard team every racer joins: friendly fire off, so a bot's sweep or a retaliation never hurts another."
  "racers")

(def team-commands
  "What a race asks of its server once beside the gamerule: the team of racers, friendly fire off.
   A sword's sweep hits the players beside its target; at a spawner several bots stand together, the
   one struck fought back (combat: the striker is answered), and 47 of 344 deaths of lanes 1 to 6
   of race 9d15bd6 were a bot killed by a bot (\"was slain by Clj_r24\", 44 of the 83 \"other\"
   deaths). A team with friendly fire off takes no damage from its own."
  [(str "team add " team) (str "team modify " team " friendlyFire false")])

(defn team-join-command
  "The RCON command that puts bot name in the team of racers."
  [name]
  (str "team join " team " " name))

(defn set-gamerules!
  "What every race asks of its server once: keep inventories over deaths, the team of racers with
   friendly fire off (team-commands; an existing team answers an error, which is no matter)."
  [{:keys [host rcon-port rcon-pass]}]
  (try (rcon/with-rcon host
                       rcon-port
                       rcon-pass
                       (fn [rc]
                         (rcon/command! rc "gamerule keep_inventory true")
                         (doseq [c team-commands] (rcon/command! rc c))))
       (catch Exception e (cli/log "gamerule failed:" e))))

(def pad-offsets
  "Where, round a fortress, racer i's pad is: eight compass points 48 blocks off, so the bots of one
   fortress (a landing set's 48 landings, 40 blocks apart in the Nether, find only two or three
   fortresses) do not stand on one pad."
  [[-48 0] [48 0] [0 -48] [0 48] [-48 -48] [48 48] [-48 48] [48 -48]])

(defn pad-of
  "The pad [x z y] of racer i (from 0) at the fortress located at [fx fz]: pad-offsets (i mod 8)
   from it, at gym/pad-y."
  [[fx fz] i]
  (let [[dx dz] (nth pad-offsets (mod i (count pad-offsets)))] [(+ fx dx) (+ fz dz) gym/pad-y]))

(def pad-radii
  "How far from the fortress a pad may be, nearest first: the eight compass points at each."
  [48 80 112])

(def pad-compass [[-1 0] [1 0] [0 -1] [0 1] [-1 -1] [1 1] [-1 1] [1 -1]])

(defn pad-candidates
  "The pads [x z y] racer i may have at the fortress [fx fz], in the order they are tried: the eight
   compass points at 48 blocks starting at point (i mod 8), then at 80, then at 112. The first is
   pad-of."
  [[fx fz] i]
  (for [r pad-radii
        k (range (count pad-compass))
        :let [[dx dz] (nth pad-compass (mod (+ i k) (count pad-compass)))]]
    [(+ fx (* r dx)) (+ fz (* r dz)) gym/pad-y]))

(def brick-blocks ["minecraft:nether_bricks" "minecraft:nether_brick_fence"])

(def brick-range 16)                  ; blocks round a pad in which no fortress block may stand: outside its body

(defn brick-probes
  "The cells [x y z] looked at for a fortress block round the pad [x z y]: five by five columns eight
   apart (brick-range each side) at three heights eight apart, 75 cells."
  [[x z y]]
  (for [dx [-16 -8 0 8 16] dz [-16 -8 0 8 16] dy [-8 0 8]] [(+ x dx) (+ y dy) (+ z dz)]))

(defn brick-test
  "The RCON test that passes when the Nether's cell [x y z] holds block."
  [[x y z] block]
  (str "execute in minecraft:the_nether if block " x " " y " " z " " block))

(def danger-mobs
  "What a bot is not landed within mob-range of: a fortress's blaze, wither skeleton, the bastion's
   brute, the forest's hoglin, the ghast."
  ["minecraft:blaze" "minecraft:hoglin" "minecraft:wither_skeleton" "minecraft:piglin_brute"
   "minecraft:ghast"])

(def mob-range 32)

(defn mob-test
  "The RCON test that passes when a mob (an entity type) is within mob-range of the pad [x z y]."
  [[x z y] mob]
  (str "execute in minecraft:the_nether positioned "
       x
       " "
       y
       " "
       z
       " if entity @e[type="
       mob
       ",distance=.."
       mob-range
       "]"))

(defn choose-pad
  "The first of candidates for which (unsafe pad) is nil, else the first candidate: a pad is taken
   outside the fortress's body and clear of mobs when one is, else the nearest as before."
  [unsafe candidates]
  (or (first (filter #(nil? (unsafe %)) candidates)) (first candidates)))

(defn standing-on
  "[x y z], where a bot stands on the pad [x z y]: the middle of its cell (the order a :go/at
   has: gym/place! teleports to x.5 y z.5). Returned from the pad, never from a question to the
   server: a landing that read a position nil handed the planner the pad's own [x z y], which is
   not a place, and 30 s later the run ended :no-landing (race 41219596455, Clj_r10 and r11)."
  [[x z y]]
  [(+ x 0.5) (double y) (+ z 0.5)])

(def fortress-near 128)               ; Nether blocks within which a located fortress is the one for a landing: fortresses stand 400 or more apart, so one this near is the nearest

(defn nearby-fortress
  "The first of known ([fx fz] ...) within fortress-near blocks of the Nether point [nx nz], or nil."
  [known [nx nz]]
  (some (fn [[fx fz :as f]]
          (when (<= (math/hypot (double (- fx nx)) (double (- fz nz))) fortress-near) f))
        known))

(def fortresses
  "The fortresses located so far on this server, [[fx fz] ...] (an atom): a landing is given one
   within fortress-near of it before the server is asked."
  (atom []))

(def pool-step 600)                   ; Nether blocks between the points a pool of fortresses is searched from, ring by ring
(def pool-apart 160)                  ; Nether blocks a fortress must stand from the others of the pool to be another
(def pool-max-locates 40)             ; searches a pool makes at most

(defn search-centres
  "The Nether points [x z] a pool of fortresses is searched from, nearest first: the centre, then
   rings of eight compass points at pool-step, 2 pool-step, ... from it."
  [[cx cz]]
  (cons [cx cz]
        (for [r (iterate inc 1)
              [dx dz] pad-compass]
          [(+ cx (* r pool-step dx)) (+ cz (* r pool-step dz))])))

(defn pool-add
  "The pool with fortress f added when it stands at least pool-apart from each of the pool's, else
   as it was."
  [pool [fx fz :as f]]
  (if (some (fn [[x z]] (< (math/hypot (double (- x fx)) (double (- z fz))) pool-apart)) pool)
    pool
    (conj pool f)))

(defn fortress-for
  "The fortress of slot i in the pool: i mod the pool's size, so each slot has a fortress of its own
   while the pool has as many as there are slots, and the pool's fortresses are shared evenly
   when it has fewer."
  [pool i]
  (nth pool (mod i (count pool))))

(defn per-fortress
  "The most bots of slots slots at once on one fortress of a pool of n: the slots over the pool,
   rounded up (fortress-for)."
  [slots n]
  (if (pos? n) (long (math/ceil (/ slots (double n)))) slots))

(defn locate-fortress!
  "The fortress for the landing [x z] (overworld coordinates; the Nether's are an eighth): one
   already located within fortress-near (nearby-fortress), else the nearest as the server locates
   it, remembered. A locate is a search in one tick of the server's thread, tens of seconds: asked
   of fifteen bots at once it stopped the server's keepalives and every bot timed out (race
   41218543330: 120 bots lost connection, `Can't keep up! Running 31317ms behind`)."
  [rc [x z]]
  (let [here [(quot x 8) (quot z 8)]]
    (or (nearby-fortress @fortresses here)
        (let [reply (rcon/command! rc
                                   (str "execute in minecraft:the_nether positioned "
                                        (first here)
                                        " 64 "
                                        (second here)
                                        " run locate structure minecraft:fortress"))
              at (or (fixture/parse-located reply)
                     (throw (ex-info "no fortress located" {:reply reply})))]
          (swap! fortresses conj at)
          at))))

(def fortress-pool
  "The fortresses a race's slots land at, [[fx fz] ...] distinct (pool-add), a vector in an atom,
   built by prepare! (build-pool!)."
  (atom []))

(defn locate-from!
  "The fortress nearest the Nether point [nx nz] as the server locates it, [fx fz], or nil."
  [rc [nx nz]]
  (fixture/parse-located (rcon/command! rc
                                        (str "execute in minecraft:the_nether positioned "
                                             nx
                                             " 64 "
                                             nz
                                             " run locate structure minecraft:fortress"))))

(defn build-pool!
  "Fill fortress-pool with up to slots distinct fortresses, searching from search-centres of
   centre ([nx nz], Nether coordinates) one at a time (at most pool-max-locates searches), and log
   how many were found and the most bots a fortress carries. Fortresses a landing set's landings
   cannot supply (they are 40 Nether blocks apart, two or three fortresses): lane 2 of race
   f8b28e3 had 41 bots in two fortresses, 26 of them at one, sharing blaze aggro, fire and
   spawner refills that a solo player never meets."
  [rc centre slots]
  (reset! fortress-pool (loop [pool []
                               cs (take pool-max-locates (search-centres centre))]
                          (cond (or (>= (count pool) slots) (empty? cs)) pool
                                :else
                                (recur (if-let [f (locate-from! rc (first cs))]
                                         (pool-add pool f)
                                         pool)
                                       (rest cs)))))
  (let [n (count @fortress-pool)]
    (cli/log "fortress pool" n
             "distinct for" slots
             "slots, at most" (per-fortress slots n)
             "bots on a fortress:" @fortress-pool)
    (when (< n (quot (inc slots) 2))
      (cli/log "fortress pool short: more than 2 bots on a fortress"))))

(def pads-built
  "The pads built so far on this server, #{[x z y] ...} (an atom): a pad is built once, before a
   bot is sent to it."
  (atom #{}))

(defn tp-command
  "The RCON command that teleports bot name onto the pad [x z y], the middle of its cell, in the
   Nether."
  [name [x z y]]
  (gym/in-dimension dimension/nether (str "tp " name " " (+ x 0.5) " " y " " (+ z 0.5))))

(def pad-view 112)                    ; blocks round a pad generated before a bot is sent to it: 15 by 15 chunks, within one forceload's 256 and past the server's view distance (6 chunks, bb/clojurecraft/server.clj)

(defn pregenerate!
  "Generate the Nether round the pad [x z y] to pad-view blocks with no bot on it: the square
   forceloaded and waited for (best effort, three waits of 30 s), then released. A bot teleported
   onto a pad whose surroundings were not generated made the server generate its whole view,
   fortress structure included, in its thread: race 41229255270 (lane 3, #662) built the ten pads
   in 13 s, the bots joined at 18:26:00 and were sent from 18:26:03, and the server was 32.5 s
   behind at 18:26:50: 298 of its bots lost connection `Timed out`, 588 runs in 29 minutes."
  [rc [x z _]]
  (let [square [(- x pad-view) (- z pad-view) (+ x pad-view) (+ z pad-view)]
        where (str/join " " square)]
    (rcon/command! rc (gym/in-dimension dimension/nether (str "forceload add " where)))
    (try (loop [n 3]
           (when (and (pos? n) (not (gym/wait-loaded-in! rc dimension/nether square)))
             (recur (dec n))))
         (finally
          (rcon/command! rc (gym/in-dimension dimension/nether (str "forceload remove " where)))))))

(defn build-pad!
  "Build the pad [x z y] (gym/fortress-pad-open) in the Nether with no bot: the square round it
   forceloaded and waited for, the commands, the forceload released. The first build of a Nether
   region generates its chunks and a fortress's structure, seconds of the server's thread each:
   done with a bot connected, the server fell 12 to 34 s behind and the bots timed out (race
   41225389190: Clj_r10 lost connection 17:39:17, six more by 17:39:36, the first pad built at
   17:39:00, 40 s after the bots joined). Remembered in pads-built."
  [rc [x z _ :as pad]]
  (when-not (contains? @pads-built pad)
    (let [where (str/join " " [(- x 2) (- z 2) (+ x 2) (+ z 2)])]
      (rcon/command! rc (gym/in-dimension dimension/nether (str "forceload add " where)))
      (try (when-not (gym/wait-loaded-in! rc dimension/nether [(- x 2) (- z 2) (+ x 2) (+ z 2)])
             (throw (ex-info "the pad's chunks never loaded" {:pad pad})))
           (doseq [c gym/fortress-pad-open]
             (rcon/command! rc (gym/in-dimension dimension/nether (gym/fill-in c "" pad))))
           (swap! pads-built conj pad)
           (pregenerate! rc pad)
           (finally (rcon/command! rc
                                   (gym/in-dimension dimension/nether
                                                     (str "forceload remove " where))))))))

(defn with-loaded
  "Call (f) with the Nether's square [x0 z0 x1 z1] forceloaded and waited for (best effort, 30 s),
   and released after, even when f throws: a test of a block or an entity only sees a loaded chunk."
  [rc square f]
  (let [where (str/join " " square)]
    (rcon/command! rc (gym/in-dimension dimension/nether (str "forceload add " where)))
    (try (gym/wait-loaded-in! rc dimension/nether square)
         (f)
         (finally
          (rcon/command! rc (gym/in-dimension dimension/nether (str "forceload remove " where)))))))

(defn mobs-near?
  "Is a danger-mob within mob-range of the pad [x z y] now? The chunks round it are loaded for the
   question."
  [rc [x z _ :as pad]]
  (with-loaded
   rc
   [(- x 40) (- z 40) (+ x 40) (+ z 40)]
   (fn [] (boolean (some #(fixture/passed? (rcon/command! rc (mob-test pad %))) danger-mobs)))))

(defn bricks-near?
  "Is a fortress block within brick-range of the pad [x z y]? (brick-probes, brick-blocks), the
   chunks round it loaded for the question."
  [rc [x z _ :as pad]]
  (with-loaded rc
               [(- x 24) (- z 24) (+ x 24) (+ z 24)]
               (fn []
                 (boolean (some (fn [cell]
                                  (some #(fixture/passed? (rcon/command! rc (brick-test cell %)))
                                        brick-blocks))
                                (brick-probes pad))))))

(def ground-ring
  "The cells [dx dz] round a pad, four blocks off, that must stand on ground too: the way out of
   its doorway is not a ledge. Eight points."
  (for [dx [-4 0 4] dz [-4 0 4] :when (not= [0 0] [dx dz])] [dx dz]))

(defn ground-test
  "The RCON test that passes when the Nether's cell [x y z] is ground, a block of the nether's
   own stone (netherrack, basalt, blackstone): not lava, not air."
  [[x y z]]
  (str "execute in minecraft:the_nether if block " x " " y " " z " #minecraft:base_stone_nether"))

(defn air-test
  "The RCON test that passes when the Nether's cell [x y z] holds block (minecraft:air or
   minecraft:cave_air)."
  [[x y z] block]
  (str "execute in minecraft:the_nether if block " x " " y " " z " " block))

(def ground-top 100)
(def ground-bottom 40)               ; the lava sea is at y 31; a pad is cut well over it

(defn ground-y
  "The height of the first ground under the roof for a pad at [x z], from ground-top down to
   ground-bottom: ground under the cell, air in it and the two over it, and ground under every
   cell of ground-ring at the same height (a ledge is not ground), or nil. Race 9d15bd6, lane 7:
   Clj_r04 and Clj_r08 stepped off a pad built at the fixed y 72 into a cavern and fell 37 blocks into
   the lava sea (\"tried to swim in lava\" at y 31): no arrival by portal ever leaves a platform
   with nothing round it."
  [rc [x z _]]
  (with-loaded
   rc
   [(- x 24) (- z 24) (+ x 24) (+ z 24)]
   (fn []
     (let [ok? #(fixture/passed? (rcon/command! rc %))
           air? (fn [c]
                  (or (ok? (air-test c "minecraft:air")) (ok? (air-test c "minecraft:cave_air"))))]
       (loop [y ground-top]
         (cond (< y ground-bottom) nil
               (and (ok? (ground-test [x (dec y) z]))
                    (every? air? [[x y z] [x (inc y) z] [x (+ y 2) z]])
                    (every? (fn [[dx dz]] (ok? (ground-test [(+ x dx) (dec y) (+ z dz)])))
                            ground-ring))
               y
               :else (recur (dec y))))))))

(defn pad-unsafe
  "Why the pad is no landing, :bricks (inside the fortress's body) or :mobs (a danger-mob near), or
   nil. Race 41224855928 and 41229255270: the first deaths of the bots at the fortress came 14 to
   27 s after landing, blazes, hoglins and a wither skeleton."
  [rc pad]
  (cond (bricks-near? rc pad) :bricks
        (mobs-near? rc pad) :mobs))

(def pads-chosen
  "The pad each slot lands on, {slot [x z y]} (an atom), chosen by prepare! before any bot joins."
  (atom {}))

(defn choose-pad!
  "Choose racer k's pad at the fortress [fx fz]: in the order of pad-candidates (minus the [x z]
   of exclude), the first with ground (ground-y: its height is the pad's) that pad-unsafe finds
   safe; else the first candidate as before. Build it (build-pad!), remember it (pads-chosen) and
   log it with its distance to the fortress."
  [rc [fx fz :as fortress] k exclude]
  (let [skip (set (map (juxt first second) exclude))
        candidates (remove #(skip [(first %) (second %)]) (pad-candidates fortress k))
        pad (or (loop [[c & more] candidates]
                  (when c
                    (let [y (ground-y rc c)
                          g (when y [(first c) (second c) y])
                          why (if g (pad-unsafe rc g) :no-ground)]
                      (when why (cli/log "pad" k c "unsafe:" why))
                      (if why (recur more) g))))
                (first candidates))]
    (build-pad! rc pad)
    (swap! pads-chosen assoc k pad)
    (cli/log "pad" k
             "chosen" pad
             "fortress" fortress
             "distance"
             (long (math/hypot (double (- (first pad) fx)) (double (- (second pad) fz)))))
    pad))

(defn prepare!
  "Before any bot joins: locate the fortresses and build the pads for the first bots' landings
   (racers 0 to n - 1), one at a time on one RCON session, so the searches and the Nether's
   chunk generation stall a server nobody is connected to (build-pad!). Then each of those bots
   is only teleported onto a pad made, and finds its fortress in the cache."
  [{:keys [host rcon-port rcon-pass landings landing-offset]} n]
  (when landings
    (try (rcon/with-rcon
          host
          rcon-port
          rcon-pass
          (fn [rc]
            (let [t0 (System/currentTimeMillis)
                  [x z] (landing-of landings (or landing-offset 0) 0)]
              (build-pool! rc [(quot x 8) (quot z 8)] n)
              (doseq [i (range n)]
                (let [fortress (if (seq @fortress-pool)
                                 (fortress-for @fortress-pool i)
                                 (locate-fortress! rc
                                                   (landing-of landings (or landing-offset 0) i)))]
                  (choose-pad! rc fortress i nil)))
              (cli/log "prepared" n "pads in" (quot (- (System/currentTimeMillis) t0) 1000) "s"))))
         (catch Exception e (cli/log "prepare failed:" e)))))

(defn land-fortress!
  "Land the bot named name (racer i) at the fortress of its landing: the pad (pad-of the fortress the
   server locates, built by prepare! or here when it is new, build-pad!), the bot teleported onto
   it; wait for the server to say it stands in the Nether, clear its inventory and give it kit
   (\"minecraft:iron_sword 1\" ...: what a race bot carries to the fortress, ci/race.edn's
   :race/kit). Returns where it stands ([x y z], standing-on the pad), or nil when the dimension
   never came; a landing that cannot locate a fortress throws."
  [rc name landing i kit]
  (let [fortress
        (if (seq @fortress-pool) (fortress-for @fortress-pool i) (locate-fortress! rc landing))
        pad (or (get @pads-chosen i) (choose-pad! rc fortress i nil))
        pad (if (mobs-near? rc pad) (choose-pad! rc fortress i [pad]) pad)]
    (build-pad! rc pad)
    (cli/log "tp:" (rcon/command! rc (tp-command name pad)))
    (when (gym/wait-in! rc name dimension/nether)
      (rcon/command! rc (str "clear " name))
      (doseq [item kit] (rcon/command! rc (str "give " name " " item)))
      (Thread/sleep (long fixture/settle-ms))
      (standing-on pad))))

(defn slot-index
  "The index racer i (from 0) lands with at the fortress: i mod slots, so a bot that replaces one
   that ended takes the landing and the pad of its slot, which prepare! built, and no new Nether
   region is generated while bots are connected. (Racer serials grow with every replacement:
   lane 3 of race 41218543330 started 116 runs in 31 minutes.)"
  [i slots]
  (if (pos? (or slots 0)) (mod i slots) i))

(defn spawn-command
  "The RCON command that pins bot name's spawn at the cell of at ([x y z]) in the dimension dim:
   `execute in` it, because spawnpoint takes the dimension of the command's source, the server's
   overworld: pinned without it, a bot that died at the fortress respawned in the overworld at
   the Nether's coordinates (race 41219596455 onward)."
  [name [x y z] dim]
  (str "execute in " dim
       " run spawnpoint " name
       " " (long (math/floor (double x)))
       " " (long (math/floor (double y)))
       " " (long (math/floor (double z)))))

(defn land-bot!
  "Land one bot, on a thread of its own with an RCON session of its own: wait for it to be
   online, make it a survival player, land it (on landing i of the set, with landings; else on a
   cell claimed for it round the race's centre, which the first bot online decides), pin its spawn
   there, and put its :go (every step up to until, milestones on, :go/at where it landed) on its
   queue. A bot whose landing fails starts where it stands."
  [{:keys [host rcon-port rcon-pass until landings landing-offset from kit bots]} ch centre name
   queue i]
  (async/thread
   (try (rcon/with-rcon
         host
         rcon-port
         rcon-pass
         (fn [rc]
           (let [online (fixture/wait-online! rc name fixture/online-timeout)
                 _ (when online (deliver centre [(long (first online)) (long (nth online 2))]))
                 at (when online
                      (rcon/command! rc (str "gamemode survival " name))
                      (rcon/command! rc (team-join-command name))
                      (cond (fortress? from)
                            (let [k (slot-index i bots)]
                              (land-fortress!
                               rc
                               name
                               (if landings (landing-of landings (or landing-offset 0) k) @centre)
                               k
                               kit))
                            landings
                            (fixture/land-at! rc name (landing-of landings (or landing-offset 0) i))
                            :else (land! rc ch name @centre i)))]
             (when at
               (rcon/command!
                rc
                (spawn-command name at (if (fortress? from) dimension/nether dimension/overworld))))
             (cli/log name "landed at" at)
             (async/>!! queue
                        (cond-> {:event/kind :go :go/goals (from-goals from until) :go/chain? true}
                          at (assoc :go/at at))))))
        (catch Exception e (cli/log name "landing failed:" e)))))

(defn board!
  "The board, on a thread of its own: every board-every-ms until done (a promise) is delivered,
   one BOARD line per bot playing now (live, an atom of {name world-atom}) and the server's tick
   time, the measure of how many bots a server can carry."
  [{:keys [host rcon-port rcon-pass]} live done]
  (async/thread
   (while (= :wait (deref done board-every-ms :wait))
     (let [tick (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "tick query"))
                     (catch Exception e (str "tick query failed: " (ex-message e))))]
       (println "BOARD" (str/replace (str tick) #"\s+" " ") (str "playing " (count @live)))
       (doseq [[name world*] (sort-by key @live)]
         (println "BOARD"
                  (try (board-line name @world*)
                       (catch Exception e (str name " unreadable: " (ex-message e))))))
       (flush)))))

(defn sampler!
  "The sampler, on a thread of its own: every sample-every-ms until done is delivered, the race
   as one fact (sample) from each playing bot's world as it is (live), the server's tick query and
   this JVM's heap and live threads (:sample/threads), printed as a SAMPLE line and appended to samples.edn in record-dir: the
   measure the capacity experiment sizes a runner by."
  [{:keys [host rcon-port rcon-pass]} race live record-dir done]
  (async/thread
   (with-open [w (io/writer (io/file record-dir "samples.edn") :append true)]
     (loop [before {}
            at (System/currentTimeMillis)]
       (when (= :wait (deref done sample-every-ms :wait))
         (let [now (System/currentTimeMillis)
               worlds (into {} (map (fn [[name world*]] [name @world*])) @live)
               tick (tick-times
                     (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "tick query"))
                          (catch Exception _ nil)))
               heap (quot (.getUsed (.getHeapMemoryUsage
                                     (java.lang.management.ManagementFactory/getMemoryMXBean)))
                          (* 1024 1024))
               threads (.getThreadCount (java.lang.management.ManagementFactory/getThreadMXBean))
               fact
               (assoc (sample race worlds before (- now at) tick heap now) :sample/threads threads)]
           (println "SAMPLE" (pr-str fact))
           (flush)
           (.write w (str (pr-str fact) "\n"))
           (.flush w)
           (recur (into {} (map (fn [[name w]] [name (:time/tick w 0)])) worlds) now)))))))

(defn milestones!
  "The milestones observer: a writer thread prints each milestone fact as a MILESTONE line and
   appends it to milestones.edn in record-dir; facts come on a bounded channel that never blocks
   a bot's loop (a full channel drops, as telemetry does). Returns {:watch (fn [name world*])
   :unwatch (fn [world*]) :stop (fn [])}: watch puts a watch on a bot's atom that turns each
   target newly reached into a fact, unwatch takes it off when the bot ends, stop finishes the
   file."
  [race record-dir]
  (let [ch (async/chan milestones-buffer)
        writer (async/thread
                (with-open [w (io/writer (io/file record-dir "milestones.edn") :append true)]
                  (loop []
                    (when-let [fact (async/<!! ch)]
                      (println "MILESTONE" (pr-str fact))
                      (flush)
                      (.write w (str (pr-str fact) "\n"))
                      (.flush w)
                      (recur)))))]
    {:watch (fn [name world*]
              (add-watch world*
                         ::milestones
                         (fn [_ _ before world]
                           (doseq [goal (reached-new before world)]
                             (async/offer! ch (milestone race name world goal))))))
     :unwatch (fn [world*] (remove-watch world* ::milestones))
     :stop (fn [] (async/close! ch) (async/<!! writer))}))

(defn truth!
  "The server's word on where a bot is: its Dimension, asked while it is still connected."
  [{:keys [host rcon-port rcon-pass]} name]
  (try (rcon/with-rcon host
                       rcon-port
                       rcon-pass
                       #(str (rcon/command! % (str "data get entity " name " Dimension"))))
       (catch Exception e (str "truth failed: " (ex-message e)))))

(defn death-class
  "The class of a server death line (\"Clj_r23 was slain by Blaze\"), a keyword: :blaze, :enderman,
   :piglin (a piglin, zombified or brute), :hoglin, :wither (a wither skeleton), :ghast, :lava, :fire, :fall, else :other (also a nil
   line). The mob is asked first: \"burned to a crisp while fighting Blaze\" is a blaze's, not
   fire's. A death at a stage is filed by its class, so a blaze's and a lava's get different
   issues and owners."
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

(defn with-death-message
  "RESULT r with the server's death line for bot name (gym/death-message from the server's log,
   the file :server-log names) as :death-message, when the run ended :death, and its :reason the
   class of that line (:death-blaze, :death-lava ..., death-class; :death-other with no line):
   the signature [stage reason] names the cause. r otherwise. The cause the bot cannot tell: it is
   the server's word."
  [{:keys [server-log]} name r]
  (if (= :death (:reason r))
    (let [f (some-> server-log
                    io/file)
          msg (when (and f (.exists f))
                (try (gym/death-message (slurp f) name) (catch Exception _ nil)))]
      (cond-> (assoc r :reason (keyword (str "death-" (clojure.core/name (death-class msg)))))
        msg (assoc :death-message msg)))
    r))

(defn play-one!
  "One bot's whole life in a slot: the next serial's name (serials, an atom shared by the slots),
   its own atom and queue, on the board (live) and watched for milestones while it plays, landed on
   landing serial - 1 of the set (land-bot!), and played until its goal, its plan failing, a crash
   or the job's end (deadline): no cap of its own. Its RESULT line is printed as it ends, marked
   cut (failure/cut) when the job's end stopped it. Returns {:name :result :truth}."
  [{:keys [server ch centre serials live watcher prefix record-dir commit deadline]}]
  (let [n (swap! serials inc)
        name (bot-name prefix n)
        queue (async/chan events-buffer)
        world* (atom (game/init {:host (:host server) :port (:port server) :name name}))]
    (swap! live assoc name world*)
    ((:watch watcher) name world*)
    (land-bot! server ch centre name queue (dec n))
    (try (let [truth (promise)
               {r :result}
               (main/run-bot!
                {:host (:host server)
                 :port (:port server)
                 :name name
                 :until (:until server)
                 :chain true
                 :end-on-death? (fortress? (:from server))
                 :timeout-ms (max 1000 (- deadline (System/currentTimeMillis)))
                 :record (str (io/file record-dir (str name ".edn")))
                 :commit commit
                 :events queue
                 :world* world*
                 :on-result
                 (fn [r]
                   (deliver truth (truth! server name))
                   (println "RESULT" name (pr-str (marked (with-death-message server name r)))) ; what bb failures groups by
                   (flush))})]
           {:name name :result r :truth @truth})
         (catch Exception e
           (cli/log name "failed:" e)
           (let [r {:ok false
                    :reason :crashed
                    :error (ex-message e)
                    :until (:until server)
                    :plan (plan/summary @world*)}]
             (println "RESULT" name (pr-str (marked r))) ; filed at the stage it crashed in (issue #152)
             (flush)
             {:name name :result r}))
         (finally ((:unwatch watcher) world*) (swap! live dissoc name)))))

(defn accepts?
  "Does the server take a connection on its game port now (within 2 s)?"
  [{:keys [^String host port]}]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. host (int port)) 2000)
         true)
       (catch Exception _ false)))

(defn ended!
  "Fold a bot's RESULT r into the server's health (its atom :health*, by health); the slot whose
   bot turns it down prints it once, loudly, on the board (the job's log shows BOARD lines)."
  [{:keys [health*]} r]
  (let [[before after] (swap-vals! health* health r)]
    (when (and (:race/down? after) (not (:race/down? before)))
      (println "BOARD SERVER DOWN:"
               dead-after
               "bots in a row never got into play; its slots start none until it accepts again,"
               "asking every"
               (quot probe-every-ms 1000)
               "s")
      (flush))))

(defn probe!
  "Ask a down server whether it accepts again; when it does, its health is recovered, and the
   slot that finds it so says it on the board and sets the game rules again (a restarted server,
   race.yml's watch, may have lost them)."
  [{:keys [server health*]}]
  (when (accepts? server)
    (let [[before _] (swap-vals! health* (constantly recovered))]
      (when (:race/down? before)
        (println "BOARD SERVER BACK: it accepts again; its slots start bots")
        (flush)
        (set-gamerules! server)))))

(defn start-turn!
  "Take this slot's turn on the server's start clock (an atom of the last start, next-start) and
   sleep until it comes."
  [{:keys [starts]}]
  (let [now (System/currentTimeMillis)
        at (swap! starts next-start now)]
    (Thread/sleep (long (max 0 (- at now))))))

(defn slot!
  "One bot slot, on a thread of its own: it joins at its place in the schedule (start-at), then
   plays bot after bot (play-one!), the next the moment the last ends, or after backoff-ms when
   the last never got to play, never sooner than its turn on the server's start clock
   (start-turn!), until less than min-play-ms is left of the job. While the server is down
   (health: dead-after bots in a row never got into play) it starts none and asks every
   probe-every-ms whether the server accepts again. Returns a channel of its results."
  [{:keys [config t0 deadline health*] :as ctx} i]
  (async/thread
   (Thread/sleep (long (max 0 (- (+ t0 (start-at config i)) (System/currentTimeMillis)))))
   (loop [results []
          wait 0]
     (cond (< (- deadline (System/currentTimeMillis)) min-play-ms) results
           (:race/down? @health*)
           (do (Thread/sleep (long probe-every-ms)) (probe! ctx) (recur results 0))
           :else
           (let [_ (Thread/sleep (long wait))
                 _ (start-turn! ctx)
                 t (System/currentTimeMillis)
                 r (play-one! ctx)]
             (ended! ctx (:result r))
             (recur (conj results r)
                    (long (backoff-ms wait (- (System/currentTimeMillis) t) (:result r)))))))))

(defn -main
  "Run the race ci/race.edn (or --config) describes on the server at --port, its RCON at
   --rcon-port with --rcon-pass, for the job's length (run-ms): :race/bots slots, each refilled
   with a fresh bot the moment its bot ends, so the server always carries its bots (issue #127);
   the board, the sampler and the milestones watch whichever bots play now. A RESULT line per
   bot as it ends, a RACE summary of every bot at the end, written to the record directory.
   Exits 0 when any bot reached the target."
  [& args]
  (let [o (cli/options args)
        {:keys [config host port rcon-port rcon-pass record-dir commit seed landing-offset]
         :or {config "ci/race.edn" host "127.0.0.1" record-dir "data/race"}}
        o
        runner (get o :runner)
        which (get o :server) ; which of the runner's servers (--server); server below is the one connected to
        landings-file (get o :landings)
        config (edn/read-string (slurp config))
        {:race/keys [bots prefix until]} config
        t0 (System/currentTimeMillis)
        server (cond-> {:host host
                        :port (Long/parseLong port)
                        :rcon-port (Long/parseLong rcon-port)
                        :rcon-pass rcon-pass
                        :until until
                        :from (or (get o :from) (:race/from config))
                        :bots bots
                        :server-log (str "work/world-" (or which 1) "/logs/latest.log")
                        :kit (:race/kit config)}
                 landings-file
                 (assoc :landings (landings/read-landings landings-file)
                        :landing-offset (parse-long (or landing-offset "0"))))
        _ (.mkdirs (io/file record-dir))
        where {:runner (some-> runner
                               parse-long)
               :server (some-> which
                               parse-long)}
        done (promise)
        live (atom {})
        ctx {:server server
             :ch (claims!)
             :centre (promise)
             :serials (atom 0)
             :health* (atom recovered)
             :starts (atom 0)
             :live live
             :watcher (milestones! (assoc where :seed seed) record-dir)
             :prefix prefix
             :record-dir record-dir
             :commit commit
             :config config
             :t0 t0
             :deadline (+ t0 (run-ms config))}]
    (cli/log "race:" bots "slots to" until "seed" seed "for" (quot (run-ms config) 60000) "min")
    (set-gamerules! server)
    (when (fortress? (:from server)) (prepare! server bots))
    (board! server live done)
    (sampler! server where live record-dir done)
    (let [slots (mapv #(slot! ctx %) (range bots))
          results (into [] (mapcat async/<!!) slots)
          s (cond-> (assoc (summary results) :race/seed seed :race/commit commit :race/until until)
              (:server where) (assoc :race/server (:server where)))]
      (deliver done true)
      ((:stop (:watcher ctx)))
      (println "RACE" (pr-str (dissoc s :race/runs)))
      (spit (io/file record-dir "summary.edn") (pr-str s))
      (flush)
      (async/close! (:ch ctx))
      (shutdown-agents)
      (System/exit (if (pos? (:race/ok s)) 0 1)))))
