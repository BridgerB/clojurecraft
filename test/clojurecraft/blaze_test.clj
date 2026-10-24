(ns clojurecraft.blaze-test
  "Blaze rods (issue #262): the :blaze-rods row's rules over canned worlds, the :camp intent,
   and the rods against the server model's blazes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn entity
  "A tracked entity of type k (a name) at pos, with more attributes."
  [k pos & {:as more}]
  (merge {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0} more))

(defn standing
  "A bot in play in the Nether at [8.5 64 8.5] on the stone floor of one column, an iron sword in
   hotbar slot 0 and inv besides, with the given entities."
  ([entities] (standing {} entities))
  ([inv entities]
   (-> (game/init fx/opts)
       (assoc :bot/phase :play
              :player/dimension dimension/nether
              :player/pos [8.5 64.0 8.5]
              :player/loaded? true
              :player/on-ground? true
              :player/health 20.0
              :player/food 20
              :world/chunks {[0 0] (world/column {})}
              :window/state-id 5
              :player/inventory (merge {0 (item :iron_sword 1)} inv)
              :world/entities entities))))

(def row (first (filter #(= :blaze-rods (:goal/id %)) plan/goals)))

(defn next-intent [w] (plan/next-intent w row plan/goals))

(defn idle?
  "Is intent i nothing to fight nor fetch: the wait :no-blaze, or the search for a spawner a bot
   with none on record carries on with (issue #507)?"
  [i]
  (or (= {:plan/wait :no-blaze} i) (some? (:intent/search i))))

(def spawner-at [15 64 15])

(defn with-spawner
  "w remembering a spawner at spawner-at, seen in its dimension."
  [w]
  (memory/observe w spawner-at blaze/spawner))

(deftest seven-rods-held-is-done
  (is (not (plan/done-by (standing {1 (item :blaze_rod 6)} {}) row)))
  (is (plan/done-by (standing {1 (item :blaze_rod 6) 2 (item :blaze_rod 1)} {}) row)
      "counted across slots")
  (is (= dimension/nether (:goal/dimension row)) "worked in the Nether only"))

(deftest a-rod-lying-near-then-the-nearest-blaze-then-the-spawner
  (let [rod (entity :item [10.5 64.0 9.5] :entity/item blaze/rod)
        near (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)
        far (entity :blaze [8.5 65.0 23.5] :entity/health 20.0)]
    (is (= {:intent/kind :collect-drops :intent/item :blaze_rod :intent/target [10 64 9]}
           (next-intent (standing {40 rod 7 near})))
        "the rod first: it lies there now, the blaze stays")
    (is (= {:intent/kind :fight :intent/target 7 :intent/yield? true}
           (next-intent (standing {7 near 8 far})))
        "the nearest blaze within 12")
    (is (= {:intent/kind :fight :intent/target 7 :intent/yield? true}
           (next-intent (standing {40 (entity :item [10.5 64.0 9.5] :entity/item 1) 7 near})))
        "a drop that says it holds something else is left")
    (is (= {:intent/kind :walk :intent/target spawner-at}
           (next-intent (with-spawner (standing {8 far}))))
        "none within 12: to the spawner, by a route")
    (is (= :tunnel-to (:intent/kind (next-intent (standing {8 far}))))
        "no spawner known: the search")
    (testing "a ghost is left alone, and a spawner no walk nor tunnel reached"
      (is (= :tunnel-to
             (:intent/kind (next-intent (assoc (with-spawner (standing {7 near}))
                                               :plan/blacklist
                                               #{7 spawner-at}))))
          "the search, not a wait"))))

(defn closed
  "w with a world of its feet's two cells alone, no way to go: nothing for a search."
  [w]
  (assoc w :world/chunks {} :world/blocks {[8 64 8] blocks/air [8 65 8] blocks/air}))

(deftest with-no-spawner-known-the-bot-searches-for-one
  ;; sim races R2/47 and R4/2 (issue #507): healthy at a fortress's edge, no spawner on record,
  ;; the plan waited :no-blaze; the captured Nethers' spawners were 117 and 134 blocks off
  (let [w (standing {})
        i (next-intent w)]
    (is (= :tunnel-to (:intent/kind i)))
    (is (= (fortress/leg-target (:intent/search i)) (:intent/target i)) "the spiral's leg")
    (is (= fortress/steps-per-tunnel (:intent/steps i)))
    (is (= [1 0] (:search/heading (:intent/search i))) "east first, as the fortress row's")
    (is (= {:plan/wait :no-blaze} (next-intent (closed w))) "no way to go: a wait")
    (is (= :tunnel-to
           (:intent/kind (next-intent (assoc (with-spawner w) :plan/blacklist #{spawner-at}))))
        "a spawner given up: the search too")))

(deftest a-blocked-tunnel-tries-another-stand-then-gives-the-spawner-up
  ;; real race s2 Clj_r06 (issue #502): four tunnels in a row to one stand, each :blocked; the
  ;; stand did not depend on the blacklist, and the spawner was never given up
  (let [sp [8 60 11]
        w (-> (standing {})
              (memory/observe sp blaze/spawner)
              (assoc :world/chunks
                     {[0 0] (world/column {[9 60 11] blocks/air
                                           [9 61 11] blocks/air
                                           [7 60 11] blocks/air
                                           [7 61 11] blocks/air
                                           [8 60 12] blocks/air
                                           [8 61 12] blocks/air})}))
        tunnel-target #(:intent/target (next-intent %))
        first-stand (blaze/stand w sp)
        w2 (assoc w :plan/blacklist #{first-stand})
        second-stand (blaze/stand w2 sp)
        w3 (assoc w :plan/blacklist #{first-stand second-stand})]
    (is (= first-stand (tunnel-target w)))
    (is (some? second-stand))
    (is (not= first-stand second-stand) "after one blocked tunnel, another face")
    (is (= second-stand (tunnel-target w2)))
    (is (= sp (blaze/nearest-spawner w2)) "one blocked stand: the spawner is still the one")
    (is (blaze/given-up? w3 sp) "after two, given up")
    (is (nil? (blaze/nearest-spawner w3)))
    (testing "the next spawner remembered is taken"
      (let [other [8 60 14]
            w4 (memory/observe w3 other blaze/spawner)]
        (is (= other (blaze/nearest-spawner w4)))))))

(deftest a-drop-in-a-pit-is-walked-onto-and-one-in-stone-is-left
  ;; sim race 39712220957 (issue #500): drops lying under the floor, or in a block placed over
  ;; them, were stood over until the collect failed :not-picked-up
  (let [pit-floor-y 63
        drop (fn [y] (entity :item [10.5 y 9.5]))
        pit (assoc-in (standing {40 (drop (+ pit-floor-y 0.25))})
             [:world/blocks [10 pit-floor-y 9]]
             blocks/air)
        near (standing {40 (drop 64.0)})]
    (is
     (= {:intent/kind :walk :intent/target [10 pit-floor-y 9] :intent/for :rod} (next-intent pit))
     "a drop in a pit under the floor: the feet onto its cell, the pickup box is half a block deep")
    (is (some? (blaze/rod-lying pit)))
    (is (= :collect-drops (:intent/kind (next-intent near))) "one on the floor: pressed toward")
    (is (nil? (blaze/rod-lying (standing {40 (drop (+ pit-floor-y 0.25))})))
        "one inside the stone under the floor: no contact reaches it, left")
    (is (idle? (next-intent (standing {40 (drop (+ pit-floor-y 0.25))}))))))

(def boxed-in
  "Stone round the feet cell [8 64 8], two high, and a roof over it: no route leaves it."
  (merge (into {}
               (for [x (range 7 10)
                     z (range 7 10)
                     y [64 65]
                     :when (not= [x z] [8 8])]
                 [[x y z] (blocks/default-state :stone)]))
         {[8 66 8] (blocks/default-state :stone)}))

(deftest only-a-blaze-the-bot-can-reach-is-fought
  ;; gym blaze-fortress-a (run 38892386220): three fights in a row failed :unreachable on blazes
  ;; seen through the fortress's walls from the tunnel, every run
  (let [near (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)]
    (is (idle? (next-intent
                (assoc (standing {7 near}) :world/chunks {[0 0] (world/column boxed-in)})))
        "behind a wall: no route, no fight")
    (is (idle? (next-intent (standing {7 (entity :blaze [10.5 72.0 8.5] :entity/health 20.0)})))
        "eight over the feet: out of reach from the floor")
    (testing "a camp that began with it there goes on"
      (let [w (assoc (standing {7 near})
                     :world/chunks {[0 0] (world/column boxed-in)}
                     :time/now 1000
                     :plan/intent
                     {:intent/kind :camp :intent/spawner [11 64 10] :intent/status :active})
            w (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000})]
        (is (= #{7} (:intent/blazes (:plan/intent w))))
        (is (= :active (:intent/status (:plan/intent w))))))))

(deftest a-spawner-under-the-feet-is-tunnelled-to-its-floor
  ;; gym blaze-fortress-b (run 38893941350): a tunnel ended within 6 of the spawner, four over it
  ;; in the wall, and the bot camped there out of reach of its blazes for ten minutes;
  ;; blaze-fortress-c (run 38895759229) run 3 came up three under the spawner's floor and every
  ;; rod lay out of reach over it
  (let [w (memory/observe (standing {}) [8 60 11] blaze/spawner)]
    (is (= {:intent/kind :tunnel-to
            :intent/target [8 60 11]
            :intent/steps fortress/steps-per-tunnel
            :intent/reach blaze/spawner-reach
            :intent/climb 0}
           (next-intent w))
        "within 8 of the eye, four under the feet, nothing open beside it: a tunnel down to it")
    (let [room (assoc w
                      :world/chunks
                      {[0 0] (world/column {[9 60 11] blocks/air [9 61 11] blocks/air})})]
      (is (= [9 60 11] (blaze/stand room [8 60 11])))
      (is (= {:intent/kind :tunnel-to
              :intent/target [9 60 11]
              :intent/steps fortress/steps-per-tunnel
              :intent/reach 0.5
              :intent/climb 0}
             (next-intent room))
          "with room beside it on its floor, into that cell")
      (is (= {:intent/kind :camp :intent/spawner [8 60 11]}
             (next-intent (assoc room :player/pos [9.5 60.0 11.5])))
          "standing there, a camp"))))

(deftest by-the-spawner-the-bot-camps
  (let [w (memory/observe (standing {}) [11 64 10] blaze/spawner)]
    (is (= {:intent/kind :camp :intent/spawner [11 64 10]} (next-intent w)))
    (let [w (assoc w
                   :plan/intent
                   {:intent/kind :camp :intent/spawner [11 64 10] :intent/status :active})
          camp
          (fn [w t]
            (intent/run (assoc w :time/now t) (:plan/intent w) {:event/kind :tick :event/now t}))
          w1 (camp w 1000)]
      (is (= :active (:intent/status (:plan/intent w1))) "nothing yet: it stands")
      (is (= :done
             (:intent/status (:plan/intent
                              (camp (assoc w1 :world/entities {7 (entity :blaze [12.5 65.0 8.5])})
                                    2000))))
          "a blaze came")
      (is (= [:failed :no-blaze]
             ((juxt :intent/status :intent/reason) (:plan/intent (camp w1 (+ 1001 blaze/camp-ms)))))
          "none in a minute")
      (is (= #{[11 64 10]} (:plan/blacklist (camp w1 (+ 1001 blaze/camp-ms))))
          "the silent spawner is given up for this plan, not camped again (issue #507)"))))

(def closet
  "A room five by three round the feet, walled and roofed in stone: nowhere ten blocks off to hide."
  (into {}
        (for [x (range 6 13)
              z (range 6 11)
              y [64 65 66]
              :when (or (= y 66) (not (and (<= 7 x 11) (<= 7 z 9))))]
          [[x y z] (blocks/default-state :stone)])))

(def pillar
  "Two stone blocks west of the feet: the cell beyond them is out of the sight of a blaze east."
  {[6 64 8] (blocks/default-state :stone) [6 65 8] (blocks/default-state :stone)})

(deftest hurt-the-bot-hides-backs-away-eats-or-waits
  ;; the blaze five off, past at-hand: one nearer is fought first (blaze/at-hand-blaze)
  (let [near (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)
        hurt (fn [w] (assoc w :player/health 8.0))]
    (testing
      "seen by a blaze, into the cell behind a pillar, out of its sight (gym blaze-fortress-e, f)"
      (let [pillar {[6 64 8] (blocks/default-state :stone) [6 65 8] (blocks/default-state :stone)}]
        (is (= {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}
               (next-intent
                (hurt (assoc (standing {7 near}) :world/chunks {[0 0] (world/column pillar)})))))
        (is (= {:intent/kind :back-off :intent/target 7} (next-intent (hurt (standing {7 near}))))
            "in the open nothing hides: backed away from")))
    (testing "a retreat off a lip into a drop no landing survives is a rest (issue #514)"
      (let [air (blocks/default-state :air)
            lip (blocks/default-state :nether_bricks)
            void (into {[7 64 8] lip} (for [x [5 6] y (range 60 64)] [[x y 8] air]))
            w (assoc (hurt (standing {7 near})) :world/chunks {[0 0] (world/column void)})]
        (is (combat/void-retreat? w (get-in w [:world/entities 7])))
        (is (= blaze/rest-intent (next-intent w)))))
    (is (= blaze/rest-intent
           (next-intent (assoc (standing {1 (item :cooked_beef 4)} {}) :player/health 12.0)))
        "out of sight under 14: a rest")
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent
            (assoc (hurt (standing {1 (item :cooked_beef 4)} {7 near})) :player/food 15)))
        "the food under the healing level: a meal, even with a blaze at hand (gym blaze-7 run 1)")
    (is (= blaze/rest-intent (next-intent (hurt (standing {1 (item :cooked_beef 4)} {}))))
        "fed: the health comes back by itself, at rest")
    (testing "cover before the meal where there is cover (gym blazefort-k run 3)"
      (is (= {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}
             (next-intent (assoc (hurt (standing {1 (item :cooked_beef 4)} {7 near}))
                                 :player/food 15
                                 :world/chunks {[0 0] (world/column pillar)})))))
    (testing "under 14, watched, cover comes before the next fight (gym blazefort-k run 3)"
      (is (= {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}
             (next-intent (assoc (standing {7 near})
                                 :player/health 12.0
                                 :world/chunks {[0 0] (world/column pillar)})))))
    (testing "under 14 a meal comes before the next fight when the food is low (gym blaze-7b run 2)"
      (is (= {:intent/kind :eat :intent/item :cooked_beef}
             (next-intent (assoc (standing {1 (item :cooked_beef 4)} {7 near})
                                 :player/health 12.0
                                 :player/food 17))))
      (is (= {:intent/kind :fight :intent/target 7 :intent/yield? true}
             (next-intent
              (assoc (standing {1 (item :cooked_beef 4)} {7 near}) :player/health 12.0)))
          "fed, it fights on"))
    (is (= {:plan/wait :other-dimension}
           (next-intent (assoc (standing {}) :player/dimension dimension/overworld))))))

(deftest rotten-flesh-is-eaten-when-it-is-all-there-is-to-heal-on
  ;; issue #501's R1-10-2 and R2-44-2: eight and two rotten flesh held, hurt at food 13 and 14,
  ;; rested for ever; the Nether's zombified piglins drop it and it gives four food
  (let [hurt #(assoc % :player/health 8.0)
        flesh (fn [food] (assoc (hurt (standing {1 (item :rotten_flesh 8)} {})) :player/food food))]
    (is (blaze/heals? (flesh 13)) "a rest can heal with it to eat")
    (is (= {:intent/kind :eat :intent/item :rotten_flesh} (next-intent (flesh 13))))
    (is (= {:intent/kind :eat :intent/item :rotten_flesh} (next-intent (flesh 14))))
    (is (= blaze/rest-intent (next-intent (flesh 18))) "fed: the rest heals by itself")
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent
            (assoc (flesh 14)
                   :player/inventory
                   {0 (item :iron_sword 1) 1 (item :rotten_flesh 8) 2 (item :cooked_beef 2)})))
        "real food first")))

(deftest a-rest-that-cannot-heal-holds-in-cover-and-never-fights-on
  ;; sim race 39712220957 (issue #501): hurt at food 16 with nothing to eat, three rests of ninety
  ;; seconds, no health back, the run ended :no-heal
  (let [hurt #(assoc % :player/health 8.0)]
    (is (blaze/heals? (standing {})) "food 20: health comes back by itself")
    (is (not (blaze/heals? (assoc (standing {}) :player/food 16))) "food under 18, nothing to eat")
    (is (blaze/heals? (assoc (standing {1 (item :cooked_beef 4)} {}) :player/food 16))
        "food held: the meal first")
    (is (= blaze/rest-intent (next-intent (hurt (standing {})))) "fed: a rest")
    (is (= blaze/rest-intent (next-intent (hurt (assoc (standing {}) :player/food 16))))
        "food 16, nothing to eat: still a rest, never back out to fight at that health")
    (let [rest-for (fn [food now]
                     (intent/run (assoc (standing {})
                                        :player/health 8.0
                                        :player/food food
                                        :time/now now
                                        :plan/intent
                                        (assoc blaze/rest-intent
                                               :intent/status :active
                                               :intent/since 0
                                               :intent/health 8.0))
                                 (assoc blaze/rest-intent :intent/since 0 :intent/health 8.0)
                                 {:event/kind :tick :event/now now}))]
      (is (= :failed (:intent/status (:plan/intent (rest-for 20 (inc blaze/rest-ms)))))
          "fed and no health back in rest-ms: :no-heal")
      (is (not= :failed (:intent/status (:plan/intent (rest-for 16 (inc blaze/rest-ms)))))
          "it cannot heal: held, not failed"))
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent (hurt (assoc (standing {1 (item :cooked_beef 4)} {}) :player/food 16))))
        "with food held it eats")
    (is (= blaze/rest-intent
           (blaze/warily (assoc (standing {}) :player/health 12.0 :player/food 16)))
        "wary the same")))

(deftest a-blaze-at-hand-is-fought-first
  ;; gym blaze-fire-b run 3 (run 39136853644): blazes half a block to two off followed the bot
  ;; into four covers in eight seconds, and it died from 14 health without a swing
  (let [hand (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        far (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)
        fight {:intent/kind :fight :intent/target 7 :intent/to-the-end? true}
        pillar-world (fn [w] (assoc w :world/chunks {[0 0] (world/column pillar)}))]
    (is (= [7 hand] (blaze/at-hand-blaze (standing {7 hand 8 far}))))
    (is (nil? (blaze/at-hand-blaze (standing {8 far}))) "five off is not at hand")
    (is (= fight (next-intent (pillar-world (assoc (standing {7 hand}) :player/health 8.0))))
        "hurt, with cover behind the pillar: fought, to the end")
    (is (= fight
           (next-intent (assoc (standing {1 (item :cooked_beef 4)} {7 hand}) :player/food 15)))
        "before the meal")
    (is (= fight
           (next-intent (pillar-world (standing
                                       {7 hand 8 far 9 (assoc far :entity/pos [13.5 65.0 6.5])}))))
        "outnumbered: the one at hand, not the cover")
    (is (= fight
           (next-intent (assoc (standing {7 hand}) :world/chunks {[0 0] (world/column closet)})))
        "in the closet too")
    (testing "it ends any other running intent, never a fight"
      (let [w (standing {7 hand})]
        (is (plan/interrupts? w row {:intent/kind :camp :intent/spawner [11 64 10]}))
        (is
         (plan/interrupts? w row {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}))
        (is (not (plan/interrupts? w row {:intent/kind :fight :intent/target 8})))
        (is (not (plan/interrupts? (standing {8 far})
                                   row
                                   {:intent/kind :camp :intent/spawner [11 64 10]})))))))

(deftest watched-by-a-volley-the-bot-takes-cover
  ;; gym blazefort-k (run 38913023560) runs 3 and 4: the natural spawner's burst of four, a hit a
  ;; second, and the bot dead from over 14 health in four seconds
  (let [three {7 (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)
               8 (entity :blaze [12.5 65.0 6.5] :entity/health 20.0)
               9 (entity :blaze [12.5 65.0 10.5] :entity/health 20.0)}
        w (standing three)]
    (is (= 3 (count (blaze/watchers w))))
    (is (blaze/outnumbered? w))
    (is (= {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}
           (next-intent (assoc w :world/chunks {[0 0] (world/column pillar)})))
        "three watching, at full health: behind the pillar")
    (is (= {:intent/kind :fight :intent/target 7 :intent/yield? true} (next-intent w))
        "in the open, nothing hides: the nearest is fought")
    (is (= {:intent/kind :fight :intent/target 7 :intent/yield? true}
           (next-intent
            (assoc (standing (dissoc three 9)) :world/chunks {[0 0] (world/column pillar)})))
        "two watching: fought")))

(deftest the-defend-row-leaves-blazes-to-this-row
  ;; gym blaze-fire-a run 1 (run 39131023637): the :defend row fought a spawner's burst of six at
  ;; hand from 20 health to 7 in four seconds and died backing away, never asking this row, which
  ;; takes cover from three
  (let [near (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        skeleton (entity :wither_skeleton [10.5 64.0 9.5] :entity/health 20.0)
        defend (first (filter #(= :defend (:goal/id %)) plan/goals))
        playing (fn [& args] (assoc (apply standing args) :plan/goals #{:blaze-rods}))]
    (is (empty? (combat/threats (playing {7 near}))) "a blaze at hand is this row's")
    (is (plan/done-by (playing {7 near}) defend))
    (is (= #{8} (set (keys (combat/threats (playing {7 near 8 skeleton})))))
        "anything else hostile is still the :defend row's")
    (is (= #{7} (set (keys (combat/threats (assoc (standing {7 near}) :plan/goals #{:wood})))))
        "with the row not in play, a blaze is a threat")
    (is (= #{7} (set (keys (combat/threats (playing {1 (item :blaze_rod 7)} {7 near})))))
        "nor once its rods are held")))

(deftest cover-is-out-of-every-watchers-sight
  (let [w (assoc (standing {}) :world/chunks {[0 0] (world/column pillar)})
        east [12 65 8]
        south [5 65 12]]
    (is (blaze/unseen-from? w [5 64 8] east) "the pillar between")
    (is (not (blaze/unseen-from? w [5 64 8] south)) "nothing between")
    (is (path/goal-done? w {:goal/kind :unseen :goal/pos east} [5 64 8]))
    (is (not (path/goal-done? w {:goal/kind :unseen :goal/pos east :goal/cells [south]} [5 64 8]))
        "a cell one blaze still sees is no cover")))

(deftest a-camp-ends-when-the-bot-is-hit
  ;; gym blazefort-k run 3 (run 38913023560): a camp stood on from 11.5 health to none
  (let [w (assoc (standing {})
                 :time/now 1000
                 :plan/intent
                 {:intent/kind :camp :intent/spawner [11 64 10] :intent/status :active})
        tick (fn [w] (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))
        w1 (tick w)]
    (is (= 20.0 (:intent/health (:plan/intent w1))))
    (is (= :active (:intent/status (:plan/intent w1))))
    (is (= :done (:intent/status (:plan/intent (tick (assoc w1 :player/health 16.88)))))
        "shot: the act looks again")))

(deftest a-rest-ends-rested-hit-or-watched
  (let [w (assoc (standing {})
                 :player/health 12.0
                 :time/now 1000
                 :plan/intent (assoc blaze/rest-intent :intent/status :active))
        rest (fn [w t]
               (intent/run (assoc w :time/now t) (:plan/intent w) {:event/kind :tick :event/now t}))
        w1 (rest w 1000)
        status (fn [w] (:intent/status (:plan/intent w)))]
    (is (= :active (status w1)) "under rested: it stands")
    (is (= {} (:player/controls w1)))
    (is (= :done (status (rest (assoc w1 :player/health 18.0) 2000))) "rested")
    (is (= :done (status (rest (assoc w1 :player/health 11.0) 2000))) "shot from somewhere unseen")
    (is (= :done
           (status
            (rest (assoc w1 :world/entities {7 (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)})
                  2000)))
        "a blaze came watching")
    (is (= [:failed :no-heal]
           ((juxt :intent/status :intent/reason) (:plan/intent (rest w1 (+ 1001 blaze/rest-ms)))))
        "nothing came back")))

(deftest a-tunnel-to-the-spawner-first-makes-the-pickaxe-it-lacks
  ;; sim race 39742401798 (issue #504) R4-6-2: no pickaxe, three sticks, cobblestone and a table
  ;; held; each tunnel step ended :no-pickaxe and the bot dug by hand
  (let [kit {1 (item :cobblestone 8) 2 (item :stick 3) 3 (item :crafting_table 1)}
        making? #(contains? #{:place :craft :open-container} (:intent/kind (next-intent %)))]
    (is (making? (with-spawner (standing kit {}))) "a pickaxe from what is held, before the way")
    (is (not (making? (with-spawner (standing (assoc kit 4 (item :stone_pickaxe 1)) {}))))
        "with a pickaxe held, the way as before")
    (is (not (making? (with-spawner (standing {} {}))))
        "nothing to make one from: the way as before")))

(deftest a-fire-by-the-spawner-is-put-out
  ;; gym blaze-7a run 3 (run 38891910129): the floor by the spawner lit, and no blaze for three
  ;; minutes
  (let [fire (blocks/default-state :fire)
        sp [11 64 10]
        w (memory/observe (standing {}) sp blaze/spawner)
        at (fn [cells] (assoc w :world/chunks {[0 0] (world/column cells)}))]
    (is (= {:intent/kind :camp :intent/spawner sp} (next-intent w)) "no fire: a camp")
    (is (= [[10 64 10]] (blaze/fires (at {[10 64 10] fire}) sp)))
    (is (= {:intent/kind :dig :intent/target [10 64 10]} (next-intent (at {[10 64 10] fire})))
        "within reach of the eye: a left click puts it out")
    (is (= {:intent/kind :walk :intent/target [15 64 12] :intent/for :fire}
           (next-intent (at {[15 64 12] fire})))
        "farther: walked to")
    (is (= {:intent/kind :camp :intent/spawner sp}
           (next-intent (assoc (at {[10 64 10] fire}) :plan/blacklist #{[10 64 10]})))
        "one a dig failed in is left")
    (is (= {:intent/kind :camp :intent/spawner sp} (next-intent (at {[21 64 15] fire})))
        "a fire farther than fire-radius from the spawner is not its concern")))

;; ---------------------------------------------------------------- against the server model

(def blazes
  "Five of the model's blazes, hovering a block over the Nether floor round the landing, all
   within the row's 12: they do not move and shoot when they see the bot (mob/kinds)."
  (mapv (fn [p] {:type :blaze :pos p})
        [[11.5 65.0 8.5] [5.5 65.0 11.5] [12.5 65.0 13.5] [3.5 65.0 4.5] [13.5 65.0 3.5]]))

(deftest the-seventh-rod-from-the-models-blazes
  ;; six rods given, one more wanted, as gym row blaze-1 starts: the fight, the pickup of a drop
  ;; that comes one kill in two (rolled from the model's seed), the bot hurt by their fireballs at
  ;; Easy and healing from a full bar
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {} dimension/nether)
                                    :spawn [8.5 64.0 8.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :seed 3
                                    :inventory {[:hotbar 0] (item :iron_sword 1)
                                                [:hotbar 1] (item :blaze_rod 6)}
                                    :mobs blazes})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (inventory/item-count w blaze/rod) blaze/rods-wanted))
    (is (pos? (:sim/health sim)) "alive")
    (is (= [] (:sim/violations sim)))))

(deftest a-hurt-bot-watched-from-afar-stops-what-it-is-doing
  ;; sim snapshot R4-31-1 (issue #502): a tunnel went on under fire from blazes ten off, past the
  ;; at-hand radius, from 16.8 health to none
  (let [far (entity :blaze [8.5 65.0 15.5] :entity/health 20.0)
        tunnel {:intent/kind :tunnel-to :intent/target [8 64 30]}
        hurt (assoc (standing {8 far}) :player/health 9.0)]
    (is (blaze/under-fire? hurt tunnel))
    (is (plan/interrupts? hurt row tunnel))
    (testing "not at full health, not when the intent is the answer, not with nothing watching"
      (is (not (plan/interrupts? (standing {8 far}) row tunnel)))
      (is (not (blaze/under-fire? hurt {:intent/kind :rest})))
      (is (not (blaze/under-fire? hurt {:intent/kind :walk :intent/for :cover})))
      (is (not (blaze/under-fire? (assoc (standing {}) :player/health 9.0) tunnel))))))
