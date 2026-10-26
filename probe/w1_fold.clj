;; Issue #263, wonder round: gym batch pearls-w1 (plan run 40387599204) run 1 of pearls-12
;; went up in flames at 19:54:54 while in :lookout/:eat at y 37-44, then respawned in the
;; overworld, made a wooden sword and failed :no-pickaxe. Fold its recording at its own
;; commit (380813e) and print every health change and intent change with the blocks at the
;; feet, the head, under the feet and beside the feet, so the ignition source is named.
;;
;;   clojure -M:dev probe/w1_fold.clj probe/263-w1/run1.edn.zst
(require '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.main :as main]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.probe :as probe]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain])

(defn out [& xs] (locking *out* (println (str/join " " (map pr-str xs))) (flush)))

(def path (first *command-line-args*))

(defn bname
  [w c]
  (some-> (terrain/block-at w c)
          blocks/name-of))

(defn cells
  [w]
  (let [[x y z] (intent/feet w)]
    {:feet [x y z]
     :at (bname w [x y z])
     :head (bname w [x (inc y) z])
     :under (bname w [x (dec y) z])
     :side (mapv (fn [c] [(bname w c) (bname w [(first c) (dec y) (nth c 2)])])
                 [[(inc x) y z] [(dec x) y z] [x y (inc z)] [x y (dec z)]])
     :burning (pearls/burning-at w)
     :in-lava (:player/in-lava? w)
     :trap (:plan/trap w)}))

(defn ikey
  [w]
  (let [i (:plan/intent w)] [(:intent/id i) (:intent/kind i) (:intent/for i) (:intent/target i)]))

(try (out :commit (:record/commit (record/header path) :unrecorded))
     (let [bad (atom nil)]
       (record/reduce-entries
        (fn [st e]
          (if-not (:event/kind e)
            st
            (let [w (assoc (main/step (:w st) e) :bot/effects [])
                  t (:time/now w)
                  t0 (or (:t0 st) t)
                  s (quot (- t t0) 1000)
                  h (:player/health w)
                  ik (ikey w)]
              (when (and h (:h st) (not= h (:h st)))
                (out :t s :health (:h st) :-> h :food (:player/food w) :intent ik (cells w)))
              (when (not= ik (:ik st)) (out :t s :intent ik :health h :pos (:player/pos w)))
              (when (and h (< h 12) (not (:bad st)))
                (reset! bad w)
                (out :first-under-12 :t s (cells w))
                (doseq [line (probe/around w 8)] (println line)))
              {:w w :t0 t0 :h h :ik ik :bad (or (:bad st) (and h (< h 12)))})))
        {:w (game/init {:host "replay" :port 0 :name "Clj_replay"})}
        path)
       (when @bad (doseq [line (probe/around @bad 8)] (println line))))
     (catch Exception ex (out :error (ex-message ex) (vec (take 5 (map str (.getStackTrace ex)))))))
(out :done)
(shutdown-agents)
