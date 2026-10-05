package dev.zenith.trader.module;

import com.github.rfresh2.EventConsumer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.inventory.Container;
import com.zenith.discord.Embed;
import com.zenith.discord.EmbedSerializer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import dev.zenith.trader.VillagerTraderConfig;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.*;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;
import static com.zenith.Globals.MODULE;

public class VillagerTrader extends Module {
    private State state = State.ENTRYPOINT;
    private final Cache<UUID, Boolean> interactedVillagersCache = CacheBuilder.newBuilder()
        .build();
    private final TradeIterator tradeIterator = new TradeIterator();
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture emeraldBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture interactWithVillagerFuture = PathingRequestFuture.rejected;
    private ClientboundMerchantOffersPacket offersPacket = null;
    private RequestFuture purchaseFuture = RequestFuture.rejected;
    private PathingRequestFuture storePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture storeDepositFuture = RequestFuture.rejected;
    private PathingRequestFuture postTradePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture postTradeDepositFuture = RequestFuture.rejected;
    private final OutputProcessor outputProcessor = new OutputProcessor();
    private final Map<String, Set<com.zenith.mc.block.BlockPos>> triedContainers = new HashMap<>();

    public void beginProtectedWorldEdit() {
        MODULE.get(MetaTrader.class).beginProtectedWorldEdit();
    }

    public void endProtectedWorldEdit() {
        MODULE.get(MetaTrader.class).endProtectedWorldEdit();
    }
    private final Timer waitForRestockTimer = Timers.tickTimer();
    private final Timer waitForInteractTimer = Timers.tickTimer();
    private int preTradeOutputCount = 0;
    private int preTradeInput1Count = 0;
    private int preTradeInput2Count = 0;
    private int outputBuyCount = 0;
    private int input1SellCount = 0;
    private int input2SellCount = 0;
    private int restockConfirmWait = 0;
    private int restockOpenWait = 0;
    private int merchantSettle;
    private UUID interactionTarget;
    private long tradeStartTime = System.nanoTime();
    private long hourlyProductionStartTime = System.nanoTime();
    private final Map<String, Integer> hourlyProduction = new LinkedHashMap<>();

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset())
        );
    }

    @Override
    public void onDisable() {
        reset();
    }

    private int getPriority() {
        return Baritone.getPriority() + 100;
    }

    private void reset() {
        triedContainers.clear();
        state = State.ENTRYPOINT;
        interactedVillagersCache.invalidateAll();
        offersPacket = null;
        waitForInteractTimer.reset();
        waitForRestockTimer.reset();
        tradeIterator.reset();
        resetTradeCounter();
        restockConfirmWait = 0;
        restockOpenWait = 0;
        hourlyProductionStartTime = System.nanoTime();
        hourlyProduction.clear();
        outputProcessor.reset();
    }

    private void resetTradeCounter() {
        preTradeOutputCount = 0;
        preTradeInput1Count = 0;
        preTradeInput2Count = 0;
        outputBuyCount = 0;
        input1SellCount = 0;
        input2SellCount = 0;
        tradeStartTime = System.nanoTime();
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("villager-trader")
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundMerchantOffersPacket.class, this::onMerchantOffers)
                .build())
            .build();
    }

    private ClientboundMerchantOffersPacket onMerchantOffers(ClientboundMerchantOffersPacket packet, ClientSession session) {
        this.offersPacket = packet;
        debug("Offers: {}", packet);
        return packet;
    }

    private void onTick(ClientBotTick event) {
        reportHourlyProductionIfDue();
        if (META_CONFIG.enabled && MODULE.get(MetaTrader.class).controlsTravel()) return;
        if (Proxy.getInstance().isInQueue() || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            state = State.ENTRYPOINT;
            return;
        }
        switch (state) {
            case ENTRYPOINT -> {
                if (!tradeIterator.hasNext())
                    return;
                if (META_CONFIG.enabled) {
                    var cleanup = MODULE.get(MetaTrader.class).cleanupInventory(tradeIterator.current());
                    if (!cleanup.isCompleted()) return;
                }
                if (META_CONFIG.enabled && META_CONFIG.completedTrades.containsKey(getTradeId(tradeIterator.current()))) {
                    tradeIterator.next();
                    return;
                }
                if (META_CONFIG.enabled && !selectContainers(tradeIterator.current())) return;
                resetTradeCounter();
                interactedVillagersCache.invalidateAll();
                setState(State.EVAL_RESTOCK);
            }
            case EVAL_RESTOCK -> {
                var trade = tradeIterator.current();
                if (META_CONFIG.enabled && trade.itemProcessing && needsOutputProcessing(trade)) {
                    setState(State.PROCESS_OUTPUT);
                    return;
                }
                if (cycleTargetReached(trade)) {
                    setState(countItem(trade.getOutputItem().id()) > 0 ? State.STORE_GO_TO_CHEST : State.READY_NEXT_TRADE);
                    return;
                }
                var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                int input1Count = countItem(input1.id());
                if (trade.inputItem1RestockCountThreshold > input1Count) {
                    setState(State.RESTOCK_INPUT_1_GO_TO_CHEST);
                    return;
                }
                if (trade.has2InputTrade()) {
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    int input2Count = countItem(input2.id());
                    if (trade.inputItem2RestockCountThreshold > input2Count) {
                        setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                        return;
                    }
                }
                var output = ItemRegistry.REGISTRY.get(trade.outputItem);
                int outputCount = countItem(output.id());
                if (outputCount > trade.outputItemStoreCountThreshold) {
                    setState(State.STORE_GO_TO_CHEST);
                    return;
                }
                setState(State.CRAFT_EMERALD_BLOCKS);
            }
            case RESTOCK_INPUT_1_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                tried("input1").add(trade.inputItem1Chest);
                restockWithdrawFuture = RequestFuture.rejected;
                restockOpenWait = 5;
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_1_WITHDRAW);
            }
            case RESTOCK_INPUT_1_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    if (restockOpenWait > 0) { restockOpenWait--; return; }
                    var trade = tradeIterator.current();
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        var actions = Lists.newArrayList(
                            InventoryActionMacros.withdraw(
                                openContainer.getContainerId(),
                                i -> {
                                    if (input1 == ItemRegistry.EMERALD) {
                                        return i.getId() == input1.id() || i.getId() == ItemRegistry.EMERALD_BLOCK.id();
                                    }
                                    return i.getId() == input1.id();
                                },
                                trade.inputItem1RestockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        var request = InventoryActionRequest.builder()
                            .owner(this)
                            .actions(actions)
                            .priority(getPriority())
                            .build();
                        restockWithdrawFuture = INVENTORY.submit(request);
                        setState(State.RESTOCK_INPUT_1_AWAIT_WITHDRAW);
                    } else {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            error("Timed out waiting for input 1 container to open");
                            setState(State.RESTOCK_INPUT_1_AWAIT_WITHDRAW);
                        }
                    }
                }
            }
            case RESTOCK_INPUT_1_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    if (restockConfirmWait++ < 5) return;
                    restockConfirmWait = 0;
                    var trade = tradeIterator.current();
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    int input1Count = countItem(input1.id());
                    if (trade.inputItem1RestockStacks * input1.stackSize() > input1Count && (input1 != ItemRegistry.EMERALD || countItem(ItemRegistry.EMERALD_BLOCK.id()) == 0)) {
                        advanceContainer(trade, true); setState(State.RESTOCK_INPUT_1_GO_TO_CHEST); return;
                    }
                    if (trade.has2InputTrade()) {
                        setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                    } else {
                        setState(State.CRAFT_EMERALD_BLOCKS);
                    }
                }
            }
            case RESTOCK_INPUT_2_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                tried("input2").add(trade.inputItem2Chest);
                restockWithdrawFuture = RequestFuture.rejected;
                restockOpenWait = 5;
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_2_WITHDRAW);
            }
            case RESTOCK_INPUT_2_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    if (restockOpenWait > 0) { restockOpenWait--; return; }
                    var trade = tradeIterator.current();
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        var actions = Lists.newArrayList(
                            InventoryActionMacros.withdraw(
                                openContainer.getContainerId(),
                                i -> {
                                    if (input2 == ItemRegistry.EMERALD) {
                                        return i.getId() == input2.id() || i.getId() == ItemRegistry.EMERALD_BLOCK.id();
                                    }
                                    return i.getId() == input2.id();
                                },
                                trade.inputItem2RestockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        var request = InventoryActionRequest.builder()
                            .owner(this)
                            .actions(actions)
                            .priority(getPriority())
                            .build();
                        restockWithdrawFuture = INVENTORY.submit(request);
                        setState(State.RESTOCK_INPUT_2_AWAIT_WITHDRAW);
                    } else {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            error("Timed out waiting for input 2 container to open");
                            setState(State.RESTOCK_INPUT_2_AWAIT_WITHDRAW);
                        }
                    }
                }
            }
            case RESTOCK_INPUT_2_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    if (restockConfirmWait++ < 5) return;
                    restockConfirmWait = 0;
                    var trade = tradeIterator.current();
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    int input2Count = countItem(input2.id());
                    if (trade.inputItem2RestockStacks * input2.stackSize() > input2Count && (input2 != ItemRegistry.EMERALD || countItem(ItemRegistry.EMERALD_BLOCK.id()) == 0)) {
                        advanceInput2(trade);
                        setState(State.RESTOCK_INPUT_2_GO_TO_CHEST); return;
                    }
                    setState(State.CRAFT_EMERALD_BLOCKS);
                }
            }
            case CRAFT_EMERALD_BLOCKS -> {
                int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockCount == 0) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                if (countInvEmptySlots() < 4) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                int emeraldBlockSlot = InventoryUtil.searchPlayerInventory(i -> i.getId() == ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockSlot == -1) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                List<InventoryAction> actions = Lists.newArrayList();
                actions.add(new PlaceRecipe(0, "minecraft:emerald", true));
                actions.add(new ShiftClick(0, ShiftClickItemAction.LEFT_CLICK));
                actions.add(new CloseContainer(0));
                emeraldBlockCraftFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(actions)
                    .priority(getPriority())
                    .build());
                setState(State.AWAIT_CRAFT_EMERALD_BLOCKS);
            }
            case AWAIT_CRAFT_EMERALD_BLOCKS -> {
                if (emeraldBlockCraftFuture.isCompleted()) {
                    int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                    if (emeraldBlockCount > 0) {
                        setState(State.CRAFT_EMERALD_BLOCKS);
                    } else {
                        setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    }
                }
            }
            case TRADING_INTERACT_WITH_VILLAGER -> {
                var trade = tradeIterator.current();
                var nextVillagerOptional = nextVillager(trade);
                if (nextVillagerOptional.isEmpty()) {
                    if (META_CONFIG.enabled) {
                        if (waitForRestockTimer.tick(200)) interactedVillagersCache.invalidateAll();
                        return;
                    }
                    if (interactedVillagersCache.asMap().isEmpty()) {
                        warn("No villagers found to trade with");
                        setState(State.READY_NEXT_TRADE);
                    } else {
                        if (countItem(trade.getOutputItem().id()) > 0) {
                            setState(State.STORE_GO_TO_CHEST);
                        } else {
                            setState(State.STORE_GO_TO_CHEST);
                        }
                    }
                    return;
                }
                var nextVillager = nextVillagerOptional.get();
                interactionTarget = nextVillager.getUuid();
                merchantSettle = 0;
                offersPacket = null;
                interactWithVillagerFuture = BARITONE.rightClickEntity(nextVillager);
                interactWithVillagerFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                interactedVillagersCache.put(nextVillager.getUuid(), true);
                setState(State.TRADING_AWAIT_INTERACT_WITH_VILLAGER);
            }
            case TRADING_AWAIT_INTERACT_WITH_VILLAGER -> {
                if (interactWithVillagerFuture.isCompleted()) {
                    if (!interactWithVillagerFuture.isAccepted()) {
                        MODULE.get(MetaTrader.class).diagnostic("VILLAGER_INTERACTION_REJECTED", interactionTarget);
                        if (interactionTarget != null) interactedVillagersCache.invalidate(interactionTarget);
                        setState(State.TRADING_INTERACT_WITH_VILLAGER);
                        return;
                    }
                    if (offersPacket == null) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            MODULE.get(MetaTrader.class).diagnostic("MERCHANT_OFFERS_TIMEOUT", interactionTarget);
                            setState(State.TRADING_INTERACT_WITH_VILLAGER);
                        }
                        return;
                    }
                    if (offersPacket.getContainerId() != CACHE.getPlayerCache().getInventoryCache().getOpenContainerId()) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.TRADING_INTERACT_WITH_VILLAGER);
                        }
                        return;
                    }
                    if (CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getType() != ContainerType.MERCHANT || ++merchantSettle < 6) return;
                    setState(State.TRADING_TRY_START_PURCHASE);
                }
            }
            case TRADING_TRY_START_PURCHASE -> {
                var trade = tradeIterator.current();
                var trades = offersPacket.getTrades();
                List<InventoryAction> actions = Lists.newArrayList();
                for (int i = 0; i < trades.length; i++) {
                    var villagerTrade = trades[i];
                    if (villagerTrade.isTradeDisabled()) continue;
                    if (villagerTrade.getOutput() == null) continue;
                    if (villagerTrade.getOutput().getId() != ItemRegistry.REGISTRY.get(trade.outputItem).id()) continue;
                    if (villagerTrade.getFirstInput().getId() != ItemRegistry.REGISTRY.get(trade.inputItem1).id()) continue;
                    if (trade.has2InputTrade()) {
                        if (villagerTrade.getSecondInput() == null) continue;
                        if (villagerTrade.getSecondInput().getId() != ItemRegistry.REGISTRY.get(trade.inputItem2).id()) continue;
                    } else {
                        if (villagerTrade.getSecondInput() != null) continue;
                    }
                    if (!trade.outputItemEnchantments.isEmpty()) {
                        if (!enchantmentFilter(trade, villagerTrade.getOutput())) {
                            continue;
                        }
                    }

                    int availableTradeCount = villagerTrade.getMaxUses() - villagerTrade.getNumUses(); // each shift click can consume many trades

                    int input1StackSize = ItemRegistry.REGISTRY.get(trade.inputItem1).stackSize();

                    int baseCostInput1 = villagerTrade.getFirstInput().getAmount();
                    int input1DemandCost = Math.max(0, MathHelper.floorI((villagerTrade.getFirstInput().getAmount() * villagerTrade.getDemand() * villagerTrade.getPriceMultiplier())));
                    int input1Cost = MathHelper.clamp(baseCostInput1 + input1DemandCost + villagerTrade.getSpecialPrice(), 1, input1StackSize);
                    if (input1Cost > trade.maxInput1PerTrade) continue;
                    int maxTradesPerInputStack = input1StackSize / input1Cost;
                    int input1Count = countItem(trade.getInputItem1().id());
                    int maxTradesForInput1 = input1Count / input1Cost;
                    int maxTradeCount = Math.min(availableTradeCount, maxTradesForInput1);

                    if (maxTradeCount == 0) {
                        info("Can't trade because not enough {} (have {}, need at least {})", ItemRegistry.REGISTRY.get(trade.inputItem1).name(), input1Count, input1Cost);
                    }

                    if (trade.has2InputTrade()) {
                        int input2StackSize = trade.has2InputTrade() ? 64 : ItemRegistry.REGISTRY.get(trade.inputItem2).stackSize();
                        int baseCostInput2 = villagerTrade.getSecondInput().getAmount();
                        int input2DemandCost = Math.max(0, MathHelper.floorI((villagerTrade.getSecondInput().getAmount() * villagerTrade.getDemand() * villagerTrade.getPriceMultiplier())));
                        int input2Cost = MathHelper.clamp(baseCostInput2 + input2DemandCost + villagerTrade.getSpecialPrice(), 1, input2StackSize);
                        if (input2Cost > trade.maxInput2PerTrade) continue;
                        int tradersPerInput2Stack = villagerTrade.getSecondInput().getAmount() / input2Cost;
                        maxTradesPerInputStack = Math.min(maxTradesPerInputStack, tradersPerInput2Stack);
                        int input2Count = countItem(trade.getInputItem2().id());
                        int maxTradesForInput2 = input2Count / input2Cost;
                        boolean hadEnoughInput1ToTrade = maxTradeCount > 0;
                        maxTradeCount = Math.min(maxTradeCount, maxTradesForInput2);
                        if (maxTradeCount == 0 && hadEnoughInput1ToTrade) {
                            info("Can't trade because not enough {} (have {}, need {})", ItemRegistry.REGISTRY.get(trade.inputItem2).name(), input2Count, input2Cost);
                        }
                    }

                    if (canShiftClickPurchase(villagerTrade)) {
                        int outputsStackSize = ItemRegistry.REGISTRY.get(villagerTrade.getOutput().getId()).stackSize();
                        int maxTradesPerOutputStack = outputsStackSize / villagerTrade.getOutput().getAmount();
                        int maxTradesPerShiftClick = Math.min(maxTradesPerInputStack, maxTradesPerOutputStack);
                        int tradeCount = 0;
                        for (int j = 0; j < maxTradeCount; j+= maxTradesPerShiftClick) {
                            tradeCount++;
                            actions.add(new SelectTrade(offersPacket.getContainerId(), i));
                            actions.add(new ShiftClick(offersPacket.getContainerId(), 2, ShiftClickItemAction.LEFT_CLICK));
                        }
                        debug("shift clicking {} times, trade count: {} trades per shift click: {}", tradeCount, maxTradeCount, maxTradesPerShiftClick);
                    } else {
                        /**
                         * If there are multiple enchanted books available to trade
                         * and one's price is lower than our current
                         * a shift click will purchase the other books
                         *
                         * so we have to do left clicks only to buy one at a time, moving it to empty slots
                         *
                         * we could also close the inventory to have an empty slot found automatically
                         * but it doesn't play well with current logic for interacted villager and trade completion tracking
                         */
                        var emptySlots = findEmptySlots();
                        if (emptySlots.isEmpty()) {
                            info("Can't trade because we don't have any empty inventory slots");
                        }
                        maxTradeCount = Math.min(maxTradeCount, emptySlots.size());
                        for (int j = 0; j < maxTradeCount; j++) {
                            int outputSlot = emptySlots.removeFirst();
                            actions.add(new SelectTrade(offersPacket.getContainerId(), i));
                            actions.add(new ClickItem(offersPacket.getContainerId(), 2, ClickItemAction.LEFT_CLICK));
                            actions.add(new ClickItem(offersPacket.getContainerId(), outputSlot, ClickItemAction.LEFT_CLICK));
                        }
                        debug("click trading {} times", maxTradeCount);
                    }
                }
                actions.add(new CloseContainer(offersPacket.getContainerId()));
                purchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(getPriority())
                    .actions(actions)
                    .build());
                preTradeInput1Count = countItem(trade.getInputItem1().id());
                preTradeInput2Count = trade.has2InputTrade() ? countItem(trade.getInputItem2().id()) : 0;
                preTradeOutputCount = countItem(trade.getOutputItem().id());
                setState(State.TRADING_AWAIT_PURCHASE);
            }
            case TRADING_AWAIT_PURCHASE -> {
                if (purchaseFuture.isCompleted()) {
                    if (!purchaseFuture.getNow()) {
                        MODULE.get(MetaTrader.class).diagnostic("PURCHASE_REJECTED", interactionTarget);
                        setState(State.EVAL_RESTOCK);
                        return;
                    }
                    var trade = tradeIterator.current();
                    var input1Sold = preTradeInput1Count - countItem(trade.getInputItem1().id());
                    info("Sold {} {}", input1Sold, trade.inputItem1);
                    input1SellCount += input1Sold;
                    if (trade.has2InputTrade()) {
                        var input2Sold = preTradeInput2Count - countItem(trade.getInputItem2().id());
                        info("Sold {} {}", input2Sold, trade.inputItem2);
                        input2SellCount += input2Sold;
                    }
                    var outputBought = countItem(trade.getOutputItem().id()) - preTradeOutputCount;
                    info("Bought {} {}", outputBought, trade.getOutputItem().name());
                    outputBuyCount += outputBought;
                    if (outputBought > 0) hourlyProduction.merge(trade.outputItem, outputBought, Integer::sum);
                    if (META_CONFIG.enabled && outputBought > 0) {
                        String id = getTradeId(trade);
                        META_CONFIG.purchasedOutputCounts.merge(id, outputBought, Integer::sum);
                        saveConfigAsync();
                        if (trade.outputPerCycleCount > 0 && META_CONFIG.purchasedOutputCounts.get(id) >= trade.outputPerCycleCount) {
                            setState(State.STORE_GO_TO_CHEST);
                            return;
                        }
                    }
                    if (countItem(trade.getOutputItem().id()) > trade.outputItemStoreCountThreshold) {
                        setState(State.STORE_GO_TO_CHEST);
                    } else {
                        setState(State.EVAL_RESTOCK);
                    }
                }
            }
            case STORE_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                tried("output").add(trade.outputChest);
                if (trade.itemProcessing && needsOutputProcessing(trade)) {
                    setState(State.PROCESS_OUTPUT);
                    return;
                }
                var storeChest = trade.outputChest;
                storePathingFuture = BARITONE.rightClickBlock(storeChest.x(), storeChest.y(), storeChest.z());
                storePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.STORE_DEPOSIT);
            }
            case PROCESS_OUTPUT -> {
                var trade = tradeIterator.current();
                if (trade.getOutputItem() == ItemRegistry.BOOKSHELF) {
                    if (outputProcessor.tickBookshelf(this)) setState(State.STORE_GO_TO_CHEST);
                    return;
                }
                boolean glass = trade.getOutputItem() == ItemRegistry.GLASS;
                if (outputProcessor.awaitingResult()) {
                    if (outputProcessor.finishTransaction() && !needsOutputProcessing(trade)) setState(State.STORE_GO_TO_CHEST);
                    return;
                }
                if (outputProcessor.tick(this, trade.getOutputItem().id(), glass)) {
                    setState(State.STORE_GO_TO_CHEST);
                }
            }
            case STORE_DEPOSIT -> {
                if (storePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.STORE_GO_TO_CHEST);
                        }
                        return;
                    }
                    var outputItem = trade.itemProcessing && trade.getOutputItem() == ItemRegistry.GLASS
                        ? ItemRegistry.GLASS_PANE : trade.getOutputItem();
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> outputItem.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    storeDepositFuture = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build());
                    storePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.STORE_AWAIT_DEPOSIT);
                }
            }
            case STORE_AWAIT_DEPOSIT -> {
                if (storeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    int buyItemCount = countItem(trade.itemProcessing && trade.getOutputItem() == ItemRegistry.GLASS
                        ? ItemRegistry.GLASS_PANE.id() : trade.getOutputItem().id());
                    if (buyItemCount > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            if (!trade.outputChests.isEmpty()) {
                                advanceContainer(trade, false);
                                setState(State.STORE_GO_TO_CHEST);
                                return;
                            }
                            warn("Unable to fully deposit buy items, trying to continue anyway");
                            setState(State.EVAL_RESTOCK);
                        }
                        return;
                    }
                    setState(State.EVAL_RESTOCK);
                }
            }
            case READY_NEXT_TRADE -> {
                var trade = tradeIterator.current();
                if (META_CONFIG.enabled) {
                    setState(State.POST_TRADE_INPUT_1_GO_TO);
                    return;
                }
                switch (trade.postTradeStoreMode) {
                    case NONE -> {
                        setState(State.NEXT_TRADE);
                    }
                    case TO_RESTOCK -> {
                        setState(State.POST_TRADE_INPUT_1_GO_TO);
                    }
                    case TO_OVERFLOW -> {
                        setState(State.POST_TRADE_OVERFLOW_GO_TO);
                    }
                }
            }
            case POST_TRADE_INPUT_1_GO_TO -> {
                var trade = tradeIterator.current();
                tried("input1").add(trade.inputItem1Chest);
                postTradePathingFuture = BARITONE.rightClickBlock(trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_INPUT_1_DEPOSIT);
            }
            case POST_TRADE_INPUT_1_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem1();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_INPUT_1_GO_TO);
                        }
                        return;
                    }
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem1.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_INPUT_1_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_INPUT_1_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem1();
                    if (countItem(inputItem1.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            if (!trade.inputItem1Chests.isEmpty()) {
                                advanceContainer(trade, true);
                                setState(State.POST_TRADE_INPUT_1_GO_TO);
                                return;
                            }
                            warn("Unable to fully deposit post trade input item 1, trying to continue anyway");
                        } else {
                            return;
                        }
                    }
                    if (trade.has2InputTrade()) {
                        setState(State.POST_TRADE_INPUT_2_GO_TO);
                    } else {
                        setState(State.NEXT_TRADE);
                    }
                }
            }
            case POST_TRADE_INPUT_2_GO_TO -> {
                var trade = tradeIterator.current();
                tried("input2").add(trade.inputItem2Chest);
                postTradePathingFuture = BARITONE.rightClickBlock(trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_INPUT_2_DEPOSIT);
            }
            case POST_TRADE_INPUT_2_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem2 = trade.getInputItem2();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_INPUT_2_GO_TO);
                        }
                        return;
                    }
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem2.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_INPUT_2_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_INPUT_2_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem2 = trade.getInputItem2();
                    if (countItem(inputItem2.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            advanceInput2(trade); setState(State.POST_TRADE_INPUT_2_GO_TO); return;
                        } else {
                            return;
                        }
                    }
                    setState(State.NEXT_TRADE);
                }
            }
            case POST_TRADE_OVERFLOW_GO_TO -> {
                var trade = tradeIterator.current();
                postTradePathingFuture = BARITONE.rightClickBlock(trade.overflowChestPos.x(), trade.overflowChestPos.y(), trade.overflowChestPos.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_OVERFLOW_DEPOSIT);
            }
            case POST_TRADE_OVERFLOW_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_OVERFLOW_GO_TO);
                        }
                        return;
                    }
                    var inputItem1 = trade.getInputItem1();
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem1.id() == i.getId()
                        ));
                    if (trade.has2InputTrade()) {
                        var inputItem2 = trade.getInputItem2();
                        actions.addAll(
                            InventoryActionMacros.deposit(
                                openContainer.getContainerId(),
                                i -> inputItem2.id() == i.getId()
                            ));
                    }
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_OVERFLOW_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_OVERFLOW_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem1();
                    if (countItem(inputItem1.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit post trade input item 1, trying to continue anyway");
                        } else {
                            return;
                        }
                    }
                    if (trade.has2InputTrade()) {
                        var inputItem2 = trade.getInputItem2();
                        if (countItem(inputItem2.id()) > 0) {
                            if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                                warn("Unable to fully deposit post trade input item 2, trying to continue anyway");
                            } else {
                                return;
                            }
                        }
                    }
                    setState(State.NEXT_TRADE);
                }
            }
            case NEXT_TRADE -> {
                var trade = tradeIterator.current();
                if (META_CONFIG.enabled) {
                    var tradeId = getTradeId(trade);
                    if (tradeId != null) {
                        META_CONFIG.completedTrades.put(tradeId, 1);
                        saveConfigAsync();
                    }
                }
                var nextTrade = tradeIterator.next();
                var tradeDuration = Duration.ofNanos(System.nanoTime() - tradeStartTime);
                var tradeResult = Embed.builder()
                    .title("Trade Completed")
                    .addField("Trade ID", Objects.requireNonNullElse(getTradeId(trade), "?"))
                    .addField("Duration", MathHelper.formatDuration(tradeDuration))
                    .addField("Input 1", trade.inputItem1)
                    .addField("Input 1 Sell Count", input1SellCount);
                if (trade.has2InputTrade()) {
                    tradeResult
                        .addField("Input 2", trade.inputItem2)
                        .addField("Input 2 Sell Count", input2SellCount);
                }
                tradeResult
                    .addField("Output", trade.outputItem)
                    .addField("Output Buy Count", outputBuyCount)
                    .addField("Next Trade", Objects.requireNonNullElse(getTradeId(nextTrade), "?"));
                if (trade.outputPerCycleCount > 0) {
                    tradeResult.addField("Cycle Target", trade.outputPerCycleCount)
                        .addField("Cycle Complete", "yes");
                }
                if (PLUGIN_CONFIG.logTradeStatusToDiscord) {
                    discordNotification(tradeResult);
                } else {
                    info(EmbedSerializer.serialize(tradeResult));
                }
                setState(State.ENTRYPOINT);
            }
        }
    }

    private void reportHourlyProductionIfDue() {
        if (!PLUGIN_CONFIG.logTradeStatusToDiscord) return;
        if (System.nanoTime() - hourlyProductionStartTime < Duration.ofHours(1).toNanos()) return;
        var report = Embed.builder().title("Hourly production");
        if (hourlyProduction.isEmpty()) {
            report.addField("Production", "No output purchased");
        } else {
            hourlyProduction.forEach((item, count) -> report.addField(item, count));
        }
        discordNotification(report);
        hourlyProduction.clear();
        hourlyProductionStartTime = System.nanoTime();
    }

    private boolean enchantmentFilter(VillagerTraderConfig.Trade trade, @NonNull ItemStack output) {
        var dataComponents = output.getDataComponents();
        if (dataComponents == null)
            return false;
        var storedEnchantments = dataComponents.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var enchantments = dataComponents.get(DataComponentTypes.ENCHANTMENTS);
        if (storedEnchantments == null && enchantments == null)
            return false;
        var itemEnchants = (storedEnchantments != null ? storedEnchantments : enchantments).getEnchantments();

        for (var bookEnchantEntry : trade.outputItemEnchantments.object2IntEntrySet()) {
            String desiredEnchantKey = bookEnchantEntry.getKey();
            var desiredEnchant = EnchantmentRegistry.REGISTRY.get(desiredEnchantKey);
            if (desiredEnchant == null) {
                error("Enchanted book enchantment id: {} not found in registry", desiredEnchantKey);
                continue;
            }
            var enchantId = desiredEnchant.id();
            int desiredLevel = bookEnchantEntry.getIntValue();
            if (!itemEnchants.containsKey(enchantId))
                continue;
            int actualLevel = itemEnchants.get(enchantId);
            if (actualLevel >= desiredLevel) {
                return true;
            }
        }
        return false;
    }

    private void stop() {
        PLUGIN_CONFIG.enabled = false;
        syncEnabledFromConfig();
        saveConfigAsync();
    }

    private void setState(State newState) {
        if (state == State.EVAL_RESTOCK || state == State.READY_NEXT_TRADE) triedContainers.clear();
        debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    private boolean selectContainers(VillagerTraderConfig.Trade trade) {
        triedContainers.clear();
        if (!trade.inputItem1Chests.isEmpty()) trade.inputItem1Chest = ContainerLocator.nearest(trade.inputItem1Chests);
        if (!trade.inputItem2Chests.isEmpty()) trade.inputItem2Chest = ContainerLocator.nearest(trade.inputItem2Chests);
        if (!trade.outputChests.isEmpty()) trade.outputChest = ContainerLocator.nearest(trade.outputChests);
        return !trade.inputItem1Chest.equals(com.zenith.mc.block.BlockPos.ZERO)
            && (!trade.has2InputTrade() || !trade.inputItem2Chest.equals(com.zenith.mc.block.BlockPos.ZERO))
            && !trade.outputChest.equals(com.zenith.mc.block.BlockPos.ZERO);
    }

    private boolean cycleTargetReached(VillagerTraderConfig.Trade trade) {
        return META_CONFIG.enabled && trade.outputPerCycleCount > 0
            && META_CONFIG.purchasedOutputCounts.getOrDefault(getTradeId(trade), 0) >= trade.outputPerCycleCount;
    }

    private boolean needsOutputProcessing(VillagerTraderConfig.Trade trade) {
        boolean supported = trade.getOutputItem() == ItemRegistry.BOOKSHELF
            || trade.getOutputItem() == ItemRegistry.GLASS
            || trade.getOutputItem() == ItemRegistry.DIAMOND_SWORD || trade.getOutputItem() == ItemRegistry.DIAMOND_PICKAXE
            || trade.getOutputItem() == ItemRegistry.DIAMOND_AXE || trade.getOutputItem() == ItemRegistry.DIAMOND_SHOVEL
            || trade.getOutputItem() == ItemRegistry.DIAMOND_HELMET || trade.getOutputItem() == ItemRegistry.DIAMOND_CHESTPLATE
            || trade.getOutputItem() == ItemRegistry.DIAMOND_LEGGINGS || trade.getOutputItem() == ItemRegistry.DIAMOND_BOOTS;
        return supported && (trade.getOutputItem() == ItemRegistry.BOOKSHELF
            ? countItem(ItemRegistry.BOOKSHELF.id()) > 0
            : outputProcessor.needsProcessing(trade.getOutputItem().id(), trade.getOutputItem() == ItemRegistry.GLASS));
    }

    private void advanceContainer(VillagerTraderConfig.Trade trade, boolean input) {
        var next = nextContainer(input ? trade.inputItem1Chests : trade.outputChests, input ? "input1" : "output");
        if (next == null) return;
        if (input) trade.inputItem1Chest = next; else trade.outputChest = next;
    }

    private Set<com.zenith.mc.block.BlockPos> tried(String kind) { return triedContainers.computeIfAbsent(kind, k -> new HashSet<>()); }
    private com.zenith.mc.block.BlockPos nextContainer(List<com.zenith.mc.block.BlockPos> list, String kind) {
        var remaining = list.stream().filter(p -> !tried(kind).contains(p)).toList();
        if (remaining.isEmpty()) {
            MODULE.get(MetaTrader.class).supplyFailure("All configured " + kind + " containers exhausted; refill/free space and restart");
            return null;
        }
        return ContainerLocator.nearest(remaining);
    }
    private void advanceInput2(VillagerTraderConfig.Trade trade) {
        var next = nextContainer(trade.inputItem2Chests, "input2"); if (next != null) trade.inputItem2Chest = next;
    }

    private Optional<EntityLiving> nextVillager(final VillagerTraderConfig.Trade trade) {
        return CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .filter(e -> !interactedVillagersCache.asMap().containsKey(e.getUuid()))
            .map(e -> (EntityLiving) e)
            .filter(e -> trade.villagerProfession == getVillagerProfession(e))
            .min(Comparator.comparingDouble(e -> e.distanceSqTo(CACHE.getPlayerCache().getThePlayer())));
    }

    private VillagerProfession getVillagerProfession(EntityLiving villager) {
        var data = villager.getMetadataValue(18, MetadataTypes.VILLAGER_DATA, VillagerData.class);
        if (data == null) {
            return VillagerProfession.NONE;
        }
        return VillagerProfession.from(data.getProfession());
    }

    private int countItem(int id) {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() == id) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countInvEmptySlots() {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            if (inv.get(i) == Container.EMPTY_STACK) {
                count++;
            }
        }
        return count;
    }

    private int countSlotUsages(int id) {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() == id) {
                count++;
            }
        }
        return count;
    }

    private boolean canShiftClickPurchase(VillagerTrade villagerTrade) {
        int count = 0;
        for (var offer : offersPacket.getTrades()) {
            if (villagerTrade.getOutput().getId() != offer.getOutput().getId()) continue;
            if (villagerTrade.getFirstInput().getId() != offer.getFirstInput().getId()) continue;
            boolean offerHasInput2 = offer.getSecondInput() != Container.EMPTY_STACK;
            boolean villagerTradeHasInput2 = villagerTrade.getSecondInput() != Container.EMPTY_STACK;
            if (offerHasInput2 && villagerTradeHasInput2) {
                if (villagerTrade.getSecondInput().getId() == offer.getSecondInput().getId()) {
                    count++;
                }
            } else if (!offerHasInput2 && !villagerTradeHasInput2) {
                count++;
            }

        }
        if (count > 1) return false;
        return true;
    }

    private IntList findEmptySlots() {
        var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (openContainer.getType() != ContainerType.MERCHANT) return IntList.of();
        var output = new IntArrayList();
        var containerInfo = ContainerTypeInfoRegistry.REGISTRY.get(openContainer.getType());
        for (int i = containerInfo.topSlots(); i < containerInfo.totalSlots(); i++) {
            if (openContainer.getItemStack(i) == Container.EMPTY_STACK) {
                output.add(i);
            }
        }
        return output;
    }

    public void onTradeListChange() {
        tradeIterator.reset();
        state = State.ENTRYPOINT;
    }

    public boolean inventoryIdle() {
        return !PLUGIN_CONFIG.enabled || (state == State.ENTRYPOINT || state == State.EVAL_RESTOCK)
            && CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() == 0;
    }

    @Nullable String getTradeId(VillagerTraderConfig.Trade trade) {
        for (var entry : activeTrades().entrySet()) {
            if (entry.getValue() == trade) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static LinkedHashMap<String, VillagerTraderConfig.Trade> activeTrades() {
        return META_CONFIG.testMode.equals("metaTraderStandalone") ? PLUGIN_CONFIG.testTrades : PLUGIN_CONFIG.trades;
    }

    public enum State {
        ENTRYPOINT,
        EVAL_RESTOCK,
        RESTOCK_INPUT_1_GO_TO_CHEST,
        RESTOCK_INPUT_1_WITHDRAW,
        RESTOCK_INPUT_1_AWAIT_WITHDRAW,
        RESTOCK_INPUT_2_GO_TO_CHEST,
        RESTOCK_INPUT_2_WITHDRAW,
        RESTOCK_INPUT_2_AWAIT_WITHDRAW,
        CRAFT_EMERALD_BLOCKS,
        AWAIT_CRAFT_EMERALD_BLOCKS,
        TRADING_INTERACT_WITH_VILLAGER,
        TRADING_AWAIT_INTERACT_WITH_VILLAGER,
        TRADING_TRY_START_PURCHASE,
        TRADING_AWAIT_PURCHASE,
        // todo: states to compact stackable items in inventory with shift clicks
        STORE_GO_TO_CHEST,
        STORE_DEPOSIT,
        STORE_AWAIT_DEPOSIT,
        PROCESS_OUTPUT,
        READY_NEXT_TRADE,
        POST_TRADE,
        POST_TRADE_INPUT_1_GO_TO,
        POST_TRADE_INPUT_1_DEPOSIT,
        POST_TRADE_INPUT_1_AWAIT_DEPOSIT,
        POST_TRADE_INPUT_2_GO_TO,
        POST_TRADE_INPUT_2_DEPOSIT,
        POST_TRADE_INPUT_2_AWAIT_DEPOSIT,
        POST_TRADE_OVERFLOW_GO_TO,
        POST_TRADE_OVERFLOW_DEPOSIT,
        POST_TRADE_OVERFLOW_AWAIT_DEPOSIT,
        NEXT_TRADE,
        AWAIT_RESTOCK
    }

    public enum VillagerProfession {
        NONE,
        ARMORER,
        BUTCHER,
        CARTOGRAPHER,
        CLERIC,
        FARMER,
        FISHERMAN,
        FLETCHER,
        LEATHERWORKER,
        LIBRARIAN,
        MASON,
        NITWIT,
        SHEPHERD,
        TOOLSMITH,
        WEAPONSMITH;

        private static final VillagerProfession[] VALUES = values();

        public static VillagerProfession from(int id) {
            return VALUES[id];
        }
    }

    public static class TradeIterator implements Iterator<VillagerTraderConfig.Trade> {
        int index = 0;
        VillagerTraderConfig.Trade[] backingArray = activeTrades().values()
            .stream()
            .filter(trade -> trade.enabled)
            .toArray(VillagerTraderConfig.Trade[]::new);

        @Override
        public boolean hasNext() {
            return backingArray.length > 0;
        }

        public VillagerTraderConfig.Trade current() {
            return backingArray[index];
        }

        @Override
        public VillagerTraderConfig.Trade next() {
            if (++index >= backingArray.length) {
                index = 0;
            }
            return backingArray[index];
        }

        public void refresh() {
            backingArray = activeTrades().values()
                .stream()
                .filter(trade -> trade.enabled)
                .toArray(VillagerTraderConfig.Trade[]::new);
            if (index >= backingArray.length) {
                index = 0;
            }
        }

        public void reset() {
            index = 0;
            refresh();
        }
    }
}
