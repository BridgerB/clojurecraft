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
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.land :as land :refer
             [at-offset dimension-of fill-in in-dimension land! wait-in!]]
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
(s/def :truth/result (s/coll-of keyword? :kind vector?)) ; a path into the bot's RESULT whose value {result} in :truth/re stands for
(s/def :truth/check keyword?)           ; how a reply is judged when a pattern cannot say (verdict): :re when absent
(s/def :truth/at (s/coll-of keyword? :kind vector?)) ; a path into the bot's RESULT to an [x y z] that {at} in :truth/cmd stands for
(s/def :truth/reply string?)
(s/def :truth/ok? boolean?)
(s/def :gym/truth
  (s/coll-of (s/keys :req [:truth/cmd :truth/re] :opt [:truth/result :truth/check :truth/at])
             :kind vector?
             :min-count 1))
(s/def :gym/setup (s/coll-of string? :kind vector?)) ; RCON commands that build the row's arena round the landing (fill-in)
(s/def :gym/land keyword?)               ; how the bot is landed (land!): :surface when absent
(s/def :gym/summon (s/coll-of string? :kind vector?)) ; RCON commands run in the row's dimension once the bot stands there with its kit (fill-in): the mobs it meets
(s/def :gym/difficulty #{"peaceful" "easy" "normal" "hard"}) ; set before the bot is landed
(s/def :gym/fixture? true?)              ; a row that judges the landing alone: its --until puts no goal in play
(s/def :gym/name string?)                ; a row's own name, for a row that shares its goal and --until with another
(s/def :gym/goals (s/coll-of keyword? :kind vector? :min-count 1)) ; the stages a chained row plays in order
(s/def ::row
  (s/keys :req [:gym/goal :gym/until :gym/prereqs :gym/timeout-ms :gym/truth]
          :opt
          [:gym/setup :gym/land :gym/summon :gym/difficulty :gym/fixture? :gym/name :gym/goals]))
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

(def trial-ms 18000000)                 ; every trial's cap: 5 hours, a plan job's whole run less setup. A trial ends when the bot finishes or its plan fails; a cap shorter than the job only hides how slow a stage is

(defn summon
  "The command that summons entity (a name: \"zombie\") at the landing moved by d [dx dy dz],
   with nbt (SNBT) when given: a row's :gym/summon entry."
  ([entity d] (summon entity d nil))
  ([entity d nbt] (str "summon minecraft:" entity " " (at-offset d) (when nbt (str " " nbt)))))

(defn no-ai-zombie
  "A zombie that never moves or attacks (NoAI) and never despawns, at d: the first thing a fight row
   kills."
  [d]
  (summon "zombie" d "{NoAI:1b,PersistenceRequired:1b}"))

(defn blaze-spawner
  "A blaze spawner at d, ruststeve's arena-run.sh numbers: two at a time, six at most near it,
   while a player is within 16, every two to four seconds."
  [d]
  (str
   "setblock "
   (at-offset d)
   " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}},SpawnCount:2,MaxNearbyEntities:6,RequiredPlayerRange:16,SpawnRange:2,MinSpawnDelay:40,MaxSpawnDelay:80}"))

(defn slow-blaze-spawner
  "A blaze spawner at d that makes one blaze at a time, every three to six seconds, two at most near
   it."
  [d]
  (str
   "setblock "
   (at-offset d)
   " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}},SpawnCount:1,MaxNearbyEntities:2,RequiredPlayerRange:16,SpawnRange:2,MinSpawnDelay:60,MaxSpawnDelay:120}"))

(defn endermen
  "n endermen at d that never despawn: the pearls' other source, the End's crowd."
  [n d]
  (vec (repeat n (summon "enderman" d "{PersistenceRequired:1b}"))))

(def dragon
  "A dragon of known state at the fountain: any other gone first, the exit portal its death opens
   shut again, then one summoned with full health and DragonPhase 0 (circling). A summoned dragon
   has no fight manager and starts in phase 10, hovering, so the siblings set both (ruststeve
   gym.rs, steve gym/registry.ts)."
  ["kill @e[type=minecraft:ender_dragon]"
   "fill -2 55 -2 2 70 2 minecraft:air replace minecraft:end_portal"
   "summon minecraft:ender_dragon 0 80 0 {DragonPhase:0,Health:200f}"])

(defn crystals
  "End crystals at each of ds (offsets from the landing), every other crystal gone first: a
   crystal row knows exactly what it must break."
  [ds]
  (into ["kill @e[type=minecraft:end_crystal]"]
        (map #(summon "end_crystal" % "{ShowBottom:0b}"))
        ds))

(def no-crystals "Kills every end crystal loaded." "kill @e[type=minecraft:end_crystal]")

(defn spike-tops
  "The fills that take blocks of kind (a name) off the ten spikes' tops round each one's middle
   (end/spikes, the same as the game's)."
  [kind]
  (vec (for [{[x z] :spike/center} end/spikes]
         (str "fill " (- x 2)
              " 60 " (- z 2)
              " " (+ x 2)
              " 110 " (+ z 2)
              " minecraft:air replace minecraft:" kind))))

(def spike-cages
  "A :gym/setup, run with the island forceloaded: every crystal gone (a crystal lights fire in its
   own cell each tick, a block over its bedrock), then the iron cages and the fire taken off the
   spikes' tops, so a crystal summoned on top stands on its bedrock."
  (into [no-crystals] (concat (spike-tops "iron_bars") (spike-tops "fire"))))

(def spike-crystals
  "Summons: the dragon and every crystal gone again (the setup's kill misses one whose entities
   have not loaded yet: gym dragon-a's killed five of ten), the fire off the tops again, then a
   crystal on each spike's top, the highest block of its middle column, its bedrock: where the
   dragon fight puts them. An integer x and z a positioned names is a block's middle already;
   dragon-a's ~0.5 put every crystal on the corner of its column."
  (into ["kill @e[type=minecraft:ender_dragon]" no-crystals]
        (concat (spike-tops "fire")
                (for [{[x z] :spike/center} end/spikes]
                  (str "execute positioned " x
                       " 0 " z
                       " positioned over world_surface run summon minecraft:end_crystal ~ ~ ~"
                       " {ShowBottom:0b}")))))

(def night "time set midnight")       ; a :gym/summon entry: the hostiles' hour, for a row that defends

(def fire-resistance
  "A :gym/summon entry: the bot never burns (a fireball's hit still hurts)."
  "effect give {name} minecraft:fire_resistance 1000000 0 true")

(def invulnerable
  "What makes the bot take no harm, as :gym/summon entries: Resistance V (every hurt cut to
   nothing) and Fire Resistance, for a row that measures a fight and not the bot's survival
   (ruststeve's arena gave its bot Invulnerable:1b, which no command can set on a player)."
  ["effect give {name} minecraft:resistance 1000000 4 true" fire-resistance])

(def iron-armour
  "Iron armour put on the bot, as :gym/summon entries: given after the dimension change with the
   kit (item replace, since a mob at hand comes before dressing)."
  ["item replace entity {name} armor.head with minecraft:iron_helmet"
   "item replace entity {name} armor.chest with minecraft:iron_chestplate"
   "item replace entity {name} armor.legs with minecraft:iron_leggings"
   "item replace entity {name} armor.feet with minecraft:iron_boots"])

(def race-armour
  "The armour the race bot makes (gear.clj): iron helmet, chestplate and leggings, no boots."
  (vec (take 3 iron-armour)))

(def no-fire
  "A :gym/summon entry: the blazes' fireballs set no blocks alight (SmallFireball places fire only
   when mob griefing is on). A fire lights the cells round it past 11, and a spawner's blaze spawns
   only where the light is 11 or less. The bot itself still burns when hit."
  "gamerule mob_griefing false")

(defn rods-held
  "A truth that the bot holds n blaze rods or more (n under 10)."
  [n]
  {:truth/cmd "clear {name} minecraft:blaze_rod 0" :truth/re (str "Found ([" n "-9]|[1-9][0-9]) ")})
(defn no-ai-enderman
  "An enderman that never moves, attacks or teleports (NoAI) and never despawns, at d: the first
   thing the pearl rows kill."
  [d]
  (summon "enderman" d "{NoAI:1b,PersistenceRequired:1b}"))

(def pearl-kit
  "What a pearl row gives: an iron sword, two iron pickaxes for the burrows the hunt fights from,
   the iron helmet and chestplate #259's armour row makes (put on by the :wear-armour row), and food
   for the fights' hunger: what a chained run carries by then. Two pickaxes because the chain's
   :cast-one and :portal rows keep a spare (:goal/spares, #485), so a run comes into the Nether
   holding two, and because one is 250 digs."
  ["minecraft:iron_sword 1" "minecraft:iron_pickaxe 2" "minecraft:iron_helmet 1"
   "minecraft:iron_chestplate 1" "minecraft:cooked_beef 16"])

(def pearl-arena
  "The live pearl row's arena: a block of stone 27 square from eight under the landing to six over
   it, the room 25 square and seven high carved out of it (a floor eight deep, a wall one thick and
   seven high round it), open to a clear midnight sky, light blocks five over the floor: the burrow
   is cut in stone, the endermen stand ten blocks off, far enough to be provoked by a stare
   (pearls/stare-from) and never by accident, no sun or rain on them (either sends an enderman off),
   and no dark for a zombie to spawn in (a monster spawns only at block light 0). The sealed Nether
   room is seven square. The wall: the lit square's light reaches ten blocks past its edge and no
   farther, the dark beyond spawns zombies, skeletons and creepers at midnight, and they walked in,
   over the row's 300 s, to the burrow's stairs. Walled, nothing walks in, no arrow has a line, and
   the endermen, which teleport only in daylight or when hurt by what is not alive, stay on the lit
   square. No roof: a surface landing teleports onto the highest motion-blocking block, which a
   light block is not."
  ["time set midnight" "weather clear"
   "fill {x-13} {y-8} {z-13} {x+13} {y+6} {z+13} minecraft:stone"
   "fill {x-12} {y} {z-12} {x+12} {y+6} {z+12} minecraft:air"
   "fill {x-12} {y+5} {z-12} {x+12} {y+5} {z+12} minecraft:light[level=15]"])

(defn pearls-held
  "A truth that the bot holds n or more ender pearls (re, a pattern of the count: \"[1-9]\")."
  [re]
  {:truth/cmd "clear {name} minecraft:ender_pearl 0" :truth/re (str "Found " re " ")})

(def portal-arena
  "The portal rows' arena round the landing: stone ten deep under air, and a trench of lava sources
   one wide set into its surface east of the landing, every source with stone beside it to refill
   from (a scoop takes a source for good)."
  ["fill {x-8} {y-10} {z-8} {x+16} {y-1} {z+16} minecraft:stone"
   "fill {x-8} {y} {z-8} {x+16} {y+14} {z+16} minecraft:air"
   "fill {x+14} {y-1} {z-6} {x+14} {y-1} {z+6} minecraft:lava"])

(def portal-kit
  "What a portal row gives: two buckets of lava, one of water, an empty one, a handful of
   cobblestone (a cast cut into the rock places four, the walls its own holes opened), flint and
   steel, and the stone pickaxe the rows hold for the digs."
  ["minecraft:lava_bucket 2" "minecraft:water_bucket 1" "minecraft:bucket 1"
   "minecraft:cobblestone 8" "minecraft:flint_and_steel 1" "minecraft:stone_pickaxe 1"])

(defn block-count
  "A truth that counts the blocks named block in the arena, from the floor of its stone to over
   its air (the frame is cut into the stone): a clone of it filtered to them, to air high over it,
   whose reply says how many (re, a pattern of that reply)."
  [block re]
  {:truth/cmd (str "clone {x-8} {y-10} {z-8} {x+16} {y+12} {z+16} {x-8} {y+60} {z-8} filtered "
                   block)
   :truth/re re})

(defn piece
  "The fill-in placeholders for cell [px py pz] of the stronghold's portal room piece
   (StrongholdPieces.PortalRoom, 11 by 8 by 16): the bot lands at its (5, 1, 2), on the room's
   floor inside its door, so the piece's origin is [-5 -1 -2] from the landing."
  [[px py pz]]
  (at-offset [(- px 5) (dec py) (- pz 2)]))

(defn piece-fill
  "The fill command from piece cell a to piece cell b with block (a name, with its states and
   fill mode as the command takes them)."
  [a b block]
  (str "fill " (piece a) " " (piece b) " minecraft:" block))

(def portal-room
  "The vanilla portal room round the landing, block for block from StrongholdPieces.PortalRoom: a
   hollow box of stone bricks, the staircase of three stairs blocks up to the frames' height with
   the spawner in the middle of its top step (a bare spawner spawns nothing), the platform with the
   3x3 lava pit, the two lava troughs by the door, and the twelve frames floating two over the
   floor, each facing inward as the game's own do (the northern three face south:
   EndPortalFrameBlock's pattern takes no other facing). The iron bars are left out: nothing need
   look in."
  [(piece-fill [0 0 0] [10 7 15] "stone_bricks hollow") (piece-fill [4 1 5] [6 1 7] "stone_bricks")
   (piece-fill [4 2 6] [6 2 7] "stone_bricks") (piece-fill [4 3 7] [6 3 7] "stone_bricks")
   (piece-fill [4 1 4] [6 1 4] "stone_brick_stairs[facing=south]")
   (piece-fill [4 2 5] [6 2 5] "stone_brick_stairs[facing=south]")
   (piece-fill [4 3 6] [6 3 6] "stone_brick_stairs[facing=south]")
   (piece-fill [3 1 8] [7 1 12] "stone_bricks") (piece-fill [4 1 9] [6 1 11] "lava")
   (piece-fill [1 1 1] [1 1 3] "lava") (piece-fill [9 1 1] [9 1 3] "lava")
   (piece-fill [4 3 8] [6 3 8] "end_portal_frame[facing=south]")
   (piece-fill [4 3 12] [6 3 12] "end_portal_frame[facing=north]")
   (piece-fill [3 3 9] [3 3 11] "end_portal_frame[facing=east]")
   (piece-fill [7 3 9] [7 3 11] "end_portal_frame[facing=west]")
   (str "setblock " (piece [5 3 6]) " minecraft:spawner")])

(defmulti gym-setup
  "What a builder form of the registry ([:kind args...], gyms.edn) expands to: a vector of the
   commands, kit items or truth maps it stands for. Keyed by the form's first keyword; a new
   builder is a defmethod."
  {:arglists '([kind & args])}
  (fn [kind & _] kind))

(defmethod gym-setup :summon [_ & args] [(apply summon args)])
(defmethod gym-setup :no-ai-zombie [_ d] [(no-ai-zombie d)])
(defmethod gym-setup :blaze-spawner [_ d] [(blaze-spawner d)])
(defmethod gym-setup :slow-blaze-spawner [_ d] [(slow-blaze-spawner d)])
(defmethod gym-setup :endermen [_ n d] (endermen n d))
(defmethod gym-setup :no-ai-enderman [_ d] [(no-ai-enderman d)])
(defmethod gym-setup :dragon [_] dragon)
(defmethod gym-setup :crystals [_ ds] (crystals ds))
(defmethod gym-setup :no-crystals [_] [no-crystals])
(defmethod gym-setup :spike-cages [_] spike-cages)
(defmethod gym-setup :spike-crystals [_] spike-crystals)
(defmethod gym-setup :fire-resistance [_] [fire-resistance])
(defmethod gym-setup :invulnerable [_] invulnerable)
(defmethod gym-setup :iron-armour [_] iron-armour)
(defmethod gym-setup :race-armour [_] race-armour)
(defmethod gym-setup :no-fire [_] [no-fire])
(defmethod gym-setup :rods-held [_ n] [(rods-held n)])
(defmethod gym-setup :pearls-held [_ re] [(pearls-held re)])
(defmethod gym-setup :block-count [_ block re] [(block-count block re)])
(defmethod gym-setup :pearl-kit [_] pearl-kit)
(defmethod gym-setup :portal-kit [_] portal-kit)
(defmethod gym-setup :pearl-arena [_] pearl-arena)
(defmethod gym-setup :portal-arena [_] portal-arena)
(defmethod gym-setup :portal-room [_] portal-room)

(defn expand
  "entries with each builder form (a vector whose first element is a keyword) replaced by what
   gym-setup makes of it, in place; any other entry (a string, a map) stays as it is."
  [entries]
  (into []
        (mapcat (fn [e] (if (and (vector? e) (keyword? (first e))) (apply gym-setup e) [e])))
        entries))

(defn expand-row
  "A registry row as gyms.edn writes it, with its builder forms expanded in :gym/prereqs,
   :gym/setup, :gym/summon and :gym/truth, and :gym/timeout-ms trial-ms when it names none."
  [row]
  (reduce (fn [row k] (cond-> row (contains? row k) (update k expand)))
          (merge {:gym/timeout-ms trial-ms} row)
          [:gym/prereqs :gym/setup :gym/summon :gym/truth]))

(def gyms
  "The gym registry, resources/clojurecraft/gyms.edn with its builder forms expanded (expand-row):
   which goal, the --until that runs it, what to give the bot first, how long it may take, and the
   truth commands that must all pass. Plain data: printable and readable back."
  (mapv expand-row (edn/read-string (slurp (io/resource "clojurecraft/gyms.edn")))))

(def counted
  "Outcomes that say something about the goal; :harness and :disconnect do not."
  #{:pass :fail :timeout :death})

(def strict-failures #{:fail :timeout :death}) ; the outcomes that fail a strict (push) trial
(def bot-heap "-Xmx1g")                 ; each bot is its own JVM; one fits several on a runner
(def hold-ms 60000)                     ; how long an ok bot stays online for the judge's truth commands

(def bearing-within 10.0)                ; degrees a throw's line may be off the way to the located stronghold
(def z95 1.959964)                      ; the normal quantile for a two-sided 95% interval
(def judge-grace-ms 120000)             ; past the goal's timeout, how long the judge waits for RESULT
(def trial-overhead-minutes 8)          ; setup, server start and judging, on top of a goal's timeout

;; ---------------------------------------------------------------- data

(defn gym
  "The registry row for a goal, named by its keyword or its --until name, or a row by its own
   :gym/name (a row that shares both with another); the first that answers, or nil."
  [goal]
  (let [n (name goal)]
    (some #(when (or (= n (:gym/name %)) (= n (name (:gym/goal %))) (= n (:gym/until %))) %) gyms)))

(defn kit
  "The commands that give row's prerequisites to bot: run only once the server says the bot is in
   the row's dimension. steve gave its End kit before the cross-dimension teleport and the server
   held none of it while the client counted six beds; so the gym lands, waits for the dimension,
   then gives."
  [row bot]
  (mapv #(str "give " bot " " %) (:gym/prereqs row)))

(defn summons
  "The row's :gym/summon commands for bot at landing, filled in and run in its dimension: after
   the kit, so a mob never meets a bot with empty hands."
  [row bot landing]
  (mapv #(in-dimension (dimension-of row) (fill-in % bot landing)) (:gym/summon row)))

(defn truth-cmd
  "A row's truth command for the bot's name and the run's landing (fill-in), and, given the bot's
   RESULT, {at} the block [x y z] at the row's :truth/at path in it written \"x y z\"; left as
   written when the RESULT has none, which no server takes."
  ([row bot landing] (truth-cmd row bot landing nil))
  ([{:truth/keys [cmd at]} bot landing result]
   (let [p (when at (get-in result at))]
     (cond-> (fill-in cmd bot landing)
       (and p (= 3 (count p)))
       (str/replace "{at}" (str/join " " (map #(long (math/floor %)) p)))))))

(defn truth-re
  "A truth row's pattern for the bot's RESULT: the row's :truth/re, or, when the row names a
   :truth/result path, that pattern with {result} the value at the path, quoted, so the server must
   say what the bot saw (the entities trial); nil when the RESULT has no value there, which no reply
   passes."
  [{:truth/keys [re] path :truth/result} result]
  (let [v (when path (get-in result path))]
    (cond (nil? path) re
          (some? v) (str/replace re "{result}" (java.util.regex.Pattern/quote (str v)))
          :else nil)))

(defmulti verdict
  "Does the server's reply pass truth row, given the bot's RESULT? Keyed by the row's :truth/check,
   :re when absent: a check a pattern cannot state is a defmethod."
  {:arglists '([row reply result])}
  (fn [row _reply _result] (:truth/check row :re)))

(defmethod verdict :re
  ;; the row's pattern (truth-re, the RESULT's value in it when the row names one) found in the reply
  [row reply result]
  (let [re (truth-re row result)] (boolean (and re (re-find (re-pattern re) (str reply))))))

(defn bearing-off
  "Degrees between the way dir [dx dz] on the plane and the way from point from [x y z] to [sx sz]."
  [[fx _ fz] [dx dz] [sx sz]]
  (let [a (math/atan2 dz dx)
        b (math/atan2 (- sz fz) (- sx fx))
        d (abs (math/to-degrees (- a b)))]
    (min d (- 360.0 d))))

(defmethod verdict :bearing
  ;; the row's pattern captures the located [x z] in the reply (locate structure); its
  ;; :truth/result names the throws in the RESULT ({:throw/from :throw/dir} each): the first's
  ;; line within bearing-within degrees of the way from where it was thrown to what the server
  ;; located
  [row reply result]
  (let [[_ x z] (re-find (re-pattern (:truth/re row)) (str reply))
        {:throw/keys [from dir]} (first (get-in result (:truth/result row)))]
    (boolean
     (and x from dir (<= (bearing-off from dir [(parse-long x) (parse-long z)]) bearing-within)))))

(defn truth
  "A truth row's verdict on the server's reply to cmd (the row's command, filled in), given the
   bot's RESULT when the row compares with it (verdict): {:truth/cmd :truth/reply :truth/ok?}."
  ([row cmd reply] (truth row cmd reply nil))
  ([row cmd reply result]
   {:truth/cmd cmd :truth/reply (str reply) :truth/ok? (verdict row reply result)}))

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
  (cond-> {:gym/goal goal
           :gym/run run
           :gym/landing landing
           :gym/landed? (boolean landed?)
           :gym/outcome (outcome left)
           :gym/ms ms
           :gym/reason (:reason result)
           :gym/truths (vec truths)
           :gym/commit commit
           :gym/result result}
    (:death-message result) (assoc :gym/death-message (:death-message result))))

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

(defn trial-name
  "The name a trial carries across the job boundary for row, which gym finds it by again: its own
   :gym/name when it has one, else its --until name."
  [row]
  (:gym/name row (:gym/until row)))

(defn plan-matrix
  "The trial matrix for goals x runs as JSON, {\"include\": [{\"goal\", \"run\", \"minutes\"}]}.
   Only goals (a row's :gym/name, else its --until name), run numbers and the job's minutes cross
   the job boundary; each trial recomputes the rest from the registry."
  [goals runs]
  (str "{\"include\":["
       (str/join ","
                 (for [g goals
                       :let [row (gym g)]
                       run (range 1 (inc runs))]
                   (str "{\"goal\":\""
                        (trial-name row)
                        "\",\"run\":"
                        run
                        ",\"minutes\":"
                        (+ trial-overhead-minutes (quot (:gym/timeout-ms row) 60000))
                        "}")))
       "]}"))

;;;; I/O: RCON, files, stdout ;;;;

(defn landing!
  "The fixture's work for one run: wait for the bot, set the row's difficulty, reset it (survival,
   empty inventory), land it (land!: on this run's landing of the set, or where the row starts from
   it), and only once the server says it stands in the row's dimension give its prerequisites (kit)
   and summon what it meets (summons). Returns what happened: {:gym/landed? true :gym/landing
   :gym/at :gym/started}, or {:gym/landed? false :gym/error ...}; never throws. A bot that never
   reaches the dimension is a failed landing, never a kit given in the wrong world."
  [{:keys [goal run name host rcon-port rcon-pass landings]}]
  (let [row (gym goal)
        landing (landings/landing-for (landings/read-landings landings) (parse-long (str run)))
        started (System/currentTimeMillis)]
    (try (rcon/with-rcon
          (or host "127.0.0.1")
          (parse-long (str rcon-port))
          rcon-pass
          (fn [rc]
            (when-not (fixture/wait-online! rc name fixture/online-timeout)
              (throw (ex-info "bot never came online" {:name name})))
            (when-let [d (:gym/difficulty row)] (rcon/command! rc (str "difficulty " d)))
            (rcon/command! rc (str "gamemode survival " name))
            (rcon/command! rc (str "clear " name))
            (let [landing (land! rc row name landing)
                  dim (dimension-of row)]
              (when-not (wait-in! rc name dim)
                (throw (ex-info "the bot never reached its dimension" {:name name :dimension dim})))
              (doseq [c (concat (kit row name) (summons row name landing))] (rcon/command! rc c))
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

(defn find-server-log
  "The server log file (latest.log or server.log) under the world directory, or nil when none exists."
  [dir]
  (let [candidates (concat (when-let [w (System/getenv "WORLD")]
                             [(io/file w "logs" "latest.log") (io/file w "server.log")])
                           (when-let [parent (some-> (io/file dir)
                                                     .getParentFile)]
                             [(io/file parent "world" "logs" "latest.log")
                              (io/file parent "world" "server.log")])
                           [(io/file "work" "world" "logs" "latest.log")
                            (io/file "work" "world" "server.log")
                            (io/file "world" "logs" "latest.log") (io/file "world" "server.log")])]
    (first (filter (fn [^java.io.File f] (and (.exists f) (pos? (.length f)))) candidates))))

(defn death-message
  "The server death message for player name from the server log text, or nil when none."
  [log-text name]
  (let
    [pattern
     (re-pattern
      (str
       "(?m)^\\[[^\\]]+\\] \\[Server thread/INFO\\]: ("
       (java.util.regex.Pattern/quote name)
       " (?:was fireballed by|tried to swim in lava|was slain by|burned to death|was burned to a crisp|walked into fire|went up in flames|fell from|hit the ground|suffocated|was blown up|was obliterated|was shot by|was killed by|withered away|starved to death|drowned|died).*)$"))]
    (when-let [m (re-find pattern log-text)] (second m))))

(defn await-result!
  "Wait for the bot's RESULT promise to be delivered, or timeout. When row lands in the Nether,
   periodically polls the server log: if the bot died, delivers an early failure :death, destroys
   the bot process p, and finishes without running for hours after respawning in the Overworld."
  [result timeout-ms row name dir ^Process p]
  (let [nether? (= dimension/nether (dimension-of row))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (realized? result) @result
            (>= (System/currentTimeMillis) deadline) nil
            :else
            (do (Thread/sleep 1000)
                (let [log-file (and nether? (some? p) (.isAlive p) (find-server-log dir))
                      msg (when log-file (death-message (slurp log-file) name))]
                  (if msg
                    (do (.destroy p)
                        (deliver
                         result
                         {:ok false :reason :death :death-message msg :stats {:stats/deaths 1}})
                        @result)
                    (recur))))))))

(defn judge!
  "Judge one run: wait for the bot's RESULT (result, a promise tee-output! delivers), then read what the fixture left (a missing landed
   file is a landing that never happened), ask the server each truth command while the bot
   still holds, write the result row and print it as one GYMRESULT line. Returns the row."
  [{:keys [goal run name host rcon-port rcon-pass result landed out wait-ms commit process dir]}]
  (let [row (gym goal)
        timeout (if wait-ms (parse-long wait-ms) (+ (:gym/timeout-ms row) judge-grace-ms))
        result (if (and process dir)
                 (await-result! result timeout row name dir process)
                 (deref result timeout nil))
        left (if (.exists (io/file landed))
               (edn/read-string (slurp landed))
               {:gym/landed? false :gym/error "no landed file"})
        truths (when result
                 (try (rcon/with-rcon (or host "127.0.0.1")
                                      (parse-long (str rcon-port))
                                      rcon-pass
                                      (fn [rc]
                                        (mapv #(let [c
                                                     (truth-cmd % name (:gym/landing left) result)]
                                                 (truth % c (rcon/command! rc c) result))
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

(defn go-event
  "The :go event for row at landing at: a chained :go over :gym/goals when the row names a chain
   of stages, else fixture/go-event for its single :gym/until."
  [row at]
  (if-let [gs (:gym/goals row)]
    (cond-> {:event/kind :go :go/goals gs :go/chain? true} at (assoc :go/at at))
    (fixture/go-event (:gym/until row) at)))

(defn attempt!
  "One bot on a running server: start it as its own JVM (classpath cp, in bot-dir), land it
   (landing!) and send its :go through its stdin (none for a fixture row), judge it from its
   log and the server (judge!), and leave result.edn, landed.edn, bot.log and run.edn in dir.
   Returns the result row with tags merged in (the plan's experiment, arm and unit)."
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
      (when-not (:gym/fixture? row) ; a fixture row plays nothing: a :go with no goals is no event
        (.write w (str (pr-str (go-event row (:gym/at landed))) "\n"))))
    (let [tagged (merge (judge! {:goal goal
                                 :run run
                                 :name name
                                 :rcon-port rcon-port
                                 :rcon-pass pass
                                 :result result
                                 :landed (str landed-file)
                                 :out (str (io/file dir "result.edn"))
                                 :commit commit
                                 :process p
                                 :dir dir})
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
