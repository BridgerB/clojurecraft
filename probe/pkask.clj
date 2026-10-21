(ns probe.pkask
  "Portal-kit batch: what the code sees at each snapshot's moment (feet, terrain layers, the swim
   and wood helpers' answers), one try per question."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.swim :as swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.wood :as wood]
            [probe.pkplay :as pkplay]))

(defn out [& xs] (println (str/join " " xs)))

(defn ask
  [label f]
  (try (out label (pr-str (f)))
       (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))

(defn layers
  "The classified cells round the feet, one block of text a level, top level first."
  [world r up down]
  (let [[fx fy fz] (intent/feet world)]
    (doseq [y (range (+ fy up) (- fy down 1) -1)]
      (out "y" y (if (= y fy) "(feet)" ""))
      (doseq [z (range (- fz r) (inc (+ fz r)))]
        (out (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                         (if (= [x y z] [fx fy fz])
                           "@"
                           (case (path/classify world [x y z])
                             :solid "#"
                             :water "W"
                             :clear "."
                             :unknown "?"
                             :lava "L"
                             "~"))))
             "|"
             z)))))

(defn swim-questions
  [world]
  (let [feet (intent/feet world)
        surface (swim/surface-y world :water feet)
        top (swim/float-top world feet surface)
        [fx _ fz] feet]
    (ask " surface/top" #(vector surface top))
    (ask " under-sky" #(terrain/under-sky? world feet))
    (ask " filler" #(stairs/filler world 1))
    (ask " banks" #(swim/banks world feet surface #{}))
    (ask " bank-block" #(swim/bank-block world feet surface))
    (ask " notch" #(swim/notch world feet surface))
    (ask " obstacle" #(swim/obstacle world feet nil))
    (ask " eye" #(game/eye world))
    (doseq [dx (range -4 5)
            dz (range -4 5)
            :let [x (+ fx dx)
                  z (+ fz dz)
                  cell [x top z]
                  bank [x (inc top) z]]
            :when (and (= :water (terrain/fluid-kind world cell))
                       (<= (physics/distance (game/eye world) (physics/centre cell)) 4.5))]
      (ask (str " cand " cell)
           #(hash-map :clear (path/clear? world bank)
                      :clear2 (path/clear? world [x (+ top 2) z])
                      :open (swim/in-the-open? world bank)
                      :inside (place/inside-player? world cell)
                      :support (some? (place/support world cell))
                      :dist (physics/distance (game/eye world) (physics/centre cell)))))))

(defn hook
  "On the first swim-out or back-off of a run, what the code sees in that world."
  [seen]
  (fn [wd i]
    (let [k (:intent/kind i)]
      (when (and (#{:swim-out :back-off} k) (not (contains? @seen k)))
        (swap! seen conj k)
        (out "-- first" k "at" (:time/now wd))
        (ask " feet" #(vector (intent/feet wd) (:player/pos wd) (:player/health wd)))
        (ask
         " hostiles"
         #(into {} (map (fn [[k e]] [k [(entity/kind e) (:entity/pos e)]])) (entity/hostiles wd)))
        (ask " terrain" #(layers wd 6 3 6))
        (when (= :swim-out k) (swim-questions wd))))))

(defn sampler
  "On every sample of a run while a swim-out is going, up to three times, what the swim sees."
  [n]
  (fn [wd]
    (when (= :swim-out (get-in wd [:plan/intent :intent/kind])) (swap! n inc))
    (when (and (= :swim-out (get-in wd [:plan/intent :intent/kind])) (<= 5 @n 7))
      (out "-- sample at" (:time/now wd) (:player/pos wd))
      (swim-questions wd))))

(defn run-one
  [p]
  (let [snap (capture/read-world p)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        world (:world m)
        feet (intent/feet world)]
    (out "==" p (pr-str (:snapshot/signature snap)))
    (ask " feet"
         #(vector feet (:player/pos world) (:player/health world) (:player/in-water? world)))
    (ask " held" #(:player/inventory world))
    (ask " intent" #(:plan/intent world))
    (ask " go-at" #(:plan/go-at world))
    (ask " blacklist" #(:plan/blacklist world))
    (ask " on-ice" #(path/on-ice? world))
    (ask
     " hostiles"
     #(into {} (map (fn [[k e]] [k [(entity/kind e) (:entity/pos e)]])) (entity/hostiles world)))
    (ask " logs-known" #(count (memory/positions-now world blocks/log-states)))
    (ask " gather-next" #(wood/gather-next world))
    (doseq [[label f] [["continue-gather" wood/continue-gather] ["near-walk" wood/near-walk]
                       ["climb-out" wood/climb-out] ["hunt" wood/hunt]
                       ["into-water" wood/into-water] ["crossing" wood/crossing]
                       ["return-walk" wood/return-walk]]]
      (ask (str "  wood/" label) #(f world)))
    (ask " nearest-logs"
         #(->> (memory/positions-now world blocks/log-states)
               (memory/nearest-first (game/eye world) 256)
               (take 8)
               (mapv (fn [c] [c (wood/standing-trunk? world c) (boolean ((wood/skipped world) c))
                              (int (physics/distance (game/eye world) (physics/centre c)))]))))
    (ask " trunk-failures" #(:plan/trunk-failures world))
    (ask " unreached" #(:plan/unreached world))
    (ask " terrain" #(layers world 6 3 4))
    (when (:player/in-water? world) (swim-questions world))
    (pkplay/play p 0 (hook (atom #{})) (sampler (atom 0)))))

(defn -main
  [& paths]
  (doseq [p paths] (try (run-one p) (catch Exception e (out "==" p "THREW" (ex-message e)))))
  (shutdown-agents))
