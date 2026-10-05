# Alpha 1.0.12 update

## Reliable item use and transfer tests

Version 1.0.12 applies one hotbar-preparation rule to every item-use path:

- search the hotbar and then the main inventory for the required item;
- fail with a diagnostic when it is unavailable;
- when the hotbar is full, swap hotbar slot 1 with the inventory item so the
  displaced item remains in the main inventory;
- select the resulting hotbar slot before using the item.

This covers ominous bottles, swords, axes, pickaxes, and flight rockets. Raid
standalone now waits for the inventory-wide bottle delta, equips the bottle,
holds right-click for three seconds, equips the sword, and reports `nosword`
when no sword is available.

The transfer tests were replaced with configurable multi-chest commands:

```text
.test retrieve set <item> <count> <posChest1> <posChest2>
.test retrieve on
.test deposit set <item> <count> <posChest1> <posChest2>
.test deposit on
```

Retrieval continues across configured chests until the requested count is
confirmed in the player inventory. Deposit continues across chests until the
requested count is confirmed as removed from the player inventory, and fails
with a diagnostic when the configured containers cannot satisfy the request.
