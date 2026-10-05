# Alpha 1.0.11 capabilities and gaps

Implemented: coordinate-based trading, output targets, processing, supply refill,
stackable/non-stackable sorting, embedded Elytra flight, level V bottle retrieval,
timed mob-targeting KillAura, totem deposit, and 40-minute raid refresh.

KillAura stays enabled through the 5½-minute combat window and totem deposit,
then is disabled before return flight. An inventory sword is explicitly selected.
Totem deposit retains one totem total, moving all excess to the configured output chest.
AutoDisconnect defaults to health â‰¤10 or the first self totem pop; its off
toggles remain respected. All add commands have corresponding del commands.
Bottle retrieval starts only after confirmed landing at `raidLanding` for `.test raid`;
`.test raidStandalone` starts at the configured bottle chest from the current location.
Sorting finishes before an overdue raid refresh; trading resumes afterward.

Outbound and return flight tests have passed in-game. Updated raid features,
trading, and sorting still require in-game verification. Anchor recording exists; automatic translated layout export
is still incomplete. Legacy processed-output/crafting tests are partial.

See [update notes](UPDATE_1.0.11.md), [changelog](CHANGELOG.md), and [testing](TESTING.md).

