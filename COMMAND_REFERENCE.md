# ZenithProxy MetaTrader Alpha 1.0.11 command reference

## Normal operation

```text
.metaTrader on
.metaTrader off
.metaTrader resetCycle
.metaTrader logging on|off
.metaTrader environment test|hall
.metaTrader add whitelistItem <item>
.metaTrader bulkConfigExecution <Discord attachment URL>
```

Bulk accepts fresh HTTPS attachment links hosted by `cdn.discordapp.com` or
`media.discordapp.net`. Upload a TXT or Markdown command list to Discord, copy
its attachment link, and pass it directly. Only dot-prefixed lines execute;
syntax is checked before execution. Nested bulk commands are rejected. The proxy
must have network access and the attachment link must not have expired.

```text
.metaTrader bulkConfigExecution https://cdn.discordapp.com/attachments/123/456/piratebayTradingHallConfigCommandList.txt?ex=...
```

## Sorting (add and del)

Every `add` command has a matching `del`. For list entries, replace `add` with
`del` and keep the same item/enchantment/coordinates. Trade creation is removed
with `.trader del [test] <id>`. Enchantment rules are removed with
`.trader set [test] <id> outputEnchants del <enchantment>`.

```text
.metaTrader del whitelistItem <item>
.metaTrader del raidFarmTotemOutputChest <pos>
.metaTrader del unstackableShulkerLocation <pos>
.metaTrader route del <routeName> <pos1> <pos2>
.trader del [test] <id> outputChest <pos>
.metaTrader del shulkerIngredientsChest <pos>
```

All toggle commands accept both `on` and `off`, including controller, logging,
trader, per-trade enablement, itemProcessing and all toggle-based tests. Scalar
boolean settings under `set` use `true`/`false`.

```text
.metaTrader add stackableShulkingInputChest <item> <x> <y> <z>
.metaTrader add outputShulkerLocation <item> <x> <y> <z>
.metaTrader add outputShulkerLocationEnchantedBook <item> <enchantment> <level> <x> <y> <z>
.metaTrader add shulkerIngredientsChest <x> <y> <z>
.metaTrader add leftoverEmptyShulkerChest <x> <y> <z>
.metaTrader add unstackableShulkerLocation <x> <y> <z>
.metaTrader set unstackableFinalStorageArea <x1> <y1> <z1> <x2> <y2> <z2>
.metaTrader add storeChestBounds <item> <pos1> <pos2>
.metaTrader add bookshelf <x> <y> <z>
.metaTrader add grindstone <x> <y> <z>
.metaTrader set nonStackableDumpChestBounds <pos1> <pos2>
.metaTrader set shulkersToCraftForStackableSort <count>
```

Use `minecraft:glass`, `minecraft:glowstone`, `minecraft:enchanted_book`.
Crafting tables are found locally. Old sorting stations/regions, non-stackable
craft-count setting, generic placement lists and global enchantment checks have
been removed. Non-stackable filled boxes are stored by hoppers automatically.

## Trade settings

Original `.trader` commands remain (`.trader set help`). Added forms:

```text
.trader add <id> <profession> <input1> <output>
.trader add <id> <profession> <input1> <output> <inputPos> <outputPos>
.trader add <id> <profession> <input1> <input2> <output> <inputPos1> <inputPos2> <outputPos>
.trader set <id> outputPerCycleCount <count>
.trader set <id> itemProcessing on|off
.trader add <id> inputItem1Chest <x> <y> <z>
.trader add <id> inputItem2Chest <x> <y> <z>
.trader add <id> outputItemChest <x> <y> <z>
```

The first form creates a one-input trade without chest positions; positions can
be added afterward with the `inputItem1Chest` and `outputItemChest` commands.
The second form creates a one-input trade and assigns its initial input and
output positions immediately. Two-input trades use the third form, where all
three positions are required at creation time; additional containers can then
be added with `inputItem1Chest`, `inputItem2Chest`, and `outputItemChest`.
Insert `test` after `add` (for example, `.trader add test ...`) to create an
isolated test trade. Manual chest forms also support `del`.

## Direct travel/resource positions

```text
.metaTrader set hallReturn <pos>
.metaTrader add rocketChest <pos>
.metaTrader set raidReturn <pos>    (alias of raidLanding)
.metaTrader set ominousBottleChest <pos>
.metaTrader add raidFarmTotemOutputChest <pos>
.metaTrader set hallBounds <pos1> <pos2>
```

### Elytra flight

```text
.metaTrader set flightLaunch <pos>      roof block to glide from (default -2012057 141 -1657332)
.metaTrader set raidLanding <pos>       raid farm landing/takeoff block (default -2011851 63 -1657483)
.metaTrader set hallLanding <pos>       roof block the return flight lands on (default -2012079 141 -1657322)
.metaTrader set flightCruiseY <y>       minimum height at the approach radius on the way back (160)
.metaTrader set flightApproachRadius <blocks>   start of the final descent to hallLanding (50)
.metaTrader set flightMinimumElytraDurability <n>   needed to start the round trip (100)
.metaTrader set flightReturnMinimumElytraDurability <n>   needed to start the return alone (40)
.metaTrader set flightTimeoutSeconds <s>   (240)
.metaTrader set flightLandingTolerance <blocks>   fail if landed further away (12)
```

The outbound glide follows the straight line from `flightLaunch` to
`raidLanding`; its slope is the glide angle. The return flight climbs with as
few rockets as possible, is at `flightCruiseY` by `flightApproachRadius` blocks
from `hallLanding`, then descends straight to it. Hold rockets in the main hand
or offhand (MetaTrader equips them) and wear an elytra.

`raidLanding` is the clear, solid landing point at the raid farm. MetaTrader
refills supplies, flies there, drinks a level V ominous bottle, enables hostile
KillAura for 5Â½ minutes, disables KillAura, deposits totems, and flies back.
The refresh deadline is 40 minutes after the confirmed return.
Anchor recording commands remain under `.metaTrader`; translation is pending.

## Tests

```text
.test stackableSortingCycle on|off
.test nonstackableSortingCycle on|off
.trader add test <id> <profession> <input1> <output>
.trader set test <id> <setting> <value>
.test metaTraderStandalone on|off
.test flight on|off
.test flightHome on|off
.test status
.test cancel
.test retrieve set <item> <count> <posChest1> <posChest2>
.test retrieve on|off
.test deposit set <item> <count> <posChest1> <posChest2>
.test deposit on|off
.test ShulkerCrafting <count> <pos1> <pos2>
.test raid on|off
.test raidStandalone on|off
.test theresNoPlaceLikeHome on|off
```

The new cycle tests use current bot configuration and real inventory/world.
Standalone skips Hero refresh and sorting. `.test raid` runs the full round trip
without trading/sorting; `.test flight` walks to `flightLaunch`, glides and lands
on `raidLanding`; `.test flightHome` takes off from `raidLanding` (walking onto it
first) and lands on `hallLanding`. Legacy processed and
crafting tests remain partial. The two isolated flight tests have passed in-game;
the updated raid sequence requires live validation.

`.test raidStandalone on` starts at bottle retrieval from your current location
and stops after depositing totems; neither flight stage runs. Both raid tests
retrieve and drink exactly one level-V bottle, enable KillAura for all mobs but
not players, equip a sword, wait 5½ minutes, deposit all but one offhand totem,
then disable KillAura. They do not run the general supply-refill step. Bring a
sword; for the full raid, also bring a usable Elytra and return-flight rockets.
The bottle chest is `ominousBottleChest`; the totem output setting is
`raidFarmTotemOutputChest`. Use `off` or `.test cancel` to stop and restore the
temporary combat/AutoTotem settings. The two isolated flight tests have passed
in-game; the updated raid sequence requires live validation.

