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
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.window :as window]
            [clojurecraft.world :as world]
            [datascript.core :as d]))

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
            :intent/climb 0
            :intent/bridge? true}
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
              :intent/climb 0
              :intent/bridge? true}
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
            "in the open nothing hides: backed away from")
        (let [far (entity :blaze [20.5 65.0 8.5] :entity/health 20.0)]
          (is (not= :back-off (:intent/kind (next-intent (hurt (standing {7 far})))))
              "a watcher past back-off-to is not backed away from: the walk would end in a tick"))))
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

(deftest a-rest-that-cannot-heal-is-never-chosen-or-kept
  ;; sim race 39712220957 (issue #501): hurt at food 16 with nothing to eat, three rests of ninety
  ;; seconds, no health back, the run ended :no-heal. Probe 40634461444 then held such a rest for
  ;; hours: it is never chosen, and one already running ends.
  (let [hurt #(assoc % :player/health 8.0)]
    (is (blaze/heals? (standing {})) "food 20: health comes back by itself")
    (is (not (blaze/heals? (assoc (standing {}) :player/food 16))) "food under 18, nothing to eat")
    (is (blaze/heals? (assoc (standing {1 (item :cooked_beef 4)} {}) :player/food 16))
        "food held: the meal first")
    (is (= blaze/rest-intent (next-intent (hurt (standing {})))) "fed: a rest")
    (let [dry (next-intent (hurt (assoc (standing {}) :player/food 16)))]
      (is (not= :rest (:intent/kind dry)) "food 16, nothing to eat: not a rest")
      (is (not= :fight (:intent/kind dry)) "and not a fight under retreat-health"))
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
      (is (= :done (:intent/status (:plan/intent (rest-for 16 0))))
          "it cannot heal: ended, not held"))
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent (hurt (assoc (standing {1 (item :cooked_beef 4)} {}) :player/food 16))))
        "with food held it eats")
    (is (nil? (blaze/warily (assoc (standing {}) :player/health 12.0 :player/food 16)))
        "wary, and the rest cannot heal: not a rest")))

(deftest a-blaze-at-hand-is-fought-first
  ;; gym blaze-fire-b run 3 (run 39136853644): blazes half a block to two off followed the bot
  ;; into four covers in eight seconds, and it died from 14 health without a swing
  (let [hand (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        far (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)
        fight {:intent/kind :fight :intent/target 7 :intent/to-the-end? true :intent/yield? true}
        pillar-world (fn [w] (assoc w :world/chunks {[0 0] (world/column pillar)}))]
    (is (= [7 hand] (blaze/at-hand-blaze (standing {7 hand 8 far}))))
    (is (nil? (blaze/at-hand-blaze (standing {8 far}))) "five off is not at hand")
    (is (= :cover
           (:intent/for (next-intent (pillar-world
                                      (assoc (standing {7 hand}) :player/health 8.0)))))
        "hurt under retreat-health: breaks off and takes cover behind pillar, never fights (#577)")
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
    (testing "it ends running camp when healthy, but never interrupts cover when hurt (#577)"
      (let [w (standing {7 hand})
            hurt-w (assoc w :player/health 8.0)]
        (is (plan/interrupts? w row {:intent/kind :camp :intent/spawner [11 64 10]}))
        (is (not (plan/interrupts?
                  hurt-w
                  row
                  {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover})))
        (is (not (plan/interrupts? w row {:intent/kind :fight :intent/target 8})))
        (is (not (plan/interrupts? (standing {8 far})
                                   row
                                   {:intent/kind :camp :intent/spawner [11 64 10]})))))))

(deftest three-blazes-about-and-under-rested-health-cover-comes-before-the-swing
  ;; sim R1/1 (probe 40751212155, t 5380350): a fight started at 15.0 health with three blazes
  ;; about, was at 7.2 six seconds later and dead 14 s on
  (let [hand (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        two {7 (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)
             8 (entity :blaze [13.5 65.0 6.5] :entity/health 20.0)
             9 (entity :blaze [13.5 65.0 10.5] :entity/health 20.0)}
        w (assoc (standing two) :world/chunks {[0 0] (world/column pillar)})]
    (is (blaze/outnumbered? w))
    (is (blaze/crowded? (assoc w :player/health 15.0)))
    (is (not (blaze/crowded? w)) "at rested health it is not crowded")
    (is (= :cover (:intent/for (next-intent (assoc w :player/health 15.0)))))
    (is (= :cover (:intent/for (next-intent w)))
        "three watching, none at hand, full health: the cover (the goal row's rule)")
    (testing "with one at hand it is fought, as it follows into any cover (blaze-fire-b)"
      (let [near (assoc-in w [:world/entities 10] hand)
            hurt (assoc near :player/health 15.0)]
        (is (not (blaze/crowded? hurt)))
        (is (= :fight (:intent/kind (next-intent hurt))))
        (is (plan/interrupts? hurt
                              row
                              {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}))))
    (testing "a cover walk is not interrupted by a blaze not at hand"
      (is (not (plan/interrupts? (assoc w :player/health 15.0)
                                 row
                                 {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}))))
    (testing "with no cover to be had the nearest is still fought"
      (is (= :fight (:intent/kind (next-intent (assoc (standing two) :player/health 15.0))))))))

(deftest a-rod-by-the-spawner-is-not-gone-for-under-fire
  ;; sim R1/1 (probe 40779151074): :collect-drops [317 77 238] at 13.4, 13.6 and 8.0 health with
  ;; blazes alive; each was hit, two died
  ;; a rod farther than grab-range: one within a step is picked up from low-health (near-rod)
  (let [rod (entity :item [14.5 64.0 9.5] :entity/item (recipe/item-id :blaze_rod))
        watchers {8 (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)}
        w (assoc (standing (assoc watchers 7 rod)) :player/health 13.4)]
    (is (seq (blaze/watchers w)))
    (is (not (blaze/collectable? w)))
    (is (not= :collect-drops (:intent/kind (next-intent w))))
    (testing "healthy, or unseen, the rod is gone for"
      (is (blaze/collectable? (assoc w :player/health 19.0)))
      (is (blaze/collectable? (assoc (standing {7 rod}) :player/health 13.4))))))

(deftest a-burning-bot-leaves-the-fire-and-neither-camps-nor-rests
  ;; gym blaze-fortress-race run 4 (run 40770939167): the own entity's flags byte {0 1} at the first
  ;; fireball, {0 0} when it burned out; `HIT type 31` between, while it camped; run 3 burned to
  ;; death, no water in the Nether to put itself out
  (let [step (fn [w md]
               (game/step w
                          {:event/kind :packet
                           :event/packet
                           {:packet/name :set-entity-data :entity-id 27 :metadata md}}))
        me (assoc (standing {}) :player/entity-id 27)]
    (testing "the flag is read off the bot's own entity, bit 0x01"
      (is (true? (:player/on-fire? (step me {0 1 9 17.1}))))
      (is (false? (:player/on-fire? (step (step me {0 1}) {0 0}))))
      (is (true? (:player/on-fire? (step me {0 3}))) "on fire and crouching")
      (is (nil? (:player/on-fire? (step me {9 17.1}))) "no flags byte: nothing learned")
      (is (nil? (:player/on-fire? (game/step me
                                             {:event/kind :packet
                                              :event/packet {:packet/name :set-entity-data
                                                             :entity-id 99
                                                             :metadata {0 1}}})))
          "another entity's"))
    (testing "in a fire block it steps out, before a fight or a camp"
      (let [burning (assoc (standing {})
                           :player/on-fire? true
                           :world/chunks
                           {[0 0] (world/column {[8 64 8] (blocks/default-state :fire)})})
            i (next-intent burning)]
        (is (blaze/burning-here? burning))
        (is (= :walk (:intent/kind i)))
        (is (= :fire-out (:intent/for i)))
        (is (not (blaze/burning-here? (assoc burning :world/chunks {[0 0] (world/column {})}))))
        (is
         (= :fire-out (:intent/for (next-intent (assoc burning :player/on-fire? false))))
         "alight or not, fire at the feet is left first: in_fire hit up to 26 times a run (blaze-race-g)")))
    (testing "burning, it does not rest in cover"
      (let [sp [11 64 10]
            pillar {[6 64 8] (blocks/default-state :stone) [6 65 8] (blocks/default-state :stone)}
            in-cover (-> (standing {})
                         (assoc :world/chunks {[0 0] (world/column pillar)}
                                :player/pos [5.5 64.0 8.5]
                                :player/health 12.0)
                         (memory/observe sp blaze/spawner))]
        (is (= blaze/rest-intent (next-intent in-cover)))
        (is (not= blaze/rest-intent (next-intent (assoc in-cover :player/on-fire? true))))))))

(deftest the-camp-is-in-the-tunnel-one-wide-not-in-the-room
  ;; gym blaze-fortress-race run 4 (run 40770939167): camped at the tunnel's end [2347 60 2282] in
  ;; the room; blazes 497 [2347.2 61 2281.5] and 498 [2348.6 60.8 2283.8], 1.4 and 1.9 blocks from
  ;; the bot at [2347.4 60 2282.5], took 18.3 health in five seconds (3.24m to 3.32m)
  (let [stone (blocks/default-state :stone)
        walls (into {} (for [x (range 3 8) y [64 65 66] z [7 9]] [[x y z] stone]))
        roof (into {} (for [x (range 3 8) z [8]] [[x 66 z] stone]))
        plug (into {} (for [y [64 65]] [[2 y 8] stone]))
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column (merge walls roof plug))})
              (memory/observe [11 64 8] blaze/spawner))]
    (is (= 2 (blaze/exposure w [5 64 8])) "the tunnel is one wide")
    (is (< 2 (blaze/exposure w [10 64 8])) "the room is not")
    (is (< 2 (blaze/exposure w [15 64 8])) "the edge of the loaded chunk is not a tunnel")
    (is (< 2 (blaze/exposure w [0 64 15])) "nor its corner")
    (is (= [6 64 8] (blaze/nook w [11 64 8]))
        "the nearest one-wide cell by walking outside the spawner's volume (5 on x)")
    (testing "a trail cell in the spawner's volume is never picked, however little it is exposed"
      (is (blaze/in-spawn-volume? [9 64 10] [6 64 8]) "3 on x and 2 on z")
      (is (blaze/in-spawn-volume? [9 64 10] [5 65 8]) "4 on x, one up")
      (is (not (blaze/in-spawn-volume? [9 64 10] [5 66 8])) "two up is out")
      (is (not (blaze/in-spawn-volume? [9 64 10] [4 64 8])) "5 on x is out")
      (is (= [4 64 8] (blaze/nook w [9 64 8])) "[6] and [5] are inside: the next back")
      (is (= [3 64 8] (blaze/nook w [8 64 8])) "[6] [5] [4] are inside")
      (is (nil? (blaze/nook w [5 64 8])) "all inside: no nook, the camp is as before"))
    (testing "a nook that does not see the spawner is none: the blazes would never come"
      ;; blaze-race-c jobs 2 and 6: 4.9 hours in the nook, 12 blocks from six blazes that never saw it
      (is (blaze/sees-spawner? w [11 64 8] [6 64 8]) "in line with the corridor")
      (is (not (blaze/sees-spawner? w [9 64 14] [4 64 8])) "behind the corridor's wall")
      (is (nil? (blaze/nook w [9 64 14]))))
    (testing "camps done in a row with no fight leave the nook for the room"
      (let [camp (fn [w n] (memory/remember-intention w {:intent/id n :intent/kind :camp} :done))
            idle (-> (assoc w :time/now 100000)
                     (camp 1)
                     (camp 2)
                     (camp 3))
            fought (memory/remember-intention idle {:intent/id 4 :intent/kind :fight} :done)]
        (is (= [6 64 8] (blaze/nook w [11 64 8])))
        (is (blaze/idle? idle))
        (is (nil? (blaze/nook idle [11 64 8])))
        (is (not (blaze/idle? fought)) "a fight in the spell ends it")
        (is (= [6 64 8] (blaze/nook fought [11 64 8])))
        (is (not (blaze/idle? (assoc idle :time/now (+ 100000 blaze/idle-ms 1000))))
            "and the spell passes")))
    (testing "within the spawner's wake"
      (is (blaze/awake-from? [11 64 8] [6 64 8]))
      (is (not (blaze/awake-from? [30 64 10] [6 64 8])) "24 blocks off: it would not run")
      (is (nil? (blaze/nook w [30 64 10]))))
    (testing "at the room's end the tunnel's dead end is a pocket: walked to, there it camps"
      ;; the plugged end [3 64 8] has one opening and a roof: a pocket (pocket.clj), which the
      ;; camp is taken in in place of the nook
      (let [at-end (assoc w :player/pos [8.5 64.0 8.5])
            in-nook (assoc w :player/pos [6.5 64.0 8.5])
            in-pocket (assoc w
                             :player/pos [3.5 64.0 8.5]
                             :pocket/site {:pocket/cell [3 64 8] :pocket/stand nil :pocket/dig []})]
        (is (blaze/at-spawner? at-end [11 64 8]))
        (is (= :pocket (:intent/kind (next-intent at-end))))
        (is (= [3 64 8] (:pocket/cell (:intent/site (next-intent at-end)))))
        (is (blaze/at-nook? in-nook [11 64 8]))
        (is (= :pocket (:intent/kind (next-intent in-nook))) "the nook is not the pocket")
        (is (pocket/in? in-pocket))
        (is (= {:intent/kind :camp :intent/spawner [11 64 8]}
               (next-intent
                (memory/remember-intention in-pocket {:intent/id 99 :intent/kind :pocket} :done)))
            "just in: the camp, a sortie only after sortie-rest-ms")))))

(deftest the-tunnel-is-the-cover-it-knows-and-cover-that-churns-is-dropped
  ;; blaze-race-b (real, a33d2a96e): :walk [2344 57 2281], [2344 57 2280], [2344 57 2279], then
  ;; 2281, 2280, 2283 ... every 0.35 to 0.6 s while being shot (run 1 bot.log 01:58:50 to 01:58:55):
  ;; each cover excluded the last two cells walked to, and the blazes moved. And every run
  ;; re-tunnelled to the room, never to the tunnel it came by
  (let [stone (blocks/default-state :stone)
        walls (into {} (for [x (range 3 8) y [64 65 66] z [7 9]] [[x y z] stone]))
        roof (into {} (for [x (range 3 8)] [[x 66 8] stone]))
        plug (into {} (for [y [64 65]] [[2 y 8] stone]))
        shooter (entity :blaze [12.5 65.0 12.5] :entity/health 20.0)
        w (-> (standing {7 shooter})
              (assoc :world/chunks {[0 0] (world/column (merge walls roof plug))}
                     :player/health 12.0
                     :time/now 100000)
              (memory/observe [11 64 8] blaze/spawner))
        walked (fn [w n target]
                 (memory/remember-intention w
                                            {:intent/id n :intent/kind :walk :intent/target target}
                                            :done))]
    (is (seq (blaze/watchers w)) "seen in the room")
    (testing "the tunnel it came by is the cover: out of the shooter's sight"
      (is (= {:intent/kind :walk
              :intent/target [6 64 8]
              :intent/for :cover
              :intent/max-nodes blaze/corridor-nodes}
             (blaze/hide w))))
    (testing "three walks in eight seconds: the cover is not holding, not asked again"
      (let [churned (-> w
                        (walked 1 [3 64 8])
                        (walked 2 [4 64 8])
                        (walked 3 [5 64 8]))]
        (is (blaze/churning? churned))
        (is (nil? (blaze/hide churned)))
        (is (not (blaze/churning? (assoc churned :time/now (+ 100000 blaze/cover-churn-ms 1000))))
            "and asked again once the spell is over")
        (is (not (blaze/churning? (-> w
                                      (walked 1 [3 64 8])
                                      (walked 2 [4 64 8])))))))))

(deftest the-stand-is-kept-while-the-feet-move-in-the-pocket
  ;; blaze-race-a run 5 (727b4c2b2) and blaze-race-c (3a082f7b7): :tunnel-to [2209 60 1794] and
  ;; [2210 60 1793], the stands west and north of the spawner [2210 60 1794], by turns every 14.5 s
  ;; for 140 minutes, as the feet moved and the nearer of the two changed
  (let [sp [10 64 10]
        west [9 64 10]
        north [10 64 9]
        at (fn [x z] (assoc (standing {}) :player/pos [(+ x 0.5) 64.0 (+ z 0.5)]))
        fact (fn [w target]
               (memory/remember-intention
                (assoc w :time/now 5000)
                {:intent/id 1 :intent/kind :tunnel-to :intent/target target}
                :done))]
    (is (= west (blaze/stand (at 2 4) sp)) "from here the west stand is nearer")
    (is (= north (blaze/stand (at 4 2) sp)) "and from here the north one: the choice flipped")
    (testing "a tunnel last done to a stand keeps it"
      (is (= west (blaze/stand (fact (at 4 2) west) sp)))
      (is (= north (blaze/stand (fact (at 2 4) north) sp))))
    (testing "a stand no longer open, or blacklisted, is not kept"
      (is (= north (blaze/stand (assoc (fact (at 2 4) west) :plan/blacklist #{west}) sp))))))

(deftest a-rod-across-a-ledge-is-not-gone-for
  ;; blaze-race-c job 1 (3a082f7b7): :collect-drops [2375 70 2338] 4060 times in 3 min 23 s (04:07:39
  ;; on), 50 ms apart, the bot standing in fire (HIT type 21, 66 hits), burned to death at 04:13:02.
  ;; The collect is done at once when the step toward the drop would be over a drop with no
  ;; landing (intent/ledge-ahead?), and the planner chose it again for ever
  (let [rod (entity :item [12.5 64.0 8.5] :entity/item (recipe/item-id :blaze_rod))
        gap (into {} (for [y (range 40 64)] [[9 y 8] (blocks/default-state :air)]))
        flat (standing {7 rod})
        ledge (assoc (standing {7 rod}) :world/chunks {[0 0] (world/column gap)})]
    (is (some? (blaze/rod-lying flat)) "on the flat: fetched")
    (is (true? (intent/ledge-ahead? ledge [12.5 64.0 8.5])) "the step toward it is over a drop")
    (is (nil? (blaze/rod-lying ledge)) "across the ledge: left")
    (is (not= :collect-drops (:intent/kind (next-intent ledge))))))

(deftest a-fight-with-a-blaze-is-begun-at-engage-health
  ;; blaze-race-f (real, 6e703362a) geared run 1: rested to 10.5, was walked out, and began :fight
  ;; 524 at 10.5 (just over retreat-health 10); hit at 6.00 min, dead at 6.04
  (let [far (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)
        w (standing {7 far})]
    (is (= 18.0 blaze/engage-health))
    (is (= :fight (:intent/kind (next-intent (assoc w :player/health 19.0)))) "healthy: fought")
    (let [pillar-w (assoc w :world/chunks {[0 0] (world/column pillar)})]
      (doseq [h [10.5 14.0 17.5]]
        (is (= :cover (:intent/for (next-intent (assoc pillar-w :player/health h))))
            (str "with cover to be had: cover first, not a fight at " h))))
    (testing "with no cover, meal or rest to be had the nearest is fought: nothing better"
      (is (= :fight (:intent/kind (next-intent (assoc w :player/health 14.0))))))
    (testing "a blaze at hand is still fought: it follows into any cover (blaze-fire-b)"
      (let [near (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)]
        (is (= :fight
               (:intent/kind (next-intent (assoc (standing {7 near}) :player/health 15.0)))))))))

(deftest a-rest-is-taken-in-the-nook-and-not-chosen-in-a-storm
  ;; blaze-race-f geared run 1: :rest began five times in 0.2 s at 6.01 min, in the blazes' sight;
  ;; and rests at the room's mouth to 10.5
  (let [stone (blocks/default-state :stone)
        walls (into {} (for [x (range 3 8) y [64 65 66] z [7 9]] [[x y z] stone]))
        roof (into {} (for [x (range 3 8)] [[x 66 8] stone]))
        plug (into {} (for [y [64 65]] [[2 y 8] stone]))
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column (merge walls roof plug))}
                     :player/health 12.0
                     :time/now 100000)
              (memory/observe [11 64 8] blaze/spawner))
        rested (fn [w n] (memory/remember-intention w {:intent/id n :intent/kind :rest} :done))]
    (testing "in the room: a walk into the nook; in the nook: the rest"
      (is (= {:intent/kind :walk
              :intent/target [6 64 8]
              :intent/for :cover
              :intent/max-nodes blaze/corridor-nodes}
             (blaze/rest-here w)))
      (is (= blaze/rest-intent (blaze/rest-here (assoc w :player/pos [6.5 64.0 8.5])))))
    (testing "three rests done within two seconds: none chosen"
      (let [storm (-> w
                      (rested 1)
                      (rested 2)
                      (rested 3))]
        (is (blaze/rest-churning? storm))
        (is (nil? (blaze/rest-here storm)))
        (is (not (blaze/rest-churning? (assoc storm :time/now (+ 100000 blaze/rest-churn-ms 100)))))
        (is (not (blaze/rest-churning? (-> w
                                           (rested 1)
                                           (rested 2)))))))))

(deftest a-swarm-out-of-sight-is-waited-for-in-cover
  ;; sim R1/1 (probe 40761589784, TRACE): healed to 18 in cover, the bot walked out to the
  ;; spawner each time, was shot to 4 in the open and went back; sixteen foods, no kill
  (let [row-of (fn [x] (entity :blaze [x 65.0 8.5] :entity/health 20.0))
        w (assoc (standing {7 (row-of 12.5) 8 (row-of 13.5) 9 (row-of 14.5)})
                 :world/chunks {[0 0] (world/column pillar)}
                 :player/pos [5.5 64.0 8.5]
                 :time/now 1000000)
        tick (fn [w i] (intent/run w i {:event/kind :tick :event/now (:time/now w)}))]
    (is (empty? (blaze/watchers w)) "behind the pillar, unseen")
    (is (= 3 (count (blaze/swarm w))))
    (is (= {:intent/kind :ambush} (next-intent w)))
    (testing "in the open it is the cover, not the ambush"
      (is (not= :ambush (:intent/kind (next-intent (assoc w :player/pos [8.5 64.0 8.5]))))))
    (testing "hurt: not an ambush"
      (is (not= :ambush (:intent/kind (next-intent (assoc w :player/health 8.0))))))
    (testing "an ambush that ended lately is not begun again, a later one is"
      (let [ended (memory/remember-intention w {:intent/id 5 :intent/kind :ambush} :done)]
        (is (not= :ambush (:intent/kind (next-intent ended))))
        (is (= :ambush
               (:intent/kind (next-intent
                              (assoc ended :time/now (+ 1000000 blaze/ambush-cooldown 1000))))))))
    (testing "the intent holds, then ends when ambush-ms is spent"
      (let [i {:intent/kind :ambush :intent/since 1000000 :intent/status :active}]
        (is (not (intent/done? (:plan/intent (tick (assoc w :plan/intent i) i)))))
        (let [late (assoc w :time/now (+ 1000000 blaze/ambush-ms 1000))]
          (is (intent/done? (:plan/intent (tick (assoc late :plan/intent i) i)))))))))

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

(deftest with-no-spawner-known-and-bricks-on-record-the-bot-walks-the-corridor
  ;; issue #262: reached fortress, but nearest spawner is far; instead of tunneling away
  ;; via spiral search, the bot walks open fortress corridors toward unexplored bricks
  (let [brick (blocks/default-state :nether_bricks)
        air (blocks/default-state :air)
        ;; A corridor extending east along x from 8 to 15 at y=64 (floor at 63) in chunk [0 0]
        corridor (merge (into {} (for [x (range 8 16)] [[x 63 8] brick]))
                        (into {} (for [x (range 8 16) y [64 65]] [[x y 8] air])))
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column corridor)})
              (memory/observe [15 63 8] brick))
        i (next-intent w)]
    (is (= :walk (:intent/kind i)))
    (is (= :corridor (:intent/for i)))
    (is (= 64 (second (:intent/target i))))
    (is (= blaze/corridor-nodes (:intent/max-nodes i)) "one bounded route, not a search per brick")
    (testing "a stand whose walk failed is not proposed again"
      (let [blocked (assoc w :plan/blacklist #{[15 64 8]})
            t (blaze/fortress-search blocked)]
        (is (= :tunnel-to (:intent/kind t)))
        (is (= [15 63 8] (:intent/target t)) "the brick, by the fortress tunnel")
        (is (not= :walk (:intent/kind (blaze/corridor-walk blocked))))))))

(deftest hurt-or-wary-bot-takes-cover-from-active-spawner-and-never-rests-in-the-open
  ;; beside a live spawner, resting in the open leads to fireball death (probe 40597529086)
  (let [sp [11 64 10]
        pillar {[6 64 8] (blocks/default-state :stone) [6 65 8] (blocks/default-state :stone)}
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column pillar)})
              (memory/observe sp blaze/spawner))]
    (testing "in the open by spawner under 14 health: takes cover rather than resting"
      (let [wary (assoc w :player/health 12.0)
            i (next-intent wary)]
        (is (blaze/in-open? wary))
        (is (= :walk (:intent/kind i)))
        (is (= :cover (:intent/for i)))
        (is (= 5 (first (:intent/target i))))))
    (testing "once in cover out of spawner sight: rests"
      (let [in-cover (assoc w :player/pos [5.5 64.0 8.5] :player/health 12.0)]
        (is (not (blaze/in-open? in-cover)))
        (is (= blaze/rest-intent (next-intent in-cover)))))))

(deftest a-struck-piglin-is-backed-away-from-and-a-rest-beside-it-ends
  ;; blaze-wall-b run 1 rested at 13 health (wary, not yet hurt) after the blaze fight yielded,
  ;; while zombified piglins 126 and 144 were hitting. :rest was chosen three times in two
  ;; seconds (bot.log 03:17:01, 03:17:02, 03:17:03) and the run died.
  (let [pig (entity :zombified_piglin [11.0 64.0 8.5] :entity/health 20.0)
        w (assoc (standing {7 pig})
                 :player/health 13.0
                 :player/food 20
                 :player/attacker 7
                 :player/struck-at 1000
                 :time/now 1500
                 :plan/goals #{:blaze-rods})]
    (is (= {:intent/kind :back-off :intent/target 7} (next-intent w))
        "the blaze row itself does not rest beside the strike")
    (is (= :defend (:goal/id (plan/choose w plan/goals))) "defend is asked first and backs away")
    (let [resting (assoc w :plan/intent (assoc blaze/rest-intent :intent/status :active))
          tick (fn [w] (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1500}))]
      (is (= :done (:intent/status (:plan/intent (tick resting))))
          "a rest already running ends when the strike is fresh"))))

(deftest rest-completes-when-healthy-and-food-cannot-heal-further
  ;; issue #262 (sim R1/17): hp 14.8, food 17, no food held: heals? is false, rest must finish
  (let [w (assoc (standing {})
                 :player/health 14.8
                 :player/food 17
                 :time/now 1000
                 :plan/intent (assoc blaze/rest-intent :intent/status :active))
        tick (fn [w] (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))]
    (is (not (blaze/heals? w)))
    (is (= :done (:intent/status (:plan/intent (tick w)))))))

(deftest rest-360-cannot-heal-so-it-ends-and-food-is-taken
  ;; probe 40634461444, sim R1/17, rest id 360: health 9.3, food 17, nothing edible held,
  ;; :intent/blazes #{}, from 72.78 min (since 4366550) to the 330 min stop. 9.3 is under
  ;; combat/retreat-health, so neither a blaze nor a hoglin is fought.
  (let [since 4366550
        w (assoc (standing {}) :player/health 9.3 :player/food 17 :time/now since)
        resting (assoc w
                       :plan/intent
                       (assoc blaze/rest-intent
                              :intent/status :active
                              :intent/since since
                              :intent/health 9.3
                              :intent/blazes #{}))
        tick (fn [w] (intent/run w (:plan/intent w) {:event/kind :tick :event/now since}))
        bare (next-intent w)]
    (is (not (blaze/heals? w)))
    (is (blaze/hurt? w))
    (is (not= :rest (:intent/kind bare)))
    (is (not= :fight (:intent/kind bare)))
    (is (= :done (:intent/status (:plan/intent (tick resting))))))
  (testing "a porkchop in sight is taken"
    (let [pork (entity :item [10.5 64.0 9.5] :entity/item (recipe/item-id :porkchop))
          w (assoc (standing {40 pork}) :player/health 9.3 :player/food 17)]
      (is (= {:intent/kind :collect-drops :intent/item :porkchop :intent/target [10 64 9]}
             (next-intent w)))))
  (testing "a stew the kit can make is made"
    (let [w (assoc (standing
                    {9 (item :bowl 1) 10 (item :red_mushroom 1) 11 (item :brown_mushroom 1)}
                    {})
                   :player/health 9.3
                   :player/food 17)]
      (is (= {:intent/kind :craft :intent/recipe :mushroom_stew :intent/window :inventory}
             (next-intent w)))))
  (testing "a hoglin under retreat-health is not approached"
    (let [hog (entity :hoglin [14.5 64.0 8.5] :entity/health 20.0)
          w (assoc (standing {5 hog}) :player/health 9.3 :player/food 17)
          i (next-intent w)]
      (is (not= :hunt (:intent/for i)))
      (is (not= 5 (:intent/target i)))))
  (testing "at or above retreat-health the same hoglin is hunted"
    (let [hog (entity :hoglin [14.5 64.0 8.5] :entity/health 20.0)
          w (assoc (standing {5 hog}) :player/health 12.0 :player/food 17)]
      (is (= {:intent/kind :walk :intent/target [14 64 8] :intent/for :hunt} (next-intent w)))))
  (testing "crimson ground is walked only at or above retreat-health"
    (let [nylium (blocks/default-state :crimson_nylium)
          floor (fn [w] (assoc w :world/chunks {[0 0] (world/column {[12 63 8] nylium})}))
          low (floor (assoc (standing {}) :player/health 9.3 :player/food 17))
          ok (floor (assoc (standing {}) :player/health 12.0 :player/food 17))]
      (is (not= [12 64 8] (:intent/target (next-intent low))))
      (is (= {:intent/kind :walk
              :intent/target [12 64 8]
              :intent/for :food
              :intent/max-nodes blaze/corridor-nodes}
             (next-intent ok)))))
  (testing "a watched void retreat is still a rest when it cannot heal"
    (let [near (entity :blaze [13.5 65.0 8.5] :entity/health 20.0)
          air (blocks/default-state :air)
          lip (blocks/default-state :nether_bricks)
          void (into {[7 64 8] lip} (for [x [5 6] y (range 60 64)] [[x y 8] air]))
          w (assoc (standing {7 near})
                   :player/health 8.0
                   :player/food 16
                   :world/chunks {[0 0] (world/column void)})]
      (is (combat/void-retreat? w (get-in w [:world/entities 7])))
      (is (not (blaze/heals? w)))
      (is (= blaze/rest-intent (next-intent w))))))

(deftest the-stage-starts-provisioned-and-a-bot-that-cannot-heal-waits-for-food
  ;; sim R1/17 (probe 40634461444): health 9.3, food 17, nothing edible, blazes watching. A
  ;; healthy bot takes the food it can before the stage; a hurt one with none says so.
  (let [hog (entity :hoglin [14.5 64.0 8.5] :entity/health 20.0)
        pork (item :cooked_porkchop 1)]
    (testing "healthy, nothing edible, a hoglin near: the hoglin is hunted before the stage"
      (is (= {:intent/kind :walk :intent/target [14 64 8] :intent/for :hunt}
             (next-intent (standing {} {5 hog})))))
    (testing "the reserve held: the hoglin is left"
      (is (not= :hunt (:intent/for (next-intent (standing {9 (assoc pork :count 4)} {5 hog}))))))
    (testing "hurt under retreat-health, cannot heal, nothing to take, no portal on record: no wait"
      (let [w (assoc (standing {}) :player/health 9.3 :player/food 17)]
        (is (not= {:plan/wait :no-food} (next-intent w)))))
    (testing "the same with a lit portal on record: home through it, for food"
      (let [portal (blocks/default-state :nether_portal)
            w (-> (standing {})
                  (assoc :player/health 9.3 :player/food 17)
                  (memory/observe [3 64 8] portal))]
        (is (= blaze/portal-states (blocks/states-of :nether_portal)))
        (is (= [3 64 8] (blaze/home-portal w)))
        (is (= {:intent/kind :portal-home} (next-intent w)))
        (testing "healthy, the portal is not the way"
          (is (not= :portal-home
                    (:intent/kind (next-intent
                                   (assoc w :player/health 20.0 :player/food 20))))))))))

(deftest a-tunnel-that-ended-where-it-began-is-not-chosen-again
  ;; probe 40640225673, sim R1/1: tunnels to one stand, each :done at the step cap from the cell
  ;; the last ended in. And blaze-race-d job 5 (story-brd5): a done tunnel is not stalled for ending
  ;; where the feet are now, or each target was left the moment its tunnel ended
  (let [sp [12 70 8]
        w (-> (standing {})
              (memory/observe sp blaze/spawner))
        feet (intent/feet w)
        first-stand (:intent/target (blaze/to-spawner w sp))
        done
        (fn [w id target ended-at]
          (memory/remember-intention
           w
           {:intent/id id :intent/kind :tunnel-to :intent/target target :intent/ended-at ended-at}
           :done))
        once (done w 1 first-stand feet)
        twice (done once 2 first-stand feet)
        moved (done once 2 first-stand [3 64 3])]
    (is (= #{} (blaze/stalled once))
        "one done tunnel ended where the feet are: that is where it ends")
    (is (= #{first-stand} (blaze/stalled twice)) "two in a row ended in one cell: no progress")
    (is (= #{} (blaze/stalled moved)) "the second ended elsewhere: progress")
    (is (= first-stand (:intent/target (blaze/to-spawner once sp))) "the target is kept")
    (is (not= first-stand (:intent/target (blaze/to-spawner twice sp))) "another when stalled")
    (testing "a stalled stand stays so past other legs (blaze-race-w job 5: a search leg between)"
      (let [between (-> twice
                        (done 3 [90 60 90] [5 64 5])
                        (done 4 [91 60 91] [6 64 6])
                        (done 5 [92 60 92] [7 64 7])
                        (done 6 [93 60 93] [8 64 8]))]
        (is (contains? (blaze/stalled between) first-stand) "four dones later it is still stalled")
        (is (not= first-stand (:intent/target (blaze/to-spawner between sp))))))
    (testing "the tunnel to the stand bridges a cavern (landing 5: a column in open air)"
      (is (true? (:intent/bridge? (blaze/to-spawner w sp)))))))

(deftest a-corridor-with-no-route-tunnels-to-the-brick
  ;; probe 40634456698, sim R2/12, snapshot at 101.00 min (t 6060000): feet [979 69 450], the
  ;; nearest brick [984 72 455] 7 blocks off, intent :tunnel-to that brick. The stand is
  ;; standable and the chunk holds no route to it. Twelve :walk :no-path failures then hit
  ;; plan/max-unwalkable (6,095,500 to 6,099,700 ms).
  (let [netherrack (blocks/default-state :netherrack)
        brick (blocks/default-state :nether_bricks)
        air (blocks/default-state :air)
        ;; local cells of chunk [61 28]: world x = 976 + lx, world z = 448 + lz
        blocks
        {[3 68 2] netherrack [3 69 2] air [3 70 2] air [8 72 7] brick [8 73 7] air [8 74 7] air}
        w (-> (standing {})
              (assoc :player/pos [979.5 69.0 450.5]
                     :time/now 6060000
                     :plan/reached #{:fortress}
                     :world/chunks {[61 28] (world/column blocks)})
              (memory/observe [984 72 455] brick))
        stand [984 73 455]
        walk {:intent/kind :walk
              :intent/target stand
              :intent/for :corridor
              :intent/max-nodes blaze/corridor-nodes}
        tunnel {:intent/kind :tunnel-to
                :intent/target [984 72 455]
                :intent/steps fortress/steps-per-tunnel
                :intent/bridge? true}]
    (is (path/standable? w stand))
    (is (not (intent/routable? w walk)))
    (is (= tunnel (blaze/corridor-walk w)))
    (is (= tunnel (next-intent w)))))

(deftest when-all-known-bricks-visited-the-bot-extends-corridor-from-fortress-bounds
  ;; issue #262 (probe 40602224561, sim R2/12 s2): all bricks on record visited; instead of
  ;; resuming Nether spiral search and tunneling 430 blocks away, search extends corridors along
  ;; fortress axes from the bounds
  (let [brick (blocks/default-state :nether_bricks)
        w (-> (standing {})
              (assoc :plan/reached #{:fortress})
              (memory/observe [10 64 10] brick)
              (memory/observe [20 64 10] brick)
              (update
               :world/facts
               #(d/db-with
                 %
                 [{:intention/kind :walk :intention/event :done :intention/target [10 65 10]}
                  {:intention/kind :walk :intention/event :done :intention/target [20 65 10]}]))
              (assoc :player/pos [15.5 65.0 10.5]))
        i (next-intent w)]
    (is (= :tunnel-to (:intent/kind i)))
    (is (not (:intent/search i)) "never runs the Nether spiral search when at fortress")
    (is (int? (second (:intent/target i))))))

(def pocket-walls
  "The cell [8 64 8] shut east, south and north at the feet and the head, roofed, open west."
  (into {}
        (for [c [[9 64 8] [9 65 8] [8 64 9] [8 65 9] [8 64 7] [8 65 7] [8 66 8]]]
          [c (blocks/default-state :stone)])))

(def pocket-site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig []})

(defn in-pocket
  "w standing in the pocket cell [8 64 8] (the feet of standing are there) over pocket-walls, the
   site held."
  [w]
  (assoc w :world/chunks {[0 0] (world/column pocket-walls)} :pocket/site pocket-site))

(deftest the-living-flags-byte-is-read-off-the-bots-own-entity
  (let [me (assoc (standing {}) :player/entity-id 27)
        md (fn [w m]
             (game/step w
                        {:event/kind :packet
                         :event/packet {:packet/name :set-entity-data :entity-id 27 :metadata m}}))]
    (is (true? (:player/using-item? (md me {8 1}))))
    (is (false? (:player/using-item? (md (md me {8 1}) {8 0}))))
    (is (nil? (:player/using-item? (md me {0 1}))))))

(deftest ignitions-are-counted-and-a-second-within-thirty-seconds-sends-the-bot-to-its-pocket
  ;; blaze-race-k: ten deaths in ten, on_fire 5 to 64 a run; a human banks health between fights
  (let [me (assoc (standing {}) :player/entity-id 27 :time/now 1000)
        md (fn [w m now]
             (game/step (assoc w :time/now now)
                        {:event/kind :packet
                         :event/packet {:packet/name :set-entity-data :entity-id 27 :metadata m}}))
        once (-> me
                 (md {0 1} 1000)
                 (md {0 1} 1500))
        twice (-> once
                  (md {0 0} 6000)
                  (md {0 1} 12000))
        far (-> once
                (md {0 0} 6000)
                (md {0 1} 50000))]
    (is (= [1000] (:player/ignitions once)) "a flag that stays on is one ignition")
    (is (= [1000 12000] (:player/ignitions twice)))
    (is (blaze/reignited? twice))
    (is (not (blaze/reignited? far)) "forty-nine seconds apart: not")
    (is (blaze/refuge-needed? (-> me
                                  (assoc :player/health 11.0)
                                  (assoc-in [:world/entities 7]
                                            (entity :blaze [3.5 65.0 8.5] :entity/health 20.0))))
        "under low-health with a blaze about")
    (is (not (blaze/refuge-needed? (assoc me :player/health 11.0)))
        "under it with none: eat and rest")
    (is (not (blaze/refuge-needed? me)))))

(deftest burning-or-outnumbered-the-bot-takes-the-pocket
  ;; blaze-race-g and -i, 20 deaths: nine fought or backed off in the open while burning or
  ;; under a volley (g race 1 backed off x3 in 3 s, burning, from 17 hp; i race 4 burned seven
  ;; times in 75 s fighting from the room)
  (let [open (-> (standing {7 (entity :blaze [5.5 65.0 12.5] :entity/health 20.0)})
                 (assoc :player/pos [5.5 64.0 8.5]
                        :world/chunks {[0 0] (world/column pocket-walls)}))
        i (next-intent (assoc open :player/on-fire? true))]
    (is (= :pocket (:intent/kind i)) "burning, in sight of a blaze: the pocket, not a fight")
    (is (= [8 64 8] (:pocket/cell (:intent/site i))))
    (is (not= :pocket (:intent/kind (next-intent open)))
        "healthy, not burning, one watching: as before")
    (testing "a volley sends it there too"
      (let [three {7 (entity :blaze [5.5 65.0 12.5] :entity/health 20.0)
                   8 (entity :blaze [4.5 65.0 12.5] :entity/health 20.0)
                   9 (entity :blaze [6.5 65.0 12.5] :entity/health 20.0)}
            w (assoc open :world/entities three)]
        (is (= :pocket (:intent/kind (next-intent w))))))))

(deftest in-the-pocket-it-holds-until-the-fire-is-out-and-the-health-is-up
  (let [far (entity :blaze [3.5 65.0 8.5] :entity/health 20.0)
        hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        w (in-pocket (standing {7 far}))]
    (is (= {:intent/kind :walk :intent/target [9 64 7] :intent/for :cover}
           (select-keys (next-intent (assoc w :player/on-fire? true))
                        [:intent/kind :intent/target :intent/for]))
        "burning with a blaze in line: out of its sight first (hide), the hold where there is none")
    (is (= {:intent/kind :pocket-hold :intent/heal-to 20.0}
           (next-intent (assoc w :player/health 14.0)))
        "under engage-health")
    (is (not= :pocket-hold (:intent/kind (next-intent w))) "healthy and not burning: no hold")
    (testing "a blaze in reach is struck, burning or not"
      (let [w (in-pocket (standing {7 hand}))]
        (is (= :fight (:intent/kind (next-intent w))))
        (is (= :fight (:intent/kind (next-intent (assoc w :player/on-fire? true)))))))
    (testing "a blaze past reach is not walked out to"
      (let [w (in-pocket (standing {7 (entity :blaze [4.5 65.0 8.5] :entity/health 20.0)}))]
        (is (not= :fight (:intent/kind (next-intent w))))))
    (testing "the hold ends when the fire is out and the health up, or a blaze is in reach"
      (let [i {:intent/kind :pocket-hold :intent/heal-to 20.0}
            run (fn [w]
                  (:plan/intent (intent/run (assoc w :plan/intent i :time/now 1000)
                                            i
                                            {:event/kind :tick :event/now 1000})))]
        (is (not (intent/done? (run (assoc w :player/on-fire? true)))))
        (is (intent/done? (run w)))
        (is (intent/done? (run (in-pocket (standing {7 hand})))))))))

(deftest the-walk-out-of-a-fire-ends-standing-in-its-cell-not-near-it
  ;; blaze-race-k race 1 at 19:42:57.9: :walk [2346 57 2286] x190 in 9.5 s from the feet at
  ;; [2344.5 57 2286.7], 30 in_fire hits, dead at 19:43:07: the walk to a cell two off, within
  ;; reach of the eye, ended in its first tick, standing in the fire
  (let [fire (blocks/default-state :fire)
        burning (-> (standing {})
                    (assoc :world/chunks {[0 0] (world/column {[8 64 8] fire})}
                           :player/on-fire? true))
        i (next-intent burning)
        run (intent/run (assoc burning :plan/intent i :time/tick 5)
                        i
                        {:event/kind :tick :event/now 1000})]
    (is (= :walk (:intent/kind i)))
    (is (= :fire-out (:intent/for i)))
    (is (contains? intent/standing-walks :fire-out))
    (is (not (intent/done? (:plan/intent run)))
        "not done in its first tick: the feet are in the fire")))

(deftest a-bot-sealed-in-with-a-fire-at-its-feet-punches-it-out
  ;; blaze-race-k race 1 sealed itself in and stood 15 s in the fire lit in its cell: no clear
  ;; cell to walk to, so nothing; the fire is dug out
  (let [fire (blocks/default-state :fire)
        stone (blocks/default-state :stone)
        shut (into {[8 64 8] fire [8 66 8] stone}
                   (for [x [7 8 9] z [7 8 9] y [64 65] :when (not= [x z] [8 8])] [[x y z] stone]))
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column shut)} :player/on-fire? true))]
    (is (= {:intent/kind :dig :intent/target [8 64 8]} (next-intent w)))))

(deftest hurt-in-an-open-pocket-with-a-blaze-in-reach-the-opening-is-sealed
  ;; blaze-race-k geared 2: :pocket and :back-off 658 alternated every 0.4 s for 8 s, burning,
  ;; from 8.2 hp to 0.9: a blaze at the opening hits for 3.6 a second and there is no cover to run to
  (let [hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        w (-> (in-pocket (standing {7 hand}))
              (assoc-in [:player/inventory 9] (item :cobblestone 62))
              (assoc-in [:player/inventory 10] (item :iron_pickaxe 1))
              (assoc :player/health 8.0))]
    (is (= {:intent/kind :seal} (next-intent w)))
    (testing "healthy it is struck, as before"
      (is (= :fight (:intent/kind (next-intent (assoc w :player/health 20.0))))))))

(deftest a-burning-bot-in-the-tunnel-is-cut-for-its-refuge
  ;; blaze-race-k geared 4: :tunnel-to went on 35 s at [2344.6 60 2284.5] while the bot burned from
  ;; 20 hp to 0; nothing cut it: its refuge was not among what interrupts?
  (let [w (-> (standing {7 (entity :blaze [3.5 65.0 8.5] :entity/health 20.0)})
              (assoc :player/on-fire? true))
        tunnel {:intent/kind :tunnel-to :intent/target [2346 60 2281]}]
    (is (plan/interrupts? w row tunnel))
    (is (not (plan/interrupts? (assoc w :player/on-fire? false) row {:intent/kind :pocket-hold}))
        "the hold is the answer, not cut for itself")
    (is (= :pocket (:intent/kind (next-intent w))) "the cell stood in is sealed in place")
    (is (true? (:pocket/here? (:intent/site (next-intent w)))))))

(def cobble (blocks/default-state :cobblestone))

(defn sealed-walls
  "pocket-walls with the opening sealed: cobblestone west of the cell at the feet and the head."
  []
  (assoc pocket-walls [7 64 8] cobble [7 65 8] cobble))

(deftest an-open-pocket-under-fire-is-sealed-and-a-sealed-one-is-held-then-opened
  ;; blaze-race-j race 3 at 17:24:36 (:pocket-hold, burning, the nearest of five blazes at 4.0 against a
  ;; reach of 3.2, 20 hp to 1.6 in 18 s) and race 1 at 17:32:51 (:pocket-hold, nearest 5.4, 37 s, hp 20 to
  ;; 0.5): holding open under fire through the opening was the death
  (let [line (entity :blaze [4.5 65.0 8.5] :entity/health 20.0)
        loaded (fn [w]
                 (-> w
                     (assoc-in [:player/inventory 9] (item :cobblestone 62))
                     (assoc-in [:player/inventory 10] (item :iron_pickaxe 1))))
        base (-> (standing {7 line})
                 in-pocket
                 loaded)
        burning (assoc base :player/on-fire? true)]
    (testing "burning, a blaze in line out of reach, filler held: a block in the opening"
      (is (= {:intent/kind :seal} (next-intent burning))))
    (testing "struck within press-ms by a blaze in line out of reach, not burning: the same"
      (is (= {:intent/kind :seal}
             (next-intent (assoc base :player/struck-at (- (:time/now base 0) 1000))))))
    (testing "no filler held: out of the line (hide), else the old hold in the open"
      (is (= :walk
             (:intent/kind (next-intent (assoc (standing {7 line})
                                               :player/on-fire? true
                                               :pocket/site pocket-site
                                               :world/chunks
                                               {[0 0] (world/column pocket-walls)}))))))
    (testing "a blaze in reach is struck, not sealed out"
      (is (= :fight
             (:intent/kind (next-intent (-> (standing {7 (entity :blaze [6.5 65.0 8.5]
                                                                 :entity/health 20.0)})
                                            in-pocket
                                            loaded
                                            (assoc :player/on-fire? true)))))))
    (testing "sealed and burning: the hold, with the blaze at the block no target"
      (let [sealed (-> base
                       (assoc :world/chunks {[0 0] (world/column (sealed-walls))}
                              :pocket/site (assoc pocket-site :pocket/placed [[7 64 8] [7 65 8]])
                              :player/on-fire? true)
                       (assoc-in [:world/entities 7]
                                 (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)))]
        (is (pocket/sealed? sealed (:pocket/site sealed)))
        (is (= :pocket-hold (:intent/kind (next-intent sealed))))
        (is (nil? (blaze/fightable-blaze sealed)) "a blaze at the seal is not fought")
        (testing "fire out, health up, the last hit old: the seal is opened"
          (is (= {:intent/kind :unseal}
                 (next-intent (assoc sealed :player/on-fire? false :player/struck-at -1000000)))))
        (testing "fire out but struck a second ago: still held"
          (is (= :pocket-hold
                 (:intent/kind (next-intent (assoc sealed
                                                   :player/on-fire? false
                                                   :player/struck-at (:time/now sealed 0)))))))))))

(deftest a-pocket-that-cannot-be-reached-is-sealed-where-the-bot-stands-not-failed
  ;; blaze-race-j geared 4 at 17:14:57 (:pocket failed :no-path x3 in 0.4 s at 17 hp, then the
  ;; plan failed) and race 2 at 17:35:38
  (let [site {:pocket/cell [3 64 3] :pocket/stand [2 64 3] :pocket/dig [[3 64 3] [3 65 3]]}
        w (assoc (standing {}) :plan/intent {:intent/kind :pocket :intent/site site})
        out (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000})
        i (:plan/intent out)]
    (is (intent/done? i) "the site is no pocket here: done, not failed")
    (is (not (intent/failed? i)))
    (is (= [8 64 8] (:pocket/cell (:pocket/site out)))
        "the cell the feet stand in is held to be sealed")
    (is (true? (:pocket/here? (:pocket/site out))))
    (testing
      "and there the openings are the cells to seal, every open side at the feet and the head; the roof over open air has no foundation but the head cell, and is none"
      (is (= #{[7 64 8] [9 64 8] [8 64 7] [8 64 9] [7 65 8] [9 65 8] [8 65 7] [8 65 9]}
             (set (pocket/seal-cells out [8 64 8]))))
      (is (contains? (set (pocket/wanted-seal-cells out [8 64 8])) [8 66 8])))))

(def rock
  "Solid rock two thick all round a tunnel along z 8 from x 4 to 8 at the feet and the head, roofed,
   plugged at x 3."
  (into {}
        (for [x (range 3 9)
              y [64 65 66]
              z (range 5 12)
              :when (or (= y 66) (not= z 8) (= x 3))]
          [[x y z] (blocks/default-state :stone)])))

(deftest the-pocket-is-dug-ahead-of-the-tunnels-last-blocks
  ;; blaze-race-j: 6 of 10 runs took the first volley at the tunnel's end at +150 s with 4 blazes
  ;; within 8 blocks; the pocket is made ready when the spawner is within pocket-approach
  (let [w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column rock)} :player/pos [5.5 64.0 8.5])
              with-spawner)
        tunnel {:intent/kind :tunnel-to :intent/target [10 64 8]}
        i (next-intent w)]
    (is (= :pocket (:intent/kind i)))
    (is (true? (:intent/prepare? i)) "dug, not entered")
    (is (plan/interrupts? w row tunnel) "the running tunnel is cut for it")
    (testing "a pocket held and valid: the tunnel goes on"
      (let [held (assoc w :pocket/site (:intent/site i))]
        (is (not (plan/interrupts? (assoc held :world/chunks {[0 0] (world/column rock)})
                                   row
                                   tunnel)))))
    (testing "far from the spawner: not yet"
      (is (not (plan/interrupts? (assoc w :world/facts (memory/empty-facts)) row tunnel))))))

(deftest a-fireball-in-flight-is-dodged-whatever-the-bot-was-doing
  ;; blaze-race-l race 2 at +210.4 s (fireball 542, hit 450 ms after its spawn): the planner steps
  ;; out of the line, and a running tunnel or camp is cut for it
  (let [fb {:entity/type (blocks/entity-types :small_fireball)
            :entity/pos [11.7 66.4 6.0]
            :entity/vel [-0.064 -0.020 0.074]
            :entity/seen-at 0}
        w (-> (standing {7 fb})
              (assoc :player/pos [8.6 64.0 8.5] :time/now 0))
        i (next-intent w)]
    (is (= :dodge (:intent/kind i)))
    (is (plan/interrupts? w row {:intent/kind :camp :intent/spawner [15 64 15]}))
    (is (not (plan/interrupts? w row {:intent/kind :dodge :intent/target (:intent/target i)}))
        "the dodge is not cut for itself")
    (is (not= :dodge (:intent/kind (next-intent (assoc w :world/entities {}))))
        "none in flight: no dodge")))

(deftest a-walk-to-a-spawner-already-in-reach-is-not-chosen-every-tick
  ;; snapshot test/issues/514/b10231346-R4-21-1.edn.gz (churn-guard scan): the bot at [540.5 57 201.5],
  ;; the spawner at [544 57 203] 3.96 from the eye, its top out of sight: :walk [544 57 203] ended in
  ;; its first tick (within reach) with at-spawner? still false, and was chosen again every tick.
  ;; The same geometry from the fixture's floor: the spawner at [11 64 10], a pillar between
  (let [sp [11 64 10]
        stone (blocks/default-state :stone)
        pillar (into {} (for [y [64 65 66]] [[10 y 9] stone]))
        w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column pillar)})
              (memory/observe sp blaze/spawner))
        walk {:intent/kind :walk :intent/target sp}
        d (physics/distance (game/eye w) (physics/centre sp))]
    (is (blaze/walkable? w sp))
    (is (blaze/at-spawner? w sp)
        "within reach of the eye with the spawner walkable: where it camps")
    (is (intent/arrived? w walk d) "the walk would be done at once")
    (is (not= walk (next-intent w)) "so the walk is not chosen: the act goes on to the camp")))

(deftest a-rod-within-a-step-is-picked-up-before-the-next-swing
  ;; blaze-race-m race 4 (22 min, 26 kills): about 11 rods dropped and at most 4 held. Eight lay 2 to
  ;; 3 blocks from its camp until they despawned at 300 s; 39 :collect-drops were begun, each cut
  ;; within a second by the next :fight (six to eight blazes hovering in reach)
  (let [rod (entity :item [10.5 64.0 9.5] :entity/item blaze/rod)
        far-rod (entity :item [15.5 64.0 9.5] :entity/item blaze/rod)
        hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        collect {:intent/kind :collect-drops :intent/item :blaze_rod :intent/target [10 64 9]}]
    (is (= collect (next-intent (standing {40 rod 7 hand}))) "the rod, with a blaze in reach")
    (is (= :fight (:intent/kind (next-intent (standing {40 far-rod 7 hand}))))
        "five off: the swing")
    (is (not= collect (next-intent (assoc (standing {40 rod 7 hand}) :player/health 9.0)))
        "under danger-health (rod-grab-health) the refuge comes first")
    (testing "a running pickup is not cut for the blaze in reach, a camp is"
      (let [w (standing {40 rod 7 hand})]
        (is (not (plan/interrupts? w row collect)))
        (is (plan/interrupts? w row {:intent/kind :camp :intent/spawner [11 64 10]}))
        (is (plan/interrupts? (assoc w :world/entities {7 hand}) row collect)
            "no rod near: it is cut as any intent")))))

(deftest at-low-health-the-refuge-comes-before-a-dodge-and-no-dodge-cuts-it
  ;; blaze-race-m race 4 at 17:30:30 to 17:30:36: :fight 965, :dodge, :fight, :dodge, :pocket, :seal,
  ;; :dodge [2349 60 2282] (cutting the seal, two cells out of the pocket), :pocket, :seal, then
  ;; :back-off and a :pillar that failed :boxed, dead at hp 2.2
  (let [fb {:entity/type (blocks/entity-types :small_fireball)
            :entity/pos [11.7 66.4 6.0]
            :entity/vel [-0.064 -0.020 0.074]
            :entity/seen-at 0}
        hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        w (-> (standing {7 hand 8 fb})
              (assoc :player/pos [8.6 64.0 8.5] :time/now 0))]
    (is (= :dodge (:intent/kind (next-intent w))) "tick 1, hp 20: step out of the line")
    (let [low (assoc w :player/health 11.4 :player/on-fire? true)]
      (is (not= :dodge (:intent/kind (next-intent low))) "tick 2, hp 11.4 and alight: not a dodge")
      (is (= :pocket (:intent/kind (next-intent low)))
          "the refuge: the cell stood in, sealed there"))
    (testing "tick 3: a seal under way is not cut for the next fireball"
      (let [low (assoc w :player/health 8.0 :player/on-fire? true)]
        (is (not (plan/interrupts? low row {:intent/kind :seal})))
        (is (not (plan/interrupts? low row {:intent/kind :pocket})))))))

(deftest a-seal-that-cannot-be-laid-is-not-tried-again-and-the-bot-takes-cover
  ;; blaze-race-n jobs 2, 4 and 6: :pocket, :seal and :place flipped with :back-off every 0.2 to 0.5 s
  ;; while burning, :place failed :no-support, and the bot died at 8 hits taken
  (let [hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        w (-> (in-pocket (standing {7 hand}))
              (assoc-in [:player/inventory 9] (item :cobblestone 62))
              (assoc-in [:player/inventory 10] (item :iron_pickaxe 1))
              (assoc :player/health 8.0))
        stuck (assoc-in w [:pocket/site :pocket/unsealable?] true)]
    (is (= {:intent/kind :seal} (next-intent w)) "sealable: the seal")
    (is (not (pocket/can-seal? stuck (:pocket/site stuck))))
    (let [i (next-intent stuck)]
      (is (contains? #{:place :pillar :eat :dig} (:intent/kind i))
          "not sealable, a blaze in reach: combat/cover, a block laid or a pillar, never a fight")
      (is (not= :fight (:intent/kind i)) "no fight under danger-health (#577)")
      (is (not= :back-off (:intent/kind i)) "and not the back-off that flipped with the seal"))))

(deftest a-fight-that-cannot-reach-and-a-pickup-that-found-nothing-do-not-end-the-plan
  ;; blaze-race-p job 6 (6m24s, health 16, fifteen beef, full iron): :collect-drops :not-picked-up
  ;; twice and :fight 537 :unreachable were three strikes, and the plan failed :unreachable
  (let [active (plan/begin (standing {}) {:go/goals [:blaze-rods]} plan/goals)
        failed (fn [w kind reason]
                 (plan/fail-intent w
                                   {:intent/kind kind
                                    :intent/target (if (= kind :fight) 537 [10 64 9])
                                    :intent/reason reason}))
        w (-> active
              (failed :collect-drops :not-picked-up)
              (failed :collect-drops :not-picked-up)
              (failed :fight :unreachable))]
    (is (zero? (:plan/attempts w)) "no strike against the plan")
    (is (not= :failed (:plan/status w)))
    (is (contains? (:plan/blacklist w) 537) "the blaze is left alone")
    (testing "another kind of the row still counts"
      (is (= 1 (:plan/attempts (failed active :walk :stuck)))))))

(deftest sealed-in-with-no-pickaxe-is-never-chosen
  ;; blaze-race-p job 2: :unseal dug four placed blocks at 10000 ms each (44 s) with no pickaxe in
  ;; the inventory while fireballs came through the half-open pocket
  (let [hand (entity :blaze [4.5 65.0 8.5] :entity/health 20.0)
        w (-> (in-pocket (standing {7 hand}))
              (assoc-in [:player/inventory 9] (item :cobblestone 62))
              (assoc :player/on-fire? true))]
    (is (not= :seal (:intent/kind (next-intent w))) "filler held, no pickaxe: no seal")
    (is (= :seal
           (:intent/kind (next-intent (assoc-in w [:player/inventory 10] (item :iron_pickaxe 1)))))
        "with one it is")))

(deftest a-failure-about-one-target-is-no-strike-for-any-goal
  ;; plan/target-scoped-failures is data, not a blaze exception: the same fight and pickup under the
  ;; wood goal and the ender-pearl goal (an enderman out of reach) strike nothing and blacklist
  (doseq [goal [:wood :ender-pearl :blaze-rods]]
    (let [active (plan/begin (standing {}) {:go/goals [goal]} plan/goals)
          w (-> active
                (plan/fail-intent {:intent/kind :fight
                                   :intent/target 14
                                   :intent/reason :unreachable
                                   :intent/goal goal})
                (plan/fail-intent {:intent/kind :collect-drops
                                   :intent/target [3 64 3]
                                   :intent/reason :not-picked-up
                                   :intent/goal goal}))]
      (is (zero? (:plan/attempts w)) (str goal))
      (is (not= :failed (:plan/status w)) (str goal))
      (is (every? (:plan/blacklist w) [14 [3 64 3]]) (str goal))))
  (testing "a fight that failed for another reason still strikes"
    (is (= 1
           (:plan/attempts (plan/fail-intent
                            (plan/begin (standing {}) {:go/goals [:wood]} plan/goals)
                            {:intent/kind :fight :intent/target 14 :intent/reason :ghost}))))))

(deftest a-sealed-bot-that-cannot-heal-holds-until-over-low-health
  ;; blaze-race-p job 4 at 22:42:01 to 22:42:06: :unseal at 10.5 hp (hold-to was retreat-health, 10),
  ;; :seal at once (a blaze in reach under low-health, 12), four times 0.6 s apart, then dead
  (let [hand (entity :blaze [6.5 65.0 8.5] :entity/health 20.0)
        sealed (-> (in-pocket (standing {7 hand}))
                   (assoc-in [:player/inventory 9] (item :cobblestone 62))
                   (assoc-in [:player/inventory 10] (item :iron_pickaxe 1))
                   (assoc :world/chunks {[0 0] (world/column (sealed-walls))}
                          :pocket/site (assoc pocket-site :pocket/placed [[7 64 8] [7 65 8]])
                          :player/food 10
                          :time/now 100000
                          :player/struck-at 0))
        at (fn [hp] (assoc sealed :player/health hp))]
    (is (not (blaze/heals? sealed)) "no food and the bar is low: a rest cannot heal")
    (is (= 12.0 (blaze/hold-to sealed)))
    (is (not= :pocket-hold (:intent/kind (next-intent (at 10.5))))
        "10.5: no hold that gains nothing")
    (is (not= :unseal (:intent/kind (next-intent (at 10.5))))
        "and not opened: a blaze in reach seals it again")
    (is (not= :pocket-hold (:intent/kind (next-intent (at 6.2))))
        "z6 landing 2: hp 6.2, food 17, none held")
    (is (not= :pocket-hold (:intent/kind (next-intent (at 11.9)))))
    (is
     (= {:intent/kind :unseal} (next-intent (at 12.2)))
     "at low-health, 12.2 (x job 4's stuck health): the seal opens, no wait for a meal that is gone")
    (is (= {:intent/kind :unseal} (next-intent (at 13.5))))))

(deftest the-pocket-is-not-wanted-for-the-wary-and-a-near-rod-is-picked-up-from-the-hold
  ;; blaze-race-p job 4 at 22:41:40: :collect-drops [2346 58 2285] and :pocket alternated every
  ;; 0.35 s for 13 s (37 times) at 12 to 18 hp with blazes watching, and five rods despawned
  (let [rod (entity :item [10.5 64.0 9.5] :entity/item blaze/rod)
        far (entity :blaze [3.5 65.0 8.5] :entity/health 20.0)
        w (-> (standing {40 rod 7 far})
              (assoc :player/health 15.0))]
    (is (seq (blaze/watchers w)))
    (is (not (blaze/refuge-needed? w)) "wary (under 18) with blazes watching is not hurt")
    (is (= :collect-drops (:intent/kind (next-intent w))) "the rod, not the pocket")
    (testing "in the pocket, healing, the near rod is still gone for"
      (let [held (in-pocket (assoc w :player/food 20))]
        (is (= :collect-drops (:intent/kind (next-intent held))))))
    (testing "burning, the pocket first"
      (is (not= :collect-drops
                (:intent/kind (next-intent (assoc (in-pocket w) :player/on-fire? true))))))))

(deftest a-pocket-is-made-ahead-once-in-five-minutes
  ;; blaze-race-r jobs 1 and 4: :pocket and :tunnel-to alternated every 3 s, each :pocket digging two
  ;; blocks beside the tunnel and each tunnel step opening the last one's other side: ten pockets,
  ;; 250 netherrack, the pickaxe worn out in 4 minutes and the run ended :boxed / :no-path
  (let [w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column rock)}
                     :player/pos [5.5 64.0 8.5]
                     :time/now 1000000)
              with-spawner)
        sp spawner-at]
    (is (some? (blaze/prepare-intent w sp)) "none begun: one is")
    (is (nil? (blaze/prepare-intent (assoc w :pocket/prepared-at 900000) sp))
        "begun 100 s ago: not again")
    (is (some? (blaze/prepare-intent
                (assoc w :pocket/prepared-at (- 1000000 blaze/prepare-cooldown-ms 1))
                sp))
        "after the cooldown, one more")
    (is (= 14.0 blaze/pocket-approach))))
(deftest a-bot-that-cannot-heal-goes-home-for-food-not-to-a-rest-without-end
  ;; race lane 4 (run 40980342194) s1 r01, s2 r11 and s2 r16: one :rest for 200, 164 and 200 minutes at
  ;; health 10.9, 10.2 and 9.0, food 17 (natural regeneration needs 18), 830 / 354 / 394 cobblestone and
  ;; not a bite to eat; the stage had no way to food
  (let [cobble (item :cobblestone 64)
        portal [20 64 8]
        w (-> (standing {1 cobble} {})
              (assoc :player/health 9.0 :player/food 17)
              (memory/observe portal (blocks/default-state :nether_portal)))]
    (is (not (blaze/heals? w)) "food 17 and nothing to eat")
    (is (= {:intent/kind :portal-home} (next-intent w)) "the way home for food, not a :rest")
    (testing "a meal held: eaten, and the rest heals"
      (is (= :eat
             (:intent/kind (next-intent
                            (assoc-in w [:player/inventory 2] (item :cooked_beef 4)))))))
    (testing "food at 18: the rest heals, so it is the rest"
      (is (= {:intent/kind :rest} (next-intent (assoc w :player/food 18)))))
    (testing "no portal on record: a rest that cannot heal is never chosen (probe 40634461444)"
      (is (not= :rest (:intent/kind (next-intent (assoc w :world/facts (memory/empty-facts)))))))))

(deftest a-spawner-whose-walk-failed-is-tunnelled-to-not-given-up
  ;; race lane 4 s1 r15: `:walk [544 57 203]` failed :no-path at the fortress, the blacklist took the
  ;; spawner, and the bot searched far spirals for 4 hours; the stand beside it was open
  (let [sp [11 64 10]
        w (-> (standing {})
              (memory/observe sp blaze/spawner)
              (assoc :plan/blacklist #{sp}))]
    (is (= sp (blaze/nearest-spawner w)) "kept: a stand beside it is open")
    (is (not (blaze/walkable? w sp)) "a walk to it failed, so it is not walked to again")
    (is (= :tunnel-to (:intent/kind (blaze/to-spawner w sp))) "tunnelled to its stand instead")
    (testing "a spawner with every stand blacklisted is given up"
      (is (nil? (blaze/nearest-spawner (update w :plan/blacklist into (blaze/cells-beside sp))))))))

(deftest the-blaze-row-takes-the-table-it-placed-along
  ;; race lane 4 (run 40980342194) s1 r15: a pickaxe made at a placed table, the tunnel went on and
  ;; the table stayed; with 598 cobblestone and 13 sticks and no table the next pickaxe could not be
  ;; made, and 4,100 netherrack were dug by hand at 2.05 s each
  (let [pick {:item (recipe/item-id :stone_pickaxe) :count 1}
        table {:item (recipe/item-id :crafting_table) :count 1}
        sp [40 64 8]
        w (-> (standing {1 pick} {})
              (memory/observe [9 64 8] (blocks/default-state :crafting_table))
              (memory/observe sp blaze/spawner))]
    (is (= {:intent/kind :dig :intent/target [9 64 8] :intent/for :take-table} (next-intent w))
        "the pickaxe made, the placed table within reach is dug before the tunnel goes on")
    (is (not= :take-table (:intent/for (next-intent (assoc-in w [:player/inventory 2] table))))
        "a table held already: on to the spawner")))

(deftest a-camp-in-the-pocket-that-no-blaze-comes-to-moves-nearer-or-stays
  ;; blaze-race-t job 3: alive 54 min, :camp 39m48s and :ambush 10m31s at [2338 60 2291] with six to
  ;; eight blazes hovering 9.4 to 10 blocks off the whole time and one attack sent: idle? was true
  ;; and blaze-race-u jobs 3 and 6 then crossed the room to the spawner and died in 5 to 8 s; a pocket nearer is made, or the camp stays
  (let [camp (fn [w n] (memory/remember-intention w {:intent/id n :intent/kind :camp} :done))
        base (-> (standing {})
                 in-pocket
                 with-spawner
                 (assoc :time/now 100000)
                 (memory/remember-intention {:intent/id 98 :intent/kind :pocket} :done))
        idle (-> base
                 (camp 1)
                 (camp 2)
                 (camp 3))]
    (is (not (blaze/idle? base)))
    (is (= :camp (:intent/kind (next-intent base))) "fresh: camped in the pocket")
    (is (blaze/idle? idle))
    (is (= :camp (:intent/kind (next-intent idle)))
        "idle with no pocket nearer the spawner: stays, never across the room to it")
    (is (nil? (blaze/advance-intent idle spawner-at)) "no site nearer in this one-pocket world")
    (testing
      "rested in the pocket sortie-rest-ms with no fight: out toward the spawner (hit and run)"
      (let [rested (assoc idle :time/now (+ 100000 blaze/sortie-rest-ms 1))]
        (is (blaze/sortie-due? rested))
        (is (not= :camp (:intent/kind (next-intent rested))))))
    (testing "after a fight the pocket is camped again"
      (let [fought (memory/remember-intention idle {:intent/id 4 :intent/kind :fight} :done)]
        (is (= :camp (:intent/kind (next-intent fought))))))))

(deftest a-pocket-far-from-any-spawner-is-a-refuge-not-a-camp
  ;; blaze-race-u job 5: sealed in 13 s after landing, far from a spawner; the camp failed
  ;; :no-blaze three times and the plan failed
  (let [w (-> (standing {})
              in-pocket
              (assoc :time/now 100000))]
    (is (true? (blaze/wakes-spawner? w spawner-at)) "a spawner within 16 of the eye: camped")
    (is (false? (blaze/wakes-spawner? w [200 64 200])) "far: the pocket is a refuge")
    (is (false? (blaze/wakes-spawner? w nil)))))

(deftest a-pocket-is-judged-by-the-blazes-it-sees
  ;; blaze-race-w jobs 1, 2, 3, 6: 30 minutes in a pocket, 7 to 10 blazes hovering 9 blocks off, none
  ;; ever shot: the pocket sees the spawner's top, no blaze
  (let [line (entity :blaze [3.5 65.0 8.5] :entity/health 20.0)
        behind (entity :blaze [8.5 65.0 14.5] :entity/health 20.0)
        w (fn [e] (in-pocket (standing {7 e})))]
    (is (true? (blaze/sees-a-blaze? (w line) [8 64 8])) "a blaze in the line of the opening")
    (is (false? (blaze/sees-a-blaze? (w behind) [8 64 8])) "a blaze behind the wall")
    (is (false? (blaze/sees-a-blaze? (in-pocket (standing {})) [8 64 8])) "none")
    (testing "a mouth facing a room of them is a volley, not a pocket to move to"
      (let [many (in-pocket (standing (into {}
                                            (map-indexed (fn [i dz] [(+ 7 i)
                                                                     (entity :blaze
                                                                             [3.5 65.0 (+ 8.5 dz)]
                                                                             :entity/health 20.0)])
                                                         [0.0 0.2 -0.2 0.1]))))]
        (is (= 4 (blaze/count-blazes-seen many [8 64 8])))
        (is (false? (blaze/sees-a-blaze? many [8 64 8])))))))

(deftest a-rod-a-step-away-is-grabbed-down-to-danger-health-and-not-cut
  ;; blaze-race-x job 4: rod 607 lay 300 s with the bot 2.2 off, the pick-up cut twenty times
  (let [rod (entity :item [10.5 64.0 8.5] :entity/item blaze/rod)
        w (assoc (standing {40 rod}) :player/health 10.5)
        grab {:intent/kind :collect-drops :intent/item :blaze_rod :intent/target [10 64 8]}]
    (is (some? (blaze/near-rod w)) "10.5 hp, 2 blocks: gone for")
    (is (nil? (blaze/near-rod (assoc w :player/health 9.0))) "under danger-health: not")
    (is (true? (blaze/rod-grab? w grab)))
    (is (true? (blaze/rod-grab? w {:intent/kind :walk :intent/for :rod})))
    (is (false? (blaze/rod-grab? w {:intent/kind :walk :intent/for :cover})))
    (is (false? (blaze/rod-grab? (assoc w :player/health 9.0) grab)))))

(deftest a-bot-shot-from-range-lays-a-wall-toward-the-shooter
  ;; blaze-race-x job 5 and y job 5: at [2151.4 72 1757.5] fireballed by blazes at [2185 57 1731]
  ;; (45 off) for 4 minutes, 47-53 hits, 0 attacks, nothing within reach
  (let [far (entity :blaze [15.5 66.0 1.5] :entity/health 20.0)
        w (-> (standing {7 far})
              (assoc :time/now 100000 :player/struck-at 99000)
              (assoc-in [:player/inventory 9] (item :cobblestone 40)))]
    (is
     (= far (blaze/shooter w))
     "a blaze beyond reach with the bot in its sight (the fixture is one chunk; the run: 45 off)")
    (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [9 64 8]}
           (blaze/long-shot-cover w))
        "the cell beside the feet toward it")
    (is (= :place (:intent/kind (next-intent w))) "chosen ahead of the tunnel and the camp")
    (testing "not while the hit is old, a blaze is at hand, or the bot is in a pocket"
      (is (nil? (blaze/long-shot-cover (assoc w :player/struck-at 50000))))
      (is (nil? (blaze/long-shot-cover (in-pocket w))))
      (is (nil? (blaze/long-shot-cover (assoc-in w
                                        [:world/entities 8]
                                        (entity :blaze [9.5 65.0 8.5] :entity/health 20.0))))))
    (testing "nothing to lay with: nothing"
      (is (nil? (blaze/long-shot-cover (assoc w :player/inventory {})))))))

(deftest an-idle-camp-baits-from-a-corridor-cell-that-sees-a-blaze
  ;; blaze-race-y jobs 1, 2, 3 and 6: 39 to 48 minutes in a pocket seeing the spawner's top and no
  ;; blaze, 7 to 10 hovering 9 blocks off, none shot, no nearer pocket found
  (let [line (entity :blaze [3.5 65.0 8.5] :entity/health 20.0)
        w (-> (standing {7 line})
              in-pocket
              with-spawner
              (assoc :time/now 100000))
        bare (-> (standing {})
                 in-pocket
                 with-spawner
                 (assoc :time/now 100000))]
    (is (true? (blaze/bait-ok? w spawner-at [8 64 8]))
        "the pocket cell sees the blaze in its opening")
    (is (true? (blaze/bait-here? w spawner-at)))
    (is (false? (blaze/bait-here? bare spawner-at)) "no blaze in sight: not a bait cell")
    (is (false? (blaze/bait-here? w nil)) "no spawner: none")
    (is (nil? (blaze/bait-walk w spawner-at)) "the bot stands in the only one: no walk")))

(deftest the-long-shot-wall-over-open-air-lays-its-support-first
  ;; blaze-race-z2 landing 5, [2159.5 69 1746.5]: the wall's :place failed :no-support, open air beside
  ;; the feet; the block under it, which a neighbour holds up, goes first (build/foundation)
  (let [far (entity :blaze [15.5 66.0 1.5] :entity/health 20.0)
        w (-> (standing {7 far})
              (assoc :time/now 100000 :player/struck-at 99000)
              (assoc-in [:player/inventory 9] (item :cobblestone 40))
              (assoc :world/chunks {[0 0] (world/column {[9 63 8] (blocks/default-state :air)})}))]
    (is (= [9 63 8] (:intent/cell (blaze/long-shot-cover w)))
        "the cell under the wall cell first")))
(deftest a-full-inventory-throws-junk-before-a-rod-is-fetched
  ;; race 41181938317 (c996f54) s2 Clj_r02: five blazes killed, seven rods dropped, :collect-drops
  ;; [333 77 300] failed :not-picked-up four times and rodMax stayed 0; all 36 slots were full
  ;; (877 cobblestone, 128 diorite, 64 cobbled deepslate ...), and a drop is picked up on contact
  (let [rod (entity :item [10.5 64.0 9.5] :entity/item blaze/rod)
        full (into {}
                   (map-indexed (fn [i p] [p (item (if (even? i) :sand :cobblestone) 64)]))
                   inventory/main-then-hotbar)
        w (standing full {40 rod})]
    (is (nil? (window/free-slot w (window/view w :inventory))) "every slot is full")
    (is (= :toss (:intent/kind (next-intent w))) "junk goes before the rod is gone to")
    (testing "a slot free, or a rod held to merge into: the rod is collected"
      (is (= :collect-drops
             (:intent/kind (next-intent (standing (dissoc full (first inventory/main-then-hotbar))
                                                  {40 rod})))))
      (is (= :collect-drops
             (:intent/kind (next-intent (standing (assoc full 9 (item :blaze_rod 3))
                                                  {40 rod}))))))))

(deftest a-sortie-is-over-after-two-fights-ninety-seconds-or-fourteen-health
  ;; blaze-race-z2 landing 2: 4 blazes in 3.4 minutes in the room, then dead there; landing 1 stood
  ;; at the tunnel's end among 6 and 7 until dead
  (let [fought (fn [w t n]
                 (reduce
                  (fn [w i]
                    (memory/remember-intention w {:intent/id (+ 100 i) :intent/kind :fight} :done))
                  (assoc w :time/now t)
                  (range n)))
        base (assoc (standing {}) :time/now 10000 :sortie/since 10000)]
    (is (false? (blaze/sortie-over? (standing {}))) "no sortie begun")
    (is (false? (blaze/sortie-over? base)) "just begun")
    (is (true? (blaze/sortie-over? (assoc base :time/now (+ 10000 blaze/sortie-ms 1))))
        "90 s spent")
    (is (true? (blaze/sortie-over? (assoc base :player/health 13.0))) "under 14")
    (is (false? (blaze/sortie-over? (assoc base :player/health 15.0))))
    (is (false? (blaze/sortie-over? (fought base 12000 1))) "one fight")
    (is (true? (blaze/sortie-over? (fought base 12000 2))) "two fights since it began")))

(deftest steer-notes-the-sortie-where-the-bot-leaves-its-pocket-for-the-zone
  (let [row (first (filter #(= :blaze-rods (:goal/id %)) plan/goals))
        out (-> (standing {})
                with-spawner
                (assoc :time/now 5000))
        in (in-pocket out)]
    (is (= 5000 (:sortie/since (plan/steer out row))) "out of a pocket in the zone: begun")
    (is (= 5000 (:sortie/since (plan/steer (plan/steer out row) row))) "and kept")
    (is (nil? (:sortie/since (plan/steer (assoc in :sortie/since 1) row))) "forgotten in a pocket")
    (is (nil? (:sortie/since (plan/steer (assoc out :player/pos [200.5 64.0 200.5]) row)))
        "and out of the zone")))

(deftest a-healed-bot-in-its-pocket-goes-out-and-a-spent-sortie-comes-back
  (let [w (-> (standing {})
              in-pocket
              with-spawner
              (assoc :time/now 100000))]
    (is (true? (blaze/sortie-due? w)) "healed, nothing recent")
    (is (false? (blaze/sortie-due? (assoc w :player/health 15.0))) "not healed")
    (is (false? (blaze/sortie-due? (memory/remember-intention (assoc w :time/now 100000)
                                                              {:intent/id 5 :intent/kind :fight}
                                                              :done)))
        "a fight just ended: it rests")
    (is (not= :camp (:intent/kind (next-intent w)))
        "out toward the spawner, not a camp in the pocket")
    (let [out (-> (standing {})
                  (assoc :world/chunks {[0 0] (world/column pocket-walls)}
                         :player/pos [5.5 64.0 8.5]
                         :sortie/since 0
                         :time/now 100000)
                  with-spawner)]
      (is (= :pocket (:intent/kind (next-intent out)))
          "a spent sortie: back to the pocket behind"))))

(deftest a-spent-sortie-does-not-cut-the-pick-up-of-a-rod-a-step-away
  ;; blaze-race-z4 landings 1 and 2: :collect-drops [2344 57 2286] and :pocket alternated every 0.35 s
  ;; for 10 s and more (14 times in 10 s), 4 of 6 rods lost, the bot dead in the room
  (let [rod (entity :item [10.5 64.0 8.5] :entity/item blaze/rod)
        w (assoc (standing {40 rod}) :sortie/since 0 :time/now 200000)
        grab {:intent/kind :collect-drops :intent/item :blaze_rod :intent/target [10 64 8]}]
    (is (true? (blaze/sortie-over? w)))
    (is (some? (blaze/near-rod w)))
    (is (not (plan/interrupts? w row grab)) "the rod is picked up first")
    (is (plan/interrupts? w row {:intent/kind :camp :intent/spawner [11 64 10]})
        "a camp is still cut")))

(deftest a-sortie-ends-when-the-bot-burns-or-the-room-is-on-it
  ;; blaze-race-z4 landing 2: 10 blazes killed in 11 minutes, dead among 13 to 14 within 8
  (let [crowd (into {}
                    (map (fn [i] [(+ 50 i)
                                  (entity :blaze [(+ 4.5 (* 0.5 i)) 65.0 8.5]
                                          :entity/health 20.0)]))
                    (range 5))
        base (assoc (standing {}) :time/now 10000 :sortie/since 10000)]
    (is (false? (blaze/sortie-over? base)))
    (is (true? (blaze/sortie-over? (assoc base :player/on-fire? true))) "burning")
    (is (true? (blaze/sortie-over? (assoc base :world/entities crowd))) "five within 8")
    (is (false? (blaze/sortie-over? (assoc base :world/entities (select-keys crowd [50 51 52]))))
        "three is the limit, not over")))

(deftest ^:eftest/synchronized the-camp-clause-never-ends-in-nil-when-no-way-to-the-spawner-is-left
  ;; blaze-race-z4 landing 5: :tunnel-to done on its last stand, to-spawner nil, the clause's value nil,
  ;; {:plan/wait :nothing-to-do} for 400 ticks and the run ended with time left
  (let [w (assoc (-> (standing {})
                     with-spawner)
                 :player/pos
                 [60.5 64.0 60.5])]
    (with-redefs [blaze/to-spawner (fn [_ _] nil)]
      (let [i (with-redefs [blaze/search (fn [_] nil)] (next-intent w))]
        (is (some? i) "an intent, never nil")
        (is (nil? (:plan/wait i)) "and no wait: it ends the plan")
        (is (= :walk (:intent/kind i)))
        (is (= :explore (:intent/for i)))))))

(deftest a-stalled-stand-is-forgotten-after-ten-minutes
  (let [tunnel
        (fn [w id at]
          (memory/remember-intention
           (assoc w :time/now at)
           {:intent/id id :intent/kind :tunnel-to :intent/target [3 64 3] :intent/ended-at [1 64 1]}
           :done))
        w (-> (standing {})
              (tunnel 1 1000)
              (tunnel 2 2000))]
    (is (contains? (blaze/stalled (assoc w :time/now 3000)) [3 64 3])
        "two tunnels ended in one cell")
    (is (empty? (blaze/stalled (assoc w :time/now (+ 2000 blaze/stall-memory-ms 1))))
        "ten minutes on: tried again")))

(deftest stalled-tunnels-to-two-stands-give-up-the-spawners-site
  ;; blaze-race-z4 job 1 (41194927191): :tunnel-to flipped among the cells beside one spawner (x 2209-2211,
  ;; y 60-61, z 1793-1795) for 13 minutes; a tunnel that went nowhere to two of them went nowhere to the site
  (let [sp [12 70 8]
        w (-> (standing {})
              (memory/observe sp blaze/spawner))
        [a b c] (blaze/cells-beside sp)
        done
        (fn [w id target ended-at]
          (memory/remember-intention
           w
           {:intent/id id :intent/kind :tunnel-to :intent/target target :intent/ended-at ended-at}
           :done))
        stall (fn [w id target cell]
                (-> w
                    (done id target cell)
                    (done (inc id) target cell)))
        one (stall w 1 a [3 64 3])
        two (stall one 3 b [3 64 3])]
    (is (some? (blaze/to-spawner one sp)) "one stand stalled: another is tried")
    (is (contains? (blaze/site-stalled two sp) c) "two stalled: every cell beside the spawner is")
    (is (nil? (blaze/to-spawner two sp)) "the site is given up: the act goes on with the search")))

(deftest the-sites-stalled-mark-expires-with-the-stalls
  ;; the mark is derived from stalled, whose facts are forgotten after stall-memory-ms
  (let [sp [12 70 8]
        w (-> (standing {})
              (memory/observe sp blaze/spawner))
        [a b c] (blaze/cells-beside sp)
        done
        (fn [w id target at]
          (memory/remember-intention
           (assoc w :time/now at)
           {:intent/id id :intent/kind :tunnel-to :intent/target target :intent/ended-at [3 64 3]}
           :done))
        two (-> w
                (done 1 a 1000)
                (done 2 a 1100)
                (done 3 b 1200)
                (done 4 b 1300))]
    (is (contains? (blaze/site-stalled (assoc two :time/now 2000) sp) c))
    (is (not (contains? (blaze/site-stalled (assoc two :time/now (+ 1300 blaze/stall-memory-ms 1))
                                            sp)
                        c))
        "ten minutes on: the site is tried again")))

(deftest an-open-hold-under-fire-is-cut-for-the-seal
  ;; blaze-race-z5 landing 1: :pocket-hold ran 30 s open at hp 20 with 63 cobblestone and a pickaxe held,
  ;; three fireballs and their burns in it, 20 to 0 in 60 s, no :seal ever: a running hold is not asked again
  (let [line (entity :blaze [4.5 65.0 8.5] :entity/health 20.0)
        loaded (fn [w]
                 (-> w
                     (assoc-in [:player/inventory 9] (item :cobblestone 62))
                     (assoc-in [:player/inventory 10] (item :iron_pickaxe 1))))
        base (-> (standing {7 line})
                 in-pocket
                 loaded)
        hold {:intent/kind :pocket-hold :intent/heal-to 20.0}]
    (is (= {:intent/kind :seal} (next-intent (assoc base :player/on-fire? true)))
        "burning in the line: the seal")
    (is (true? (boolean (plan/interrupts? (assoc base :player/on-fire? true) row hold)))
        "so a running hold is cut for it")
    (is (false? (boolean (plan/interrupts? (-> (standing {})
                                               in-pocket
                                               loaded)
                                           row
                                           hold)))
        "nothing shooting: the hold runs")))

(deftest a-sortie-is-not-cut-for-a-pocket-ahead
  ;; blaze-race-z6 landings 1 and 2: 138 minutes of :camp (60 s), :camp failed :no-blaze, :tunnel-to, :pocket
  ;; one second later, :camp: the sortie's first dig opened the pocket's wall (valid? false), and
  ;; prepare-intent made a new pocket ahead and cut the tunnel; the leg never got past two cells
  (let [w (-> (standing {})
              (assoc :world/chunks {[0 0] (world/column rock)}
                     :player/pos [5.5 64.0 8.5]
                     :time/now 1000000)
              with-spawner)]
    (is (some? (blaze/prepare-intent w spawner-at)) "no sortie: a pocket ahead is made")
    (is (nil? (blaze/prepare-intent (assoc w :sortie/since 990000) spawner-at))
        "on a sortie: no pocket ahead")))
