(ns clojurecraft.hear-test
  "What the bot hears of a fight (the observability plan, part A): hits named by damage type and
   attacker, hurt flashes, swings, the fight bracket, effects and attributes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.hear]
            [clojurecraft.registry :as registry]))

(use-fixtures :once fx/instrumented)

(defn id-of
  "The id name has in a registry {id name} table."
  [table n]
  (some (fn [[i x]] (when (= n x) i)) table))

(def hoglin 9)

(defn playing
  "A bot in play, its own entity 5, a hoglin tracked as entity 9, at 20 health."
  []
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/entity-id 5
         :player/health 20.0
         :time/now 1000
         :world/entities
         {hoglin {:entity/type (blocks/entity-types :hoglin)
                  :entity/pos [3.0 64.0 3.0]
                  :entity/seen-at 0}}))

(defn heard
  "w after the packet p."
  [w p]
  (game/on-packet w (assoc p :packet/name (:packet/name p))))

(deftest a-hit-is-kept-by-name-and-attacker
  (let [w (heard (playing)
                 {:packet/name :damage-event
                  :entity-id 5
                  :source-type (id-of registry/damage-types :mob_attack)
                  :source-cause (inc hoglin)
                  :source-direct (inc hoglin)})]
    (is (= [{:at 1000 :type :mob_attack :hp 20.0 :by hoglin :by-kind :hoglin}] (:player/hits w)))
    (is (= hoglin (:player/attacker w)))
    (testing "lava has no attacker"
      (is (= {:at 1000 :type :lava :hp 20.0}
             (peek (:player/hits (heard (playing)
                                        {:packet/name :damage-event
                                         :entity-id 5
                                         :source-type (id-of registry/damage-types :lava)
                                         :source-cause 0
                                         :source-direct 0}))))))
    (testing "only the last hits-kept are kept"
      (let [many (reduce (fn [w _]
                           (heard w
                                  {:packet/name :damage-event
                                   :entity-id 5
                                   :source-type 0
                                   :source-cause 0
                                   :source-direct 0}))
                         (playing)
                         (range (+ 5 game/hits-kept)))]
        (is (= game/hits-kept (count (:player/hits many))))))))

(deftest hurt-flashes-and-swings-are-kept-on-the-entity
  (let [w (-> (playing)
              (heard {:packet/name :hurt-animation :entity-id hoglin :yaw 0.0})
              (heard {:packet/name :animate :entity-id hoglin :action 0}))]
    (is (= 1000 (get-in w [:world/entities hoglin :entity/hurt-at])))
    (is (= 1000 (get-in w [:world/entities hoglin :entity/swung-at])))
    (is (nil? (get-in (heard (playing) {:packet/name :animate :entity-id hoglin :action 3})
                      [:world/entities hoglin :entity/swung-at]))
        "not a main-hand swing")))

(deftest the-fight-bracket-effects-and-armour-are-the-servers-word
  (let [wither (id-of registry/mob-effects :wither)
        armor (id-of registry/attributes :armor)
        w (-> (playing)
              (heard {:packet/name :player-combat-enter})
              (heard {:packet/name :update-mob-effect
                      :entity-id 5
                      :effect wither
                      :amplifier 1
                      :duration 200
                      :flags 0})
              (heard {:packet/name :update-attributes
                      :entity-id 5
                      :attributes
                      [{:id armor
                        :base 0.0
                        :modifiers [{:mod-id "minecraft:armor.head" :amount 2.0 :operation 0}
                                    {:mod-id "minecraft:armor.chest" :amount 6.0 :operation 0}
                                    {:mod-id "minecraft:armor.legs" :amount 5.0 :operation 0}
                                    {:mod-id "minecraft:armor.feet" :amount 1.0 :operation 0}]}]}))]
    (is (:player/in-combat? w))
    (is (= {:wither {:amplifier 1 :until (+ 1000 (* 200 game/server-tick-ms))}}
           (:player/effects w)))
    (is (= 14.0 (:player/armor w)) "iron helmet, chest, legs and gold boots")
    (is (= {}
           (:player/effects (heard w
                                   {:packet/name :remove-mob-effect :entity-id 5 :effect wither}))))
    (is (false? (:player/in-combat? (heard w {:packet/name :player-combat-end :duration 40}))))))
