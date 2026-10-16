(ns clojurecraft.copilot
  "Copilot cloud agent sessions on the bot-failure issues, kept going until each issue closes
   (copilot.yml, every few minutes and on every issue or check event).

     bb copilot dispatch [--dry-run]

   Each open bot-failure issue is in one of a few states, and each state has one move:
     1. no Copilot pull request names it (Fixes #n) and it is not assigned: assign Copilot with
        the bot-fixer agent (.github/agents/bot-fixer.agent.md), oldest first, while fewer than
        cap issues are assigned. The session it starts opens the pull request;
     2. assigned, no pull request and no session running for start-grace-ms: assign again (the
        session never started or died before its first push);
     3. its pull request has a session running, or checks still pending: wait;
     4. ready (not a draft), `unit / unit` and `check` green: merge. The merge closes the issue
        by its Fixes line. A pull request that changes a guarded path (the workflows, this
        dispatcher) is never merged here: the issue is labelled needs-review for a person;
     5. otherwise (a red check, a draft left by a session that ended, a Refs-only body): ask
        Copilot to go on, in a pull request comment that says what is wrong, at most once every
        nudge-every-ms and max-nudges times; after that the issue is labelled agent-stuck and
        left for a person.
   An issue labelled no-agent or agent-stuck is never touched. A user token (COPILOT_TOKEN) does
   all of it: GitHub assigns Copilot and hears @copilot only from a user."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def repo "BridgerB/clojurecraft")
(def agent-login "copilot-swe-agent[bot]")
(def custom-agent "bot-fixer")                ; .github/agents/bot-fixer.agent.md
(def default-cap 3)                           ; issues assigned at once (COPILOT_CAP): sessions and their checks take runners from the lanes
(def start-grace-ms (* 20 60 1000))           ; an assignment with no pull request and no session this long is retried
(def nudge-every-ms (* 30 60 1000))           ; the least time between two continue comments on one pull request
(def max-nudges 8)                            ; continue comments before the issue is left for a person
(def required-checks #{"unit / unit" "check"}) ; the checks a fix must pass (issue.yml)
(def marker "<!-- copilot-dispatch -->")      ; in every comment this namespace writes, so it can count its own
(def hands-off #{"no-agent" "agent-stuck" "needs-review"}) ; labels that keep an issue away from Copilot
(def guarded
  "Paths a Copilot pull request may not change and still be merged here: the workflows and this
   dispatcher. COPILOT_TOKEN lives in an environment only main can read, so a merged change to
   them is the one way a session's code could reach it; a person merges those."
  [".github/" "bb/clojurecraft/copilot.clj" "bb.edn"])

;; ---------------------------------------------------------------- the state as values

(defn fixes
  "The issue numbers a pull request body says it fixes (Fixes, Closes or Resolves #n)."
  [body]
  (set (map (comp parse-long second)
            (re-seq #"(?i)(?:fixes|closes|resolves) #(\d+)" (or body "")))))

(defn check-state
  "The required checks of a pull request's status rollup as one word: :green when every one
   passed, :red when any failed, :pending otherwise (missing ones count as pending). A name that
   appears twice (test.yml and issue.yml both run `unit / unit`) takes its worst run."
  [rollup]
  (let [word (fn [c]
               (let [w (str/upper-case (str (or (:conclusion c) (:state c) "")))]
                 (cond (#{"FAILURE" "CANCELLED" "TIMED_OUT" "ACTION_REQUIRED" "ERROR"} w) :red
                       (#{"SUCCESS" "SKIPPED" "NEUTRAL"} w) :green
                       :else :pending)))
        by-name (group-by #(or (:name %) (:context %)) rollup)
        states (for [n required-checks
                     :let [ws (set (map word (get by-name n)))]]
                 (cond (ws :red) :red
                       (and (seq ws) (= ws #{:green})) :green
                       :else :pending))]
    (cond (some #{:red} states) :red
          (every? #{:green} states) :green
          :else :pending)))

(defn issue-move
  "The one move for open issue i ({:number :labels :assignees}), given pr (the open Copilot pull
   request that fixes it, {:number :branch :draft? :checks :nudges :last-nudge-ms} or nil), live
   (the branches with a Copilot session queued or running), assigned-ms (when Copilot was last
   assigned to it, or nil) and now: {:move kw ...} as the namespace doc's rules say. Pure."
  [{n :number :keys [labels assignees]} pr live assigned-ms now]
  (let [assigned? (some #{"Copilot" agent-login "copilot-swe-agent"} assignees)]
    (cond
      (some hands-off labels) {:move :leave :issue n :why "hands-off label"}
      (and pr (live (:branch pr))) {:move :wait :issue n :pr (:number pr) :why "session running"}
      (and pr (not (:draft? pr)) (= :green (:checks pr)) (:guarded? pr))
      {:move :review :issue n :pr (:number pr)}
      (and pr (not (:draft? pr)) (= :green (:checks pr))) {:move :merge :issue n :pr (:number pr)}
      (and pr (= :pending (:checks pr)) (not (:draft? pr)))
      {:move :wait :issue n :pr (:number pr) :why "checks running"}
      (and pr (>= (:nudges pr) max-nudges)) {:move :stuck :issue n :pr (:number pr)}
      (and pr (:last-nudge-ms pr) (< (- now (:last-nudge-ms pr)) nudge-every-ms))
      {:move :wait :issue n :pr (:number pr) :why "nudged recently"}
      pr {:move :nudge :issue n :pr (:number pr) :checks (:checks pr) :draft? (:draft? pr)}
      (and assigned? assigned-ms (> (- now assigned-ms) start-grace-ms)) {:move :reassign :issue n}
      assigned? {:move :wait :issue n :why "session starting"}
      :else {:move :assign :issue n})))

(defn moves
  "Every issue's move, oldest issue first, with :assign held back to the cap: assigned counts the
   issues Copilot holds now (assigned or with a pull request), and only cap minus that many
   unassigned issues are given out."
  [issue-moves holding cap]
  (let [room (max 0 (- cap holding))
        assigns (take room (filter #(= :assign (:move %)) issue-moves))
        given (set (map :issue assigns))]
    (vec (for [m issue-moves :when (or (not= :assign (:move m)) (given (:issue m)))] m))))

(defn nudge-text
  "The continue comment for a pull request in move m, saying what is left."
  [{:keys [issue checks draft?]}]
  (str
   "@copilot Go on with #" issue
   " by your bot-fixer profile until it can merge. "
   (cond
     (= :red checks)
     "A required check is red: read its log whole (the run's failed job), fix the cause and push. "
     draft?
     "The pull request is still a draft and no session is working on it: finish the fix, judge the snapshots on main and on your branch, fill in the body, and mark it ready. "
     :else
     "The pull request has no green `unit / unit` and `check` yet: push the fix and let them run. ")
   "Its first line must stay `Fixes #" issue
   "`.\n\n" marker))

;;;; I/O: gh ;;;;

(defn gh
  "gh with args, its stdout; throws when it fails, printing gh's error."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "gh " (str/join " " args) ": " (str/trim (:err r))) {})))
    (:out r)))

(defn gh-json
  "gh with args, its stdout parsed as JSON with keyword keys."
  [& args]
  (json/parse-string (apply gh args) true))

(defn ms "An ISO instant's epoch milliseconds." [s] (.toEpochMilli (java.time.Instant/parse s)))

(defn open-issues
  "The open bot-failure issues, oldest first: [{:number :labels :assignees}]."
  []
  (->> (gh-json "issue" "list"
                "--repo" repo
                "--label" "bot-failure"
                "--state" "open"
                "--limit" "200"
                "--json" "number,labels,assignees")
       (map (fn [i]
              {:number (:number i)
               :labels (map :name (:labels i))
               :assignees (map :login (:assignees i))}))
       (sort-by :number)))

(defn copilot-prs
  "The open pull requests from copilot/* branches, by the issue each fixes:
   {n {:number :branch :draft? :checks :nudges :last-nudge-ms}}."
  []
  (into {}
        (for [pr (gh-json "pr" "list"
                          "--repo" repo
                          "--state" "open"
                          "--limit" "100"
                          "--json"
                          "number,headRefName,isDraft,body,statusCheckRollup,comments,files")
              :when (str/starts-with? (:headRefName pr) "copilot/")
              :let [ours (filter #(str/includes? (str (:body %)) marker) (:comments pr))]
              n (fixes (:body pr))]
          [n
           {:number (:number pr)
            :branch (:headRefName pr)
            :draft? (:isDraft pr)
            :guarded? (boolean (some (fn [{:keys [path]}] (some #(str/starts-with? path %) guarded))
                                     (:files pr)))
            :checks (check-state (:statusCheckRollup pr))
            :nudges (count ours)
            :last-nudge-ms (some->> ours
                                    (map (comp ms :createdAt))
                                    seq
                                    (apply max))}])))

(defn live-branches
  "The branches with a Copilot session queued or running (its runs have event dynamic)."
  []
  (set (for [status ["queued" "in_progress"]
             r (:workflow_runs (gh-json "api"
                                        (str "repos/" repo
                                             "/actions/runs?event=dynamic&per_page=100&status="
                                             status)))]
         (:head_branch r))))

(defn assigned-ms
  "When Copilot was last assigned to issue n, or nil."
  [n]
  (some->> (gh-json "api" (str "repos/" repo "/issues/" n "/events?per_page=100"))
           (filter #(and (= "assigned" (:event %))
                         (str/starts-with? (str (get-in % [:assignee :login])) "copilot")))
           last
           :created_at
           ms))

(defn assign!
  "Assign Copilot to issue n with the bot-fixer agent, which starts a session."
  [n]
  (let [body (json/generate-string {:assignees [agent-login]
                                    :agent_assignment {:target_repo repo
                                                       :base_branch "main"
                                                       :custom_instructions ""
                                                       :custom_agent custom-agent
                                                       :model ""}})]
    (p/shell {:in body :out :string}
             "gh"
             "api" "-X"
             "POST" (str "repos/" repo "/issues/" n "/assignees")
             "--input" "-")))

(defn unassign!
  "Take Copilot off issue n."
  [n]
  (p/shell {:in (json/generate-string {:assignees [agent-login]}) :out :string}
           "gh"
           "api" "-X"
           "DELETE" (str "repos/" repo "/issues/" n "/assignees")
           "--input" "-"))

(defn perform!
  "Carry out move m (printing it); with dry? only print."
  [dry? {:keys [move issue pr why] :as m}]
  (println (str "#" issue)
           (name move)
           (cond pr (str "PR #" pr)
                 :else "")
           (or why ""))
  (when-not dry?
    (case move
      :assign (assign! issue)
      :reassign (do (unassign! issue) (assign! issue))
      :merge (gh "pr" "merge" (str pr) "--repo" repo "--merge")
      :review
      (do (gh "issue" "edit" (str issue) "--repo" repo "--add-label" "needs-review")
          (gh "pr"
              "comment" (str pr)
              "--repo" repo
              "--body"
              (str
               "Green, but it changes a workflow or the dispatcher, which only a person merges.\n\n"
               marker)))
      :nudge (gh "pr" "comment" (str pr) "--repo" repo "--body" (nudge-text m))
      :stuck
      (do (gh "issue" "edit" (str issue) "--repo" repo "--add-label" "agent-stuck")
          (gh "pr"
              "comment" (str pr)
              "--repo" repo
              "--body"
              (str "Left for a person: " max-nudges
                   " continue requests and still not mergeable.\n\n" marker)))
      (:wait :leave) nil)))

(defn dispatch!
  "Look at every open bot-failure issue and make its one move (the namespace doc)."
  [dry?]
  (let [issues (open-issues)
        prs (copilot-prs)
        live (live-branches)
        now (System/currentTimeMillis)
        cap (or (some-> (System/getenv "COPILOT_CAP")
                        parse-long)
                default-cap)
        assigned? (fn [i] (some #(str/starts-with? (str/lower-case %) "copilot") (:assignees i)))
        holding (count (filter #(or (assigned? %) (prs (:number %))) issues))
        each (for [i issues]
               (issue-move i
                           (prs (:number i))
                           live
                           (when (and (assigned? i) (nil? (prs (:number i))))
                             (assigned-ms (:number i)))
                           now))]
    (println (count issues) "open bot-failure issues," holding "held by Copilot, cap" cap)
    (run! #(perform! dry? %) (moves each holding cap))))

(defn -main
  "dispatch [--dry-run]: make every open bot-failure issue's move."
  [& args]
  (case (first args)
    "dispatch" (dispatch! (some #{"--dry-run"} args))
    (println "usage: bb copilot dispatch [--dry-run]")))
