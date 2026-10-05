# Alpha 1.0.11 testing

Build: `gradlew.bat clean build`. Configuration export uses
`python tools/generate_hall_commands.py` when the private source document
`pirateBayConfig/piratebayTradingHallConfigCommandList.md` is present. That
private source document is not included in this package, so the export step is
not runnable from the distributed directory alone.

Outbound `.test flight` and return `.test flightHome` have passed in-game.
The updated raid workflow requires the checks below. Build success alone does
not establish gameplay correctness.

1. Verify supply refill picks nearest configured rocket/ingredient chests.
2. Run `.test flight on` from inside the hall: it walks to `flightLaunch`,
   glides to `raidLanding` without rockets and stops there. Then, at the raid
   farm, run `.test flightHome on`: it climbs, should report about y160 at the
   approach radius in the `flight` events and lands on `hallLanding`. Attach
   both diagnostic TXT files. `.test cancel` aborts a flight in the air (the bot
   keeps gliding without control, so cancel only near the ground).
3. Run `.test raidStandalone on`: from the raid farm, confirm exactly one
     amplifier-4 bottle is retrieved anywhere in the player inventory, moved to
     the hotbar, and consumed, KillAura targets mobs, a sword
    is selected, the timer lasts 5½ minutes, and all but one totem deposits into
    the configured output chest before KillAura turns off. The reserved totem
    remains in the offhand. Whitelist required food; swords are retained automatically.
4. Run `.test raid on`: confirm the same sequence is bracketed by the tested
    outbound and home flight routes, and finishes without trading or sorting.
5. Verify the 40-minute deadline pauses trading and preserves trade counts.
   During sorting, expiry must defer refresh until sorting completes.
6. Run standalone trading and both sorting tests using the real configured hall. Check
   acknowledged inventory transfers, processing, pickup, hopper timing and TPS.
7. Check death/disconnect/cancel handling and diagnostics before resuming.
8. Verify health ≤10 and the first self totem pop trigger AutoDisconnect.
9. Verify add/del pairs remove only the specified entries, for normal and test
    trades, whitelist, sorting/supply positions and route segments. Check on/off
    toggles, including test cancellation.
10. For the 1.0.10 regression, inspect the diagnostic for
    `bottle-retrieval-wait` if the server inventory update is delayed. The test
    must not fail merely because the bottle entered the main inventory rather
    than the hotbar.

Use `.test status`, `.test cancel`, or a test's `off` toggle. World/inventory
changes already performed are not undone by cancellation.

## Multi-chest transfer tests

```text
.test retrieve set <item> <count> <posChest1> <posChest2>
.test retrieve on
.test deposit set <item> <count> <posChest1> <posChest2>
.test deposit on
```

The tests walk the configured chests, confirm inventory deltas, and continue
to the next chest when the current chest has no source item or capacity.
