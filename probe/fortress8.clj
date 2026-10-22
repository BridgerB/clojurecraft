(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.game :as game]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.build :as build]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.window :as window]
         '[clojure.math :as math]
         '[clojure.string :as str])

(def made (atom 0))
(def entry (atom nil))
(def last-dur (atom nil))

(defn track!
  "Counts a stone pickaxe made in the Nether: the best durability held rises."
  [world]
  (when (and (= :nether (dimension/of world)) (nil? @entry)) (reset! entry (:player/pos world)))
  (when @entry
    (let [best (apply max
                      0
                      (for
                        [[_ slot] (:player/inventory world)
                         :when (str/ends-with? (name (recipe/item-name (:item slot))) "_pickaxe")]
                        (or (window/uses-left slot) 0)))]
      (when (and @last-dur (> best (inc @last-dur))) (swap! made inc))
      (reset! last-dur best))))

(defn inv-line
  [world]
  (let [inv (:player/inventory world)
        tally (reduce (fn [m [_ {:keys [item count]}]]
                        (update m (recipe/item-name item) (fnil + 0) (or count 1)))
                      {}
                      inv)
        picks (keep (fn [[p slot]]
                      (let [n (recipe/item-name (:item slot))]
                        (when (str/ends-with? (name n) "_pickaxe") [n (window/uses-left slot)])))
                    inv)]
    (pr-str
     {:dim (some-> (dimension/of world)
                   str)
      :pos (mapv #(math/round (double %)) (:player/pos world))
      :hp (:player/health world)
      :food (:player/food world)
      :picks (vec picks)
      :stick (get tally :stick 0)
      :planks (reduce + (for [[k v] tally :when (str/ends-with? (name k) "_planks")] v))
      :logs (reduce + (for [[k v] tally :when (str/ends-with? (name k) "_log")] v))
      :table (get tally :crafting_table 0)
      :cobble
      (+ (get tally :cobblestone 0) (get tally :cobbled_deepslate 0) (get tally :blackstone 0))
      :goal (:intent/goal (:plan/intent world))
      :intent (:intent/kind (:plan/intent world))
      :wood-near (try (build/wood-near? world) (catch Exception e :err))
      :made @made
      :tunnel (when-let [e @entry]
                (long (math/hypot (- (nth (:player/pos world) 0) (nth e 0))
                                  (- (nth (:player/pos world) 2) (nth e 2)))))})))

(let [[file seed until minutes every] *command-line-args*
      seed (Long/parseLong seed)
      minutes (Long/parseLong minutes)
      every (if-let [e (System/getenv "EVERY_S")]
              (* 1000 (Long/parseLong e))
              (* 60000 (Long/parseLong every)))
      w0 (simrace/prepared (capture/read-world file))
      nether (simrace/nether-file (java.io.File. file))
      w (cond-> (assoc w0 :capture/file file)
          (.exists (java.io.File. nether))
          (simrace/with-nether (simrace/read-nether nether) nether))
      last-at (atom -1)
      stop? (fn [world]
              (track! world)
              (let [k (quot (:time/now world) every)]
                (when (not= k @last-at)
                  (reset! last-at k)
                  (if-let [lo (System/getenv "FINE_FROM")]
                    (when (>= (:time/now world) (* 60000 (Long/parseLong lo)))
                      (let [p (:plan/last world)]
                        (println "FINE"
                                 (quot (:time/now world) 1000)
                                 "s"
                                 (pr-str (mapv #(math/round (double %)) (:player/pos world)))
                                 (:intent/kind p)
                                 (:intent/steps p)
                                 (:intent/retreats p)
                                 (pr-str (:intent/search p))
                                 (count (:intent/tunnel-visited p))
                                 (pr-str (:plan/waiting world))
                                 (pr-str (:intent/op p)))))
                    (println file (quot (:time/now world) 60000) "min" (inv-line world)))
                  (flush)))
              (or (plan/done? world) (plan/failed? world)))
      {:keys [world]} (sim/run+ simrace/step
                                (game/init {:host "sim" :port 0 :name "Clj_sim"})
                                (sim/init (cond-> {:columns (:capture/columns w)
                                                   :decoded (:capture/decoded w)
                                                   :spawn (simrace/spawn-of w)
                                                   :seed seed
                                                   :tick-seed seed
                                                   :difficulty simrace/difficulty
                                                   :day-time (simrace/day-time-of seed)}
                                            (:capture/nether w)
                                            (assoc :nether (:capture/nether w))))
                                stop?
                                (* 60000 minutes)
                                (simrace/go-of w until))]
  (println file
           "END"
           (quot (:time/now world) 60000)
           "min"
           (inv-line world)
           (pr-str (select-keys world [:plan/status :plan/reason :plan/last :plan/waiting])))
  (doseq [line (simrace/story world
                              (* 60000
                                 (Long/parseLong (or (System/getenv "STORY_FROM") "100000"))))]
    (println "STORY" (quot (first line) 1000) "s" (pr-str (rest line))))
  (shutdown-agents))
