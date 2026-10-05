# Alpha 1.0.11 inventory acknowledgement and hotbar handling

- Raid bottle retrieval now validates the acknowledged item-count increase
  across the full player inventory, including stack merges and main-inventory
  slots, and waits through delayed inventory updates.
- Required consumables and tools are promoted from the main inventory to a
  selected hotbar slot before use. This covers ominous bottles, swords,
  rockets, axes, and pickaxes.

# Alpha 1.0.9 elytra flight rework

- Replaced the old corridor planner (`baritone zenith elytra flight bridge`, no
  longer compiled) with `dev.zenith.trader.flight`: a receding-horizon planner
  that simulates Zenith's own elytra and rocket physics every tick.
- Outbound: walk to `flightLaunch` on the hall roof, sneak to the block edge,
  standing jump, open the elytra and glide the straight line to `raidLanding`
  in the slow nose-up regime (about -30 pitch, 0.34 blocks/tick). That line is
  the glide angle (17 degrees for the Piratebay route). No rockets are used.
- Return: walk onto `raidLanding`, take off, climb with rockets fired only at
  the top of each zoom climb and only when no unpowered glide can reach the
  cruise height in time, be at `flightCruiseY` (160) by `flightApproachRadius`
  (50 blocks) from `hallLanding`, then descend the straight approach line.
- Landing: a backwards-facing flare before touchdown and a backwards-facing
  brake while the server ends the glide stop the slide within about a block.
- New settings: `flightLaunch`, `raidLanding` (`raidReturn` is now an alias),
  `hallLanding`, `flightCruiseY`, `flightApproachRadius`,
  `flightMinimumElytraDurability` (round trip), `flightReturnMinimumElytraDurability`,
  `flightTimeoutSeconds`, `flightLandingTolerance`. New test: `.test flightHome on|off`.
- During a flight AutoEat and AutoArmor are paused (restored afterwards) so food
  cannot replace the rockets in hand and armour cannot replace the elytra. A
  flight stopped mid-air leaves the bot in the slow glide until it is down.
- AutoTotem is re-enabled after the raid totem deposit (it previously stayed off).
- Flight telemetry (phase changes, rockets, a position/pitch/reference line
  every half second, the landing result) is written to the MetaTrader
  diagnostic session as `flight` and `flight-result` events, so it appears in
  the Discord TXT attachment like the other diagnostics.

# Alpha 1.0.9 changes

- Fixed inventory restock operations stalling after opening a chest.
- Added server-state settling time before reading newly opened containers and
  after withdrawal actions.
- Capped effective flight cruise height at 160 blocks for low-TPS testing.
- Flight replanning now uses each tick's observed position and velocity, waits
  for measured propulsion to settle, and avoids firing rockets while propulsion
  is still active.
- Added shulker-shell and chest whitelist commands to the test configuration.

## Alpha 1.0.9 changes

- Default `hallReturn` is now `-2012057 141 -1657332`.
- Inventory actions now fail fast when Zenith rejects the request instead of
  treating a completed future as a successful transfer.
- Container reads wait for several ticks after opening so low-TPS screen data
  is settled before sorting or restocking proceeds.
- Villager interaction paths now require an accepted entity interaction and a
  settled merchant screen before selecting or purchasing a trade. Rejected
  interactions and purchases are recorded as diagnostic events.
- Flight recovery replans after an early touchdown using the observed position
  instead of aborting immediately.
- Every active MetaTrader test records a throttled tick/event session and sends
  a downloadable TXT diagnostic attachment when the test completes, fails, or
  is cancelled.

## Alpha 1.0.7 changes

- Created as a clean update from the tested 1.0.5 project.
- Includes command feedback, standalone-test fixes, and flight tuning from the
  1.0.6 test results.
- Added corrected `pirateBayConfigList.txt` and `testConfigList.txt` command
  documents.

## Alpha 1.0.5 changes

- Integrated the supplied Zenith-native flight planner into the MetaTrader JAR.
- Added a shared raid cycle and `.test raid on/off` round-trip test.
- Bottle retrieval begins only after confirmed landing at raidReturn.
- Retrieves one level V bottle, drinks it, runs hostile-only KillAura for 5Â½
  minutes, then disables KillAura before totem deposit and return flight.
- Keeps one offhand totem and one spare in the main inventory; deposits excess.
- Enables health AutoDisconnect at â‰¤10 and first-self-totem-pop disconnect.
- Refreshes after 40 minutes at the hall; active sorting completes first.
- Added list-based nearest rocket sources and expanded inventory retention.
- Replaced raidHeliPad with raidReturn; removed helipad, dedicated firework-chest
  and raidFarmBounds command forms.
- Audited command inverses: added whitelist, raid totem chest, coordinate-specific
  unstackable location and individual route-segment removals. All toggles accept
  on/off; normal/test trade chest lists already supported add/del.
- Fresh documentation and updated configuration documents in pirateBayConfig.

The outbound and return flight tests have passed in-game. The updated raid
sequence remains pending live validation. Automatic anchor translation remains incomplete.
# Alpha 1.0.10 raid workflow

- Added `.test raidStandalone on|off` for local raid-feature testing.
- Completed `.test raid on|off`: flight, level-V bottle retrieval and drinking,
  mob KillAura, sword selection, 5½-minute combat window, excess-totem deposit,
  KillAura shutdown, and return flight.
- Restores prior KillAura configuration and custom targets after completion or cancellation.
# 1.0.12

- Unified hotbar preparation for all required item-use operations, including
  full-hotbar swapping.
- Fixed raid bottle acknowledgement and sword selection diagnostics.
- Added multi-chest `.test retrieve` and `.test deposit` configuration and run
  commands; removed the old transfer test commands from the documented test
  interface.
