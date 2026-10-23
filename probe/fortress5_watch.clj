(ns probe.fortress5-watch
  "Probe: play snapshots as judged does, printing every PERIOD ms (first argument) the feet, the
   tunnel's target, move, op and child, every game/say line, and, when a tunnel chooses a move
   that does not climb toward a target more than 3 above, why each climbing move was refused."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn nm
  [world c]
  (some-> (terrain/block-at world c)
          blocks/name-of
          name))

(defn why
  "A line per climbing move from feet: its verdict and what refuses it."
  [world feet target]
  (for [dx [-1 0 1]
        dz [-1 0 1]
        :when (not= 0 dx dz)
        :when (or (zero? dx) (zero? dz))
        :let [move [dx 1 dz]
              cells (fortress/opens feet move)
              floor (fortress/plus (fortress/plus feet move) [0 -1 0])]]
    (str "  WHY " move
         " safe " (fortress/safe? world feet move)
         " lava-beside " (count (fortress/lava-beside world feet move))
         " sealed " (fortress/sealed? world feet move)
         " cells " (pr-str (mapv #(nm world %) cells))
         " floor " (nm world floor))))

(defn run!*
  [period path]
  (println "== " path)
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        last-say (volatile! nil)
        last-t (volatile! 0)
        last-move (volatile! nil)
        n (volatile! 0)
        step (fn [world ev]
               (let [w' (sr/step world ev)
                     t (:time/now w')
                     i (:plan/intent w')
                     c (:intent/child i)]
                 (doseq [e (:bot/effects w')
                         :when (and (= :log (:effect/kind e))
                                    (< @n 300)
                                    (not (str/starts-with? (str (:effect/message e)) "digging"))
                                    (not= @last-say (:effect/message e)))]
                   (vreset! last-say (:effect/message e))
                   (vswap! n inc)
                   (println "SAY" t (:effect/message e)))
                 (when (and (= :tunnel-to (:intent/kind i))
                            (:intent/move i)
                            (not= [(:intent/from i) (:intent/move i)] @last-move)
                            (< @n 300))
                   (vreset! last-move [(:intent/from i) (:intent/move i)])
                   (let [feet (:intent/from i)
                         tgt (:intent/target i)]
                     (when (and feet
                                tgt
                                (> (- (second tgt) (second feet)) 3)
                                (<= (second (:intent/move i)) 0))
                       (vswap! n inc)
                       (println "CHOSE" t feet (:intent/move i) "target" tgt)
                       (run! println (why w' feet tgt)))))
                 (when (and (< @n 300) (> (- t @last-t) period))
                   (vreset! last-t t)
                   (vswap! n inc)
                   (println "INT"
                            t
                            (mapv #(math/round (double %)) (:player/pos w'))
                            (pr-str (select-keys i
                                                 [:intent/target :intent/move :intent/taken
                                                  :intent/bridge? :intent/reason :intent/status]))
                            (pr-str (:build/step (:intent/op i)))
                            (pr-str (select-keys c
                                                 [:intent/kind :intent/status :intent/reason
                                                  :intent/target :intent/for :intent/item]))
                            (pr-str (select-keys (:plan/last w') [:intent/status :intent/reason]))
                            "health"
                            (:player/health w')))
                 w'))
        stage (sr/stage-of snap)
        stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))]
    (sim/play-from step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (println "== end")))

(let [[period & paths] *command-line-args*] (doseq [p paths] (run!* (Long/parseLong period) p)))
(shutdown-agents)
