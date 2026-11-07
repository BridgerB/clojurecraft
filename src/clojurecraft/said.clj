(ns clojurecraft.said
  "The one place a heard line joins :world/chat, so a handler in game.clj and one in chat.clj (which
   requires game) keep the same window: values in, values out, no dependency on either.")

(set! *warn-on-reflection* true)

(def chat-kept 20)                     ; lines of :world/chat kept, the newest

(defn say
  "w with line (a map {:at :text ...}) appended to :world/chat, the oldest dropped past chat-kept."
  [w line]
  (update w
          :world/chat
          (fn [chat]
            (let [chat (conj (or chat []) line)]
              (cond-> chat (> (count chat) chat-kept) (subvec (- (count chat) chat-kept)))))))
