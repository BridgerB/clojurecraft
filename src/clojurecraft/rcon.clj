(ns clojurecraft.rcon
  "Minimal RCON client, used by the harness for fixtures and by CI as the independent judge.
   `clojure -M:rcon --host H --port P --pass S <command words>` prints the response."
  (:import [java.io DataInputStream DataOutputStream]
           [java.net InetSocketAddress Socket]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

(defn encode-packet
  "One RCON packet: little-endian length, id, type (3 login, 2 command), the
   UTF-8 payload and two null bytes."
  ^bytes [^long id ^long type ^String payload]
  (let [body (.getBytes payload StandardCharsets/UTF_8)
        len (+ 4 4 (alength body) 2)
        bb (doto (ByteBuffer/allocate (+ 4 len)) (.order ByteOrder/LITTLE_ENDIAN))]
    (.putInt bb (int len)) (.putInt bb (int id)) (.putInt bb (int type))
    (.put bb body) (.put bb (byte 0)) (.put bb (byte 0))
    (.array bb)))

(defn decode-packet
  "{:id :type :body} from a buffer positioned at a response, or nil when short."
  [^ByteBuffer bb]
  (.order bb ByteOrder/LITTLE_ENDIAN)
  (when (>= (.remaining bb) 4)
    (let [len (.getInt bb)]
      (when (>= (.remaining bb) len)
        (let [id (.getInt bb) type (.getInt bb)
              body (byte-array (- len 10))]
          (.get bb body)
          (.get bb) (.get bb)
          {:id id :type type :body (String. body StandardCharsets/UTF_8)})))))

;;;; I/O: the socket ;;;;

(defn read-response "Block for one whole response packet and decode it." [^DataInputStream in]
  (let [head (byte-array 4)]
    (.readFully in head)
    (let [len (.getInt (.order (ByteBuffer/wrap head) ByteOrder/LITTLE_ENDIAN))
          rest (byte-array len)]
      (.readFully in rest)
      (decode-packet (ByteBuffer/wrap (byte-array (concat head rest)))))))

(defn connect
  "Open and authenticate; returns {:sock :in :out :next-id}. Throws when the
   password is refused (the server answers with id -1)."
  [host port pass]
  (let [sock (doto (Socket.) (.connect (InetSocketAddress. ^String host (int port)) 5000))
        in (DataInputStream. (.getInputStream sock))
        out (DataOutputStream. (.getOutputStream sock))]
    (.write out (encode-packet 1 3 pass))
    (.flush out)
    (let [r (read-response in)]
      (when (= -1 (:id r)) (throw (ex-info "rcon auth failed" {:host host :port port}))))
    {:sock sock :in in :out out :next-id (atom 10)}))

(defn command
  "Send one command, return the server's text response."
  [{:keys [^DataInputStream in ^DataOutputStream out next-id]} ^String s]
  (let [id (swap! next-id inc)]
    (.write out (encode-packet id 2 s))
    (.flush out)
    (loop [acc ""]
      (let [r (read-response in)]
        (if (= id (:id r))
          (str acc (:body r))
          (recur acc))))))

(defn close "Close the connection's socket." [{:keys [^Socket sock]}] (.close sock))

(defn with-rcon
  "Call (f conn) on an authenticated connection and close it afterwards, even on
   a throw; returns what f returns."
  [host port pass f]
  (let [c (connect host port pass)]
    (try (f c) (finally (close c)))))

(defn -main
  "clojure -M:rcon --host H --port P --pass S <command...>"
  [& args]
  (let [[flags words] (split-with (fn [[k _]] (clojure.string/starts-with? k "--")) (partition-all 2 args))
        {:strs [--host --port --pass]} (into {} (map vec flags))
        cmd (clojure.string/join " " (apply concat words))]
    (println (with-rcon (or --host "127.0.0.1") (Long/parseLong (or --port "25575")) --pass
               #(command % cmd)))
    (shutdown-agents)))
