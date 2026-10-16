(ns clojurecraft.hunger-test
  "Hunger as the server keeps it (issue #260): the rules of vanilla's FoodData, and the server
   model draining, healing, starving and feeding by them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

;; ---------------------------------------------------------------- the rules

(deftest four-exhaustion-cost-saturation-first-then-a-food-point
  (let [tired (hunger/exhaust hunger/fresh 4.5)]
    (is (= 4.0 (:food/saturation (hunger/spend tired :normal))) "saturation goes first")
    (is (= 20 (:food/level (hunger/spend tired :normal))))
    (let [empty-sat (assoc tired :food/saturation 0.0)]
      (is (= 19 (:food/level (hunger/spend empty-sat :normal))) "then the bar")
      (is (= 20 (:food/level (hunger/spend empty-sat :peaceful))) "never on peaceful"))
    (let [rested (hunger/exhaust hunger/fresh 3.9)]
      (is (= rested (hunger/spend rested :normal)) "under four nothing is spent"))))

(deftest starvation-stops-at-ten-on-easy-one-on-normal-and-kills-on-hard
  (is (hunger/starves? 11.0 :easy))
  (is (not (hunger/starves? 10.0 :easy)))
  (is (hunger/starves? 2.0 :normal))
  (is (not (hunger/starves? 1.0 :normal)))
  (is (hunger/starves? 1.0 :hard)))

(deftest eating-adds-nutrition-and-saturation-up-to-the-bar
  (let [bread (recipe/item-id :bread)
        hungry (assoc hunger/fresh :food/level 10 :food/saturation 0.0)]
    (is (= {:food/level 15 :food/saturation 6.0}
           (select-keys (hunger/eat hungry bread) [:food/level :food/saturation])))
    (is (= 20 (:food/level (hunger/eat (assoc hungry :food/level 18) bread))) "capped at full")
    (is (hunger/can-eat? hungry bread))
    (is (not (hunger/can-eat? hunger/fresh bread)) "a full bar eats no bread")
    (is (hunger/can-eat? hunger/fresh (recipe/item-id :golden_apple)) "a golden apple always")
    (is (not (hunger/can-eat? hungry (recipe/item-id :stick))) "a stick is no food")))

;; ---------------------------------------------------------------- on the model

(defn hungry-sim
  "A model in play at difficulty, the player at [0.5 64 0.5] on the stone floor with inv as
   window 0's slots and food as its hunger."
  [difficulty inv food]
  (->
    (sim/init
     {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5] :inventory inv :difficulty difficulty})
    (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5] :sim/on-ground? true)
    (update :sim/food merge food)))

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

(defn said
  "Every packet named n the model has queued."
  [sim n]
  (filter #(= n (:packet/name %)) (:sim/out sim)))

(def use-held
  {:packet/name :use-item :hand (packet/value :hand :main-hand) :sequence 1 :y-rot 0.0 :x-rot 0.0})

(defn jump
  "The model after the client reports one jump: on the ground at y 64, then airborne at 64.42."
  [sim]
  (-> sim
      (sends {:packet/name :move-player-pos :x 0.5 :y 64.0 :z 0.5 :flags 1})
      (sends {:packet/name :move-player-pos :x 0.5 :y 64.42 :z 0.5 :flags 0})))

(deftest eighty-one-jumps-cost-a-food-point-once-saturation-is-gone
  ;; 0.05 a jump (ServerGamePacketListenerImpl.handleMovePlayer: on the ground, then not, going
  ;; up): 81 jumps are past four exhaustion, and with no saturation the bar drops one
  (let [sim
        (reduce (fn [sim _] (jump sim)) (hungry-sim :normal {} {:food/saturation 0.0}) (range 81))
        after (ticks sim 1)]
    (is (< 4.0 (:food/exhaustion (:sim/food sim)) 4.1))
    (is (= 19 (:food/level (:sim/food after))))
    (is (= [[20.0 19 0.0]] (map (juxt :health :food :saturation) (said after :set-health)))
        "the client is told once the bar changed")))

(deftest a-model-without-a-difficulty-has-no-hunger
  ;; snapshots kept before issue #260 carry no difficulty and must play as they did
  (let [sim (-> (sim/init {:column (world/column-bytes {})
                           :spawn [0.5 64.0 0.5]
                           :inventory {[:hotbar 0] {:item (recipe/item-id :bread) :count 3}}})
                (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5]))
        jumped (ticks (reduce (fn [sim _] (jump sim)) sim (range 100)) 100)
        ate (ticks (sends sim use-held) 40)]
    (is (nil? (:sim/food jumped)))
    (is (empty? (said jumped :set-health)) "no health told where none was before")
    (is (= 3 (get-in ate [:sim/inv (inventory/slot [:hotbar 0]) :count])) "nothing eaten")))

(deftest starving-on-normal-stops-at-one-health
  (let [sim (-> (hungry-sim :normal {} {:food/level 0 :food/saturation 0.0})
                (assoc :sim/health 3.0))
        after (ticks sim 400)]
    (is (= 1.0 (:sim/health after)) "two hurts, one every 80 ticks, then none at one")
    (is (= [3.0 2.0 1.0] (map :health (said after :set-health)))
        "the first tick tells the client the health it starts with, then each hurt")
    (testing "on hard it kills"
      (is (= 0.0 (:sim/health (ticks (assoc sim :sim/difficulty :hard) 400)))))))

(deftest a-bar-at-eighteen-heals-a-heart-every-four-seconds-for-six-exhaustion
  (let [sim (-> (hungry-sim :normal {} {:food/level 18 :food/saturation 0.0})
                (assoc :sim/health 10.0))
        after (ticks sim 80)]
    (is (= 11.0 (:sim/health after)))
    (is (= 6.0 (:food/exhaustion (:sim/food after))))))

(deftest bread-eaten-for-thirty-two-ticks-restores-five
  (let [bread (recipe/item-id :bread)
        sim (sends (hungry-sim :normal
                               {[:hotbar 0] {:item bread :count 2}}
                               {:food/level 10 :food/saturation 0.0})
                   use-held)
        slot (inventory/slot [:hotbar 0])
        early (ticks sim 31)
        done (ticks sim 32)]
    (is (= 10 (:food/level (:sim/food early))) "not before 32 ticks")
    (is (= 2 (get-in early [:sim/inv slot :count])))
    (is (= 15 (:food/level (:sim/food done))))
    (is (= 6.0 (:food/saturation (:sim/food done))))
    (is (= 1 (get-in done [:sim/inv slot :count])) "one bread taken")
    (is (= 1 (:sim/eaten done)))
    (is (= [{:entity-id sim/player-eid :status sim/use-finished}]
           (map #(select-keys % [:entity-id :status]) (said done :entity-event))))
    (is (some #(= 15 (:food %)) (said done :set-health)) "the client is told the new bar")))

(deftest eating-stops-when-the-held-slot-changes-or-the-use-is-released
  (let [bread (recipe/item-id :bread)
        sim (sends (hungry-sim :normal
                               {[:hotbar 0] {:item bread :count 2}}
                               {:food/level 10 :food/saturation 0.0})
                   use-held)
        swapped (ticks (sends (ticks sim 10) {:packet/name :set-carried-item :slot 1}) 40)
        released (ticks (sends (ticks sim 10)
                               {:packet/name :player-action
                                :status sim/release-use-item
                                :pos [0 0 0]
                                :face 0
                                :sequence 0})
                        40)]
    (is (= 10 (:food/level (:sim/food swapped))))
    (is (= 10 (:food/level (:sim/food released))))
    (is (= 2 (get-in released [:sim/inv (inventory/slot [:hotbar 0]) :count])))))

(deftest a-full-bar-eats-nothing
  (let [sim (sends (hungry-sim :normal {[:hotbar 0] {:item (recipe/item-id :bread) :count 2}} {})
                   use-held)]
    (is (nil? (:sim/eating sim)))))

(deftest a-respawn-refills-the-bar
  (let [sim (-> (hungry-sim :normal {} {:food/level 3 :food/saturation 0.0})
                (assoc :sim/health 0.0 :sim/respawn [0.5 64.0 0.5])
                (sends {:packet/name :client-command
                        :action (packet/value :client-command :perform-respawn)}))]
    (is (= hunger/fresh (:sim/food sim)))
    (is (= sim/max-health (:sim/health sim)))))
