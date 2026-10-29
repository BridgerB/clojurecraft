(require '[clojurecraft.dragon :as dragon]
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.record :as record])

(defn show
  "The facts about the perch decision in world w, one line each."
  [label w]
  (let [[eid d] (dragon/dragon w)
        pos (:player/pos w)
        stand (dragon/fountain-stand w)
        far? (and stand
                  (> (physics/horizontal-distance pos (physics/centre stand)) dragon/stand-near))]
    (println label)
    (println "  pos" pos
             "health" (:player/health w)
             "intent" (select-keys (:plan/intent w) [:intent/kind :intent/status]))
    (println "  dragon" eid (select-keys d [:entity/pos :entity/yaw :entity/phase]))
    (println "  stand" stand
             "far?" far?
             "in-basin?" (dragon/in-basin? w)
             "from-fountain" (dragon/from-fountain w))
    (println "  breath-near" (some? (dragon/breath-near w))
             "holds?" (dragon/holds? w)
             "grounded?"
             (some-> d
                     dragon/grounded?)
             "scanning?"
             (some-> d
                     dragon/scanning?)
             "in-bite?"
             (some-> d
                     (dragon/in-bite? pos)))
    (println "  breath-on-way"
             (and stand (dragon/breath-on-way? w pos (physics/centre stand) dragon/breath-hold)))
    (println "  toward-dragon" (dragon/toward-dragon w))))

(let [path (first *command-line-args*)
      n (Long/parseLong (or (second *command-line-args*) "3"))
      state (atom {:w (game/init {:host "replay" :port 0 :name "Clj_replay"}) :seen 0})]
  (record/reduce-entries (fn [acc e]
                           (if-not (:event/kind e)
                             acc
                             (let [before (:w @state)
                                   stepped (main/step before e)
                                   after (assoc stepped :bot/effects [])
                                   perch? (some #(= "intent :perch 118" (:effect/message %))
                                                (:bot/effects stepped))]
                               (swap! state assoc :w after)
                               (if perch?
                                 (let [k (:seen (swap! state update :seen inc))]
                                   (if (= k n)
                                     (do (show "BEFORE the tick that started the perch" before)
                                         (show "AFTER" after)
                                         (println "event" (select-keys e [:event/kind :event/now]))
                                         (reduced :done))
                                     acc))
                                 acc))))
                         nil
                         path))
