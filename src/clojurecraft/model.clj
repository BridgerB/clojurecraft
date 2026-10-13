(ns clojurecraft.model
  "The information model, in writing: every attribute the bot knows, with a one-line meaning and
   when it exists. docs/hickey.md starts here (\"Write the attribute list with namespaced names
   and one-line meanings. Everything after this is a function over that model.\"). It is data, so
   model_test can hold it in step with the code: every key a running bot writes is listed, and
   every world attribute listed has a spec. An attribute never changes meaning; a new meaning is
   a new name beside it.")

(set! *warn-on-reflection* true)

(def attributes
  "[attribute meaning when] rows, grouped by what the fact is about."
  [;; ---- the bot itself and its connection
   [:bot/phase
    "protocol phase: :handshake, :login, :configuration or :play; moves only in game/emit" "always"]
   [:bot/name "player name" "once given, by init or the :start event"]
   [:bot/uuid "offline UUID derived from the name" "once the name is given"]
   [:bot/effects "effects the last event asked for; the loop performs and clears them" "always"]
   [:bot/sequence "last block-interaction sequence number sent" "always"]
   [:bot/closed "why the socket closed" "after it closed"]
   [:bot/disconnected "the server's disconnect reason" "after a disconnect"]
   [:conn/host "server host" "once given, by init or the :start event"]
   [:conn/port "server port" "once given, by init or the :start event"]
   ;; ---- time, which enters only on ticks
   [:time/now "ms of the last tick event" "always"] [:time/tick "ticks seen so far" "always"]
   ;; ---- facts about itself
   [:player/pos "feet-centre position [x y z]" "from the first teleport"]
   [:player/vel "velocity per tick [vx vy vz]; vy is about -0.078 at rest" "always"]
   [:player/look "[yaw pitch] in degrees" "always"]
   [:player/on-ground? "last vertical move hit ground while falling" "always"]
   [:player/horizontal-collision? "last horizontal move was blocked" "always"]
   [:player/jump-ticks "ticks until a jump is allowed again" "always"]
   [:player/fall-distance "blocks fallen since the last landing or liquid; vanilla hurts past 3"
    "after the first physics tick"]
   [:player/in-water? "the body (contracted) overlaps water, by the physics this tick"
    "after the first physics tick"]
   [:player/in-lava? "the body (contracted) overlaps lava, by the physics this tick"
    "after the first physics tick"]
   [:player/eye-in-water? "the eye (y + 1.62) is inside water, by the physics this tick"
    "after the first physics tick"]
   [:plan/wet-ticks "consecutive ticks the eye has been under water" "while planning"]
   [:plan/dry-ticks "consecutive ticks on dry land (trap/dry-land?)" "while planning"]
   [:plan/trap "the trap the player is in, :water or :lava, with hysteresis (trap/trapped-tick)"
    "while trapped"] [:plan/trap-at "the [x z] column where the current trap began" "while trapped"]
   [:world/traps
    "every trap escaped: {:trap/pos [x z] :trap/kind :trap/at}, for routes to keep away from"
    "after the first escape"] [:trap/pos "the column [x z] a trap began in" "inside :world/traps"]
   [:trap/kind "what the trap was, :water or :lava" "inside :world/traps"]
   [:trap/at "ms the trap cleared" "inside :world/traps"]
   [:intent/bank "the cell a :swim-out swims to, chosen once" "while a :swim-out has one"]
   [:intent/wet? "a :wade has had the body in water at some point" "while a :wade runs"]
   [:intent/water "the water cell a :wade enters from its bank (:intent/target)"
    "while a :wade has an entry"]
   [:intent/dig-when-down "the cell a :swim-out will dig once it has sunk to the floor"
    "while a :swim-out sinks"]
   [:intent/next-search "the tick a :swim-out with no bank yet looks for one again"
    "while a :swim-out rises with no bank"]
   [:intent/exposes-ok?
    "a :dig that may open a cell beside water or lava (an escape digs beside the water it is in)"
    "on an escape's child dig"]
   [:intent/passage-ok?
    "a :place that may put its block in a passage, since it is dug again at once (a gravel for flint)"
    "on a redig's child place"]
   [:player/air "breath left, 0-300 ticks: an estimate, down 1 under water, up 4 out"
    "after the first physics tick"]
   [:player/dead? "health reached zero; cleared by the server's respawn" "while dead"]
   [:player/loaded? "player_loaded was sent: the server lets the bot act" "always"]
   [:player/dimension
    "the dimension the last respawn named (\"minecraft:the_nether\"); the overworld before one"
    "after a respawn"]
   [:player/controls "what the planner wants physics to do next tick (:control/*)" "always"]
   [:player/inventory "{player-slot {:item id :count n}}: the server's word, slots 0-40" "always"]
   [:player/held-slot "selected hotbar slot 0-8" "always"]
   [:player/entity-id "the bot's entity id" "from the play login packet"]
   [:player/teleport-id "id of the last teleport accepted" "from the first teleport"]
   [:player/synced-at "ms of the last teleport" "from the first teleport"]
   [:player/health "health from set_health" "after the first set_health"]
   [:control/forward? "walk forward" "inside :player/controls"]
   [:control/jump? "jump when allowed" "inside :player/controls"]
   [:control/yaw "heading to walk on" "inside :player/controls"]
   [:control/look "[yaw pitch] to face" "inside :player/controls"]
   [:control/sneak? "sneak: in a liquid, swim down" "inside :player/controls"]
   ;; ---- the player's screen
   [:window/grid "the 2x2 crafting grid of window 0: {cell item}, never inventory" "always"]
   [:window/cursor "the item on the cursor" "while something is carried"]
   [:window/state-id "window 0's last server state id" "after the first window 0 content"]
   [:window/open "the open container {:window/id :window/menu-type ...}" "while one is open"]
   [:window/id "container window id" "inside :window/open"]
   [:window/menu-type "container menu type id (12 is a crafting table)" "inside :window/open"]
   [:window/slots "the open container's slots" "inside :window/open"]
   [:window/data
    "the open container's numbered properties {property value} (a furnace: 0 fuel left, 1 fuel total, 2 cook progress, 3 cook total)"
    "inside :window/open, once the server sends one"]
   ;; ---- the world as last seen, and as remembered
   [:world/chunks "{[cx cz] column}: chunks the server has sent and not forgotten" "always"]
   [:world/blocks "{[x y z] state}: block updates and own breaks over the chunks" "always"]
   [:world/entities "{entity-id {:entity/type :entity/pos :entity/seen-at}}: tracked items"
    "always"]
   [:world/facts "memory: DataScript value of append-only observations, never retracted" "always"]
   [:entity/type "entity type id (71 is an item)" "inside :world/entities"]
   [:entity/pos "last known entity position" "inside :world/entities"]
   [:entity/seen-at "ms the entity was added" "inside :world/entities"]
   [:sight/pos "an observation's block position" "a fact in :world/facts"]
   [:sight/state "the state id observed there" "a fact in :world/facts"]
   [:sight/at "ms of the observation" "a fact in :world/facts"]
   [:pool/chunk "the chunk [cx cz] a pool fact summarises" "a fact in :world/facts"]
   [:pool/kind "the pool's fluid, :water or :lava" "a fact in :world/facts"]
   [:pool/pos "the chunk's highest surface source of that fluid, nearest its centre"
    "a fact in :world/facts"] [:pool/at "ms the pool was seen" "a fact in :world/facts"]
   [:intention/id "which intent a fact is about (its :intent/id)" "a fact in :world/facts"]
   [:intention/event "what happened to it: :started :done :failed or :abandoned"
    "a fact in :world/facts"] [:intention/kind "the intent's kind" "a fact in :world/facts"]
   [:intention/at "ms it happened" "a fact in :world/facts"]
   [:intention/target "the intent's target" "a fact, when the intent had one"]
   [:intention/recipe "the intent's recipe" "a fact, when the intent had one"]
   [:intention/reason "why it failed or was abandoned (:goal-met, :preempted)"
    "a :failed or :abandoned fact"]
   [:answer/kind
    "which server answer to the bot's own action: :ack, :pickup, or :death (health reached zero)"
    "a fact in :world/facts"] [:answer/at "ms the answer arrived" "a fact in :world/facts"]
   [:answer/sequence "the block-interaction sequence the server acknowledged" "an :ack fact"]
   [:answer/entity "the item entity the bot picked up" "a :pickup fact"]
   [:answer/count "how many items that pickup gave" "a :pickup fact"]
   ;; ---- the server's answers to the bot's own actions, and counts
   [:stats/keep-alives "keep-alives answered" "always"]
   [:stats/teleports "teleports accepted" "always"] [:stats/chunks "chunk columns loaded" "always"]
   [:stats/unknown "{[phase id] n}: packets with no handler" "always"]
   [:stats/last-ack "last block-changed-ack sequence from the server" "after the first ack"]
   [:stats/pickups "take-item-entity packets seen" "after the first pickup"]
   [:stats/deaths "times health fell to zero from above it" "after the first death"]
   [:net/sent-pos "position in the last movement packet" "after the first movement packet"]
   [:net/sent-look "look in the last movement packet" "after the first movement packet"]
   [:net/sent-tick "tick of the last movement packet" "after the first movement packet"]
   ;; ---- its own intentions
   [:plan/status ":landing, :active, :done or :failed" "from the :go event"]
   [:plan/goals "the target goal ids in play" "from the :go event"]
   [:plan/chain?
    "a run that plays every step up to its target (--chain true): reached steps stay reached"
    "a chained :go"]
   [:plan/reached "the targets a chained run has reached; choose skips them (plan/reach)"
    "a chained plan"] [:plan/go-at "the landing a fixture named" "from a :go with :go/at"]
   [:plan/since "ms the status last changed" "from the :go event"]
   [:plan/blacklist "targets that failed, skipped when choosing" "from the :go event"]
   [:plan/trunk-failures
    "failed gather intents per trunk column [x z]; a column that failed twice is skipped when choosing a log"
    "from the :go event"]
   [:plan/attempts "intent failures in a row, a walk that failed :no-path aside (:plan/unwalkable)"
    "from the :go event"]
   [:plan/unwalkable "walks in a row that failed :no-path; max-unwalkable of them fail the plan"
    "from the :go event"]
   [:plan/boxed-at
    "feet cells where a staircase died :boxed: no descent starts there again, no stair steps onto one"
    "after a :boxed staircase"]
   [:plan/intents "intents started so far; the next one's :intent/id" "after the first intent"]
   [:plan/intent "the intent being executed" "while one runs"]
   [:plan/last "the last intent that ended, with its outcome" "after one ends"]
   [:plan/reason "why the plan failed" "after it failed"]
   [:plan/waiting "why the planner is waiting" "after the first wait"]
   [:plan/waiting-since "ms the current wait began" "while waiting"]
   [:plan/wait "a planner answer meaning: nothing to do yet, for this reason"
    "returned by next-intent, never stored"]
   [:intent/kind
    "executor: :walk :dig :collect :craft :place :open-container :stairs-down :stairs-up :tunnel :tunnel-to :smelt :redig :scoop :equip :build :pour :use-on :await"
    "every intent"] [:intent/status ":active, :done or :failed" "every intent"]
   [:intent/id "the intent's number in this run, linking its facts in memory"
    "every started intent"]
   [:intent/goal "the target goal id the intent serves; another target chosen preempts it"
    "intents the planner starts"]
   [:intent/reason "why it failed, or why the planner abandoned it" "a failed or abandoned intent"]
   [:intent/target "block position it acts on or walks to" "most intents"]
   [:intent/for
    "what the intent serves (:log, a mining row's id), so chains continue only their own"
    "gather-chain intents"]
   [:intent/pickup?
    "a walk toward a block to mine from beside: it ends where the dug block's drop lands in the pickup box"
    "a :mine walk"]
   [:intent/step-down?
    "a :mine walk toward a block in the floor: it may end a block up beside it, the drop landing in the hole the dig leaves"
    "a :mine walk to a block that leaves a step down"]
   [:intent/cell "the cell a :place puts its block in, given (a build's), not chosen"
    "a build's :place"]
   [:intent/click-face "the face of the clicked block a placement clicks, toward the cell it fills"
    ":place, once it has a spot"]
   [:intent/build "which plan a :build works: :cast-one, :portal or :nether" ":build"]
   [:intent/origin "a build's frame origin [bx by bz]" ":build"]
   [:intent/facing "the side of the frame a build works from: :south :north :east :west" ":build"]
   [:intent/op "the plan op a :build or a :tunnel-to step works now; a new op drops the child"
    ":build, :tunnel-to"]
   [:intent/aim "the point a :pour or :use-on looks at to act" ":pour, :use-on"]
   [:intent/expect "the predicate (build/holds?) that shows a :pour, :use-on or :await worked"
    ":pour, :use-on, :await"]
   [:intent/fluid "what a :scoop takes: :water (when absent) or :lava" "a :scoop for lava"]
   [:intent/steps "how many steps a :tunnel-to takes before the planner looks again" ":tunnel-to"]
   [:intent/taken "steps a :tunnel-to has taken" ":tunnel-to, after its first step"]
   [:intent/move "the step a :tunnel-to is taking, [dx dy dz], chosen when it starts"
    ":tunnel-to, during a step"]
   [:intent/from "the feet a :tunnel-to's step started from, where its ops are worked"
    ":tunnel-to, during a step"]
   [:intent/trunk "bottom log of the trunk a gather chain works; a failure blacklists it whole"
    "gather-chain intents"]
   [:intent/stage "executor stage, e.g. :settle :digging :click :verify :take" "staged intents"]
   [:intent/since "ms the stage began" "staged intents"]
   [:intent/started "ms the intent began" "walk and dig"]
   [:intent/still "ticks the bot has been still" "settling intents"]
   [:intent/face "block face it acts on" "dig"]
   [:intent/next-swing "ms of the next arm swing" "dig"]
   [:intent/finish-at "ms after which FINISH may be sent" "dig"]
   [:intent/slot "hotbar slot the dig holds its tool in (0 for the hand)" "dig, from START"]
   [:intent/tool "item id of the tool the dig holds, nil for the hand" "dig, from START"]
   [:intent/dig-ms "ms the server needs to break the target with that tool" "dig, from START"]
   [:intent/state
    "the target's state id at START; the same state after FINISH means the server refused"
    "dig, from START"]
   [:intent/confirm-left "ticks left to wait for the server's ack after FINISH"
    "dig, :confirm stage"]
   [:intent/to-y "feet height at or below which the staircase is done" "stairs-down"]
   [:intent/dir "[dx dz] heading the next stair is cut toward" "stairs-down, once planned"]
   [:intent/turns "quarter turns taken after refused stairs; four fail :boxed"
    "stairs-down, after a refusal"]
   [:intent/cells "the three cells the current stair opens, top first"
    "stairs-down, while a stair is cut"]
   [:intent/level-cells
    "the two cells a level step opens, head then feet, when every stair down was refused"
    "stairs-down, during a level step"]
   [:intent/level-steps
    "level steps this staircase (past stairs/max-level-steps it fails :boxed) or tunnel (done at its length) has taken"
    "stairs-down and tunnel, after a level step"]
   [:intent/length "level steps a tunnel cuts" "tunnel"]
   [:intent/side "the side a tunnel steps to, keeping its heading, where the heading is refused"
    "tunnel, once one is taken"]
   [:intent/level-dir
    "the way the staircase's last level step went; the next keeps it while allowed"
    "stairs-down and stairs-up, after a level step"]
   [:intent/child
    "the child intent run in the intent's place by its own executor (stairs: the :dig cutting one cell; redig: the place, dig or collect it is on)"
    "stairs-down, while digging; redig; place, while digging a niche"]
   [:intent/started-y "feet height when the current stair began; a step is judged by going below it"
    "stairs-down, per stair"]
   [:intent/started-tick "tick the current stair or its step began" "stairs-down, per stair"]
   [:intent/best-dist "closest it has come to the target" "walk and collect"]
   [:intent/best-tick "tick of the last progress" "walk and collect"]
   [:intent/detour-until "tick a detour ends" "walk, while detouring"]
   [:intent/detour-yaw "heading offset of the detour" "walk, while detouring"]
   [:intent/detours "detours taken" "walk"]
   [:intent/waypoints "the route's feet cells still to walk, from clojurecraft.path"
    "walk, once planned"]
   [:intent/route "how the route ended: :found, :partial (budget spent) or :none (no way on)"
    "walk, once planned"] [:intent/at "index of the next waypoint" "walk, once planned"]
   [:intent/replans "routes planned for this walk; more than intent/max-replans fails :no-path"
    "walk, once planned"]
   [:intent/planned-chunks
    "chunk columns loaded when the route was made (kept for the record; a new chunk no longer replans by itself)"
    "walk, once planned"]
   [:intent/logs-before "logs held when collecting began" "collect, before :intent/drops"]
   [:intent/drops
    "the item ids a collect is for: one more of any of them than it began with ends it"
    "collect, redig"]
   [:intent/drops-before "items of :intent/drops held when collecting began" "collect"]
   [:intent/blocked-by "leaf block over the drop" "collect, when the drop is covered"]
   [:intent/clears "leaf blocks cleared for this drop" "a collect resumed after clearing"]
   [:intent/resume "the intent to run after clearing a blocker" "a dig that clears a blocker"]
   [:intent/recipe "recipe id to craft" "craft"] [:intent/window ":inventory or :table" "craft"]
   [:intent/clicks "clicks still to send" "craft, :click stage; smelt, :load stage"]
   [:intent/result "result item id" "craft, smelt"]
   [:intent/makes "items one craft yields; for a smelt, the batch" "craft, smelt"]
   [:intent/before "result items held before crafting, smelting or scooping" "craft, smelt, scoop"]
   [:intent/smelt "furnace recipe id to cook (smelting.edn)" "smelt"]
   [:intent/count "how many of the input the batch cooks" "smelt"]
   [:intent/lost "clicks the server lost in this smelt; the intent resumed from its slots"
    "smelt, after a lost click"]
   [:intent/resync "the next click is a resync: the cursor after a lost click is unknown"
    "smelt, from a lost click until the resync is sent"]
   [:intent/cook-by "ms by which the batch must be cooked; the cook stage fails after it"
    "smelt, :cook stage, once the furnace is loaded"]
   [:intent/awaiting "state id a click waits on" "after a click"]
   [:intent/awaiting-window "window id that click went to" "after a click"]
   [:intent/sent-at "ms a click or placement was sent" "after sending"]
   [:intent/item "item to place" "place, redig"]
   [:intent/against "block the placement is against" "place"]
   [:intent/sequence "sequence number of the placement, or of the bucket's use"
    "place and scoop, after sending"]
   [:intent/tries "scoops sent at this source; the last of scoop/max-tries fails :no-scoop"
    "scoop, after a scoop went unanswered"]
   ;; ---- events: everything the reducer is given
   [:event/kind ":start :packet :tick :go :closed" "every event"]
   [:event/now "wall-clock ms of a tick" "tick"]
   [:event/rand "a random double in [0, 1] for this tick" "tick"]
   [:event/packet "the decoded packet" "packet"] [:event/reason "why the socket closed" "closed"]
   [:start/host "server host" "start"] [:start/port "server port" "start"]
   [:start/name "player name" "start"] [:go/goals "target goal ids to pursue" "go"]
   [:go/chain? "the goals are the steps of one run: reached ones stay reached" "a chained go"]
   [:go/at "where the fixture landed the bot" "go, from a fixture"]
   ;; ---- effects: everything the reducer asks for
   [:effect/kind ":send or :log" "every effect"] [:effect/packet "packet to send" "send"]
   [:effect/message "line to log" "log"]
   ;; ---- the race (claims and the board; values the race folds, never a bot's world)
   [:claim/by "the bot a landing cell is claimed for" "a claim"]
   [:claim/cell "the landing cell [cx cz], claims/cell-size blocks square from the race's centre"
    "a claim"]
   [:claim/granted? "whether the claims loop gave the cell: no one held it, or the claimant did"
    "a claim's answer"] [:race/target "the target a racer works now" "a board line"]
   [:race/reached "how many of the chain's targets a racer has reached" "a board line"]
   [:race/dimension "the dimension a racer is in" "a board line"]
   [:race/y "a racer's feet height" "a board line"] [:race/deaths "a racer's deaths" "a board line"]
   [:race/status "a racer's plan status" "a board line"]
   [:race/bots "how many bots raced" "a race summary"]
   [:race/ok "how many reached the target" "a race summary"]
   [:race/reasons "why the racers stopped, counted" "a race summary"]
   [:race/runs "each racer's name, outcome, milestones and the server's word" "a race summary"]
   [:race/seed "the world seed the runner's server made" "a race summary"]
   [:race/commit "the code that raced" "a race summary"]
   [:race/until "the race's target" "a race summary"]
   [:milestone/runner "the runner a milestone was reached on" "a milestone fact"]
   [:milestone/seed "that runner's world seed" "a milestone fact"]
   [:milestone/bot "the bot that reached it" "a milestone fact"]
   [:milestone/goal "the target it reached (joined :plan/reached)" "a milestone fact"]
   [:milestone/at "when, ms (:time/now)" "a milestone fact"]
   [:milestone/after-s "seconds after its plan began, its landing"
    "a milestone fact, once a plan began"]
   ;; ---- packets
   [:packet/name "which packet; its other fields are the wire's names, unqualified" "every packet"]
   [:chunk/column "the decoded column, added on the reader thread; never recorded"
    "level-chunk packets"]
   [:chunk/min-y "a column's lowest block y: -64 for 24 sections, 0 for 16 (the Nether, the End)"
    "every decoded column"]])

(def by-attribute
  "attribute → {:doc meaning :when lifetime}."
  (into {} (for [[a doc w] attributes] [a {:doc doc :when w}])))
