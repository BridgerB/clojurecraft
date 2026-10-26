(ns clojurecraft.craft-test
  "The :craft intent tick by tick, against canned server answers (no sim, no socket)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.craft]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]
            [clojurecraft.world :as world]))

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

(def main-0 (inventory/slot [:main 0]))

(deftest a-lost-click-resyncs-and-never-takes-blind
  (let [[w fx] (fx/fold step (in-play) (map tick (range 50 3500 50)))]
    (is (= [[(inventory/left-click hotbar-0) 5] [(inventory/left-click main-0) -1]] (clicks fx))
        "past the answer timeout the one click sent is a resync into a free slot, never a repeat")
    (is (= :active (:intent/status (:plan/intent w))))
    (let [[w fx] (fx/fold step w (map tick (range 3500 10000 50)))]
      (is (= [[(inventory/left-click main-0) -1]] (clicks fx))
          "a server that never answers gets one more resync, and the result is never clicked")
      (is (= :stale-window (:intent/reason (:plan/intent w)))
          "the third lost click fails the craft"))))

(deftest a-late-server-answers-the-resync-and-the-craft-goes-on
  ;; issues #131/#132: a loaded server answered later than the timeout; the craft picks up from
  ;; the full window the resync brings back instead of failing :stale-window
  (let [[w _] (fx/fold step (in-play) (map tick (range 50 3300 50)))
        items (assoc (vec (repeat (inventory/slot-count (inventory/layouts :inventory)) nil))
                     hotbar-0
                     {:item planks :count 8})
        [w fx] (fx/fold step
                        w
                        [(packet {:packet/name :container-set-content
                                  :window-id 0
                                  :state-id 9
                                  :items items
                                  :carried nil}) (tick 3350) (tick 3400)])]
    (is (= [[(inventory/left-click hotbar-0) 9]] (clicks fx))
        "the craft settles again and lays the planks with the state id the resync brought")
    (is (= 1 (get-in w [:plan/intent :intent/lost])))
    (is (= :active (:intent/status (:plan/intent w))))))

(deftest a-full-inventory-throws-junk-before-it-crafts
  ;; race 38805920624 runner 5 (issues #172, #192): eight bots at 36 full slots shift-clicked a
  ;; craft's result or a furnace's output into the inventory, the server had nowhere to put it
  ;; and answered nothing, and the resync found no free slot: :inventory-full
  (let [sand (recipe/item-id :sand)
        full (-> (in-play)
                 (assoc :world/chunks {[0 0] (world/column {})} :player/on-ground? true)
                 (update :player/inventory into (for [p (range 1 36)] [p {:item sand :count 64}])))
        [w fx] (fx/fold step full [(tick 50) (tick 100) (tick 150)])]
    (is (= [[(inventory/toss-click main-0) 5]] (clicks fx))
        "one stack of junk thrown, once the eyes look up, and nothing laid")
    (is (= window/toss-pitch (second (:player/look w))))
    (let [[_ fx] (fx/fold step w [(set-slot 6 main-0 nil) (tick 200) (tick 250)])]
      (is (= [[(inventory/left-click hotbar-0) 6]] (clicks fx))
          "with a slot free for the sticks, the planks are laid"))))

(deftest cobblestone-is-thrown-only-above-what-is-kept
  (let [cobblestone {:item (recipe/item-id :cobblestone) :count 64}
        w {:player/inventory {9 cobblestone 10 cobblestone 11 cobblestone}}]
    (is (= 9 (window/junk-slot w)) "192 held: a stack goes, 128 stay")
    (is (nil? (window/junk-slot (update w :player/inventory dissoc 11))) "128 held: nothing goes")
    (is (= 12
           (window/junk-slot
            (assoc-in w [:player/inventory 12] {:item (recipe/item-id :sand) :count 1})))
        "junk before any cobblestone")))

(deftest every-junk-item-is-an-item-of-the-game
  (is (every? recipe/item-id window/junk) (pr-str (remove recipe/item-id window/junk))))

(deftest fortress-bricks-are-junk-a-full-inventory-throws
  ;; issue #580: 705 nether bricks from digging through the fortress, every slot full, and
  ;; no stack a craft could throw to make room for its result
  (let [bricks {:item (recipe/item-id :nether_bricks) :count 64}
        w {:player/inventory {9 bricks 10 bricks}}]
    (is (= 9 (window/junk-slot w)) "a stack of the fortress's bricks goes first")))

(deftest surplus-building-blocks-make-room-without-spending-the-reserve
  (doseq [[item keep] window/filler-keep]
    (testing (name item)
      (let [stack {:item (recipe/item-id item) :count 64}
            w (assoc (in-play) :player/inventory {9 stack 10 stack 11 stack})
            v (window/view w :inventory)]
        (is (= 128 keep))
        (is (= 9 (window/junk-slot w)) "192 held: one stack can go")
        (is (nil? (window/junk-slot (update w :player/inventory dissoc 11)))
            "128 held: the building reserve stays")
        (is (nil? (window/junk-slot (-> w
                                        (assoc-in [:player/inventory 10 :count] 63)
                                        (assoc-in [:player/inventory 11 :count] 2))))
            "129 held: no whole stack may take the reserve below 128")
        (is (= 11 (window/junk-slot (assoc-in w [:player/inventory 11 :count] 1)))
            "a partial surplus stack may go when two full stacks stay")
        (is (nil? (window/tossable w v)) "a free slot needs no discard"))))
  (is (nil? (window/junk-slot {:player/inventory {9 {:item (recipe/item-id :raw_iron) :count 64}
                                                  10 {:item (recipe/item-id :coal) :count 64}
                                                  11 {:item (recipe/item-id :stone_pickaxe)
                                                      :count 1}}}))
      "ores, fuel and tools are not surplus building blocks"))

(deftest a-worn-tool-with-a-better-one-beside-it-is-thrown
  ;; issues #463, #457, #172, #446: sixteen worn stone pickaxes took sixteen slots
  (let [pick (fn [damage]
               {:item (recipe/item-id :stone_pickaxe) :count 1 :components {:damage damage}})
        w {:player/inventory {9 (pick 110) 10 (pick 20) 11 (pick 100)}}]
    (is (= 9 (window/junk-slot w)) "the most worn of the three goes first")
    (is (= 11 (window/junk-slot (update w :player/inventory dissoc 9))) "then the next")
    (is (nil? (window/junk-slot (update w :player/inventory dissoc 9 11)))
        "the last stone pickaxe stays, fresh")
    (is (nil? (window/junk-slot {:player/inventory {9 (pick 110)}})) "and the last stays, worn")
    (is (nil? (window/junk-slot {:player/inventory {9 (pick 20) 10 (pick 20)}}))
        "two fresh ones are both spares")
    (is (nil? (window/junk-slot {:player/inventory {9 (pick 110)
                                                    10 {:item (recipe/item-id :wooden_pickaxe)
                                                        :count 1}}}))
        "a better tool of another item is no reason")))
