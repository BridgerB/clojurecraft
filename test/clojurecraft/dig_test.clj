(ns clojurecraft.dig-test
  "The break formula against vanilla's numbers (issue #9's worked table), with every value read
   from the generated tables, never typed in here but as the expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]))

(defn state [n] (first (blocks/states-where (fn [[name]] (= name n)))))
(defn item [n] (get blocks/items n))

(def stone (state :stone))
(def cobblestone (state :cobblestone))
(def dirt (state :dirt))
(def oak-log (state :oak_log))
(def iron-ore (state :iron_ore))
(def obsidian (state :obsidian))
(def bedrock (state :bedrock))
(def none {})

(deftest the-tables-come-from-the-game
  (is (= 1.5 (blocks/hardness stone)))
  (is (= 50.0 (blocks/hardness obsidian)))
  (is (= -1.0 (blocks/hardness bedrock)))
  (is (= :pickaxe (blocks/tool-of stone)))
  (is (= :axe (blocks/tool-of oak-log)))
  (is (= :shovel (blocks/tool-of dirt)))
  (is (= :stone (blocks/needs-tier iron-ore)))
  (is (= :diamond (blocks/needs-tier obsidian)))
  (is (nil? (blocks/needs-tier stone)))
  (is (= {:tier :wooden :kind :pickaxe} (blocks/tool (item :wooden_pickaxe))))
  (is (= {:tier :copper :kind :axe} (blocks/tool (item :copper_axe))))
  (is (nil? (blocks/tool (item :oak_log))))
  (is (= 5.0 (:speed (blocks/materials :copper))) "read from ToolMaterial, not believed"))

(deftest the-worked-table
  (testing "by hand"
    (is (= 3000 (dig/ms oak-log nil none)) "today's constant, now derived")
    (is (= 750 (dig/ms dirt nil none)))
    (is (= 2300 (dig/ms stone nil none)) "the issue said 7500 and no drop; the game's tags say stone needs no tier, so a hand harvests it, only slower (45 ticks)")
    (is (dig/harvest? stone nil)))
  (testing "with the mining tool"
    (is (= 1500 (dig/ms oak-log (item :wooden_axe) none)))
    (is (= 1150 (dig/ms stone (item :wooden_pickaxe) none)))
    (is (= 600 (dig/ms stone (item :stone_pickaxe) none)))
    (is (= 1500 (dig/ms cobblestone (item :wooden_pickaxe) none)))
    (is (= 750 (dig/ms cobblestone (item :stone_pickaxe) none)))
    (is (= 1150 (dig/ms iron-ore (item :stone_pickaxe) none))))
  (testing "the wrong tier breaks but drops nothing"
    (is (= 7500 (dig/ms iron-ore (item :wooden_pickaxe) none)))
    (is (not (dig/harvest? iron-ore (item :wooden_pickaxe))))
    (is (= 41700 (dig/ms obsidian (item :iron_pickaxe) none)))
    (is (not (dig/harvest? obsidian (item :iron_pickaxe))))
    (is (= 9400 (dig/ms obsidian (item :diamond_pickaxe) none)))
    (is (dig/harvest? obsidian (item :diamond_pickaxe))))
  (testing "a tool of the wrong kind is a hand"
    (is (= 2300 (dig/ms stone (item :wooden_axe) none))))
  (testing "copper harvests what stone does"
    (is (dig/harvest? iron-ore (item :copper_pickaxe)))
    (is (not (dig/harvest? obsidian (item :copper_pickaxe)))))
  (testing "the penalties divide the speed by five each"
    ;; the issue's 5750 multiplied the ms by five; the game divides the speed, and ceil runs
    ;; after: speed 0.4 → ceil(112.5) = 113 ticks
    (is (= 5650 (dig/ms stone (item :wooden_pickaxe) {:off-ground? true})))
    (is (= 5650 (dig/ms stone (item :wooden_pickaxe) {:eyes-in-water? true})))
    (is (= 28150 (dig/ms stone (item :wooden_pickaxe) {:eyes-in-water? true :off-ground? true}))))
  (testing "unbreakable and unknown"
    (is (nil? (dig/ms bedrock (item :diamond_pickaxe) none)))
    (is (nil? (dig/ms 999999 nil none)))))

(deftest the-best-tool-is-the-fastest-that-harvests
  (let [inv {0 {:item (item :oak_log) :count 3} 3 {:item (item :wooden_pickaxe) :count 1}
             5 {:item (item :stone_axe) :count 1} 7 {:item (item :wooden_axe) :count 1}}]
    (is (= [3 (item :wooden_pickaxe)] (dig/best-tool stone inv none)))
    (is (= [5 (item :stone_axe)] (dig/best-tool oak-log inv none)) "the faster of the two axes")
    (is (nil? (dig/best-tool dirt inv none)) "no shovel held: the hand is as good")
    (is (nil? (dig/best-tool iron-ore inv none)) "needs stone; the wooden pickaxe would drop nothing, and the stone axe is the wrong kind")
    (is (= [3 (item :stone_pickaxe)] (dig/best-tool iron-ore (assoc inv 3 {:item (item :stone_pickaxe) :count 1}) none))
        "a stone pickaxe in that slot reaches it")
    (is (nil? (dig/best-tool stone {} none)) "nothing held")))
