(ns clojurecraft.craft-test
  "The :craft intent tick by tick, against canned server answers (no sim, no socket)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.craft]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]))

(use-fixtures :once fx/instrumented)

(def planks (recipe/item-id :oak_planks))
(def stick (recipe/item-id :stick))
(def button (recipe/item-id :oak_button))

(defn in-play
  "Holding 8 planks in hotbar slot 0, window 0 at state id 5, crafting a stick."
  []
  (->
    (game/init fx/opts)
    (assoc
     :bot/phase :play
     :player/pos [0.5 64.0 0.5]
     :player/loaded? true
     :window/state-id 5
     :player/inventory {0 {:item planks :count 8}}
     :plan/intent
     {:intent/kind :craft :intent/recipe :stick :intent/window :inventory :intent/status :active})))

(defn step
  "The world reducer, then one run of the current intent per tick (the planner is not needed)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e))
             (:plan/intent w)
             (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})
(def hotbar-0 (inventory/slot [:hotbar 0]))
(defn grid [n] (inventory/slot [:grid n]))
(defn set-slot
  "The server setting window 0's slot s to item, under state-id."
  [state-id s item]
  (packet {:packet/name :container-set-slot :window-id 0 :state-id state-id :slot s :item item}))
(defn clicks
  "The clicks the bot sent, each [named click, state id it carried]."
  [fx]
  (vec (for [p (fx/packets fx)
             :when (= :container-click (:packet/name p))]
         [{:click/slot (:slot p) :click/button (:button p) :click/mode (:mode p)} (:state-id p)])))

(deftest a-stick-is-laid-one-answered-click-at-a-time
  (let [[w fx] (fx/fold step (in-play) [(tick 50) (tick 100) (tick 150) (tick 200)])]
    (testing "settle plans, then the first click goes out and nothing more until the server answers"
      (is (= [[(inventory/left-click hotbar-0) 5]] (clicks fx)))
      (is (= 5 (get-in w [:plan/intent :intent/awaiting]))))
    (let [[w fx] (fx/fold step
                          w
                          [(set-slot 6 hotbar-0 nil)
                           (packet {:packet/name :set-cursor-item :item {:item planks :count 8}})
                           (tick 250) (tick 300)])]
      (is (= [[(inventory/right-click (grid 1)) 6]] (clicks fx))
          "the next click carries the new state id")
      (let [[w fx] (fx/fold step
                            w
                            [(set-slot 7 inventory/result-slot {:item stick :count 4})
                             (set-slot 8 (grid 1) {:item planks :count 1}) (tick 350)
                             (set-slot 9 (grid 3) {:item planks :count 1}) (tick 400)
                             (set-slot 10 hotbar-0 {:item planks :count 6}) (tick 450) (tick 500)])]
        (is
         (= [[(inventory/right-click (grid 3)) 8] [(inventory/left-click hotbar-0) 9]
             [(inventory/shift-click inventory/result-slot) 10]]
            (clicks fx))
         "one item into cell 3, the remainder back, then - slot 0 verified as exactly 4 sticks - the take")
        (is (= {0 {:item stick :count 4} 1 {:item planks :count 1} 3 {:item planks :count 1}}
               (:window/grid w)))
        (let [[w fx] (fx/fold step w [(tick 550) (tick 600)])]
          (is (= [] (clicks fx)) "nothing more until the take is answered")
          (let [[w _] (fx/fold step
                               w
                               [(set-slot 11 inventory/result-slot nil) (set-slot 12 (grid 1) nil)
                                (set-slot 13 (grid 3) nil)
                                (set-slot 14 (inventory/slot [:hotbar 1]) {:item stick :count 4})
                                (tick 650)])]
            (is (intent/done? (:plan/intent w)))
            (is (= 4 (inventory/item-count w stick)))))))))

(deftest a-wrong-result-is-never-taken
  (let [w (-> (in-play)
              (assoc :window/grid {0 {:item button :count 1} 1 {:item planks :count 1}})
              (update :plan/intent assoc
                      :intent/stage :verify
                      :intent/since 0
                      :intent/result stick
                      :intent/makes 4))
        [w fx] (fx/fold step w [(tick 50)])]
    (is (= :wrong-result (:intent/reason (:plan/intent w))))
    (is (= [] (clicks fx)) "no click at all, so no button is minted")
    (testing "the next craft reclaims the grid before laying anything"
      (let [w (assoc w
                     :plan/intent
                     {:intent/kind :craft
                      :intent/recipe :stick
                      :intent/window :inventory
                      :intent/status :active})
            [_ fx] (fx/fold step w [(tick 100)])]
        (is (= [[(inventory/shift-click (grid 1)) 5]] (clicks fx))
            "a shift-click on the stray plank, not on the result")))))

(deftest a-stale-state-id-in-the-table-window-is-just-another-answer
  ;; vanilla applies a click that carries a stale state id and answers with the full window;
  ;; the bot treats that like any answer: the state id moved, the next click carries the new one
  (let [table {:window/id 2
               :window/menu-type (blocks/menus :crafting)
               :window/state-id 5
               :window/slots {}}
        w (-> (in-play)
              (assoc :window/open table
                     :player/inventory {0 {:item planks :count 3} 1 {:item stick :count 2}}
                     :plan/intent
                     {:intent/kind :craft
                      :intent/recipe :wooden_pickaxe
                      :intent/window :table
                      :intent/status :active}))
        [w fx] (fx/fold step w [(tick 50) (tick 100)])
        first-click (first (fx/packets fx))]
    (is (= [(inventory/left-click (inventory/slot-in :table [:hotbar 0])) 5] (first (clicks fx)))
        "the planks picked up from hotbar 0")
    (is (= 2 (:window-id first-click)))
    (let [items (assoc (vec (repeat (inventory/slot-count (inventory/layouts :table)) nil))
                       (inventory/slot-in :table [:hotbar 1])
                       {:item stick :count 2})
          [w _] (fx/fold step
                         w
                         [(packet {:packet/name :container-set-content
                                   :window-id 2
                                   :state-id 9
                                   :items items
                                   :carried {:item planks :count 3}})])
          _ (is (= {:item planks :count 3} (:window/cursor w))
                "the resend says what the cursor really holds")
          [_ fx] (fx/fold step w [(tick 150)])]
      (is (= [[(inventory/right-click (inventory/slot-in :table [:grid 1])) 9]] (clicks fx))
          "the full resend moved the state id; the next click carries 9"))))

(deftest a-lost-click-fails-stale-and-never-takes-blind
  (let [[w fx] (fx/fold step (in-play) (map tick (range 50 3500 50)))]
    (is (= [[(inventory/left-click hotbar-0) 5]] (clicks fx))
        "the click is never repeated blindly and the result is never clicked")
    (is (= :stale-window (:intent/reason (:plan/intent w))))))
