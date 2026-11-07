(ns clojurecraft.land
  "Landing a bot where a run starts, over RCON: the pieces the gym (gym.clj) and the race (race.clj)
   share. `land!` is the open multimethod that puts a gym row's bot where its row starts (a defmethod
   a kind of start); the helpers under it (`place!`, `wait-in!`, `fill-in`, the pad and arena
   templates, the warped-forest floor) build a place and stand the bot on it; and the race's fortress
   landings (`land-fortress!`, `land-forest!`, `prepare!`) choose a pad round a located fortress,
   build it, clear its lava and give the start's kit. Everything here takes an RCON session."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.food :as food]
            [clojurecraft.landings :as landings]
            [clojurecraft.rcon :as rcon]))

(set! *warn-on-reflection* true)

(def arena-reach 16)                  ; blocks round the landing an arena's setup is forceloaded over

(def fortress-offset 48)              ; blocks west of a located fortress the Nether pad is carved

(def pad-y 72)                        ; the Nether pad's feet height: over the lava sea (y 31), under the roof

(def arena-half 5)                    ; the nether arena's half-width: rock two thick round a room seven square

(def end-at [8 0])                    ; [x z] of the End landing: the main island's end stone 8 east of the fountain, as ruststeve's End gyms start

(def end-reach 64)                    ; blocks round the fountain loaded for the End landing: the towers stand at radius 42

(def dimension-wait-ms 30000)         ; how long the kit waits for the server to say the bot is in its row's dimension

(def hungry-food 10)                  ; the food a :hungry landing waits for before the bot is told to go

(def warped-ranges [16 32 64 96])     ; blocks round a located warped forest a floor is spread for, nearer first

(def warped-tries 3)                  ; spreads at each range before a wider one

(def locate-offsets [256 512])        ; blocks off the landing's eighth, along each axis, another warped forest is located from when the nearest gives no floor

(def spread-under 120)                ; the height a Nether spread scans down from: under the roof's rock (y 123 up), over the forest's highest floors

(def dimensions
  "The dimension each kind of landing (:gym/land) leaves the bot in; any other is the overworld.
   The kit is given only once the server says the bot stands there (landing!)."
  {:nether-fortress dimension/nether
   :nether-fortress-open dimension/nether
   :nether-arena dimension/nether
   :warped-forest dimension/nether
   :end dimension/end})

(def fortress-pad
  "The fortress row's start: a hollow box of netherrack round three cells of air at the pad."
  ["fill {x-2} {y-1} {z-2} {x+2} {y+3} {z+2} minecraft:netherrack hollow"])

(def fortress-pad-open
  "The open fortress start: fortress-pad with a doorway, two cells high and three wide, cut in its
   east wall, so the bot walks out onto the Nether as a race bot arrives at a fortress, not sealed
   in a box."
  (conj fortress-pad "fill {x+2} {y} {z-1} {x+2} {y+1} {z+1} minecraft:air"))

(def nether-arena
  "A sealed room in the Nether, ruststeve's arena-run.sh recipe: netherrack two thick on every side
   round a room seven square and four high (an enderman stands in it, a blaze hovers), its floor at
   the landing's feet. Nothing outside reaches in: no lava, no ghast, no fall, so a row measures the
   fight and not the Nether. ruststeve built its blaze arena in the overworld because its SDK
   streamed Nether chunks too slowly after the cross-dimension teleport; ours reads the dimension's
   columns as they come."
  ["fill {x-5} {y-2} {z-5} {x+5} {y+5} {z+5} minecraft:netherrack"
   "fill {x-3} {y} {z-3} {x+3} {y+3} {z+3} minecraft:air"])

(defn offset
  "A fill-in placeholder for axis (\"x\") at d blocks from the landing: {x}, {x+3}, {x-3}."
  [axis d]
  (str "{"
       axis
       (cond (pos? d) (str "+" d)
             (neg? d) d
             :else "")
       "}"))

(defn at-offset
  "The three fill-in placeholders for the landing moved by [dx dy dz]."
  [[dx dy dz]]
  (str/join " " [(offset "x" dx) (offset "y" dy) (offset "z" dz)]))

(def stronghold-off 250)                ; blocks from the located stronghold a stronghold row lands the bot

(defn fill-in
  "cmd with {name} the bot's name and {x} {y} {z} the landing's coordinates, each with an offset
   when written {x+14} or {z-8}: an arena or a truth command placed relative to the landing."
  [cmd bot [x z y]]
  (-> cmd
      (str/replace "{name}" bot)
      (str/replace #"\{([xyz])([+-]\d+)?\}"
                   (fn [[_ axis off]]
                     (str (+ ({"x" x "y" y "z" z} axis) (if off (parse-long off) 0)))))))

(defn in-dimension
  "cmd run in the dimension named dim."
  [dim cmd]
  (str "execute in " dim " run " cmd))

(defn dimension-of
  "The dimension row's landing leaves the bot in (dimensions; the overworld for the rest)."
  [row]
  (get dimensions (:gym/land row) dimension/overworld))

(defn dimension-test
  "The test the server passes once the bot named bot stands in the dimension named dim."
  [bot dim]
  (str "execute as " bot " at @s if dimension " dim))

;;;; I/O: RCON, from here ;;;;

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

(defn wait-loaded-in!
  "Block until the server reports every column of the square [x0 z0 x1 z1] loaded in dim (its
   corners), polling every 200 ms, up to 30 s; true when they loaded."
  [rc dim [x0 z0 x1 z1]]
  (let [corners [[x0 z0] [x0 z1] [x1 z0] [x1 z1]]]
    (loop [i 0]
      (cond (every? (fn [[x z]]
                      (fixture/passed?
                       (rcon/command! rc (in-dimension dim (str "execute if loaded " x " 0 " z)))))
                    corners)
            true
            (>= i 150) false
            :else (do (Thread/sleep 200) (recur (inc i)))))))

(defn place!
  "Build cmds (fill-in templates) round at [x z y] in the dimension dim and stand the bot named
   name on at: the square r round it forceloaded and waited for (a box that crosses a chunk
   edge fills nothing in an unloaded chunk), the commands run, the bot teleported to the middle
   of at's cell, the forceload released after (the bot holds its chunks itself then). Returns at."
  [rc dim name [x z y :as at] r cmds]
  (let [square [(- x r) (- z r) (+ x r) (+ z r)]
        where (str/join " " square)]
    (rcon/command! rc (in-dimension dim (str "forceload add " where)))
    (try (when-not (wait-loaded-in! rc dim square)
           (throw (ex-info "the arena's chunks never loaded" {:dimension dim :at at})))
         (doseq [c cmds] (rcon/command! rc (in-dimension dim (fill-in c name at))))
         (cli/log "tp:"
                  (rcon/command! rc (in-dimension dim (str "tp " name " " x ".5 " y " " z ".5"))))
         (finally (rcon/command! rc (in-dimension dim (str "forceload remove " where)))))
    at))

(defn food-level
  "The bot's food as the server holds it (data get entity <name> foodLevel), or nil when the
   reply says none."
  [rc name]
  (some->> (rcon/command! rc (str "data get entity " name " foodLevel"))
           (re-find #"data: (\d+)")
           second
           parse-long))

(defmethod land! :hungry
  ;; the surface landing, then hunger at its strongest until the server says the food is under
  ;; hungry-food (at most 30 s), then no more: a bot told to go while still fed is done at once,
  ;; and the effect takes a point every three ticks at best, slower than the go comes
  [rc row name landing]
  (let [landing ((get-method land! :surface) rc row name landing)]
    (rcon/command! rc (str "effect give " name " minecraft:hunger 30 255"))
    (try (loop [i 0]
           (when (and (< i 150) (>= (or (food-level rc name) food/max-food) hungry-food))
             (Thread/sleep 200)
             (recur (inc i))))
         (finally (rcon/command! rc (str "effect clear " name " minecraft:hunger"))))
    landing))

(defn place-at-fortress!
  "The fortress nearest the landing's Nether coordinates (an eighth of the overworld's, so each
   landing has its own), pad (fortress-pad or fortress-pad-open) built fortress-offset blocks west
   of it at pad-y, and the bot in it."
  [rc name [x z _] pad]
  (let [reply (rcon/command! rc
                             (str "execute in minecraft:the_nether positioned "
                                  (quot x 8)
                                  " 64 "
                                  (quot z 8)
                                  " run locate structure minecraft:fortress"))
        [fx fz] (or (fixture/parse-located reply)
                    (throw (ex-info "no fortress located" {:reply reply})))]
    (place! rc dimension/nether name [(- fx fortress-offset) fz pad-y] 2 pad)))

(defmethod land! :nether-fortress
  ;; a pocket of netherrack (fortress-pad) and the bot in it
  [rc _row name landing]
  (place-at-fortress! rc name landing fortress-pad))

(defmethod land! :nether-fortress-open
  ;; the same pocket with a doorway cut in its east wall (fortress-pad-open)
  [rc _row name landing]
  (place-at-fortress! rc name landing fortress-pad-open))

(defmethod land! :nether-arena
  ;; a sealed room (nether-arena) over the landing's Nether coordinates (an eighth of the
  ;; overworld's, so each landing has its own) at pad-y, the row's setup built in it, the bot on
  ;; its floor
  [rc row name [x z _]]
  (place! rc
          dimension/nether
          name
          [(quot x 8) (quot z 8) pad-y]
          arena-half
          (into nether-arena (:gym/setup row))))

(defn located-pos
  "[x y z] from a `locate biome` reply, which names the height it found, or nil."
  [reply]
  (when-let [[_ x y z] (re-find #"\[(-?\d+), (-?\d+), (-?\d+)\]" (str reply))]
    (mapv parse-long [x y z])))

(defn spread
  "The command that stands the bot named name on a floor in the Nether within r blocks of x z, under
   the roof (spreadplayers ... under spread-under: the highest floor with two cells of air over it
   below that height, scanning down (SpreadPlayersCommand.getSpawnY), where vanilla judges a player
   safe: no lava, no fire)."
  [x z r name]
  (str "execute in " dimension/nether
       " run spreadplayers " x
       " " z
       " 0 " r
       " under " spread-under
       " false " name))

(defn pocket?
  "The test the server passes when cell [x y z] of the Nether is cave_air: the air a carved cave
   is made of (NetherWorldCarver carves cave_air over its lava), never the open cavern's, so a
   floor the spread found inside the rock is told from the forest's floor."
  [[x y z]]
  (str "execute in " dimension/nether " if block " x " " y " " z " minecraft:cave_air"))

(defn warped?
  "The test the server passes when cell [x y z] of the Nether is in a warped forest."
  [[x y z]]
  (str "execute in " dimension/nether " if biome " x " " y " " z " minecraft:warped_forest"))

(defmethod land! :near-stronghold
  [rc _row name [x z _]]
  (let [reply (rcon/command!
               rc
               (str "execute positioned " x " 64 " z " run locate structure minecraft:stronghold"))
        [sx sz] (or (fixture/parse-located reply)
                    (throw (ex-info "no stronghold located" {:reply reply})))
        d (max 1.0 (math/hypot (- x sx) (- z sz)))
        at [(long (+ sx (* stronghold-off (/ (- x sx) d))))
            (long (+ sz (* stronghold-off (/ (- z sz) d))))]
        [px py pz] (or (fixture/land-at! rc name at)
                       (throw (ex-info "no landing near the stronghold" {:at at})))
        [lx ly lz] (mapv #(long (math/floor %)) [px py pz])]
    (cli/log "stronghold:" [sx sz] "landing:" [lx ly lz])
    (rcon/command! rc (str "spawnpoint " name " " lx " " ly " " lz))
    [lx lz ly]))

(def room-reach 16)                     ; blocks round the landing the portal room's chunks are forceloaded over while it is built

(defmethod land! :portal-room
  ;; Not on the surface (land! :surface): the surface over the landing is now the room's roof
  [rc row name [x z y :as landing]]
  (fixture/with-forceload
   rc
   [(- x room-reach) (- z room-reach) (+ x room-reach) (+ z room-reach)]
   (fn []
     (doseq [c (:gym/setup row)] (rcon/command! rc (fill-in c name landing)))
     (cli/log "tp:" (rcon/command! rc (str "tp " name " " (+ x 0.5) " " y " " (+ z 0.5))))))
  (rcon/command! rc (str "spawnpoint " name " " x " " y " " z))
  landing)

(defn search-points-from
  "The Nether points [x z] a warped forest is located from, nearest first, for a bot at the
   Nether's own [nx nz]: that point, then locate-offsets off it along each axis (warped-origins
   for a landing in overworld coordinates; the race's forest start for a pad in the Nether)."
  [nx nz]
  (into
   [[nx nz]]
   (for [d locate-offsets [dx dz] [[1 0] [-1 0] [0 1] [0 -1]]] [(+ nx (* d dx)) (+ nz (* d dz))])))

(defn warped-origins
  "The Nether points [x z] a warped forest is located from for a landing at overworld x z,
   nearest first: the landing's eighth (the Nether's scale, so each landing has a forest of its
   own), then locate-offsets off it along each axis, for when the forest nearest gives no
   floor. Set A's landing 19360,18752, a plan's run 2, located its nearest forest at [2420 64
   2216] and found no floor in it three plans running (pearls-warped-e, pearls-warped3-a and
   pearls-warped4-b, plan runs 39178856841, 39529384355 and 39596287510: a harness row each,
   the bot reset to the overworld spawn with no kit and the trial counted for nothing)."
  [x z]
  (search-points-from (quot x 8) (quot z 8)))

(defn locate-warped!
  "[x y z] of the warped forest the server locates nearest the Nether point [ox oz]; throws when
   it names none."
  [rc [ox oz]]
  (let [reply (rcon/command! rc
                             (str "execute in "
                                  dimension/nether
                                  " positioned "
                                  ox
                                  " 64 "
                                  oz
                                  " run locate biome minecraft:warped_forest"))]
    (or (located-pos reply) (throw (ex-info "no warped forest located" {:reply reply})))))

(defn warped-floor!
  "The cell [x y z] a floor in the warped forest round the located point [bx _ bz] stands on, or
   nil: the bot named name spread onto one (spread) at each of warped-ranges, nearer first,
   warped-tries times each, until the server says where it stands is warped forest (warped?) and
   not the air of a carved cave (pocket?). The bot is left there."
  [rc name [bx _ bz]]
  (some (fn [r]
          (let [spread-reply (str (rcon/command! rc (spread bx bz r name)))
                p (when (str/starts-with? spread-reply "Spread") (fixture/position rc name))
                c (when p (mapv #(long (math/floor %)) p))]
            (cli/log "spread:" r spread-reply c)
            (when (and c
                       (fixture/passed? (rcon/command! rc (warped? c)))
                       (not (fixture/passed? (rcon/command! rc (pocket? c)))))
              c)))
        (mapcat #(repeat warped-tries %) warped-ranges)))

(defn warped-floor-from!
  "{:floor [x y z] :tried #{located points}} for the bot named name: the forest located from each
   of origins ([[x z] ...], nearest first; warped-origins, search-points-from) tried once each
   (a forest already tried is skipped) until warped-floor! finds a floor in one; :floor is nil
   when none gave one."
  [rc name origins]
  (reduce (fn [{:keys [tried] :as acc} origin]
            (let [at (locate-warped! rc origin)
                  f (when-not (contains? tried at) (warped-floor! rc name at))]
              (cond f (reduced (assoc acc :floor f))
                    :else (update acc :tried conj at))))
          {:tried #{}}
          origins))

(defmethod land! :warped-forest
  ;; the warped forest nearest the landing's Nether coordinates (an eighth of the overworld's, so
  ;; each landing has its own), and a floor in it: the bot spread onto one round the located point
  ;; (spread), nearer ranges first, until the server says where it stands is warped forest (warped?)
  ;; and not the air of a carved cave (pocket?), then stood on that cell with the row's setup round
  ;; it. A forest with no floor found is left for the one nearest the next of warped-origins, each
  ;; located forest tried once. Batches d, e and f stood two landings in three forty minutes in a
  ;; cave pocket the spread had put them in
  [rc row name [x z _]]
  (let [dim dimension/nether
        {:keys [floor tried]} (warped-floor-from! rc name (warped-origins x z))
        [fx fy fz] (or floor (throw (ex-info "no floor in the warped forest" {:at (vec tried)})))]
    (place! rc dim name [fx fz fy] 2 (vec (:gym/setup row)))))

(defmethod land! :end
  ;; the main island, ruststeve's End start: the island round the fountain loaded, the bot on the
  ;; surface at end-at (its first arrival starts the dragon fight, crystals and all), then the row's
  ;; setup round where it stands. Every landing of the set starts here: the End has one main island.
  ;; No portal and no stronghold: the rows past them start in the End
  [rc row name _]
  (let [dim dimension/end
        [x z] end-at
        square [(- end-reach) (- end-reach) end-reach end-reach]
        where (str/join " " square)]
    (rcon/command! rc (in-dimension dim (str "forceload add " where)))
    (try (when-not (wait-loaded-in! rc dim square)
           (throw (ex-info "the End's chunks never loaded" {:square square})))
         (cli/log "tp:"
                  (rcon/command!
                   rc
                   (in-dimension dim (fixture/on-surface x z (str "tp " name " ~0.5 ~ ~0.5")))))
         (let [[_ y _] (or (fixture/position rc name)
                           (throw (ex-info "the bot left the world" {:name name})))
               at [x z (long (math/floor y))]]
           (doseq [c (:gym/setup row)] (rcon/command! rc (in-dimension dim (fill-in c name at))))
           at)
         (finally (rcon/command! rc (in-dimension dim (str "forceload remove " where)))))))

(defn wait-in!
  "Block until the server says the bot named name stands in the dimension dim (dimension-test),
   polling every 250 ms, up to dimension-wait-ms; true when it does."
  [rc name dim]
  (let [until (+ (System/currentTimeMillis) dimension-wait-ms)]
    (loop []
      (cond (fixture/passed? (rcon/command! rc (dimension-test name dim))) true
            (> (System/currentTimeMillis) until) false
            :else (do (Thread/sleep 250) (recur))))))

;; ---------------------------------------------------------------- the race's fortress landings

(defn landing-of
  "The [x z] racer i (from 0) lands on in a landing set ([[x z y] ...]), offset into it: the
   set's landing offset + i, wrapping, so a runner's servers and the runners sharing a set start
   their bots on different landings, and the same racer always on the same one."
  [set offset i]
  (let [[x z _] (landings/landing-for set (inc (+ offset i)))] [x z]))

(def pad-offsets
  "Where, round a fortress, racer i's pad is: eight compass points 48 blocks off, so the bots of one
   fortress (a landing set's 48 landings, 40 blocks apart in the Nether, find only two or three
   fortresses) do not stand on one pad."
  [[-48 0] [48 0] [0 -48] [0 48] [-48 -48] [48 48] [-48 48] [48 -48]])

(defn pad-of
  "The pad [x z y] of racer i (from 0) at the fortress located at [fx fz]: pad-offsets (i mod 8)
   from it, at pad-y."
  [[fx fz] i]
  (let [[dx dz] (nth pad-offsets (mod i (count pad-offsets)))] [(+ fx dx) (+ fz dz) pad-y]))

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
    [(+ fx (* r dx)) (+ fz (* r dz)) pad-y]))

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
  "[x y z], where a bot stands on the pad [x z y]: the middle of its cell (the order a :go/at has:
   place! teleports to x.5 y z.5). Returned from the pad, never from a question to the server: a
   landing that read a position nil handed the planner the pad's own [x z y], which is not a place,
   and 30 s later the run ended :no-landing."
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
   of fifteen bots at once it stopped the server's keepalives and every bot timed out. Running
   31317ms behind`)."
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
  (in-dimension dimension/nether (str "tp " name " " (+ x 0.5) " " y " " (+ z 0.5))))

(def pad-view 112)                    ; blocks round a pad generated before a bot is sent to it: 15 by 15 chunks, within one forceload's 256 and past the server's view distance (6 chunks, bb/clojurecraft/server.clj)

(defn pregenerate!
  "Generate the Nether round the pad [x z y] to pad-view blocks with no bot on it: the square
   forceloaded and waited for (best effort, three waits of 30 s), then released. A bot teleported
   onto a pad whose surroundings were not generated made the server generate its whole view,
   fortress structure included, in its thread."
  [rc [x z _]]
  (let [square [(- x pad-view) (- z pad-view) (+ x pad-view) (+ z pad-view)]
        where (str/join " " square)]
    (rcon/command! rc (in-dimension dimension/nether (str "forceload add " where)))
    (try (loop [n 3]
           (when (and (pos? n) (not (wait-loaded-in! rc dimension/nether square))) (recur (dec n))))
         (finally
          (rcon/command! rc (in-dimension dimension/nether (str "forceload remove " where)))))))

(def lava-clear-radius 10) ; blocks round a pad on the plane cleared of lava.

(defn lava-clear-command
  "The RCON fill that turns every lava block round the pad [x z y] into netherrack:
   lava-clear-radius on the plane, from a block under the pad's floor to its roof. The pad is a box
   with a doorway (fortress-pad-open), built once and landed on by every bot of its slot, and a
   pad beside a lava sea let the lava in: on f9c021c every lava death of race lane 2 (the stage-18
   rods start, landing set R2), 7 of 7, came 4 to 10 s after the teleport, hit by lava before the
   bot's :go."
  [[x z y]]
  (in-dimension dimension/nether
                (str "fill "
                     (str/join " "
                               [(- x lava-clear-radius) (dec y) (- z lava-clear-radius)
                                (+ x lava-clear-radius) (+ y 3) (+ z lava-clear-radius)])
                     " minecraft:netherrack replace minecraft:lava")))

(defn build-pad!
  "Build the pad [x z y] (fortress-pad-open) in the Nether with no bot: the square round it
   forceloaded and waited for, the commands, the forceload released. The first build of a Nether
   region generates its chunks and a fortress's structure, seconds of the server's thread each: done
   with a bot connected, the server fell 12 to 34 s behind and the bots timed out. Remembered in
   pads-built."
  [rc [x z _ :as pad]]
  (when-not (contains? @pads-built pad)
    (let [where (str/join " " [(- x 2) (- z 2) (+ x 2) (+ z 2)])]
      (rcon/command! rc (in-dimension dimension/nether (str "forceload add " where)))
      (try (when-not (wait-loaded-in! rc dimension/nether [(- x 2) (- z 2) (+ x 2) (+ z 2)])
             (throw (ex-info "the pad's chunks never loaded" {:pad pad})))
           (rcon/command! rc (lava-clear-command pad))
           (doseq [c fortress-pad-open]
             (rcon/command! rc (in-dimension dimension/nether (fill-in c "" pad))))
           (swap! pads-built conj pad)
           (pregenerate! rc pad)
           (finally
            (rcon/command! rc (in-dimension dimension/nether (str "forceload remove " where))))))))

(defn with-loaded
  "Call (f) with the Nether's square [x0 z0 x1 z1] forceloaded and waited for (best effort, 30 s),
   and released after, even when f throws: a test of a block or an entity only sees a loaded chunk."
  [rc square f]
  (let [where (str/join " " square)]
    (rcon/command! rc (in-dimension dimension/nether (str "forceload add " where)))
    (try (wait-loaded-in! rc dimension/nether square)
         (f)
         (finally
          (rcon/command! rc (in-dimension dimension/nether (str "forceload remove " where)))))))

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

(def ground-drop 3)                   ; blocks the floor may fall away round a pad and still be ground: the Nether's floors are not level, a step down of three is no ledge

(def ground-top 100)

(def ground-bottom 40)               ; the lava sea is at y 31; a pad is cut well over it

(defn ground-y
  "The height of the first ground under the roof for a pad at [x z], from ground-top down to
   ground-bottom: ground under the cell, air in it and the two over it, and ground under every cell
   of ground-ring within ground-drop below it (a ledge is not ground, a slope is), or nil. The first
   version asked the ring at exactly the pad's floor: of 16 pads chosen in lane 2 of race f8b28e3,
   15 fell back to y 72 after 333 and 192 `:no-ground` rejections. Race 9d15bd6, lane 7."
  [rc [x z _]]
  (with-loaded rc
               [(- x 24) (- z 24) (+ x 24) (+ z 24)]
               (fn []
                 (let [ok? #(fixture/passed? (rcon/command! rc %))
                       air? (fn [c]
                              (or (ok? (air-test c "minecraft:air"))
                                  (ok? (air-test c "minecraft:cave_air"))))]
                   (loop [y ground-top]
                     (cond (< y ground-bottom) nil
                           (and (ok? (ground-test [x (dec y) z]))
                                (every? air? [[x y z] [x (inc y) z] [x (+ y 2) z]])
                                (every? (fn [[dx dz]]
                                          (some #(ok? (ground-test [(+ x dx) (- y %) (+ z dz)]))
                                                (range 1 (inc ground-drop))))
                                        ground-ring))
                           y
                           :else (recur (dec y))))))))

(defn pad-unsafe
  "Why the pad is no landing, :bricks (inside the fortress's body) or :mobs (a danger-mob near), or
   nil."
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
   server locates, built by prepare! or here when it is new, build-pad!), its lava cleared again
   (lava-clear-command: lava flows back between landings), the bot teleported onto it; wait for the server to say it stands in the Nether, clear its inventory and give it kit
   (\"minecraft:iron_sword 1\" ...: what a race bot carries to the fortress, ci/race.edn's
   start/kit-commands). Returns where it stands ([x y z], standing-on the pad), or nil when the dimension
   never came; a landing that cannot locate a fortress throws."
  [rc name landing i kit]
  (let [fortress
        (if (seq @fortress-pool) (fortress-for @fortress-pool i) (locate-fortress! rc landing))
        pad (or (get @pads-chosen i) (choose-pad! rc fortress i nil))
        pad (if (mobs-near? rc pad) (choose-pad! rc fortress i [pad]) pad)]
    (build-pad! rc pad)
    (with-loaded rc
                 [(- (first pad) lava-clear-radius) (- (second pad) lava-clear-radius)
                  (+ (first pad) lava-clear-radius) (+ (second pad) lava-clear-radius)]
                 #(cli/log "pad lava:" (rcon/command! rc (lava-clear-command pad))))
    (cli/log "tp:" (rcon/command! rc (tp-command name pad)))
    (when (wait-in! rc name dimension/nether)
      (rcon/command! rc (str "clear " name))
      (doseq [item kit] (rcon/command! rc (str "give " name " " item)))
      (Thread/sleep (long fixture/settle-ms))
      (standing-on pad))))

(defn forest-origins
  "The Nether points [x z] a bot's forest is located from, nearest first, for its fortress pad
   [x z y]: the pad's own point, then locate-offsets off it along each axis for when the
   nearest forest gives no floor (search-points-from)."
  [[x z _]]
  (search-points-from x z))

(defn land-forest!
  "Land the bot named name (racer i) on a floor of the warped forest nearest its fortress pad: the
   pad as land-fortress! chooses it (built by prepare! or here), the forest the server locates
   from it, the bot spread onto a floor round that point (warped-floor-from!: warped forest,
   never a carved cave's air, each forest tried once, the next nearest when one gives no floor),
   then its inventory cleared and kit given as at the fortress. Returns where it stands ([x y z],
   standing-on the floor), or nil when the dimension never came; throws when no forest near gave
   a floor."
  [rc name landing i kit]
  (let [fortress
        (if (seq @fortress-pool) (fortress-for @fortress-pool i) (locate-fortress! rc landing))
        pad (or (get @pads-chosen i) (choose-pad! rc fortress i nil))
        {:keys [floor tried]} (warped-floor-from! rc name (forest-origins pad))
        [fx fy fz]
        (or floor (throw (ex-info "no floor in the warped forest" {:pad pad :at (vec tried)})))]
    (cli/log "forest floor" [fx fy fz] "pad" pad)
    (when (wait-in! rc name dimension/nether)
      (rcon/command! rc (str "clear " name))
      (doseq [item kit] (rcon/command! rc (str "give " name " " item)))
      (Thread/sleep (long fixture/settle-ms))
      (standing-on [fx fz fy]))))
