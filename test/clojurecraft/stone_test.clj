(ns clojurecraft.stone-test
  "The :mine act over the rows that name what they dig: the tool each needs, the drops a
   collect waits for, the stairs it cuts when nothing is in sight, and where it stops."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.stone :as stone]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn standing
  "On a bare stone floor (nothing to mine in sight but stone) with feet at y, holding items by
   name in the hotbar."
  [y & items]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [5.5 (double y) 5.5]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory
         (into {} (map-indexed (fn [i n] [i {:item (blocks/items n) :count 1}])) items)
         :world/chunks {[0 0] (world/column {})}))

(deftest each-row-collects-what-its-blocks-drop
  (is (= #{(blocks/items :cobblestone)} (stone/drops-of-row (row :mine-stone))))
  (is (= #{(blocks/items :coal)} (stone/drops-of-row (row :mine-coal))))
  (is (= #{(blocks/items :raw_iron)} (stone/drops-of-row (row :mine-iron))))
  (is (= #{(blocks/items :gravel) (blocks/items :flint)} (stone/drops-of-row (row :dig-gravel)))
      "gravel's loot table: flint one time in ten, else gravel")
  (is (= (stone/states-of-row (row :mine-stone)) (stone/states-of-row {})) "stone by default"))

(deftest the-tool-a-row-needs
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64) (row :mine-iron))))
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64 :wooden_pickaxe) (row :mine-iron)))
      "iron ore drops only to stone or better")
  (is (= :stairs-down (:intent/kind (stone/mine-next (standing 64) (row :dig-gravel))))
      "gravel drops to a hand"))

(deftest with-nothing-in-sight-the-stairs-stop-at-the-rows-depth
  (testing "stairs: six down at a time"
    (is (= {:intent/kind :stairs-down :intent/to-y 58}
           (stone/mine-next (standing 64 :stone_pickaxe) (row :mine-iron)))))
  (testing "never below the row's depth: there, a tunnel"
    (is (= {:intent/kind :stairs-down :intent/to-y 16}
           (stone/mine-next (standing 20 :stone_pickaxe) (row :mine-iron))))
    (is (= {:intent/kind :tunnel :intent/dir [1 0] :intent/length stone/tunnel-length}
           (stone/mine-next (standing 16 :stone_pickaxe) (row :mine-iron))))
    (is (= [-1 0]
           (:intent/dir (stone/mine-next
                         (assoc (standing 16 :stone_pickaxe) :plan/go-at [40.5 70.0 5.5])
                         (row :mine-iron))))
        "outward: away from the landing"))
  (testing "a heading refused, or with no rock left to cut, is not taken; with none, deeper"
    (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
          w (assoc (standing 16 :stone_pickaxe)
                   :plan/go-at [5.5 70.0 40.5] ; south: north leads farthest from it
                   :world/chunks
                   {[0 0] (world/column {[7 16 5] lava [5 16 6] lava [5 16 4] 0 [5 17 4] 0})})]
      (is (= [-1 0] (stone/tunnel-heading w))
          "north is an open tunnel, east borders lava, so west (south is lava itself)")
      (is (= {:intent/kind :stairs-down :intent/to-y 10}
             (stone/mine-next (assoc w
                                     :world/chunks
                                     {[0 0]
                                      (world/column
                                       {[7 16 5] lava [5 16 6] lava [3 16 5] lava [5 16 3] lava})})
                              (row :mine-iron)))
          "lava two along every heading: stairs six deeper")))
  (testing "stone has no depth: always down"
    (is (= {:intent/kind :stairs-down :intent/to-y -40}
           (stone/mine-next (standing -34 :wooden_pickaxe) (row :mine-stone))))))

(def sealed-ore
  "Iron ore buried in the floor at [8 61 8], with a two-cell pocket beside it to stand in
   ([9 61 8] and [9 62 8]) and stone all round: exposed, and no route from the surface reaches
   it, as the live run's ore down in a cave. Another iron ore stands in the open at
   [14 64 14], farther off."
  {[8 61 8] (blocks/default-state :iron_ore)
   [9 61 8] blocks/air
   [9 62 8] blocks/air
   [14 64 14] (blocks/default-state :iron_ore)})

(deftest an-ore-no-route-reaches-is-passed-over
  ;; live iron-kit run 4: three walks to iron in an old excavation below, each failing :no-path
  ;; at once, and the third ended the plan
  (let [w (assoc (standing 64 :stone_pickaxe) :world/chunks {[0 0] (world/column sealed-ore)})]
    (is (= [8 61 8] (first (stone/exposed-of-states w (stone/states-of-row (row :mine-iron)))))
        "the sealed ore is the nearest exposed one")
    (is (=
         {:intent/kind :walk :intent/target [14 64 14] :intent/for :mine-iron :intent/pickup? true}
         (stone/mine-next w (row :mine-iron)))
        "the open one, farther, is the one walked to")))

(def corridor
  "A tunnel two high at y 64-65 along x from 2 to 12 at z 8, cut into stone filling y 64-70, with
   iron ore in its wall at the head (x 5), in its ceiling (x 8) and in its floor (x 11)."
  (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
         (into {} (for [x (range 2 13) y [64 65]] [[x y 8] blocks/air]))
         {[5 65 9] (blocks/default-state :iron_ore)
          [8 66 8] (blocks/default-state :iron_ore)
          [11 63 8] (blocks/default-state :iron_ore)}))

(deftest a-tunnel-shows-ore-at-the-head-and-overhead
  ;; batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 seeing only the walls at its feet
  (let [w (assoc (standing 64 :stone_pickaxe)
                 :player/pos [2.5 64.0 8.5]
                 :world/chunks {[0 0] (world/column corridor)})
        seen (set (stone/exposed-of-states w (stone/states-of-row (row :mine-iron))))]
    (is (contains? seen [5 65 9])
        "in the wall at the head: dug from the cell beside it, a block lower")
    (is (contains? seen [8 66 8]) "in the ceiling: dug from under it")
    (is (not (contains? seen [11 63 8])) "in the floor: the drop would lie under the feet")))
