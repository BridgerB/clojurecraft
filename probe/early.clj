(require '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.game :as game]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.make :as make]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.path :as path]
         '[clojurecraft.place :as place]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.wood :as wood])

(defn out [& xs] (println (str/join " " xs)))

(defn ask
  [label f]
  (try (out label (pr-str (f)))
       (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))

(defn names-round
  "The block names in the cells round the feet, a layer a line, dy from 2 down to -1, rows z."
  [world]
  (let [[fx fy fz] (intent/feet world)]
    (doseq [dy [2 1 0 -1]]
      (out "  y" (+ fy dy))
      (doseq [dz [-1 0 1]]
        (out "   "
             (str/join " "
                       (for [dx [-1 0 1]]
                         (or (some-> (terrain/block-at world [(+ fx dx) (+ fy dy) (+ fz dz)])
                                     blocks/name-of
                                     name)
                             "nil"))))))))

(defn wood-asks
  "Every candidate of wood/gather-next, and the logs remembered near the eye."
  [world tag]
  (ask (str tag " feet") #(intent/feet world))
  (ask (str tag " out?") #(stairs/out? world (intent/feet world)))
  (ask (str tag " under-sky?") #(terrain/under-sky? world (intent/feet world)))
  (ask (str tag " in-water?") #(:player/in-water? world))
  (ask (str tag " boxed-at") #(:plan/boxed-at world))
  (ask (str tag " blacklist") #(:plan/blacklist world))
  (ask (str tag " trunk-failures") #(:plan/trunk-failures world))
  (ask (str tag " logs")
       #(->> (memory/positions-now world blocks/log-states)
             (memory/nearest-first (game/eye world) 48)
             (take 10)
             (map (fn [p] [p :standing (wood/standing-trunk? world p) :skipped
                           ((wood/skipped world) p) :below
                           (some-> (terrain/block-at world (update p 1 dec))
                                   blocks/name-of)]))))
  (ask (str tag " continue-gather") #(wood/continue-gather world))
  (ask (str tag " near-walk") #(wood/near-walk world))
  (ask (str tag " climb-out") #(wood/climb-out world))
  (ask (str tag " hunt") #(wood/hunt world))
  (ask (str tag " into-water") #(wood/into-water world))
  (ask (str tag " crossing") #(wood/crossing world))
  (ask (str tag " return-walk") #(wood/return-walk world))
  (ask (str tag " gather-next") #(wood/gather-next world))
  (ask (str tag " way-out") #(wood/way-out world)))

(defn map-round
  "The terrain round the feet, r cells each way, heights from 3 over the feet to 3 under: # solid,
   W water, . clear, ? unknown, L lava, ~ other."
  [world r]
  (let [[fx fy fz] (intent/feet world)]
    (doseq [y (range (+ fy 3) (- fy 4) -1)]
      (out "  map y" y)
      (doseq [z (range (- fz r) (inc (+ fz r)))]
        (out "   "
             (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                         (if (= [x y z] [fx fy fz])
                           "@"
                           (case (path/classify world [x y z])
                             :solid "#"
                             :water "W"
                             :clear "."
                             :unknown "?"
                             :lava "L"
                             "~")))))))))

(defn route-asks
  "The route the planner finds to each of the nearest standing trunks, and the mobs near."
  [world tag]
  (ask (str tag " routes")
       #(for [p (->> (memory/positions-now world blocks/log-states)
                     (memory/nearest-first (game/eye world) 48)
                     (filter (fn [q] (wood/standing-trunk? world q)))
                     (take 3))]
          (let [i (wood/log-walk world p)
                r (path/plan world (path/feet-cell (:player/pos world)) (intent/goal-of i) {})]
            [p (intent/goal-of i) (:path/status r) (count (:path/waypoints r))
             (last (:path/waypoints r))])))
  (ask (str tag " hostiles")
       #(for [[eid e] (entity/hostiles world)
              :let [d (physics/distance (:player/pos world) (:entity/pos e))]
              :when (< d 12)]
          [eid (entity/kind e) (long d) (:entity/pos e)])))

(defn facts
  [world tag]
  (ask (str tag " pos") #(:player/pos world))
  (ask (str tag " health/food") #((juxt :player/health :player/food) world))
  (ask (str tag " held") #(recipe/counts (:player/inventory world)))
  (ask (str tag " intent")
       #(select-keys (:plan/intent world)
                     [:intent/kind :intent/for :intent/stage :intent/target :intent/status]))
  (ask (str tag " child")
       #(select-keys (:intent/child (:plan/intent world))
                     [:intent/kind :intent/for :intent/target :intent/status :intent/reason]))
  (ask (str tag " chosen")
       #(let [g (plan/choose world plan/goals)]
          (select-keys g [:goal/id :goal/act :goal/plan :goal/needs :goal/item :goal/doc])))
  (ask (str tag " row")
       #(select-keys (make/next-row world (plan/choose world plan/goals) plan/goals)
                     [:goal/id :goal/act :goal/item :goal/doc]))
  (ask (str tag " next-intent") #(plan/next-intent world (plan/choose world plan/goals) plan/goals))
  (ask (str tag " last") #(:plan/last world))
  (ask (str tag " reason") #(:plan/reason world))
  (ask (str tag " wait") #(:plan/wait world))
  (ask (str tag " names") #(names-round world))
  (route-asks world tag)
  (ask (str tag " map") #(map-round world 10)))

(defn play
  "The snapshot played as judged, printing the intent's changes (the first 70) as they come;
   the world it ended in."
  [snap w]
  (let [m (simrace/moment-of snap w)
        stage (simrace/stage-of snap)
        until (:snapshot/until snap)
        seen (atom nil)
        n (atom 0)
        stop?
        (fn [world]
          (let [i (:plan/intent world)
                c (:intent/child i)
                cur [(try (intent/feet world) (catch Exception _ nil))
                     (select-keys i [:intent/kind :intent/for :intent/stage :intent/target])
                     (select-keys c [:intent/kind :intent/target :intent/status :intent/reason])]]
            (when (and (not= cur @seen) (< @n 70))
              (reset! seen cur)
              (swap! n inc)
              (out (:time/now world) (pr-str cur)))
            (or (plan/done? world) (plan/failed? world) (contains? (:plan/reached world) stage))))
        r (sim/play-from simrace/step m stop? (simrace/window-of snap) (simrace/go-of w until) nil)]
    (:world r)))

(def wood? #{:no-log})

(doseq [p *command-line-args*]
  (try (let [snap (capture/read-world p)
             w (simrace/world-for! snap)
             [world _sim] (simrace/moment snap w)
             sig (:snapshot/signature snap)]
         (out "==" p (pr-str sig) (pr-str (:snapshot/moment snap)))
         (facts world "start")
         (when (wood? (second sig)) (wood-asks world "start"))
         (let [end (play snap w)]
           (out "-- end")
           (facts end "end")
           (when (wood? (second sig)) (wood-asks end "end"))
           (when (= :no-water (second sig))
             (ask "end sources" #(take 5 (scoop/sources end)))
             (ask "end scoop-next" #(scoop/scoop-next end))
             (ask "end deep?" #(scoop/deep? end))
             (ask "end go-at" #(:plan/go-at end)))
           (ask "end place/spot" #(place/spot end))
           (ask "end place/niche" #(place/niche end))
           (ask "end place/fresh-rock" #(place/fresh-rock end))))
       (catch Exception e (out "==" p "THREW" (ex-message e)))))

(shutdown-agents)
