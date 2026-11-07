(ns clojurecraft.senses-test
  "Senses: a line a second of what the bot senses and decides, and the classes a run's last lines
   show. Pure over world values, as the observer reads them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.senses :as senses]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def blaze-type (some (fn [[id k]] (when (= :blaze k) id)) blocks/entity-names))

(defn- nether
  "A bot in play on one Nether column, holding seven rods, a blaze 6 blocks off."
  []
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :time/now 1793886242032
             :player/pos [8.5 64.0 8.5]
             :player/health 17.3
             :player/food 20
             :player/loaded? true
             :player/on-ground? true
             :player/on-fire? true
             :player/dimension dimension/nether
             :world/chunks {[0 0] (world/column {})}
             :player/inventory {36 {:item (recipe/item-id :blaze_rod) :count 7}}
             :world/entities
             {42615 {:entity/type blaze-type :entity/pos [14.5 65.0 8.5] :entity/health 20.0}}
             :plan/intent {:intent/kind :pocket :intent/goal :ender-pearls})))

(deftest a-line-says-what-the-bot-senses-and-decides
  (let [l (senses/of (nether) 3015)]
    (is (= 1793886242032 (:t l)))
    (is (= 17.3 (:hp l)))
    (is (= 7 (:rods l)))
    (is (:fire? l))
    (is (= 3015 (:tick-ms l)) "the loop's slowest event, from the observer")
    (is (= {:kind :pocket :goal :ender-pearls} (:intent l)))
    (is (= [{:kind :blaze :d 6.1 :hp 20.0 :sees? true}] (:hostiles l)))
    (testing "no position, no line: the bot is not in play yet"
      (is (nil? (senses/of (dissoc (nether) :player/pos) 0))))))

(deftest a-run-ends-in-the-classes-its-last-lines-show
  ;; 42024297742/s2/Clj_r57 on 0ace52e: rod 7, a pocket, the loop frozen 3015 ms, burning, then dead
  (let [blaze {:kind :blaze :d 6.0 :sees? true}
        lines [{:t 1000 :tick-ms 40 :hostiles [blaze] :watchers 1 :intent {:kind :pocket}}
               {:t 4015
                :tick-ms 3015
                :fire? true
                :hostiles (vec (repeat 6 blaze))
                :watchers 6
                :intent {:kind :pocket}}]]
    (is (= #{:freeze :burning :swarm :open :building :watched} (senses/classes lines))))
  (is (= #{:idle :open} (senses/classes [{:t 1 :tick-ms 5 :waiting :nothing-to-do}])))
  (is (= #{} (senses/classes [{:t 1 :tick-ms 5 :pocket {:in? true :sealed? true}}]))
      "sealed and quiet: nothing to name"))

(deftest lines-keep-the-last-five-minutes
  (let [ls (reduce senses/kept [] (map (fn [t] {:t t}) (range (+ senses/keep-lines 5))))]
    (is (= senses/keep-lines (count ls)))
    (is (= 5 (:t (first ls))) "the oldest five dropped")))

(deftest the-ledger-notes-every-change-of-a-fight
  ;; the observability plan, part C: Clj_r10 (900395b lane 2) was hit nine times by a hoglin over 40
  ;; s while it held still in a pocket and never swung; the ledger says each of those as it happens
  (let [before (nether)
        t (+ 50 (:time/now before))
        after
        (-> before
            (assoc :time/now t
                   :player/health 13.3
                   :player/hits [{:at t :type :mob_attack :hp 17.3 :by 42615 :by-kind :blaze}]
                   :plan/intent
                   (with-meta {:intent/kind :fight :intent/target 42615 :intent/id 7}
                              {:intent/why :struck-back}))
            (assoc-in [:world/entities 42615 :entity/hurt-at] t)
            (assoc-in [:world/entities 9] {:entity/type blaze-type :entity/pos [10.5 64.0 8.5]})
            (assoc :bot/effects
                   [{:effect/kind :send :effect/packet {:packet/name :attack :entity-id 42615}}]))
        es (senses/events before after)
        by (group-by :ev es)]
    (is (every? #(= t (:t %)) es))
    (is (= :fight (:kind (first (:intent by)))))
    (is (= :struck-back (:why (first (:intent by)))))
    (is (= {:ev :hit :at t :type :mob_attack :hp 17.3 :by 42615 :by-kind :blaze :t t}
           (first (:hit by))))
    (is (= 13.3 (:hp (first (:hp by)))))
    (is (= 42615 (:eid (first (:swing by)))) "the bot's own swing")
    (is (= 42615 (:eid (first (:hurt by)))) "and it landed")
    (is (= 9 (:eid (first (:appear by)))) "a second blaze came near")
    (is (empty? (senses/events before before)) "nothing changed: nothing noted")))

(deftest a-moment-holds-the-whole-mind-at-an-ending
  ;; the observability plan, part D: what a death's reader needs in one value
  (let [w (-> (nether)
              (assoc :player/hits [{:at 1 :type :fireball :hp 20.0 :by 42615 :by-kind :blaze}]
                     :player/armor 14.0
                     :pocket/site {:pocket/cell [8 64 8]}
                     :plan/status :active
                     :plan/intent
                     {:intent/kind :pocket
                      :intent/why :refuge
                      :intent/child {:intent/kind :place :intent/target [9 64 8]}}))
        m (senses/moment w)]
    (is (= [:pocket :place] (mapv :intent/kind (:intents m))) "the intent and its child")
    (is (= :refuge (:intent/why (first (:intents m)))))
    (is (= :blaze (get-in m [:entities 42615 :kind])))
    (is (= 6.1 (get-in m [:entities 42615 :d])))
    (is (= {:pocket/site {:pocket/cell [8 64 8]}} (select-keys (:stage m) [:pocket/site])))
    (is (= 14.0 (:armor m)))
    (is (= :fireball (:type (first (:hits m)))))
    (is (seq (:slice m)) "the blocks round the feet")
    (is (vector? (:threats m)) "the eids it ranked as threats, for blind to set against the truth")
    (is (nil? (senses/moment (dissoc w :player/pos))))))

(deftest a-child-intent-is-an-event-with-its-why
  (let [parent (fn [child]
                 (cond-> {:intent/kind :tunnel-to :intent/id 3} child (assoc :intent/child child)))
        child (with-meta {:intent/kind :dig :intent/target [1 2 3]} {:intent/why :child/tunnel-to})
        before {:plan/intent (parent nil)}
        after {:plan/intent (parent child)}
        es (senses/intent-events before after)]
    (is (= [{:ev :child :kind :dig :target [1 2 3] :why :child/tunnel-to :parent :tunnel-to}] es))
    (is (empty? (senses/intent-events after after)) "the same child is not said twice")
    (is (= [:dig]
           (mapv :kind
                 (senses/intent-events after
                                       {:plan/intent (parent {:intent/kind :dig
                                                              :intent/target [1 2 4]})})))
        "a new target is a new child")))

(deftest the-ledger-names-what-lit-the-bot
  ;; 62% of hits over 450 runs were burning and none said what lit the bot
  (let [dry (assoc (nether) :player/on-fire? false)
        t (inc (:time/now dry))
        lit (fn [w]
              (-> w
                  (assoc :time/now t :player/on-fire? true)))
        ev (fn [w] (first (filter #(= :alight (:ev %)) (senses/events dry w))))]
    (testing "a fireball hit just before: its type and the attacker"
      (is (= {:ev :alight :cause :fireball :by :blaze :t t}
             (ev (assoc (lit dry)
                        :player/hits
                        [{:at (- t 100) :type :fireball :hp 20.0 :by 42615 :by-kind :blaze}])))))
    (testing "a burning hit is not a cause, and an old one is not either"
      (is (= :unknown
             (:cause (ev (assoc (lit dry)
                                :player/hits
                                [{:at (- t 100) :type :on_fire :hp 20.0}
                                 {:at (- t 9000) :type :lava :hp 20.0}]))))))
    (testing "lava in the feet cell lights it when no hit came first"
      (let [col (world/column {[8 64 8] blocks/lava-base})]
        (is (= :lava (:cause (ev (assoc-in (lit dry) [:world/chunks [0 0]] col)))))))
    (testing "going out is noted, staying lit is not"
      (is (= [:out] (mapv :ev (senses/events (lit dry) (assoc (lit dry) :player/on-fire? false)))))
      (is (empty? (filter #{:alight :out} (map :ev (senses/events (lit dry) (lit dry)))))))))

(deftest the-ledger-notes-items-gained-and-lost
  (let [before (nether)
        t (inc (:time/now before))
        pearl (recipe/item-id :ender_pearl)
        stone (recipe/item-id :cobblestone)
        ev (fn [inv] (senses/events before (assoc before :time/now t :player/inventory inv)))
        item (fn [es k] (first (filter #(and (= :item (:ev %)) (= k (:item %))) es)))]
    (testing "a pearl picked up and the rods lost on one line each"
      (let [es (ev {37 {:item pearl :count 2}})]
        (is (= {:ev :item :item :ender_pearl :n 2 :have 2 :t t} (item es :ender_pearl)))
        (is (= -7 (:n (item es :blaze_rod))))))
    (testing "cobblestone only when a multiple of 64 is crossed"
      (is (nil? (item (ev {36 {:item (recipe/item-id :blaze_rod) :count 7}
                           38 {:item stone :count 10}})
                      :cobblestone)))
      (is (= 64
             (:have (item (ev {36 {:item (recipe/item-id :blaze_rod) :count 7}
                               38 {:item stone :count 64}})
                          :cobblestone)))))
    (testing "no change, no event"
      (is (empty? (filter #(= :item (:ev %)) (senses/events before (assoc before :time/now t))))))))

(deftest the-ledger-notes-what-the-server-does-to-the-body
  (let [before (nether)
        t (inc (:time/now before))
        after (assoc before
                     :time/now t
                     :player/knock {:vel [0.4 0.3612 -0.2] :at t}
                     :player/corrected {:d 2.456 :at t})
        by (group-by :ev (senses/events before after))]
    (is (= [0.4 0.36 -0.2] (:v (first (:knock by)))))
    (is (= 2.46 (:d (first (:corrected by)))))
    (is (empty? (filter #{:knock :corrected} (map :ev (senses/events after after)))))))

(deftest the-ledger-notes-a-new-biome-and-the-breath
  (let [col (fn [id]
              (assoc (world/column {}) :chunk/min-y 0 :chunk/biomes (vec (repeat 16 {:single id}))))
        before (assoc-in (nether) [:world/chunks [0 0]] (col 3))
        t (inc (:time/now before))
        moved (assoc-in (assoc before :time/now t) [:world/chunks [0 0]] (col 5))]
    (is (= [{:ev :biome :biome 5 :t t}] (filterv #(= :biome (:ev %)) (senses/events before moved))))
    (is (empty? (filter #(= :biome (:ev %)) (senses/events moved moved)))))
  (let [w (assoc (nether) :player/air 300)
        t (inc (:time/now w))
        air (fn [a b]
              (filterv #(= :air (:ev %))
                       (senses/events (assoc w :player/air a)
                                      (assoc w :time/now t :player/air b))))]
    (is (empty? (air 299 298)) "inside one step: nothing")
    (is (= [:air] (map :ev (air 241 239))) "crossed 240")
    (is (= 300 (:air (first (air 250 300)))) "a full breath again")))
