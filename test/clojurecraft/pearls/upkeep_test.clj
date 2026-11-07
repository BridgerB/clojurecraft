(ns clojurecraft.pearls.upkeep-test
  "The pearl hunt's sword and pickaxe upkeep."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.plan :as plan]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest a-worn-pickaxe-is-kept-for-the-burrow
  ;; gym batch pearls-warped3-a (plan run 39529384355) run 1: three kills from a burrow, then the
  ;; stairs, tunnels and bores toward endermen wore the iron pickaxe out at fourteen minutes, and
  ;; with no burrow to be cut it stood in :lookout for the last twenty-five
  (let [worn (assoc (ps/item :iron_pickaxe 1) :components {:damage 220})
        below (ps/enderman [8.5 40.0 8.5])
        w (assoc-in (ps/standing {7 below}) [:player/inventory 1] worn)
        pocket (-> (ps/standing {7 (ps/enderman [8.5 64.0 20.5])})
                   (assoc :world/chunks (ps/cave (ps/passage 8 8)))
                   (assoc-in [:player/inventory 1] worn))]
    (is (= 30 (pupkeep/digs-left w)) "an iron pickaxe has 250 digs, 220 spent")
    (is (not (pupkeep/spare? w)) "fewer than a burrow's twice over")
    (is (pupkeep/spare? (assoc-in w [:player/inventory 1 :components :damage] 218)))
    (is (nil? (pmove/descend w {7 below})) "thirty digs left: no stairs down toward one")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals)))
    (is (nil? (pmove/bore pocket (psense/endermen pocket pparams/descend-radius)))
        "nor a tunnel toward one")
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent
            (assoc-in (ps/standing {7 (ps/enderman [11.5 64.0 8.5])}) [:player/inventory 1] worn)
            ps/row
            plan/goals))
        "the burrow is what the digs are kept for")))

(deftest a-pickaxe-worn-out-on-burrows-ends-the-hunt-in-the-open
  ;; sim probe 39890964793 (seed 5): its pickaxe broke on burrows, and it struck calm endermen in
  ;; the open in worlds R2 and R3 and was slain, R2 nine times
  (let [w (ps/standing {7 (ps/enderman [11.5 64.0 8.5])})
        spent
        (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "never held one: the hunt in the open, as before")
    (is (pupkeep/worn-out? spent))
    (is (= {:plan/wait :no-pickaxe} (plan/next-intent spent ps/row plan/goals))
        "spent on burrows: no calm one struck in the open")
    (is (= :fight
           (:intent/kind (plan/next-intent (assoc-in spent [:world/entities 7 :entity/creepy?] true)
                                           ps/row
                                           plan/goals)))
        "one coming is still met")
    (is (not (pupkeep/worn-out? (assoc-in spent [:player/inventory 1] (ps/item :iron_pickaxe 1))))
        "a pickaxe held")))

(deftest empty-hands-hunt-the-wood-of-a-pickaxe
  ;; gym batch pearls-w1 (plan run 40387599204) run 1: slain in the forest, it respawned at
  ;; the overworld spawn, remade a wooden sword from the spruces there, and twenty seconds
  ;; later failed :no-pickaxe with sticks and its table held: the log hunt that may start a
  ;; bare-handed sword was refused for the pickaxe, so the death still ended the plan
  (let [bare-floor (into {} (for [cx (range -3 4) cz (range -3 4)] [[cx cz] (world/column {})]))
        respawned
        (-> (ps/standing {})
            (assoc :world/chunks bare-floor
                   :player/inventory
                   {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3) 2 (ps/item :crafting_table 1)}
                   :time/now 0)
            (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        walked (plan/next-intent respawned ps/row plan/goals)
        worn
        (assoc-in respawned [:player/inventory 3] (assoc (ps/item :iron_pickaxe 1) :durability 11))]
    (is (pupkeep/worn-out? respawned) "a burrow cut this run and no pickaxe held")
    (is (= :walk (:intent/kind walked)))
    (is (= :log-hunt (:intent/for walked))
        "no pickaxe at all: out for the wood of one, never the :no-pickaxe wait")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent worn ps/row plan/goals))
        "a worn pickaxe still held: no hunt for wood, the hunt goes on with it"))
  ;; gym batch pearls-w2 (plan run 40428200343) run 3: the same respawn with spruces
  ;; remembered round it, so the planner asked for the remembered trunk (:log), not the
  ;; hunt; the table taken up after the sword craft counted as the table's pieces
  ;; (sword/pieces?) and the walk was refused, :no-pickaxe again in 184 s
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        respawned
        (-> (ps/standing {})
            (assoc :world/chunks {[0 0] col}
                   :player/inventory
                   {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3) 2 (ps/item :crafting_table 1)}
                   :time/now 0)
            (memory/remember-column [0 0] col)
            (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        walked (plan/next-intent respawned ps/row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked))
        "a table held is no wood: the remembered trunk is walked to for the pickaxe"))
  ;; gym batch pearls-w2 (plan run 40428200343) run 2: respawned, remade its wooden sword,
  ;; dug up the placed table (:take-table), and failed :no-pickaxe on the next tick because
  ;; pick-acts had no :collect, leaving the dropped table uncollected
  (let [respawned (-> (ps/standing {})
                      (assoc :player/inventory {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3)}
                             :time/now 0
                             :plan/last
                             {:intent/kind :dig
                              :intent/target [3 94 49]
                              :intent/for :take-table
                              :intent/status :done})
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        collected (plan/next-intent respawned ps/row plan/goals)]
    (is (= :collect (:intent/kind collected)))
    (is (= :take-table (:intent/for collected))
        "a dug table is collected toward the pickaxe remake, never the :no-pickaxe wait")))

(deftest a-table-held-hides-no-log-chain-from-the-pickaxe-decide
  ;; gym batch pearls-w3 (plan run 40434266158) run 1: slain ninety seconds in, it
  ;; respawned at the overworld spawn, remade its wooden sword, took its table up, and
  ;; walked to the spruce trunk beside it 356,882 times to the five-hour timeout: the
  ;; table held counted as the table's pieces (sword/pieces?), so each done walk's chain
  ;; was hidden from the decide and the walk was asked for again, never the dig
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        walked {:intent/kind :walk :intent/target [12 64 8] :intent/for :log :intent/status :done}
        respawned
        (-> (ps/standing {})
            (assoc :world/chunks {[0 0] col}
                   :player/inventory
                   {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3) 2 (ps/item :crafting_table 1)}
                   :time/now 0
                   :plan/last walked)
            (memory/remember-column [0 0] col)
            (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pupkeep/for-pick-decide respawned)))
        "sticks and a table held, no log and no plank: the done walk's chain stays")
    (is (nil? (:plan/last (pupkeep/for-pick-decide
                           (assoc-in respawned [:player/inventory 3] (ps/item :spruce_log 1)))))
        "a log counted: the chain is finished and hidden, as before")
    (is (= [:dig :log]
           ((juxt :intent/kind :intent/for) (plan/next-intent respawned ps/row plan/goals)))
        "so the decide continues to the dig, never the same walk again")))

(deftest table-held-hides-no-log-chain-from-sword-decide
  ;; remaking a sword when a table is held: sword/for-decide dropped :plan/last
  ;; whenever pieces? held, so table held without planks or logs lost the done walk
  ;; and re-planned it every tick. for-sword-decide hides the chain only once a log
  ;; is counted (log-counted?), as for-pick-decide does for a pickaxe
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        walked {:intent/kind :walk :intent/target [12 64 8] :intent/for :log :intent/status :done}
        w (-> (ps/standing {})
              (assoc :world/chunks {[0 0] col}
                     :player/inventory {0 (ps/item :crafting_table 1)}
                     :time/now 0
                     :plan/last walked)
              (memory/remember-column [0 0] col)
              (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pupkeep/for-sword-decide w)))
        "table held, no log and no plank: sword decide keeps the done walk's chain")
    (is (nil? (:plan/last (pupkeep/for-sword-decide
                           (assoc-in w [:player/inventory 1] (ps/item :spruce_log 1)))))
        "a log counted: sword decide hides the finished chain")
    (is (= [:dig :log] ((juxt :intent/kind :intent/for) (plan/next-intent w ps/row plan/goals)))
        "so the decide continues to the dig, never the walk again")))

(deftest nether-warped-stem-walk-advances-to-dig-not-loop
  ;; gym batch pearls-w3 (plan run 40464154227) run 2: in the Nether at pos
  ;; [2374.4 38 2125.3], with four warped planks and a worn pickaxe held, it
  ;; re-issued intent :walk [2374 39 2124] :for :log 186,397 times because planks
  ;; counted as pieces (sword/pieces?) and dropped :plan/last after each arrival.
  ;; With log-counted?, :plan/last stays and the chain continues to the dig
  (let [stem (first (blocks/states-of :warped_stem))
        trunk {[6 39 12] stem [6 40 12] stem [6 41 12] stem}
        col (world/column trunk)
        chunk [148 132]
        walked {:intent/kind :walk
                :intent/target [2374 39 2124]
                :intent/for :log
                :intent/trunk [2374 39 2124]
                :intent/status :done}
        w (-> (ps/standing {})
              (assoc :player/pos [2374.4 38.0 2125.3]
                     :player/dimension "minecraft:the_nether"
                     :world/chunks {chunk col}
                     :player/inventory
                     {0 (assoc (ps/item :iron_pickaxe 1) :durability 11)
                      1 (ps/item :iron_sword 1)
                      2 (ps/item :warped_planks 4)}
                     :time/now 0
                     :plan/last walked)
              (memory/remember-column chunk col)
              (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pupkeep/for-pick-decide w)))
        "four planks held but no log: the done walk's chain stays")
    (is (= [:dig :log] ((juxt :intent/kind :intent/for) (plan/next-intent w ps/row plan/goals)))
        "standing beside the warped stem after the walk: advances to the dig, never the walk again")
    (is (= [2374 39 2124] (:intent/target (plan/next-intent w ps/row plan/goals)))
        "digs the warped stem target")))

(deftest overworld-respawn-beside-trunk-advances-to-dig-not-loop
  ;; gym batch pearls-w3 (plan run 40464154227) run 3: died and respawned in the
  ;; overworld at pos [2.5 93 49.3], holding crafting_table, stick 3, wooden_sword 1;
  ;; re-issued intent :walk [3 94 50] :for :log 256,743 times because crafting_table
  ;; counted as pieces and dropped :plan/last
  (let [trunk {[3 94 2] 136 [3 95 2] 136 [3 96 2] 136}
        col (world/column trunk)
        chunk [0 3]
        walked {:intent/kind :walk
                :intent/target [3 94 50]
                :intent/for :log
                :intent/trunk [3 94 50]
                :intent/status :done}
        respawned
        (-> (ps/standing {})
            (assoc :player/pos [2.5 93.0 49.3]
                   :player/dimension "minecraft:overworld"
                   :world/chunks {chunk col}
                   :player/inventory
                   {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3) 2 (ps/item :crafting_table 1)}
                   :time/now 0
                   :stats/deaths 1
                   :plan/last walked)
            (memory/remember-column chunk col)
            (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pupkeep/for-pick-decide respawned)))
        "table and sticks held, no log: the done walk's chain stays")
    (is (= [:dig :log]
           ((juxt :intent/kind :intent/for) (plan/next-intent respawned ps/row plan/goals)))
        "standing beside the trunk after the walk: advances to the dig, never the walk again")
    (is (= [3 94 50] (:intent/target (plan/next-intent respawned ps/row plan/goals)))
        "digs the spruce log target")))
