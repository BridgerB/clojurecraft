(ns clojurecraft.eye-test
  "What the bot sees of a creature (the observability plan, issue #263): where its head looks, what
   it holds and wears, a fireball's power and the statuses the server shows, kept on the entity; the
   pure question of whether a head faces the bot; and the ledger events for a gear change and a
   turn to face the bot."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.entity :as entity]
            [clojurecraft.eye]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.senses :as senses]))

(use-fixtures :once fx/instrumented)

(def skeleton 9)
(def enderman 10)

(defn playing
  "A bot in play at [8.5 64 8.5], a wither skeleton as entity 9 and an enderman as entity 10, both
   6 blocks off along x."
  []
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/entity-id 5
         :player/pos [8.5 64.0 8.5]
         :time/now 1000
         :world/entities
         {skeleton {:entity/type (blocks/entity-types :wither_skeleton)
                    :entity/pos [14.5 64.0 8.5]
                    :entity/seen-at 0}
          enderman {:entity/type (blocks/entity-types :enderman)
                    :entity/pos [8.5 64.0 14.5]
                    :entity/seen-at 0}}))

(defn heard "w after packet p." [w p] (game/on-packet w p))

(deftest a-head-turn-is-kept-in-degrees
  (let [w (heard (playing) {:packet/name :rotate-head :entity-id skeleton :head-yaw 64})]
    (is (= 90.0 (get-in w [:world/entities skeleton :entity/head-yaw])))
    (is (= (playing) (heard (playing) {:packet/name :rotate-head :entity-id 99 :head-yaw 1}))
        "an entity the bot does not track is left alone")))

(deftest an-add-entity-keeps-the-head-yaw
  (let [w (heard (playing)
                 {:packet/name :add-entity
                  :entity-id 30
                  :uuid nil
                  :type 1
                  :x 0.0
                  :y 0.0
                  :z 0.0
                  :vel [0.0 0.0 0.0]
                  :pitch 0
                  :yaw 0
                  :head-yaw -128
                  :data 0})]
    (is (= -180.0 (get-in w [:world/entities 30 :entity/head-yaw])))))

(deftest equipment-is-named-and-only-what-changed-is-replaced
  (let [sword (recipe/item-id :stone_sword)
        helmet (recipe/item-id :golden_helmet)
        w1 (heard (playing)
                  {:packet/name :set-equipment
                   :entity-id skeleton
                   :equipment [[0 {:item sword :count 1}] [5 {:item helmet :count 1}]]})
        w2 (heard w1 {:packet/name :set-equipment :entity-id skeleton :equipment [[0 nil]]})]
    (is (= {:mainhand :stone_sword :head :golden_helmet}
           (get-in w1 [:world/entities skeleton :entity/equipment])))
    (is (= {:head :golden_helmet} (get-in w2 [:world/entities skeleton :entity/equipment]))
        "the emptied hand goes, the helmet stays")))

(deftest a-fireballs-power-is-kept
  (is (= 0.1
         (get-in (heard (playing) {:packet/name :projectile-power :entity-id skeleton :power 0.1})
                 [:world/entities skeleton :entity/power]))))

(deftest statuses-other-than-death-are-kept-the-newest-few
  (let [w (reduce (fn [w s]
                    (heard (assoc w :time/now (+ 1000 s))
                           {:packet/name :entity-event :entity-id enderman :status s}))
                  (playing)
                  [46 47 48 49 50])
        w3 (heard (playing) {:packet/name :entity-event :entity-id enderman :status 3})]
    (is (= [[47 1047] [48 1048] [49 1049] [50 1050]]
           (get-in w [:world/entities enderman :entity/statuses])))
    (is (nil? (get-in w3 [:world/entities enderman :entity/statuses]))
        "a death is :entity/died-at, not a status")
    (is (some? (get-in w3 [:world/entities enderman :entity/died-at])))))

(deftest a-head-faces-the-bot-within-an-angle
  (let [at [14.5 64.0 8.5]
        e {:entity/pos at :entity/head-yaw 90.0}]
    (is (entity/facing-bot? e [8.5 64.0 8.5] 12.0) "yaw 90 looks along -x, at the bot")
    (is (entity/facing-bot? (assoc e :entity/head-yaw 100.0) [8.5 64.0 8.5] 12.0) "ten degrees off")
    (is (not (entity/facing-bot? (assoc e :entity/head-yaw 0.0) [8.5 64.0 8.5] 12.0))
        "looking along +z")
    (is (not (entity/facing-bot? (dissoc e :entity/head-yaw) [8.5 64.0 8.5] 12.0)) "no yaw known")
    (is (entity/facing-bot? (assoc e :entity/head-yaw -269.0) [8.5 64.0 8.5] 12.0) "angles wrap")
    (is (not (entity/facing-bot? e at 12.0)) "on one spot there is no line")
    (is
     (entity/facing-bot? {:entity/pos [8.5 64.0 14.5] :entity/head-yaw 180.0} [8.5 64.0 8.5] 12.0)
     "yaw 180 looks along -z")))

(deftest only-an-enderman-stares
  (let [looking #(assoc (get-in (playing) [:world/entities %]) :entity/head-yaw 180.0)]
    (is (entity/stares? (looking enderman) [8.5 64.0 8.5]))
    (is (not (entity/stares? (assoc (looking skeleton) :entity/pos [8.5 64.0 14.5])
                             [8.5 64.0 8.5])))))

(deftest the-ledger-notes-a-gear-change-and-a-turn-to-face-the-bot
  (let [before (playing)
        geared (heard before
                      {:packet/name :set-equipment
                       :entity-id skeleton
                       :equipment [[0 {:item (recipe/item-id :stone_sword) :count 1}]]})
        turned (heard geared {:packet/name :rotate-head :entity-id skeleton :head-yaw 64})
        by (fn [a b] (group-by :ev (senses/events a b)))]
    (is (= [{:ev :equip
             :eid skeleton
             :kind :wither_skeleton
             :d 6.0
             :equipment {:mainhand :stone_sword}
             :t 1000}]
           (:equip (by before geared))))
    (is (empty? (:facing (by before geared))))
    (is (= [{:ev :facing :eid skeleton :kind :wither_skeleton :d 6.0 :t 1000}]
           (:facing (by geared turned))))
    (is (empty? (:facing (by turned turned))) "only on change")
    (is (empty? (:equip (by geared turned))) "only on change")))

(deftest the-ledger-keeps-to-twenty-four-blocks
  (let [far (assoc-in (playing) [:world/entities skeleton :entity/pos] [38.5 64.0 8.5])
        geared (heard far
                      {:packet/name :set-equipment
                       :entity-id skeleton
                       :equipment [[0 {:item (recipe/item-id :stone_sword) :count 1}]]})]
    (is (empty? (filter #{:equip} (map :ev (senses/events far geared)))))))
