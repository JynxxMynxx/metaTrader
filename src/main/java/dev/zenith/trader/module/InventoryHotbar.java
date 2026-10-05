package dev.zenith.trader.module;

import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.SetHeldItem;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;

import java.util.List;

/** Builds the common, safe inventory transaction used before an item is used. */
final class InventoryHotbar {
    private InventoryHotbar() {}

    /**
     * Places the item at {@code inventorySlot} in hotbar slot 1 and selects it.
     * A left-click/left-click swap is intentional: it works both when slot 1 is
     * empty and when every hotbar slot is occupied, preserving the displaced item
     * in the original inventory slot.
     */
    static List<InventoryAction> selectSlot(int inventorySlot) {
        if (inventorySlot >= 36 && inventorySlot <= 44)
            return List.of(new SetHeldItem(inventorySlot - 36));
        if (inventorySlot < 9 || inventorySlot > 35)
            return List.of();
        return List.of(
            new ClickItem(0, inventorySlot, ClickItemAction.LEFT_CLICK),
            new ClickItem(0, 36, ClickItemAction.LEFT_CLICK),
            new SetHeldItem(0));
    }
}
