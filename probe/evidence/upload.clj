;; The evidence path on a runner (feat/evidence): a snapshot uploaded by churn/upload! to a
;; throwaway release, named by failures/snapshot-note, read back by issue-check's sources and
;; downloaded by release-file!, then the release deleted. bb probe/evidence/upload.clj
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.java.io :as io]
         '[clojurecraft.churn :as churn]
         '[clojurecraft.failures :as failures]
         '[clojurecraft.issue-check :as ic])

(let [run (parse-long (System/getenv "GITHUB_RUN_ID"))
      sha (System/getenv "GITHUB_SHA")
      tag (str "evidence-probe-" run)
      snap "out/R9-1-1.edn.gz"
      _ (fs/create-dirs "out")
      _ (with-open [o (java.util.zip.GZIPOutputStream. (io/output-stream snap))]
          (.write o (.getBytes "#:snapshot{:moment {:why :intent-failed, :t 0}}")))
      runs [{:run/recurs? true
             :run/snapshot snap
             :run/moment {:why :intent-failed :t 0 :stop-t 62000 :window-ms 122000}}
            {:run/recurs? true :run/snapshot "out/missing.edn.gz"}
            {:run/snapshot "out/R9-2-1.edn.gz"}]
      assets (churn/upload! "out/up" run sha tag runs)
      after (churn/evidenced runs run tag assets)
      note (failures/snapshot-note (first after))
      found
      (ic/kept-snapshots
       (str "- "
            note
            "; or ...\n- snapshot `R1-2-3.edn.gz` in artifact `simrace-x-1` (moment 0:30 before)"))
      got (ic/release-file! (:release (first found)) (:asset (first found)))]
  (println "hour tag now:" (churn/evidence-tag (str (java.time.Instant/now))))
  (println "assets:" assets)
  (println "after:" (pr-str after))
  (println (churn/evidence-text runs after tag))
  (println "note:" note)
  (println "sources:" (pr-str found))
  (println "downloaded:"
           (str got)
           (some-> got
                   fs/size)
           "bytes, original" (fs/size snap)
           "kept-moment?"
           (some-> got
                   ic/kept-moment?))
  (println "VERDICT"
           (if (and (= 1 (count (filter :run/release after)))
                    (not-any? :run/recurs? (rest after))
                    got
                    (= (fs/size got) (fs/size snap))
                    (ic/kept-moment? got)
                    (= {:file "R1-2-3.edn.gz" :moment? true :artifact "simrace-x-1"}
                       (second found)))
             "ok"
             "WRONG"))
  (p/shell {:continue true}
           "gh" "release"
           "delete" tag
           "--repo" (churn/gh-repo)
           "--cleanup-tag" "-y"))
