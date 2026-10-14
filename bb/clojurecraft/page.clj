(ns clojurecraft.page
  "The results page (issue #116): one static page that says how far the bots are from winning
   and what stands in the way, built from the rounds' own facts and served by GitHub Pages
   from the gh-pages branch. It replaces the race board issue.

     bb page round <dir> <kind> <run> <commit> <at>    the round's facts from the runs under dir
                                                       (what bb failures reads), as one EDN map
     bb page wall <dir>                                the round's failures ranked by how far the
                                                       bot got, then how often (the job summary)
     bb page build <rounds.edn> <out-dir>              index.html from the rounds, one map a line
     bb page publish <round.edn>                       the round appended to rounds.edn on
                                                       gh-pages, the page rebuilt, pushed

   A round is a map: the run, its kind (:real or :sim), the commit and when it was committed, how
   many runs, how many won, how many reached each milestone of the chain, and the failures by
   signature. rounds.edn holds one such map a line, newest last, so the page can always be
   rebuilt from the data and the data read without the page. The chain is goals.edn's: the
   targets in priority order up to the race's end (the Nether), read from the file, so the page
   and the bot agree on what a milestone is.

   Minimal on purpose: a table of rounds, one line chart per kind (an SVG the task draws itself,
   no library), the wall. Nothing a bot needs to reach the Nether is here; this is where we see
   whether it does. Babashka runs this (hiccup2 is bundled); nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]
            [hiccup2.core :as h]))

(def branch "gh-pages")                ; the page's branch: index.html and rounds.edn, nothing else
(def rounds-file "rounds.edn")
(def top-signatures 5)                 ; signatures named per round in the table
(def goals-file "resources/clojurecraft/goals.edn")
(def race-end "nether")                ; the --until the races play to: the chain ends there

;; ---------------------------------------------------------------- the chain and a round

(defn chain
  "The milestones in order, from the goal table: every target with a --until name that is not an
   escape or an aside, by priority, up to the one whose until is race-end."
  [goals]
  (let [targets (->> goals
                     (filter #(and (:goal/target? %)
                                   (:goal/until %)
                                   (not (:goal/always? %))
                                   (not= :wade (:goal/plan %))))
                     (sort-by :goal/priority))
        end (first (filter #(= race-end (:goal/until %)) targets))]
    (mapv :goal/id (take-while #(not= (:goal/id %) (:goal/id end)) targets))))

(defn with-end
  "The chain with its end: chain stops before the race's last target, this adds it."
  [goals]
  (let [targets (sort-by :goal/priority (filter #(= race-end (:goal/until %)) goals))]
    (into (chain goals) (map :goal/id) (take 1 targets))))

(defn reached
  "How many runs reached each milestone: a run's RESULT carries :plan/reached, the goals it had
   met when it ended."
  [runs milestones]
  (into {}
        (for [m milestones]
          [m (count (filter #(contains? (set (get-in % [:plan :plan/reached])) m) runs))])))

(defn round
  "The round as a value: run, kind, commit, at (an ISO instant), how many runs, how many won
   (RESULT :ok), the runs that reached each milestone, and the failures by signature with counts,
   the most frequent first (failures/grouped: no infrastructure stops)."
  [runs milestones kind run commit at]
  {:round/run run
   :round/kind kind
   :round/commit commit
   :round/at at
   :round/runs (count runs)
   :round/ok (count (filter :ok runs))
   :round/reached (reached runs milestones)
   :round/failures (mapv (fn [[sig rs]] [sig (count rs)]) (failures/grouped runs))})

(defn wall
  "The failures by the stage the bot was on, the furthest stage first: [[stage total
   [[signature count] ...]] ...]. The furthest stage with the most failures is the wall."
  [failures milestones]
  (let [place (zipmap milestones (range))]
    (->> (group-by (comp first first) failures)
         (map (fn [[stage sigs]] [stage (reduce + (map second sigs))
                                  (vec (sort-by (comp - second) sigs))]))
         (sort-by (fn [[stage total]] [(- (place stage -1)) (- total)]))
         vec)))

(defn furthest
  "The furthest milestone any run of the round reached, and how many did: [milestone n], or nil."
  [round milestones]
  (last (for [m milestones :let [n (get-in round [:round/reached m] 0)] :when (pos? n)] [m n])))

;; ---------------------------------------------------------------- the page

(def chart-width 640)
(def chart-height 220)
(def chart-pad 32)

(defn share
  "The share of a round's runs that reached milestone m, 0.0-1.0."
  [round m]
  (if (pos? (:round/runs round 0))
    (/ (get-in round [:round/reached m] 0) (double (:round/runs round)))
    0.0))

(defn hue
  "A colour for the i-th of n milestones: evenly round the wheel."
  [i n]
  (str "hsl(" (int (* 360 (/ i (max n 1)))) " 70% 40%)"))

(defn chart
  "An SVG line chart of the share of runs reaching each milestone, round by round (oldest to
   newest), one polyline a milestone, labelled at its last point. Nothing is drawn for a single
   round but its points."
  [rounds milestones]
  (let [n (count rounds)
        w (- chart-width (* 2 chart-pad) 120)
        hgt (- chart-height (* 2 chart-pad))
        x (fn [i] (+ chart-pad (if (> n 1) (* w (/ i (dec n))) 0)))
        y (fn [s] (+ chart-pad (* hgt (- 1 s))))]
    [:svg
     {:viewBox (str "0 0 " chart-width " " chart-height)
      :width chart-width
      :height chart-height
      :role "img"}
     [:rect {:x chart-pad :y chart-pad :width w :height hgt :fill "none" :stroke "#ccc"}]
     (for [s [0.25 0.5 0.75]]
       [:line {:x1 chart-pad :y1 (y s) :x2 (+ chart-pad w) :y2 (y s) :stroke "#eee"}])
     [:text {:x (- chart-pad 4) :y (+ (y 1) 4) :text-anchor "end" :font-size 10} "100%"]
     [:text {:x (- chart-pad 4) :y (+ (y 0) 4) :text-anchor "end" :font-size 10} "0%"]
     (for [[i m] (map-indexed vector milestones)
           :let [pts (map-indexed (fn [j r] [(x j) (y (share r m))]) rounds)
                 [lx ly] (last pts)
                 colour (hue i (count milestones))]]
       (list [:polyline
              {:fill "none"
               :stroke colour
               :stroke-width 1.5
               :points (str/join
                        " "
                        (map (fn [[px py]]
                               (str (format "%.1f" (double px)) "," (format "%.1f" (double py))))
                             pts))}]
             (for [[px py] pts] [:circle {:cx px :cy py :r 2 :fill colour}])
             [:text {:x (+ lx 6) :y (+ ly 3) :font-size 10 :fill colour} (name m)]))]))

(defn commit-link
  "The commit, linked to the repository when one is known."
  [repo sha]
  (let [short (subs (str sha) 0 (min 7 (count (str sha))))]
    (if repo
      [:a {:href (str "https://github.com/" repo "/commit/" sha)} [:code short]]
      [:code short])))

(defn run-link
  "The run, linked to its Actions page when the repository is known."
  [repo run]
  (if repo [:a {:href (str "https://github.com/" repo "/actions/runs/" run)} (str run)] (str run)))

(defn table-row
  "One table row for a round."
  [repo milestones r]
  (let [[m n] (furthest r milestones)]
    [:tr [:td (subs (str (:round/at r)) 0 (min 16 (count (str (:round/at r)))))]
     [:td (commit-link repo (:round/commit r))] [:td (name (:round/kind r))]
     [:td (run-link repo (:round/run r))] [:td (:round/runs r)]
     [:td (if (pos? (:round/ok r 0)) [:strong (:round/ok r)] "0")]
     [:td (when m (str (name m) " (" n ")"))]
     [:td
      (str/join ", "
                (for [[sig c] (take top-signatures (:round/failures r))]
                  (str (pr-str sig) " " c)))]]))

(defn page
  "The whole page as hiccup: the title, the state (Nether reached or not), a chart per kind, the
   wall of the newest round of each kind, the rounds newest first."
  [rounds milestones repo]
  (let [by-kind (group-by :round/kind rounds)
        won (reduce + (map #(:round/ok % 0) rounds))
        newest (last rounds)]
    [:html {:lang "en"}
     [:head [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "clojurecraft: the race to the Nether"]
      [:style
       "body{font:14px/1.4 system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#222}
               table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:4px 6px;text-align:left;vertical-align:top}
               th{font-weight:600}code{font-size:12px}h2{margin-top:2rem}.muted{color:#777}"]]
     [:body [:h1 "clojurecraft: the race to the Nether"]
      [:p
       (if (pos? won)
         [:strong (str won " run" (when (> won 1) "s") " reached the Nether.")]
         [:strong "No bot has reached the Nether yet."]) " "
       [:span.muted
        (str (count rounds)
             " round"
             (when (not= 1 (count rounds)) "s")
             " since "
             (subs (str (:round/at (first rounds))) 0 10)
             (when newest
               (str "; the newest " (subs (str (:round/at newest)) 0 16)
                    " at " (subs (str (:round/commit newest)) 0 7)))
             ".")]]
      (for [kind [:real :sim]
            :let [rs (get by-kind kind)]
            :when (seq rs)]
        (list [:h2
               (str (if (= kind :real) "Real servers" "Server model")
                    ": the share of runs reaching each milestone, round by round")]
              (chart rs milestones)
              (let [r (last rs)
                    w (wall (:round/failures r) milestones)]
                (list [:h3 (str "The wall, newest round (" (:round/runs r) " runs)")]
                      [:ol
                       (for [[stage total sigs] (take 5 w)]
                         [:li (name stage) ": " total " runs; "
                          (str/join ", "
                                    (for [[sig c] (take 3 sigs)]
                                      (str (name (second sig)) " " c)))])])))) [:h2 "Rounds"]
      [:table
       [:thead
        [:tr [:th "when (UTC)"] [:th "commit"] [:th "kind"] [:th "run"] [:th "runs"]
         [:th "to the Nether"] [:th "furthest"] [:th "failures by signature"]]]
       [:tbody (for [r (reverse rounds)] (table-row repo milestones r))]]
      [:p.muted
       "Built by bb page from rounds.edn beside this file; one round a line. Issue #116."]]]))

(defn html
  "The page as a string."
  [rounds milestones repo]
  (str "<!DOCTYPE html>\n" (h/html (page rounds milestones repo))))

;;;; I/O: the files, git ;;;;

(defn goals
  "The goal table, from the repository's resources."
  []
  (edn/read-string {:default (fn [_ v] v)} (slurp goals-file)))

(defn read-rounds
  "The rounds in a rounds.edn, oldest first."
  [path]
  (if (fs/exists? path)
    (into []
          (keep #(when (seq (str/trim %)) (edn/read-string {:default (fn [_ v] v)} %)))
          (str/split-lines (slurp path)))
    []))

(defn repo
  "The repository, owner/name, when a workflow says it."
  []
  (System/getenv "GITHUB_REPOSITORY"))

(defn build!
  "Write index.html for the rounds in rounds-path into out-dir."
  [rounds-path out-dir]
  (fs/create-dirs out-dir)
  (spit (str (fs/path out-dir "index.html"))
        (html (read-rounds rounds-path) (with-end (goals)) (repo)))
  (println "built"
           (str (fs/path out-dir "index.html"))
           "from"
           (count (read-rounds rounds-path))
           "rounds"))

(defn git!
  "git with args in dir; throws when it fails."
  [dir & args]
  (apply p/shell {:dir (str dir)} "git" args))

(defn publish!
  "Append the round in round-path to rounds.edn on the gh-pages branch, rebuild index.html there,
   commit and push. The branch is fetched into a worktree beside the repository (made orphan when
   it does not exist yet); a push another report won just now is retried on top of it, three times."
  [round-path]
  (let [round (edn/read-string {:default (fn [_ v] v)} (slurp round-path))
        dir (str (fs/path (fs/parent (fs/absolutize ".")) (str "pages-" (:round/run round))))
        fetched (zero? (:exit (p/shell {:continue true :out :string :err :string}
                                       "git" "fetch"
                                       "origin" branch)))]
    (if fetched
      (git! "." "worktree" "add" "--detach" dir (str "origin/" branch))
      (do (git! "." "worktree" "add" "--detach" dir "HEAD")
          (git! dir "checkout" "--orphan" branch)
          (git! dir "rm" "-rfq" ".")))
    (loop [tries 3]
      (when fetched (git! dir "reset" "-q" "--hard" (str "origin/" branch)))
      (let [rounds (str (fs/path dir rounds-file))
            kept (remove #(and (= (:round/run %) (:round/run round))
                               (= (:round/kind %) (:round/kind round)))
                         (read-rounds rounds))]
        (spit rounds (str/join "" (map #(str (pr-str %) "\n") (concat kept [round]))))
        (build! rounds dir)
        (git! dir "add" "-A")
        (git! dir
              "-c" "user.name=github-actions[bot]"
              "-c" "user.email=41898282+github-actions[bot]@users.noreply.github.com"
              "commit" "-q"
              "-m"
              (str "Round " (:round/run round)
                   " (" (name (:round/kind round))
                   ") at " (subs (str (:round/commit round)) 0 7)))
        (let [pushed (zero? (:exit (p/shell {:dir dir :continue true}
                                            "git" "push"
                                            "origin" (str "HEAD:" branch))))]
          (cond pushed (println "published round" (:round/run round) "to" branch)
                (and (pos? (dec tries)) fetched)
                (do (git! "." "fetch" "origin" branch) (recur (dec tries)))
                :else (throw (ex-info "could not push the page" {:branch branch}))))))))

(defn -main
  "round <dir> <kind> <run> <commit> <at>, wall <dir>, build <rounds.edn> <out-dir>, publish <round.edn>."
  [& [cmd a b c d e]]
  (case cmd
    "round" (prn (round (failures/runs-under a) (with-end (goals)) (keyword b) (parse-long c) d e))
    "wall"
    (doseq [[stage total sigs] (wall (mapv (fn [[sig rs]] [sig (count rs)])
                                           (failures/grouped (failures/runs-under a)))
                                     (with-end (goals)))]
      (println (name stage)
               total
               (str/join ", " (for [[sig n] (take 3 sigs)] (str (pr-str sig) " " n)))))
    "build" (build! a b)
    "publish" (publish! a)
    (do
      (println
       "usage: bb page round <dir> <kind> <run> <commit> <at> | wall <dir> | build <rounds.edn> <out-dir> | publish <round.edn>")
      (System/exit 2))))
