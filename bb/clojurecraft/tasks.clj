(ns clojurecraft.tasks
  "Helpers for the tasks in bb.edn: run a tool natively when it is on PATH (the flake's shell),
   else through its deps.edn alias on the JVM (CI, which has no nix). Either way the same version:
   flake.nix pins the native one, deps.edn the alias, and they are kept equal.

   Babashka runs this, not the JVM, so nothing here may require the bot's own namespaces."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.tasks :as tasks]))

(def source-paths
  "Where the Clojure the checks look at lives: the bot, its tooling, its tests, the REPL's user
   namespace, these tasks and their tests."
  ["src" "dev" "test" "repl" "bb" "test-bb"])

(defn tool!
  "Run native (a program name) with args when it is on PATH, else `clojure -M:<alias>` with the
   same args. Throws when the command fails, so a task stops there."
  [native alias & args]
  (if (fs/which native) (apply p/shell native args) (apply tasks/clojure (str "-M:" alias) args)))
