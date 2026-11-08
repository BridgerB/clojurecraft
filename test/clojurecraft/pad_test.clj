(ns clojurecraft.pad-test
  "The pad a fortress bot lands on is cleared before it is teleported onto it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.land :as land]))

(deftest a-pad-is-cleared-of-lava-and-of-a-former-bots-blocks-before-a-bot-lands
  ;; the pad is built once and every bot of its slot lands on it: one teleported into blocks a
  ;; former bot placed, or the netherrack the lava clear left in its cells, took in_wall damage
  (let [pad [96 224 72]
        [lava interior :as cmds] (land/pad-clear-commands pad)]
    (is (= 2 (count cmds)))
    (is (str/includes? lava "replace minecraft:lava") "the lava clear is first")
    (is
     (= "execute in minecraft:the_nether run fill 95 72 223 97 74 225 minecraft:air" interior)
     "the 3x3 round the pad from the feet's cell to two over it: never the floor under the feet (y 71)")))
