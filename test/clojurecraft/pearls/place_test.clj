(ns clojurecraft.pearls.place-test
  "The burrow and the shelter geometry, and the intents that cut it and wait in it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest no-way-out-is-cut-from-the-brink-of-a-drop
  ;; gym batch pearls-warped8-c (plan run 39845223845, probe 39854206523) run 2: dig-out's stairs
  ;; up from a ledge over a lava lake, the stair's lay backing the bot off the edge into the lava
  (let [shaft (into ps/feet-cells (for [y (range 61 66)] [9 y 8]))
        w (ps/walled-in shaft [8.5 80.0 20.5])]
    (is (pplace/brink? w [8 64 8]) "open beside the feet down to y 61")
    (is (not (pplace/brink? (ps/walled-in (into ps/feet-cells [[9 64 8] [9 65 8] [9 63 8]])
                                          [8.5 80.0 20.5])
                            [8 64 8]))
        "a step down beside the feet is no brink")
    (is (nil? (pmove/dig-out w)) "no cut from it")))

(deftest the-burrow-end-is-out-of-an-endermans-reach
  ;; a mob's melee is its box grown 0.83 sideways, never up or down (Mob.getAttackBoundingBox,
  ;; probe 38893918055): one cell off, an enderman reaches; two off, or roofed out, it cannot
  (let [w (ps/standing {} ps/burrow)]
    (is (pplace/shelter? w [8 61 8])
        "the tunnel's end: the last stair, where one can stand, is two off")
    (is (not (pplace/shelter? w [7 61 8])) "the tunnel's first step is beside the last stair")
    (is (not (pplace/shelter? w [6 61 8])) "a stair is three high: an enderman stands in it")
    (is (not (pplace/shelter? w [3 64 8])) "open ground")
    (is (not (pplace/shelter? w [9 61 8])) "inside the rock")))

(deftest a-tunnel-step-out-over-a-drop-is-refused
  ;; gym batch pearls-warped9-a (plan run 39898400239) run 3: its tunnel toward an enderman
  ;; broke into the cave it stood in, and the bot came down thirteen blocks into the open
  ;; fight there, where it died
  (is (= 6 (pplace/floored-length (ps/standing {}) [8 64 8] [1 0] 6))
      "stone all the way: every step floored")
  (is (= 2 (pplace/floored-length (ps/standing {} {[11 63 8] ps/air}) [8 64 8] [1 0] 8))
      "the third step's floor is the pit: two")
  (is (= 0 (pplace/floored-length (ps/standing {} {[9 63 8] ps/air}) [8 64 8] [1 0] 8))
      "the first step out over it: none"))

(deftest ^:eftest/synchronized
         with-a-pickaxe-held-no-walk-goes-to-an-endermans-feet-without-a-stare-site
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: with stare-site nil, the bot walked straight
  ;; to an enderman's feet with a pickaxe held and died in the open
  (let [e (ps/enderman [8.5 64.0 15.5])
        w (-> (ps/standing {7 e} ps/wall)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (update :world/chunks
                      into
                      (for [cx [-1 0 1]
                            cz [-1 0 1]
                            :when (not= [0 0] [cx cz])]
                        [[cx cz] (world/column {})])))]
    (with-redefs [pplace/stare-site (constantly nil)]
      (is (nil? (pplace/stare-site w e)))
      (is (not= [8 64 15] (:intent/target (plan/next-intent w ps/row plan/goals)))
          "never walks to the feet when holding a pickaxe"))))

(deftest ^:eftest/synchronized
         a-distant-angry-enderman-is-met-standing-not-charged-with-a-pickaxe-held
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: charged across the open to fight an
  ;; angry enderman 15 blocks off with no burrow shelter and fell into lava on the way; a
  ;; lookout there instead stood the hunt still while any angry one was tracked, never
  ;; given up on. A held fight stands on ground already stood on and times out instead.
  (let [angry (assoc (ps/enderman [8.5 64.0 20.5]) :entity/creepy? true)
        w (-> (ps/standing {7 angry})
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
    (with-redefs [pplace/burrowable? (constantly false)
                  pplace/burrow-site (constantly nil)]
      (let [held (plan/next-intent w ps/row plan/goals)
            open (plan/next-intent (update w :player/inventory dissoc 1) ps/row plan/goals)]
        (is (= :fight (:intent/kind held)))
        (is (:intent/hold? held) "with a pickaxe held, met standing, never charged")
        (is (:intent/yield? held) "and broken off under danger-health, as in the burrow")
        (is (= :fight (:intent/kind open)))
        (is (not (:intent/hold? open)) "without one, fought in the open as the arena row does")))))

(deftest stuck-lookout-in-hole-climbs-out-to-surface
  ;; reviewer finding 1 (gym batch pearls-r3-w1 run 1, plan run 40469919220):
  ;; stuck at y 47 inside rock with a brink behind it from its descent,
  ;; logged :lookout 3,369 times and 15 tunnels that dug nothing.
  ;; When stuck? is true, climb-out cuts stairs up into solid rock rather than
  ;; refusing on brink?, and dig-out refuses tunnels with 0 solid cuts.
  (let [blocks (into {[8 46 8] ps/stone
                      [8 46 9] ps/stone
                      [8 47 9] ps/stone
                      [8 48 9] ps/stone
                      [8 49 9] ps/stone
                      [8 50 9] ps/stone
                      [7 46 8] ps/stone
                      [9 46 8] ps/stone}
                     (for [y (range 43 48)] [[8 y 7] 0]))
        w (-> (ps/standing {} blocks)
              (assoc :player/pos [8.5 47.0 8.5]
                     :plan/last {:intent/kind :lookout :intent/for :pearls}
                     :player/inventory {0 (ps/item :diamond_pickaxe 1) 1 (ps/item :iron_sword 1)})
              (memory/remember-intention {:intent/id 0 :intent/kind :walk} :done))
        w (reduce (fn [acc n]
                    (memory/remember-intention acc {:intent/id n :intent/kind :lookout} :done))
                  w
                  (range 1 13))]
    (is (pmove/stuck? w) "stood 12 lookouts: stuck is true")
    (is (pplace/brink? w [8 47 8]) "drop behind makes feet a brink")
    (let [next (plan/next-intent w ps/row plan/goals)]
      (is (= :stairs-up (:intent/kind next))
          "climbs out toward surface rather than repeating lookout")
      (is (= [1 0] (:intent/dir next)) "climbs into the solid rock wall"))))

(deftest a-burrow-is-cut-where-it-would-shelter-and-the-provoke-goes-from-its-mouth
  ;; batch pearls-warped-e (plan run 39164914828) run 1: on broken ground :burrow failed
  ;; :no-shelter six times in a row where the bot stood, it then provoked an enderman fifteen
  ;; blocks from its old burrow, the walk home found no path, and it was slain in the open
  (let [seen {7 (ps/enderman [8.5 64.0 15.5])}
        flat (assoc-in (ps/standing seen) [:player/inventory 1] (ps/item :iron_pickaxe 1))
        cave (assoc flat :world/chunks (:world/chunks (ps/standing seen {[8 62 7] ps/air})))]
    (is (pplace/burrowable? flat [8 64 8] [0 -1]) "rock all the way: the end is a shelter")
    (is (not (pplace/burrowable? cave [8 64 8] [0 -1]))
        "a hollow under the first stair's floor: the stair is a drop")
    (is (= {:intent/kind :walk :intent/target [7 64 8] :intent/for :shelter}
           (plan/next-intent cave ps/row plan/goals))
        "so the hunt walks to the nearest cell a burrow would shelter from, and stands in it")
    (is (= [7 64 8] (pplace/burrow-site cave [0 -1])))
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (ps/burrowed [3.5 64.0 2.5] {7 (ps/enderman [3.5 64.0 14.5])})
                             ps/row
                             plan/goals))
        "a burrow six blocks off and one to provoke: to the mouth first, never the stare from here")
    (is
     (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
        (plan/next-intent (ps/burrowed [3.5 64.0 2.5]
                                       {7 (ps/enderman [3.5 67.0 14.5])}
                                       (into {} (for [x (range 16) y [64 65]] [[x y 5] ps/stone])))
                          ps/row
                          plan/goals))
     "a fence two high between the bot and its burrow, one to provoke over it: to the mouth
         first, as run 1 should have (the walk fails, and the provoke waits)")
    (is (= :netherrack (stairs/filler {:player/inventory {0 (ps/item :netherrack 3)}} 2))
        "a Nether descent lays its floors from what it digs")))

(deftest a-burrow-site-is-one-a-walk-reaches-and-none-a-walk-failed-toward
  ;; batch pearls-warped2-b (plan run 39182782203) run 1: thirteen walks to a burrow site failed
  ;; :no-path in a row, the same site chosen again after each
  (let [seen {7 (ps/enderman [8.5 64.0 15.5])}
        cave (assoc-in (ps/standing seen {[8 62 7] ps/air})
              [:player/inventory 1]
              (ps/item :iron_pickaxe 1))
        ring (into {[8 62 7] ps/air}
                   (for [dx [-1 0 1]
                         dz [-1 0 1]
                         y [64 65]
                         :when (not= 0 dx dz)]
                     [[(+ 8 dx) y (+ 8 dz)] ps/stone]))
        boxed (assoc-in (ps/standing seen ring) [:player/inventory 1] (ps/item :iron_pickaxe 1))]
    (is (= [7 64 8] (pplace/burrow-site cave [0 -1])) "the nearest")
    (is (not= [7 64 8] (pplace/burrow-site (assoc cave :plan/blacklist #{[7 64 8]}) [0 -1]))
        "a site a walk failed toward is passed over")
    (is (some? (pplace/burrow-site (assoc cave :plan/blacklist #{[7 64 8]}) [0 -1]))
        "for the next one")
    (is (nil? (pplace/burrow-site boxed [0 -1]))
        "walled in two high: cells two off would shelter, but no walk reaches one")))

(deftest a-burrows-stairs-are-four-high-so-an-enderman-walks-down-them
  ;; batch pearls-warped2-b (plan run 39182782203, probe 39206015361): a burrow cut into a hill,
  ;; its stairs three high, held every provoked enderman on the first stair (a body steps off at
  ;; its old height, 2.9 tall), and six held fights in six timed out :unreachable. The model's
  ;; enderman (mob/step-toward, mob/tall) walks down the four-high stairs to the last one, two
  ;; cells from the bot at the tunnel's end, and no further
  (let [mouth [3 64 8]
        rock? (fn [[x y _]] (<= y (if (>= x 4) 69 63))) ; a hill rising from x 4
        walk (fn [opened]
               (let [free? (fn [c] (or (contains? opened c) (not (rock? c))))]
                 (mob/cell-of (nth (iterate #(mob/step-toward free? % [8.5 61.0 8.5] 0.3 0.0 3)
                                            [3.5 64.0 8.5])
                                   200))))
        steps (pplace/burrow-steps mouth [1 0])
        three-high (set (concat (stairs/stair-cells mouth [1 0])
                                (stairs/stair-cells [4 63 8] [1 0])
                                (stairs/stair-cells [5 62 8] [1 0])
                                (stairs/level-cells [6 61 8] [1 0])
                                (stairs/level-cells [7 61 8] [1 0])))]
    (is (= [[4 66 8] [4 65 8] [4 64 8] [4 63 8]] (first steps)) "a stair opens four cells")
    (is (= [8 61 8] (last (last steps))) "the tunnel's end")
    (is (= 3 (mob/tall :enderman)))
    (is (= [6 61 8] (walk (set (apply concat steps)))) "down to the last stair")
    (is (= [3 64 8] (walk three-high)) "three high: it stands at the mouth")))

(deftest a-burrows-mouth-is-opened-to-an-endermans-height-first
  ;; batch pearls-warped7-b (plan run 39725964182, probe 39732372050) run 3: the burrow was cut
  ;; under a huge fungus's cap, a warped wart block two over the mouth's feet, its stairs four
  ;; high below it; the bot stands in two cells and an enderman needs three, so the enderman the
  ;; lure brought stood in the hollows beside the mouth, and six held fights in six timed out
  ;; :unreachable. The model's enderman (mob/step-toward, three tall) stops outside the mouth
  ;; under the cap and walks down to the last stair once the cap is dug
  (let [mouth [3 64 8]
        roof [3 66 8]
        cap (blocks/default-state :warped_wart_block)
        rock? (fn [[x y z]] (or (<= y 63) (= [x y z] roof))) ; flat ground, the cap over the mouth
        walk (fn [opened]
               (let [free? (fn [c] (or (contains? opened c) (not (rock? c))))]
                 (mob/cell-of (nth (iterate #(mob/step-toward free? % [8.5 61.0 8.5] 0.3 0.0 3)
                                            [0.5 64.0 8.5])
                                   200))))
        steps (set (apply concat (pplace/burrow-steps mouth [1 0])))
        seen {7 (ps/enderman [0.5 64.0 8.5])} ; calm, at hand, west of the mouth
        capped (-> (ps/standing seen {roof cap})
                   (assoc :player/pos [3.5 64.0 8.5])
                   (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
        open (assoc capped :world/chunks (:world/chunks (ps/standing seen)))
        burrow {:intent/kind :burrow :intent/dir [1 0] :intent/for :pearls :intent/status :active}
        first-child
        (fn [w]
          (let [i (:plan/intent (ps/intent-step (assoc w :plan/intent burrow) (ps/tick 1)))]
            [(:intent/stage i) (select-keys (:intent/child i) [:intent/kind :intent/target])]))]
    (is (= [roof] (pplace/mouth-cells mouth)) "one cell, the third over the feet, for 2.9")
    (is (not (pplace/stand? capped mouth)) "under the cap no enderman stands in the mouth")
    (is (= [2 64 8] (walk steps))
        "the stairs four high and the cap over the mouth: it stays outside")
    (is (= [6 61 8] (walk (conj steps roof))) "the cap dug: down to the last stair")
    (is (= {:intent/kind :burrow :intent/dir [1 0] :intent/for :pearls}
           (plan/next-intent capped ps/row plan/goals))
        "a burrow is cut where the bot stands under the cap: the cap is dug first")
    (is (not
         (pplace/burrowable? (ps/standing seen {roof (blocks/default-state :lava)}) mouth [1 0]))
        "lava over the mouth: no burrow here")
    (is (= [:roof {:intent/kind :dig :intent/target roof}] (first-child capped))
        "the burrow's first child digs the cap")
    (is (= [:down {:intent/kind :stairs-down}] (first-child open))
        "nothing over the mouth: the stairs at once")))

(deftest an-enderman-is-provoked-and-killed-from-a-burrow-against-the-model
  ;; the model's enderman (mob.clj, issue #260) is neutral until stared at, then walks at the bot
  ;; and hits for 7 a second (5.74 through the iron): in the open it beat the bot (gym batch
  ;; pearls-263a: five deaths in five). The bot digs a burrow east, stairs then a tunnel two high,
  ;; provokes it from the mouth, goes back to the end, and kills it where it stops, at the last
  ;; stair (three cells tall, mob/tall), out of its reach; then picks the pearl up: the twelfth
  (let [[w sim] (sim/run ps/step
                         (game/init fx/opts)
                         (sim/init (assoc ps/arena :seed (ps/pearl-seed)))
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ender-pearls]})
        story (mapv (juxt :intention/kind :intention/event :intention/reason)
                    (memory/intentions w))]
    (is (plan/done? w) (pr-str (plan/summary w) story))
    (is (not-any? (fn [[_ m]] (and (= :enderman (:type m)) (not (:dead-at m)))) (:sim/mobs sim))
        "the enderman was killed")
    (is (>= (get (recipe/counts (:player/inventory w)) :ender_pearl 0) 12))
    (is (some #(= [:burrow :done nil] %) story) "it dug a burrow")
    (is (some #(= [:provoke :done nil] %) story) "it came to a stare")
    (is (pplace/shelter? w (:pearls/shelter w)) (pr-str (:pearls/shelter w)))
    (is (pos? (:sim/health sim)) "and the bot lived")
    (is (= [] (:sim/violations sim)))))

(deftest nothing-is-cut-down-into-the-nethers-lava-sea
  ;; gym plan run 41134469333 (pearls-travel-b) job 6 (issue #263): stairs-down from y 47 toward an
  ;; enderman far below, to y 34, a burrow cut at [2498 34 2160], lava let in, two flees, dead in lava
  (let [low (-> (ps/standing {7 (ps/enderman [8.5 40.0 14.5])} {})
                (assoc :player/dimension ps/nether
                       :player/pos [8.5 38.0 8.5]
                       :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))]
    (is (= 36 (pplace/lava-floor low)))
    (is (zero? (pplace/lava-floor (assoc low :player/dimension "minecraft:overworld"))))
    (is (nil? (pmove/descend low (psense/endermen low pparams/descend-radius)))
        "an enderman 40 and below the cut's floor: none")
    (is (not (pplace/burrowable? (assoc low :player/pos [8.5 38.0 8.5]) [8 38 8] [1 0]))
        "a burrow whose last stair is at y 35 is under the floor")))

(deftest a-stare-site-asks-few-cheap-routes
  ;; 4f317da lane 2, Clj_r01: three endermen by eight vantages, each route test at the full budget,
  ;; froze real-18 bots 19 to 53 s a tick and the server timed them out (profile probe
  ;; 42327950512: 921 of 1219 samples in stare-site); each test now spends at most vantage-nodes
  (let [e (ps/enderman [8.5 64.0 15.5])
        w (-> (ps/standing {7 e} ps/wall)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (update :world/chunks
                      into
                      (for [cx [-1 0 1]
                            cz [-1 0 1]
                            :when (not= [0 0] [cx cz])]
                        [[cx cz] (world/column {})])))
        asked (atom [])]
    ;; only this thread's route tests are counted (routable-here)
    (with-redefs [intent/routable? (ps/routable-here
                                    (fn [_ i] (swap! asked conj (:intent/max-nodes i)) false))]
      (is (nil? (pplace/stare-site w e)) "no route: no site"))
    (is (<= 1 (count @asked) pparams/vantage-tries) "at most vantage-tries route tests")
    (is (every? #(= pparams/vantage-nodes %) @asked) "each within vantage-nodes")))
