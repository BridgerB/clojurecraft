(ns clojurecraft.gen-tables
  "Regenerate every generated table in resources/clojurecraft/ from the vanilla 26.1.2 server: the
   --reports output (blocks, items, packets, entity types), the inner jar's data files (version,
   recipes, item tags, harvest), and, by reflection against the game's own classes
   (clojurecraft.jar-probe), block hardness and tool materials. Needs Java 25 (the flake's shell).

     bb datagen      ; the jar (sha1-checked) -> its reports -> the probe -> the EDN tables

   The JVM side is dev/clojurecraft/datagen.clj and jar_probe.clj; this is the sequence around
   them, and what it already has (the reports, the extracted jars) is not made again."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.server :as server])
  (:import [java.util.zip ZipFile]))

(def version "26.1.2")                                          ; the server the tables describe
(def inner-entry (str "META-INF/versions/" version "/server-" version ".jar"))   ; the game itself
(def libraries-prefix "META-INF/libraries/")                    ; what the bundler ships beside it
(def probe-noise #"^\[|^SLF4J|^WARNING")                        ; the game's own log lines, not the probe's

(defn paths
  "Where everything lives, under the repo root."
  [root]
  (let [reports (str root "/data/reports")]
    {:jar (str root "/data/local-server/server-" version ".jar")
     :reports reports
     :inner (str reports "/inner.jar")
     :libs (str reports "/libs")
     :probe (str reports "/probe.json")
     :resources (str root "/resources/clojurecraft")}))

(defn quiet
  "The probe's output without the game's own log lines."
  [out]
  (str/join "\n" (remove #(re-find probe-noise %) (str/split-lines out))))

;;;; I/O: the jar, the reports, two JVMs ;;;;

(defn extract!
  "Copy the jar's entries that keep? accepts to under dir, at their own paths (one entry when
   keep? names exactly one)."
  [jar keep? dir]
  (with-open [z (ZipFile. (str jar))]
    (doseq [e (enumeration-seq (.entries z))
            :when (and (not (.isDirectory e)) (keep? (.getName e)))]
      (let [out (fs/file dir (.getName e))]
        (fs/create-dirs (fs/parent out))
        (with-open [in (.getInputStream z e)] (io/copy in out))))))

(defn reports!
  "Run the vanilla data generator once: its reports are kept between runs."
  [{:keys [jar reports]}]
  (when-not (fs/exists? (str reports "/reports/blocks.json"))
    (println "running vanilla data generator")
    (fs/create-dirs reports)
    (p/shell {:dir reports}
             "java"
             "-DbundlerMainClass=net.minecraft.data.Main"
             "-jar"
             jar
             "--reports"
             "--output"
             reports)))

(defn jars!
  "The inner jar and the bundled libraries, unpacked once."
  [{:keys [jar inner libs]}]
  (when-not (fs/exists? inner)
    (let [tmp (str inner ".d")]
      (extract! jar #{inner-entry} tmp)
      (fs/move (fs/path tmp inner-entry) inner {:replace-existing true})
      (fs/delete-tree tmp))
    (extract! jar #(str/starts-with? % libraries-prefix) libs)))

(defn probe!
  "Boot the game's classes in their own JVM (the inner jar, its libraries, and the datagen alias's
   classpath) and write hardness and tool materials to probe.json."
  [{:keys [inner libs probe]}]
  (let [lib-jars (map str (fs/glob libs "**.jar"))
        datagen-cp
        (str/trim (:out (p/shell {:out :string :dir server/root} "clojure" "-A:datagen" "-Spath")))
        cp (str/join ":" (concat [inner] lib-jars [datagen-cp]))
        res (p/shell {:out :string :err :out :continue true :dir server/root}
                     "java"
                     "-cp" cp
                     "clojure.main" "-m"
                     "clojurecraft.jar-probe" probe)]
    (println (quiet (:out res)))
    (when-not (zero? (:exit res)) (throw (ex-info "jar-probe failed" {:exit (:exit res)})))))

(defn -main
  "The jar, the reports, the probe, then the tables."
  []
  (let [{:keys [jar reports resources probe] :as ps} (paths server/root)]
    (server/ensure-jar! jar)
    (reports! ps)
    (println (str/join "\n"
                       (sort (map (comp str fs/file-name) (fs/list-dir (str reports "/reports"))))))
    (jars! ps)
    (probe! ps)
    (p/shell {:dir server/root}
             "clojure"
             "-M:datagen"
             (str reports "/reports")
             resources
             jar
             probe)))
