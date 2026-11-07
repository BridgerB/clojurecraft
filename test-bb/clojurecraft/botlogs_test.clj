(ns clojurecraft.botlogs-test
  "The bot-log digest: a recording's lines to the text a lead reads within one batch."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.botlogs :as botlogs]))

(def lines
  "Recording lines in the shape a race writes them (lane 42024297742 s1 Clj_r22, trimmed)."
  ["#:event{:kind :packet, :packet {:packet/name :login, :death-screen? true, :entity-id 12200, :seed 7}}"
   "#:event{:kind :tick, :now 1793881408918, :rand 0.81}"
   "#:record{:effects [#:effect{:kind :log, :message \"intent :collect-drops [-616 82 158]\"}]}"
   "#:event{:kind :packet, :packet {:entity-id 3583, :source-type 21, :source-cause 0, :source-direct 0, :packet/name :damage-event}}"
   "#:event{:kind :packet, :packet {:entity-id 900, :type 55, :x 1.0, :y 2.0, :z 3.0, :packet/name :add-entity}}"
   "#:event{:kind :packet, :packet {:entity-id 12200, :source-type 5, :source-cause 901, :source-direct 902, :packet/name :damage-event}}"
   "#:event{:kind :tick, :now 1793881408969, :rand 0.74}"
   "#:event{:kind :packet, :packet {:health 14.5, :food 18, :saturation 5.0, :packet/name :set-health}}"
   "#:record{:effects [#:effect{:kind :log, :message \"intent :collect-drops failed: :not-picked-up (attempt 0 of 3)\"} #:effect{:kind :log, :message \"say \\\"hi\\\"\"}]}"])

(deftest a-recording-digests-to-timed-logs-health-and-hits-on-the-bot
  (is (= ["1793881408918 intent :collect-drops [-616 82 158]"
          (str "1793881408918 "
               (botlogs/hit-text {900 55}
                                 "{:source-type 5, :source-cause 901, :source-direct 902}"))
          "1793881408969 hp 14.5 food 18"
          "1793881408969 intent :collect-drops failed: :not-picked-up (attempt 0 of 3)"
          "1793881408969 say \\\"hi\\\""]
         (botlogs/digest lines))
      "a hit on another entity (3583) is left out"))

(deftest a-hit-is-named-by-type-and-attacker
  (let [t (botlogs/hit-text {900 55} "{:source-type 5, :source-cause 901, :source-direct 901}")]
    (is (str/includes? t " by "))
    (is (str/ends-with? t "#900") "the source id less one is the entity")
    (is (not (str/includes? (botlogs/hit-text {}
                                              "{:source-type 5, :source-cause 0, :source-direct 0}")
                            " by "))
        "lava or a fall has no attacker")))

(deftest a-batch-archive-and-its-release-are-named-per-lane-and-hour
  (is (= "42024297742-r7-b3.tar.gz" (botlogs/asset 42024297742 7 3)))
  (is (= "botlogs-2026-11-06-04" (botlogs/tag "2026-11-06T04:12:00Z"))))

(deftest a-batch-counts-its-senses-and-their-coverage
  (let [dir (str (fs/create-temp-dir))
        _ (fs/create-dirs (fs/path dir "s1"))
        _ (spit (str (fs/path dir "s1" "Clj_r57.senses.edn"))
                "{:senses/class #{:freeze :burning} :senses/lines 2}\n{:t 1}\n")
        runs [{:run/bot "Clj_r57" :run/dir "s1" :ok false}
              {:run/bot "Clj_r58" :run/dir "s1" :ok false}]]
    (is (= {:coverage [1 2] :senses {"freeze" 1 "burning" 1}} (botlogs/senses-summary dir runs))
        "one of two runs has its senses: a blind spot of one")))

(deftest a-recording-is-found-while-the-lane-plays-and-after
  ;; recordings are packed to .edn.zst only at the job's end: lane 8 of adec45c uploaded nothing
  ;; from its first batch while botlogs looked only for .zst
  (let [dir (str (fs/create-temp-dir))
        r {:run/bot "Clj_r01" :run/dir "s1"}]
    (fs/create-dirs (fs/path dir "s1"))
    (is (nil? (botlogs/recording dir r)))
    (spit (str (fs/path dir "s1" "Clj_r01.edn")) "#:event{:kind :tick, :now 5, :rand 0.1}\n")
    (is (str/ends-with? (botlogs/recording dir r) "Clj_r01.edn"))
    (let [out (str (fs/path dir "out.log"))]
      (is (botlogs/digest-file! (botlogs/recording dir r) out))
      (is (= "\n" (slurp out)) "a tick alone says nothing"))))

(deftest a-sim-run-names-its-senses-file
  (let [dir (str (fs/create-temp-dir))
        f (str (fs/path dir "R1-44-1.senses.edn"))]
    (spit f "{:senses/class #{:idle} :senses/lines 1}\n{:t 1}\n")
    (is (= f (botlogs/senses-file "data/simrace" {:run/senses-file f})))
    (is (= {:coverage [1 1] :senses {"idle" 1}}
           (botlogs/senses-summary "data/simrace" [{:run/senses-file f :ok false}])))))

(deftest a-frozen-run-ships-its-recording
  (let [dir (str (fs/create-temp-dir))
        frozen (str (fs/path dir "a.senses.edn"))
        quiet (str (fs/path dir "b.senses.edn"))]
    (spit frozen "#:senses{:class #{:freeze :open} :lines 1}\n{:t 1}\n")
    (spit quiet "#:senses{:class #{:open} :lines 1}\n{:t 1}\n")
    (is (botlogs/keep-recording? frozen))
    (is (not (botlogs/keep-recording? quiet)))
    (spit quiet "#:senses{:class #{:idle :open} :lines 1}\n{:t 1}\n")
    (is (botlogs/keep-recording? quiet) "an idle run ships its recording too")
    (spit quiet "#:senses{:class #{:open} :lines 1}\n{:t 1}\n")
    (is (botlogs/keep-recording? quiet {:ok false :reason :death}) "a failed run ships it")
    (is (not (botlogs/keep-recording? quiet {:ok true})) "a won run does not")
    (is (= #{} (botlogs/senses-classes (str (fs/path dir "none.senses.edn")))))))

(deftest a-case-tells-how-a-run-ended
  ;; Clj_r10 (900395b lane 2 b1), written out: hoglin 1724 hit it nine times over 40 s while it held
  ;; still in a pocket it could not seal, and it never swung back
  (let [t0 1793989108107
        hit (fn [s]
              {:ev :hit :t (+ t0 (* 5000 s)) :type :mob_attack :by 1724 :by-kind :hoglin :hp 18.0})
        es (vec (concat
                 [{:ev :intent :t (- t0 1000) :kind :pocket-hold :why :pocket-bore/and-in?-hp-20-0}]
                 (map hit (range 9))
                 [{:ev :intent :t (+ t0 41000) :kind :eat :why :pocket-bore/and-in?-hp-20-0}
                  {:ev :death :t (+ t0 46000)}]))
        c (botlogs/case-of
           {:run/bot "Clj_r10" :reason :death-hoglin :death-message "Clj_r10 was slain by Hoglin"}
           es
           "bb probe why T A s1-Clj_r10 1")]
    (is (= "Clj_r10 was slain by Hoglin" (:killer c)))
    (is (= 9 (:hits c)))
    (is (= {:hoglin 9} (:hits-by c)))
    (is (= 0 (:swings c)) "it never swung back")
    (is (= :pocket-hold (get-in c [:first-hit :doing])) "first hit while holding still")
    (is (= :eat (get-in c [:at-end :doing])))
    (is (= [:death-hoglin :hoglin :eat :pocket-bore/and-in?-hp-20-0] (:signature c)))
    (is (str/includes? (botlogs/case-text c) "9 hits taken"))))

(deftest unknown-packets-are-named-and-the-deliberate-dropped
  (let [names (botlogs/packet-names {:play {:s2c {:sound 117 :rotate-head 83 :system-chat 121}}
                                     :configuration {:s2c {:registry-data 7}}})
        runs [{:stats {:stats/unknown {[:play 117] 500 [:play 83] 9000 [:configuration 7] 28}}}
              {:stats {:stats/unknown {[:play 117] 46 [:play 121] 3 [:play 999] 2}}}
              {:reason :timeout}]]
    (is (= :sound (names [:play 117])))
    (is (= {"sound" 546 "system-chat" 3 "[:play 999]" 2} (botlogs/unheard names runs))
        "rotate-head and registry-data are deliberate; an id with no name keeps its key")))

(deftest a-batch-says-what-it-saw
  (let [facts [{:ok? false
                :real? true
                :senses? true
                :events? true
                :moment? true
                :ring? true
                :truth? true
                :recording? true
                :intents 10
                :whys 4
                :dropped 0}
               {:ok? false
                :real? false
                :senses? true
                :events? true
                :moment? true
                :ring? true
                :truth? false
                :recording? false
                :intents 6
                :whys 6
                :dropped 2}
               {:ok? true
                :real? true
                :senses? true
                :events? false
                :moment? false
                :ring? false
                :truth? true
                :recording? false}]
        o (botlogs/observed facts {"sound" 3})]
    (is (= [3 3] (:senses o)))
    (is (= [2 3] (:events o)) "a run without its ledger is a blind spot")
    (is (= [2 3] (:truth o)) "a sim run without its truth file is one")
    (is (= [1 1] (:recording o)) "only a failed real run needs its recording")
    (is (= [10 16] (:why o)))
    (is (= 2 (:dropped o)))
    (is (= {"sound" 3} (:unheard o)))))

(deftest a-ledger-counts-its-intents-and-drops
  (let [f (str (fs/create-temp-file {:suffix ".events.edn"}))]
    (spit f
          (str "#:ledger{:events 3, :dropped 1}\n"
               "{:ev :intent, :kind :walk, :why :hunt/travel, :t 1}\n"
               "{:ev :intent, :kind :dig, :t 2}\n" "{:ev :hp, :hp 19.0, :t 3}\n"))
    (is (= {:intents 2 :whys 1 :dropped 1} (botlogs/ledger-facts f)))))
