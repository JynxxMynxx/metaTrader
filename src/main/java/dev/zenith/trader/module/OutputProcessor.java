package dev.zenith.trader.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.List;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.META_CONFIG;

/** Performs one acknowledged crafting/grindstone transaction at a time. */
final class OutputProcessor {
    private static final int GLASS_BLOCKS_PER_RECIPE = 6;
    private static final int GLASS_PANES_PER_RECIPE = 16;
    private enum Stage { OPEN, WAIT_OPEN, WAIT_AXE, WAIT_BOOKSHELF_BREAK, WAIT_BOOKSHELF_PLACE, LOAD, WAIT_LOAD, TAKE, WAIT_TAKE }
    private Stage stage = Stage.OPEN;
    private PathingRequestFuture path;
    private RequestFuture action;
    private int wait;
    private int originalSlot;
    private BlockPos bookshelfTarget;
    private boolean finalBookshelfPlacement;
    private RequestFuture axeAction;
    private int bookshelfBreaksAtTarget;
    private final StationaryWorldEdit bookshelfEdit = new StationaryWorldEdit();

    void reset() {
        bookshelfEdit.cancel();
        stage = Stage.OPEN;
        path = null;
        action = null;
        wait = 0;
        bookshelfTarget = null;
        finalBookshelfPlacement = false;
        axeAction = null;
        bookshelfBreaksAtTarget = 0;
    }

    boolean tick(VillagerTrader owner, int itemId, boolean glass) {
        if (stage == Stage.OPEN) {
            if (glass && count(itemId) < GLASS_BLOCKS_PER_RECIPE) return true;
            if (!glass && enchantedSlot(itemId, 9, 45) < 0) return true;
            var targets = glass ? ContainerLocator.craftingTables()
                : (META_CONFIG.grindstones.isEmpty() ? ContainerLocator.find("minecraft:grindstone") : META_CONFIG.grindstones);
            if (targets.isEmpty()) return false;
            var target = ContainerLocator.nearest(targets);
            path = BARITONE.rightClickBlock(target.x(), target.y(), target.z());
            stage = Stage.WAIT_OPEN;
            wait = 0;
            return false;
        }
        var container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        var expected = glass ? ContainerType.CRAFTING : ContainerType.GRINDSTONE;
        if (stage == Stage.WAIT_OPEN) {
            if (!path.isCompleted()) return false;
            if (container.getContainerId() == 0 || container.getType() != expected) {
                if (++wait > 100) reset();
                return false;
            }
            stage = Stage.LOAD;
        }
        if (container.getType() != expected) {
            reset();
            return false;
        }
        int id = container.getContainerId();
        if (stage == Stage.LOAD) {
            List<InventoryAction> actions;
            if (glass) {
                // Six glass blocks craft into sixteen panes. Reserving an empty
                // slot keeps the recipe result from overflowing the inventory.
                if (!hasEmptySlot()) {
                    submit(owner, List.of(new CloseContainer(id)));
                    reset();
                    return true;
                }
                actions = List.of(new PlaceRecipe(id, "minecraft:glass_pane", false));
            } else {
                originalSlot = enchantedSlot(itemId, container.getSize() - 36, container.getSize());
                if (originalSlot < 0) {
                    submit(owner, List.of(new CloseContainer(id)));
                    reset();
                    return true;
                }
                actions = List.of(new ClickItem(id, originalSlot, ClickItemAction.LEFT_CLICK),
                    new ClickItem(id, 0, ClickItemAction.LEFT_CLICK));
            }
            action = submit(owner, actions);
            stage = Stage.WAIT_LOAD;
            wait = 0;
            return false;
        }
        if (stage == Stage.WAIT_LOAD) {
            if (!action.isCompleted()) return false;
            int resultSlot = glass ? 0 : 2;
            if (container.getItemStack(resultSlot) == Container.EMPTY_STACK) {
                if (++wait > 100) {
                    submit(owner, List.of(new CloseContainer(id)));
                    reset();
                }
                return false;
            }
            stage = Stage.TAKE;
        }
        if (stage == Stage.TAKE) {
            action = submit(owner, List.of(new ShiftClick(id, glass ? 0 : 2, ShiftClickItemAction.LEFT_CLICK),
                new CloseContainer(id)));
            stage = Stage.WAIT_TAKE;
            return false;
        }
        return false;
    }

    /** Breaks and replaces hopper-mounted bookshelves, leaving the hall stocked. */
    boolean tickBookshelf(VillagerTrader owner) {
        bookshelfEdit.tick();
        int bookshelfCount = count(ItemRegistry.BOOKSHELF.id());
        if (bookshelfCount == 0) return true;

        if (stage == Stage.OPEN) {
            if (bookshelfTarget == null || bookshelfBreaksAtTarget >= 100) {
                bookshelfTarget = nearestHopperBookshelf();
                bookshelfBreaksAtTarget = 0;
            }
            if (bookshelfTarget == null) return false;
            path = BARITONE.pathTo(bookshelfTarget.x(), bookshelfTarget.y(), bookshelfTarget.z());
            stage = Stage.WAIT_OPEN;
            return false;
        }
        if (stage == Stage.WAIT_OPEN) {
            if (!path.isCompleted()) return false;
            int axeSlot = findAxeSlot();
            if (axeSlot < 0) return false;
            owner.beginProtectedWorldEdit();
            var actions = new java.util.ArrayList<InventoryAction>();
            actions.addAll(InventoryHotbar.selectSlot(axeSlot));
            axeAction = INVENTORY.submit(InventoryActionRequest.builder().owner(owner)
                .priority(Baritone.getPriority() + 100).actions(actions).build());
            stage = Stage.WAIT_AXE;
            return false;
        }
        if (stage == Stage.WAIT_AXE) {
            if (!axeAction.isCompleted()) return false;
            path = bookshelfEdit.start(bookshelfTarget, null);
            stage = Stage.WAIT_BOOKSHELF_BREAK;
            return false;
        }
        if (stage == Stage.WAIT_BOOKSHELF_BREAK) {
            if (!path.isCompleted()) return false;
            if (!path.isAccepted()) { owner.endProtectedWorldEdit(); reset(); return false; }
            bookshelfBreaksAtTarget++;
            finalBookshelfPlacement = count(ItemRegistry.BOOKSHELF.id()) <= 1;
            if (finalBookshelfPlacement) {
                path = bookshelfEdit.start(bookshelfTarget, ItemRegistry.BOOKSHELF);
                stage = Stage.WAIT_BOOKSHELF_PLACE;
                return false;
            }
            path = bookshelfEdit.start(bookshelfTarget, ItemRegistry.BOOKSHELF);
            stage = Stage.WAIT_BOOKSHELF_PLACE;
            return false;
        }
        if (stage == Stage.WAIT_BOOKSHELF_PLACE) {
            if (!path.isCompleted()) return false;
            if (!path.isAccepted()) { owner.endProtectedWorldEdit(); reset(); return false; }
            boolean finished = finalBookshelfPlacement;
            owner.endProtectedWorldEdit();
            if (!finished) {
                stage = Stage.OPEN;
                path = null;
                axeAction = null;
            } else reset();
            return finished;
        }
        return false;
    }

    private BlockPos nearestHopperBookshelf() {
        var player = CACHE.getPlayerCache().getThePlayer().blockPos();
        return (META_CONFIG.bookshelves.isEmpty() ? ContainerLocator.positions(META_CONFIG.hallBounds) : META_CONFIG.bookshelves).stream()
            .filter(pos -> World.getBlock(pos) == BlockRegistry.BOOKSHELF)
            .filter(pos -> World.getBlock(pos.x(), pos.y() - 1, pos.z()) == BlockRegistry.HOPPER)
            .min(java.util.Comparator.comparingDouble(pos -> {
                double x = pos.x() - player.x();
                double y = pos.y() - player.y();
                double z = pos.z() - player.z();
                return x * x + y * y + z * z;
            }))
            .orElse(null);
    }

    private int findAxeSlot() {
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        int[] axes = {ItemRegistry.NETHERITE_AXE.id(), ItemRegistry.DIAMOND_AXE.id(), ItemRegistry.IRON_AXE.id(),
            ItemRegistry.GOLDEN_AXE.id(), ItemRegistry.STONE_AXE.id(), ItemRegistry.WOODEN_AXE.id()};
        for (int axe : axes) {
            for (int slot = 36; slot <= 44; slot++) if (inventory.get(slot) != Container.EMPTY_STACK && inventory.get(slot).getId() == axe) return slot;
            for (int slot = 9; slot <= 35; slot++) if (inventory.get(slot) != Container.EMPTY_STACK && inventory.get(slot).getId() == axe) return slot;
        }
        return -1;
    }

    boolean awaitingResult() { return stage == Stage.WAIT_TAKE; }

    boolean needsProcessing(int itemId, boolean glass) {
        return glass ? count(itemId) >= 6 : enchantedSlot(itemId, 9, 45) >= 0;
    }

    boolean finishTransaction() {
        if (action == null || !action.isCompleted()) return false;
        reset();
        return true;
    }

    private RequestFuture submit(VillagerTrader owner, List<InventoryAction> actions) {
        return INVENTORY.submit(InventoryActionRequest.builder().owner(owner)
            .priority(Baritone.getPriority() + 100).actions(actions).build());
    }

    private int enchantedSlot(int itemId, int start, int end) {
        var container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        for (int slot = start; slot < end; slot++) {
            var stack = container.getItemStack(slot);
            if (stack == Container.EMPTY_STACK || stack.getId() != itemId || stack.getDataComponents() == null) continue;
            var enchantments = stack.getDataComponents().get(DataComponentTypes.ENCHANTMENTS);
            if (enchantments != null && !enchantments.getEnchantments().isEmpty()) return slot;
        }
        return -1;
    }

    private boolean hasEmptySlot() {
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) if (inventory.get(slot) == Container.EMPTY_STACK) return true;
        return false;
    }

    private int count(int itemId) {
        int count = 0;
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) {
            var stack = inventory.get(slot);
            if (stack != Container.EMPTY_STACK && stack.getId() == itemId) count += stack.getAmount();
        }
        return count;
    }
}
