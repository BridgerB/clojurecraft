(ns clojurecraft.main
  "The loop: the one place with an atom, a clock, randomness and effects. Reads packets and
   external events, feeds the reducer, drains :bot/effects, prints RESULT.

     clojure -M:run --port 25571 [--name N] [--until play|wood|table|pickaxe] [--chain true]
                    [--events stdin]
                    [--timeout-ms 120000] [--hold-ms 0] [--record run.edn [--commit sha]]
                    [--telemetry t.edn]
     clojure -M:replay run.edn

   With --events stdin the bot reads EDN events (one per line) from stdin: that is how the
   fixture (clojure -M:harness, its own process) tells it to go. Without it, a planned --until
   starts where the bot stands, once it is loaded. --telemetry writes one EDN line per change of
   the world, from a watch on the atom (clojurecraft.watch) that never slows the loop."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.armour]
            [clojurecraft.blaze]
            [clojurecraft.build]
            [clojurecraft.cli :as cli]
            [clojurecraft.combat]
            [clojurecraft.conn :as conn]
            [clojurecraft.dragon]
            [clojurecraft.drops]
            [clojurecraft.eat]
            [clojurecraft.end-portal :as end-portal]
            [clojurecraft.entity :as entity]
            [clojurecraft.eyes]
            [clojurecraft.fortress]
            [clojurecraft.game :as game]
            [clojurecraft.hunt]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.observe]
            [clojurecraft.pearls]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.record :as record]
            [clojurecraft.scoop]
            [clojurecraft.spec]
            [clojurecraft.stairs]
            [clojurecraft.stone]
            [clojurecraft.stronghold :as stronghold]
            [clojurecraft.suffocate]
            [clojurecraft.swim]
            [clojurecraft.sword]
            [clojurecraft.watch :as watch]
            [clojurecraft.wood])
  (:gen-class))

(set! *warn-on-reflection* true)

(def step
  "The whole bot: the world reducer, then the planner. Requiring clojurecraft.wood and
   clojurecraft.make registers the goals."
  (game/compose game/step plan/step))

(defn goal-fn
  "What counts as done for a --until name: the plan done when it names goals, else being loaded."
  [until]
  (if (plan/goals-for until) plan/done? :player/loaded?))

(defn ok?
  "Did a run that ended in world succeed, for --until until: its goal holds and the socket is
   neither closed nor disconnected. The live run and its replay judge with this one rule."
  [world until]
  (boolean (and ((goal-fn until) world) (not (:bot/closed world)) (not (:bot/disconnected world)))))

(defn external-event
  "An event line from outside the bot (the fixture's stdout, piped in), read and checked against
   the event spec: [event nil] when it is one, [nil why] when it does not read or is not an
   event. What comes from another process is checked here, at the edge, before it is queued."
  [line]
  (try (let [e (edn/read-string {:readers record/readers} line)]
         (if (s/valid? :clojurecraft.spec/event e)
           [e nil]
           [nil (str "not an event: " (s/explain-str :clojurecraft.spec/event e))]))
       (catch Exception e [nil (str "unreadable: " (ex-message e))])))

(defn result
  "The RESULT map: :ok, :until, the reason, the game summary, held items, the nearest
   remembered table, the health of the nearest living entity of each kind whose health is known
   (:mobs, only when there is one, so older recordings' RESULTs are unchanged; the gym's entities
   trial reads it), the eyes of ender thrown (:throws, only once one is kept: the gym's throw
   trial reads the first), the nearest end portal frame remembered (:frame, only once one is: the
   stronghold trials ask the server whether one stands there), the nearest end portal block
   remembered (:portal, only once one is: the end-portal trial asks the server about it, issue
   #266) and the plan summary (once a plan began)."
  [world until ok]
  (merge {:ok ok
          :until until
          :reason (cond ok :goal
                        (:bot/disconnected world) :disconnected
                        (:bot/closed world) :closed
                        (plan/failed? world) (:plan/reason world)
                        :else :timeout)}
         (game/summary world)
         (let [held (recipe/counts (:player/inventory world))]
           {:held held
            :planks (recipe/have held (recipe/tags :planks))
            :sticks (get held :stick 0)
            :tables (get held :crafting_table 0)})
         (when-let [t (and (:player/pos world)
                           (memory/nearest world (game/eye world) 32 memory/crafting-table?))]
           {:table/pos t}) ; no position yet (an early end): no table to speak of
         (when-let [h (and (:player/pos world)
                           (not-empty (entity/healths world (:player/pos world))))]
           {:mobs h})
         (when (seq (:stronghold/throws world)) {:throws (:stronghold/throws world)}) ; the eyes' lines (issue #265: the gym's throw trial reads the first)
         (when-let [f (and (:player/pos world) (stronghold/nearest-frame world))] {:frame f}) ; the portal room's (the stronghold trials ask the server about it)
         (when-let [p (and (:player/pos world) (end-portal/nearest-portal world))] {:portal p}) ; the lit portal's (the end-portal trial asks the server about it)
         (when (seq (:world/traps world)) {:escaped true}) ; only once a trap was escaped, so older recordings' RESULTs are unchanged
         (when (:plan/status world) {:plan (plan/summary world)})))

;;;; I/O: the clock, stderr, the socket, the atom ;;;;

(defn- now [] (System/currentTimeMillis))

(defn perform!
  "Do one effect: :send puts the packet on the socket's out channel (blocking), :log writes it to stderr."
  [{:effect/keys [kind packet message]} out]
  (case kind
    :send (async/>!! out packet)
    :log (cli/log message)))

(defn apply-event!
  "The epochal write: swap the world, then perform and clear what it asked for."
  [world* event out tap]
  (when tap ((:write tap) event))
  (swap! world* step event)
  (let [effects (:bot/effects @world*)]
    (when tap ((:effects tap) effects))
    (swap! world* assoc :bot/effects [])
    (doseq [e effects] (perform! e out))))

(defn run-loop!
  "Drive the reducer over socket packets, game ticks (physics/tick-ms) and external events until (stop? world)
   or the socket closes. Returns the final world."
  [{:keys [in out]} events world* stop? tap]
  (loop [next-tick (+ (now) physics/tick-ms)]
    (let [[v ch] (async/alts!! [in events (async/timeout (max 0 (- next-tick (now))))])
          event (cond (= ch in)
                      (if (nil? v)
                        {:event/kind :closed :event/reason "socket closed"}
                        {:event/kind :packet :event/packet v})
                      (= ch events) v
                      :else {:event/kind :tick :event/now (now) :event/rand (rand)})]
      (apply-event! world* event out tap)
      (let [w @world*]
        (if (or (stop? w) (:bot/closed w) (:bot/disconnected w))
          w
          (recur (if (= :tick (:event/kind event)) (+ next-tick physics/tick-ms) next-tick)))))))

(defn read-events!
  "Feed EDN events from a reader (the fixture's stdout, piped in) onto the events channel until
   end of input. The only way anything outside the bot reaches it."
  [reader events]
  (async/thread (doseq [line (line-seq (java.io.BufferedReader. reader))
                        :let [line (str/trim line)]
                        :when (seq line)]
                  (let [[e why] (external-event line)]
                    (if e (async/>!! events e) (cli/log "dropped event" line why))))))

(defn go-when-loaded!
  "Put a :go on the queue the first time the world says the player is loaded: the run without
   a fixture starts where the bot stands, as soon as it stands anywhere."
  [world* queue go]
  (add-watch world*
             ::go
             (fn [k r _ w] (when (:player/loaded? w) (remove-watch r k) (async/put! queue go)))))

(defn run-bot!
  "One bot's whole life: connect as name, fold the socket's packets, the clock's ticks and the
   events put on events (a bounded channel: a fixture's :go) into its own world atom until the
   goal holds, a planned run fails, the deadline passes or the socket closes; record it when
   record names a file. Then (on-result result) while still connected, hold hold-ms for an
   outside judge (giving RCON time to read truth inventory before the bot leaves), and close, whatever the run ends with (a throw too). world* is the bot's atom (one is made when none is given;
   a watcher may be on it already). With no events channel and a plan, the run starts where it
   stands as soon as it is loaded. Returns {:result r :world final}. Nothing is shared with
   another bot: a race runs many of these, each its own atom and loop."
  [{:keys [host port name until timeout-ms hold-ms record commit chain events on-result world*]
    :or {host "127.0.0.1" until "wood" timeout-ms 120000 hold-ms 0 on-result (fn [_])}}]
  (let [opts {:host host :port port :name name}
        deadline (+ (now) timeout-ms)
        ;; --chain plays every step up to --until. A gym stage names only its own target.
        planned (if chain (plan/through until) (plan/goals-for until))
        goal? (goal-fn until)
        stop? (fn [w] (or (goal? w) (and planned (plan/failed? w)) (> (now) deadline)))
        tap (when record (record/tap! record (if commit {:record/commit commit} {}))) ; the code that made the run
        c (conn/open! opts)
        queue (or events (async/chan 16))
        world* (or world* (atom (game/init opts)))]
    (cli/log "connected to" host port "as" name)
    (when (and (nil? events) planned)
      (go-when-loaded! world*
                       queue
                       (cond-> {:event/kind :go :go/goals planned} ; no fixture: start where we stand
                         chain (assoc :go/chain? true)))) ; milestones (plan/reach)
    (apply-event! world*
                  {:event/kind :start :start/host host :start/port port :start/name name}
                  (:out c)
                  tap)
    (try (let [final (run-loop! c queue world* stop? tap)
               ok (ok? final until)
               r (result final until ok)]
           (when tap ((:result tap) r) ((:close tap))) ; the recording is the run: it ends at RESULT
           (on-result r)
           (when (and (pos? hold-ms) (not (:bot/closed final)) (not (:bot/disconnected final)))
             (cli/log "holding" hold-ms "ms for an outside judge")
             (let [until (+ (now) hold-ms)]
               (run-loop! c queue world* (fn [_] (> (now) until)) nil)))
           {:result r :world final})
         (finally ((:close! c)))))) ; however the run ends: a race refills the slot, so a socket left open is a leak

(defn -main
  "Connect, run until the goal, a failed plan, the deadline or a closed socket, print RESULT,
   hold for an outside judge, and exit 0 when ok else 1."
  [& args]
  (let [{:keys [host port name until timeout-ms hold-ms events record commit telemetry chain]
         :or {host "127.0.0.1"
              port "25571"
              name "Clj_wood"
              until "wood"
              timeout-ms "120000"
              hold-ms "0"}}
        (cli/options args)
        queue (when (= events "stdin") (async/chan 16))
        world* (atom (game/init {:host host :port (Long/parseLong port) :name name}))
        watcher (some->> telemetry
                         (watch/telemetry! world*))]
    (when queue (read-events! *in* queue)) ; a fixture speaks through the pipe
    (let [{r :result} (run-bot! {:host host
                                 :port (Long/parseLong port)
                                 :name name
                                 :until until
                                 :timeout-ms (Long/parseLong timeout-ms)
                                 :hold-ms (Long/parseLong hold-ms)
                                 :record record
                                 :commit commit
                                 :chain (= chain "true")
                                 :events queue
                                 :world* world*
                                 :on-result (fn [r] (prn 'RESULT r) (flush))})]
      (when watcher ((:close watcher)) (cli/log "telemetry dropped" ((:dropped watcher)) "lines"))
      (shutdown-agents)
      (System/exit (if (:ok r) 0 1)))))

(defn outcome
  "A RESULT as a replay is held to it: everything but the last intent (an executor's internals
   caught mid-tick) and the position (where the last tick's physics happened to leave the
   feet). A dig that gained a :confirm stage since the recording ends a tick later, and the
   same run then stands a fraction of a block elsewhere when its goal is met. What the run
   held, reached and reported is the outcome."
  [result]
  (-> result
      (update :plan dissoc :plan/last)
      (dissoc :pos)))

(defn replay-code
  "Whose code a replay folds a recording with: :same when the recording names the commit that
   is running, :other when it names another (the fold is then another run: newer code decides
   otherwise and the bot ends up elsewhere, which reads as a truncated file; issue #111 was
   that, on whole recordings), :unrecorded when the recording names none, :unknown when the
   running commit cannot be told."
  [recorded running]
  (cond (nil? recorded) :unrecorded
        (nil? running) :unknown
        (= recorded running) :same
        :else :other))

(defn running-commit
  "The commit of the code running, git's HEAD of the working directory, or nil when there is no
   git to ask."
  []
  (try (let [{:keys [exit out]} (sh/sh "git" "rev-parse" "HEAD")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(defn replayed
  "The RESULT of folding the reducer over a recording, judged as the run judged itself (its
   recorded --until), plus :replay/effects (every effect identical, or the first mismatch),
   :replay/result (:identical when the fold reaches the RESULT the run printed: every attribute
   of its outcome (see outcome), with the same value; attributes the model gained since are
   accretion, not change), :replay/commit (the commit the recording names and the one running,
   those known) and :replay/code (replay-code's word on the two)."
  [path]
  (let [world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
        final (record/replay step world0 path)
        check (record/verify step world0 path)
        recorded (record/recorded-result path)
        until (:until recorded "replay")
        r (result final until (ok? final until))
        made-by (:record/commit (record/header path))
        running (running-commit)]
    (assoc r
           :replay/effects
           (cond (nil? check) :identical
                 (= check :record/no-effects) :not-recorded
                 :else check)
           :replay/result
           (cond (nil? recorded) :not-recorded
                 (record/accretes? (outcome recorded) (outcome r)) :identical
                 :else {:recorded recorded})
           :replay/commit
           (cond-> {}
             made-by (assoc :recorded made-by)
             running (assoc :running running))
           :replay/code (replay-code made-by running))))

(defn replay
  "clojure -M:replay run.edn → print the replayed RESULT (see replayed). A fold by other code
   than the recording's is said so on stderr: bb replay folds at the recording's own commit."
  [& [path]]
  (let [r (replayed path)]
    (when (= :other (:replay/code r))
      (cli/log "replay: recorded by"
               (subs (get-in r [:replay/commit :recorded]) 0 7)
               "and folded by"
               (subs (get-in r [:replay/commit :running]) 0 7)
               "- a recording replays only with the code that made it; this fold is another run."
               "bb replay"
               path
               "folds it at its own commit"))
    (prn 'RESULT r))
  (shutdown-agents))
