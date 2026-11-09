(ns clojurecraft.shield-test
  "The shield: the :shield row's rule and a shield made and put in the offhand against the server
   model, a fight that holds it up between swings and lowers it to swing, the planner's guard, and
   the model blocking a blow from in front."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.shield :as shield]
            [clojurecraft.sim :as sim]
            [clojurecraft.sim.mobs :as sim-mobs]
            [clojurecraft.sim.protocol :as sim-protocol]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column, holding inv, with entities."
  [inv entities]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :player/health 20.0
             :world/chunks {[0 0] (world/column {})}
             :window/state-id 5
             :player/inventory inv
             :world/entities entities)))

(def kit
  {0 (item :flint_and_steel 1)
   1 (item :water_bucket 1)
   2 (item :bucket 1)
   4 (item :crafting_table 1)})

;; ---------------------------------------------------------------- the row

(deftest a-shield-is-wanted-from-a-spare-ingot-and-the-wood-at-hand
  (testing "the kit, an ingot and six planks with a table held: a shield to make"
    (is (armour/shield-wanted? (standing (merge kit {5 (item :iron_ingot 1) 6 (item :oak_planks 6)})
                                         {})))
    (is (not (plan/done-by (standing (merge kit {5 (item :iron_ingot 1) 6 (item :oak_planks 6)}) {})
                           (row :shield)))))
  (testing "two logs are eight planks"
    (is (armour/shield-wanted? (standing (merge kit {5 (item :iron_ingot 1) 6 (item :oak_log 2)})
                                         {}))))
  (testing "no table held or near: its four planks are wanted too"
    (is (not (armour/shield-wanted?
              (standing (merge (dissoc kit 4) {5 (item :iron_ingot 1) 6 (item :oak_planks 6)})
                        {}))))
    (is (armour/shield-wanted?
         (standing (merge (dissoc kit 4) {5 (item :iron_ingot 1) 6 (item :oak_planks 10)}) {}))))
  (testing "the portal's iron not all held: no ingot is spare"
    (is (not (armour/shield-wanted?
              (standing (merge (dissoc kit 2) {5 (item :iron_ingot 1) 6 (item :oak_planks 6)})
                        {})))))
  (testing "too little wood: the row never sends the bot after a tree"
    (is (not (armour/shield-wanted?
              (standing (merge kit {5 (item :iron_ingot 1) 6 (item :oak_planks 5)}) {})))))
  (testing "one held, in the offhand: none more"
    (is (plan/done-by (standing (merge kit
                                       {5 (item :iron_ingot 1)
                                        6 (item :oak_planks 6)
                                        inventory/offhand-slot (item :shield 1)})
                                {})
                      (row :shield)))))

(deftest the-shield-row-comes-before-the-armour
  (let [ids (mapv :goal/id plan/goals)]
    (is (< (.indexOf ^java.util.List ids :shield) (.indexOf ^java.util.List ids :armour)))
    (is (= (:goal/priority (row :shield)) (:goal/priority (row :armour))))))

(deftest a-shield-is-made-and-worn-in-the-offhand-against-the-model
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :inventory {[:hotbar 0] (item :flint_and_steel 1)
                                                [:hotbar 1] (item :water_bucket 1)
                                                [:hotbar 2] (item :bucket 1)
                                                [:hotbar 3] (item :crafting_table 1)
                                                [:hotbar 4] (item :iron_ingot 1)
                                                [:hotbar 5] (item :oak_planks 6)}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:shield]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= :shield (armour/worn w :offhand))
        (pr-str {:inventory (:player/inventory w)
                 :story (mapv (juxt :intention/kind :intention/event :intention/reason)
                              (memory/intentions w))}))
    (is (= (recipe/item-id :shield) (sim-protocol/offhand-item sim)) "the server holds it there")
    (is (zero? (:iron_ingot (recipe/counts (:player/inventory w)) 0)))
    (is (= [] (:sim/violations sim)))))

;; ---------------------------------------------------------------- the fight

(defn mob
  "A tracked living entity of type k (a name) at pos, with health h."
  [k pos h]
  {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0 :entity/health h})

(defn fighting
  "A bot with a stone sword and a shield in the offhand, a zombie (eid 7) d blocks east and a
   :fight on it running."
  [d]
  (assoc (standing {0 (item :stone_sword 1) inventory/offhand-slot (item :shield 1)}
                   {7 (mob :zombie [(+ 8.5 d) 64.0 8.5] 20.0)})
         :plan/intent
         {:intent/kind :fight :intent/target 7 :intent/status :active}))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(defn hands
  "[[ms what] ...] of the shield's packets and the attacks in fx: :up a use-item with the offhand,
   :down a release-use-item, :attack an attack."
  [fx]
  (vec (keep (fn [[t {n :packet/name :as pk}]]
               (cond (and (= :use-item n) (= (p/value :hand :off-hand) (:hand pk))) [t :up]
                     (and (= :player-action n) (= (p/value :dig :release-use-item) (:status pk)))
                     [t :down]
                     (= :attack n) [t :attack]
                     :else nil))
             (fx/sent fx))))

(deftest a-fight-holds-the-shield-up-between-swings-and-lowers-it-to-swing
  (let [[w fx] (fx/fold intent-step (fighting 2.0) (map tick (range 50 1550 50)))
        h (hands fx)]
    (testing "the swings keep the gap they had without a shield"
      (is (= [150 800 1450] (mapv first (filter #(= :attack (second %)) h)))))
    (is (= :up (second (first h))) "up before the first swing")
    (is (< (ffirst h) 150))
    (testing "down in the tick of each swing, before it, and up again the tick after"
      (doseq [t [150 800 1450]]
        (is (= [[t :down] [t :attack] [(+ t 50) :up]] (filterv #(<= t (first %) (+ t 50)) h))
            (str "the swing at " t))))
    (is (= 1 (count (filter #(= :up (second %)) (filter #(< (first %) 150) h))))
        "raised once while it waits for the first swing, not every tick")
    (is (shield/raised? w))
    (testing "blocking only raise-ticks after the use"
      (is (not (shield/blocking? (assoc w :time/now 1550))))
      (is (shield/blocking? (assoc w :time/now 1750))))))

(deftest no-shield-held-sends-nothing-new
  (let [plain (update (fighting 2.0) :player/inventory dissoc inventory/offhand-slot)
        [_ fx] (fx/fold intent-step plain (map tick (range 50 1550 50)))]
    (is (= [[150 :attack] [800 :attack] [1450 :attack]] (hands fx)))))

(deftest a-fight-that-walks-keeps-the-shield-down
  ;; ten blocks off: the approach walks, and a vanilla client walks at a fifth of its speed with
  ;; the use key held
  (let [[_ fx] (fx/fold intent-step (fighting 10.0) (map tick (range 50 500 50)))]
    (is (= [] (hands fx)))))

(deftest a-fight-that-ends-lowers-the-shield
  (let [up (-> (fighting 2.0)
               (assoc :player/shield-up-at 0)
               (update :world/entities dissoc 7))
        [w fx] (fx/fold intent-step up [(tick 50)])]
    (is (intent/done? (:plan/intent w)))
    (is (= [[50 :down]] (hands fx)))
    (is (not (shield/raised? w)))))

;; ---------------------------------------------------------------- the guard

(deftest the-guard-lowers-the-shield-before-any-other-intent
  (let [w (-> (standing {inventory/offhand-slot (item :shield 1) 0 (item :bread 3)} {})
              (assoc :time/now 1000
                     :player/shield-up-at 500
                     :plan/intent {:intent/kind :eat :intent/item :bread :intent/status :active}))
        g (shield/guard w)]
    (is (not (shield/raised? g)))
    (is (= [(p/value :dig :release-use-item)]
           (keep #(when (= :player-action (get-in % [:effect/packet :packet/name]))
                    (get-in % [:effect/packet :status]))
                 (:bot/effects g)))))
  (testing "a running fight is left alone"
    (let [w (assoc (fighting 2.0) :time/now 1000 :player/shield-up-at 500)]
      (is (= w (shield/guard w))))))

(deftest a-wait-under-a-shooters-fire-faces-it-with-the-shield-up
  (let [w (-> (standing {inventory/offhand-slot (item :shield 1)}
                        {9 (mob :skeleton [14.5 64.0 8.5] 20.0)})
              (assoc :time/now 5000 :player/attacker 9 :player/struck-at 4000))
        g (shield/guard w)
        [yaw _] (get-in g [:player/controls :control/look])]
    (is (shield/raised? g))
    (is (< (abs (- -90.0 yaw)) 1.0) "facing east, at the skeleton")
    (is (= [(p/value :hand :off-hand)]
           (keep #(when (= :use-item (get-in % [:effect/packet :packet/name]))
                    (get-in % [:effect/packet :hand]))
                 (:bot/effects g))))
    (testing "a strike long past, the skeleton out of threat-reach: nothing"
      (is (not (shield/raised? (shield/guard (assoc w :player/struck-at 0))))))
    (testing "a zombie that struck a moment ago is the threat too (striker)"
      (is (shield/raised? (shield/guard (assoc-in w
                                         [:world/entities 9 :entity/type]
                                         (blocks/entity-types :zombie)))))
      (is (not (shield/raised? (shield/guard (-> w
                                                 (assoc-in [:world/entities 9 :entity/type]
                                                           (blocks/entity-types :zombie))
                                                 (assoc :player/struck-at 2500)))))
          "2.5 s ago, six blocks off: past melee-struck-ms and threat-reach"))))

(defn still
  "A bot with the shield in the offhand, looking along yaw, a zombie (eid 9) three blocks east,
   running intent i."
  [yaw i]
  (-> (standing {inventory/offhand-slot (item :shield 1)} {9 (mob :zombie [11.5 64.0 8.5] 20.0)})
      (assoc :time/now 5000 :player/look [yaw 0.0] :plan/intent i)))

(defn effects-named
  "The packet names of the world's queued effects, in order."
  [w]
  (mapv (comp :packet/name :effect/packet) (:bot/effects w)))

(deftest a-still-intent-raises-the-shield-toward-a-hostile-in-reach
  (testing "a lookout turns to face it, the shield up"
    (let [g (shield/guard (still 90.0 {:intent/kind :lookout :intent/status :active}))
          [yaw _] (get-in g [:player/controls :control/look])]
      (is (shield/raised? g))
      (is (< (abs (- -90.0 yaw)) 1.0) "facing east, at the zombie")))
  (testing "a dig between breaks: up only when the zombie is in front, the look its own"
    (let [i {:intent/kind :dig :intent/target [9 64 8] :intent/status :active}
          front (shield/guard (still -90.0 i))
          back (shield/guard (still 90.0 i))]
      (is (shield/raised? front))
      (is (= (:player/controls (still -90.0 i)) (:player/controls front))
          "the dig's look is not turned")
      (is (not (shield/raised? back)) "behind: the dig's look is not turned to it")))
  (testing "a dig breaking a block: down"
    (is (not (shield/raised?
              (shield/guard
               (still -90.0 {:intent/kind :dig :intent/stage :digging :intent/status :active}))))))
  (testing "a burrow whose child digs: down"
    (is (not (shield/raised? (shield/guard (still -90.0
                                                  {:intent/kind :burrow
                                                   :intent/status :active
                                                   :intent/child {:intent/kind :dig
                                                                  :intent/stage :digging}}))))))
  (testing "a walk: down"
    (is (not (shield/raised? (shield/guard (still -90.0
                                                  {:intent/kind :walk :intent/status :active}))))))
  (testing "no hostile near: down"
    (is (not (shield/raised?
              (shield/guard
               (update (still -90.0 {:intent/kind :lookout}) :world/entities dissoc 9)))))))

(deftest the-hand-lets-the-shield-go-just-before-it-is-used
  (let [up (assoc (still -90.0 {:intent/kind :dig :intent/target [9 64 8] :intent/status :active})
                  :player/shield-up-at 4000
                  :bot/effects [])
        dug (-> up
                (game/emit {:packet/name :player-action
                            :status (p/value :dig :start-destroy)
                            :pos [9 64 8]
                            :face 1
                            :sequence 3})
                (game/emit {:packet/name :swing :hand (p/value :hand :main-hand)})
                shield/guard)]
    (is (= [:player-action :player-action :swing] (effects-named dug)))
    (is (= (p/value :dig :release-use-item)
           (get-in (first (:bot/effects dug)) [:effect/packet :status]))
        "the release goes first")
    (is (not (shield/raised? dug)))
    (is (shield/raised? (shield/guard (assoc dug :bot/effects [])))
        "up again the next tick, the dig between breaks")))

(deftest an-arrow-names-the-skeleton-that-loosed-it
  ;; the damage-event's attacker is the arrow, gone once it lands (a real race's soul sand valley
  ;; deaths: every hit by-kind :arrow): the shooter is the nearest skeleton within its range
  (let [w (-> (standing {inventory/offhand-slot (item :shield 1)}
                        {9 (mob :skeleton [14.5 64.0 8.5] 20.0)
                         11 (mob :skeleton [8.5 64.0 22.5] 20.0)})
              (assoc :time/now 5000 :player/attacker 40 :player/struck-at 4500))]
    (is (= 9 (first (shield/shooter w))) "the arrow gone: the nearer skeleton")
    (is (= 9
           (first (shield/shooter
                   (assoc-in w [:world/entities 40] (mob :arrow [9.5 64.0 8.5] 0.0)))))
        "the arrow still shown")
    (is (shield/raised? (shield/guard w)))))

(def archer
  "A bot with the shield in the offhand and food, a skeleton (eid 9) six blocks east in sight."
  (-> (standing {inventory/offhand-slot (item :shield 1) 0 (item :bread 3)}
                {9 (mob :skeleton [14.5 64.0 8.5] 20.0)})
      (assoc :time/now 5000 :player/food 15 :player/health 8.0)))

(deftest the-cover-from-a-shooter-in-sight-is-the-shield-not-a-meal
  ;; real race on main 54d5444, real-18: r1 Clj_r06 took 17 arrows in 44 s and r3 Clj_r02 11 in
  ;; 49 s, both ending on a meal eaten in cover a skeleton could see
  (let [e (get-in archer [:world/entities 9])]
    (is (= {:intent/kind :block :intent/target 9} (combat/cover archer e)))
    (is (= :eat
           (:intent/kind
            (combat/cover (update archer :player/inventory dissoc inventory/offhand-slot) e)))
        "no shield: the meal, as before")
    (is (not= :block
              (:intent/kind (combat/cover (assoc-in archer
                                           [:world/entities 9 :entity/type]
                                           (blocks/entity-types :zombie))
                                          (mob :zombie [14.5 64.0 8.5] 20.0))))
        "a mob that does not shoot: no block")))

(deftest a-block-faces-the-shooter-with-the-shield-up-for-a-while
  (let [w (assoc archer :plan/intent {:intent/kind :block :intent/target 9 :intent/status :active})
        [w2 fx] (fx/fold intent-step w (map tick (range 5050 5300 50)))
        [yaw _] (get-in w2 [:player/controls :control/look])]
    (is (= [[5050 :up]] (hands fx)) "raised once, held")
    (is (< (abs (- -90.0 yaw)) 1.0) "facing the skeleton")
    (is (= :active (:intent/status (:plan/intent w2))))
    (let [[w3 _] (fx/fold intent-step w2 (map tick (range 5300 8200 50)))]
      (is (intent/done? (:plan/intent w3)) "done after block-ms: the planner looks again")
      (is (shield/raised? w3) "still up: the guard lowers it only before another intent"))))

;; ---------------------------------------------------------------- the model

(def night 18000)

(defn arena
  "A model in play on a stone floor, the player at [0.5 64 0.5] holding inv and looking along
   yaw, with mobs given as a fixture's."
  [inv yaw mobs]
  (-> (sim/init
       {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5] :inventory inv :day-time night})
      (assoc :sim/phase :play
             :sim/player-pos [0.5 64.0 0.5]
             :sim/player-look [yaw 0.0]
             :sim/on-ground? true)
      (sim-mobs/with-mobs mobs)))

(defn ticks
  "The model after n game ticks, 50 ms apart."
  [sim n]
  (reduce (fn [sim _] (sim/step sim {:sim/kind :tick :sim/now (+ (:sim/now sim) physics/tick-ms)}))
          sim
          (range n)))

(defn sends
  "The model after the client sends pkt."
  [sim pkt]
  (sim/step sim {:sim/kind :packet :sim/packet pkt}))

(def shield-held {[:offhand] (item :shield 1)})

(defn raised
  "The model with the shield raised ms ago."
  [sim ms]
  (assoc sim :sim/blocking-at (- (:sim/now sim) ms)))

(def east -90.0)                      ; the yaw that looks along +x
(def west 90.0)

(deftest a-raised-shield-facing-the-blow-takes-it
  (let [skel [{:type :wither_skeleton :pos [1.0 64.0 0.5]}]
        facing (ticks (raised (arena shield-held east skel) 1000) 1)
        behind (ticks (raised (arena shield-held west skel) 1000) 1)
        fresh (ticks (raised (arena shield-held east skel) 0) 1)
        none (ticks (raised (arena {} east skel) 1000) 1)]
    (is (= 20.0 (:sim/health facing)) "the eight blocked")
    (is (= {[:wither_skeleton :melee] 1} (:sim/blocked-by facing)) "counted by kind and how")
    (is (= 1 (:sim/blocked facing)))
    (is (nil? (:sim/wither facing)) "no wither from a blow that never landed")
    (is (= 12.0 (:sim/health behind)) "a blow from behind lands")
    (is (= 12.0 (:sim/health fresh)) "up less than a quarter second: not yet a block")
    (is (= 12.0 (:sim/health none)) "no shield in the offhand")))

(deftest a-raised-shield-takes-a-hoglins-swing-from-in-front
  ;; knockback (a hoglin's throw) is not modelled: what the shield takes is the hit
  (let [hog [{:type :hoglin :pos [1.5 64.0 0.5]}]
        facing (ticks (raised (arena shield-held east hog) 1000) 1)
        behind (ticks (raised (arena shield-held west hog) 1000) 1)]
    (is (= 20.0 (:sim/health facing)))
    (is (= 1 (:sim/blocked facing)))
    (is (= 14.0 (:sim/health behind)) "six from behind")))

(deftest a-raised-shield-takes-a-fireball-from-in-front
  (let [dir (mob/aim [4.5 65.4 0.5] [0.5 64.9 0.5] 0.5 0.5 0.5 0.5)
        fly (fn [sim]
              (-> sim
                  (assoc-in [:sim/fireballs 90]
                            {:pos [4.5 65.4 0.5] :dir dir :owner 91 :born 0 :kind :blaze})
                  (ticks 12)))
        blocked (fly (raised (arena shield-held east []) 1000))
        hit (fly (arena shield-held east []))]
    (is (= 20.0 (:sim/health blocked)))
    (is (nil? (:sim/fire blocked)) "not alight")
    (is (empty? (:sim/fireballs blocked)) "spent on the shield")
    (is (= {[:blaze :fireball] 1} (:sim/blocked-by blocked)))
    (is (= 15.0 (:sim/health hit)) "down, it lands")))

(deftest the-offhand-use-raises-the-shield-and-the-release-lowers-it
  (let [sim (arena shield-held east [])
        up (sends sim
                  {:packet/name :use-item
                   :hand (p/value :hand :off-hand)
                   :sequence 1
                   :y-rot east
                   :x-rot 0.0})
        down (sends up
                    {:packet/name :player-action
                     :status (p/value :dig :release-use-item)
                     :pos [0 0 0]
                     :face 0
                     :sequence 0})]
    (is (= (:sim/now sim) (:sim/blocking-at up)))
    (is (some #(= {entity/living-flags-index sim-protocol/using-flags} (:metadata %))
              (filter #(= :set-entity-data (:packet/name %)) (:sim/out up)))
        "the client told it uses its offhand")
    (is (nil? (:sim/blocking-at down)))
    (is (nil? (:sim/blocking-at (sends sim
                                       {:packet/name :use-item
                                        :hand (p/value :hand :main-hand)
                                        :sequence 1
                                        :y-rot east
                                        :x-rot 0.0})))
        "the main hand's use raises nothing")
    (is (nil? (:sim/blocking-at (sends (arena {} east [])
                                       {:packet/name :use-item
                                        :hand (p/value :hand :off-hand)
                                        :sequence 1
                                        :y-rot east
                                        :x-rot 0.0})))
        "no shield in the offhand")))

;; ---------------------------------------------------------------- a standoff at the shield

(defn at-shield
  "A bot with an iron sword and the shield in the offhand, looking east, a hoglin (eid 9) two
   blocks east, health hp, the shield up since up-at."
  [hp up-at]
  (-> (standing {0 (item :iron_sword 1) inventory/offhand-slot (item :shield 1) 1 (item :bread 4)}
                {9 (mob :hoglin [10.5 64.0 8.5] 40.0)})
      (assoc :time/now 10000
             :player/look [-90.0 0.0]
             :player/health hp
             :player/food 15
             :player/shield-up-at up-at)))

(deftest a-standoff-at-the-shield-becomes-a-fight
  ;; shd forest R2/21 seed 2: a hoglin hit a raised shield sixty times in a minute of :lookout,
  ;; and the bot died walking off from it at 10.9 health
  (let [w (at-shield 18.0 5000)]
    (is (= 9 (first (combat/pressing w))) "up five seconds toward it: a standoff")
    (is (not (plan/done-by w (row :defend))))
    (is (= {:intent/kind :fight :intent/target 9} (plan/next-intent w (row :defend) plan/goals))))
  (testing "up a moment only: not yet" (is (nil? (combat/pressing (at-shield 18.0 8000)))))
  (testing "no shield: no standoff"
    (is (nil? (combat/pressing
               (update (at-shield 18.0 5000) :player/inventory dissoc inventory/offhand-slot)))))
  (testing "two hostiles near: not a lone striker"
    (is (nil? (combat/pressing (assoc-in (at-shield 18.0 5000)
                                [:world/entities 11]
                                (mob :zombie [8.5 64.0 10.5] 20.0)))))))

(deftest too-hurt-to-fight-the-shield-holds-and-the-bot-neither-walks-nor-eats
  (let [w (at-shield 5.0 5000)]
    (is (nil? (combat/pressing w)) "under the danger-line: no fight")
    (is (not (plan/done-by w (row :shield-hold))))
    (is (= {:intent/kind :block :intent/target 9}
           (plan/next-intent w (row :shield-hold) plan/goals)))
    (is (= :shield-hold (:goal/id (plan/choose w plan/goals)))
        "chosen before the meal and the defence's back-off")
    (let [i (assoc (plan/next-intent w (row :shield-hold) plan/goals) :intent/status :active)
          [w2 fx] (fx/fold intent-step (assoc w :plan/intent i) (map tick (range 10050 10300 50)))]
      (is (not-any? :control/forward? [(:player/controls w2)]) "standing, no walk")
      (is (= []
             (filter #(= :use-item (:packet/name %))
                     (remove #(= (p/value :hand :off-hand) (:hand %)) (fx/packets fx))))
          "no meal")))
  (testing "healthy: the row is done, the fight takes it"
    (is (plan/done-by (at-shield 18.0 5000) (row :shield-hold))))
  (testing "nothing in reach: done, the meal may come"
    (is (plan/done-by (update (at-shield 5.0 5000) :world/entities dissoc 9) (row :shield-hold)))))

(deftest burning-or-trapped-outranks-the-shield-hold
  (let [w (assoc (at-shield 5.0 5000) :player/on-fire? true)]
    (is (plan/done-by w (row :shield-hold)) "burning: the fire's answer first")
    (is (not= :shield-hold (:goal/id (plan/choose w plan/goals)))))
  (let [ids (mapv :goal/id plan/goals)
        at #(.indexOf ^java.util.List ids %)]
    (is (< (at :escape-water) (at :shield-hold)) "the water escape stands before it")
    (is (< (at :escape-lava) (at :shield-hold)) "the lava escape stands before it")))

(deftest a-fight-under-the-danger-line-hands-over-to-the-block
  ;; a held :defend fight whose health falls under the danger-line used to break off and walk away,
  ;; the shield down; with its target at the shield it ends, and the :shield-hold row blocks
  (let [i {:intent/kind :fight
           :intent/target 9
           :intent/goal :defend
           :intent/status :active
           :intent/stage :engage
           :intent/since 9000}
        [w _] (fx/fold intent-step (assoc (at-shield 5.0 5000) :plan/intent i) [(tick 10050)])]
    (is (intent/done? (:plan/intent w)) "ended, not broken off")
    (is (not= :retreat (:intent/stage (:plan/intent w))))
    (is (not (:control/forward? (:player/controls w))) "no walk")
    (is (= :shield-hold (:goal/id (plan/choose (dissoc w :plan/intent) plan/goals))))))

(deftest a-pack-at-full-health-is-no-hold
  ;; two hoglins make an outmatched pack, whose danger-line is over every health: a hold under it
  ;; stood at full health for a whole sim run; the hold is for a bot under retreat-health
  (let [pack
        (assoc-in (at-shield 20.0 5000) [:world/entities 11] (mob :hoglin [8.5 64.0 10.5] 40.0))]
    (is (< 20.0 (combat/danger-line pack)) "the pack's line is over full health")
    (is (plan/done-by pack (row :shield-hold)) "full health: no hold")
    (is (not (plan/done-by (assoc pack :player/health 8.0) (row :shield-hold))) "hurt: the hold")))
