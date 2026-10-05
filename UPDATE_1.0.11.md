# Alpha 1.0.11 update

## Raid retrieval and hotbar reliability

The 1.0.10 diagnostic showed that `.test raidStandalone on` retrieved the
level-V ominous bottle, but reported `failed:r` while the item was present in
the player inventory. Version 1.0.11 fixes this by:

- accepting an acknowledged inventory increase even when the item merges into
  an existing stack or lands outside the hotbar;
- waiting for the inventory acknowledgement instead of failing on a transient
  count snapshot;
- moving bottles, swords, rockets, axes, and pickaxes found in the main
  inventory into hotbar slot 1 and selecting that slot before use;
- selecting an already-hotbar item explicitly after locating it.

The existing flight behavior remains covered by `.test flight on` and
`.test flightHome on`. Re-run `.test raidStandalone on` from the raid farm and
confirm the bottle is consumed, the sword is selected, combat completes, and
the reserved totem remains in the offhand.
