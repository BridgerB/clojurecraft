(require '[clojurecraft.blocks :as blocks]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])

(doseq [file *command-line-args*]
  (try (let [snap (capture/read-world file)
             captured (simrace/world-for! snap)
             [world _] (simrace/moment snap captured)
             feet (intent/feet world)
             target (get-in world [:plan/intent :intent/target])]
         (println "SNAPSHOT" file)
         (try (println "QUESTION known bricks and available tunnel steps")
              (prn :feet feet
                   :brick (fortress/nearest-brick world)
                   :search (:fortress/search world)
                   :intent (:plan/intent world))
              (prn :bricks (take 10 (sort-by #(path/flat-distance feet %) (fortress/bricks world))))
              (doseq [move (fortress/moves feet target)]
                (prn :move move
                     :safe (fortress/safe? world feet move)
                     :opens
                     (map #(blocks/name-of (terrain/block-at world %)) (fortress/opens feet move))
                     :floor
                     (blocks/name-of
                      (terrain/block-at world (fortress/plus feet (fortress/plus move [0 -1 0]))))))
              (catch Exception e (println "ERROR tunnel steps" (str e))))
         (try (println "QUESTION existing walk toward the tunnel target")
              (prn :walk
                   (path/plan world
                              feet
                              {:goal/kind :near :goal/pos target :goal/range fortress/reach}))
              (catch Exception e (println "ERROR walk" (str e)))))
       (catch Exception e (println "ERROR snapshot" file (str e)))))
