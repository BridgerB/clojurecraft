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
         '[clojurecraft.fortress]
         '[clojurecraft.blocks]
         '[clojurecraft.terrain]
         '[clojure.string :as str])

(def made (atom 0))
(def entry (atom nil))
(def last-dur (atom nil))

(defn track!
  "Counts a stone pickaxe made in the Nether: the best durability held rises."
  [world]
  (when (and (= "minecraft:the_nether" (dimension/of world)) (nil? @entry))
    (reset! entry (:player/pos world)))
  (when @entry
    (let [best (apply max
                      0
                      (for
                        [[_ slot] (:player/inventory world)
                         :when (str/ends-with? (name (recipe/item-name (:item slot))) "_pickaxe")]
                        (or (window/uses-left slot) 0)))]
      (when (and @last-dur (> best (inc @last-dur))) (swap! made inc))
      (reset! last-dur best))))

(def steps (atom []))
(def last-step (atom nil))

(defn trace-step!
  "Records each :tunnel-to step chosen in the Nether: time, feet, move, target, solid cells it digs."
  [world]
  (let [p (:plan/intent world)
        from (:intent/from p)
        move (:intent/move p)]
    (when (and (= :tunnel-to (:intent/kind p))
               move
               (= "minecraft:the_nether" (dimension/of world))
               (not= [from move] @last-step))
      (reset! last-step [from move])
      (let [feet (vec (map #(long (math/floor %)) (:player/pos world)))
            digs (count (filter #(clojurecraft.blocks/solid? (clojurecraft.terrain/block-at world
                                                                                            %))
                                (clojurecraft.fortress/opens from move)))]
        (swap! steps conj
          {:t (quot (:time/now world) 1000)
           :from from
           :move move
           :target (:intent/target p)
           :digs digs
           :why (let [at #(clojurecraft.blocks/name-of (clojurecraft.terrain/block-at world %))
                      seen (set (map :from @steps))]
                  (vec (for [m (take 3
                                     (clojurecraft.fortress/moves from
                                                                  (:intent/target p)
                                                                  (or (:intent/climb p) 3)))
                             :let [to (mapv + from m)]]
                         [m (mapv at [(mapv + to [0 1 0]) to (mapv + to [0 -1 0])]) :lava
                          (count (clojurecraft.fortress/lava-beside world from m)) :safe
                          (boolean (clojurecraft.fortress/safe? world from m)) :seal
                          (boolean (clojurecraft.fortress/sealed? world from m)) :seen
                          (contains? seen to)])))
           :search (some-> (:intent/search p)
                           (select-keys [:search/leg :search/heading]))})))))

(defn step-report
  "Per minute: steps, digs, mean heading cosine, net progress toward the target, move kinds."
  []
  (doseq [[m ss] (sort-by key (group-by #(quot (:t %) 60) @steps))
          :let [n (count ss)
                digs (reduce + (map :digs ss))
                cos (fn [{:keys [from move target]}]
                      (let [tx (- (nth target 0) (nth from 0))
                            tz (- (nth target 2) (nth from 2))
                            d (math/hypot tx tz)
                            mm (math/hypot (nth move 0) (nth move 2))]
                        (if (or (zero? d) (zero? mm))
                          0.0
                          (/ (+ (* tx (nth move 0)) (* tz (nth move 2))) (* d mm)))))
                kinds (frequencies (map (fn [s]
                                          (let [c (cos s)]
                                            (cond (and (zero? (nth (:move s) 2))
                                                       (zero? (nth (:move s) 0)))
                                                  :vertical
                                                  (> c 0.9) :fwd
                                                  (> c 0.1) :diag
                                                  (> c -0.1) :side
                                                  :else :back)))
                                        ss))
                a (first ss)
                z (last ss)
                dist (fn [{:keys [from target]}]
                       (math/hypot (- (nth target 0) (nth from 0))
                                   (- (nth target 2) (nth from 2))))]]
    (println "MIN"
             m
             "steps"
             n
             "digs"
             digs
             "kinds"
             (pr-str kinds)
             "dist"
             (long (dist a))
             "->"
             (long (dist z))
             "y"
             (apply min (map #(nth (:from %) 1) ss))
             (apply max (map #(nth (:from %) 1) ss))
             "target" (pr-str (:target a))
             "search" (pr-str (:search a))))
  (when-let [lo (System/getenv "RAW_FROM")]
    (doseq [s @steps
            :when (and (>= (:t s) (* 60 (Long/parseLong lo)))
                       (< (:t s)
                          (* 60
                             (+ (Long/parseLong lo)
                                (Long/parseLong (or (System/getenv "RAW_LEN") "6"))))))]
      (println "STEP"
               (:t s)
               (pr-str (:from s))
               (pr-str (:move s))
               "digs"
               (:digs s)
               (pr-str (:target s))
               (pr-str (:why s))))))

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
              (trace-step! world)
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
  (step-report)
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
