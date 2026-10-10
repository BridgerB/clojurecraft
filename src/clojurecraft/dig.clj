(ns clojurecraft.dig
  "How long a block takes to break, and whether it drops anything, as pure functions of the
   block, what is held and the two conditions the game penalises (the eyes in water, the feet
   off the ground). This is vanilla's own rule (the siblings reproduced it from the client):

     damage per tick = speed / hardness / (30 when the tool harvests the block, else 100)
     ticks = ceil(1 / damage), or 0 when damage >= 1

   where speed is the tool tier's speed when the tool is the kind that mines the block, else 1,
   divided by 5 for each penalty in force. The tool decides harvestability, never the penalised
   speed. Every number comes from the generated tables (clojurecraft.blocks); nothing here is
   typed in or read from a clock."
  (:require [clojurecraft.blocks :as blocks]))

(def tick-ms 50)                      ; one game tick
(def harvest-divisor 30)              ; damage divisor when the tool harvests the block
(def no-harvest-divisor 100)          ; when it does not: the block breaks but drops nothing
(def penalty 5.0)                     ; the eyes in water, or the feet off the ground: speed / 5 each

(defn harvest?
  "Does a block state drop when broken with held (an item id, or nil for the hand)? Yes unless
   the block needs a tier and the held tool's tier ranks below it."
  [id held]
  (let [needs (blocks/needs-tier id)
        tier (:tier (blocks/tool held))]
    (or (nil? needs)
        (and (some? tier) (>= (blocks/tier-rank tier) (blocks/tier-rank needs))))))

(defn speed
  "The breaking speed of held (an item id, or nil) against block id: the tool tier's speed when
   the tool's kind is the one that mines the block, else 1.0."
  [id held]
  (let [{:keys [tier kind]} (blocks/tool held)]
    (if (and kind (= kind (blocks/tool-of id)))
      (:speed (blocks/materials tier))
      1.0)))

(defn ms
  "Milliseconds from START to the server breaking block id with held in hand, under the
   penalties {:eyes-in-water? bool :off-ground? bool}: nil for an unbreakable block (hardness
   below zero) or an unknown one, 0 when it breaks at once."
  [id held {:keys [eyes-in-water? off-ground?]}]
  (let [h (blocks/hardness id)]
    (when (and h (>= h 0.0))
      (let [s (cond-> (speed id held)
                eyes-in-water? (/ penalty)
                off-ground? (/ penalty))
            damage (/ s h (if (harvest? id held) harvest-divisor no-harvest-divisor))]
        (if (>= damage 1.0)
          0
          (long (* tick-ms (Math/ceil (/ 1.0 damage)))))))))

(defn best-tool
  "The player-inventory slot holding the tool to dig block id with, among {slot {:item id ...}}:
   the fastest tool of the block's own kind that harvests it, as [slot item-id]. nil when no
   held tool is of that kind (the hand is as good), or when the block needs a tier and no held
   tool of its kind reaches it (digging would break the block and drop nothing)."
  [id inventory penalties]
  (let [kind (blocks/tool-of id)
        candidates (for [[slot {:keys [item]}] inventory
                         :let [t (blocks/tool item)]
                         :when (and t (= kind (:kind t)) (harvest? id item))
                         :let [m (ms id item penalties)]
                         :when m]
                     [m slot item])
        [_ slot item] (first (sort candidates))]
    (when slot [slot item])))
