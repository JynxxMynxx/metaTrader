# ZenithProxy MetaTrader Alpha 1.0.11

An extension of rfresh2's Villager Trader for persistent output-target trade
cycles, configured storage, output processing, and hall workflow coordination.

## Documentation

- **[User guide](METATRADER_OVERVIEW.md)** â€” start here for actual behavior and
  the current cycle and verification status.
- **[Piratebay setup commands](pirateBayConfig/piratebayTradingHallConfigCommandList.md)** â€”
  authoritative Alpha 1.0.11 setup commands.
- **[Command reference](COMMAND_REFERENCE.md)** â€” current configuration syntax.
- **[Testing](TESTING.md)** â€” build and in-world validation.
- **[Change notes](CHANGELOG.md)** â€” significant configuration changes.

Trade input and output use explicit coordinate lists and nearest-container
selection. Type-based container settings have been removed. MetaTrader refills
shells, chests, and rockets from configured supply chests, and test trades are
configured separately with `.trader add test ...` and `.trader set test ...`.
Use `.test metaTraderStandalone on/off` for the same trade/post-trade script
without Hero-of-the-Village seeking; sorting tests remain isolated.
See [the Alpha 1.0.11 update notes](UPDATE_1.0.11.md), [the 1.0.10 assessment](TEST_LOG_ASSESSMENT_1.0.10.md), [changelog](CHANGELOG.md), and
[command reference](COMMAND_REFERENCE.md) for the raid, standalone-test, and flight updates.

## Build and installation

Minecraft 1.21.4 / ZenithProxy, Java 21+ runtime. The Gradle build uses a Java 25
toolchain and targets Java 21.

```text
gradlew.bat clean build
```

Copy the shadow JAR from `build/libs/` to ZenithProxy's plugin directory and
restart. Configuration is persisted through ZenithProxy.

`tools/generate_hall_commands.py` validates the authoritative user-edited command
document and exports its commands to TXT without overwriting it. The private
source document is not included in this package; provide it locally before
running the exporter. It contains private world coordinates, so review it
before publishing the project.

The upstream license is in `LICENSE`.

