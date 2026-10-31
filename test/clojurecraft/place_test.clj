(ns clojurecraft.place-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
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

(deftest a-niche-is-cut-where-powder-snow-lies-over-it-and-is-the-spot-once-dug
  ;; race 38998997695 bot Clj_r12 (issue #94): after smelting it stood in a one-cell pocket, the
  ;; rock round its feet under powder snow, and the table found no spot three times: the niche
  ;; wanted solid over it, and the cell dug out is a place the feet could stand in (a passage), which
  ;; spot refuses
  (let [powder (blocks/default-state :powder_snow)
        pocket {[8 64 8] 0 [8 65 8] 0}
        rock
        (merge
         (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
         (into
          {}
          (for [x (range 16) y [65 66] z (range 16) :when (not= [x z] [8 8])] [[x y z] powder]))
         pocket)
        world-of (fn [rock]
                   (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column rock)}))
        w (world-of rock)
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        spot (fn [w i]
               (let [i (merge {:intent/kind :place
                               :intent/item :crafting_table
                               :intent/status :active
                               :intent/stage :spot}
                              i)]
                 (:plan/intent (intent/run (assoc w :plan/intent i) i tick))))
        cut (place/niche w)]
    (is (#{[9 64 8] [7 64 8] [8 64 9] [8 64 7]} cut)
        "the stone beside the feet, powder snow over it")
    (is (nil? (place/spot w)) "no air to place into")
    (let [started (spot w {})]
      (is (= :niche (:intent/stage started)))
      (is (= cut (:intent/niche started)) "the cell is kept for after the dig"))
    (let [dug (world-of (assoc rock cut 0))
          settled (spot dug {:intent/niche cut})]
      (is (nil? (place/spot dug)) "the dug cell is a passage to spot")
      (is (= [cut (update cut 1 dec)] (place/dug-spot dug cut #{})))
      (is (= :settle (:intent/stage settled)))
      (is (= cut (:intent/target settled)) "the block goes into the niche that was cut")
      (is (nil? (place/dug-spot w cut #{})) "not before it is dug out")
      (is (nil? (place/dug-spot dug cut #{cut})) "nor where the server refused one"))))

(deftest powder-snow-beside-the-feet-is-dug-for-the-niche
  ;; race 39031393203 bot Clj_r12 (issue #94): entombed in a powder-snow field, every cell beside
  ;; its feet and head powder snow, which no block is placed into; the hand breaks it
  (let [powder (blocks/default-state :powder_snow)
        rock (merge (into {} (for [x (range 16) y (range 60 64) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] powder]))
                    {[8 64 8] 0 [8 65 8] 0})
        w (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column rock)})]
    (is (nil? (place/spot w)) "no air round the feet")
    (is (#{[9 64 8] [7 64 8] [8 64 9] [8 64 7]} (place/niche w)) "a powder-snow cell on stone")))

(deftest snow-or-leaves-over-the-ground-is-dug-clear-for-a-spot
  ;; race 40028266922 bot Clj_r04 (issue #68): snow-covered grass, every cell round the
  ;; feet snow or grass, no spot and no wall for a niche; race 40029764849 bot Clj_r03
  ;; (issue #574): a birch crown, every cell leaves or air over air, and the furnace
  ;; found no spot. The cover over solid ground is dug clear and placed into.
  (let [snow (blocks/default-state :snow)
        ground (merge
                (into {} (for [x (range 16) y (range 60 64) z (range 16)] [[x y z] 1]))
                (into {} (for [x (range 16) z (range 16) :when (not= [x z] [8 8])] [[x 64 z] snow]))
                {[8 64 8] 0 [8 65 8] 0})
        world-of (fn [g] (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column g)}))
        w (world-of ground)
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        spot (fn [w i]
               (let [i (merge {:intent/kind :place
                               :intent/item :crafting_table
                               :intent/status :active
                               :intent/stage :spot}
                              i)]
                 (:plan/intent (intent/run (assoc w :plan/intent i) i tick))))
        cut (place/covered-spot w #{})]
    (is (nil? (place/spot w)) "snow is no spot")
    (is (nil? (place/niche w)) "and no wall to cut")
    (is (some? cut) "but the snow over the ground is dug clear")
    (is (= snow (terrain/block-at w cut)))
    (let [started (spot w {})]
      (is (= :niche (:intent/stage started)))
      (is (= cut (:intent/niche started)) "the cell is kept for after the dig"))
    (let [dug (world-of (assoc ground cut 0))
          settled (spot dug {:intent/niche cut})]
      (is (= :settle (:intent/stage settled)))
      (is (= cut (:intent/target settled)) "the block goes where the snow was")))
  (let [leaves (blocks/default-state :birch_leaves)
        crown
        (merge
         (into {} (for [x (range 16) y (range 60 64) z (range 16)] [[x y z] 1]))
         (into
          {}
          (for [x (range 16) y [64 65 66] z (range 16) :when (not= [x z] [8 8])] [[x y z] leaves]))
         {[8 64 8] 0 [8 65 8] 0 [8 66 8] 0})
        w (assoc (standing [8.5 64.0 8.5]) :world/chunks {[0 0] (world/column crown)})
        cut (place/covered-spot w #{})]
    (is (some? cut) "leaves over leaves are dug clear on a crown")
    (is (blocks/leaves? (terrain/block-at w cut)))))

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

(deftest a-retreat-that-fails-afloat-swims-out
  ;; race 39551467157 (issue #450, world R3/34): a climb out of a pocket under the sea came up
  ;; into the open, the place found nowhere and its retreat, stairs up, was :boxed in the water
  ;; it had fallen into: :no-spot, and the plan with it, fifteen blocks from any bank
  (let [w (assoc (standing [8.5 64.0 8.5]) :player/in-water? true)
        i {:intent/kind :place
           :intent/item :furnace
           :intent/status :active
           :intent/stage :retreat
           :intent/retreated? true
           :intent/child {:intent/kind :stairs-up
                          :intent/for :retreat
                          :intent/status :failed
                          :intent/reason :boxed}}
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        afloat (:plan/intent (intent/run (assoc w :plan/intent i) i tick))
        dry (:plan/intent (intent/run (assoc w :plan/intent i :player/in-water? false) i tick))]
    (is (= {:intent/kind :swim-out :intent/for :retreat}
           (select-keys (:intent/child afloat) [:intent/kind :intent/for]))
        "afloat: a swim out to a bank")
    (is (= :retreat (:intent/stage afloat)) "the place goes on from the bank")
    (is
     (= :equip (:intent/stage dry))
     "on dry land the failed retreat looks for a spot again (issue #148: walk-off never fails the place)")
    (testing "a swim out that fails is not swum again"
      (let [i (assoc-in i [:intent/child :intent/kind] :swim-out)
            failed (:plan/intent (intent/run (assoc w :plan/intent i) i tick))]
        (is (= :equip (:intent/stage failed)))
        (is (nil? (:intent/child failed)))))))

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

(deftest a-swap-into-the-hand-closes-an-open-furnace-first
  ;; race 38805920624 runner 5 (issues #131, #132): 20 bots whose smelt failed :not-cooked left
  ;; the furnace open; the server ignores a window-0 click while a container is its open menu,
  ;; and every swap into the hand after it failed :stale-window until the plan did
  (let [w (-> (standing [5.5 64.0 5.5])
              (assoc :window/state-id 5
                     :player/held-slot 0
                     :player/inventory {9 {:item (recipe/item-id :stone_pickaxe) :count 1}}
                     :window/open
                     {:window/id 11
                      :window/menu-type (:menu-type (inventory/layouts :furnace))
                      :window/state-id 3
                      :window/slots {}}
                     :plan/intent
                     {:intent/kind :equip :intent/item :stone_pickaxe :intent/status :active}))
        windowed (fn [fx]
                   (vec (for [p (fx/packets fx)
                              :when (#{:container-close :container-click} (:packet/name p))]
                          [(:packet/name p) (:window-id p)])))
        [w fx] (fx/fold intent-step w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= [[:container-close 11]] (windowed fx)) "the furnace is closed first, nothing clicked")
    (is (nil? (:window/open w)))
    (let [[w fx] (fx/fold intent-step w [{:event/kind :tick :event/now 100 :event/rand 0.5}])]
      (is (= [[:container-click 0]] (windowed fx)) "then the swap goes to window 0")
      (is (= :active (:intent/status (:plan/intent w)))))))

(deftest a-given-cell-the-body-clips-is-stepped-off-before-it-is-placed-into
  ;; race 39095700271 runner 3 bot Clj_r04 (issue #430): the stairs' seal into the water beside
  ;; the feet was sent from x 2838.74, the body over the cell's edge, and refused three times
  (let [floor (into {} (for [x (range 3 10) z (range 3 10)] [[x 63 z] 1]))
        at (fn [x nudges]
             (-> (standing [x 64.0 5.5])
                 (assoc :world/chunks {[0 0] (world/column floor)})
                 (assoc :plan/intent
                        (cond-> {:intent/kind :place
                                 :intent/item :cobblestone
                                 :intent/status :active
                                 :intent/stage :spot
                                 :intent/cell [6 64 5]}
                          nudges (assoc :intent/nudges nudges)))))
        run (fn [w]
              (first (fx/fold intent-step w [{:event/kind :tick :event/now 0 :event/rand 0.5}])))]
    (is (place/inside-player? (at 5.74 nil) [6 64 5]))
    (let [w (run (at 5.74 nil))]
      (is (= :spot (:intent/stage (:plan/intent w))))
      (is (= 1 (:intent/nudges (:plan/intent w))))
      (is (:control/forward? (:player/controls w)) "toward the middle of its own cell"))
    (is (= :settle (:intent/stage (:plan/intent (run (at 5.5 nil)))))
        "clear of the cell: placed as before")
    (is (= :settle (:intent/stage (:plan/intent (run (at 5.74 place/max-nudges)))))
        "after max-nudges the server decides")
    (is (= :spot
           (:intent/stage (:plan/intent (run (update (at 5.74 nil)
                                                     :plan/intent
                                                     merge
                                                     {:intent/stage :settle
                                                      :intent/target [6 64 5]
                                                      :intent/against [6 63 5]
                                                      :intent/since 0
                                                      :intent/still 0})))))
        "a body drifted onto the edge while settling goes back to the spot stage")))

(deftest on-a-pillar-in-the-sea-the-block-goes-into-the-water-beside-it
  ;; sim race 39601680523 world R1/10 (issue #148): the bot stood on the one cobblestone it had
  ;; pillared up in open water, water on every side of it, and its table failed :no-spot three
  ;; times, a pickaxe short of the bucket it held for the water all round it
  (let [water (blocks/default-state :water)
        sea (merge (into {} (for [x (range 3 14) z (range 3 14)] [[x 63 z] water])) {[8 63 8] 1})
        w (assoc (standing [8.5 64.0 8.5])
                 :world/chunks {[0 0] (world/column sea)}
                 :player/inventory {0 {:item (blocks/items :crafting_table) :count 1}})
        run (fn [i]
              (let [i (merge {:intent/kind :place
                              :intent/item :crafting_table
                              :intent/status :active
                              :intent/stage :spot}
                             i)]
                (:plan/intent (intent/run (assoc w :plan/intent i)
                                          i
                                          {:event/kind :tick :event/now 0 :event/rand 0.5}))))
        wet (run {:intent/retreated? true})]
    (is (nil? (place/spot w)) "no dry spot: the pillar is the only support, under the feet")
    (is (= :settle (:intent/stage wet)))
    (is (blocks/solid? (terrain/block-at w (:intent/against wet))) "clicked against a solid block")
    (is (= water (terrain/block-at w (:intent/cell wet))) "into a water cell")
    (is (not (place/inside-player? w (:intent/cell wet))))
    (is (= :retreat (:intent/stage (run {}))) "fresh rock is tried first")))

(deftest a-given-cell-holding-powder-snow-is-dug-before-it-is-placed-into
  ;; real race 39807290314 bot Clj_r18 (issue #279): the portal's first floor cell held powder
  ;; snow, which no block is placed into; the cobblestone was refused three times, :rejected
  (let [powder (blocks/default-state :powder_snow)
        floor (into {} (for [x (range 3 10) z (range 3 10)] [[x 63 z] 1]))
        at (fn [cell-state]
             (-> (standing [4.5 64.0 5.5])
                 (assoc :world/chunks {[0 0] (world/column (assoc floor [6 64 5] cell-state))})
                 (assoc :plan/intent
                        {:intent/kind :place
                         :intent/item :cobblestone
                         :intent/status :active
                         :intent/stage :spot
                         :intent/cell [6 64 5]})))
        run (fn [w]
              (first (fx/fold intent-step w [{:event/kind :tick :event/now 0 :event/rand 0.5}])))]
    (is (place/unplaceable-in? (at powder) [6 64 5]))
    (is (not (place/unplaceable-in? (at 0) [6 64 5])) "air takes a block")
    (is (not (place/unplaceable-in? (at 1) [6 64 5])) "stone is the build's own done")
    (let [i (:plan/intent (run (at powder)))]
      (is (= :niche (:intent/stage i)))
      (is (= {:intent/kind :dig :intent/target [6 64 5]}
             (select-keys (:intent/child i) [:intent/kind :intent/target]))
          "the hand breaks it first"))
    (is (= :settle (:intent/stage (:plan/intent (run (at 0))))) "air: placed as before")))

(deftest no-spot-beside-a-drop
  ;; issue #338, sim world R2/12: a table placed on a pillar over a cavern, dug to carry on, and
  ;; the bot walked off after the drop
  (let [air (blocks/default-state :air)
        stone (blocks/default-state :stone)
        cliff (into {[0 64 0] air [0 63 0] stone} (for [y (range 40 65)] [[1 y 0] air]))
        ledge (assoc cliff [1 63 0] stone)
        w #(assoc (standing [0.5 64.0 0.5]) :world/blocks %)]
    (is (place/drop-beside? (w cliff) [0 64 0]) "air east with nothing under it for twenty blocks")
    (is (not (place/drop-beside? (w ledge) [0 64 0])) "a floor beside it")))

(deftest a-spot-along-a-bridge
  ;; issues #559, #560: a one-block bridge over a drop, every cell of it beside a drop. The plain
  ;; spot refuses all; the fallback takes the cell ahead while the floor goes on past it, and
  ;; refuses it where the bridge ends
  (let [air (blocks/default-state :air)
        stone (blocks/default-state :stone)
        sky (fn [zs] (into {} (for [x (range -4 5) y (range 50 66) z zs] [[x y z] air])))
        bridge (fn [zs] (into (sky (range -3 4)) (for [z zs] [[0 63 z] stone])))
        w #(assoc (standing [0.5 64.0 0.5]) :world/blocks %)
        long-bridge (w (bridge (range -3 4)))
        short-bridge (w (bridge (range -3 2)))]
    (is (nil? (place/spot long-bridge)) "a drop beside every cell: no plain spot")
    (is (not (place/drop-beyond? long-bridge [0 64 0] [0 64 1]))
        "the floor goes on past the cell ahead")
    (is (some? (place/spot long-bridge false #{} true)) "the fallback finds a cell along the floor")
    (is (place/drop-beyond? short-bridge [0 64 0] [0 64 1]) "the bridge ends past the cell ahead")
    (is (not= [0 64 1] (first (place/spot short-bridge false #{} true)))
        "never the cell at the end of the bridge, where the pickup carries on over the drop")))

(deftest a-meal-swapped-into-the-hand-does-not-push-the-pickaxe-out-of-the-hotbar
  ;; gym chain-w9 run 3 (main, plan run 40851746139): `digging [18 101 -40] ... with pickaxe in 6
  ;; 500ms`, then `intent :eat`, and the recording's slot 11 holds item 934 with damage 69: the meal
  ;; went into the held slot 6 and the pickaxe into the main inventory, where no dig looks; the
  ;; way down a 28-high pillar was dug by hand, ten seconds a block
  (let [pick {:item (recipe/item-id :stone_pickaxe) :count 1}
        beef {:item (recipe/item-id :cooked_beef) :count 4}
        at (fn [inventory held]
             (assoc (standing [5.5 64.0 5.5])
                    :window/state-id 5
                    :player/held-slot held
                    :player/inventory inventory))
        swapped (fn [w]
                  (let [[_ fx] (fx/fold intent-step
                                        (assoc w
                                               :plan/intent
                                               {:intent/kind :equip
                                                :intent/item :cooked_beef
                                                :intent/status :active})
                                        [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
                    (some #(when (= :container-click (:packet/name %)) (:button %))
                          (fx/packets fx))))]
    (is (= 0 (swapped (at {6 pick 11 beef} 6)))
        "the pickaxe held: into the first slot with no tool")
    (is (= 6 (swapped (at {11 beef} 6))) "no tool held: into the held slot as before")
    (is (= 1 (swapped (at {0 pick 6 pick 11 beef} 6)))
        "a tool in slot 0 too: the first hotbar slot with none is 1")))

(deftest a-body-on-the-edge-of-the-cell-it-places-into-does-not-step-to-a-floorless-middle
  ;; pearl-fight-n job 1 (plan run 41229277566, 18:49:11): stairs-up laid a block into the cell
  ;; beside its feet from x/z 2164.8, the body clipping the cell; the nudge walked it to the middle
  ;; of its own cell, which had no floor over a lava lake: three blocks down, burned to death
  (let [floor (into {} (for [x (range 6 10) z (range 3 10)] [[x 63 z] 1]))
        w (-> (standing [5.74 64.0 5.5])
              (assoc :world/chunks {[0 0] (world/column floor)})
              (assoc :plan/intent
                     {:intent/kind :place
                      :intent/item :cobblestone
                      :intent/status :active
                      :intent/stage :spot
                      :intent/cell [6 64 5]}))
        out (first (fx/fold intent-step w [{:event/kind :tick :event/now 0 :event/rand 0.5}]))]
    (is (place/inside-player? w [6 64 5]))
    (is (= :failed (:intent/status (:plan/intent out))))
    (is (= :no-support (:intent/reason (:plan/intent out))))
    (is (not (:control/forward? (:player/controls out))) "no step off the edge")))

(deftest an-airborne-placement-over-no-floor-still-nudges-as-a-pillar-lays-its-block
  ;; the fail :no-support for a floorless feet cell holds on the ground only: a pillar's block is laid
  ;; at the top of a jump with nothing under the feet cell yet
  (let [floor (into {} (for [x (range 6 10) z (range 3 10)] [[x 63 z] 1]))
        w (-> (standing [5.74 64.0 5.5])
              (assoc :world/chunks {[0 0] (world/column floor)} :player/on-ground? false)
              (assoc :plan/intent
                     {:intent/kind :place
                      :intent/item :cobblestone
                      :intent/status :active
                      :intent/stage :spot
                      :intent/cell [6 64 5]}))
        out (first (fx/fold intent-step w [{:event/kind :tick :event/now 0 :event/rand 0.5}]))]
    (is (= :active (:intent/status (:plan/intent out))))
    (is (= 1 (:intent/nudges (:plan/intent out))) "nudged as before")))
