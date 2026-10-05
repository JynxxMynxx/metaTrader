package dev.zenith.trader;

import com.zenith.mc.block.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/** Persistent configuration and progress for the MetaTrader controller. */
public class MetaTraderConfig {
    public boolean enabled = false;
    public BlockPos hallReturn = new BlockPos(-2012057, 141, -1657332);
    /** Legacy raid landing setting; {@code set raidReturn} now also sets {@link #raidLanding}. */
    public BlockPos raidReturn = BlockPos.ZERO;
    /** Elytra flight: roof block the bot walks to and glides off towards the raid farm. */
    public BlockPos flightLaunch = new BlockPos(-2012057, 141, -1657332);
    /** Elytra flight: raid farm block the glide lands on and the return flight takes off from. */
    public BlockPos raidLanding = new BlockPos(-2011851, 63, -1657483);
    /** Elytra flight: roof block the return flight lands on. */
    public BlockPos hallLanding = new BlockPos(-2012079, 141, -1657322);
    /** Return flight: minimum height when it reaches {@link #flightApproachRadius} of the hall landing. */
    public int flightCruiseY = 160;
    /** Return flight: distance from the hall landing at which the final descent starts. */
    public int flightApproachRadius = 50;
    /**
     * Elytra durability needed to start the outbound glide. It covers the whole round trip
     * (about one point per second of flight: ~40 out, ~25 back) so the bot is never stranded
     * at the raid farm.
     */
    public int flightMinimumElytraDurability = 100;
    /** Elytra durability needed to start the return flight on its own. */
    public int flightReturnMinimumElytraDurability = 40;
    public int flightTimeoutSeconds = 240;
    /** A landing further than this from the landing block (blocks) fails the raid instead of walking over. */
    public double flightLandingTolerance = 12.0;
    public ArrayList<BlockPos> rocketChests = new ArrayList<>();
    public long raidRefreshAt = 0L;
    public BlockPos hallHeliPad = BlockPos.ZERO;
    public Region raidFarmBounds = new Region();
    public BlockPos raidFarmHeliPad = BlockPos.ZERO;
    public BlockPos raidFarmFireworkChest = BlockPos.ZERO;
    public BlockPos hallFireworkChest = BlockPos.ZERO;
    public BlockPos rocketChest = BlockPos.ZERO;
    public BlockPos ominousBottleChest = BlockPos.ZERO;
    public Region hallBoatPark = new Region();
    public Region raidFarmBoatPark = new Region();
    public Region hallBounds = new Region();
    public Region nonStackableDumpChestBounds = new Region();
    public int shulkersToCraftForStackableSort = 15;
    public Set<String> whitelistItems = new LinkedHashSet<>();
    /** Significantly changed: sorting is now driven by explicit coordinates. */
    public LinkedHashMap<String, ArrayList<BlockPos>> stackableShulkingInputChests = new LinkedHashMap<>();
    public LinkedHashMap<String, ArrayList<BlockPos>> outputShulkerLocations = new LinkedHashMap<>();
    public LinkedHashMap<String, ArrayList<BlockPos>> outputShulkerLocationEnchantments = new LinkedHashMap<>();
    public ArrayList<BlockPos> shulkerIngredientsChests = new ArrayList<>();
    /** Temporary placement point used while sorting unstackable items. */
    public BlockPos unstackableShulkerLocation = BlockPos.ZERO;
    /** Chests used for completed and partially filled unstackable shulkers. */
    public Region unstackableFinalStorageArea = new Region();
    public ArrayList<BlockPos> leftoverEmptyShulkerChests = new ArrayList<>();
    public ArrayList<BlockPos> stackableShulkerOutputChests = new ArrayList<>();
    public BlockPos raidFarmTotemOutputChest = BlockPos.ZERO;
    public ArrayList<BlockPos> grindstones = new ArrayList<>();
    public ArrayList<BlockPos> bookshelves = new ArrayList<>();
    public LinkedHashMap<String, ArrayList<Region>> storeChestBounds = new LinkedHashMap<>();
    public BlockPos anchorEnderChestOld = BlockPos.ZERO;
    public BlockPos anchorEnderChestNew = BlockPos.ZERO;
    public boolean standardLayoutConfigured = false;
    public LinkedHashMap<String, Integer> completedTrades = new LinkedHashMap<>();
    public LinkedHashMap<String, Integer> purchasedOutputCounts = new LinkedHashMap<>();
    public LinkedHashMap<String, Integer> containerCursor = new LinkedHashMap<>();
    public String cyclePhase = "TRADING";
    public int cycleStationIndex = 0;
    public String sortingStage = "LEFTOVERS";
    public int sortingCursor = 0;
    public String sortingRule = "";
    public BlockPos sortingReplacement = null;
    public String sortingError = "";
    public String sortingResumeStage = "";
    public int sortingResumeCursor = 0;
    public int sortingStorageCursor = 0;
    public BlockPos sortingStand = null;
    public int sortingPickupBefore = 0;
    public LinkedHashMap<String, BlockPos> sortingIngredientSources = new LinkedHashMap<>();
    public int heroVillageMinimumSeconds = 600;
    public boolean raidFarmEnabled = true;
    public boolean inventoryCleanupEnabled = true;
    public boolean loggingEnabled = true;
    public EnvironmentMode environmentMode = EnvironmentMode.TEST;
    public boolean environmentDiagnostics = true;
    public int environmentSnapshotIntervalTicks = 100;
    /** Hall input containers are assumed to be continuously prefilled. */
    public boolean inputsContinuouslyPrefilled = true;
    /** Expected server TPS range for interaction timeout sizing and diagnostics. */
    public int expectedMinimumTps = 5;
    public int expectedMaximumTps = 10;
    public int hallFlightAltitude = 200;
    public double flightDescentStartFraction = 0.5;
    public int flightArrivalRadius = 16;
    public boolean flightBridgeEnabled = false;
    public boolean elytraTravelEnabled = true;
    public GroundTravelMode groundTravelMode = GroundTravelMode.AUTO;
    public ArrayList<Region> hallToRaidSoulRoute = new ArrayList<>();
    public ArrayList<Region> raidToHallSoulRoute = new ArrayList<>();
    public ArrayList<Region> hallToRaidBlueIceRoute = new ArrayList<>();
    public ArrayList<Region> raidToHallBlueIceRoute = new ArrayList<>();

    public enum GroundTravelMode { AUTO, SOUL_SPEED, BLUE_ICE, DIRECT_WALK }
    public enum EnvironmentMode { TEST, HALL }
    public String testMode = "";
    public String testItem = "";
    public String testContainer = "";
    public int testCount = 0;
    public Region testRegion = new Region();
    public String lastTestResult = "";
    public long lastTestStartedAt = 0L;
    public long lastTestFinishedAt = 0L;

    public static class Region {
        public BlockPos first = BlockPos.ZERO;
        public BlockPos second = BlockPos.ZERO;

        public Region() {}
        public Region(BlockPos first, BlockPos second) {
            this.first = first;
            this.second = second;
        }

        public boolean contains(BlockPos pos) {
            return pos.x() >= Math.min(first.x(), second.x()) && pos.x() <= Math.max(first.x(), second.x())
                && pos.y() >= Math.min(first.y(), second.y()) && pos.y() <= Math.max(first.y(), second.y())
                && pos.z() >= Math.min(first.z(), second.z()) && pos.z() <= Math.max(first.z(), second.z());
        }

        public ArrayList<BlockPos> positions() {
            var result = new ArrayList<BlockPos>();
            for (int x = Math.min(first.x(), second.x()); x <= Math.max(first.x(), second.x()); x++)
                for (int y = Math.min(first.y(), second.y()); y <= Math.max(first.y(), second.y()); y++)
                    for (int z = Math.min(first.z(), second.z()); z <= Math.max(first.z(), second.z()); z++)
                        result.add(new BlockPos(x, y, z));
            return result;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Region r && Objects.equals(first, r.first) && Objects.equals(second, r.second);
        }
        @Override public int hashCode() { return Objects.hash(first, second); }
    }
}
