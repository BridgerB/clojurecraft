(ns clojurecraft.pocket-test
  "The pocket to fight blazes from (issue #262): a cell with one opening, a roof and solid blocks
   round it; the site of one that exists or is dug into a wall; the ops that make it; and the
   :pocket intent. Worlds are one chunk column, stone below y 64, air above, feet at [8.5 64 8.5]."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def stone (blocks/default-state :stone))

(defn solid "A block map of stone at each cell." [& cells] (into {} (map (fn [c] [c stone])) cells))

(defn world-of
  "A play world over one chunk column of blocks, the feet at pos."
  ([blocks] (world-of blocks [8.5 64.0 8.5]))
  ([blocks pos]
   (assoc (game/init fx/opts)
          :bot/phase :play
          :player/pos pos
          :player/loaded? true
          :player/on-ground? true
          :player/health 20.0
          :world/chunks {[0 0] (world/column blocks)})))

(def walled
  "A cell [8 64 8] shut on the east, south and north at the feet and the head, roofed, open west."
  (solid [9 64 8] [9 65 8] [8 64 9] [8 65 9] [8 64 7] [8 65 7] [8 66 8]))

(def wall
  "A wall to dig a pocket into from [5 64 8] toward the east: the cells [6 64 8] and [6 65 8] to
   dig, solid blocks round them and over them."
  (solid [6 64 8] [6 65 8] [7 64 8] [7 65 8] [6 64 7] [6 65 7] [6 64 9] [6 65 9] [6 66 8]))

(deftest a-pocket-has-one-opening-and-a-roof
  (let [w (world-of walled)
        at (path/cells w)]
    (is (pocket/pocket? w at [8 64 8]))
    (is (= [[-1 0]] (pocket/openings w [8 64 8])))
    (testing "two openings is a tunnel, not a pocket"
      (let [w (world-of (dissoc walled [9 64 8] [9 65 8]))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "no roof is none"
      (let [w (world-of (dissoc walled [8 66 8]))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "a roof that falls is none"
      (let [w (world-of (assoc walled [8 66 8] (blocks/default-state :gravel)))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "fire in the cell is none: nothing stands in it"
      (let [w (world-of (assoc walled [8 64 8] (blocks/default-state :fire)))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))))

(deftest a-pocket-is-dug-into-a-wall-whose-other-faces-are-solid
  (let [w (world-of wall [5.5 64.0 8.5])]
    (is (= {:pocket/cell [6 64 8]
            :pocket/stand [5 64 8]
            :pocket/dig [[6 64 8] [6 65 8]]
            :pocket/placed []}
           (pocket/dig-in w [5 64 8] [1 0])))
    (is (nil? (pocket/dig-in w [5 64 8] [-1 0])) "toward open air: nothing to dig")
    (testing "a side that is open lets lava or a fall in: none"
      (let [w (world-of (dissoc wall [7 64 8]) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "a roof of gravel falls on the bot: none"
      (let [w (world-of (assoc wall [6 66 8] (blocks/default-state :gravel)) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "bedrock is never dug"
      (let [w (world-of (assoc wall [6 64 8] (blocks/default-state :bedrock)) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "a pocket half dug is still one to dig"
      (let [w (world-of (dissoc wall [6 64 8]) [5.5 64.0 8.5])]
        (is (= [[6 65 8]] (:pocket/dig (pocket/dig-in w [5 64 8] [1 0]))))))))

(deftest the-site-is-the-nearest-pocket-or-a-wall-to-dig-into
  (let [any (constantly true)
        w (world-of walled)]
    (is (= {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}
           (pocket/site-near w [8 64 8] any))
        "a pocket the feet stand in")
    (is (= [8 64 8] (:pocket/cell (pocket/site-near (world-of walled [5.5 64.0 8.5]) [5 64 8] any)))
        "a pocket three cells off, walked to")
    (is (= [6 64 8] (:pocket/cell (pocket/site-near (world-of wall [5.5 64.0 8.5]) [5 64 8] any)))
        "a wall to dig into when there is no pocket")
    (is (nil? (pocket/site-near (world-of wall [5.5 64.0 8.5]) [5 64 8] (constantly false)))
        "the caller's predicate drops a cell")
    (is (nil? (pocket/site-near (world-of {}) [8 64 8] any)) "open ground: none")))

(deftest the-ops-dig-then-stand
  (let [dig (pocket/ops
             {:pocket/cell [6 64 8] :pocket/stand [5 64 8] :pocket/dig [[6 64 8] [6 65 8]]})
        have (pocket/ops {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig []})]
    (is (= [:dig :dig :stand-in] (map :build/op dig)))
    (is (= [[6 64 8] [6 65 8] [6 64 8]] (map :build/pos dig)))
    (is (= [:stand-in] (map :build/op have)))
    (is (= [:feet [8 64 8]] (:build/done (last have))))))

(deftest the-pocket-intent-is-done-in-the-pocket
  (let [site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig []}
        w (assoc (world-of walled) :plan/intent {:intent/kind :pocket :intent/site site})
        i (:plan/intent (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))]
    (is (intent/done? i))
    (is (pocket/in? (assoc w :pocket/site site)))
    (is (not (pocket/in? (assoc (world-of walled [5.5 64.0 8.5]) :pocket/site site))))
    (testing
      "a site that stopped being one is done, not failed: the cell stood in is held to be sealed"
      (let [w (assoc (world-of (dissoc walled [8 66 8]))
                     :plan/intent
                     {:intent/kind :pocket :intent/site site})
            i (:plan/intent (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))]
        (is (intent/done? i))
        (is (nil? (:intent/reason i)))))))

(deftest a-pocket-is-sealed-with-blocks-in-its-opening-and-opened-again
  (let [w (world-of walled)
        site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}]
    (is (= [[7 64 8] [7 65 8]] (pocket/seal-cells w [8 64 8]))
        "the opening at the feet, then the head")
    (is (not (pocket/sealed? w site)))
    (testing
      "the cells placed, all solid: sealed; the pocket is still a pocket (at most one side open)"
      (let [placed [[7 64 8] [7 65 8]]
            sealed (world-of (merge walled (solid [7 64 8] [7 65 8])))
            site (assoc site :pocket/placed placed)]
        (is (pocket/sealed? sealed site))
        (is (empty? (pocket/openings sealed [8 64 8])))
        (is (pocket/valid? sealed (path/cells sealed) site))
        (is (pocket/in? (assoc sealed :pocket/site site)))
        (is (not (pocket/sealed? w site)) "the placed cells open: not sealed")))
    (testing "the ops place the feet cell first, dig the head cell first"
      (is (= [[7 64 8] [7 65 8]] (map :build/pos (pocket/seal-ops [8 64 8] [[7 64 8] [7 65 8]]))))
      (is (= [[7 65 8] [7 64 8]]
             (map :build/pos (pocket/unseal-ops [8 64 8] [[7 64 8] [7 65 8]])))))
    (testing "filler and a pickaxe to open it again: sealable only with both held"
      (is (not (pocket/can-seal? w site)))
      (let [w (assoc-in w [:player/inventory 9] {:item (recipe/item-id :cobblestone) :count 62})
            picked
            (assoc-in w [:player/inventory 10] {:item (recipe/item-id :iron_pickaxe) :count 1})]
        (is (not (pocket/can-seal? w site)) "no pickaxe: sealed in, ten seconds a block by hand")
        (is (pocket/can-seal? picked site))
        (testing "none within a minute of a seal that failed, anywhere"
          (is (not (pocket/can-seal? (assoc picked :pocket/seal-failed-at 1000 :time/now 30000)
                                     site)))
          (is (pocket/can-seal? (assoc picked :pocket/seal-failed-at 1000 :time/now 70000)
                                site)))))))
