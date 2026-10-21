(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks])

(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (or (simrace/own-world snap)
                 (simrace/prepared (capture/read-world (:snapshot/file snap))))
          m (simrace/moment-of snap w0)
          r (sim/play-from simrace/step
                           m
                           (fn [w]
                             (let [c (:intent/child (:plan/intent w))]
                               (and (= :place (:intent/kind c)) (= :rejected (:intent/reason c)))))
                           (simrace/window-of snap)
                           (simrace/go-of w0 (:snapshot/until snap))
                           nil)
          w (:world r)
          s (:sim r)
          c (:intent/child (:plan/intent w))
          cell (:intent/cell c)
          against (:intent/against c)]
      (println "==" p "pos" (:player/pos w))
      (println "child" (pr-str c))
      (println "plan-intent" (pr-str (dissoc (:plan/intent w) :intent/child)))
      (doseq [[nm pos] [["cell" cell] ["against" against]]
              :when pos]
        (println nm
                 pos
                 "bot"
                 (some-> (terrain/block-at w pos)
                         blocks/name-of)
                 "sim"
                 (some-> (sim/block-at s pos)
                         blocks/name-of)))
      (doseq [dy (range -2 4)
              dz (range -2 4)]
        (let [pos [(first cell) (+ (second cell) dy) (+ (nth cell 2) dz)]]
          (print (or (some-> (sim/block-at s pos)
                             blocks/name-of
                             name)
                     "?")
                 ""))
        (when (= dz 3) (println "y" (+ (second cell) dy)))))
    (catch Exception e (println "==" p "THREW" (ex-message e)))))
(shutdown-agents)
