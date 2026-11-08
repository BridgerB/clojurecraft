(ns clojurecraft.shapes
  "The two shapes every position attribute shares, registered under the names clojurecraft.spec
   uses (:clojurecraft.spec/vec3, :clojurecraft.spec/block-pos), in a leaf namespace so an
   executor that specs its own attributes can load them before clojurecraft.spec exists."
  (:require [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

(s/def :clojurecraft.spec/vec3 (s/coll-of double? :kind vector? :count 3))
(s/def :clojurecraft.spec/block-pos (s/coll-of int? :kind vector? :count 3))
