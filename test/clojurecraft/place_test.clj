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
            [clojurecraft.terrain :as terrain]
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
  ;; a placement the server answers with the cell still empty is a body standing in it (issue
  ;; #106, PR #114): the spot is given up for another, up to place/max-refusals; refused with a
  ;; block in the cell, or once the refusals are spent, it fails :rejected
  (let [sent (fn [refused]
               (-> (standing [5.5 64.0 5.5])
                   (assoc :plan/status :active
                          :plan/goals #{:pickaxe}
                          :bot/sequence 7
                          :plan/intent
                          (cond-> {:intent/kind :place
                                   :intent/item :crafting_table
                                   :intent/status :active
                                   :intent/stage :sent
                                   :intent/target [7 64 5]
                                   :intent/sequence 7
                                   :intent/sent-at 0}
                            (seq refused) (assoc :intent/refused refused)))))
        answer (fn [w state]
                 (first (fx/fold intent-step
                                 w
                                 [(packet {:packet/name :block-update :pos [7 64 5] :state state})
                                  (packet {:packet/name :block-changed-ack :sequence 7})
                                  {:event/kind :tick :event/now 600 :event/rand 0.5}])))
        empty-once (:plan/intent (answer (sent #{}) 0))
        spent (set (for [x (range place/max-refusals)] [x 70 0]))
        empty-spent (:plan/intent (answer (sent spent) 0))
        stone (:plan/intent (answer (sent #{}) 1))]
    (is (not (intent/failed? empty-once)) "an empty answer is not believed a placement")
    (is (contains? (:intent/refused empty-once) [7 64 5]) "the cell is refused")
    (is (not= :sent (:intent/stage empty-once)) "and another spot is sought")
    (is (intent/failed? empty-spent))
    (is (= :rejected (:intent/reason empty-spent)) "the refusals spent, it fails")
    (is (intent/failed? stone))
    (is (= :rejected (:intent/reason stone)) "a block put back fails at once")
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

(deftest a-cell-refused-with-nothing-in-it-is-given-up-for-another
  ;; race 38515209959 runner 9 bot Clj_r17 (issue #106): bot Clj_r24, landed at the same cell,
  ;; walked through the furnace's spot; the server refuses a block into a body and answers with
  ;; the cell still air, and the same spot was sent three times in two seconds, :rejected
  (let [sent (fn [refused]
               (-> (standing [5.5 64.0 5.5])
                   (assoc :bot/sequence 7
                          :plan/intent
                          (cond-> {:intent/kind :place
                                   :intent/item :furnace
                                   :intent/status :active
                                   :intent/stage :sent
                                   :intent/target [7 64 5]
                                   :intent/against [7 63 5]
                                   :intent/sequence 7
                                   :intent/sent-at 0}
                            refused (assoc :intent/refused refused)))))
        answer [(packet {:packet/name :block-update :pos [7 64 5] :state 0})
                (packet {:packet/name :block-changed-ack :sequence 7})
                {:event/kind :tick :event/now 600 :event/rand 0.5}]
        [w _] (fx/fold intent-step (sent nil) answer)
        i (:plan/intent w)]
    (is (= :spot (:intent/stage i)) "the spot is sought again")
    (is (= #{[7 64 5]} (:intent/refused i)) "without that cell")
    (let [[w _] (fx/fold intent-step w [{:event/kind :tick :event/now 650 :event/rand 0.5}])
          i (:plan/intent w)]
      (is (= :settle (:intent/stage i)))
      (is (not= [7 64 5] (:intent/target i)) "another spot"))
    (let [[first-spot _] (place/spot (standing [5.5 64.0 5.5]))]
      (is (not= first-spot (first (place/spot (standing [5.5 64.0 5.5]) false #{first-spot})))
          "spot passes a refused cell over"))
    (testing "after max-refusals such spots the placement fails: something stands at every one"
      (let [[w _] (fx/fold intent-step (sent #{[6 64 5] [7 64 6] [6 64 6]}) answer)]
        (is (intent/failed? (:plan/intent w)))
        (is (= :rejected (:intent/reason (:plan/intent w))))))
    (testing "a block in the cell, the niche's stone put back, is still :rejected at once"
      (let [[w _] (fx/fold intent-step
                           (sent nil)
                           [(packet {:packet/name :block-update :pos [7 64 5] :state 1})
                            (packet {:packet/name :block-changed-ack :sequence 7})
                            {:event/kind :tick :event/now 600 :event/rand 0.5}])]
        (is (= :rejected (:intent/reason (:plan/intent w))))))))

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

(deftest no-niche-under-gravel
  ;; race 38477665442 bot Clj_r14, at y 16 for the ingots: the gravel over each side niche fell
  ;; into it as soon as it was dug, seven digs in turn, then the table went in as the gravel
  ;; came down and was refused three times
  (let [gravel (blocks/default-state :gravel)
        rock (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 2 9) y [64 65]] [[x y 8] 0]))
                    {[8 65 9] gravel [8 65 7] gravel})
        at (fn [rock]
             (place/niche (assoc (standing [8.5 64.0 8.5])
                                 :world/chunks {[0 0] (world/column rock)}
                                 :plan/go-at [2.5 64.0 8.0])))]
    (is (= [9 64 8] (at rock)) "gravel over both side walls: only the wall ahead is left")
    (is (= [8 64 9] (at (assoc rock [8 65 9] 1))) "stone over one side: that side")))

(deftest a-niche-beside-the-head-when-the-side-walls-border-water
  ;; race 38477665442 bot Clj_r12, at the end of its tunnel at y 26 for the ingots: water
  ;; behind both side walls at the feet's height, the table in the third wall, the furnace
  ;; found no spot, :no-spot three times. The walls at head height are dry.
  (let [water (blocks/default-state :water)
        rock (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 2 9) y [64 65]] [[x y 8] 0]))
                    (into {} (for [x (range 7 10)] [[x 64 10] water]))
                    (into {} (for [x (range 7 10)] [[x 64 6] water]))
                    {[9 64 8] (blocks/default-state :crafting_table)})
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column rock)}
                 :plan/go-at [2.5 64.0 8.0])]
    (is (#{[8 65 9] [8 65 7]} (place/niche w)) "a side wall at head height")))

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

(deftest with-nowhere-to-put-the-furnace-it-goes-back-to-fresh-rock
  ;; sim races 38660819204, 38662908438 and 38665691522 (issue #94): at the dead end of an iron
  ;; tunnel by an aquifer every wall beside the feet touched water, the passage was behind, and
  ;; the furnace failed :no-spot three times there
  (let [water (blocks/default-state :water)
        table (blocks/default-state :crafting_table)
        rock (merge (into {} (for [x (range 16) y (range 64 74) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 1 16) y [64 65]] [[x y 8] 0]))
                    (into {} (for [x (range 0 3) y [64 65] z [6 10]] [[x y z] water]))
                    {[0 64 8] table [0 66 8] water})
        w (assoc (standing [1.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column rock)}
                 :player/inventory
                 {0 {:item (blocks/items :wooden_pickaxe) :count 1}
                  1 {:item (blocks/items :furnace) :count 1}})
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        spot (fn [w i]
               (let [i (merge {:intent/kind :place
                               :intent/item :furnace
                               :intent/status :active
                               :intent/stage :spot}
                              i)]
                 (:plan/intent (intent/run (assoc w :plan/intent i) i tick))))
        first-time (spot w {})]
    (is (= :retreat (:intent/stage first-time)))
    (is (= {:intent/kind :walk :intent/for :retreat}
           (select-keys (:intent/child first-time) [:intent/kind :intent/for]))
        "back along the tunnel to fresh rock")
    (is (>= (physics/distance (:intent/target (:intent/child first-time)) [1 64 8])
            place/fresh-rock-distance))
    (is (= :no-spot (:intent/reason (spot w {:intent/retreated? true}))) "once only")
    (is (= :niche (:intent/stage (spot (assoc w :player/pos [12.5 64.0 8.5]) {})))
        "where the rock is dry a niche is cut")))

(deftest sealed-in-sand-with-nowhere-it-climbs
  ;; sim race 38659291345 world R1/1 (issue #148): sand had fallen round the bot at y 59 under
  ;; a desert, every cell beside it sand or sandstone under sand, no route anywhere, and the
  ;; place failed :no-spot three times
  (let [sand (blocks/default-state :sand)
        rock (merge (into {} (for [x (range 16) y (range 64 74) z (range 16)] [[x y z] sand]))
                    {[8 64 8] 0 [8 65 8] 0})
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column rock)}
                 :player/inventory {0 {:item (blocks/items :furnace) :count 1}})
        i {:intent/kind :place :intent/item :furnace :intent/status :active :intent/stage :spot}
        placed (:plan/intent (intent/run (assoc w :plan/intent i)
                                         i
                                         {:event/kind :tick :event/now 0 :event/rand 0.5}))]
    (is (= :retreat (:intent/stage placed)))
    (is (= {:intent/kind :stairs-up :intent/to-y (+ 64 place/fresh-rock-climb) :intent/for :retreat}
           (select-keys (:intent/child placed) [:intent/kind :intent/to-y :intent/for]))
        "no route away: stairs up out of the sand")))

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

(deftest a-guess-the-server-never-answers-is-forgotten
  ;; race 38477665442 bot Clj_r19: the stone under its next stair, dug and acked, was never
  ;; broken by the server; the bot's physics fell through its guessed air while the server
  ;; put it back on the stone, and the next dig waited to be still until the run timed out
  (let [w (-> (standing [8.5 64.0 8.5])
              (assoc :time/now 1000)
              (terrain/predict [9 63 8] blocks/air))]
    (is (= blocks/air (terrain/block-at w [9 63 8])) "the guess is in view")
    (is (not (terrain/confirmed? w [9 63 8])))
    (testing "the server's word confirms it"
      (let [w (terrain/set-block w [9 63 8] blocks/air)]
        (is (terrain/confirmed? w [9 63 8]))
        (is (= blocks/air (terrain/block-at w [9 63 8])))))
    (testing "silence past unconfirmed-ms drops it"
      (let [w (terrain/forget-stale w (+ 1000 terrain/unconfirmed-ms 1))]
        (is (terrain/confirmed? w [9 63 8]))
        (is (= 1 (terrain/block-at w [9 63 8])) "the chunk's stone is back in view")))
    (testing "a guess within the time stays"
      (let [w (terrain/forget-stale w (+ 1000 terrain/unconfirmed-ms))]
        (is (not (terrain/confirmed? w [9 63 8])))))))
