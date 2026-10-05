package dev.zenith.trader;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.trader.command.VillagerTraderCommand;
import dev.zenith.trader.module.VillagerTrader;
import dev.zenith.trader.module.MetaTrader;
import dev.zenith.trader.command.MetaTraderCommand;
import dev.zenith.trader.command.MetaTraderTestCommand;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "ZenithProxy Villager Trader",
    url = "https://github.com/rfresh2/ZenithProxyVillagerTrader",
    authors = {"rfresh2"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class VillagerTraderPlugin implements ZenithProxyPlugin {
    public static ComponentLogger LOG;
    public static VillagerTraderConfig PLUGIN_CONFIG;
    public static MetaTraderConfig META_CONFIG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig("villager-trader", VillagerTraderConfig.class);
        META_CONFIG = pluginAPI.registerConfig("meta-trader", MetaTraderConfig.class);
        pluginAPI.registerModule(new dev.zenith.trader.flight.ElytraFlight());
        pluginAPI.registerModule(new VillagerTrader());
        pluginAPI.registerModule(new MetaTrader());
        pluginAPI.registerCommand(new VillagerTraderCommand());
        pluginAPI.registerCommand(new MetaTraderCommand());
        pluginAPI.registerCommand(new MetaTraderTestCommand());
    }
}
