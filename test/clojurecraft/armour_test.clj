(ns clojurecraft.armour-test
  "Armour (issue #259): the :wear intent's two clicks against canned server answers, the
   :wear-armour and :armour rows' rules, and a helmet and chestplate made and worn against the
   server model."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn standing
  "A bot in play on the stone floor of one column, holding inv, window 0 at state id 5."
  [inv]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [0.5 64.0 0.5]
             :player/loaded? true
             :player/on-ground? true
             :world/chunks {[0 0] (world/column {})}
             :window/state-id 5
             :player/inventory inv)))

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(defn set-slot
  "The server setting window 0's slot s to item, under state-id."
  [state-id s item]
  (packet {:packet/name :container-set-slot :window-id 0 :state-id state-id :slot s :item item}))

(defn clicks
  "The clicks the bot sent, each [window slot, button, mode, state id]."
  [fx]
  (vec (for [p (fx/packets fx)
             :when (= :container-click (:packet/name p))]
         [(:slot p) (:button p) (:mode p) (:state-id p)])))

(def head (inventory/slot [:armour 0]))
(def main-0 (inventory/slot [:main 0]))

(deftest a-piece-goes-in-by-hand-two-answered-clicks
  (let [w (assoc (standing {9 (item :iron_helmet 1)})
                 :plan/intent
                 {:intent/kind :wear :intent/item :iron_helmet :intent/status :active})
        [w fx] (fx/fold intent-step w [(tick 50) (tick 100)])]
    (is (= [[main-0 0 0 5]] (clicks fx)) "picked up from its slot, then nothing until the answer")
    (let [[w fx] (fx/fold intent-step
                          w
                          [(set-slot 6 main-0 nil)
                           (packet {:packet/name :set-cursor-item :item (item :iron_helmet 1)})
                           (tick 150) (tick 200)])]
      (is (= [[head 0 0 6]] (clicks fx)) "put down in the head slot")
      (let [[w _] (fx/fold intent-step w [(set-slot 7 head (item :iron_helmet 1)) (tick 250)])]
        (is (= :done (:intent/status (:plan/intent w))))
        (is (= :iron_helmet (armour/worn w 0)) "worn, at player slot 39")
        (is (= 1 (:iron_helmet (recipe/counts (:player/inventory w))))
            "and still counted as held")))))

(deftest a-refused-piece-goes-back-before-the-wear-fails
  (let [w
        (assoc
         (standing {})
         :window/cursor (item :iron_helmet 1)
         :plan/intent
         {:intent/kind :wear :intent/item :iron_helmet :intent/status :active :intent/stage :check})
        [w fx] (fx/fold intent-step w [(tick 50) (tick 100)])]
    (is (= [[main-0 0 0 5]] (clicks fx)) "the cursor's piece is put in a free slot")
    (let [[w _] (fx/fold intent-step w [(set-slot 6 main-0 (item :iron_helmet 1)) (tick 150)])]
      (is (= [:failed :not-worn] ((juxt :intent/status :intent/reason) (:plan/intent w)))))))

(deftest a-piece-held-over-an-empty-slot-is-worn-first
  (let [w (assoc (standing {3 (item :iron_chestplate 1) 39 (item :iron_helmet 1)})
                 :plan/goals
                 #{:wood})]
    (is (= :wear-armour (:goal/id (plan/choose w plan/goals))))
    (is (= {:intent/kind :wear :intent/item :iron_chestplate}
           (plan/next-intent w (row :wear-armour) plan/goals)))
    (is (plan/done-by (standing {3 (item :iron_helmet 1) 39 (item :iron_helmet 1)})
                      (row :wear-armour))
        "a spare helmet with one worn is not worn twice")))

(deftest a-carved-pumpkin-is-headwear-the-armour-row-leaves-alone
  ;; worn on the head like a helmet (the dragon row's hood in the End), but the always
  ;; :wear-armour row never dresses the bot in it for the whole run
  (is (= 0 (armour/part :carved_pumpkin)))
  (is (plan/done-by (standing {3 (item :carved_pumpkin 1)}) (row :wear-armour))
      "held over a bare head: still dressed"))

(def kit
  {0 (item :flint_and_steel 1)
   1 (item :water_bucket 1)
   2 (item :bucket 1)
   4 (item :crafting_table 1)})

(deftest only-spare-ingots-make-armour
  (testing "the portal's iron not all held: no ingot is spare"
    (is (plan/done-by (standing (merge (dissoc kit 2) {5 (item :iron_ingot 13)})) (row :armour))))
  (testing "the kit held: five spare make a helmet"
    (is (= :iron_helmet (armour/wanted (standing (merge kit {5 (item :iron_ingot 5)}))))))
  (testing "a helmet held: eight more make a chestplate, seven do not"
    (is (= :iron_chestplate
           (armour/wanted (standing (merge kit
                                           {5 (item :iron_ingot 8) 39 (item :iron_helmet 1)})))))
    (is (nil? (armour/wanted (standing
                              (merge kit {5 (item :iron_ingot 7) 39 (item :iron_helmet 1)}))))))
  (testing "no table at hand: nothing is made, so the row never sends the bot after a tree"
    (is (nil? (armour/wanted (standing (merge (dissoc kit 4) {5 (item :iron_ingot 13)})))))))

(deftest a-helmet-and-chestplate-are-made-and-worn-against-the-model
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :inventory {[:hotbar 0] (item :flint_and_steel 1)
                                                [:hotbar 1] (item :water_bucket 1)
                                                [:hotbar 2] (item :bucket 1)
                                                [:hotbar 3] (item :crafting_table 1)
                                                [:hotbar 4] (item :iron_ingot 13)}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:armour]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= [:iron_helmet :iron_chestplate] [(armour/worn w 0) (armour/worn w 1)])
        (pr-str {:inventory (:player/inventory w)
                 :sim (:sim/inv sim)
                 :story (mapv (juxt :intention/kind :intention/event :intention/reason)
                              (memory/intentions w))}))
    (is (zero? (:iron_ingot (recipe/counts (:player/inventory w)) 0)))
    (is (nil? (:window/cursor w)))
    (is (= [] (:sim/violations sim)))))

(deftest a-click-unanswered-is-never-abandoned
  ;; the armour row's first run against the model: a :wear preempted between its pick-up and the
  ;; answer left the helmet on a cursor the bot believed empty
  (let [w (assoc (standing {})
                 :plan/intent
                 {:intent/kind :wear
                  :intent/item :iron_helmet
                  :intent/status :active
                  :intent/awaiting 5
                  :intent/awaiting-window 0
                  :intent/sent-at 0})]
    (is (not (plan/can-abandon? w)) "the click went out under state id 5, still the window's")
    (is (plan/can-abandon? (assoc w :window/state-id 6)) "answered: the cursor is known again")
    (is (not (plan/can-abandon?
              (assoc w :window/state-id 6 :window/cursor (item :iron_helmet 1)))))))

(deftest wear-put-waits-for-cursor-item
  ;; batch chain-w1 dragon row run 3: container-set-slot advanced state-id before set-cursor-item
  ;; arrived; wear-stage :put must wait up to stage-timeout rather than failing immediately
  (let [w (assoc
           (standing {})
           :time/now 1000
           :plan/intent
           {:intent/kind :wear :intent/item :carved_pumpkin :intent/stage :put :intent/since 900})]
    (is (= :active (:intent/status (:plan/intent (armour/wear-stage w (:plan/intent w)))))
        "still waiting for cursor item within timeout")
    (is (= :failed
           (:intent/status (:plan/intent (armour/wear-stage (assoc w :time/now 7000)
                                                            (:plan/intent w)))))
        "fails past timeout")))
