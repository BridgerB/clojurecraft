(ns clojurecraft.filler
  "The blocks a bot lays and seals with: which item to lay from what is held, with what each
   caller keeps back. Values in, values out: every function reads the inventory of a world and
   chooses an item name."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.path :as path]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def fillers
  "The items a floor is laid from, what a mine carries most of first: cobblestone, the stones that
   drop themselves, then dirt, then netherrack (what a descent in the Nether digs), then the
   Nether's own rock a tunnel digs and carries. Never gravel or sand, which fall out of a floor."
  [:cobblestone :cobbled_deepslate :andesite :diorite :granite :tuff :dirt :netherrack :soul_soil
   :blackstone :basalt])

(defn planks-of
  "The first planks item among the held item counts that has n or more, or nil: a block that does
   not fall, the last thing a floor or a seal is laid from."
  [held n]
  (->> held
       (filter (fn [[item c]] (and (>= c n) (str/ends-with? (name item) "_planks"))))
       (map first)
       first))

(def last-resorts
  "Blocks that do not fall and are laid when nothing in fillers is held: the bricks a bastion's
   loot gives and the soul sand a floor of the Nether is dug out as."
  [:polished_blackstone_bricks :cracked_polished_blackstone_bricks :soul_sand])

(def pickaxe-cobble 3) ; cobblestone a building filler never lays: a stone pickaxe's, for the one a tunnel breaks

(def stone-materials
  "The blocks a stone pickaxe is made of (the item tag stone_tool_materials): what a filler keeps
   back for one (stone-kept)."
  #{:cobblestone :cobbled_deepslate :blackstone})

(def pickaxe-spares 2) ; pickaxes under which the stone of one more is kept back (stone-kept)

(defn pickaxes-held
  "How many pickaxes of any kind the held item counts hold."
  [held]
  (reduce + 0 (keep (fn [[item c]] (when (str/ends-with? (name item) "_pickaxe") c)) held)))

(defn stone-kept
  "The stone (stone-materials, together) no filler lays: pickaxe-cobble while fewer than
   pickaxe-spares pickaxes are held, else 0. Two real stage-18 bots on 4170b7d wore out their last
   pickaxe holding a table and sticks and no cobblestone, deepslate or blackstone: every block of
   it had gone into floors, seals and cover."
  [held]
  (if (< (pickaxes-held held) pickaxe-spares) pickaxe-cobble 0))

(defn spendable?
  "May n of item be laid from the held item counts: it is no stone material, or the stone left
   after it (stone-materials together) is stone-kept or more."
  [held item n]
  (or (not (contains? stone-materials item))
      (>= (- (reduce + 0 (vals (select-keys held stone-materials))) n) (stone-kept held))))

(defn pick
  "The first of fillers the inventory holds n or more of and may lay (spendable?), else a planks
   item it holds n or more of, else the first of last-resorts it holds n or more of, or nil.
   Planks and last-resorts are fallbacks only: carried counts none of them, so a descent never
   spends them."
  [world n]
  (let [held (recipe/counts (:player/inventory world))]
    (or (first (filter #(and (>= (get held % 0) n) (spendable? held % n)) fillers))
        (planks-of held n)
        (first (filter #(>= (get held % 0) n) last-resorts)))))

(def cobble-reserve 8) ; cobblestone a building filler keeps for seals and cover: fireball-proof, and the one block the Nether does not give


(defn pickaxe-reserve
  "The cobblestone kept back for a replacement pickaxe: pickaxe-cobble when the sticks to make one
   are held (two), else 0 (the cobblestone alone makes none)."
  [held]
  (if (>= (get held :stick 0) 2) pickaxe-cobble 0))

(defn building
  "The filler a tunnel's floors and steps are laid from, or nil: the Nether's own rock first (a
   tunnel digs hundreds), then the rest of fillers in order, cobblestone only above n plus
   cobble-reserve, then any filler (cobblestone above n plus pickaxe-cobble), planks and the last
   resorts: a tunnel is not stopped for the reserve, but the cobblestone of one stone pickaxe is
   never laid, nor the last stone of one while pickaxes are short (spendable?)."
  [world n]
  (let [held (recipe/counts (:player/inventory world))
        need (fn [item] (if (= :cobblestone item) (+ n cobble-reserve) n))]
    (or (first (filter #(and (>= (get held % 0) (need %)) (spendable? held % n))
                       (cons :netherrack (remove #{:netherrack} fillers))))
        (first (filter #(and (>= (get held % 0)
                                 (if (= :cobblestone %) (+ n (pickaxe-reserve held)) n))
                             (spendable? held % n))
                       fillers))
        (planks-of held n)
        (first (filter #(>= (get held % 0) n) last-resorts)))))

(defn carried
  "How many of every filler the inventory holds, together."
  [world]
  (reduce + 0 (vals (select-keys (recipe/counts (:player/inventory world)) fillers))))

(defn walkable
  "The filler a step's floor at cell is laid from: building's item when a block of it laid at cell
   stands as a floor (path/floor?), else nil. Soul sand, the last resort a hand-dug tunnel
   carries, is no floor to a route (path treats it as awkward)."
  [world cell]
  (let [item (building world 1)]
    (when (and item
               (path/floor? (assoc-in world [:world/blocks cell] (blocks/default-state item)) cell))
      item)))
