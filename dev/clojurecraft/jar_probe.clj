(ns clojurecraft.jar-probe
  "Ask the vanilla server itself what the --reports output does not say: every block's
   hardness (BlockState.getDestroySpeed, the number the server divides by when it breaks a
   block), every tool material's speed, durability and harvest tag (ToolMaterial), and every
   fuel's burn ticks (FuelValues). Run by
   bb datagen in a JVM whose classpath is the inner server jar plus the libraries the
   bundler ships, since the server's classes need them to boot:

     java -cp <inner.jar>:<libs>:<clojure> clojure.main -m clojurecraft.jar-probe out.json

   The inner jar is not obfuscated (net.minecraft.world.level.block.Blocks is its real name),
   so this is reflection against the game at the version bb datagen downloads, and nothing
   here is a number someone typed."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.lang.reflect Field]
           [java.util Map]
           [net.minecraft SharedConstants]
           [net.minecraft.core BlockPos]
           [net.minecraft.core Registry$PendingTags RegistryAccess]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.server Bootstrap]
           [net.minecraft.server.packs PackType]
           [net.minecraft.server.packs.repository ServerPacksSource]
           [net.minecraft.server.packs.resources MultiPackResourceManager]
           [net.minecraft.tags TagLoader]
           [net.minecraft.world.flag FeatureFlags]
           [net.minecraft.world.item Item ToolMaterial]
           [net.minecraft.world.level EmptyBlockGetter]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.entity FuelValues]))

(set! *warn-on-reflection* true)

(defn hardness
  "{\"minecraft:stone\" 1.5 ...} for every registered block: its default state's destroy speed;
   -1.0 is unbreakable (bedrock)."
  []
  (let [reg BuiltInRegistries/BLOCK]
    (into (sorted-map)
          (for [^Block b reg]
            [(str (.getKey reg b))
             (double (.getDestroySpeed (.defaultBlockState b)
                                       EmptyBlockGetter/INSTANCE
                                       BlockPos/ZERO))]))))

(defn materials
  "{\"WOOD\" {\"speed\" 2.0 \"durability\" 59 \"incorrect\" \"minecraft:incorrect_for_wooden_tool\"} ...}
   for every ToolMaterial constant."
  []
  (into (sorted-map)
        (for [^Field f (.getDeclaredFields ToolMaterial)
              :when (= ToolMaterial (.getType f))
              :let [^ToolMaterial m (.get f nil)]]
          [(.getName f)
           {"speed" (double (.speed m))
            "durability" (.durability m)
            "incorrect" (str (.location (.incorrectBlocksForDrops m)))}])))

(defn fuel
  "{\"minecraft:coal\" 1600 ...}: every item the game burns and its burn ticks, from the game's own
   FuelValues. Fuel is given partly by item tag (#logs, #planks), so the vanilla data pack's tags
   are loaded and bound to the registries first; the burn times are FuelValues' own item map."
  []
  (let [rm (MultiPackResourceManager. PackType/SERVER_DATA
                                      [(ServerPacksSource/createVanillaPackSource)])
        access (RegistryAccess/fromRegistryOfRegistries BuiltInRegistries/REGISTRY)]
    (doseq [^Registry$PendingTags p (TagLoader/loadTagsForExistingRegistries rm access)] (.apply p))
    (let [fv (FuelValues/vanillaBurnTimes access FeatureFlags/VANILLA_SET)
          ^Map values (.get (doto (.getDeclaredField FuelValues "values") (.setAccessible true))
                            fv)]
      (into (sorted-map)
            (for [[^Item i ticks] values] [(str (.getKey BuiltInRegistries/ITEM i)) ticks])))))

(defn -main
  "Boot the game's registries and write {\"hardness\" ... \"materials\" ... \"fuel\" ...} as
   JSON to out."
  [out]
  (SharedConstants/tryDetectVersion)
  (Bootstrap/bootStrap)
  (with-open [w (io/writer out)]
    (json/write {"hardness" (hardness) "materials" (materials) "fuel" (fuel)} w))
  (println "wrote" out)
  (shutdown-agents))
