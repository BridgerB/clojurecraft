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
      (:intent/goal i) (assoc :goal (:intent/goal i))
      (:intent/why (meta i)) (assoc :why (:intent/why (meta i))))))

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

(defn kept
  "lines with line l added, the last keep-lines of them."
  [lines l]
  (let [v (conj (vec lines) l)]
    (if (> (count v) keep-lines) (subvec v (- (count v) keep-lines)) v)))

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

;; ---------------------------------------------------------------- the ledger: every change, not a sample

(def ledger-radius 32.0)               ; blocks from the feet an entity's swing, hurt flash, anger or coming and going is noted within

(defn near-entities
  "{eid entity} of world's entities within ledger-radius of the feet that are not items."
  [world]
  (let [pos (:player/pos world)]
    (into {}
          (filter (fn [[_ e]]
                    (and pos
                         (not (entity/item? e))
                         (<= (physics/distance pos (:entity/pos e)) ledger-radius))))
          (:world/entities world))))

(defn entity-events
  "What changed among the entities near the bot between before and after: one came or went
   (:appear, :gone, with its kind and distance), flashed hurt (:hurt: a swing that landed when it
   is the bot's target), swung (:mob-swing) or turned angry (:angry)."
  [before after]
  (let [b (near-entities before)
        a (near-entities after)
        pos (:player/pos after)
        view (fn [eid e]
               {:eid eid
                :kind (entity/kind e)
                :d (r1 (when pos (physics/distance pos (:entity/pos e))))})]
    (concat (for [[eid e] a :when (not (contains? b eid))] (assoc (view eid e) :ev :appear))
            (for [[eid e] b :when (not (contains? a eid))] (assoc (view eid e) :ev :gone))
            (for [[eid e] a
                  :let [o (get b eid)]
                  :when o
                  [k ev] [[:entity/hurt-at :hurt] [:entity/swung-at :mob-swing]]
                  :when (and (get e k) (not= (get e k) (get o k)))]
              (assoc (view eid e) :ev ev :hp (r1 (:entity/health e))))
            (for [[eid e] a
                  :let [o (get b eid)]
                  :when (and o (:entity/creepy? e) (not (:entity/creepy? o)))]
              (assoc (view eid e) :ev :angry)))))

(defn intent-events
  "What changed in the plan between before and after: a new intent (:intent, with its kind,
   target, purpose, why and goal), a new failure (:failed, with its reason), a new wait (:wait),
   a trap entered or left (:trap)."
  [before after]
  (let [ib (:plan/intent before)
        ia (:plan/intent after)
        fb (:plan/failed before)
        fa (:plan/failed after)]
    (cond-> []
      (and ia (not= (:intent/id ia) (:intent/id ib))) (conj (assoc (intent-view ia) :ev :intent))
      (and fa (not= fa fb)) (conj (assoc (intent-view fa) :ev :failed :reason (:intent/reason fa)))
      (not= (:plan/waiting before) (:plan/waiting after))
      (conj {:ev :wait :wait (:plan/waiting after)})
      (not= (:plan/trap before) (:plan/trap after)) (conj {:ev :trap :trap (:plan/trap after)}))))

(defn body-events
  "What changed in the bot's own body and gear between before and after: a hit taken (:hit, the
   newest of :player/hits: type, attacker and kind, health before), health or food (:hp), armour
   (:armor), effects (:effects), the fight bracket (:combat), the dimension (:dim), a death and a
   respawn."
  [before after]
  (let [hb (:player/hits before)
        ha (:player/hits after)]
    (cond-> []
      (and (seq ha) (not= (peek ha) (peek hb))) (conj (assoc (peek ha) :ev :hit))
      (or (not= (:player/health before) (:player/health after))
          (not= (:player/food before) (:player/food after)))
      (conj {:ev :hp :hp (r1 (:player/health after)) :food (:player/food after)})
      (not= (:player/armor before) (:player/armor after))
      (conj {:ev :armor :armor (:player/armor after)})
      (not= (:player/effects before) (:player/effects after))
      (conj {:ev :effects :effects (vec (keys (:player/effects after)))})
      (not= (:player/in-combat? before) (:player/in-combat? after))
      (conj {:ev :combat :in? (boolean (:player/in-combat? after))})
      (not= (:player/dimension before) (:player/dimension after))
      (conj {:ev :dim :dim (:player/dimension after)})
      (and (:player/dead? after) (not (:player/dead? before))) (conj {:ev :death})
      (and (:player/dead? before) (not (:player/dead? after))) (conj {:ev :respawn}))))

(defn sent-events
  "What the bot sent that a reader of a fight or a build wants: an attack (:swing, its target and
   whether it is the running intent's), a block dug or placed (:dig, :place)."
  [after]
  (for [{:effect/keys [kind packet]} (:bot/effects after)
        :when (= :send kind)
        :let [n (:packet/name packet)]
        :when (#{:attack :player-action :use-item-on} n)]
    (case n
      :attack
      (let [eid (:entity-id packet)
            e (get-in after [:world/entities eid])]
        {:ev :swing :eid eid :kind (when e (entity/kind e)) :hp (r1 (:entity/health e))})
      :player-action {:ev :dig :status (:status packet) :pos (:pos packet)}
      :use-item-on {:ev :place :pos (:pos packet)})))

(defn events
  "Every change between two successive worlds a reader of a run wants, in order, each a map with
   :ev and :t (after's time): the plan's (intent-events), the body's (body-events), what the bot
   sent (sent-events) and the entities near it (entity-events). A pure function of the two values:
   the ledger observer folds it over every write of the atom, so nothing is sampled away (the
   observability plan, part C: the digest, done in-process, named and the same in the sim)."
  [before after]
  (let [t (:time/now after)]
    (when (:player/pos after)
      (mapv #(assoc % :t t)
            (concat (intent-events before after)
                    (body-events before after)
                    (sent-events after)
                    (entity-events before after))))))

;;;; I/O: the observer and its file ;;;;

(def ledger-buffer 8192)               ; changes in flight to the ledger's thread before one is dropped (and counted)
(def ledger-keep 20000)                ; events a ledger keeps, the newest: a long run's whole fight and more

(defn ledger!
  "Watch world* and keep every change (events of each write and the one before it), on a thread
   of its own from a bounded buffer: a full buffer drops the change and counts it (the loop never
   waits), so a ledger says how complete it is. Returns {:events (fn []) :dropped (fn [])
   :close (fn [])}."
  [world*]
  (let [ch (async/chan ledger-buffer)
        kept (atom [])
        dropped (atom 0)
        done (async/thread (loop []
                             (when-let [[b a] (async/<!! ch)]
                               (when-let [es (seq (events b a))]
                                 (swap! kept (fn [v]
                                               (let [v (into v es)]
                                                 (if (> (count v) ledger-keep)
                                                   (subvec v (- (count v) ledger-keep))
                                                   v)))))
                               (recur))))]
    (add-watch world*
               ::ledger
               (fn [_ _ old new] (when-not (async/offer! ch [old new]) (swap! dropped inc))))
    {:events (fn [] @kept)
     :dropped (fn [] @dropped)
     :close (fn [] (remove-watch world* ::ledger) (async/close! ch) (async/<!! done))}))

(defn write-events!
  "Write a ledger's events to path, one EDN map a line, after a head line with their count and
   the changes dropped."
  [path es dropped]
  (spit path
        (apply str (prn-str {:ledger/events (count es) :ledger/dropped dropped}) (map prn-str es))))

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
