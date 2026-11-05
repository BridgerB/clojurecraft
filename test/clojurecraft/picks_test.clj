(ns clojurecraft.picks-test
  "The spare pickaxes row (issue #263): three stone pickaxes held before leaving the fortress
   after the blaze rods, made from carried cobblestone, sticks and table when sealed and safe;
   made again whenever safe and down to one pickaxe in the hunt."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.picks :as picks]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.window :as window]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def stone (blocks/default-state :stone))
(defn- solid [& cells] (into {} (map (fn [c] [c stone])) cells))

(def pocket-walls
  "A cell at [8 64 8] surrounded by solid blocks, roofed, and sealed with placed blocks."
  (solid [9 64 8] [9 65 8] [7 64 8] [7 65 8] [8 64 9] [8 65 9] [8 64 7] [8 65 7] [8 66 8] [8 63 8]))

(defn- item [k n] {:item (recipe/item-id k) :count n})

(defn- standing-nether
  "A bot in play on the netherrack floor of one column in the Nether, with full health."
  [inv]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/health 20.0
             :player/loaded? true
             :player/on-ground? true
             :player/dimension dimension/nether
             :world/chunks {[0 0] (world/column {})}
             :player/inventory (into {} (map-indexed vector inv)))))

(defn- sealed-pocket-world
  "A bot sealed in a blaze pocket at full health holding 7 rods, a stone pickaxe, table, sticks,
   and cobblestone."
  [inv]
  (let [feet [8 64 8]
        site {:pocket/cell feet :pocket/stand nil :pocket/dig [] :pocket/placed [[7 64 8] [7 65 8]]}
        brick-id (blocks/default-state :nether_bricks)]
    (->
      (standing-nether inv)
      (assoc :pocket/site site :plan/chain? true :world/chunks {[0 0] (world/column pocket-walls)})
      (memory/observe [8 60 8] brick-id))))

(def rod-kit
  [(item :blaze_rod 7) (item :stone_pickaxe 1) (item :crafting_table 1) (item :stick 12)
   (item :cobblestone 64) (item :cooked_beef 16) (item :iron_sword 1)])

(deftest a-rod-bot-sealed-in-fortress-makes-spare-pickaxes-to-hold-three
  (let [w (sealed-pocket-world rod-kit)
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (is (not (plan/done-by w row)) "not done while holding only 1 pickaxe")
    (is (= :place (:intent/kind (plan/next-intent w row plan/goals)))
        "places the crafting table first")
    (testing "holding three serviceable pickaxes and table in bag completes the row"
      (let [done-w (assoc-in w [:player/inventory 7] (item :stone_pickaxe 1))
            done-w (assoc-in done-w [:player/inventory 8] (item :stone_pickaxe 1))]
        (is (= 3 (picks/serviceable-count done-w)))
        (is (plan/done-by done-w row) "done when three pickaxes and table are held")))))

(deftest does-not-run-while-blaze-in-sight-or-under-danger-health
  (let [w (sealed-pocket-world rod-kit)
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (testing "under danger health: steps aside so refuge/meal runs"
      (let [hurt (assoc w :player/health 8.0)]
        (is (not (picks/safe? hurt)))
        (is (plan/done-by hurt row) "steps aside under danger health")))
    (testing "blaze in sight: steps aside"
      (let [blaze-ent {:entity/type (blocks/entity-types :blaze)
                       :entity/kind :blaze
                       :entity/pos [8.5 64.0 8.5]
                       :entity/health 20.0}
            watching (assoc w :world/entities {42 blaze-ent})]
        (is (picks/blaze-in-sight? watching))
        (is (plan/done-by watching row) "steps aside when blaze is in sight")))
    (testing "unsealed in fortress: steps aside"
      (let [unsealed (assoc w :pocket/site nil)]
        (is (not (picks/sealed-in-fortress? unsealed)))
        (is (plan/done-by unsealed row) "steps aside until sealed in pocket")))))

(deftest outside-fortress-makes-more-only-when-down-to-one-pickaxe
  (let [w (-> (standing-nether [(item :blaze_rod 7) (item :crafting_table 1) (item :stick 12)
                                (item :cobblestone 64) (item :cooked_beef 16) (item :iron_sword 1)])
              (assoc :plan/chain? true))
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (testing "holding 2 pickaxes away from fortress: does not craft"
      (let [two-picks (-> w
                          (assoc-in [:player/inventory 6] (item :stone_pickaxe 1))
                          (assoc-in [:player/inventory 7] (item :stone_pickaxe 1)))]
        (is (= 2 (picks/serviceable-count two-picks)))
        (is (plan/done-by two-picks row) "two pickaxes held is not down to one: row stays done")))
    (testing "holding 1 pickaxe away from fortress: crafts more"
      (let [one-pick (assoc-in w [:player/inventory 6] (item :stone_pickaxe 1))]
        (is (= 1 (picks/serviceable-count one-pick)))
        (is (not (plan/done-by one-pick row)) "down to 1 pickaxe: makes more")))))

(deftest takes-the-placed-table-along
  (let [table-id (blocks/default-state :crafting_table)
        w (-> (sealed-pocket-world [(item :blaze_rod 7) (item :stone_pickaxe 1)
                                    (item :stone_pickaxe 1) (item :stone_pickaxe 1) (item :stick 8)
                                    (item :cobblestone 58)])
              (assoc-in [:world/chunks [0 0]] (world/column (assoc pocket-walls [9 64 8] table-id)))
              (memory/observe [9 64 8] table-id))
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (is (= 3 (picks/serviceable-count w)))
    (is (picks/placed-table? w))
    (is (not (plan/done-by w row)) "not done while table is still standing on the ground")
    (is (= :dig (:intent/kind (plan/next-intent w row plan/goals)))
        "digs up the placed table to take it along")))

(deftest drops-one-junk-stack-when-sealed-with-36-slots-full
  (let [full-inv (into {}
                       (map
                        vector
                        (range 36)
                        (concat rod-kit (repeat 28 (item :netherrack 64)) [(item :diorite 64)])))
        w (-> (sealed-pocket-world [])
              (assoc :player/inventory full-inv))
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (is (zero? (window/free-count w)) "36 of 36 slots full")
    (is (picks/room-needed? w))
    (let [i (plan/next-intent w row plan/goals)]
      (is (= :toss (:intent/kind i)))
      (is (= 1 (:intent/free i)))
      (testing "blaze rods and food are not tossed"
        (let [toss-slot (window/reserve-slot w 1)]
          (is (not= (recipe/item-id :blaze_rod) (:item (get full-inv toss-slot))))
          (is (not= (recipe/item-id :cooked_beef) (:item (get full-inv toss-slot))))
          (is (not= (recipe/item-id :stone_pickaxe) (:item (get full-inv toss-slot)))))))))

(deftest throws-junk-so-the-table-comes-back-to-a-full-bag
  ;; 0ace52e lane 42024297742 s1 Clj_r22: 36 of 36 slots used after the pickaxes were made, the
  ;; table lying at its feet, :collect-drops :for :take-table failing :not-picked-up every tick
  ;; until :nothing-to-do (41 of 42 such endings on that commit)
  (let [table-id (blocks/default-state :crafting_table)
        full-inv (into {}
                       (map vector
                            (range 36)
                            (concat [(item :blaze_rod 7) (item :stone_pickaxe 1)
                                     (item :stone_pickaxe 1) (item :stone_pickaxe 1) (item :stick 8)
                                     (item :cooked_beef 16) (item :iron_sword 1)]
                                    (repeat 28 (item :netherrack 64))
                                    [(item :diorite 64)])))
        placed (-> (sealed-pocket-world [])
                   (assoc :player/inventory full-inv)
                   (assoc-in [:world/chunks [0 0]]
                             (world/column (assoc pocket-walls [9 64 8] table-id)))
                   (memory/observe [9 64 8] table-id))
        row (first (filter #(= :spare-picks (:goal/id %)) plan/goals))]
    (is (zero? (window/free-count placed)) "36 of 36 slots full")
    (is (picks/table-needed? placed))
    (is (= :toss (:intent/kind (plan/next-intent placed row plan/goals)))
        "a junk stack is thrown before the table is dug, so it can be picked up")
    (testing "with nothing to throw the table is left, never collected for ever"
      (let [no-junk (assoc placed
                           :player/inventory
                           (into {} (map vector (range 36) (repeat 36 (item :blaze_rod 1)))))]
        (is (not (picks/table-room? no-junk)))
        (is (not (picks/table-needed? no-junk)))))))

(deftest a-sim-pass-makes-the-pickaxes
  ;; Verify that in a safe room with carried materials, a sim pass places the table,
  ;; crafts stone pickaxes up to 3, and takes the table along.
  (let [floor-id (blocks/default-state :netherrack)
        ;; A 5x5 room with perimeter walls, floor at 63, roof at 66, clear 3x3 inside (7..9, 7..9)
        room (into {}
                   (concat (for [x (range 6 11) z (range 6 11)] [[x 63 z] floor-id])
                           (for [x (range 6 11) z (range 6 11)] [[x 66 z] floor-id])
                           (for [x [6 10] z (range 6 11) y [64 65]] [[x y z] floor-id])
                           (for [z [6 10] x (range 7 10) y [64 65]] [[x y z] floor-id])))
        inv {[:hotbar 0] (item :stone_pickaxe 1)
             [:hotbar 1] (item :crafting_table 1)
             [:hotbar 2] (item :stick 12)
             [:hotbar 3] (item :cobblestone 64)
             [:hotbar 4] (item :blaze_rod 7)
             [:hotbar 5] (item :cooked_beef 16)
             [:hotbar 6] (item :iron_sword 1)}
        [w sim] (sim/run main/step
                         (assoc (game/init fx/opts) :player/dimension dimension/nether)
                         (sim/init {:column (world/column-bytes room)
                                    :dimension dimension/nether
                                    :spawn [8.5 64.0 8.5]
                                    :inventory inv})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:spare-picks]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (picks/serviceable-count w) 3) "holds three stone pickaxes")
    (is (pos? (long (get (make/counts w) :crafting_table 0))) "crafting table taken along in bag")
    (is (= 7 (:blaze_rod (recipe/counts (:player/inventory w)))) "rods must not drop")
    (is (empty? (:sim/violations sim)))))
