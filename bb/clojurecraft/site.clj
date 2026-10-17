(ns clojurecraft.site
  "The results site (issue #116), built by pages.yml at every deploy from main's code and the
   gh-pages branch's data, so the page is always main's and the branch holds only data
   (clojurecraft.page writes it).

     bb site build <pages-dir> <out-dir>    the site: index.html (the shell, with the road from
                                            goals.edn, ci/race.edn and ci/road.edn in it),
                                            site/app.js and site/style.css, the branch's data/
                                            and orchestrator.json, agents.html sending to the
                                            Agents tab
     bb site gyms                           the gym runs (gym.yml) not yet on the branch, a line
                                            each in data/gyms.ndjson, from their summary artifacts
     bb site agents <out-dir>               data/agents.json in the site: the bot-failure issues
                                            and the pull requests that fix them, from GitHub

   The page is one shell and a script (site/app.js): it loads data/summary.json first (every
   day's totals), a day's file when the history opens it, data/gyms.ndjson for the road's gym
   proof, data/agents.json for the Agents tab, and the orchestrator's state live from the
   branch. A branch still in the one-file layout (rounds.edn, before the first publish of this
   code) is laid out in the site alone (page/changes), so the page never waits for a publish.
   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.page :as page]
            [hiccup2.core :as h]))

(def road-file "ci/road.edn")
(def assets ["site/app.js" "site/style.css"]) ; copied beside index.html as they are
(def gyms-file "data/gyms.ndjson")
(def gyms-per-pass 60)                 ; gym runs a deploy reads at most: the first deploy fills the backlog over a few
(def gym-runs-looked 300)              ; the newest gym.yml runs looked at for ones not yet on the branch

;; ---------------------------------------------------------------- the road

(defn road
  "The road from spawn to the dragon as the page draws it: the race's chain (page/chain to end),
   then road's goal rows the race does not play yet, then its stages with no goal row: each stage
   {:id :phase :says :issue :race :goal} in order (:race when the race plays it, :goal when
   goals.edn has its row), and :end, the race's target."
  [goals end {:road/keys [after unbuilt phases says issues]}]
  (let [chain (page/chain goals end)
        rows (set (map :goal/id goals))
        stage (fn [id race?]
                (cond-> {:id (name id)
                         :phase (name (phases id :wood))
                         :says (says id "")
                         :race race?
                         :goal (contains? rows id)}
                  (issues id) (assoc :issue (issues id))))]
    {:end end
     :stages (vec (concat (for [id chain] (stage id true))
                          (for [id after :when (not-any? #{id} chain)] (stage id false))
                          (for [{:keys [id issue says]} unbuilt]
                            {:id (name id)
                             :phase (name (phases id :end))
                             :says says
                             :race false
                             :goal false
                             :issue issue})))}))

;; ---------------------------------------------------------------- the gyms

(defn tally-of
  "\"a 2, b 1\" (gym/tally) as {:a 2 :b 1}."
  [s]
  (into {} (for [[_ k n] (re-seq #"([\w-]+) (\d+)" (str s))] [(keyword k) (parse-long n)])))

(defn gym-goal
  "One goal's section of a gym summary (from after its ### line): pass k of n with its interval,
   the outcomes and reasons tallied, the seconds of each passing run."
  [goal text]
  (let [[_ k n lo hi] (re-find #"pass (\d+)/(\d+) \[(\d+)%, (\d+)%\]" text)
        rows (for [[_ outcome s] (re-seq #"(?m)^\| \d+ \| [^|]* \| (\w+) \| (\d*) \|" text)]
               [outcome (parse-long (str s))])]
    (cond-> {:goal goal
             :pass (parse-long (str (or k "0")))
             :n (parse-long (str (or n "0")))
             :outcomes (tally-of (second (re-find #"(?m)^outcomes: (.*)$" text)))
             :reasons (tally-of (second (re-find #"(?m)^reasons: (.*)$" text)))
             :pass-s (vec (keep (fn [[o s]] (when (= "pass" o) s)) rows))}
      lo (assoc :lo (parse-long lo) :hi (parse-long hi)))))

(defn gym-summary
  "A gym run's summary.md (gym.yml's report) as a value: its label, landing set and commit from
   the heading, and each goal (gym-goal)."
  [text]
  (let [[_ label set sha] (re-find #"## gym (\S+) on landing set (\S+) at (\S+)" text)]
    {:label label
     :set set
     :sha sha
     :goals (vec (for [[_ goal body] (re-seq #"(?ms)^### (\S+)\s*$(.*?)(?=^### |\z)" text)]
                   (gym-goal goal body)))}))

(defn trial-names
  "The gym rows a run tried, from its trial artifacts' names (gym-<label>-<row>-<n>)."
  [label dirs]
  (vec (sort (distinct (keep #(second (re-matches (re-pattern (str "gym-"
                                                                   (java.util.regex.Pattern/quote
                                                                    (str label))
                                                                   "-(.+)-\\d+"))
                                                  %))
                             dirs)))))

(defn gym-line
  "A gym run as data/gyms.ndjson keeps it: run (gh's run JSON), its summary (gym-summary, or nil
   when the run left none) and the trial artifacts' names."
  [run summary dirs]
  (cond-> {:run (:id run)
           :at (:created_at run)
           :branch (:head_branch run)
           :sha (:head_sha run)
           :conclusion (:conclusion run)
           :goals (:goals summary [])}
    (:label summary)
    (assoc :label (:label summary) :set (:set summary) :rows (trial-names (:label summary) dirs))))

(defn gyms-change
  "The change adding gym lines to data/gyms.ndjson (page/update-branch!): each line once, by run,
   in the order of the runs."
  [lines read]
  (let [kept (page/read-ndjson (read gyms-file))
        known (set (map :run kept))]
    {gyms-file (page/ndjson (sort-by :run (concat kept (remove (comp known :run) lines))))}))

;; ---------------------------------------------------------------- the agents

(defn signature-of
  "[stage reason] from a bot-failure issue's title (\"Bot stops at fortress: no-pickaxe\"), or nil."
  [title]
  (some-> (re-find #"(?i)stops at ([\w-]+): ([\w-]+)" (str title))
          rest
          vec))

(defn by-whom
  "Who wrote a pull request: copilot (Copilot's cloud agent, copilot/* branches), fixer (a fix/*
   branch: a person or a Claude session, AGENTS.md section 3) or person."
  [{:keys [headRefName author]}]
  (cond (or (str/starts-with? (str headRefName) "copilot/")
            (str/includes? (str/lower-case (str (:login author))) "copilot"))
        "copilot"
        (str/starts-with? (str headRefName) "fix/") "fixer"
        :else "person"))

(defn agents
  "The Agents tab's history from GitHub's lists (gh issue list and gh pr list JSON): every
   bot-failure issue with its signature, state, why it closed and when; every pull request that
   closes one, or that Copilot opened, with who wrote it, its state and when it opened, merged or
   closed. now is when it was asked."
  [issues prs now]
  (let [failure? (set (map :number issues))]
    {:updated now
     :issues (vec (for [{:keys [number title state stateReason createdAt closedAt labels]} issues
                        :let [[stage reason] (signature-of title)]]
                    (cond-> {:n number
                             :title title
                             :state (str/lower-case (str state))
                             :opened createdAt
                             :labels (mapv :name labels)}
                      stage (assoc :stage stage :reason reason)
                      closedAt (assoc :closed closedAt :why (str/lower-case (str stateReason))))))
     :prs (vec (for [pr prs
                     :let [fixes (filterv failure? (map :number (:closingIssuesReferences pr)))
                           who (by-whom pr)]
                     :when (or (seq fixes) (= "copilot" who))]
                 (cond-> {:n (:number pr)
                          :title (:title pr)
                          :by who
                          :branch (:headRefName pr)
                          :state (str/lower-case (str (:state pr)))
                          :draft (boolean (:isDraft pr))
                          :opened (:createdAt pr)
                          :fixes fixes}
                   (:mergedAt pr) (assoc :merged (:mergedAt pr))
                   (:closedAt pr) (assoc :closed (:closedAt pr)))))}))

;; ---------------------------------------------------------------- the shell

(defn json-in-html
  "A value as JSON safe inside a <script> element: no </ can end it early."
  [x]
  (str/replace (json/generate-string x) "</" "<\\/"))

(defn shell
  "The page as hiccup: the header, the four tabs' places (the script fills them), the road as
   JSON for the script, the script. version (the commit) is asked of the script and the style, so
   a deploy is never drawn by a browser's cached copy of the last."
  [road repo version]
  [:html {:lang "en"}
   [:head [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "color-scheme" :content "light dark"}]
    [:title "clojurecraft: spawn to the dragon"]
    [:meta
     {:name "description"
      :content
      "How far an autonomous Clojure Minecraft bot gets from spawn toward the Ender Dragon, round after round."}]
    [:link
     {:rel "icon"
      :href
      "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Crect width='16' height='16' rx='3' fill='%236250d6'/%3E%3Crect x='4' y='4' width='8' height='8' fill='%23e87ba4'/%3E%3C/svg%3E"}]
    [:link {:rel "stylesheet" :href (str "style.css?v=" version)}]]
   [:body {:data-repo repo}
    [:header.top
     [:div.brand [:h1 "clojurecraft"]
      [:p.sub
       "From spawn to a dead Ender Dragon with no human input: how far the bots get, what stops them, and who is fixing it."]]
     [:div.top-right [:div#live.live "Lanes: …"]
      [:button#theme.ghost {:type "button" :aria-label "Switch light or dark"} "◐"]]]
    [:nav.tabs {:role "tablist"}
     (for [[id label] [["road" "Road"] ["trends" "Trends"] ["history" "History"]
                       ["agents" "Agents"]]]
       [:a {:href (str "#" id) :data-tab id :role "tab"} label])]
    [:main
     (for [id ["road" "trends" "history" "agents"]]
       [:section {:id (str "tab-" id) :class "tab" :hidden true} [:p.loading "Loading…"]])]
    [:footer
     [:p "Data: " [:a {:href "data/summary.json"} "data/summary.json"] " (every day's totals), "
      [:code "data/days/<day>.json"] " (a day's runs), " [:code "data/batches/<day>.ndjson"]
      " (every batch, whole), " [:a {:href "data/gyms.ndjson"} "data/gyms.ndjson"] ", "
      [:a {:href "data/agents.json"} "data/agents.json"] ". Days are UTC; times are shown in "
      [:span#tz "your zone"] ". Built by " [:code "bb site"] " (issue #116)."]]
    [:div#tip.tip {:role "tooltip" :hidden true}]
    [:script#road {:type "application/json"} (h/raw (json-in-html road))]
    [:script {:src (str "app.js?v=" version) :defer true}]]])

(defn redirect
  "agents.html as hiccup: the old Agents page's address, sent to the tab."
  []
  [:html {:lang "en"}
   [:head [:meta {:charset "utf-8"}] [:meta {:http-equiv "refresh" :content "0; url=./#agents"}]
    [:title "clojurecraft: agents"]] [:body [:a {:href "./#agents"} "The Agents tab"]]])

(defn html "Hiccup as an HTML document." [x] (str "<!DOCTYPE html>\n" (h/html x)))

;;;; I/O: the files, gh ;;;;

(defn read-road
  "The road for this checkout: goals.edn, ci/race.edn's end and ci/road.edn."
  []
  (road (page/goals) (page/race-end) (edn/read-string (slurp road-file))))

(defn legacy!
  "When the branch at pages-dir is still in the one-file layout (rounds.edn, no summary), lay it
   out under out-dir as a publish would (page/changes), without touching the branch."
  [pages-dir out-dir]
  (let [legacy (fs/file pages-dir page/legacy-file)]
    (when (and (fs/exists? legacy) (not (fs/exists? (fs/file pages-dir page/summary-file))))
      (println "laying out" page/legacy-file "for the site")
      (page/apply-files! out-dir
                         (page/publish-change (str (java.time.Instant/now))
                                              []
                                              #(let [f (fs/file pages-dir %)]
                                                 (when (fs/exists? f) (slurp f))))))))

(defn version
  "This checkout's commit, short: the workflow's, else git's, else dev."
  []
  (let [v (str (or (System/getenv "GITHUB_SHA")
                   (not-empty (str/trim (:out (page/quiet! {} "git" "rev-parse" "HEAD"))))
                   "dev"))]
    (subs v 0 (min 7 (count v)))))

(defn build!
  "The site under out-dir from pages-dir (a checkout of gh-pages) and this checkout's code."
  [pages-dir out-dir]
  (fs/create-dirs out-dir)
  (when (fs/exists? (fs/file pages-dir "data"))
    (fs/copy-tree (fs/file pages-dir "data") (fs/file out-dir "data") {:replace-existing true}))
  (legacy! pages-dir out-dir)
  (when (fs/exists? (fs/file pages-dir "orchestrator.json"))
    (fs/copy (fs/file pages-dir "orchestrator.json")
             (fs/file out-dir "orchestrator.json")
             {:replace-existing true}))
  (doseq [a assets] (fs/copy a (fs/file out-dir (fs/file-name a)) {:replace-existing true}))
  (spit (str (fs/file out-dir "index.html")) (html (shell (read-road) (page/repo) (version))))
  (spit (str (fs/file out-dir "agents.html")) (html (redirect)))
  (println "built" (str out-dir)))

(defn gh-json
  "gh with args, its stdout as JSON with keyword keys; nil when gh fails (printed)."
  [& args]
  (let [r (apply page/quiet! {} "gh" args)]
    (cond (zero? (:exit r)) (json/parse-string (:out r) true)
          :else (do (println "gh" (first args) "failed:" (str/trim (:err r))) nil))))

(defn gym-runs
  "The newest completed gym.yml runs (gym-runs-looked at most), oldest first."
  []
  (->> (range 1 (inc (quot gym-runs-looked 100)))
       (mapcat #(:workflow_runs
                 (gh-json "api"
                          (str "repos/" (page/repo)
                               "/actions/workflows/gym.yml/runs?status=completed&per_page=100&page="
                               %))))
       (sort-by :id)))

(defn gym-run!
  "A gym run's line (gym-line): its summary artifact downloaded under tmp and read; a run with no
   summary artifact still gets a line, with no goals, so it is not asked for again."
  [run tmp]
  (let [art (some #(when (re-matches #"gym-.*-summary" (:name %)) %)
                  (:artifacts (gh-json
                               "api"
                               (str "repos/" (page/repo) "/actions/runs/" (:id run) "/artifacts"))))
        dir (fs/file tmp (str (:id run)))]
    (cond (or (nil? art) (:expired art)) (gym-line run nil [])
          :else
          (do (page/quiet! {}
                           "gh" "run"
                           "download" (str (:id run))
                           "--repo" (page/repo)
                           "-n" (:name art)
                           "-D" (str dir))
              (let [md (fs/file dir "summary.md")
                    arts (fs/file dir "artifacts")]
                (gym-line run
                          (when (fs/exists? md) (gym-summary (slurp md)))
                          (when (fs/exists? arts) (map fs/file-name (fs/list-dir arts)))))))))

(defn gyms!
  "Add the gym runs not yet in data/gyms.ndjson on the branch, gyms-per-pass at most, oldest
   first (page/update-branch!)."
  []
  (page/fetch!)
  (let [known
        (set (map :run
                  (page/read-ndjson
                   (:out (page/quiet! {} "git" "show" (str "origin/" page/branch ":" gyms-file))))))
        todo (take gyms-per-pass (remove (comp known :id) (gym-runs)))
        tmp (fs/create-temp-dir)]
    (println (count todo) "gym runs to add")
    (when (seq todo)
      (let [lines (mapv #(gym-run! % tmp) todo)]
        (println
         (page/update-branch! "gyms" (str "Gym runs: " (count lines)) #(gyms-change lines %)))))))

(defn agents!
  "Write data/agents.json under out-dir from GitHub's issue and pull request lists."
  [out-dir]
  (let
    [issues (gh-json "issue" "list"
                     "--repo" (page/repo)
                     "--label" "bot-failure"
                     "--state" "all"
                     "--limit" "2000"
                     "--json" "number,title,state,stateReason,createdAt,closedAt,labels")
     prs
     (gh-json
      "pr" "list"
      "--repo" (page/repo)
      "--state" "all"
      "--limit" "2000"
      "--json"
      "number,title,headRefName,author,state,isDraft,createdAt,closedAt,mergedAt,closingIssuesReferences")]
    (when (and issues prs)
      (fs/create-dirs (fs/file out-dir "data"))
      (spit (str (fs/file out-dir "data" "agents.json"))
            (json/generate-string (agents issues prs (str (java.time.Instant/now)))))
      (println "agents:" (count issues) "issues," (count prs) "pull requests"))))

(defn -main
  "build <pages-dir> <out-dir>, gyms, agents <out-dir>."
  [& [cmd a b]]
  (case cmd
    "build" (build! a b)
    "gyms" (gyms!)
    "agents" (agents! a)
    (do (println "usage: bb site build <pages-dir> <out-dir> | gyms | agents <out-dir>")
        (System/exit 2))))
