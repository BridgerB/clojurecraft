(ns clojurecraft.botlogs-test
  "The bot-log digest: a recording's lines to the text a lead reads within one batch."
  (:require [clojure.test :refer [deftest is]]
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
