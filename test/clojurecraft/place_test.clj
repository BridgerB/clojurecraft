(ns clojurecraft.place-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn standing
  [pos]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
      (assoc :world/chunks {[0 0] (world/column {})})))

(defn overlaps?
  [[px py pz] [x y z]]
  (and (< (- px 0.3) (inc x))
       (> (+ px 0.3) x)
       (< py (inc y))
       (> (+ py 1.8) y)
       (< (- pz 0.3) (inc z))
       (> (+ pz 0.3) z)))

(deftest a-placement-spot-is-never-inside-the-player
  (let [r (tc/quick-check 300
                          (prop/for-all
                           [x (gen/double* {:min 2.0 :max 13.9 :NaN? false :infinite? false}) z
                            (gen/double* {:min 2.0 :max 13.9 :NaN? false :infinite? false})]
                           (let [w (standing [x 64.0 z])
                                 [target support] (place/spot w)]
                             (and target
                                  (not (overlaps? (:player/pos w) target))
                                  (= support (update target 1 dec))
                                  (<= (abs (- (second target) 64)) 1)
                                  (<= (physics/distance (game/eye w) (physics/centre target))
                                      place/place-reach)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(defn intent-step
  "The world reducer plus one run of the current intent per tick (no planner)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (:plan/intent w)) (intent/run w (:plan/intent w) e) w)))

(deftest a-spot-is-found-on-an-uneven-floor
  (testing "every feet-level cell around is walled: place on top of the nearest wall, one up"
    (let [walls (into {}
                      (for [x (range 3 10) z (range 3 10) :when (not= [x z] [6 6])] [[x 64 z] 1]))
          w (assoc (standing [6.5 64.0 6.5]) :world/chunks {[0 0] (world/column walls)})]
      (is (= [[5 65 5] [5 64 5]] (place/spot w))))))

(deftest a-rejected-placement-fails-instead-of-believing
  (let [w (-> (standing [5.5 64.0 5.5])
              (assoc :plan/status :active
                     :plan/goals #{:pickaxe}
                     :bot/sequence 7
                     :plan/intent
                     {:intent/kind :place
                      :intent/item :crafting_table
                      :intent/status :active
                      :intent/stage :sent
                      :intent/target [7 64 5]
                      :intent/sequence 7
                      :intent/sent-at 0}))
        [w _] (fx/fold intent-step
                       w
                       [(packet {:packet/name :block-update :pos [7 64 5] :state 0})
                        (packet {:packet/name :block-changed-ack :sequence 7})
                        {:event/kind :tick :event/now 600 :event/rand 0.5}])]
    (is (intent/failed? (:plan/intent w)))
    (is (= :rejected (:intent/reason (:plan/intent w))))
    (testing "a placement the server confirms is done"
      (let [w (-> (standing [5.5 64.0 5.5])
                  (assoc :plan/intent
                         {:intent/kind :place
                          :intent/item :crafting_table
                          :intent/status :active
                          :intent/stage :sent
                          :intent/target [7 64 5]
                          :intent/sequence 7
                          :intent/sent-at 0}))
            [w _] (fx/fold
                   intent-step
                   w
                   [(packet {:packet/name :block-update :pos [7 64 5] :state memory/crafting-table})
                    {:event/kind :tick :event/now 100 :event/rand 0.5}])]
        (is (intent/done? (:plan/intent w)))
        (is (= memory/crafting-table (memory/remembered w [7 64 5])) "remembered")))))

(deftest a-niche-is-cut-in-the-side-wall-not-ahead
  ;; batch iron-a2 run 1: at the end of a tunnel east of its landing the bot dug its niche in the
  ;; wall ahead, put the table there, and the tunnel went on through it
  (let [rock (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 2 9) y [64 65]] [[x y 8] 0])))
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column rock)}
                 :plan/go-at [2.5 64.0 8.0])]
    (is (not= [9 64 8] (place/niche w)) "not the wall ahead, away from the landing")
    (is (#{[8 64 9] [8 64 7]} (place/niche w)) "a side wall")))

(deftest no-niche-where-the-tools-cannot-drop
  ;; gym batch iron-a3 run 5 failed placing its furnace :needs-tool three times: its niche was a
  ;; block the held tools cannot drop
  (let [iron (blocks/default-state :iron_ore)
        rock (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] iron]))
                    (into {} (for [x (range 2 9) y [64 65]] [[x y 8] 0])))
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column rock)}
                 :player/inventory {0 {:item (blocks/items :wooden_pickaxe) :count 1}})]
    (is (nil? (place/niche w)) "iron ore all round, and only a wooden pickaxe")))

(deftest a-block-is-never-placed-on-a-table
  ;; gym batch iron-a5 run 4: the only spot was on its crafting table, and a right-click on a
  ;; table opens it, so the gravel was never placed and the intent failed :rejected three times
  (let [walls
        (into {}
              (for [dx [-1 0 1] dz [-1 0 1] :when (not= [dx dz] [0 0])] [[(+ 8 dx) 64 (+ 8 dz)] 1]))
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks
                 {[0 0] (world/column
                         (assoc walls [7 64 7] (blocks/default-state :crafting_table)))})]
    (is (= [[7 65 8] [7 64 8]] (place/spot w)) "on the wall beside the table, not on the table")))

(deftest a-block-dug-again-at-once-may-go-in-a-passage
  ;; gym batch iron-a6 run 1: at the foot of its stairs, between its table and furnace, the only
  ;; spot for the gravel it meant to dig again for flint was the stair behind it
  (let [rock (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
                    {[8 64 8] 0 [8 65 8] 0 [7 65 8] 0 [7 66 8] 0}
                    {[9 64 8] (blocks/default-state :furnace)
                     [8 64 9] (blocks/default-state :crafting_table)})
        w (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column rock)})]
    (is (nil? (place/spot w)) "the stair behind is a passage: no block stays there")
    (is (= [[7 65 8] [7 64 8]] (place/spot w true)) "but one dug again at once may")))

(deftest a-table-may-go-in-the-passage-a-furnace-not
  ;; gym batch solid-b1 run 6 broke its pickaxe on the stairs down, in a stairwell one wide: no
  ;; spot but the stair, no niche without a pickaxe, and the table failed :no-spot three times. The
  ;; table is taken along when the bot goes on, so the stair is free again; a furnace stays
  (let [row (fn [id] (first (filter #(= id (:goal/id %)) plan/goals)))]
    (is (:intent/passage-ok? (plan/act {} (row :place-table))))
    (is (not (:intent/passage-ok? (plan/act {} (row :place-furnace)))))))

(deftest a-spot-is-never-a-flower
  ;; race 38477665442 bot Clj_r23: the nearest cell held wildflowers, which the server's
  ;; replaceable tag does not list, and the table was refused three times, :rejected
  (let [flowers (into {}
                      (for [dx [-1 0 1]
                            dz [-1 0 1]
                            :when (not= [dx dz] [0 0])]
                        [[(+ 8 dx) 64 (+ 8 dz)] (blocks/default-state :wildflowers)]))
        w (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column flowers)})
        [target] (place/spot w)]
    (is (= 2 (max (abs (- (first target) 8)) (abs (- (last target) 8)))) "the second ring")
    (is (= 64 (second target)))
    (testing "short grass is replaceable: a spot"
      (let [grass (update-vals flowers (constantly (blocks/default-state :short_grass)))
            w (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column grass)})]
        (is (= 1
               (max (abs (- (ffirst (place/spot w)) 8))
                    (abs (- (last (first (place/spot w))) 8)))))))))
