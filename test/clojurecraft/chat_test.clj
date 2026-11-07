(ns clojurecraft.chat-test
  "What the server says in words (the observability plan): network NBT read and written, a real
   death component's bytes, text/plain and text/death, the handlers for system-chat and
   player-combat-kill, the ledger's lines, and the model telling the bot how it died."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chat :as chat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.mob-test :as m]
            [clojurecraft.nbt :as nbt]
            [clojurecraft.packet :as p]
            [clojurecraft.senses :as senses]
            [clojurecraft.sim :as sim]
            [clojurecraft.text :as text])
  (:import [java.io DataOutputStream]))

(use-fixtures :once fx/instrumented)

(defn raw
  "Bytes of the parts: an integer is one byte, a string a u16 length and its modified UTF-8, as
   hand-written network NBT reads."
  ^bytes [& parts]
  (b/with-out (fn [^DataOutputStream out]
                (run! #(if (string? %) (.writeUTF out ^String %) (.writeByte out (int %))) parts))))

(defn read-raw "The component the bytes hold." [^bytes bs] (nbt/read-component (b/buffer bs)))

(defn round-trip
  "v written as a component and read back."
  [v]
  (read-raw (b/with-out #(nbt/write-component % v))))

(def blaze-death
  "The death component of a player killed by a blaze, as the server's own bytes lay it out: a
   compound with a translate key and a list of two compounds."
  (raw 10 8
       "translate" "death.attack.mob"
       9 "with"
       10 0
       0 0
       2 8
       "text" "Clj_r05"
       0 8
       "translate" "entity.minecraft.blaze"
       0 0))

(def blaze-death-map (read-raw blaze-death))

(deftest a-plain-string-component-is-a-tag-string (is (= "hello" (read-raw (raw 8 "hello")))))

(deftest a-real-death-component-reads-as-a-map
  (is (= {"translate" "death.attack.mob"
          "with" [{"text" "Clj_r05"} {"translate" "entity.minecraft.blaze"}]}
         (read-raw blaze-death))))

(deftest every-tag-type-can-be-stepped-over
  (let [bs (raw
            10 1
            "byte" 7
            2 "short"
            0 9
            3 "int"
            0 0
            1 0
            4 "long"
            0 0
            0 0
            0 0
            0 5
            5 "float"
            0x3f 0xc0
            0 0
            6 "double"
            0x3f 0xf8
            0 0
            0 0
            0 0
            7 "bytes"
            0 0
            0 2
            1 2
            11 "ints"
            0 0
            0 1
            0 0
            0 7
            12 "longs"
            0 0
            0 1
            0 0
            0 0
            0 0
            0 9
            9 "empty"
            0 0
            0 0
            0 0)
        buf (b/buffer bs)]
    (is (= {"byte" 7
            "short" 9
            "int" 256
            "long" 5
            "float" 1.5
            "double" 1.5
            "bytes" [1 2]
            "ints" [7]
            "longs" [9]
            "empty" []}
           (nbt/read-component buf)))
    (is (zero? (.remaining buf)) "the whole tag was consumed")))

(deftest a-component-reads-only-as-far-as-it-goes
  (let [buf (b/buffer (raw 8 "ab" 1))]
    (is (= "ab" (nbt/read-component buf)))
    (is (= 1 (.remaining buf)) "what follows is left for the next field")))

(deftest malformed-nbt-reads-as-nil-and-leaves-the-buffer-where-it-began
  (doseq [[why bs]
          {"a list longer than the frame" (raw 9 10 0x7f 0xff 0xff 0xff)
           "an unknown tag type" (raw 99 1 2 3)
           "a string longer than the frame" (raw 8 0xff 0xff)
           "a truncated compound" (raw 10 8 "translate" "x")
           "an array longer than the frame" (raw 7 0x7f 0xff 0xff 0xff)
           "nothing at all" (raw)
           "nesting too deep"
           (apply raw 10 (concat (mapcat (fn [_] [10 "a"]) (range (* 2 nbt/max-depth))) [0]))}]
    (testing why
      (let [buf (b/buffer bs)]
        (is (nil? (nbt/read-component buf)))
        (is (zero? (.position buf)))))))

(deftest values-survive-the-writer-and-the-reader
  (doseq [v ["a string" "" "café ☃" {"text" "hi" "color" "red"} {}
             {"translate" "death.attack.lava" "with" [{"text" "Clj_r05"}]}
             {"n" 7 "big" 5000000000 "neg" -3 "d" 0.25 "xs" [1 2 3] "none" []}
             [{"text" "a"} {"text" "b"}]]]
    (is (= v (round-trip v)) (pr-str v))))

(deftest a-component-the-writer-cannot-hold-is-refused
  (is (thrown? Exception (round-trip {"mixed" ["a" {"text" "b"}]})))
  (is (thrown? Exception (round-trip {"x" :keyword}))))

(deftest plain-reads-a-component-as-a-string
  (is (= "hi" (text/plain "hi")))
  (is (= "hi" (text/plain {"text" "hi" "color" "red"})))
  (is (= "ab" (text/plain {"text" "a" "extra" [{"text" "b"}]})))
  (is (= "death.attack.mob Clj_r05 entity.minecraft.blaze" (text/plain (read-raw blaze-death))))
  (is (= "ab" (text/plain ["a" {"text" "b"}])))
  (testing "a malformed component reads as what it holds"
    (is (= "" (text/plain nil)))
    (is (= "k" (text/plain {"translate" "k" "with" 5})))
    (is (= "x" (text/plain {"text" "x" "extra" "no"})))))

(deftest death-names-the-key-the-victim-and-the-killer
  (is (= {:death/key "death.attack.mob" :death/victim "Clj_r05" :death/by "blaze"}
         (text/death (read-raw blaze-death))))
  (is (= {:death/key "death.attack.lava" :death/victim "Clj_r05"}
         (text/death {"translate" "death.attack.lava" "with" [{"text" "Clj_r05"}]})))
  (is (= {:death/key "death.attack.player" :death/victim "A" :death/by "Bridger"}
         (text/death {"translate" "death.attack.player" "with" ["A" "Bridger"]})))
  (is (nil? (text/death "just words")))
  (is (nil? (text/death {"translate" "chat.type.text" "with" ["a" "b"]})))
  (is (= {:death/key "death.fell.accident.ladder"}
         (text/death {"translate" "death.fell.accident.ladder"}))))

(deftest the-models-death-messages-are-the-servers-keys
  (let [msg (fn [cause how] (text/death (text/cause-component "Clj_r05" cause how)))]
    (is (= {:death/key "death.attack.mob" :death/victim "Clj_r05" :death/by "blaze"}
           (msg :blaze nil)))
    (is (= {:death/key "death.attack.fireball" :death/victim "Clj_r05" :death/by "blaze"}
           (msg :blaze :fireball)))
    (is (= {:death/key "death.attack.arrow" :death/victim "Clj_r05" :death/by "skeleton"}
           (msg :skeleton :arrow)))
    (is (= "death.attack.wither" (:death/key (msg :wither_skeleton :wither))))
    (is (= "death.attack.onFire" (:death/key (msg :on_fire nil))))
    (is (= "death.attack.lava" (:death/key (msg :lava nil))))
    (is (= "death.attack.inFire" (:death/key (msg :in_fire nil))))
    (is (= "death.attack.fall" (:death/key (msg :fall nil))))
    (is (= "death.attack.drown" (:death/key (msg :drown nil))))))

;; ---------------------------------------------------------------- the handlers

(defn playing
  "A bot in play at time 1000."
  []
  (assoc (game/init fx/opts) :bot/phase :play :player/entity-id 5 :time/now 1000))

(defn heard "w after the packet p." [w p] (game/on-packet w p))

(defn said
  "A system-chat packet for component c."
  [c]
  {:packet/name :system-chat :content c :overlay false})

(deftest a-system-chat-line-is-kept-with-its-time-and-key
  (let [w (-> (playing)
              (heard (said "Welcome"))
              (heard (said blaze-death-map)))]
    (is (= [{:at 1000 :text "Welcome" :chat/via :system-chat}
            {:at 1000
             :text "death.attack.mob Clj_r05 entity.minecraft.blaze"
             :key "death.attack.mob"
             :chat/via :system-chat}]
           (:world/chat w)))))

(deftest chat-keeps-the-newest-twenty-lines
  (let [w (reduce (fn [w i] (heard (assoc w :time/now i) (said (str "line " i))))
                  (playing)
                  (range 30))]
    (is (= chat/chat-kept (count (:world/chat w))))
    (is (= "line 10" (:text (first (:world/chat w)))))
    (is (= "line 29" (:text (peek (:world/chat w)))))))

(deftest a-malformed-component-is-a-blank-line-not-a-crash
  (is (= [{:at 1000 :text "" :chat/via :system-chat}] (:world/chat (heard (playing) (said nil))))))

(deftest the-bots-own-death-text-is-kept-and-gone-at-the-respawn
  (let [w (heard (playing) {:packet/name :player-combat-kill :player-id 5 :message blaze-death-map})
        dead {:at 1000
              :text "death.attack.mob Clj_r05 entity.minecraft.blaze"
              :key "death.attack.mob"
              :killer "blaze"}]
    (is (= dead (:player/death-text w)))
    (is (not (contains? (game/on-packet w
                                        {:packet/name :respawn
                                         :dimension-type 0
                                         :dimension "minecraft:overworld"
                                         :seed 0
                                         :game-type 0
                                         :previous-game-type -1
                                         :debug? false
                                         :flat? false})
                        :player/death-text)))))

(deftest a-death-packet-decodes-from-its-wire-bytes
  (let [frame (b/with-out (fn [out]
                            (b/write-varint out (get-in p/ids [:play :s2c :player-combat-kill]))
                            (b/write-varint out 5)
                            (.write ^DataOutputStream out ^bytes blaze-death)))
        pkt (p/decode :play :s2c frame)]
    (is (= 5 (:player-id pkt)))
    (is (= blaze-death-map (:message pkt)))
    (is (= {:packet/name :system-chat :content "hi" :overlay true}
           (p/decode :play
                     :s2c
                     (b/with-out (fn [out]
                                   (b/write-varint out (get-in p/ids [:play :s2c :system-chat]))
                                   (.write ^DataOutputStream out ^bytes (raw 8 "hi"))
                                   (b/write-bool out true))))))))

;; ---------------------------------------------------------------- the ledger

(deftest the-ledger-names-each-new-line-and-the-death-text
  (let [before (assoc (playing) :player/pos [0.0 64.0 0.0])
        said-w (heard before (said "careful"))
        events (senses/events before said-w)]
    (is (= [{:ev :chat :at 1000 :text "careful" :chat/via :system-chat :t 1000}] events)))
  (let [alive (assoc (playing) :player/pos [0.0 64.0 0.0] :player/health 4.0)
        heard-w (heard alive
                       {:packet/name :player-combat-kill :player-id 5 :message blaze-death-map})
        dead (assoc heard-w :player/dead? true)
        evs (senses/events heard-w dead)]
    (is (= [:death-text] (map :ev (senses/events alive heard-w))))
    (is (= "death.attack.mob Clj_r05 entity.minecraft.blaze"
           (:text (first (senses/events alive heard-w)))))
    (is (= [:death] (map :ev evs)))
    (is (= "blaze" (:killer (first evs))))))

;; ---------------------------------------------------------------- the model

(defn arena
  "A model in play over blocks, the player named Clj_r05 and at 3 health."
  [blocks]
  (-> (m/arena blocks {} nil m/night [])
      (assoc :sim/username "Clj_r05" :sim/health 3.0)))

(deftest the-model-tells-a-death-by-lava
  (let [dead (m/ticks (arena {[0 64 0] (blocks/default-state :lava)}) 1)
        [kill :as kills] (m/said dead :player-combat-kill)]
    (is (= 1 (count kills)))
    (is (= {"translate" "death.attack.lava" "with" [{"text" "Clj_r05"}]} (:message kill)))
    (is (= (sim/player-eid-of dead) (:player-id kill)))
    (is (= 1 (count (m/said (m/ticks dead 5) :player-combat-kill))) "told once, not every tick")))

(deftest the-model-tells-a-mobs-blow-and-a-fireballs
  (let [hit (fn [cause how] (m/said (sim/hurt (arena {}) 5.0 true cause how) :player-combat-kill))]
    (is (= [(text/cause-component "Clj_r05" :zombie nil)] (map :message (hit :zombie nil))))
    (is (= "death.attack.mob" (get (first (map :message (hit :zombie nil))) "translate")))
    (is (= "death.attack.fireball" (get (first (map :message (hit :blaze :fireball))) "translate")))
    (is (= "death.attack.wither"
           (get (first (map :message (hit :wither_skeleton :wither))) "translate")))))

(deftest a-blow-that-leaves-the-player-alive-tells-no-death
  (is (empty? (m/said (sim/hurt (assoc (arena {}) :sim/health 20.0) 5.0 true :zombie)
                      :player-combat-kill))))

(deftest the-models-death-packet-survives-the-wire
  (let [pkt (first (m/said (sim/hurt (arena {}) 5.0 true :blaze :fireball) :player-combat-kill))
        frame (b/with-out (fn [out]
                            (b/write-varint out (get-in p/ids [:play :s2c :player-combat-kill]))
                            (p/write-fields out (p/specs [:play :s2c :player-combat-kill]) pkt)))
        got (p/decode :play :s2c frame)]
    (is (= (:message pkt) (:message got)))
    (is (= {:death/key "death.attack.fireball" :death/victim "Clj_r05" :death/by "blaze"}
           (text/death (:message got))))))

;; ---------------------------------------------------------------- the other lines

(deftest the-action-bar-title-and-subtitle-are-kept-with-their-packet
  (let [w (-> (playing)
              (heard {:packet/name :set-action-bar-text :text "Low on food"})
              (heard {:packet/name :set-title-text :text {"text" "Round 2" "color" "gold"}})
              (heard {:packet/name :set-subtitle-text :text {"translate" "k.sub" "with" ["x"]}}))]
    (is (= [{:at 1000 :text "Low on food" :chat/via :set-action-bar-text}
            {:at 1000 :text "Round 2" :chat/via :set-title-text}
            {:at 1000 :text "k.sub x" :key "k.sub" :chat/via :set-subtitle-text}]
           (:world/chat w)))))

(deftest a-boss-bars-name-is-a-line-when-the-bar-is-added
  (let [bar (java.util.UUID/fromString "6d986270-cdc1-46bd-9cdb-2f89cb3b1a83")
        w (heard (playing)
                 {:packet/name :boss-event
                  :uuid bar
                  :action 0
                  :name {"translate" "entity.minecraft.ender_dragon"}})]
    (is (= #{bar} (:world/boss-bars w)) "the bar is shown as before")
    (is (= [{:at 1000 :text "entity.minecraft.ender_dragon" :chat/via :boss-event}]
           (:world/chat w)))
    (testing "an add whose name did not read, a health update and a removal say nothing"
      (is (nil? (:world/chat (heard (playing) {:packet/name :boss-event :uuid bar :action 0}))))
      (is (= 1
             (count (:world/chat
                     (heard w {:packet/name :boss-event :uuid bar :action 2 :health 0.5}))))
          "an update adds no line")
      (is (= 1 (count (:world/chat (heard w {:packet/name :boss-event :uuid bar :action 1}))))))))

(deftest the-new-packets-decode-from-wire-bytes
  (let [frame (fn [pkt-name & parts]
                (b/with-out (fn [out]
                              (b/write-varint out (get-in p/ids [:play :s2c pkt-name]))
                              (run! #(.write ^DataOutputStream out ^bytes %) parts))))
        bar (java.util.UUID/fromString "6d986270-cdc1-46bd-9cdb-2f89cb3b1a83")]
    (is (= {:packet/name :set-action-bar-text :text "hi"}
           (p/decode :play :s2c (frame :set-action-bar-text (raw 8 "hi")))))
    (is (= {:packet/name :set-title-text :text {"text" "T"}}
           (p/decode :play :s2c (frame :set-title-text (raw 10 8 "text" "T" 0)))))
    (is (= {:packet/name :set-subtitle-text :text "s"}
           (p/decode :play :s2c (frame :set-subtitle-text (raw 8 "s")))))
    (testing "a boss bar add carries its name, then its colour and the rest unread"
      (let [add (frame :boss-event
                       (b/with-out #(do (b/write-uuid % bar) (b/write-varint % 0)))
                       (raw 8 "Ender Dragon")
                       (b/with-out #(b/write-f32 % 1.0)))
            d (p/decode :play :s2c add)]
        (is (= "Ender Dragon" (:name d)))
        (is (not (contains? d :health)))))
    (testing "a health update still reads its float, with no name"
      (let [upd (frame :boss-event
                       (b/with-out
                        #(do (b/write-uuid % bar) (b/write-varint % 2) (b/write-f32 % 0.75))))]
        (is (= {:packet/name :boss-event :uuid bar :action 2 :health 0.75}
               (p/decode :play :s2c upd)))))))
