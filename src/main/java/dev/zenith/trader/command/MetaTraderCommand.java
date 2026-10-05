package dev.zenith.trader.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import dev.zenith.trader.MetaTraderConfig;
import dev.zenith.trader.module.MetaTrader;

import java.util.Locale;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ItemArgument.getItem;
import static com.zenith.command.brigadier.ItemArgument.item;
import static com.zenith.command.brigadier.RegistryDataArgument.enchantment;
import static com.zenith.command.brigadier.RegistryDataArgument.getEnchantment;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;
import dev.zenith.trader.module.VillagerTrader;

/** Commands for persistent MetaTrader orchestration settings. */
public class MetaTraderCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder().name("metaTrader").category(CommandCategory.MODULE)
            .description("Controls persistent villager trade cycles and sorting configuration.")
            .usageLines("on/off", "logging on/off", "environment test/hall", "set <key> ...", "add whitelistItem <item>", "add stackableShulkingInputChest <item> <pos>", "add outputShulkerLocation <item> <pos>", "add unstackableShulkerLocation <pos>", "resetCycle")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        var root = command("metaTrader");
        root.then(literal("bulkConfigExecution").then(argument("url", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> {
            try {
                var uri = java.net.URI.create(getString(c,"url").strip());
                if (!"https".equals(uri.getScheme()) || !("cdn.discordapp.com".equals(uri.getHost()) || "media.discordapp.net".equals(uri.getHost()))) throw new IllegalArgumentException("Use a Discord HTTPS attachment link");
                var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(30)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200) throw new IllegalArgumentException("Attachment download failed: HTTP " + response.statusCode());
                var commands=response.body().lines().map(String::strip).filter(s->s.startsWith(".")).map(s->s.substring(1)).toList();
                if(commands.isEmpty()) throw new IllegalArgumentException("No dot-prefixed commands found");
                var contexts = new java.util.ArrayList<com.zenith.command.api.CommandContext>();
                for(var command:commands) {
                    if(command.toLowerCase(Locale.ROOT).contains("bulkconfigexecution")) throw new IllegalArgumentException("Nested bulk execution is not supported");
                    var ctx=com.zenith.command.api.CommandContext.create(command,c.getSource().getSource());
                    var parse=com.zenith.Globals.COMMAND.parse(ctx);
                    if(parse.getReader().canRead() || !parse.getExceptions().isEmpty() || parse.getContext().getCommand()==null) throw new IllegalArgumentException("Invalid command: " + command);
                    contexts.add(ctx);
                }
                 CommandFeedback.setBulkExecution(true);
                 try {
                     for(var ctx:contexts) com.zenith.Globals.COMMAND.execute(ctx);
                 } finally {
                     CommandFeedback.setBulkExecution(false);
                 }
                 c.getSource().getEmbed().title("Bulk configuration executed").description(contexts.size()+" commands executed in document order. Review configuration before starting MetaTrader.");
            } catch(Exception e) { c.getSource().getEmbed().title("Bulk configuration failed").description(e.getMessage()); return ERROR; }
            return OK;
        })));
        root
            .then(argument("toggle", toggle()).executes(c -> {
                META_CONFIG.enabled = getToggle(c, "toggle");
                MODULE.get(MetaTrader.class).syncEnabledFromConfig();
                PLUGIN_CONFIG.enabled = META_CONFIG.enabled;
                MODULE.get(VillagerTrader.class).syncEnabledFromConfig();
                CommandFeedback.success(c, "MetaTrader " + toggleStrCaps(META_CONFIG.enabled));
            }))
            .then(literal("resetCycle").executes(c -> {
                MODULE.get(MetaTrader.class).resetCycle();
                CommandFeedback.success(c, "MetaTrader cycle reset");
            }))
            .then(literal("logging").then(argument("toggle", toggle()).executes(c -> {
                META_CONFIG.loggingEnabled = getToggle(c, "toggle");
                CommandFeedback.success(c, "MetaTrader logging " + toggleStrCaps(META_CONFIG.loggingEnabled));
                return OK;
            })))
            .then(literal("environment").then(argument("mode", wordWithChars()).executes(c -> {
                try {
                    META_CONFIG.environmentMode = MetaTraderConfig.EnvironmentMode.valueOf(getString(c, "mode").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return ERROR;
                }
                CommandFeedback.success(c, "MetaTrader environment set to " + META_CONFIG.environmentMode.name().toLowerCase(Locale.ROOT));
                return OK;
            })))
            ;
        var add = literal("add");
        add.then(literal("whitelistItem").then(argument("item", item()).executes(c -> {
                META_CONFIG.whitelistItems.add(getItem(c, "item").name());
                CommandFeedback.success(c, "Whitelist item added");
             })));

        // SIGNIFICANT OVERHAUL: explicit sorting coordinates replace the old
        // region-only station configuration. Lists preserve command order.
        addItemPosition(add, "stackableShulkingInputChest", META_CONFIG.stackableShulkingInputChests);
        addPosition(add, "shulkerIngredientsChest", META_CONFIG.shulkerIngredientsChests);
        add.then(literal("unstackableShulkerLocation").then(argument("pos", blockPos()).executes(c -> { META_CONFIG.unstackableShulkerLocation = getBlockPos(c, "pos"); CommandFeedback.success(c, "Unstackable shulker location set"); return OK; })));
        addPosition(add, "leftoverEmptyShulkerChest", META_CONFIG.leftoverEmptyShulkerChests);
        addPosition(add, "stackableShulkerOutputChest", META_CONFIG.stackableShulkerOutputChests);
        addPosition(add, "grindstone", META_CONFIG.grindstones);
        addPosition(add, "bookshelf", META_CONFIG.bookshelves);
        addItemPosition(add, "outputShulkerLocation", META_CONFIG.outputShulkerLocations);
        add.then(literal("outputShulkerLocationEnchantedBook").then(argument("item", item()).then(argument("enchantment", enchantment()).then(argument("level", integer(1)).then(argument("pos", blockPos()).executes(c -> {
            var key = getItem(c, "item").name() + "|" + getEnchantment(c, "enchantment").name() + "|" + getInteger(c, "level");
            META_CONFIG.outputShulkerLocationEnchantments.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(getBlockPos(c, "pos"));
             CommandFeedback.success(c, "Enchanted-book output shulker location added"); return OK;
        }))))));
          add.then(literal("raidFarmTotemOutputChest").then(argument("pos", blockPos()).executes(c -> { META_CONFIG.raidFarmTotemOutputChest = getBlockPos(c, "pos"); CommandFeedback.success(c, "Raid totem output chest set"); return OK; })));
         addPosition(add, "rocketChest", META_CONFIG.rocketChests);
        addStoreBounds(add, "storeChestBounds");
        root.then(add);
        var del = literal("del");
        del.then(literal("whitelistItem").then(argument("item", item()).executes(c -> {
            META_CONFIG.whitelistItems.remove(getItem(c, "item").name());
             CommandFeedback.success(c, "Whitelist item removed"); return OK;
        })));
        del.then(literal("raidFarmTotemOutputChest").then(argument("pos", blockPos()).executes(c -> {
            if (META_CONFIG.raidFarmTotemOutputChest.equals(getBlockPos(c, "pos"))) META_CONFIG.raidFarmTotemOutputChest = com.zenith.mc.block.BlockPos.ZERO;
            CommandFeedback.success(c, "Raid totem output chest removed"); return OK;
        })));
        delPosition(del, "rocketChest", META_CONFIG.rocketChests);
        delItemPosition(del, "stackableShulkingInputChest", META_CONFIG.stackableShulkingInputChests);
        delPosition(del, "shulkerIngredientsChest", META_CONFIG.shulkerIngredientsChests);
         del.then(literal("unstackableShulkerLocation").executes(c -> { META_CONFIG.unstackableShulkerLocation = com.zenith.mc.block.BlockPos.ZERO; CommandFeedback.success(c, "Unstackable shulker location cleared"); return OK; })
            .then(argument("pos", blockPos()).executes(c -> {
                if (META_CONFIG.unstackableShulkerLocation.equals(getBlockPos(c, "pos"))) META_CONFIG.unstackableShulkerLocation = com.zenith.mc.block.BlockPos.ZERO;
                 CommandFeedback.success(c, "Unstackable shulker location removed"); return OK;
            })));
        delPosition(del, "leftoverEmptyShulkerChest", META_CONFIG.leftoverEmptyShulkerChests);
        delPosition(del, "stackableShulkerOutputChest", META_CONFIG.stackableShulkerOutputChests);
        delPosition(del, "grindstone", META_CONFIG.grindstones);
        delPosition(del, "bookshelf", META_CONFIG.bookshelves);
        delItemPosition(del, "outputShulkerLocation", META_CONFIG.outputShulkerLocations);
        del.then(literal("outputShulkerLocationEnchantedBook").then(argument("item", item()).then(argument("enchantment", enchantment()).then(argument("level", integer(1)).then(argument("pos", blockPos()).executes(c -> {
            var key = getItem(c, "item").name() + "|" + getEnchantment(c, "enchantment").name() + "|" + getInteger(c, "level");
            var list = META_CONFIG.outputShulkerLocationEnchantments.get(key);
            if (list != null) { list.remove(getBlockPos(c, "pos")); if (list.isEmpty()) META_CONFIG.outputShulkerLocationEnchantments.remove(key); }
             CommandFeedback.success(c, "Enchanted-book output shulker location removed"); return OK;
        }))))));
        delStoreBounds(del);
        root.then(del);
        root.then(literal("metaTraderConfigureAnchorEnderChestOld").then(argument("pos", blockPos()).executes(c -> {
            META_CONFIG.anchorEnderChestOld = getBlockPos(c, "pos");
             CommandFeedback.success(c, "Old hall anchor recorded"); return OK;
        })));
        root.then(literal("metaTraderSetConfigStandard").executes(c -> {
            META_CONFIG.standardLayoutConfigured = true;
             CommandFeedback.success(c, "Current configuration recorded as standard layout"); return OK;
        }));
        root.then(literal("metaTraderConfigureAnchorEnderChestNew").then(argument("pos", blockPos()).executes(c -> {
            META_CONFIG.anchorEnderChestNew = getBlockPos(c, "pos");
             CommandFeedback.success(c, "New hall anchor recorded", "Standard-layout export is available from the saved configuration; use the generated commands with translated coordinates.");
            return OK;
        })));
        var set = literal("set");
        var key = argument("key", wordWithChars());
        key.then(argument("value", wordWithChars()).executes(c -> {
                setScalar(getString(c, "key"), getString(c, "value"));
                 CommandFeedback.success(c, "MetaTrader setting updated");
            }));
        key.then(argument("first", blockPos()).executes(c -> {
            var name = getString(c, "key").toLowerCase(Locale.ROOT);
            var pos = getBlockPos(c, "first");
            switch (name) {
                case "hallreturn" -> META_CONFIG.hallReturn = pos;
                case "raidreturn", "raidlanding" -> { META_CONFIG.raidReturn = pos; META_CONFIG.raidLanding = pos; }
                case "flightlaunch" -> META_CONFIG.flightLaunch = pos;
                case "halllanding" -> META_CONFIG.hallLanding = pos;
                 case "ominousbottlechest" -> META_CONFIG.ominousBottleChest = pos;
                case "unstackableshulkerlocation" -> META_CONFIG.unstackableShulkerLocation = pos;
                default -> { return ERROR; }
            }
             CommandFeedback.success(c, "MetaTrader coordinate updated");
            return OK;
        }).then(argument("second", blockPos()).executes(c -> {
            setRegion(getString(c, "key"), new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second")));
             CommandFeedback.success(c, "MetaTrader region updated");
         })));
        set.then(key);
        root.then(set);
        var route = literal("route");
        for (String name : new String[]{"hallToRaidSoulRoute", "raidToHallSoulRoute", "hallToRaidBlueIceRoute", "raidToHallBlueIceRoute"}) {
            route.then(literal("del").then(literal(name).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> {
                var region = new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second"));
                switch (name) {
                    case "hallToRaidSoulRoute" -> META_CONFIG.hallToRaidSoulRoute.remove(region);
                    case "raidToHallSoulRoute" -> META_CONFIG.raidToHallSoulRoute.remove(region);
                    case "hallToRaidBlueIceRoute" -> META_CONFIG.hallToRaidBlueIceRoute.remove(region);
                    case "raidToHallBlueIceRoute" -> META_CONFIG.raidToHallBlueIceRoute.remove(region);
                }
                 CommandFeedback.success(c, "Route segment removed"); return OK;
            })))));
            route.then(literal("add").then(literal(name).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> {
                var region = new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second"));
                switch (name) {
                    case "hallToRaidSoulRoute" -> META_CONFIG.hallToRaidSoulRoute.add(region);
                    case "raidToHallSoulRoute" -> META_CONFIG.raidToHallSoulRoute.add(region);
                    case "hallToRaidBlueIceRoute" -> META_CONFIG.hallToRaidBlueIceRoute.add(region);
                    case "raidToHallBlueIceRoute" -> META_CONFIG.raidToHallBlueIceRoute.add(region);
                }
                 CommandFeedback.success(c, "Route segment added");
                return OK;
             })))));
        }
        route.then(literal("clear").then(argument("name", wordWithChars()).executes(c -> {
            switch (getString(c, "name")) {
                case "hallToRaidSoulRoute" -> META_CONFIG.hallToRaidSoulRoute.clear();
                case "raidToHallSoulRoute" -> META_CONFIG.raidToHallSoulRoute.clear();
                case "hallToRaidBlueIceRoute" -> META_CONFIG.hallToRaidBlueIceRoute.clear();
                case "raidToHallBlueIceRoute" -> META_CONFIG.raidToHallBlueIceRoute.clear();
                default -> { return ERROR; }
            }
             CommandFeedback.success(c, "Route cleared");
            return OK;
        })));
        root.then(route);
        return root;
    }

    private void setScalar(String key, String value) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "shulkerstocraftforstackablesort" -> META_CONFIG.shulkersToCraftForStackableSort = Integer.parseInt(value);
            case "herovillageminimumseconds" -> META_CONFIG.heroVillageMinimumSeconds = Integer.parseInt(value);
            case "raidfarmenabled" -> META_CONFIG.raidFarmEnabled = Boolean.parseBoolean(value);
            case "inventorycleanupenabled" -> META_CONFIG.inventoryCleanupEnabled = Boolean.parseBoolean(value);
            case "inputscontinuouslyprefilled" -> META_CONFIG.inputsContinuouslyPrefilled = Boolean.parseBoolean(value);
            case "expectedminimumtps" -> META_CONFIG.expectedMinimumTps = Integer.parseInt(value);
            case "expectedmaximumtps" -> META_CONFIG.expectedMaximumTps = Integer.parseInt(value);
            case "hallflightaltitude" -> META_CONFIG.hallFlightAltitude = Integer.parseInt(value);
            case "flightdescentstartfraction" -> META_CONFIG.flightDescentStartFraction = Double.parseDouble(value);
            case "flightarrivalradius" -> META_CONFIG.flightArrivalRadius = Integer.parseInt(value);
            case "flightbridgeenabled" -> META_CONFIG.flightBridgeEnabled = Boolean.parseBoolean(value);
            case "elytratravelenabled" -> META_CONFIG.elytraTravelEnabled = Boolean.parseBoolean(value);
            case "flightcruisey" -> META_CONFIG.flightCruiseY = Integer.parseInt(value);
            case "flightapproachradius" -> META_CONFIG.flightApproachRadius = Integer.parseInt(value);
            case "flightminimumelytradurability" -> META_CONFIG.flightMinimumElytraDurability = Integer.parseInt(value);
            case "flightreturnminimumelytradurability" -> META_CONFIG.flightReturnMinimumElytraDurability = Integer.parseInt(value);
            case "flighttimeoutseconds" -> META_CONFIG.flightTimeoutSeconds = Integer.parseInt(value);
            case "flightlandingtolerance" -> META_CONFIG.flightLandingTolerance = Double.parseDouble(value);
            case "groundtravelmode" -> META_CONFIG.groundTravelMode = MetaTraderConfig.GroundTravelMode.valueOf(value.toUpperCase(Locale.ROOT));
            case "loggingenabled" -> META_CONFIG.loggingEnabled = Boolean.parseBoolean(value);
            case "environmentmode" -> META_CONFIG.environmentMode = MetaTraderConfig.EnvironmentMode.valueOf(value.toUpperCase(Locale.ROOT));
            case "environmentdiagnostics" -> META_CONFIG.environmentDiagnostics = Boolean.parseBoolean(value);
            case "environmentsnapshotintervalticks" -> META_CONFIG.environmentSnapshotIntervalTicks = Integer.parseInt(value);
            default -> throw new IllegalArgumentException("Unknown MetaTrader setting: " + key);
        }
    }

    private void setRegion(String key, MetaTraderConfig.Region region) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "hallboatpark" -> META_CONFIG.hallBoatPark = region;
            case "raidfarmboatpark" -> META_CONFIG.raidFarmBoatPark = region;
            case "hallbounds" -> META_CONFIG.hallBounds = region;
            case "nonstackabledumpchestbounds" -> META_CONFIG.nonStackableDumpChestBounds = region;
            case "unstackablefinalstoragearea" -> META_CONFIG.unstackableFinalStorageArea = region;
            default -> throw new IllegalArgumentException("Unknown MetaTrader region: " + key);
        }
    }

    private void addPosition(LiteralArgumentBuilder<CommandContext> parent, String name, java.util.ArrayList<com.zenith.mc.block.BlockPos> list) {
        parent.then(literal(name).then(argument("pos", blockPos()).executes(c -> { var pos = getBlockPos(c, "pos"); if (!list.contains(pos)) list.add(pos); CommandFeedback.success(c, name + " added"); return OK; })));
    }
    private void delPosition(LiteralArgumentBuilder<CommandContext> parent, String name, java.util.ArrayList<com.zenith.mc.block.BlockPos> list) {
        parent.then(literal(name).then(argument("pos", blockPos()).executes(c -> { list.remove(getBlockPos(c, "pos")); CommandFeedback.success(c, name + " removed"); return OK; })));
    }
    private void addItemPosition(LiteralArgumentBuilder<CommandContext> parent, String name, java.util.LinkedHashMap<String, java.util.ArrayList<com.zenith.mc.block.BlockPos>> map) {
        parent.then(literal(name).then(argument("item", item()).then(argument("pos", blockPos()).executes(c -> { var list = map.computeIfAbsent(getItem(c, "item").name(), k -> new java.util.ArrayList<>()); var pos = getBlockPos(c, "pos"); if (!list.contains(pos)) list.add(pos); CommandFeedback.success(c, name + " added"); return OK; }))));
    }
    private void delItemPosition(LiteralArgumentBuilder<CommandContext> parent, String name, java.util.LinkedHashMap<String, java.util.ArrayList<com.zenith.mc.block.BlockPos>> map) {
        parent.then(literal(name).then(argument("item", item()).then(argument("pos", blockPos()).executes(c -> { var key = getItem(c, "item").name(); var values = map.get(key); if (values != null) { values.remove(getBlockPos(c, "pos")); if (values.isEmpty()) map.remove(key); } CommandFeedback.success(c, name + " removed"); return OK; }))));
    }
    private void addStoreBounds(LiteralArgumentBuilder<CommandContext> parent, String name) {
        parent.then(literal(name).then(argument("item", item()).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> { var key = getItem(c, "item").name(); META_CONFIG.storeChestBounds.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second"))); CommandFeedback.success(c, "Store chest bounds added"); return OK; })))));
    }
    private void delStoreBounds(LiteralArgumentBuilder<CommandContext> parent) {
        parent.then(literal("storeChestBounds").then(argument("item", item()).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> { var key = getItem(c, "item").name(); var values = META_CONFIG.storeChestBounds.get(key); if (values != null) { values.remove(new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second"))); if (values.isEmpty()) META_CONFIG.storeChestBounds.remove(key); } CommandFeedback.success(c, "Store chest bounds removed"); return OK; })))));
    }
}

