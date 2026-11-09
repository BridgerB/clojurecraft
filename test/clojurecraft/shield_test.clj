(ns clojurecraft.shield-test
  "The shield: the :shield row's rule and a shield made and put in the offhand against the server
   model, a fight that holds it up between swings and lowers it to swing, the planner's guard, and
   the model blocking a blow from in front."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.blocks :as blocks]
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
  (let [plain (assoc-in (fighting 2.0) [:player/inventory inventory/offhand-slot] nil)
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
    (testing "a strike past struck-ms, or a mob that does not shoot: nothing"
      (is (not (shield/raised? (shield/guard (assoc w :player/struck-at 0)))))
      (is (not (shield/raised? (shield/guard (assoc-in w
                                              [:world/entities 9 :entity/type]
                                              (blocks/entity-types :zombie))))))
      (is (contains? mob/kinds :skeleton)))))

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
    (is (= 1 (:sim/blocked facing)))
    (is (nil? (:sim/wither facing)) "no wither from a blow that never landed")
    (is (= 12.0 (:sim/health behind)) "a blow from behind lands")
    (is (= 12.0 (:sim/health fresh)) "up less than a quarter second: not yet a block")
    (is (= 12.0 (:sim/health none)) "no shield in the offhand")))

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
