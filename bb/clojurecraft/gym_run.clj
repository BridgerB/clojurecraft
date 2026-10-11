(ns clojurecraft.gym-run
  "One gym run on a running server: the fixture (clojure -M:gym land) piped into the bot, then the
   judge (clojure -M:gym judge) while the bot holds. A run that loses its connection says nothing
   about the goal, so it is run once more on the same landing before the result is kept.

     GOAL=wood RUN=3 BOT=Clj_g3 UNTIL=wood TIMEOUT_MS=150000 LANDINGS=f.edn RCON_PASS=s \\
     STRICT=false COMMIT=sha bb gym-run

   Exits with the judge's code. The server is on 25565, RCON on 25575, as gym.yml starts it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def required ["GOAL" "RUN" "BOT" "UNTIL" "TIMEOUT_MS" "LANDINGS" "RCON_PASS"])   ; the run, from gym.yml
(def leftovers ["bot.log" "land.log" "landed.edn" "result.edn" "work/run.edn"])     ; a previous attempt's

(defn land-cmd
  "The fixture: wait for the bot, land it, print its :go."
  [{:strs [GOAL RUN BOT LANDINGS RCON_PASS]}]
  ["clojure" "-M:gym" "land" "--goal" GOAL "--run" RUN "--name" BOT "--rcon-port" "25575"
   "--rcon-pass" RCON_PASS "--landings" LANDINGS "--out" "landed.edn"])

(defn bot-cmd
  "The bot, reading the fixture's :go on stdin and recording the run; it holds for the judge."
  [{:strs [BOT UNTIL TIMEOUT_MS]}]
  ["clojure" "-M:run" "--host" "127.0.0.1" "--port" "25565" "--name" BOT "--until" UNTIL "--events"
   "stdin" "--timeout-ms" TIMEOUT_MS "--hold-ms" "60000" "--record" "work/run.edn"])

(defn judge-cmd
  "The judge: wait for RESULT, ask the server, write result.edn, print one GYMRESULT line."
  [{:strs [GOAL RUN BOT RCON_PASS STRICT COMMIT]}]
  ["clojure" "-M:gym" "judge" "--goal" GOAL "--run" RUN "--name" BOT "--rcon-port" "25575"
   "--rcon-pass" RCON_PASS "--bot-log" "bot.log" "--landed" "landed.edn" "--out" "result.edn"
   "--commit" (or COMMIT "unknown") "--strict" (or STRICT "false")])

(defn disconnected?
  "Did the judged run lose its connection (result.edn says :disconnect)?"
  []
  (and (fs/exists? "result.edn") (str/includes? (slurp "result.edn") ":gym/outcome :disconnect")))

;;;; I/O: the processes, the files, the exit code ;;;;

(defn attempt!
  "One attempt: the fixture piped into the bot in the background, the judge in the foreground,
   then the bot stopped (its recording already ended at RESULT; the hold was only for the
   judge). Returns the judge's exit code."
  [env]
  (run! fs/delete-if-exists leftovers)
  (fs/create-dirs "work")
  (let [land (apply p/process {:err (fs/file "land.log")} (land-cmd env))
        bot (apply p/process land {:out (fs/file "bot.log") :err :out} (bot-cmd env))
        code (:exit (apply p/shell {:continue true} (judge-cmd env)))]
    (p/destroy bot)
    (deref bot)
    code))

(defn -main
  "Run once, again on a disconnect, print the fixture's and the bot's logs, exit with the judge's
   code."
  []
  (let [env (into {} (System/getenv))]
    (doseq [k required] (when-not (env k) (throw (ex-info (str "set " k) {}))))
    (let [code (attempt! env)
          code (if (disconnected?)
                 (do (println "disconnected: running once more on the same landing") (attempt! env))
                 code)]
      (println (slurp "land.log"))
      (println (slurp "bot.log"))
      (System/exit code))))
