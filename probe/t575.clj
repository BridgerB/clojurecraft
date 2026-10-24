(require '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.build :as build]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])
(defn out [& xs] (println (str/join " " xs)) (flush))
(defn ask
  [label f]
  (try (out label (pr-str (f))) (catch Exception e (out label "THREW" (ex-message e)))))
(defn bname
  [w c]
  (some-> (terrain/block-at w c)
          blocks/name-of))
(defn diag
  [w label]
  (let [feet (intent/feet w)
        i (:plan/intent w)
        op (:intent/op i)]
    (out " DIAG" label
         "t" (:time/now w)
         "pos" (pr-str (:player/pos w))
         "feet" (pr-str feet)
         "ground" (pr-str (:player/on-ground? w)))
    (ask "  intent"
         #(select-keys i
                       [:intent/kind :intent/target :intent/move :intent/from :intent/taken
                        :intent/status :intent/reason]))
    (ask "  op" #(pr-str op))
    (ask "  under-feet" #(bname w (update feet 1 dec)))
    (ask "  standable-feet" #(path/standable? w feet))
    (ask "  centred-feet" #(intent/centred? w feet))
    (ask "  classify-under" #(path/classify w (update feet 1 dec)))
    (when op
      (ask "  op-child" #(build/op-child w i op))
      (ask "  approach" #(build/approach-stand w i op)))
    (ask "  next-move"
         #(fortress/next-move w
                              feet
                              (:intent/target i)
                              fortress/climb-past
                              false
                              #{}
                              (boolean (:intent/bridge? i))))
    (doseq [y [(inc (second feet)) (second feet) (dec (second feet))]]
      (out "  y"
           y
           (str/join (for [z (range (- (nth feet 2) 6) (+ (nth feet 2) 7))]
                       (str/join (for [x (range (- (first feet) 6) (+ (first feet) 7))]
                                   (let [c [x y z]]
                                     (cond (= c feet) "@"
                                           (= :solid (path/classify w c)) "#"
                                           (= :clear (path/classify w c)) "."
                                           :else "~"))))))))))
(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
          m (simrace/moment-of snap w0)
          world (:world m)
          prev (atom nil)]
      (out "==" p (pr-str (:snapshot/signature snap)))
      (let [{w :world}
            (sim/play-from
             simrace/step
             m
             (fn [w]
               (let [i (:plan/intent w)
                     s [(:intent/kind i) (:intent/move i) (:intent/op i)
                        (select-keys (:intent/child i)
                                     [:intent/kind :intent/status :intent/reason :intent/target])
                        (:intent/status i) (:intent/reason i) (:plan/status w)]]
                 (when (not= s @prev)
                   (reset! prev s)
                   (out " t"
                        (:time/now w)
                        "feet"
                        (pr-str (intent/feet w))
                        "pos"
                        (pr-str (:player/pos w))
                        (pr-str s))
                   (when (and (= :failed (:intent/status i)) (= :tunnel-to (:intent/kind i)))
                     (diag w "tunnel-failed")))
                 (or (plan/done? w) (plan/failed? w))))
             (simrace/window-of snap)
             (simrace/go-of w0 (:snapshot/until snap))
             nil)]
        (out " END status" (:plan/status w) "reason" (:plan/reason w))
        (diag w "end")
        (doseq [s (simrace/story w (:time/now world))] (out "  STORY" (pr-str s)))))
    (catch Exception e (out "==" p "THREW" (.getName (class e)) (ex-message e)))))
(shutdown-agents)
