(ns clojurecraft.brief-test
  "A Copilot session's brief (bb brief): the signature a title names, the judge's lines kept, grep
   hits as values, the pull requests that closed an issue, and the markdown, whose empty sections
   are left out. Babashka runs these (bb test:bb)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.brief :as brief]))

(deftest a-title-names-a-stage-and-a-reason
  (is (= {:stage "fortress" :reason "no-stand"}
         (brief/signature "Bot stops at fortress: no-stand")))
  (is (= {:stage "flint-and-steel" :reason "stale-window"}
         (brief/signature "Bot stops at flint-and-steel: stale-window")))
  (is (nil? (brief/signature "Something else"))))

(deftest clipped-says-what-it-cut
  (is (= "abc" (brief/clip "abc" 5)))
  (is (str/starts-with? (brief/clip "abcdefgh" 3) "abc\n… (5 more")))

(deftest the-judge-lines-and-the-last-of-each-story
  (let [story (for [i (range 50)] (str "STORY /a.edn.gz [" i "]"))
        out (str/join "\n"
                      (concat ["Downloading..." "JUDGE {:path \"/a.edn.gz\" :recurs? true}"]
                              story
                              ["STORY /b.edn.gz [0]"]))
        kept (brief/judged-lines out)]
    (is (= "JUDGE {:path \"/a.edn.gz\" :recurs? true}" (first kept)))
    (is (not-any? #(str/starts-with? % "Downloading") kept))
    (testing "the last story-kept lines of a snapshot: its failure is at the end"
      (is (some #{"STORY /a.edn.gz [49]"} kept))
      (is (not-any? #{"STORY /a.edn.gz [0]"} kept))
      (is (some #{"STORY /b.edn.gz [0]"} kept)))))

(deftest grep-hits-are-values
  (is (= [{:file "src/clojurecraft/build.clj" :line 1109 :text ":else :no-stand)))"}]
         (brief/grep-hits
          "src/clojurecraft/build.clj:1109:                        :else :no-stand)))\n"))))

(deftest the-prs-that-closed-each-issue
  (is (= {62 [{:number 324 :title "fix: x"}] 65 []}
         (brief/closing-prs {:data {:repository {:i62 {:closedByPullRequestsReferences
                                                       {:nodes [{:number 324 :title "fix: x"}]}}
                                                 :i65 {:closedByPullRequestsReferences {:nodes
                                                                                        []}}}}}))))

(deftest the-brief-leaves-out-what-it-has-not
  (let [issue {:number 328
               :title "Bot stops at fortress: no-stand"
               :body "body"
               :comments [{:createdAt "t1" :body "OLD-COMMENT"}
                          {:createdAt "t2" :body "NEW-COMMENT"}]}
        full (brief/brief issue
                          {:snapshots ["cache/issues/328/R1-2-3.edn.gz"]
                           :judged {:main {:sha "abc" :out "JUDGE {:recurs? true}"}}
                           :raised
                           [{:file "src/clojurecraft/build.clj" :line 1109 :text "no-stand"}]
                           :notes ["brain/bot/plan/fortress-tunnel.md"]
                           :related [{:number 196
                                      :title "Bot stops at portal: no-stand"
                                      :prs [{:number 200 :title "fix: y"}]}]})
        bare (brief/brief issue {})]
    (is (str/includes? full "Judged on main (abc)"))
    (is (str/includes? full "`src/clojurecraft/build.clj:1109`"))
    (is (str/includes? full "closed by PR #200 fix: y"))
    (is (not (str/includes? full "Judged on this branch"))
        "no branch judgement when the branch is main")
    (is (< (str/index-of full "NEW-COMMENT") (str/index-of full "OLD-COMMENT"))
        "comments newest first")
    (testing "no snapshots: the brief says stop"
      (is (str/includes? bare "None in cache/issues/328"))
      (is (not (str/includes? bare "Judged on main"))))))

(deftest paths-into-the-checkout-are-cut
  (testing "a snapshot path made absolute as found makes it, as the judge prints it"
    (is (= "JUDGE {:path \"cache/issues/1/a.edn.gz\"}"
           (brief/relative
            (str "JUDGE {:path \"" (fs/absolutize "cache/issues/1/a.edn.gz") "\"}"))))))
