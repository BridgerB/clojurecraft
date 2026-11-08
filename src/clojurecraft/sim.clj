(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, pickup when the player comes
   within the inflated pickup box after the 10-tick delay, window 0 (the player's inventory
   with its 2x2 crafting grid) with vanilla click rules and state ids, block placement, and the
   containers a right-click opens: a crafting table, and a furnace that cooks by the game's
   smelting and fuel tables every tick, open or not. Gravel and sand fall: a block of a falling
   type whose neighbour below changed falls two ticks later if the cell under it is free, and
   lands where the game's gravity brings it.

   Fault knobs are inputs, not hidden state: :sim/drop-clicks is a set of click ordinals
   (0-based) the server silently loses, :sim/drop-breaks a set of FINISH ordinals it answers
   with the ack alone, the block left standing (a lagging server queues an early FINISH
   behind another and drops it), and the loot table's chances (gravel's flint) roll from
   :sim/rng, a pure generator whose start is the :seed input, so a run's luck replays. :sim/violations records anything a real server would
   punish or a careful client must never do: a click on an empty result slot, a close with a
   loaded cursor.

   Two ways to drive it: run folds bot and model together, deterministic and fast (generated
   worlds); connect puts the model on a thread behind a channel pair shaped like conn/open!'s,
   so main's own loop (its clock, its effects, its tap) runs against it with no Java process."
  (:refer-clojure :exclude [send])
  (:require [clojure.core.async :as async]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.food :as food]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.physics :as physics]
            [clojurecraft.sim.end :as sim-end :refer [through-end-portal tick-eyes]]
            [clojurecraft.sim.mobs :as sim-mobs :refer
             [tick-burn tick-fireballs tick-fires tick-lava tick-wither with-mobs]]
            [clojurecraft.sim.portal :as sim-portal :refer [through-portal]]
            [clojurecraft.sim.protocol :as sim-protocol :refer
             [drown feed keep-alive kill login pick-up stream tick-delayed tick-eating]]
            [clojurecraft.sim.spawns :as sim-spawns :refer [tick-mobs]]
            [clojurecraft.sim.window :as sim-window :refer [tick-furnaces]]
            [clojurecraft.sim.world :as sim-world :refer
             [column-key keep-alive-every max-air max-health mob-salt on-packet pickup-delay roll
              send sync-health tell-health tick-falls tick-fluids told wild-salt with-food-value]]))

(set! *warn-on-reflection* true)

(defn init
  "A fresh server before the handshake, from {:columns {[cx cz] bytes} :spawn [x y z]} (a world: its
   chunk columns as a real server sent them, a capture of one; or {:column bytes}, the world of the
   one column [0 0]), with optional :decoded {[cx cz] column} (the same columns decoded, once, when
   the world was read, so the runs of one world share them); the whole world goes to the client at
   login and stays, unless the optional :view-distance r streams the columns within r chunks as the
   player moves, as a server does. Then the optional :keep-alive-every ms, :drop-clicks #{ordinal},
   :drop-breaks #{ordinal}, :inventory {slot item} (a slot is a named place, [:hotbar 0] as
   inventory/slot reads it, or a window-0 slot number), :lag-ticks, :slow-breaks f (blocks take f
   times their time to break by the server's clock, a loaded server's: a FINISH at the client's time
   is acked but the break comes later, full-ms), :still-blazes? true (the model's blazes hover where
   they stand, as before they flew: for the tests that pin the bot's own cover and fight, not the
   blaze's flight), :respawn-in dimension (the dimension a new life begins in when the player dies
   there, as a spawn the server holds for it does: the Nether for a race that starts at a fortress;
   absent, a death anywhere but the overworld ends in the overworld), :respawn [x y z] (where a new
   life begins; the spawn when absent, as a gym landing may differ from the world spawn), :seed
   (where the loot rolls start, 0 when absent), :tick-seed (where a run's tick randomness starts;
   0.5 every tick when absent), :kill-at ms (the player dies then, a fault as an input: a death
   mid-task), :dimension (the dimension the player joins in, the overworld when absent: a run that
   starts in the Nether is a gym's tp there), :food (the hunger bar the player joins with, told at
   login; absent, no food is modelled and none is told), :nether {:column bytes :at [x y z]} (the
   Nether a portal leads to: its one column and where the player arrives; :columns {[cx cz] bytes}
   in place of :column, a whole Nether, with optional :decoded, the same columns decoded once,
   :view-distance r, the chunks streamed round the player there, and :at, absent for where the
   server would link the portal: arrive) and :difficulty (:peaceful, :easy, :normal or :hard, as the
   server's setting), and :mobs [{:type kind :pos [x y z]}] (a fixture's mobs, mob/kinds, sent at
   login and never despawned), :stronghold [x y z] (where a thrown eye flies toward; absent, an eye
   is not thrown) and :end {:columns {[cx cz] bytes} :dragon [x y z] :dragon-yaw deg :crystals [[x y
   z] ...]} (the End an end portal leads to: its columns, a void when absent, where the dragon
   perches, the fountain's top when absent, the way it faces, its head harmless when absent, and its
   crystals, one on every spike when absent)."
  [{:keys [column columns decoded view-distance spawn keep-alive-every drop-clicks drop-breaks
           inventory lag-ticks slow-breaks respawn respawn-in seed tick-seed kill-at dimension
           nether food difficulty day-time mobs stronghold end still-blazes?]
    :or {keep-alive-every keep-alive-every}}]
  (cond->
    {:sim/phase :handshake
     :sim/lag-ticks (or lag-ticks 0)
     :sim/inv (into {} (for [[k it] inventory] [(if (vector? k) (inventory/slot k) k) it]))
     :sim/state-id 1
     :sim/held 0
     :sim/placed {}
     :sim/furnaces {}
     :sim/falls {}
     :sim/falling {}
     :sim/fluids {}
     :sim/fluid-sources-ready? true
     :sim/next-window 1
     :sim/clicks 0
     :sim/drop-clicks (or drop-clicks #{})
     :sim/breaks 0
     :sim/drop-breaks (or drop-breaks #{})
     :sim/rng (or seed 0)
     :sim/violations []
     :sim/keep-alive-every keep-alive-every
     :sim/now 0
     :sim/out []
     :sim/columns (or columns {[0 0] column})
     :sim/view #{}
     :sim/spawn spawn
     :sim/respawn (or respawn spawn)
     :sim/broken #{}
     :sim/air max-air
     :sim/health max-health
     :sim/respawns 0
     :sim/items {}
     :sim/next-eid 100
     :sim/next-keep-alive keep-alive-every
     :sim/keep-alives-pending #{}}
    decoded (assoc :sim/decoded decoded)
    slow-breaks (assoc :sim/slow-breaks slow-breaks)
    tick-seed (assoc :sim/tick-seed tick-seed)
    view-distance (assoc :sim/view-distance view-distance)
    kill-at (assoc :sim/kill-at kill-at)
    dimension (assoc :sim/dimension dimension)
    respawn-in (assoc :sim/respawn-in respawn-in)
    still-blazes? (assoc :sim/still-blazes? true)
    food (assoc :sim/food food)
    nether (assoc :sim/nether nether)
    difficulty
    (-> (assoc :sim/difficulty difficulty :sim/day-time (or day-time 0))
        (with-food-value (assoc hunger/fresh :food/level (or food food/max-food)))
        (assoc :sim/sent-health [max-health (or food food/max-food) false]))
    (or difficulty (seq mobs)) (assoc :sim/mob-rng (bit-xor (long (or seed 0)) mob-salt))
    difficulty (assoc :sim/wild-rng (bit-xor (long (or seed 0)) wild-salt))
    stronghold (assoc :sim/stronghold stronghold)
    end (assoc :sim/end end)
    (seq mobs)
    (assoc :sim/fixture-mobs (vec mobs)
           :sim/sent-health [max-health (or food food/max-food) false])))

(defn joined
  "A server already in play with a player it never saw join: init's world from opts (:columns and
   :decoded the columns it holds, :spawn, :respawn, :seed, :tick-seed, :difficulty, :day-time,
   :dimension, :food, :inventory as window-0 slots), then what the real server held at that moment,
   given as data in play: the time (:now, ms, the bot's clock), the player's :pos, :look,
   :on-ground?, :held hotbar place, window 0's :state-id, :health, :saturation and :air, its entity
   id (:player-eid), the blocks changed since their columns were sent (:placed {pos state}), the
   items lying about (:items {eid {:pos :item}}, ready to pick up) and the mobs (:mobs {eid {:type
   :pos :health}}), under the ids the client tracks them by, and the columns the client holds
   (:view, every one within :view-distance chunks of the player, a square as stream counts it).
   Nothing is sent: the client has it all already, but for the entities it tracks that this model
   does not carry (:gone ids), told removed (remove-entities) with its first tick's packets. Every
   column the model holds is taken as populated: the animals the real server placed are the mobs
   given, so the model adds none. The keep-alive clock starts at :now, and with hunger what it last
   told is what the client knows (sync-health tells only a change)."
  [opts
   {:keys [now pos look on-ground? held state-id health saturation air player-eid placed items mobs
           view view-distance gone]}]
  (let [fresh (init opts)
        here (column-key pos)
        sim (cond->
              (assoc fresh
                     :sim/phase :play
                     :sim/now now
                     :sim/player-pos pos
                     :sim/player-look look
                     :sim/on-ground? (boolean on-ground?)
                     :sim/held held
                     :sim/state-id state-id
                     :sim/health health
                     :sim/air air
                     :sim/placed placed
                     :sim/items (update-vals items #(assoc % :spawned (- now pickup-delay)))
                     :sim/mobs mobs
                     :sim/next-eid
                     (inc (reduce max
                                  (dec (:sim/next-eid fresh))
                                  (concat (keys items) (keys mobs) gone)))
                     :sim/next-keep-alive (+ now (:sim/keep-alive-every fresh))
                     :sim/view view
                     :sim/view-at here
                     :sim/view-distance view-distance
                     :sim/populated (set (keys (:sim/columns fresh)))
                     :sim/populated-at here)
              player-eid (assoc :sim/player-eid player-eid)
              saturation (assoc :sim/saturation saturation))]
    (cond-> sim
      (:sim/difficulty sim) (assoc :sim/sent-health (told sim))
      (seq gone) (send {:packet/name :remove-entities :ids (vec (sort gone))}))))

(defmethod on-packet [:configuration :finish-configuration]
  [sim _]
  (let [[x y z] (:sim/spawn sim)]
    (-> sim
        (assoc :sim/phase :play)
        (send (login (:sim/dimension sim dimension/overworld)))
        (send {:packet/name :player-position
               :teleport-id 1
               :x x
               :y y
               :z z
               :dx 0.0
               :dy 0.0
               :dz 0.0
               :yaw 0.0
               :pitch 0.0
               :flags 0})
        (send {:packet/name :container-set-content
               :window-id 0
               :state-id (:sim/state-id sim)
               :items (mapv #(get-in sim [:sim/inv %])
                            (range (inventory/slot-count (inventory/layouts :inventory))))
               :carried nil})
        (cond-> (:sim/food sim) tell-health)
        stream
        (with-mobs (:sim/fixture-mobs sim))
        (dissoc :sim/fixture-mobs))))

(defn carried-through-portal
  "The model at its own clock with a player standing in a portal long enough carried through it
   (through-portal at :sim/now)."
  [sim]
  (through-portal sim (:sim/now sim)))

(def tick-steps
  "The server's tick as data: the steps run in this order, each a function from the model to the
   model, the clock already at the tick's time. A new rule of the world is an entry here."
  [kill drown tick-burn tick-lava tick-wither feed tick-eating tick-delayed tick-furnaces tick-falls
   tick-fluids carried-through-portal through-end-portal tick-eyes tick-mobs tick-fireballs
   tick-fires keep-alive pick-up sync-health])

(defn tick
  "Advance the server clock to now, one game tick: the scheduled death, breath or drowning,
   hunger (feed) and eating, a delayed destroy, every furnace, falling gravel and sand, flowing
   water and lava, a player carried through a portal or an end portal, thrown eyes (tick-eyes),
   the mobs (tick-mobs), a keep-alive when one is due, every item handed over whose pickup delay
   has passed and that the player stands within reach of, and, with hunger, the health and food
   told when they changed (sync-health): tick-steps, in order."
  [sim now]
  (reduce (fn [sim f] (f sim)) (assoc sim :sim/now now) tick-steps))

(defn step
  "The model's reducer: a client packet or a tick in, the next sim out."
  [sim {:sim/keys [kind packet now]}]
  (case kind
    :packet (on-packet sim packet)
    :tick (tick sim now)
    sim))

(defn drain "[sim' packets-for-the-client]" [sim] [(assoc sim :sim/out []) (:sim/out sim)])

(defn sends
  "The packets a bot world asked to send, from its :bot/effects."
  [world]
  (mapv :effect/packet (filter #(= :send (:effect/kind %)) (:bot/effects world))))

(defn tick-rand
  "[r' x]: the next state of a tick seed r and its roll x in [0, 1), by the same pure step as
   the loot's rolls (roll)."
  [r]
  (let [[{r' :sim/rng} x] (roll {:sim/rng r})] [r' x]))

(def moment-every-ms 30000)       ; simulated ms between the moments a run keeps (play-from)

(def moment-keep 12)              ; moments kept: the last six simulated minutes

(defn kept
  "The moments with m added when every (simulated ms, nil to keep none) have passed since the last
   kept, the oldest dropped past moment-keep."
  [moments m every]
  (let [last-t (:t (peek moments))]
    (cond (or (nil? every) (and last-t (< (- (:t m) last-t) every))) moments
          :else (let [v (conj moments m)] (if (> (count v) moment-keep) (subvec v 1) v)))))

(defn tick-from
  "One tick of a bot reducer against the model from moment m (see play-from): {:next m'} the
   moment after it, or {:stop {:world :sim}} when (stop? world) holds after it or t is past
   until-t, the last tick's packets delivered to the model."
  [bot-step {:keys [world sim pending t went? inflight] r :rand} stop? until-t go lag]
  (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
        sim (step sim {:sim/kind :tick :sim/now t})
        [sim fresh] (drain sim)
        inflight (into inflight (map (fn [p] [(+ t lag) p]) fresh))
        inbound (map second (take-while #(<= (first %) t) inflight))
        inflight (vec (drop-while #(<= (first %) t) inflight))
        world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
        [r rand] (if r (tick-rand r) [nil 0.5])
        world (bot-step world {:event/kind :tick :event/now t :event/rand rand})
        go? (and (not went?) (:player/loaded? world))
        world (if go? (bot-step world go) world)
        out (sends world)
        world (assoc world :bot/effects [])]
    (cond (or (stop? world) (> t until-t))
          {:stop {:world world :sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim out)}}
          :else
          {:next {:world world
                  :sim sim
                  :pending out
                  :t (+ t physics/tick-ms)
                  :rand r
                  :went? (boolean (or went? go?))
                  :inflight inflight}})))

(defn play-from
  "Play a bot reducer against the model on from a moment m, a tick (physics/tick-ms) at a time
   (tick-from), until (stop? world) or max-ms of play. A moment is everything the next tick depends
   on: {:world :sim :pending :t :rand :went? :inflight}: the bot's world and the model's state, what
   the bot has sent and the model not yet taken (pending), the tick's time, the tick seed's state
   (rand), whether go was sent, and the packets still in flight under :sim/lag-ticks. So playing on
   from a moment is the run itself, packet for packet. Returns {:world :sim :before :last :moments}:
   :last the moment of the stopping tick, :before its world and model, and, with every (simulated
   ms; nil keeps none), :moments, the run's moments that far apart, the last moment-keep of them,
   for a failure to be kept from before the choice that led to it (simrace/snapshot). A tick that
   throws ends the play there with :crashed (the exception) and the world and model of the moment
   before it, so a crash is kept and judged like any other failure. go is sent once the bot is
   loaded, or never when nil. Each tick's :event/rand is 0.5, or, with a rand state, the next of a
   pure generator (tick-rand), so runs of one world with different seeds are different runs, each
   the same every time. With a tap (a function of the moment, called once a tick before it is
   played, for an observer's atom: the truth ring), the tap sees every moment and changes none."
  ([bot-step m stop? max-ms go every] (play-from bot-step m stop? max-ms go every nil))
  ([bot-step m stop? max-ms go every tap]
   (let [lag (* physics/tick-ms (:sim/lag-ticks (:sim m) 0))
         until-t (+ (:t m) max-ms)]
     (loop [m m
            moments []]
       (let [moments (kept moments m every)
             _ (when tap (tap m))
             r (try (tick-from bot-step m stop? until-t go lag) (catch Exception e {:crashed e}))
             ended {:before [(:world m) (:sim m)] :last m :moments moments}]
         (cond (:crashed r) (assoc ended :world (:world m) :sim (:sim m) :crashed (:crashed r))
               (:stop r) (merge ended (:stop r))
               :else (recur (:next r) moments)))))))

(defn play-on
  "Play a bot reducer against the model on from world and sim as they are, from t0 with pending
   not yet taken by the model (the start packets, for a run from the handshake), until (stop?
   world) or max-ms of play: play-from from that moment, the tick seed started at :sim/tick-seed,
   no moments kept. Returns {:world :sim :before [world sim] :last}, :before the pair one tick
   before the stop."
  [bot-step world sim stop? max-ms t0 pending go]
  (play-from bot-step
             {:world (assoc world :bot/effects [])
              :sim sim
              :pending pending
              :t t0
              :rand (:sim/tick-seed sim)
              :went? (boolean (nil? go))
              :inflight []}
             stop?
             max-ms
             go
             nil))

(defn start-moment
  "The moment a run from a fresh handshake starts from: the bot's world after :start, the model,
   the start packets pending, time 0, the tick seed at :sim/tick-seed, go not yet sent."
  [bot-step world0 sim0]
  (let [w (bot-step world0 {:event/kind :start})]
    {:world (assoc w :bot/effects [])
     :sim sim0
     :pending (sends w)
     :t 0
     :rand (:sim/tick-seed sim0)
     :went? false
     :inflight []}))

(defn run+
  "Run a bot reducer against the model from a fresh handshake (start-moment) until (stop? world)
   or max-ms, sending go (default {:event/kind :go}) once the bot is loaded, keeping a moment every
   moment-every-ms: play-from's answer, {:world :sim :before :last :moments}. A tap, when given
   after go, is play-from's."
  [bot-step world0 sim0 stop? max-ms & [go tap]]
  (play-from bot-step
             (start-moment bot-step world0 sim0)
             stop?
             max-ms
             (or go {:event/kind :go})
             moment-every-ms
             tap))

(defn run
  "Run a bot reducer against the model from a fresh handshake, a tick (physics/tick-ms) at a
   time, until (stop? world) or max-ms. Sends go (default {:event/kind :go}) once the bot is
   loaded. Returns [world sim] (run+ for the moment before the stop too)."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [{:keys [world sim]} (run+ bot-step world0 sim0 stop? max-ms go)] [world sim]))

(defn connect!
  "The model behind a channel pair shaped like conn/open!'s: {:in chan :out chan :close! fn}.
   Packet maps the client puts on :out are stepped into the model; every physics/tick-ms of wall time
   the model ticks; whatever it says for the client goes onto :in, in order. Both channels are
   bounded, as conn's are. :in closes when the client closes :out or calls :close!, which the
   loop reads as the socket closing."
  [sim0]
  (let [in (async/chan 1024)
        out (async/chan 256)
        stop (async/chan)
        t0 (System/currentTimeMillis)]
    (async/thread (loop [sim sim0
                         next-tick physics/tick-ms]
                    (let [wait (max 0 (- next-tick (- (System/currentTimeMillis) t0)))
                          [v ch] (async/alts!! [stop out (async/timeout wait)] :priority true)
                          closed? (or (= ch stop) (and (= ch out) (nil? v)))
                          sim (cond closed? sim
                                    (= ch out) (step sim {:sim/kind :packet :sim/packet v})
                                    :else (step sim {:sim/kind :tick :sim/now next-tick}))
                          [sim said] (drain sim)]
                      (doseq [p said] (async/>!! in p))
                      (cond closed? (async/close! in)
                            (= ch out) (recur sim next-tick)
                            :else (recur sim (+ next-tick physics/tick-ms))))))
    {:in in :out out :close! (fn [] (async/close! stop) (async/close! out))}))
