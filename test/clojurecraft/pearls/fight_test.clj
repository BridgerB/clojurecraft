(ns clojurecraft.pearls.fight-test
  "Which fights the hunt takes: a walk past heavy hostiles is a tunnel, and an open fight that is
   not winnable is a cover (issue #263)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.rules :as rules]
            [clojurecraft.physics :as physics]))

(use-fixtures :once fx/instrumented)

(deftest a-walk-through-hoglins-is-a-tunnel
  ;; Logged state, sim R1/17 seed 1 (probe rh6-R1-17, issue #263): :walk [987 58 -288] :for :pearls
  ;; at hoglins 24 to 32 blocks off, their follow range 16: the bot's own distance said clear, the route
  ;; ended in them. A route that passes within a heavy hostile's follow range, with a pickaxe held, is
  ;; a sealed tunnel; a clear route, or no pickaxe, the walk.
  (let [walk {:intent/kind :walk :intent/target [60 64 8] :intent/for :pearls}
        w (-> (ps/wide (ps/standing {7 (ps/mob :hoglin [56.5 64.0 14.5])} {}))
              (assoc :player/dimension ps/nether)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
        far (assoc w :world/entities {7 (ps/mob :hoglin [8.5 64.0 60.5])})
        calm (assoc w :world/entities {})
        bare (update w :player/inventory dissoc 1)]
    (is (> (physics/distance (:player/pos w) [56.5 64.0 14.5]) 40.0)
        "the hoglin is far from the feet")
    (is (pmove/heavy-on-route? w walk) "but the route to [60 64 8] ends in its follow range")
    (is (= :tunnel-to (:intent/kind (pmove/tunnel-past-heavy w walk))))
    (is (= [60 64 8] (:intent/target (pmove/tunnel-past-heavy w walk))))
    (is (true? (:intent/seal-behind? (pmove/tunnel-past-heavy w walk))))
    (is (= walk (pmove/tunnel-past-heavy far walk)) "a hoglin 50 blocks off the route: the walk")
    (is (= walk (pmove/tunnel-past-heavy calm walk)) "none tracked: the walk")
    (is (= walk (pmove/tunnel-past-heavy bare walk)) "no pickaxe: the walk")
    (is (< (pmove/segment-distance [0 0 0] [10 0 0] [5 3 0]) 3.01))
    (is (> (pmove/segment-distance [0 0 0] [10 0 0] [20 0 0]) 9.99))))

(deftest an-unwinnable-open-fight-with-no-pickaxe-is-a-cover-not-a-fight
  ;; Sim R4/1 seeds 1 and 2 (probe wide-bo4-R4-1, issue #263): fights in the open at 12.9 and 15.9 health
  ;; against an angry enderman, both lost. With no pickaxe there is no burrow to dig, but a lost fight is
  ;; still lost: blocks held are laid or a pillar raised; only with nothing at all to lay, dig or eat is
  ;; the fight all there is.
  (let [angry (assoc (ps/mob :enderman [11.0 64.0 8.5]) :entity/creepy? true :entity/health 40.0)
        kit (fn [w]
              (-> w
                  (assoc-in [:player/inventory 0] (ps/item :iron_sword 1))
                  (assoc-in [:player/inventory (armour/worn-slot 0)] (ps/item :iron_helmet 1))
                  (assoc-in [:player/inventory (armour/worn-slot 1)] (ps/item :iron_chestplate 1))
                  (assoc-in [:player/inventory (armour/worn-slot 2)] (ps/item :iron_leggings 1))
                  (assoc-in [:player/inventory (armour/worn-slot 3)] (ps/item :golden_boots 1))))
        w (-> (ps/standing {7 angry} {})
              (assoc :player/dimension ps/nether :player/health 12.9)
              kit)
        with-blocks (assoc-in w [:player/inventory 9] (ps/item :cobblestone 64))]
    (is (not (combat/winnable? w [7 angry])) "lost at 12.9")
    (is (not= :fight (:intent/kind (rules/decide with-blocks)))
        (str "blocks held: a cover, not the fight; the row is " (rules/row-of with-blocks)))
    (is (= :fight (:intent/kind (rules/decide w)))
        "nothing to lay, dig or eat: the fight is all there is")))
