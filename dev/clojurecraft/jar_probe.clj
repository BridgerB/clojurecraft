(ns clojurecraft.jar-probe
  "Ask the vanilla server itself what the --reports output does not say: every block's
   hardness (BlockState.getDestroySpeed, the number the server divides by when it breaks a
   block), which blocks drop only to their correct tool (requiresCorrectToolForDrops: stone
   dug by hand drops nothing), every tool material's speed, durability and harvest tag
   (ToolMaterial), every fuel's burn ticks (FuelValues), and every living entity type's
   maximum health and category (DefaultAttributes, MobCategory). Run by
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
           [net.minecraft.core BlockPos Direction$Axis]
           [net.minecraft.core Registry$PendingTags RegistryAccess]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.server Bootstrap]
           [net.minecraft.server.packs PackType]
           [net.minecraft.server.packs.repository ServerPacksSource]
           [net.minecraft.server.packs.resources MultiPackResourceManager]
           [net.minecraft.tags TagLoader]
           [net.minecraft.world.entity EntityType MobCategory]
           [net.minecraft.world.entity.ai.attributes AttributeSupplier Attributes DefaultAttributes]
           [net.minecraft.world.flag FeatureFlags]
           [net.minecraft.world.item Item ToolMaterial]
           [net.minecraft.world.level EmptyBlockGetter]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.entity FuelValues]
           [net.minecraft.world.phys.shapes Shapes VoxelShape]))

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

(defn needs-tool
  "[\"minecraft:stone\" ...]: every block whose default state drops only when broken with its
   correct tool (BlockState.requiresCorrectToolForDrops, which Player.hasCorrectToolForDrops
   asks before it asks the tool), sorted."
  []
  (let [reg BuiltInRegistries/BLOCK]
    (vec (sort (for [^Block b reg
                     :when (.requiresCorrectToolForDrops (.defaultBlockState b))]
                 (str (.getKey reg b)))))))

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

(defn partial-shapes
  "{\"minecraft:cocoa\" 0.75 ...}: every block whose default state collides as neither nothing nor
   a full cube (BlockState.getCollisionShape against an empty world), with the top of its shape
   in blocks: a cocoa pod, a slab, a lantern, a carpet (0.0625). The bot's physics knows only
   cubes."
  []
  (let [reg BuiltInRegistries/BLOCK]
    (into (sorted-map)
          (for [^Block b reg
                :let [^VoxelShape s (.getCollisionShape (.defaultBlockState b)
                                                        EmptyBlockGetter/INSTANCE
                                                        BlockPos/ZERO)]
                :when (and (not (.isEmpty s)) (not= s (Shapes/block)))]
            [(str (.getKey reg b)) (.max s Direction$Axis/Y)]))))

(defn mobs
  "{\"minecraft:pig\" {\"max-health\" 10.0 \"category\" \"creature\"} ...}: every entity type the
   game gives attributes to (DefaultAttributes.hasSupplier: the living entities, and only those,
   since a living entity cannot be built without them), its maximum health (the MAX_HEALTH its
   supplier starts it with) and its MobCategory's name (monster, creature, misc ...). Health in an
   entity's metadata belongs to living entities only: the same index carries an interaction's
   height (issue #258)."
  []
  (let [reg BuiltInRegistries/ENTITY_TYPE]
    (into (sorted-map)
          (for [^EntityType t reg
                :when (DefaultAttributes/hasSupplier t)
                :let [^AttributeSupplier s (DefaultAttributes/getSupplier t)]]
            [(str (.getKey reg t))
             {"max-health" (.getValue s Attributes/MAX_HEALTH)
              "category" (.getName ^MobCategory (.getCategory t))}]))))

(defn -main
  "Boot the game's registries and write {\"hardness\" ... \"needs-tool\" ... \"materials\" ...
   \"fuel\" ...} as JSON to out."
  [out]
  (SharedConstants/tryDetectVersion)
  (Bootstrap/bootStrap)
  (with-open [w (io/writer out)]
    (json/write {"hardness" (hardness)
                 "needs-tool" (needs-tool)
                 "materials" (materials)
                 "fuel" (fuel)
                 "partial-shapes" (partial-shapes)
                 "mobs" (mobs)}
                w))
  (println "wrote" out)
  (shutdown-agents))
