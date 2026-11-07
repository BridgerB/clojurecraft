(ns clojurecraft.pearls-parity-test
  "The pearl hunt's rule table (pearls-rules.edn) chooses what the cond it replaced chose: the
   same intent and the same why label, over a grid of worlds built from the pearl tests' own
   canned states."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls-legacy :as legacy]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.rules :as rules]))

(def nether "The Nether's dimension id." "minecraft:the_nether")

(def enderman-sets
  "Entity maps (id -> entity): none, one enderman near, mid, far and very far, angry or calm,
   with a zombie, a ghast, and the piglin pack of the burst state."
  (let [e (fn [x] (ps/enderman [x 64.0 8.5]))
        angry (fn [x] (assoc (e x) :entity/creepy? true))]
    [{} {7 (e 11.5)} {7 (angry 11.5)} {7 (e 13.5)} {7 (angry 13.5)} {7 (e 28.5)} {7 (angry 28.5)}
     {7 (e 60.5) 9 (e 40.5)} {7 (e 95.5)} {7 (angry 11.5) 8 (angry 25.5)}
     {7 (e 11.5) 8 (ps/mob :zombie [10.5 64.0 8.5])} {7 (e 20.5) 8 (ps/mob :ghast [14.5 66.0 8.5])}
     {8 (ps/mob :zombie [9.5 64.0 8.5])} {8 (ps/mob :ghast [14.5 66.0 8.5])} ps/burst {7 (e 3.5)}
     {7 (angry 3.5)}]))

(def places
  "Where the bot stands: [name world-fn], each taking entities."
  [["floor" ps/standing]
   ["floor-pickaxe" #(assoc-in (ps/standing %) [:player/inventory 1] (ps/item :iron_pickaxe 1))]
   ["wide-pickaxe"
    #(ps/wide (assoc-in (ps/standing %) [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
   ["burrow-end" #(ps/burrowed [8.5 61.0 8.5] %)] ["burrow-mouth" #(ps/burrowed [3.5 64.0 8.5] %)]
   ["burrow-stairs" #(ps/burrowed [5.5 62.0 8.5] %)]
   ["burrow-out" #(ps/wide (ps/burrowed [8.5 64.0 14.5] %))]
   ["nether-burrow-end" #(assoc (ps/burrowed [8.5 61.0 8.5] %) :player/dimension nether)]
   ["nether-floor" #(assoc (ps/wide (ps/standing %)) :player/dimension nether)]
   ["walled" #(assoc (ps/walled-in (ps/passage 12 14) [20.5 64.0 20.5]) :world/entities %)]])

(def conditions
  "Changes to a world: [name fn]."
  [["fresh" identity] ["hurt" #(assoc % :player/health 9.0)]
   ["nearly-dead" #(assoc % :player/health 3.0)]
   ["wounded-fed"
    #(assoc %
            :player/health 15.0
            :player/food 12
            :player/inventory (assoc (:player/inventory %) 2 (ps/item :cooked_beef 8)))]
   ["blacklisted" #(assoc % :plan/blacklist #{7})]
   ["cornered" #(assoc % :plan/cornered #{7} :player/health 9.0)] ["looked-out" ps/looked-out]
   ["stale" #(ps/looked % 14)] ["lured" #(ps/lured % 3)] ["fought-out" #(ps/fought % 9)]
   ["relocating" #(assoc % :pearls/relocate {:from [8 64 8] :at 0 :near #{}} :time/now 600000)]
   ["deaths" #(assoc % :stats/deaths 1)]])

(defn fortress-world
  "A bot in the Nether at a known blaze spawner (as the fortress tests build one), with the
   options: :blaze a blaze in sight, :pick a pickaxe, :trail an inbound trail, :stage the pearl
   stage in play, :hp its health, :ghast a ghast in sight."
  [{:keys [blaze pick trail stage hp ghast]}]
  (let [spawner [889 60 -206]
        entities (cond-> {}
                   blaze (assoc 9 (assoc (ps/mob :blaze [892.5 60.0 -204.5]) :entity/health 20.0))
                   ghast (assoc 10 (ps/mob :ghast [900.5 64.0 -204.5])))]
    (cond-> (-> (ps/standing entities)
                (assoc :player/dimension nether :player/pos [890.5 60.0 -203.9] :player/health hp)
                (memory/observe spawner blaze/spawner))
      pick (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
      trail
      (assoc :fortress/trail {:trail/target spawner :trail/cells []}
             :plan/trail
             [[976 48 -176] [958 49 -190] [926 63 -190] [902 63 -198] [897 61 -204] [890 60 -204]])
      stage (assoc :plan/goals #{:ender-pearls}))))

(def fortress-worlds
  "Fortress states: every combination of a blaze, a pickaxe, a trail, the stage and a ghast in
   sight, at full health and hurt."
  (for [blaze [false true]
        pick [false true]
        trail [false true]
        stage [false true]
        ghast [false true]
        hp [20.0 9.0]]
    [(str "fortress/"
          (if blaze "b" "-")
          (if pick "p" "-")
          (if trail "t" "-")
          (if stage "s" "-")
          (if ghast "g" "-")
          hp)
     (fortress-world {:blaze blaze :pick pick :trail trail :stage stage :ghast ghast :hp hp})]))

(def named-worlds
  "States the pearl tests build by name, and ones the grid cannot reach."
  (concat
   [["stranded" ps/stranded] ["distant" (ps/distant-world [100.5 64.0 8.5])]
    ;; a bot on a lone two-high pillar
    ["perched"
     (assoc (ps/standing {} {[8 64 8] ps/stone [8 65 8] ps/stone}) :player/pos [8.5 66.0 8.5])]
    ;; at the burrow's end with a blacklisted enderman standing out of reach in its stairs,
    ;; after a long stand there
    ["blocked-out"
     (-> (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [4.5 63.0 8.5])}) 14)
         (assoc :plan/blacklist #{7}))]
    ;; a hunt that has stood looking for five minutes with nothing tracked
    ["wander" (ps/stuck-in (ps/wide (ps/standing {})) 61)]
    ["wander-pickaxe"
     (ps/stuck-in (ps/wide
                   (assoc-in (ps/standing {}) [:player/inventory 1] (ps/item :iron_pickaxe 1)))
                  61)]
    ;; hurt and cornered by a calm enderman with blocks to lay
    ["cornered-filler"
     (-> (ps/standing {7 (ps/enderman [11.5 64.0 8.5])})
         (assoc :player/health 9.0 :plan/cornered #{7})
         (assoc-in [:player/inventory 3] (ps/item :cobblestone 64)))]
    ["hurt-threat-filler"
     (-> (ps/standing {8 (ps/mob :zombie [9.5 64.0 8.5])})
         (assoc :player/health 9.0)
         (assoc-in [:player/inventory 3] (ps/item :cobblestone 64)))]
    ["swarmed-filler"
     (-> (ps/standing ps/burst)
         (assoc-in [:player/inventory 3] (ps/item :cobblestone 64)))]]
   fortress-worlds))

(defn grid
  "Every [label world] of places x entity sets x conditions, then the named worlds."
  []
  (concat (for [[pn pf] places
                [i es] (map-indexed vector enderman-sets)
                [cn cf] conditions]
            [(str pn "/" i "/" cn) (try (cf (pf es)) (catch Exception _ nil))])
          named-worlds))

(defn outcome
  "[intent why] of f on world, or [:threw class] when it throws."
  [f world]
  (try (let [i (f world)] [i (intent/why-of i)]) (catch Exception ex [:threw (class ex)])))

(deftest the-rule-table-chooses-what-the-cond-chose
  (let [worlds (remove (comp nil? second) (grid))
        results
        (mapv (fn [[label w]]
                {:label label :table (outcome rules/decide w) :cond (outcome legacy/legacy-hunt w)})
              worlds)
        differ (remove #(= (:table %) (:cond %)) results)
        whys (into #{} (keep (comp second :cond)) results)]
    (is (empty? (map :label (take 5 differ))) "worlds where the table and the cond differ")
    (is (< 200 (count results)) "worlds compared")
    (when (.isDirectory (io/file "out"))
      (spit "out/pearls-parity.edn"
            (pr-str {:worlds (count results)
                     :rules (count rules/rules)
                     :hit (count whys)
                     :not-hit (vec (sort (remove whys (map :rule/id rules/rules))))
                     :differ (vec (map :label (take 20 differ)))})))))

(deftest the-phase-of-a-hunt-is-the-first-not-done
  (is (= :set-up (rules/phase (ps/standing {}))))
  (is (= :provoke (rules/phase (ps/burrowed [8.5 61.0 8.5] {})))))
