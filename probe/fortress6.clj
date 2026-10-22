(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.game :as game]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.build :as build]
         '[clojurecraft.recipe :as recipe]
         '[clojure.math :as math]
         '[clojure.string :as str])

(defn inv-line
  [world]
  (let [inv (:player/inventory world)
        tally (reduce (fn [m [_ {:keys [item count]}]]
                        (update m (recipe/item-name item) (fnil + 0) (or count 1)))
                      {}
                      inv)
        picks (keep (fn [[p {:keys [item durability]}]]
                      (let [n (recipe/item-name item)]
                        (when (str/ends-with? (name n) "_pickaxe") [n durability])))
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
      :carry-sticks (try (build/carry-sticks? world) (catch Exception e :err))
      :carry-table (try (build/carry-table? world) (catch Exception e :err))
      :wanted (try (build/kit-sticks-wanted world) (catch Exception e :err))})))

(let [[file seed until minutes every] *command-line-args*
      seed (Long/parseLong seed)
      minutes (Long/parseLong minutes)
      every (* 60000 (Long/parseLong every))
      w0 (simrace/prepared (capture/read-world file))
      nether (simrace/nether-file (java.io.File. file))
      w (cond-> (assoc w0 :capture/file file)
          (.exists (java.io.File. nether))
          (simrace/with-nether (simrace/read-nether nether) nether))
      last-at (atom -1)
      stop? (fn [world]
              (let [k (quot (:time/now world) every)]
                (when (not= k @last-at)
                  (reset! last-at k)
                  (println file (quot (:time/now world) 60000) "min" (inv-line world))
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
           (pr-str (plan/failed? world)))
  (shutdown-agents))
