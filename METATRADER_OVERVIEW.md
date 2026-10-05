# MetaTrader Alpha 1.0.11 user guide

Configure trades with `.trader` and the controller with `.metaTrader`.
Use the [Piratebay command list](pirateBayConfig/piratebayTradingHallConfigCommandList.md)
with MetaTrader off, then start with `.metaTrader on`.

## Startup and raid

Refill rockets, shells and chests to 64 from nearest configured supply locations.
Walk to `flightLaunch` on the hall roof and glide the straight line to
`raidLanding` (no rockets), and wait for confirmed landing before opening the
nearby ominous-bottle chest. Retrieve one level V bottle (amplifier
4), drink it for 60 acknowledged bot ticks, enable KillAura for all mobs (not players),
and select an inventory sword. After 5½ minutes, deposit excess totems while
retaining exactly one in the offhand, disable KillAura, then fly home.
The return flight takes off from `raidLanding`, climbs with the fewest rockets
it can, is at `flightCruiseY` within `flightApproachRadius` of `hallLanding` and
lands there. The 40-minute deadline starts at confirmed return. Expiry pauses trading for a
new raid; sorting completes first if already running. Trade counts are retained.

## Trading and sorting

Trade chest lists are coordinate-based. Purchased-output targets drive each
trade's completion. Outputs can be processed into panes, books or disenchanted
equipment. Finished trading leads to stackable sorting, then non-stackable sorting.
Shells/chests refill below 16; rockets refill below 16 after raid return.
Empty shulkers are reused before crafting. Full stackable boxes go to item storage
bounds; non-stackables use the configured working position and final storage.
Sorting progress and replacement state are saved. Unknown sorting items pause
with diagnostics. Stationary breaking/placing uses temporary permissions.

Standard retained items include axes, pickaxes, swords, panes, emeralds, shells, shulker
boxes, chests, rockets, ominous bottles and totems. Configured whitelist and
trade items are also retained. Inventory cleanup expels other items when inventory
actions can safely run. Equip an Elytra and whitelist required food/weapons.

## Tests

`.test raid on/off` walks to the launch point, flies to the raid farm, retrieves
and drinks one level-V ominous bottle, enables KillAura for mobs, selects a sword,
runs for 5½ minutes, deposits all but one totem, disables KillAura, and flies home.
`.test raidStandalone on/off` runs only the bottle, combat, timed wait, and totem
deposit stages from the current location; it does not use either flight route.
`.test flight on/off` flies to `raidLanding`. Sorting tests exercise the configured
real-world pipeline. Standalone trading uses separately configured test trades and
skips raids and sorting. `.test status` reports results; `.test cancel` stops.

The outbound `.test flight` and return `.test flightHome` routes have been
successfully tested in-game. The updated raid stages still need in-game verification.
AutoDisconnect is enabled at health â‰¤10 and the first self totem pop; automatic
reconnect is cancelled. Every add command has a corresponding del, and every
toggle accepts on/off; see the command reference for removal syntax.
See [testing](TESTING.md), [command reference](COMMAND_REFERENCE.md), and
[update notes](UPDATE_1.0.11.md), [1.0.10 assessment](TEST_LOG_ASSESSMENT_1.0.10.md), and [changelog](CHANGELOG.md). Automatic anchor translation remains incomplete.

