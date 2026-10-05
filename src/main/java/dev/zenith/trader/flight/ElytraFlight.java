package dev.zenith.trader.flight;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.MODULE;

/**
 * ZenithProxy glue for {@link FlightController}: reads the bot's simulated
 * state each tick, runs the controller and submits its rotation, jump, sneak,
 * walk and rocket inputs. One flight at a time; callers poll the returned future.
 */
public final class ElytraFlight extends Module {
    /** Above AutoEat (11000), KillAura and Baritone (7000) so nothing steals the controls mid-air. */
    public static final int INPUT_PRIORITY = 20000;

    /** One flight leg. Launch block is the block the bot's feet are in at takeoff. */
    public record Request(FlightRoute route, int launchX, int launchY, int launchZ, int timeoutSeconds,
                          int minimumElytraDurability, Consumer<String> log) {}

    /** Where and how the bot came down. */
    public record Result(double x, double y, double z, double distanceToTarget, int rockets, int ticks,
                         double approachAltitude, double touchdownSpeed, int elytraDurability) {}

    private final FlightPlanner planner = new FlightPlanner();
    private FlightController controller;
    private CompletableFuture<Result> future;
    private Consumer<String> log = s -> {};
    private InputRequestFuture lastInput;
    private int lostInputTicks;
    private int stillTicks;
    private double lastX, lastY, lastZ;
    /** Input of the tick a rocket was used on, checked next tick to confirm the click happened. */
    private InputRequestFuture rocketInput;
    /** After a failure mid-air, keep the bot in the slow safe glide until it is down. */
    private boolean safeGlide;
    private boolean pausedAutoEat, pausedAutoArmor;

    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            EventConsumer.of(ClientBotTick.class, -500, e -> tick()),
            EventConsumer.of(ClientBotTick.Stopped.class, e -> abort("bot control stopped")),
            EventConsumer.of(ClientDisconnectEvent.class, e -> abort("disconnected")),
            EventConsumer.of(ClientDeathEvent.class, e -> abort("player died"))
        );
    }

    @Override
    public void onDisable() {
        abort("flight module disabled");
    }

    public synchronized boolean isFlying() {
        return future != null;
    }

    public FlightPlanner planner() {
        return planner;
    }

    public synchronized CompletableFuture<Result> start(Request request) {
        if (future != null) return CompletableFuture.failedFuture(new IllegalStateException("a flight is already active"));
        String problem = prerequisites(request.minimumElytraDurability(), request.route().mode == FlightRoute.Mode.CLIMB, true);
        if (problem != null) return CompletableFuture.failedFuture(new IllegalStateException(problem));
        BARITONE.stop();
        safeGlide = false;
        rocketInput = null;
        pauseInventoryModules();
        log = request.log();
        controller = new FlightController(request.route(), planner, request.launchX(), request.launchY(),
            request.launchZ(), request.timeoutSeconds() * 20, log);
        future = new CompletableFuture<>();
        lostInputTicks = 0;
        stillTicks = 0;
        lastInput = null;
        lastX = BOT.getX();
        lastY = BOT.getY();
        lastZ = BOT.getZ();
        log.accept(String.format("start: elytra durability=%d rockets in hand=%d (%s) at %.2f %.2f %.2f",
            elytraDurability(), rocketsInHand(), rocketHand(), BOT.getX(), BOT.getY(), BOT.getZ()));
        return future;
    }

    /** Pitch left on the bot if a flight is stopped mid-air: the slow nose-up glide, too slow to take wall damage. */
    public static final float SAFE_GLIDE_PITCH = -30f;

    /** Stops the active flight; its future completes exceptionally with the reason. */
    public synchronized boolean abort(String reason) {
        if (future == null) return false;
        if (controller != null) controller.fail(reason);
        finish();
        return true;
    }

    private synchronized void tick() {
        if (future == null || controller == null) {
            if (safeGlide) holdSafeGlide();
            return;
        }
        try {
            String problem = prerequisites(0, false, false);
            if (problem != null) {
                controller.fail(problem);
                finish();
                return;
            }
            if (lastInput != null && lastInput.isCompleted() && !lastInput.getNow()) lostInputTicks++;
            else lostInputTicks = 0;
            if (lostInputTicks > 20) {
                controller.fail("movement control is owned by another module");
                finish();
                return;
            }
            if (BARITONE.isActive()) {
                BARITONE.stop();
                log.accept("stopped the walking pathfinder, which started during the flight");
            }
            if (rocketInput != null && rocketInput.isCompleted()) {
                if (!rocketInput.getNow() || !(rocketInput.getClickResult() instanceof ClickResult.RightClickResult))
                    controller.rocketNotFired();
                rocketInput = null;
            }
            trackProgress();
            if (controller.finished()) {
                finish();
                return;
            }
            var v = BOT.getVelocity();
            int[] rocket = attachedRocket();
            var observation = new FlightController.Observation(BOT.getX(), BOT.getY(), BOT.getZ(),
                v.getX(), v.getY(), v.getZ(), BOT.getYaw(), BOT.getPitch(), BOT.isOnGround(), BOT.isFallFlying(),
                BOT.isTouchingWater(), rocket != null, rocket != null ? rocket[0] : 0, rocketsInHand(),
                BOT.isHorizontalCollision());
            var command = controller.tick(observation, ZenithFlightTerrain.INSTANCE);
            if (controller.finished()) {
                finish();
                return;
            }
            Hand hand = rocketHand();
            var input = Input.builder()
                .pressingForward(command.forward())
                .sneaking(command.sneak())
                .jumping(command.jump())
                .rightClick(command.useRocket() && hand != null)
                .hand(hand != null ? hand : Hand.MAIN_HAND)
                .clickTarget(ClickTarget.None.INSTANCE)
                .clickRequiresRotation(false)
                .build();
            boolean firing = command.useRocket() && hand != null;
            // On the rocket tick keep the current rotation: Zenith skips clicks while rotating unless
            // botRotateBeforeInteract is on, and the boost only starts once the rocket entity spawns anyway.
            lastInput = INPUTS.submit(InputRequest.builder()
                .owner(this)
                .input(input)
                .yaw(firing ? BOT.getYaw() : command.yaw())
                .pitch(firing ? BOT.getPitch() : command.pitch())
                .priority(INPUT_PRIORITY)
                .build());
            if (firing) rocketInput = lastInput;
            else if (command.useRocket()) controller.rocketNotFired();
        } catch (Exception e) {
            controller.fail("flight controller error: " + e);
            finish();
        }
    }

    private void trackProgress() {
        double moved = Math.abs(BOT.getX() - lastX) + Math.abs(BOT.getY() - lastY) + Math.abs(BOT.getZ() - lastZ);
        lastX = BOT.getX();
        lastY = BOT.getY();
        lastZ = BOT.getZ();
        if (controller.phase() == FlightController.Phase.FLY && moved < 0.005) {
            if (++stillTicks > 200) controller.fail("no flight progress for 10 seconds (chunk not loaded or position frozen)");
        } else stillTicks = 0;
    }

    private void finish() {
        var f = future;
        var c = controller;
        future = null;
        controller = null;
        rocketInput = null;
        restoreInventoryModules();
        if (f == null || c == null) return;
        if (c.phase() != FlightController.Phase.DONE && BOT.isFallFlying() && !BOT.isOnGround() && !BOT.isTouchingWater()) {
            safeGlide = true;
            log.accept("flight stopped in the air; holding the slow " + SAFE_GLIDE_PITCH + " pitch glide until down");
            holdSafeGlide();
        }
        if (c.phase() == FlightController.Phase.DONE) {
            double d = c.route().remaining(BOT.getX(), BOT.getZ());
            f.complete(new Result(BOT.getX(), BOT.getY(), BOT.getZ(), d, c.rocketsFired(), c.ticks(),
                c.altitudeAtApproach(), c.touchdownSpeed(), elytraDurability()));
        } else {
            String reason = c.failure().isEmpty() ? "flight stopped" : c.failure();
            log.accept("failed: " + reason + String.format(" at %.2f %.2f %.2f after %d ticks, rockets used=%d",
                BOT.getX(), BOT.getY(), BOT.getZ(), c.ticks(), c.rocketsFired()));
            f.completeExceptionally(new IllegalStateException(reason));
        }
    }

    /**
     * Keeps a stopped flight in the slow nose-up glide (about 0.34 blocks/tick, too slow for wall
     * damage, no fall damage on touchdown) until the bot is on the ground or in water.
     */
    private void holdSafeGlide() {
        if (!BOT.isFallFlying() || BOT.isOnGround() || BOT.isTouchingWater() || Proxy.getInstance().hasActivePlayer()
            || !Proxy.getInstance().isConnected()) {
            safeGlide = false;
            return;
        }
        INPUTS.submit(InputRequest.builder().owner(this).yaw(BOT.getYaw()).pitch(SAFE_GLIDE_PITCH)
            .priority(INPUT_PRIORITY).build());
    }

    /** AutoEat would swap food into the rocket hand and AutoArmor could replace the elytra mid-air. */
    private void pauseInventoryModules() {
        var extra = CONFIG.client.extra;
        pausedAutoEat = extra.autoEat.enabled;
        pausedAutoArmor = extra.autoArmor.enabled;
        if (pausedAutoEat) {
            extra.autoEat.enabled = false;
            MODULE.get(com.zenith.module.impl.AutoEat.class).syncEnabledFromConfig();
        }
        if (pausedAutoArmor) {
            extra.autoArmor.enabled = false;
            MODULE.get(com.zenith.module.impl.AutoArmor.class).syncEnabledFromConfig();
        }
    }

    private void restoreInventoryModules() {
        var extra = CONFIG.client.extra;
        if (pausedAutoEat) {
            extra.autoEat.enabled = true;
            MODULE.get(com.zenith.module.impl.AutoEat.class).syncEnabledFromConfig();
            pausedAutoEat = false;
        }
        if (pausedAutoArmor) {
            extra.autoArmor.enabled = true;
            MODULE.get(com.zenith.module.impl.AutoArmor.class).syncEnabledFromConfig();
            pausedAutoArmor = false;
        }
    }

    /** Null when flight may start/continue, otherwise the reason it cannot. */
    private String prerequisites(int minimumDurability, boolean needRockets, boolean starting) {
        var proxy = Proxy.getInstance();
        if (!proxy.isConnected() || proxy.isInQueue() || proxy.hasActivePlayer())
            return "bot must be online, out of queue and not controlled by a player";
        String dimension = World.getCurrentDimension().name();
        if (!dimension.equals("overworld") && !dimension.equals("minecraft:overworld"))
            return "only overworld flight is supported";
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player.getHealth() <= 0) return "player is dead";
        if (player.isInVehicle()) return "leave the vehicle before flying";
        // Zenith ignores movement and rotation while a container is open.
        if (starting && CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0)
            return "close the open container before flying";
        int durability = elytraDurability();
        if (durability < 0) return "equip an elytra in the chest slot";
        if (durability <= 1) return "elytra is broken";
        if (minimumDurability > 0 && durability < minimumDurability)
            return "elytra durability " + durability + " is below the required " + minimumDurability;
        if (needRockets && rocketsInHand() <= 0) return "hold firework rockets in the main hand or offhand";
        return null;
    }

    /** Remaining elytra durability, or -1 when no elytra is worn. */
    public static int elytraDurability() {
        ItemStack chest = CACHE.getPlayerCache().getEquipment(EquipmentSlot.CHESTPLATE);
        if (chest == null || chest == Container.EMPTY_STACK || chest.getId() != ItemRegistry.ELYTRA.id()) return -1;
        Integer damage = chest.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        Integer max = ItemRegistry.ELYTRA.components().get(DataComponentTypes.MAX_DAMAGE);
        return (max == null ? 432 : max) - (damage == null ? 0 : damage);
    }

    private static boolean isRocket(ItemStack stack) {
        return stack != null && stack != Container.EMPTY_STACK
            && stack.getId() == ItemRegistry.FIREWORK_ROCKET.id() && stack.getAmount() > 0;
    }

    private static Hand rocketHand() {
        if (isRocket(CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND))) return Hand.OFF_HAND;
        if (isRocket(CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND))) return Hand.MAIN_HAND;
        return null;
    }

    private static int rocketsInHand() {
        ItemStack off = CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND);
        if (isRocket(off)) return off.getAmount();
        ItemStack main = CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND);
        return isRocket(main) ? main.getAmount() : 0;
    }

    /** {ageTicks} of a rocket attached to the bot, or null when none is. */
    private static int[] attachedRocket() {
        int self = CACHE.getPlayerCache().getEntityId();
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.isRemoved() || entity.getEntityType() != EntityType.FIREWORK_ROCKET) continue;
            OptionalInt attached = entity.getMetadataValue(9, MetadataTypes.OPTIONAL_VARINT, OptionalInt.class);
            if (attached != null && attached.isPresent() && attached.getAsInt() == self) return new int[]{entity.getTickCount()};
        }
        return null;
    }
}
