(ns clojurecraft.sim-shield-test
  "The server model's shield: a use-item with the offhand raises a shield there and a release lowers
   it; a raised shield facing a blow (a strike, a shot, a fireball, a blast) takes it, counted by
   kind (:sim/blocked-by), and every blow that lands is counted too (:sim/struck-by)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.sim.mobs :as sim-mobs]
            [clojurecraft.sim.protocol :as sim-protocol]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(def night 18000)

(defn arena
  "A model in play on a stone floor, the player at [0.5 64 0.5] holding inv and looking along
   yaw, with mobs given as a fixture's."
  [inv yaw mobs]
  (-> (sim/init
       {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5] :inventory inv :day-time night})
      (assoc :sim/phase :play
             :sim/player-pos [0.5 64.0 0.5]
             :sim/player-look [yaw 0.0]
             :sim/on-ground? true)
      (sim-mobs/with-mobs mobs)))

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

(def shield-held {[:offhand] (item :shield 1)})

(defn raised
  "The model with the shield raised ms ago."
  [sim ms]
  (assoc sim :sim/blocking-at (- (:sim/now sim) ms)))

(def east -90.0)                      ; the yaw that looks along +x
(def west 90.0)

(deftest a-raised-shield-facing-the-blow-takes-it
  (let [skel [{:type :wither_skeleton :pos [1.0 64.0 0.5]}]
        facing (ticks (raised (arena shield-held east skel) 1000) 1)
        behind (ticks (raised (arena shield-held west skel) 1000) 1)
        fresh (ticks (raised (arena shield-held east skel) 0) 1)
        none (ticks (raised (arena {} east skel) 1000) 1)]
    (is (= 20.0 (:sim/health facing)) "the eight blocked")
    (is (= {[:wither_skeleton :melee] 1} (:sim/blocked-by facing)) "counted by kind and how")
    (is (= 1 (:sim/blocked facing)))
    (is (nil? (:sim/wither facing)) "no wither from a blow that never landed")
    (is (= 12.0 (:sim/health behind)) "a blow from behind lands")
    (is (= 1 (:sim/struck behind)))
    (is (= {[:wither_skeleton :melee] 1} (:sim/struck-by behind)) "counted by cause and how")
    (is (= 12.0 (:sim/health fresh)) "up less than a quarter second: not yet a block")
    (is (= 12.0 (:sim/health none)) "no shield in the offhand")))

(deftest a-raised-shield-takes-a-hoglins-swing-from-in-front
  ;; knockback (a hoglin's throw) is not modelled: what the shield takes is the hit
  (let [hog [{:type :hoglin :pos [1.5 64.0 0.5]}]
        facing (ticks (raised (arena shield-held east hog) 1000) 1)
        behind (ticks (raised (arena shield-held west hog) 1000) 1)]
    (is (= 20.0 (:sim/health facing)))
    (is (= 1 (:sim/blocked facing)))
    (is (= 14.0 (:sim/health behind)) "six from behind")))

(deftest a-raised-shield-takes-a-fireball-from-in-front
  (let [dir (mob/aim [4.5 65.4 0.5] [0.5 64.9 0.5] 0.5 0.5 0.5 0.5)
        fly (fn [sim]
              (-> sim
                  (assoc-in [:sim/fireballs 90]
                            {:pos [4.5 65.4 0.5] :dir dir :owner 91 :born 0 :kind :blaze})
                  (ticks 12)))
        blocked (fly (raised (arena shield-held east []) 1000))
        hit (fly (arena shield-held east []))]
    (is (= 20.0 (:sim/health blocked)))
    (is (nil? (:sim/fire blocked)) "not alight")
    (is (empty? (:sim/fireballs blocked)) "spent on the shield")
    (is (= {[:blaze :fireball] 1} (:sim/blocked-by blocked)))
    (is (= 15.0 (:sim/health hit)) "down, it lands")))

(deftest the-offhand-use-raises-the-shield-and-the-release-lowers-it
  (let [sim (arena shield-held east [])
        up (sends sim
                  {:packet/name :use-item
                   :hand (p/value :hand :off-hand)
                   :sequence 1
                   :y-rot east
                   :x-rot 0.0})
        down (sends up
                    {:packet/name :player-action
                     :status (p/value :dig :release-use-item)
                     :pos [0 0 0]
                     :face 0
                     :sequence 0})]
    (is (= (:sim/now sim) (:sim/blocking-at up)))
    (is (some #(= {entity/living-flags-index sim-protocol/using-flags} (:metadata %))
              (filter #(= :set-entity-data (:packet/name %)) (:sim/out up)))
        "the client told it uses its offhand")
    (is (nil? (:sim/blocking-at down)))
    (is (nil? (:sim/blocking-at (sends sim
                                       {:packet/name :use-item
                                        :hand (p/value :hand :main-hand)
                                        :sequence 1
                                        :y-rot east
                                        :x-rot 0.0})))
        "the main hand's use raises nothing")
    (is (nil? (:sim/blocking-at (sends (arena {} east [])
                                       {:packet/name :use-item
                                        :hand (p/value :hand :off-hand)
                                        :sequence 1
                                        :y-rot east
                                        :x-rot 0.0})))
        "no shield in the offhand")))
