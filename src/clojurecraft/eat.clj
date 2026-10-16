(ns clojurecraft.eat
  "Eating (issue #259): on Normal difficulty hunger drains with every block dug and every jump,
   and a bot whose food runs out stops healing and then starves down to one heart.

   Requiring this namespace registers the :eat row's rules: it is done by :fed? (the food the
   server last said is at least hungry-below, or nothing edible is held), planned by :eat into one
   :eat intent, and the executor is a defmethod of intent/run. The row is an always row at
   priority 0 after the escapes, so like them it preempts whatever runs; the food level comes
   from set-health (:player/food), so there is no clock and no guess: the intent is done when the
   server's food rises.

   The intent puts the food in the hand (place/equip), sends one use-item, and stands still. The
   server eats on its own clock: once started, using a food item finishes after its eating time
   (32 ticks for most) with no further word from the client, then sends set-health with the new
   food. A deadline of the food's eating time (food/eating-ticks, stretched by the server's
   slowdown, intent/slowdown) plus answer-ms fails it :not-eaten."
  (:require [clojurecraft.food :as food]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.packet :as packet]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def hungry-below 14)                 ; food under this is hunger to answer: six short of full, four below where natural healing stops (18)
(def answer-ms 3000)                  ; past the eating time, how long the server's food may take to rise

(def unsafe
  "Food the bot never eats: what poisons or makes hungry (rotten flesh, spider eye, pufferfish,
   poisonous potato, raw chicken), chorus fruit (it teleports the eater), suspicious stew (a
   random effect), and golden apples (worth more than food)."
  #{:rotten_flesh :spider_eye :pufferfish :poisonous_potato :chicken :chorus_fruit :suspicious_stew
    :golden_apple :enchanted_golden_apple})

(def edible
  "What the bot eats, by item name, with the hunger each restores: the game's food
   (food/nutrition) less what is unsafe."
  (apply dissoc food/nutrition unsafe))

(def store-slots 36)                  ; player slots 0-35, the hotbar and the main inventory: never the armour or the offhand

(defn food-held
  "The name of the food to eat now: the most nourishing edible item in the hotbar or the main
   inventory (player slots under store-slots), the lower slot on a tie; nil when none is held."
  [world]
  (->> (:player/inventory world)
       (keep (fn [[p {:keys [item]}]]
               (let [n (recipe/item-name item)]
                 (when (and (< -1 p store-slots) (edible n)) [(- (edible n)) p n]))))
       sort
       first
       last))

(defn hungry?
  "Is the food the server last sent (set-health) under hungry-below? Not before the first
   set-health: what the bot does not know is no reason to eat."
  [world]
  (boolean (some-> (:player/food world)
                   (< hungry-below))))

(defmethod plan/done-by :fed?
  ;; fed, or nothing to eat: a row with nothing to do must be done, or it would stand in front of
  ;; every other target
  [world _]
  (not (and (hungry? world) (food-held world))))

(defmethod plan/next-intent :eat
  [world _ _]
  (when-let [n (and (hungry? world) (food-held world))] {:intent/kind :eat :intent/item n}))

(defn deadline
  "The ms past which an :eat of food n started now has failed: its eating time by the server's
   clock (stretched by intent/slowdown) and answer-ms more."
  [world n]
  (+ (:time/now world)
     (* (food/eating-ticks n) game/server-tick-ms (intent/slowdown world))
     answer-ms))

(defmulti eat-stage
  "Advance an :eat intent one tick in its :intent/stage (:equip :use :eating). Called only when no
   inventory click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod eat-stage :equip [world {:intent/keys [item]}] (place/equip world item :use))

(defmethod eat-stage :use
  ;; one use-item along the look, no swing: the server starts eating and finishes by itself
  [world {:intent/keys [item]}]
  (let [seq (inc (:bot/sequence world))
        [yaw pitch] (:player/look world)]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item
                    :hand (packet/value :hand :main-hand)
                    :sequence seq
                    :y-rot yaw
                    :x-rot pitch})
        (intent/assoc-intent :intent/stage :eating
                             :intent/food-before (:player/food world 0)
                             :intent/eat-by (deadline world item)))))

(defmethod eat-stage :eating
  [world {:intent/keys [food-before eat-by]}]
  (cond (> (:player/food world 0) food-before) (intent/done world)
        (> (:time/now world) eat-by) (intent/fail world :not-eaten)
        :else world))

(defmethod intent/run :eat
  ;; {:intent/kind :eat :intent/item :bread}: stand still, the food in hand, one use-item, done
  ;; when the server's food rises
  [world i _]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip))
        world (assoc world :plan/intent i :player/controls {})]
    (case (window/waiting world i)
      :overdue (window/lost world i :equip)
      :waiting world
      (if (:intent/resync i)
        (window/resync world (window/view world :inventory))
        (eat-stage world i)))))
