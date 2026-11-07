(ns clojurecraft.derived
  "Senses the bot already has and nobody read: facts two successive worlds hold together, as
   ledger events (senses/events folds them in). Each is a pure function of before and after, noted
   only on a change, with thresholds where a value is noisy: what set the bot alight and when it
   went out (alight-events), what it gained and lost of the items the stages count (item-events),
   the server setting its own motion and moving it (body-events), the biome under its feet
   (biome-events) and its breath running out (air-events).

   Why: 14,156 of 19,018 hits over 450 runs named no attacker, 62% of all hits were burning, and
   what lit the bot was never said."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.food :as food]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def cause-ms 2000)                    ; a hit this recent before the ignition is what lit it
(def air-step 60)                      ; ticks of breath (three seconds) between two air events
(def max-air 300)                      ; a full breath, as game/max-air

(defn r2 "x to two decimals, or nil." [x] (when x (/ (math/round (* 100.0 (double x))) 100.0)))

(defn fire-block
  "The cause a block name is when the bot stands in it: :lava, :in_fire for fire, or nil."
  [block-name]
  (case block-name
    :lava :lava
    (:fire :soul_fire) :in_fire
    nil))

(defn alight-cause
  "What lit the bot at world: {:cause kw} of the newest hit within cause-ms that was not itself
   burning (its damage type: :fireball, :lava, :in_fire ...), with :by the attacker's kind when it
   had one; else the block at its feet when that is lava or fire; else {:cause :unknown}."
  [world]
  (let [now (:time/now world 0)
        hit (->> (:player/hits world)
                 reverse
                 (filter #(and (<= (- now (:at % 0)) cause-ms) (not= :on_fire (:type %))))
                 first)
        feet (when-let [[x y z] (:player/pos world)]
               [(long (math/floor x)) (long (math/floor y)) (long (math/floor z))])
        under (when feet (fire-block (blocks/name-of (terrain/block-at world feet))))]
    (cond hit (cond-> {:cause (:type hit)} (:by-kind hit) (assoc :by (:by-kind hit)))
          under {:cause under}
          :else {:cause :unknown})))

(defn alight-events
  "The bot caught fire (:alight, with alight-cause) or stopped burning (:out) between before and
   after, by :player/on-fire?."
  [before after]
  (let [b (boolean (:player/on-fire? before))
        a (boolean (:player/on-fire? after))]
    (cond (and a (not b)) [(assoc (alight-cause after) :ev :alight)]
          (and b (not a)) [{:ev :out}]
          :else [])))

(def tracked-items
  "The items a stage counts, [name ids step]: a change of the count by name is an :item event; step
   is the count a change must cross a multiple of (1: every change)."
  (let [ids (fn [ns] (into #{} (keep recipe/item-id) ns))]
    [[:ender_pearl (ids [:ender_pearl]) 1] [:blaze_rod (ids [:blaze_rod]) 1]
     [:ender_eye (ids [:ender_eye]) 1]
     [:pickaxe
      (ids [:wooden_pickaxe :stone_pickaxe :iron_pickaxe :golden_pickaxe :diamond_pickaxe
            :netherite_pickaxe]) 1] [:food (ids (keys food/nutrition)) 1]
     [:cobblestone (ids [:cobblestone]) 64]
     [:bucket (ids [:bucket :water_bucket :lava_bucket]) 1]]))

(defn item-events
  "An :item event {:item name :n delta :have count} for each tracked item whose count in the
   inventory changed between before and after (cobblestone only when it crossed a multiple of 64).
   Nothing before the bot holds an inventory."
  [before after]
  (when (and (:player/inventory before) (:player/inventory after))
    (vec (for [[nm ids step] tracked-items
               :let [b (inventory/held before ids)
                     a (inventory/held after ids)]
               :when (and (not= a b) (not= (quot a step) (quot b step)))]
           {:ev :item :item nm :n (- a b) :have a}))))

(defn body-events
  "The server acted on the bot's own body between before and after: it set its motion (:knock, the
   velocity) or moved it (:corrected, the distance), each once per packet by the fact's own time."
  [before after]
  (let [k (:player/knock after)
        c (:player/corrected after)]
    (cond-> []
      (and k (not= k (:player/knock before))) (conj {:ev :knock :v (mapv r2 (:vel k))})
      (and c (not= c (:player/corrected before))) (conj {:ev :corrected :d (r2 (:d c))}))))

(defn biome-events
  "The biome under the bot's feet changed between before and after: {:ev :biome :biome id} (the
   server's biome registry id; nothing here names it). Nothing while either chunk is unloaded."
  [before after]
  (let [at (fn [w] (when-let [p (:player/pos w)] (chunk/biome-at (:world/chunks w) p)))
        b (at before)
        a (at after)]
    (if (and a (not= a b)) [{:ev :biome :biome a}] [])))

(defn air-events
  "The bot's breath fell past a multiple of air-step ticks, or filled up again, while its eyes are
   in a fluid: {:ev :air :air n}. An estimate (game/breathe), so only the crossings are noted."
  [before after]
  (let [b (:player/air before max-air)
        a (:player/air after max-air)]
    (if (or (and (< a b) (< (quot a air-step) (quot b air-step))) (and (= a max-air) (< b max-air)))
      [{:ev :air :air a}]
      [])))

(defn events
  "Every derived event between before and after, in order: alight-events, item-events,
   body-events, biome-events, air-events. Pure; empty when nothing changed."
  [before after]
  (vec (concat (alight-events before after)
               (item-events before after)
               (body-events before after)
               (biome-events before after)
               (air-events before after))))
