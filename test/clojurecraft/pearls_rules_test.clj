(ns clojurecraft.pearls-rules-test
  "The pearl hunt's rule table: an intent carries the id of the row that chose it, and the rows
   that other code names (still-wanted? methods) exist."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.intent :as intent]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.rules :as rules]))

(def worlds
  "A few states of the hunt: on the floor, near an enderman angry and calm, at a burrow's end, and
   hurt with a zombie at hand and blocks to lay (cover-from labels its own intent)."
  [(ps/standing {}) (ps/standing {7 (ps/enderman [11.5 64.0 8.5])})
   (ps/standing {7 (assoc (ps/enderman [11.5 64.0 8.5]) :entity/creepy? true)})
   (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [20.5 64.0 8.5])})
   (-> (ps/standing {8 (ps/mob :zombie [9.5 64.0 8.5])})
       (assoc :player/health 9.0)
       (assoc-in [:player/inventory 3] (ps/item :cobblestone 64)))])

(deftest an-intent-carries-the-id-of-the-row-that-chose-it
  (doseq [w worlds
          :let [i (rules/decide w)]
          :when (map? i)]
    (is (= (rules/row-of w) (intent/why-of i)))))

(deftest every-row-has-a-unique-id
  (is (= (count rules/all-rules) (count (set (map :rule/id rules/all-rules))))))

(deftest the-hunt-is-its-rules-first-holding-row
  (doseq [w worlds] (is (= (rules/decide w) (pearls/hunt w)))))

(deftest every-still-wanted-method-names-a-row
  (let [ids (set (map :rule/id rules/all-rules))
        named (filter (comp #{"hunt" "pearls"} namespace)
                      (filter keyword? (keys (methods intent/still-wanted?))))]
    (is (seq named))
    (is (every? ids named) "a still-wanted? method for a row that does not exist")))

(deftest the-phase-of-a-hunt-is-the-first-not-done
  (is (= :set-up (rules/phase (ps/standing {}))))
  (is (= :provoke (rules/phase (ps/burrowed [8.5 61.0 8.5] {})))))

(deftest a-strike-on-the-way-out-is-answered-before-the-pocket-is-sealed
  ;; Logged state, sim R1/17 seed 2 (probe wide-bo2-R1-17, issue #263): the pocket was sealed and
  ;; held (:seal, :pocket-hold) while a wither skeleton at 0.7 blocks hit the bot four times, 20
  ;; health to none in four seconds, because the pocket-bore row came before the tunnel answer.
  (let [ids (mapv :rule/id rules/all-rules)
        at (fn [id] (.indexOf ^java.util.List ids id))]
    (is (< -1 (at :hunt/tunnel-answer) (at :hunt/pocket-bore)))))
