;; Per-step trace of :tunnel-to over a recording, for a probe (never run on the Mac): every time the running
;; :tunnel-to chooses a move, its feet, target, move, retreats, visited cells and steps taken; every
;; time a new :tunnel-to starts, its target and search leg.
;;   clojure -M scripts/flip_tunnel.clj run.edn.zst 240
(require '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojure.math]
         '[clojurecraft.main :as main]
         '[clojurecraft.physics]
         '[clojurecraft.record :as record])

(let [[path secs] *command-line-args*
      secs (Long/parseLong (or secs "240"))
      events (record/events path)
      end (or (some :event/now (reverse events)) 0)
      cutoff (- end (* 1000 secs))
      world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})]
  (reduce
   (fn [[w prev-move prev-id] e]
     (let [w (assoc (main/step w e) :bot/effects [])
           i (:plan/intent w)
           tunnel? (= :tunnel-to (:intent/kind i))
           now (:time/now w 0)
           move (when tunnel? (:intent/move i))
           id (:intent/id i)]
       (when (and tunnel? (>= now cutoff))
         (cond
           (not= id prev-id)
           (prn
            {:new-tunnel id
             :t (- now cutoff)
             :feet (intent/feet w)
             :target (:intent/target i)
             :search (:intent/search i)
             :steps (:intent/steps i)
             :hp (:player/health w)
             :blaze (try (let [sp ((requiring-resolve 'clojurecraft.blaze/nearest-spawner) w)]
                           {:spawner sp
                            :stand (when sp ((requiring-resolve 'clojurecraft.blaze/stand) w sp))
                            :given-up? (when sp
                                         ((requiring-resolve 'clojurecraft.blaze/given-up?) w sp))
                            :blacklist (:plan/blacklist w)})
                         (catch Exception e (str e)))
             :brick (try ((requiring-resolve 'clojurecraft.fortress/nearest-brick) w)
                         (catch Exception e (str e)))
             :planner
             (try
               (let [feet (intent/feet w)
                     nxt {:intent/kind :tunnel-to :intent/target (:intent/target i)}]
                 {:feet feet
                  :menaced? (boolean ((requiring-resolve 'clojurecraft.plan/menaced?) w))
                  :hostiles (->> ((requiring-resolve 'clojurecraft.entity/hostiles) w)
                                 (map (fn [[eid e]]
                                        [eid ((requiring-resolve 'clojurecraft.entity/kind) e)
                                         (some-> (:entity/pos e)
                                                 (#(clojurecraft.physics/distance (:player/pos w)
                                                                                  %))
                                                 (clojure.math/round))]))
                                 (take 6)
                                 vec)
                  :on-fire (:player/on-fire? w)
                  :hurt-at (:player/hurt-at w)
                  :tick (:time/tick w)
                  :failed-before? ((requiring-resolve 'clojurecraft.plan/failed-before?) w nxt feet)
                  :area-blocked? ((requiring-resolve 'clojurecraft.plan/area-blocked?) w nxt feet)
                  :n-failures (count (:plan/failures w))
                  :trail-n (count (:plan/trail w))
                  :held (into {} (map (fn [[_ s]] [(:item s) (:count s)])) (:player/inventory w))})
               (catch Exception e (str e)))})
           (and move (not= move prev-move))
           (prn {:id id
                 :t (- now cutoff)
                 :feet (intent/feet w)
                 :target (:intent/target i)
                 :move move
                 :taken (:intent/taken i)
                 :retreats (:intent/retreats i)
                 :visited (count (:intent/tunnel-visited i))})))
       [w move id]))
   [world0 nil nil]
   events)
  (println "events end at" end "ms"))
(shutdown-agents)
