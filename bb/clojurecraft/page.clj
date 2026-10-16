(ns clojurecraft.page
  "The results page (issue #116): one static page that says how close the bots are to the Nether,
   which stage stops them, how fast each stage goes and how that changes round by round, built
   from the rounds' own facts and served by GitHub Pages from the gh-pages branch.

     bb page round <dir> <kind> <run> <commit> <at>    the round's facts from the runs under dir
                                                       (what bb failures reads, and the race
                                                       logs' MILESTONE lines), as one EDN map
     bb page wall <dir>                                the round's failures ranked by how far the
                                                       bot got, then how often (the job summary)
     bb page build <rounds.edn> <out-dir>              index.html from the rounds, one map a line
     bb page publish <round.edn>                       the round appended to rounds.edn on
                                                       gh-pages, the page rebuilt, pushed

   A round is a map: the run, its kind (:real or :sim), the commit, when it started and ended, how
   many runs, how many won, how many reached each milestone, how fast (fastest and median seconds
   from the bot going active to each milestone: the race's MILESTONE facts, a sim run's
   :plan/reached-after), the furthest bot's splits, the failures by signature and the issue each
   is filed as. Every instant is an ISO-8601 string in UTC; durations are seconds. rounds.edn
   holds one such map a line, oldest first, so the page can always be
   rebuilt from the data and the data read without the page. The chain is goals.edn's targets in
   priority order up to the race's target (ci/race.edn's :race/until: the fortress since issue
   #261), both read from their files, so the page and the bot agree on what a milestone is.

   The page borrows from steve's and ruststeve's dashboards what serves a static page: a numbered
   ladder with a tick per milestone ever reached and split times, the funnel of how many get how
   far, the wall with its reasons, a strip of rounds per milestone, the leader's splits. Times are
   ISO instants in <time> elements and a few lines of script show them in the viewer's own zone;
   the live line asks GitHub's public API what is running now. Babashka runs this (hiccup2 and
   cheshire are bundled); nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]
            [hiccup2.core :as h]))

(def branch "gh-pages")                ; the page's branch: index.html and rounds.edn, nothing else
(def rounds-file "rounds.edn")
(def goals-file "resources/clojurecraft/goals.edn")
(def race-file "ci/race.edn")          ; the race's settings: its :race/until is where the chain ends
(def strip-rounds 30)                  ; rounds shown in each milestone's strip, newest last
(def table-rounds 40)                  ; rounds listed in the table, newest first

(def phases
  "The phase each milestone belongs to, as steve colours its ladder: wood, stone, iron, portal."
  {:wood :wood
   :kit :wood
   :pickaxe :wood
   :cobblestone :stone
   :stone-pickaxe :stone
   :furnace :stone
   :ingots :iron
   :bucket :iron
   :water-bucket :iron
   :flint-and-steel :iron
   :iron-kit :iron
   :portal-kit :portal
   :cast-one :portal
   :portal :portal
   :nether :nether
   :fortress :nether})

;; ---------------------------------------------------------------- the chain

(defn chain
  "The milestones in order, from the goal table: every target with a --until name that is not an
   escape or an aside, by priority, up to and with the one whose until is end (the race's
   :race/until, race-end)."
  [goals end]
  (let [targets (->> goals
                     (filter #(and (:goal/target? %)
                                   (:goal/until %)
                                   (not (:goal/always? %))
                                   (not= :wade (:goal/plan %))))
                     (sort-by :goal/priority))
        [before from] (split-with #(not= end (:goal/until %)) targets)]
    (mapv :goal/id (concat before (take 1 from)))))

;; ---------------------------------------------------------------- a round from its runs

(defn median
  "The median of xs, or nil when there are none."
  [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (cond (zero? n) nil
          (odd? n) (v (quot n 2))
          :else (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0))))

(defn mark-of-line
  "The milestone a race log's MILESTONE line carries, or nil."
  [line]
  (when (str/starts-with? line "MILESTONE ")
    (try (edn/read-string {:default (fn [_ v] v)} (subs line 10)) (catch Exception _ nil))))

(defn reached
  "How many runs reached each milestone: a run's RESULT carries :plan/reached."
  [runs milestones]
  (into {}
        (for [m milestones]
          [m (count (filter #(contains? (set (get-in % [:plan :plan/reached])) m) runs))])))

(defn times
  "Each milestone's times in seconds from the bot going active: the race's MILESTONE facts
   (:milestone/after-s), else the runs' :plan/reached-after (ms). {milestone [s ...]}."
  [runs marks]
  (if (seq marks)
    (reduce (fn [m {:milestone/keys [goal after-s]}] (update m goal (fnil conj []) after-s))
            {}
            marks)
    (reduce (fn [m r]
              (reduce (fn [m [g ms]] (update m g (fnil conj []) (quot ms 1000)))
                      m
                      (get-in r [:plan :plan/reached-after])))
            {}
            runs)))

(defn speed
  "{milestone {:n :fastest-s :median-s}} from the times."
  [ts]
  (into {} (for [[g xs] ts] [g {:n (count xs) :fastest-s (apply min xs) :median-s (median xs)}])))

(defn first-at
  "When each milestone was first reached in the round, as an ISO instant (the race's MILESTONE
   facts carry the wall clock; a sim run has none): {milestone iso}."
  [marks]
  (into {}
        (for [[g ms] (group-by :milestone/goal marks)
              :let [at (apply min (keep :milestone/at ms))]]
          [g (str (java.time.Instant/ofEpochMilli at))])))

(defn leader
  "The run that got furthest (most milestones, then the earliest last one), with its splits:
   {:bot name :splits {milestone s}}, or nil when none reached any."
  [runs marks milestones]
  (let [place (zipmap milestones (range))
        rank (fn [[_ splits]] [(- (count splits)) (apply max 0 (vals splits))])
        splits (if (seq marks)
                 (reduce (fn [m {:milestone/keys [runner server bot goal after-s]}]
                           (assoc-in m
                            [(str bot " (runner " runner (when server (str "/" server)) ")") goal]
                            after-s))
                         {}
                         marks)
                 (into {}
                       (keep-indexed
                        (fn [i r]
                          (when-let [ra (not-empty (get-in r [:plan :plan/reached-after]))]
                            [(str "run "
                                  (or (:run/seed r) i)
                                  (when-let [l (get-in r [:run/world :capture/landing])]
                                    (str " at " (pr-str l)))) (update-vals ra #(quot % 1000))])))
                       runs))]
    (when-let [[bot s] (first (sort-by rank (filter (fn [[_ s]] (some place (keys s))) splits)))]
      {:bot bot :splits s})))

(defn round
  "The round as a value (see the namespace): runs and marks are what the report job downloaded,
   the rest is the run's own facts."
  [runs marks milestones {:keys [kind run commit at started ended issues runner batch]}]
  (cond-> {:round/run run
           :round/kind kind
           :round/commit commit
           :round/at at
           :round/runs (count runs)
           :round/ok (count (filter :ok runs))
           :round/reached (reached runs milestones)
           :round/speed (speed (times runs marks))
           :round/failures (mapv (fn [[sig rs]] [sig (count rs)]) (failures/grouped runs))}
    started (assoc :round/started started)
    ended (assoc :round/ended ended)
    runner (assoc :round/runner runner)
    batch (assoc :round/batch batch)
    (seq marks) (assoc :round/first-at (first-at marks))
    (seq issues) (assoc :round/issues issues)
    (leader runs marks milestones) (assoc :round/leader (leader runs marks milestones))))

(defn best-leader
  "Of leaders, the one that got furthest: most milestones, then the earliest last one."
  [leaders]
  (first (sort-by (fn [{:keys [splits]}] [(- (count splits)) (reduce max 0 (vals splits))])
                  leaders)))

(defn combine
  "One row from the batches of a run and kind (a churn job reports each runner's finished bots
   every 20 minutes, issue #127): counts summed, failures summed by signature (most first),
   each milestone's fastest the least and its median the batches' medians weighted by their runs,
   the earliest start and first reach, the latest end, the furthest leader. One batch is itself."
  [batches]
  (let [speeds (keep :round/speed batches)
        goals (distinct (mapcat keys speeds))
        some-of (fn [k] (some k batches))]
    (cond-> {:round/run (some-of :round/run)
             :round/kind (some-of :round/kind)
             :round/commit (some-of :round/commit)
             :round/at (some-of :round/at)
             :round/runs (reduce + (keep :round/runs batches))
             :round/ok (reduce + (keep :round/ok batches))
             :round/reached (apply merge-with + (keep :round/reached batches))
             :round/speed (into {}
                                (for [g goals
                                      :let [xs (keep #(get % g) speeds)
                                            n (reduce + (map :n xs))]
                                      :when (pos? n)]
                                  [g
                                   {:n n
                                    :fastest-s (apply min (map :fastest-s xs))
                                    :median-s (/ (reduce + (map #(* (:n %) (:median-s %)) xs))
                                                 (double n))}]))
             :round/failures (->> (mapcat :round/failures batches)
                                  (reduce (fn [m [sig c]] (update m sig (fnil + 0) c)) {})
                                  (sort-by (comp - val))
                                  (mapv vec))}
      (seq (keep :round/started batches))
      (assoc :round/started (first (sort (keep :round/started batches))))
      (seq (keep :round/ended batches))
      (assoc :round/ended (last (sort (keep :round/ended batches))))
      (seq (keep :round/first-at batches))
      (assoc :round/first-at
             (apply merge-with #(first (sort [%1 %2])) (keep :round/first-at batches)))
      (seq (keep :round/issues batches))
      (assoc :round/issues (apply merge (keep :round/issues batches)))
      (seq (keep :round/leader batches))
      (assoc :round/leader (best-leader (keep :round/leader batches))))))

(defn rows
  "The page's rows from rounds.edn's entries, oldest first: the batches of each run and kind
   combined into one row, in the order each run first appears."
  [entries]
  (let [groups (group-by (juxt :round/run :round/kind) entries)]
    (mapv #(combine (groups %)) (distinct (map (juxt :round/run :round/kind) entries)))))

(defn wall
  "The failures by the stage the bot was on, the furthest stage first: [[stage total
   [[signature count] ...]] ...]."
  [failures milestones]
  (let [place (zipmap milestones (range))]
    (->> (group-by (comp first first) failures)
         (map (fn [[stage sigs]] [stage (reduce + (map second sigs))
                                  (vec (sort-by (comp - second) sigs))]))
         (sort-by (fn [[stage total]] [(- (place stage -1)) (- total)]))
         vec)))

;; ---------------------------------------------------------------- what the rounds say

(defn instant
  "An ISO instant in UTC for a timestamp string with any offset, or the string unchanged."
  [s]
  (try (str (.toInstant (java.time.OffsetDateTime/parse (str s))))
       (catch Exception _
         (some-> s
                 str))))

(defn when-of
  "When a round started, else when its commit was made."
  [r]
  (instant (or (:round/started r) (:round/at r))))

(defn share
  "The share of a round's runs that reached milestone m, 0.0-1.0."
  [r m]
  (if (pos? (:round/runs r 0)) (/ (get-in r [:round/reached m] 0) (double (:round/runs r))) 0.0))

(defn furthest
  "The furthest milestone a round's runs reached and how many did: [m n], or nil."
  [r milestones]
  (last (for [m milestones :let [n (get-in r [:round/reached m] 0)] :when (pos? n)] [m n])))

(defn ever
  "Per milestone across all rounds: {m {:first round-started-iso :fastest-s s :rounds n}} for every
   milestone any run ever reached."
  [rounds]
  (reduce (fn [acc r]
            (reduce (fn [acc [m n]]
                      (if (pos? n)
                        (-> acc
                            (update-in [m :first]
                                       #(or % (get-in r [:round/first-at m]) (when-of r)))
                            (update-in [m :rounds] (fnil inc 0))
                            (update-in [m :fastest-s]
                                       (fn [f]
                                         (let [s (get-in r [:round/speed m :fastest-s])]
                                           (cond (nil? s) f
                                                 (nil? f) s
                                                 :else (min f s))))))
                        acc))
                    acc
                    (:round/reached r)))
          {}
          rounds))

(defn clock
  "Seconds as m:ss, or h:mm:ss from an hour."
  [s]
  (when s
    (let [s (long (math/round (double s)))
          h (quot s 3600)
          m (quot (mod s 3600) 60)
          sec (mod s 60)]
      (if (pos? h) (format "%d:%02d:%02d" h m sec) (format "%d:%02d" m sec)))))

(defn span
  "A duration between two ISO instants, as clock, or nil."
  [a b]
  (try (clock (/ (- (.toEpochMilli (java.time.Instant/parse b))
                    (.toEpochMilli (java.time.Instant/parse a)))
                 1000.0))
       (catch Exception _ nil)))

;; ---------------------------------------------------------------- the page

(defn t
  "A <time> the script shows in the viewer's zone; style :ago shows how long ago."
  ([iso] (t iso nil))
  ([iso style]
   (when iso [:time (cond-> {:datetime iso} style (assoc :data-style (name style))) iso])))

(defn pct "A share as a whole percent." [x] (str (math/round (* 100.0 (double x))) "%"))

(defn bar
  "A horizontal bar of a share, its colour the phase's."
  [x phase]
  [:span.bar
   [:span
    {:class (str "fill " (name phase))
     :style (str "width:" (format "%.1f" (* 100.0 (double x))) "%")}]])

(defn sig-label "A signature in words." [[stage reason]] (str (name stage) ": " (name reason)))

(defn issue-link
  "A signature linked to its issue, when the round knew it."
  [repo r sig]
  (if-let [n (get (:round/issues r) (pr-str sig))]
    [:a {:href (str "https://github.com/" repo "/issues/" n)} (sig-label sig) " #" n]
    (sig-label sig)))

(defn latest
  "The newest round of a kind, or nil."
  [rounds kind]
  (last (filter #(= kind (:round/kind %)) rounds)))

(defn ladder
  "The road to the Nether: one row a milestone, numbered, its phase, ticked when any run ever
   reached it, the latest real and sim round's funnel (share and count, and the drop from the
   step before), the fastest time ever, the latest median and the median stage time (the gap
   between this milestone's median and the one before), when it was first reached; the first
   milestone never reached is the wall."
  [rounds milestones]
  (let [ev (ever rounds)
        real (latest rounds :real)
        sim (latest rounds :sim)
        wall-m (first (remove ev milestones))
        cell (fn [r m prev]
               (if r
                 (let [n (get-in r [:round/reached m] 0)
                       before (if prev (get-in r [:round/reached prev] 0) (:round/runs r))]
                   [:td.funnel (bar (share r m) (phases m :wood))
                    [:span.num (str n "/" (:round/runs r))]
                    (when (and (pos? before) (>= (/ (- before n) (double before)) 0.005))
                      [:span.drop (str "−" (pct (/ (- before n) (double before))))])])
                 [:td.muted "—"]))]
    [:table.ladder
     [:thead
      [:tr [:th "#"] [:th "milestone"] [:th "real servers, newest round"]
       [:th "server model, newest round"] [:th "fastest ever"] [:th "median (newest)"]
       [:th "stage takes"] [:th "first reached"]]]
     [:tbody
      (for [[i m] (map-indexed vector milestones)
            :let [prev (when (pos? i) (milestones (dec i)))
                  e (ev m)
                  med (fn [mm]
                        (or (get-in real [:round/speed mm :median-s])
                            (get-in sim [:round/speed mm :median-s])))
                  stage (when (and prev (med m) (med prev)) (- (med m) (med prev)))]]
        [:tr {:class (str (if e "done" "todo") (when (= m wall-m) " wall"))}
         [:td.n (format "%02d" (inc i))]
         [:td [:span.tick (if e "✓" (if (= m wall-m) "✗" "·"))] " "
          [:span {:class (str "pill " (name (phases m :wood)))} (name (phases m :wood))] " "
          [:b (name m)] (when (= m wall-m) [:span.wallmark " the wall"])] (cell real m prev)
         (cell sim m prev) [:td.time (or (clock (:fastest-s e)) "—")]
         [:td.time (or (clock (med m)) "—")]
         [:td.time (if stage (str "+" (clock (max 0 stage))) "—")]
         [:td (if e (t (:first e)) [:span.muted "never"])]])]]))

(defn stops
  "Where a round's runs stopped: the stages furthest first, each with its count and its top
   reasons linked to their issues."
  [repo r milestones]
  (let [w (wall (:round/failures r) milestones)
        top (reduce max 1 (map second w))]
    [:div.stops
     (for [[stage total sigs] (take 8 w)]
       [:div.stop
        [:div.stophead [:b (name stage)] [:span.num (str total " run" (when (not= 1 total) "s"))]]
        (bar (/ total (double top)) (phases stage :wood))
        [:ul
         (for [[sig c] (take 3 sigs)] [:li (issue-link repo r sig) [:span.num (str " " c)]])]])]))

(defn strip
  "Each milestone's share round by round, oldest to newest, as a row of squares (ruststeve's
   last-40 strip): the darker, the more runs got there."
  [rounds milestones]
  (let [rs (vec (take-last strip-rounds rounds))]
    [:table.strip
     [:thead
      [:tr [:th "milestone"]
       (for [r rs]
         [:th {:title (str (name (:round/kind r)) " " (:round/run r))}
          (if (= :real (:round/kind r)) "R" "S")])]]
     [:tbody
      (for [m milestones]
        [:tr [:td (name m)]
         (for [r rs
               :let [s (share r m)
                     n (get-in r [:round/reached m] 0)]]
           [:td
            {:class (str "sq" (when (zero? n) " zero"))
             :style (when (pos? n)
                      (str "background:hsl(140 55% " (format "%.0f" (- 88 (* 55 s))) "%)"))
             :title (str (name m)
                         ": " n
                         "/" (:round/runs r)
                         " (" (pct s)
                         "), " (name (:round/kind r))
                         " round " (:round/run r))}])])]]))

(defn leader-card
  "The furthest bot of the newest round of a kind: its splits, steve's ladder for one bot."
  [r milestones label]
  (when-let [{:keys [bot splits]} (:round/leader r)]
    [:div.card [:h3 (str "Furthest " label ": ") [:span.mono bot]]
     [:ol.splits
      (for [m milestones
            :let [s (get splits m)]]
        [:li {:class (if s "done" "todo")} [:span.tick (if s "✓" "·")] [:span (name m)]
         [:span.time (or (clock s) "")]])]]))

(defn table-row
  "One row of the rounds table."
  [repo milestones r]
  (let [[m n] (furthest r milestones)]
    [:tr [:td (t (when-of r))] [:td.time (or (span (:round/started r) (:round/ended r)) "—")]
     [:td (if (= :real (:round/kind r)) "real" "sim")]
     [:td
      [:a {:href (str "https://github.com/" repo "/commit/" (:round/commit r))}
       [:code (subs (str (:round/commit r)) 0 7)]]]
     [:td [:a {:href (str "https://github.com/" repo "/actions/runs/" (:round/run r))} "run"]]
     [:td.num (:round/runs r)] [:td.num (if (pos? (:round/ok r 0)) [:b.win (:round/ok r)] "0")]
     [:td (when m (str (name m) " (" n ")"))]
     [:td.reasons
      (interpose ", "
       (for [[sig c] (take 3 (:round/failures r))] (list (issue-link repo r sig) (str " " c))))]]))

(def style
  ":root{--bg:#fff;--fg:#1b1f24;--muted:#6b7280;--line:#e5e7eb;--card:#f8fafc;--wood:#a16207;--stone:#6b7280;--iron:#b45309;--portal:#7c3aed;--nether:#dc2626;--ok:#15803d}
   @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8b949e;--line:#30363d;--card:#161b22}}
   *{box-sizing:border-box}body{background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,-apple-system,sans-serif;max-width:1180px;margin:0 auto;padding:1.25rem}
   h1{font-size:1.5rem;margin:.2rem 0}h2{font-size:1.1rem;margin:2rem 0 .6rem}h3{font-size:.95rem;margin:.2rem 0 .5rem}
   a{color:inherit}.muted{color:var(--muted)}.mono,code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
   .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:.75rem}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:.75rem .9rem}
   .big{font-size:1.6rem;font-weight:700}.label{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
   .live{display:flex;gap:1.5rem;flex-wrap:wrap;margin:.4rem 0 1rem}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:var(--muted);margin-right:6px}.dot.on{background:var(--ok);box-shadow:0 0 6px var(--ok)}
   table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid var(--line);padding:5px 7px;text-align:left;vertical-align:middle}th{font-size:12px;color:var(--muted);font-weight:600}
   .n{color:var(--muted);font-family:ui-monospace,monospace}.tick{display:inline-block;width:1em;text-align:center}.done .tick{color:var(--ok);font-weight:700}.wall{background:color-mix(in srgb,var(--nether) 9%,transparent)}.wall .tick{color:var(--nether)}.wallmark{color:var(--nether);font-weight:600;font-size:12px}
   .pill{font-size:10px;text-transform:uppercase;padding:1px 6px;border-radius:999px;color:#fff;letter-spacing:.04em}.pill.wood{background:var(--wood)}.pill.stone{background:var(--stone)}.pill.iron{background:var(--iron)}.pill.portal{background:var(--portal)}.pill.nether{background:var(--nether)}
   .bar{display:inline-block;width:110px;height:9px;background:var(--line);border-radius:5px;overflow:hidden;vertical-align:middle;margin-right:6px}.fill{display:block;height:100%}.fill.wood{background:var(--wood)}.fill.stone{background:var(--stone)}.fill.iron{background:var(--iron)}.fill.portal{background:var(--portal)}.fill.nether{background:var(--nether)}
   .num{font-variant-numeric:tabular-nums}.funnel .num{font-size:12px}.drop{color:var(--nether);font-size:11px;margin-left:6px}.time{font-variant-numeric:tabular-nums;white-space:nowrap}
   .stops{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:.75rem}.stop{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:.6rem .8rem}.stophead{display:flex;justify-content:space-between}.stop .bar{width:100%}.stop ul{margin:.4rem 0 0;padding-left:1.1rem;font-size:13px}
   .strip{width:auto}.strip td,.strip th{padding:0;border:0}.strip td:first-child{padding-right:8px;font-size:12px;white-space:nowrap}.strip th{font-size:9px;text-align:center;width:16px}.sq{width:16px;height:14px;border:1px solid var(--bg)}.sq.zero{background:var(--line)}
   .splits{list-style:none;padding:0;margin:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));column-gap:2rem;font-size:13px}.splits li{display:grid;grid-template-columns:1.2em 1fr auto;padding:1px 0}.splits .todo{color:var(--muted)}
   .reasons{font-size:12px}.win{color:var(--ok)}.scroll{overflow-x:auto}footer{margin:2rem 0 1rem;color:var(--muted);font-size:12px}")

(def script
  "Times in the viewer's zone; how long ago; and what GitHub says is running now."
  "(function(){
  var fmt=new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeStyle:'short'});
  var rel=new Intl.RelativeTimeFormat(undefined,{numeric:'auto'});
  function ago(d){var s=(d-Date.now())/1000,a=Math.abs(s);
    if(a<90)return rel.format(Math.round(s),'second');if(a<5400)return rel.format(Math.round(s/60),'minute');
    if(a<172800)return rel.format(Math.round(s/3600),'hour');return rel.format(Math.round(s/86400),'day');}
  function dur(ms){var m=Math.floor(ms/60000),h=Math.floor(m/60);return h?h+' h '+(m%60)+' min':m+' min';}
  function show(root){root.querySelectorAll('time[datetime]').forEach(function(el){var d=new Date(el.getAttribute('datetime'));if(isNaN(d))return;
    el.textContent=el.dataset.style==='ago'?ago(d):fmt.format(d);el.title=d.toString();});}
  show(document);
  var tz=document.getElementById('tz');if(tz)tz.textContent=Intl.DateTimeFormat().resolvedOptions().timeZone;
  var repo=document.body.dataset.repo;if(!repo)return;
  [['race.yml','live-real','Real servers'],['simrace.yml','live-sim','Server model']].forEach(function(w){
    var el=document.getElementById(w[1]);if(!el)return;
    fetch('https://api.github.com/repos/'+repo+'/actions/workflows/'+w[0]+'/runs?branch=main&per_page=1').then(function(r){return r.json();}).then(function(j){
      var run=j.workflow_runs&&j.workflow_runs[0];if(!run){el.textContent=w[2]+': no round yet';return;}
      var on=run.status==='in_progress',start=new Date(run.run_started_at||run.created_at);
      var what=on?'round running for '+dur(Date.now()-start):run.status==='completed'?'last round '+run.conclusion+', ended':'round '+run.status;
      el.innerHTML='<span class=\"dot'+(on?' on':'')+'\"></span><b>'+w[2]+'</b>: <a href=\"'+run.html_url+'\">'+what+'</a> '+
        (on?'(since <time datetime=\"'+start.toISOString()+'\"></time>)':'<time data-style=\"ago\" datetime=\"'+run.updated_at+'\"></time>')+
        ' on <code>'+run.head_sha.slice(0,7)+'</code>';show(el);
    }).catch(function(){el.textContent=w[2]+': status unavailable';});});
})();")

(defn page
  "The whole page as hiccup."
  [rounds milestones repo]
  (let [real (latest rounds :real)
        sim (latest rounds :sim)
        won (reduce + (map #(:round/ok % 0) rounds))
        ev (ever rounds)
        best (last (filter ev milestones))
        nr (count milestones)]
    [:html {:lang "en"}
     [:head [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "clojurecraft: the race to the Nether"] [:style (h/raw style)]]
     [:body {:data-repo repo} [:h1 "clojurecraft: the race to the Nether"]
      [:p.muted
       "Bots play from spawn to the Nether, with nothing given, on real servers and against the server model, round after round on the newest code. Times are in "
       [:span#tz "your zone"] "."]
      [:div.live [:div#live-real.muted "Real servers: …"] [:div#live-sim.muted "Server model: …"]]
      [:div.cards
       [:div.card [:div.label "Nether"]
        [:div.big (if (pos? won) [:span.win (str won " reached")] "not yet")]
        [:div.muted (str (count rounds) " rounds since ") (t (when-of (first rounds)))]]
       [:div.card [:div.label "Furthest ever"] [:div.big (if best (name best) "—")]
        [:div.muted
         (when best (str "milestone " (inc (.indexOf milestones best)) " of " nr ", first "))
         (when best (t (:first (ev best)) :ago))]]
       (for [[r label] [[real "Newest real round"] [sim "Newest sim round"]]
             :when r
             :let [[m n] (furthest r milestones)]]
         [:div.card [:div.label label] [:div.big (when m (name m))]
          [:div.muted (str n " of " (:round/runs r) " runs got there; ") (t (when-of r) :ago)]])]
      [:h2 "The road to the Nether"]
      [:p.muted
       "Each milestone: ticked once any run has reached it. The bars are the newest round's funnel, how many of its runs got this far, and the red figure how many of those that reached the step before were lost on the way. Times are from the bot setting off; the stage time is the gap between this milestone's median and the one before."]
      [:div.scroll (ladder rounds milestones)]
      (for [[r label] [[real "real servers"] [sim "the server model"]]
            :when r]
        (list
         [:h2 (str "Where they stop: " label)]
         [:p.muted
          "The newest round's failures by the stage the bot was on, the furthest first; each reason links to its issue."]
         (stops repo r milestones))) [:h2 "Round by round"]
      [:p.muted
       "A square per round (R real, S sim), oldest to newest: the darker, the more of its runs reached the milestone. Hover for the numbers."]
      [:div.scroll (strip rounds milestones)]
      [:div.cards {:style "margin-top:1rem"} (leader-card real milestones "bot, real servers")
       (leader-card sim milestones "run, server model")] [:h2 "Rounds"]
      [:div.scroll
       [:table
        [:thead
         [:tr [:th "started"] [:th "took"] [:th "kind"] [:th "commit"] [:th ""] [:th "runs"]
          [:th "Nether"] [:th "furthest"] [:th "top reasons"]]]
        [:tbody (for [r (take table-rounds (reverse rounds))] (table-row repo milestones r))]]]
      [:footer "Built by " [:code "bb page"] " from " [:a {:href "rounds.edn"} "rounds.edn"]
       ", one round a line, beside this file. Issue #116."] [:script (h/raw script)]]]))

(defn html
  "The page as a string."
  [rounds milestones repo]
  (str "<!DOCTYPE html>\n" (h/html (page rounds milestones repo))))

;;;; I/O: the files, git, gh ;;;;

(defn goals
  "The goal table, from the repository's resources."
  []
  (edn/read-string {:default (fn [_ v] v)} (slurp goals-file)))

(defn race-end
  "The --until the races play to (ci/race.edn's :race/until): the chain ends there. Issue #261
   raised it from the Nether to the fortress; the page follows the file, never a constant of its
   own."
  []
  (:race/until (edn/read-string (slurp race-file))))

(defn read-rounds
  "The rounds in a rounds.edn, oldest first."
  [path]
  (if (fs/exists? path)
    (into []
          (keep #(when (seq (str/trim %)) (edn/read-string {:default (fn [_ v] v)} %)))
          (str/split-lines (slurp path)))
    []))

(defn repo
  "The repository, owner/name: the workflow's, else this checkout's origin."
  []
  (or (System/getenv "GITHUB_REPOSITORY")
      (some->>
        (p/shell {:out :string :err :string :continue true} "git" "remote" "get-url" "origin")
        :out
        (re-find #"github\.com[:/](.+?)(?:\.git)?\s*$")
        second)))

(defn marks-under
  "Every MILESTONE fact in the race logs under dir."
  [dir]
  (into []
        (comp (filter #(= "race.log" (fs/file-name %)))
              (mapcat #(str/split-lines (slurp (fs/file %))))
              (keep mark-of-line))
        (fs/glob dir "**")))

(defn run-times
  "The run's start and end as ISO instants, from GitHub, or {} without gh: the end is now while
   the run is still going (its report job asks), else when GitHub last updated it."
  [run]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "gh"
                   "api"
                   (str "repos/" (repo) "/actions/runs/" run))]
    (if (zero? (:exit r))
      (let [j (json/parse-string (:out r) true)]
        {:started (:run_started_at j)
         :ended (if (= "completed" (:status j)) (:updated_at j) (str (java.time.Instant/now)))})
      {})))

(defn issue-numbers
  "{signature-string issue-number} for the round's signatures, from the bot-failure issues."
  [runs]
  (let [all (failures/issues)]
    (into {}
          (keep (fn [[sig _]]
                  (when-let [i (failures/issue-for all sig)] [(pr-str sig) (:number i)])))
          (failures/grouped runs))))

(defn build!
  "Write index.html for the rounds in rounds-path into out-dir."
  [rounds-path out-dir]
  (fs/create-dirs out-dir)
  (let [rounds (read-rounds rounds-path)]
    (spit (str (fs/path out-dir "index.html"))
          (html (rows rounds) (chain (goals) (race-end)) (repo)))
    (println "built" (str (fs/path out-dir "index.html")) "from" (count rounds) "rounds")))

(defn git!
  "git with args in dir; throws when it fails."
  [dir & args]
  (apply p/shell {:dir (str dir)} "git" args))

(defn entry-key
  "What makes an entry the same entry: run, kind, runner and batch."
  [r]
  ((juxt :round/run :round/kind :round/runner :round/batch) r))

(defn deploy!
  "Ask GitHub to deploy gh-pages (pages.yml): the branch is the page's data, the deployment is
   what the site serves. A failed request is printed, never thrown: the next publish asks again."
  []
  (let [r (p/shell {:continue true :out :string :err :string}
                   "gh" "workflow"
                   "run" "pages.yml"
                   "--repo" (repo)
                   "--ref" "main")]
    (println (if (zero? (:exit r)) "deploy requested" (str "deploy request failed: " (:err r))))))

(defn publish-round!
  "Append round (a value) to rounds.edn on the gh-pages branch (replacing an earlier entry with
   the same run, kind, runner and batch), rebuild index.html there, commit and push; a push another
   report won meanwhile is retried on top of it, three times. The branch is worked in a worktree
   of its own, removed after."
  [round]
  (let [dir (str (fs/path (fs/parent (fs/absolutize "."))
                          (str "pages-" (:round/run round)
                               "-" (name (:round/kind round))
                               "-" (:round/runner round 0)
                               "-" (:round/batch round 0))))
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
            kept (remove #(= (entry-key %) (entry-key round)) (read-rounds rounds))]
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
          (cond pushed
                (do (println "published round" (:round/run round) "to" branch)
                    (p/shell {:continue true} "git" "worktree" "remove" "--force" dir)
                    (deploy!))
                (and (pos? (dec tries)) fetched)
                (do (git! "." "fetch" "origin" branch) (recur (dec tries)))
                :else (throw (ex-info "could not push the page" {:branch branch}))))))))

(defn publish!
  "Publish the round in the EDN file at round-path (publish-round!)."
  [round-path]
  (publish-round! (edn/read-string {:default (fn [_ v] v)} (slurp round-path))))

(defn -main
  "round <dir> <kind> <run> <commit> <at>, wall <dir>, build <rounds.edn> <out-dir>, publish <round.edn>."
  [& [cmd a b c d e]]
  (case cmd
    "round"
    (let [runs (failures/runs-under a)]
      (prn (round runs
                  (marks-under a)
                  (chain (goals) (race-end))
                  (merge {:kind (keyword b)
                          :run (parse-long c)
                          :commit d
                          :at (instant e)
                          :issues (when (repo) (issue-numbers runs))}
                         (run-times c)))))
    "wall"
    (doseq [[stage total sigs] (wall (mapv (fn [[sig rs]] [sig (count rs)])
                                           (failures/grouped (failures/runs-under a)))
                                     (chain (goals) (race-end)))]
      (println (name stage)
               total
               (str/join ", " (for [[sig n] (take 3 sigs)] (str (pr-str sig) " " n)))))
    "build" (build! a b)
    "publish" (publish! a)
    (do
      (println
       "usage: bb page round <dir> <kind> <run> <commit> <at> | wall <dir> | build <rounds.edn> <out-dir> | publish <round.edn>")
      (System/exit 2))))
