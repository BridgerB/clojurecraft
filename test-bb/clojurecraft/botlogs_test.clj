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
   "#:event{:kind :packet, :packet {:entity-id 12200, :source-type 5, :source-cause 901, :source-direct 902, :packet/name :damage-event}}"
   "#:event{:kind :tick, :now 1793881408969, :rand 0.74}"
   "#:event{:kind :packet, :packet {:health 14.5, :food 18, :saturation 5.0, :packet/name :set-health}}"
   "#:record{:effects [#:effect{:kind :log, :message \"intent :collect-drops failed: :not-picked-up (attempt 0 of 3)\"} #:effect{:kind :log, :message \"say \\\"hi\\\"\"}]}"])

(deftest a-recording-digests-to-timed-logs-health-and-hits-on-the-bot
  (is (= ["1793881408918 intent :collect-drops [-616 82 158]"
          "1793881408918 hit source-type 5 by 901" "1793881408969 hp 14.5 food 18"
          "1793881408969 intent :collect-drops failed: :not-picked-up (attempt 0 of 3)"
          "1793881408969 say \\\"hi\\\""]
         (botlogs/digest lines))
      "a hit on another entity (3583) is left out"))

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
    (is (= #{} (botlogs/senses-classes (str (fs/path dir "none.senses.edn")))))))
