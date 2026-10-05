# 1.0.10 test-log assessment

The flight portion was not implicated: the diagnostic reached the raid test at
the expected position and remained in `phase=TRADING`. The failure occurred
immediately after the bottle event and was recorded as `failed:r`.

Manual inspection confirmed the bottle existed in the player inventory. The
failure was therefore caused by an overly strict retrieval acknowledgement and
by assuming a retrieved usable item would already be in the hotbar. Version
1.0.11 changes the acknowledgement to an inventory-wide, delta-based check
with a short wait for delayed server updates, and promotes required items to a
selected hotbar slot before use.
