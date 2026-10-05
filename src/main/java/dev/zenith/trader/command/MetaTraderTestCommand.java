package dev.zenith.trader.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import dev.zenith.trader.MetaTraderConfig;
import dev.zenith.trader.module.MetaTrader;

import static com.zenith.Globals.MODULE;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;
import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ItemArgument.getItem;
import static com.zenith.command.brigadier.ItemArgument.item;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public class MetaTraderTestCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder().name("test").category(CommandCategory.MODULE)
            .description("Runs isolated MetaTrader retrieval, deposit, processing, sorting, and travel tests.")
            .usageLines("retrieve set <item> <count> <first> <second>", "retrieve on/off",
                "deposit set <item> <count> <first> <second>", "deposit on/off",
                "stackableSortingCycle on/off", "nonstackableSortingCycle on/off", "metaTraderStandalone on/off", "flight on/off", "flightHome on/off", "raid on/off", "raidStandalone on/off", "theresNoPlaceLikeHome on/off")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        var root = command("test");
        root.then(literal("status").executes(c -> {
            CommandFeedback.success(c, "MetaTrader test status", META_CONFIG.testMode.isEmpty() ? META_CONFIG.lastTestResult : META_CONFIG.testMode);
            return OK;
        }));
        root.then(literal("cancel").executes(c -> {
            MODULE.get(MetaTrader.class).cancelTest();
            CommandFeedback.success(c, "MetaTrader test cancelled");
            return OK;
        }));
        addTransferCommands(root, "retrieve", "inputRetrieval");
        addTransferCommands(root, "deposit", "outputDeposit");
        root.then(literal("ShulkerCrafting").then(argument("count", integer(1)).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> {
            start(c, "ShulkerCrafting", "", "", getInteger(c, "count"), region(c));
        })))));
        for (String mode : new String[]{"stackableSortingCycle", "nonstackableSortingCycle", "metaTraderStandalone", "flight", "flightHome", "raid", "raidStandalone", "theresNoPlaceLikeHome"}) {
            root.then(literal(mode).then(argument("toggle", com.zenith.command.brigadier.ToggleArgumentType.toggle()).executes(c -> {
                if (!com.zenith.command.brigadier.ToggleArgumentType.getToggle(c,"toggle")) { MODULE.get(MetaTrader.class).cancelTest(); CommandFeedback.success(c, "MetaTrader test disabled"); return OK; }
                start(c, mode.equals("flight") ? "flightToRaid" : mode.equals("flightHome") ? "flightToHall" : mode, "", "", 0, null); return OK;
            })));
        }
        return root;
    }

    private void addTransferCommands(LiteralArgumentBuilder<CommandContext> root, String command, String mode) {
        root.then(literal(command).then(literal("set").then(argument("item", item())
            .then(argument("count", integer(1)).then(argument("first", blockPos()).then(argument("second", blockPos()).executes(c -> {
                META_CONFIG.testItem = getItem(c, "item").name();
                META_CONFIG.testCount = getInteger(c, "count");
                META_CONFIG.testRegion = region(c);
                META_CONFIG.testContainer = "minecraft:chest";
                CommandFeedback.success(c, command + " test configured");
                return OK;
            })))))));
        root.then(literal(command).then(argument("toggle", com.zenith.command.brigadier.ToggleArgumentType.toggle()).executes(c -> {
            if (!com.zenith.command.brigadier.ToggleArgumentType.getToggle(c, "toggle")) {
                MODULE.get(MetaTrader.class).cancelTest();
                return OK;
            }
            start(c, mode, META_CONFIG.testItem, META_CONFIG.testContainer, META_CONFIG.testCount, META_CONFIG.testRegion);
            return OK;
        })));
    }

    private void start(com.mojang.brigadier.context.CommandContext<CommandContext> c, String mode, String item, String container, int count, MetaTraderConfig.Region region) {
        MODULE.get(MetaTrader.class).startTest(mode, item, container, count, region);
        CommandFeedback.success(c, "MetaTrader test started: " + mode);
    }

    private MetaTraderConfig.Region region(com.mojang.brigadier.context.CommandContext<CommandContext> c) {
        return new MetaTraderConfig.Region(getBlockPos(c, "first"), getBlockPos(c, "second"));
    }
}
