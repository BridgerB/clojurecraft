(ns clojurecraft.fixtures
  "Shared test helpers: spec instrumentation, event folding, effect filters."
  (:require [clojure.spec.test.alpha :as stest]
            [clojurecraft.game :as game]
            [clojurecraft.spec]))

(defn instrumented
  "clojure.test fixture: instrument the reducers (the bot's and the server model's) for the
   duration of the namespace."
  [f]
  (stest/instrument [`game/step `clojurecraft.plan/step `clojurecraft.intent/run `clojurecraft.sim/step])
  (try (f) (finally (stest/unstrument))))

(def opts {:host "h" :port 1 :name "Clj_test"})

(def login-finished
  "The login-finished packet as the decoder produces it: every field its spec lists."
  {:packet/name :login-finished :uuid #uuid "00000000-0000-3000-8000-000000000000" :username "Clj_test"})

(defn fold
  "Fold events through step; returns [final-world [[now effect] ...]] with effects cleared
   after each step, as the loop does."
  [step world events]
  (reduce (fn [[w fx] e]
            (let [w (step w e)]
              [(assoc w :bot/effects []) (into fx (map (fn [f] [(:time/now w) f]) (:bot/effects w)))]))
          [world []] events))

(defn sent [fx] (for [[t {:effect/keys [kind packet]}] fx :when (= kind :send)] [t packet]))
(defn names [fx] (mapv (comp :packet/name second) (sent fx)))
(defn packets [fx] (mapv second (sent fx)))
(defn ticks [from to] (for [t (range from to 50)] {:event/kind :tick :event/now t :event/rand 0.5}))
(defn packet [p] {:event/kind :packet :event/packet p})
