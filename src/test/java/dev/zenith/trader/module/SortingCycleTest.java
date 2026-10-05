package dev.zenith.trader.module;

import com.zenith.mc.block.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SortingCycleTest {
    @Test void storageTraversesSouthNorthThenEastWestThenUp() {
        var southEast = new BlockPos(2, 10, 5);
        var northEast = new BlockPos(2, 10, 4);
        var southWest = new BlockPos(1, 10, 5);
        var upper = new BlockPos(3, 11, 6);
        assertEquals(List.of(southEast,northEast,southWest,upper),
            SortingCycle.storageOrder(List.of(upper,southWest,northEast,southEast)));
    }
    @Test void absentStacksCannotBeClassifiedAsBoxes() {
        assertFalse(SortingCycle.emptyBox(null));
        assertFalse(SortingCycle.filledBox(null));
        assertFalse(SortingCycle.matches(null,"minecraft:enchanted_book|minecraft:density|5"));
    }
}
