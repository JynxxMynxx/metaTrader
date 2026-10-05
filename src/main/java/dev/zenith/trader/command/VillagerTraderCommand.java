package dev.zenith.trader.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.*;
import dev.zenith.trader.VillagerTraderConfig;
import dev.zenith.trader.module.VillagerTrader;
import java.util.*;
import static com.zenith.Globals.MODULE;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.BlockPosArgument.*;
import static com.zenith.command.brigadier.ItemArgument.*;
import static com.zenith.command.brigadier.RegistryDataArgument.*;
import static com.zenith.command.brigadier.ToggleArgumentType.*;

/** Identical command trees for production and isolated test trades. */
public class VillagerTraderCommand extends Command {
    @Override public CommandUsage commandUsage() {
        return CommandUsage.builder().name("trader").category(CommandCategory.MODULE)
            .description("Configure coordinate-only trades. Insert test after add/set/del/list/clear for test trades.")
            .usageLines("on/off", "add [test] <id> <profession> <input1> <output>",
                "add [test] <id> <profession> <input1> <output> <inputPos> <outputPos>",
                "add [test] <id> <profession> <input1> <input2> <output> <inputPos1> <inputPos2> <outputPos>",
                "set help", "list [test]", "clear [test]", "del [test] <id>").build();
    }
    @Override public LiteralArgumentBuilder<CommandContext> register() {
        var root = command("trader");
        root.then(argument("toggle", toggle()).executes(c -> {
            PLUGIN_CONFIG.enabled = getToggle(c,"toggle"); MODULE.get(VillagerTrader.class).syncEnabledFromConfig(); CommandFeedback.success(c, "Villager trader " + (PLUGIN_CONFIG.enabled ? "enabled" : "disabled")); return OK;
        }));
        root.then(literal("waitForInteractTimeout").then(argument("ticks", integer(1)).executes(c -> { PLUGIN_CONFIG.waitForInteractTimeoutTicks=getInteger(c,"ticks"); CommandFeedback.success(c, "Interaction timeout updated"); return OK; })));
        root.then(literal("logTradeStatusToDiscord").then(argument("toggle",toggle()).executes(c -> { PLUGIN_CONFIG.logTradeStatusToDiscord=getToggle(c,"toggle"); CommandFeedback.success(c, "Trade status logging " + (PLUGIN_CONFIG.logTradeStatusToDiscord ? "enabled" : "disabled")); return OK; })));
        for (String operation : List.of("add","set","del","list","clear")) {
            var branch = literal(operation);
            configure(branch, operation, PLUGIN_CONFIG.trades);
            var test = literal("test"); configure(test, operation, PLUGIN_CONFIG.testTrades);
            branch.then(test); root.then(branch);
        }
        return root;
    }
    private void changed() { MODULE.get(VillagerTrader.class).onTradeListChange(); }
    private void configure(LiteralArgumentBuilder<CommandContext> parent, String op, LinkedHashMap<String,VillagerTraderConfig.Trade> trades) {
        if (op.equals("list")) { parent.executes(c -> { CommandFeedback.success(c, "Trades", trades.keySet().toString()); return OK; }); return; }
        if (op.equals("clear")) { parent.executes(c -> { trades.clear(); changed(); CommandFeedback.success(c, "Trades cleared"); return OK; }); return; }
        var id = argument("id",wordWithChars());
        if (op.equals("del")) id.executes(c -> { var removed=trades.remove(getString(c,"id")); changed(); CommandFeedback.success(c, removed == null ? "Trade not found" : "Trade deleted"); return removed == null ? ERROR : OK; });
        if (op.equals("add")) {
            var output = argument("output",item());
            output.executes(c -> { return create(c,trades,false,false); });
            output.then(argument("inputPos",blockPos()).then(argument("outputPos",blockPos()).executes(c -> { return create(c,trades,false,true); })));
            var first = argument("input1",item()); first.then(output);
            first.then(argument("input2",item()).then(argument("output",item()).then(argument("inputPos",blockPos()).then(argument("inputPos2",blockPos()).then(argument("outputPos",blockPos()).executes(c -> { return create(c,trades,true,true); }))))));
            id.then(argument("profession",enumStrings(VillagerTrader.VillagerProfession.values())).then(first));
        }
        for (String name : List.of("inputItem1Chest","inputItem2Chest","outputItemChest","outputChest")) {
            if (op.equals("add") || op.equals("del")) id.then(literal(name).then(argument("pos",blockPos()).executes(c -> {
                var t=trades.get(getString(c,"id")); if(t==null)return ERROR;
                var list=name.equals("inputItem1Chest")?t.inputItem1Chests:name.equals("inputItem2Chest")?t.inputItem2Chests:t.outputChests;
                var p=getBlockPos(c,"pos"); boolean changedList=op.equals("del") ? list.remove(p) : list.add(p); changed(); CommandFeedback.success(c, (op.equals("del") ? "Trade container removed" : "Trade container added")); return changedList || !op.equals("del") ? OK : ERROR;
            })));
        }
        if(op.equals("set")) {
            parent.then(literal("help").executes(c -> { CommandFeedback.success(c, "Trade settings", "Use set [test] <id> with item fields, profession, chest coordinates, restock stacks/thresholds, outputPerCycleCount, maxInput1/2PerTrade, itemProcessing, postTradeStore or outputEnchants."); return OK; }));
            id.then(argument("toggle",toggle()).executes(c -> { var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.enabled=getToggle(c,"toggle");changed();CommandFeedback.success(c, "Trade " + (t.enabled ? "enabled" : "disabled"));return OK; }));
            for(String name:List.of("inputItem1","inputItem2","outputItem")) id.then(literal(name).then(argument("item",item()).executes(c -> {
                var t=trades.get(getString(c,"id"));if(t==null)return ERROR;var value=getItem(c,"item").name();
                switch(name){case "inputItem1"->t.inputItem1=value;case "inputItem2"->t.inputItem2=value;default->t.outputItem=value;} changed();CommandFeedback.success(c, "Trade item updated");return OK;
            })));
             id.then(literal("profession").then(argument("value",enumStrings(VillagerTrader.VillagerProfession.values())).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.villagerProfession=VillagerTrader.VillagerProfession.valueOf(getString(c,"value").toUpperCase(Locale.ROOT));changed();CommandFeedback.success(c, "Trade profession updated");return OK;})));
             id.then(literal("itemProcessing").then(argument("toggle",toggle()).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.itemProcessing=getToggle(c,"toggle");CommandFeedback.success(c, "Trade item processing updated");return OK;})));
             id.then(literal("postTradeStore").then(argument("value",enumStrings(VillagerTraderConfig.Trade.PostTradeStoreMode.values())).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.postTradeStoreMode=VillagerTraderConfig.Trade.PostTradeStoreMode.valueOf(getString(c,"value").toUpperCase(Locale.ROOT));CommandFeedback.success(c, "Trade post-storage updated");return OK;})));
             for(String name:List.of("inputItem1Chest","inputItem2Chest","outputChest","overflowChest"))id.then(literal(name).then(argument("pos",blockPos()).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;var p=getBlockPos(c,"pos");switch(name){case "inputItem1Chest"->{t.inputItem1Chest=p;t.inputItem1Chests.clear();}case "inputItem2Chest"->{t.inputItem2Chest=p;t.inputItem2Chests.clear();}case "outputChest"->{t.outputChest=p;t.outputChests.clear();}default->t.overflowChestPos=p;}changed();CommandFeedback.success(c, "Trade setting updated");return OK;})));
             for(String name:List.of("inputItem1RestockStacks","inputItem2RestockStacks","inputItem1RestockCountThreshold","inputItem2RestockCountThreshold","outputItemStoreCountThreshold","maxInput1PerTrade","maxInput2PerTrade","outputPerCycleCount"))id.then(literal(name).then(argument("count",integer(1,name.endsWith("Stacks")?35:Integer.MAX_VALUE)).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;try{VillagerTraderConfig.Trade.class.getField(name).setInt(t,getInteger(c,"count"));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}CommandFeedback.success(c, "Trade numeric setting updated");return OK;})));
            var ench=literal("outputEnchants");
             ench.then(literal("add").then(argument("enchantment",enchantment()).then(argument("level",integer(1)).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.outputItemEnchantments.put(getEnchantment(c,"enchantment").name(),getInteger(c,"level"));CommandFeedback.success(c,"Trade enchantment added");return OK;}))));
             ench.then(literal("del").then(argument("enchantment",enchantment()).executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.outputItemEnchantments.remove(getEnchantment(c,"enchantment").name());CommandFeedback.success(c,"Trade enchantment removed");return OK;})));
             ench.then(literal("clear").executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;t.outputItemEnchantments.clear();CommandFeedback.success(c,"Trade enchantments cleared");return OK;}));
             ench.then(literal("list").executes(c->{var t=trades.get(getString(c,"id"));if(t==null)return ERROR;CommandFeedback.success(c,"Enchantments",t.outputItemEnchantments.toString());return OK;})); id.then(ench);
        }
        parent.then(id);
    }
    private int create(com.mojang.brigadier.context.CommandContext<CommandContext> c, LinkedHashMap<String,VillagerTraderConfig.Trade> trades, boolean two, boolean positions) {
        var t=new VillagerTraderConfig.Trade();t.villagerProfession=VillagerTrader.VillagerProfession.valueOf(getString(c,"profession").toUpperCase(Locale.ROOT));
        t.inputItem1=getItem(c,"input1").name();t.outputItem=getItem(c,"output").name();if(two)t.inputItem2=getItem(c,"input2").name();
        if(positions){t.inputItem1Chest=getBlockPos(c,"inputPos");t.outputChest=getBlockPos(c,"outputPos");if(two)t.inputItem2Chest=getBlockPos(c,"inputPos2");}
         trades.put(getString(c,"id"),t);changed();CommandFeedback.success(c,"Trade added");return OK;
    }
}
