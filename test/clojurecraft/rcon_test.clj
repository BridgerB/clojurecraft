(ns clojurecraft.rcon-test
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.rcon :as rcon])
  (:import [java.nio ByteBuffer]))

(deftest frame-roundtrip
  (let [bytes (rcon/encode-packet 7 2 "list")]
    (is (= (+ 4 4 4 4 2) (alength bytes)))
    (is (= {:id 7 :type 2 :body "list"} (rcon/decode-packet (ByteBuffer/wrap bytes))))))
