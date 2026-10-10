(ns clojurecraft.jar-probe
  "Ask the vanilla server itself what the --reports output does not say: every block's
   hardness (BlockState.getDestroySpeed, the number the server divides by when it breaks a
   block) and every tool material's speed, durability and harvest tag (ToolMaterial). Run by
   scripts/datagen.sh in a JVM whose classpath is the inner server jar plus the libraries the
   bundler ships, since the server's classes need them to boot:

     java -cp <inner.jar>:<libs>:<clojure> clojure.main -m clojurecraft.jar-probe out.json

   The inner jar is not obfuscated (net.minecraft.world.level.block.Blocks is its real name),
   so this is reflection against the game at the version datagen.sh downloads, and nothing
   here is a number someone typed."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [net.minecraft.server Bootstrap]
           [net.minecraft SharedConstants]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.world.item ToolMaterial]
           [net.minecraft.world.level EmptyBlockGetter]))

(defn hardness
  "{\"minecraft:stone\" 1.5 ...} for every registered block: its default state's destroy speed;
   -1.0 is unbreakable (bedrock)."
  []
  (let [reg BuiltInRegistries/BLOCK]
    (into (sorted-map)
          (for [b reg]
            [(str (.getKey reg b))
             (double (.getDestroySpeed (.defaultBlockState b) EmptyBlockGetter/INSTANCE BlockPos/ZERO))]))))

(defn materials
  "{\"WOOD\" {\"speed\" 2.0 \"durability\" 59 \"incorrect\" \"minecraft:incorrect_for_wooden_tool\"} ...}
   for every ToolMaterial constant."
  []
  (into (sorted-map)
        (for [f (.getDeclaredFields ToolMaterial) :when (= ToolMaterial (.getType f))
              :let [m (.get f nil)]]
          [(.getName f) {"speed" (double (.speed m))
                         "durability" (.durability m)
                         "incorrect" (str (.location (.incorrectBlocksForDrops m)))}])))

(defn -main
  "Boot the game's registries and write {\"hardness\" ... \"materials\" ...} as JSON to out."
  [out]
  (SharedConstants/tryDetectVersion)
  (Bootstrap/bootStrap)
  (with-open [w (io/writer out)]
    (json/write {"hardness" (hardness) "materials" (materials)} w))
  (println "wrote" out)
  (shutdown-agents))
