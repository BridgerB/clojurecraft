(require '[clojure.math :as math]
         '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.probe :as probe]
         '[clojurecraft.stronghold :as stronghold]
         '[clojurecraft.terrain :as terrain])

(defn out [& xs] (println (str/join " " xs)))

(defn ask
  [label f]
  (try (out label (pr-str (f)))
       (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))

(defn surface
  "A char for column x z: the highest standable cell within 16 of fy, as its height off fy
   (0-9, a-g up, A-G down), W when the top of the column there is water, ? unloaded."
  [w x z fy]
  (let [ys (range (+ fy 16) (- fy 17) -1)
        top (first (filter #(some? (terrain/block-at w [x % z])) ys))
        topid
        (some #(let [id (terrain/block-at w [x % z])] (when (and id (not (blocks/air? id))) id)) ys)
        s (first (filter #(path/standable? w [x % z]) ys))]
    (cond (nil? top) "?"
          (and topid (contains? blocks/water-states topid)) "W"
          (nil? s) "#"
          :else
          (let [d (- s fy)]
            (cond (<= 0 d 9) (str d)
                  (pos? d) (str (char (+ 96 (min 7 (- d 9)))))
                  :else (str (char (+ 64 (min 7 (- d))))))))))

(doseq [p *command-line-args*]
  (try (let [w (probe/fold p (constantly false))
             [fx fy fz :as feet] (intent/feet w)
             ts (:stronghold/throws w)]
         (out "==" p "feet" (pr-str feet) "reason" (:plan/reason w))
         (ask " throws" #(mapv (juxt :throw/from :throw/dir) ts))
         (ask " spot" #(stronghold/second-spot (peek ts)))
         (ask " crossing" #(stronghold/crossing w))
         (ask " blacklist" #(:plan/blacklist w))
         (ask " next" #(stronghold/stronghold-next w))
         (doseq [turn stronghold/hop-turns]
           (ask (str " turn " turn)
                #(let [[x z] (stronghold/second-spot (peek ts))
                       v [(- x fx) (- z fz)]
                       d (stronghold/norm v)
                       [ux uz] (stronghold/rotate [(/ (first v) d) (/ (second v) d)] turn)
                       cx (long (math/floor (+ fx 0.5 (* 32 ux))))
                       cz (long (math/floor (+ fz 0.5 (* 32 uz))))
                       c (stronghold/stand-in w cx cz fy)]
                   [cx cz c
                    (when c
                      (:path/status
                       (path/plan w feet {:goal/kind :near :goal/pos c :goal/range 3})))])))
         (out " map (x right, z down), @ feet, radius 40")
         (doseq [z (range (- fz 40) (+ fz 41) 2)]
           (out (str/join (for [x (range (- fx 40) (+ fx 41) 2)]
                            (if (and (<= (abs (- x fx)) 1) (<= (abs (- z fz)) 1))
                              "@"
                              (surface w x z fy)))))))
       (catch Exception e (out "==" p "THREW" (ex-message e)) (.printStackTrace e))))
(shutdown-agents)
