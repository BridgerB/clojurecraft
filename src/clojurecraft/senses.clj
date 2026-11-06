(ns clojurecraft.senses
  "What a bot senses and decides, one line a second, readable within one batch (LOOP.md section 4).
   The bot's whole mind is one world value; a line is a pure function of it (of), so an observer
   on the atom (senses!) samples it without touching the loop, as the telemetry watcher does
   (docs/RICH-HICKEY.md: let everyone watch). The loop's own clock says how long each event took
   (main/apply-event!'s on-ms); a line carries the slowest event since the last line, so a frozen
   bot shows as a number beside what it was doing. classes names what a run's last lines show (the
   LOOP.md behaviour classes, computed, not guessed).

   Why: a lane uploads its recordings only when it ends, five and a half hours on, and a fold of
   one costs 27 to 100 s on a busy runner (churn/convert!). The freeze after the seventh rod (23 s
   of the first minute on 0ace52e, 54 of 65 blaze deaths with a tick over 1.5 s) went unseen for
   days for want of this."
  (:require [clojure.core.async :as async]
            [clojure.math :as math]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.physics :as physics]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def every-ms 1000)                    ; world time between two lines of a bot
(def keep-lines 300)                   ; lines kept a bot, the last five minutes: what a death report reads
(def hostile-radius 32.0)              ; blocks from the feet a hostile is listed within
(def hostiles-listed 6)                ; the nearest this many listed, each with its distance and sight
(def projectile-radius 16.0)           ; blocks from the feet a fireball is counted within
(def projectile-kinds #{:small_fireball :fireball :arrow :wither_skull :dragon_fireball})
(def freeze-ms 1500)                   ; one event this slow is a freeze: the bot could not act
(def swarm-count 6)                    ; hostiles this many within swarm-radius is a swarm
(def swarm-radius 16.0)
(def window-ms 30000)                  ; the last lines class reads: what the run ended in

(defn r1 "x to one decimal, or nil." [x] (when x (/ (math/round (* 10.0 (double x))) 10.0)))

(defn hostile-view
  "A hostile as a line shows it: its kind, distance from the feet, health, whether it sees the
   bot (combat/seen?) and whether it burns."
  [world e]
  {:kind (entity/kind e)
   :d (r1 (physics/distance (:player/pos world) (:entity/pos e)))
   :hp (r1 (:entity/health e))
   :sees? (combat/seen? world e)})

(defn hostile-list
  "The hostiles within hostile-radius of the feet (entity/hostiles), nearest first, at most
   hostiles-listed, as hostile-view shows them."
  [world]
  (let [pos (:player/pos world)]
    (->> (entity/hostiles world)
         vals
         (map (fn [e] [(physics/distance pos (:entity/pos e)) e]))
         (filter (fn [[d]] (<= d hostile-radius)))
         (sort-by first)
         (take hostiles-listed)
         (mapv (fn [[_ e]] (hostile-view world e))))))

(defn counted-near
  "How many entities of world within radius of the feet pass pred."
  [world radius pred]
  (let [pos (:player/pos world)]
    (count (filter (fn [e] (and (pred e) (<= (physics/distance pos (:entity/pos e)) radius)))
                   (vals (:world/entities world))))))

(defn intent-view
  "An intent as a line shows it: kind, target, purpose and the goal it serves, or nil."
  [i]
  (when i
    (cond-> {:kind (:intent/kind i)}
      (:intent/target i) (assoc :target (:intent/target i))
      (:intent/for i) (assoc :for (:intent/for i))
      (:intent/goal i) (assoc :goal (:intent/goal i)))))

(defn of
  "One line of what the bot senses and decides in world, a flat map: the time, its body
   (position, dimension, health, food, fire, trap), what it holds that the stages count (rods,
   pearls, free slots), its plan (the running intent, the wait, the last failure), its cover (in
   the pocket, sealed, the refuge done), the hostiles round it (nearest, with distance, health and
   sight), the blazes that see it (blaze/watchers), fireballs near and endermen tracked, and
   tick-ms, the slowest event of the loop since the last line (given by the observer, not the
   world: a reducer reads no clock). Nil without a position: before the bot is in play."
  [world tick-ms]
  (when-let [pos (:player/pos world)]
    (cond-> {:t (:time/now world)
             :pos (mapv r1 pos)
             :dim (:player/dimension world)
             :hp (r1 (:player/health world))
             :food (:player/food world)
             :rods (blaze/rods world)
             :pearls (inventory/item-count world (recipe/item-id :ender_pearl))
             :free (window/free-count world)
             :tick-ms tick-ms
             :hostiles (hostile-list world)
             :watchers (count (blaze/watchers world))
             :projectiles
             (counted-near world projectile-radius #(contains? projectile-kinds (entity/kind %)))
             :endermen (counted-near world 128.0 #(= :enderman (entity/kind %)))}
      (:player/on-fire? world) (assoc :fire? true)
      (:plan/trap world) (assoc :trap (:plan/trap world))
      (:plan/intent world) (assoc :intent (intent-view (:plan/intent world)))
      (:plan/waiting world) (assoc :waiting (:plan/waiting world))
      (:plan/failed world)
      (assoc
       :failed
       (assoc (intent-view (:plan/failed world)) :reason (:intent/reason (:plan/failed world))))
      (:pocket/site world)
      (assoc :pocket {:in? (pocket/in? world) :sealed? (pocket/sealed? world (:pocket/site world))})
      (:pearls/refuge-done world) (assoc :refuge-done true))))

(defn window-of
  "The lines of the last window-ms of lines (in time order)."
  [lines]
  (let [end (:t (peek (vec lines)) 0)] (filterv #(>= (:t % 0) (- end window-ms)) lines)))

(defn classes
  "The behaviour classes the last window-ms of lines show, a set: :freeze (an event over
   freeze-ms), :swarm (swarm-count hostiles within swarm-radius), :burning, :open (the last line
   not sealed in a pocket), :building (a :pocket, :place or :seal intent), :idle (waiting),
   :lava (trapped in lava), :watched (a blaze sees the bot at the end)."
  [lines]
  (let [w (window-of lines)
        last-line (peek w)
        kinds (set (keep #(get-in % [:intent :kind]) w))]
    (cond-> #{}
      (some #(> (:tick-ms % 0) freeze-ms) w) (conj :freeze)
      (some (fn [l] (>= (count (filter #(<= (:d % 99) swarm-radius) (:hostiles l))) swarm-count)) w)
      (conj :swarm)
      (some :fire? w) (conj :burning)
      (and last-line (not (get-in last-line [:pocket :sealed?]))) (conj :open)
      (some #{:pocket :place :seal} kinds) (conj :building)
      (some :waiting w) (conj :idle)
      (some #(= :lava (:trap %)) w) (conj :lava)
      (pos? (:watchers last-line 0)) (conj :watched))))

;;;; I/O: the observer and its file ;;;;

(defn senses!
  "Watch world* and keep its last keep-lines lines (of), one every every-ms of world time,
   computed on a thread of its own from a sliding buffer (a busy thread skips a sample, never
   the loop). slowest is an atom the loop puts each event's ms in (main/apply-event!'s on-ms);
   a line takes its max and resets it. Returns {:lines (fn [] lines) :close (fn [])}."
  [world* slowest]
  (let [ch (async/chan (async/sliding-buffer 1))
        lines (atom [])
        next-t (atom 0)
        done (async/thread (loop []
                             (when-let [w (async/<!! ch)]
                               (let [ms (first (reset-vals! slowest 0))]
                                 (when-let [l (of w ms)]
                                   (swap! lines #(let [v (conj % l)]
                                                   (if (> (count v) keep-lines)
                                                     (subvec v (- (count v) keep-lines))
                                                     v)))))
                               (recur))))]
    (add-watch world*
               ::senses
               (fn [_ _ _ new]
                 (let [t (:time/now new 0)]
                   (when (>= t @next-t) (reset! next-t (+ t every-ms)) (async/offer! ch new)))))
    {:lines (fn [] @lines)
     :close (fn [] (remove-watch world* ::senses) (async/close! ch) (async/<!! done))}))

(defn write!
  "Write lines to path, one EDN map a line, with their class as the first line."
  [path lines]
  (spit path
        (apply str
               (prn-str {:senses/class (classes lines) :senses/lines (count lines)})
               (map prn-str lines))))
