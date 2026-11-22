/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class AddonFlightState {
    private static final PathingCommand WAIT = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    private final IElytraProcess process;
    private final ElytraSupplyAlerts supplyAlerts = new ElytraSupplyAlerts();
    private ElytraTripForecast trip = new ElytraTripForecast();
    private BlockPos destination;
    private BlockPos paused;
    private ElytraMileage mileage;
    private Path mileageFile;
    private long savedAt;
    private int previousRockets = -1;
    private int nextRefill;
    private int retryAt;
    private int launchAt;
    private int glideAt;
    private Rotation launchAim;
    private boolean launchIgnitionRejected;
    private Vec3 launchOrigin;
    private CompletableFuture<Void> launchRoute;

    public AddonFlightState(IElytraProcess process) {
        this.process = process;
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public void start(BlockPos target) {
        end();
        destination = target;
    }

    public void end() {
        supplyAlerts.reset();
        trip = new ElytraTripForecast();
        if (mileage != null) {
            mileage.interrupt();
            saveMileage();
        }
        destination = null;
        paused = null;
        launchAim = null;
        launchIgnitionRejected = false;
        launchOrigin = null;
        launchRoute = null;
        retryAt = 0;
        previousRockets = -1;
    }

    public boolean isPaused() { return paused != null; }

    public void togglePause() {
        if (paused != null) {
            BlockPos target = paused;
            ElytraTripForecast activeTrip = trip;
            paused = null;
            process.pathTo(target);
            trip = activeTrip;
            trip.interrupt();
        } else if (process.isActive()) {
            BlockPos target = destination();
            ElytraTripForecast activeTrip = trip;
            takeOver();
            paused = target;
            trip = activeTrip;
            trip.interrupt();
        } else {
            takeOver();
        }
    }

    public void takeOver() {
        baritone().getPathingBehavior().forceCancel();
        baritone().getInputOverrideHandler().clearAllKeys();
        end();
    }

    public BlockPos destination() {
        return paused != null ? paused : destination != null ? destination : process.currentDestination();
    }

    public double remainingDistance() {
        BlockPos target = destination();
        return target == null ? 0 : distanceTo(target);
    }

    public double distanceTo(BlockPos target) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0 : Vec3.atCenterOf(target).subtract(player.position()).horizontalDistance();
    }

    public String status() {
        return ElytraUi.text("等待起飞", "Waiting for takeoff");
    }

    public ElytraFlightStats.Estimate estimateFlight() {
        if (!process.isActive()) {
            return null;
        }
        double remaining = remainingDistance();
        return trip.estimate(remaining, estimate(remaining));
    }

    public long flightRocketsUsed() {
        return trip.rocketsUsed();
    }

    public ElytraFlightStats.Estimate estimate(double distance) {
        ElytraMileage stats = mileage();
        ElytraMileage.Summary total = stats.summary();
        double seconds = stats.recentSecondsPerTenThousand();
        if (seconds <= 0) {
            seconds = total.blocks() >= 64 ? 10_000 * total.seconds() / total.blocks() : 1000;
        }
        long rockets = distance == 0 ? 0 : total.rockets() == 0 || total.blocks() < 64 ? -1
                : (long) Math.ceil(total.rockets() * distance / total.blocks());
        long wear = distance == 0 ? 0 : total.wear() == 0 || total.blocks() < 64 ? -1
                : (long) Math.ceil(total.wear() * distance / total.blocks());
        return new ElytraFlightStats.Estimate(Math.round(seconds * distance / 10_000), rockets, wear);
    }

    public ElytraMileage mileage() {
        if (mileage == null) {
            Minecraft client = Minecraft.getInstance();
            mileageFile = client.gameDirectory.toPath().resolve("baritone").resolve("flight-profiles")
                    .resolve(client.getUser().getProfileId() + "-mileage.properties");
            mileage = ElytraMileage.load(mileageFile);
        }
        return mileage;
    }

    private void saveMileage() {
        mileage.save(mileageFile);
        savedAt = System.nanoTime();
    }

    public PathingCommand tick(boolean landing, List<BetterBlockPos> path,
                               Function<BlockPos, CompletableFuture<Void>> prepareRoute) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !process.isActive()) {
            return null;
        }
        refill(player);
        if (!landing && destination() != null) {
            trip.sample(remainingDistance());
        }
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (player.isFallFlying() && !player.onGround()) {
            mileage().sample(player.position(), chest.is(Items.ELYTRA) ? chest.getDamageValue() : -1);
            int rockets = countRockets(player);
            if (previousRockets - rockets == 1) {
                mileage.rocketUsed();
                trip.rocketUsed();
            }
            previousRockets = rockets;
            if (System.nanoTime() - savedAt > 30_000_000_000L) {
                saveMileage();
            }
        } else if (mileage != null) {
            mileage.interrupt();
        }
        if (player.tickCount % 20 == 0) {
            supplyAlerts.update(player, countRockets(player), estimateFlight(),
                    message -> player.displayClientMessage(Component.literal(message), true));
        }
        supplyAlerts.tick(player);
        if (landing || player.isInLava() || player.isInWater() || player.isOnFire()) {
            launchAim = null;
            return null;
        }
        if (launchAim == null && player.onGround()) {
            if (!player.isFallFlying() && chest.is(Items.ELYTRA)
                    && chest.getMaxDamage() - chest.getDamageValue() > 1 && rocketSlot(player) != -2) {
                prepareLaunch(player, path, prepareRoute);
            }
            baritone().getInputOverrideHandler().clearAllKeys();
            return WAIT;
        }
        if (launchAim == null) {
            return launchIgnitionRejected ? WAIT : null;
        }
        int elapsed = player.tickCount - launchAt;
        if (elapsed > 22 || (elapsed > 6 && player.onGround())) {
            launchAim = null;
            baritone().getInputOverrideHandler().clearAllKeys();
            return launchIgnitionRejected ? WAIT : null;
        }
        var inputs = baritone().getInputOverrideHandler();
        inputs.clearAllKeys();
        baritone().getLookBehavior().updateTarget(launchAim, true);
        if (player.onGround()) {
            inputs.setInputForceState(Input.JUMP, true);
        } else if (player.isFallFlying() && elapsed > 1) {
            Rotation actualAim = baritone().getLookBehavior().getAimProcessor().peekRotation(launchAim);
            int slot = rocketSlot(player);
            launchIgnitionRejected = !ElytraLaunchPlanner.canIgnite(player, launchAim, actualAim);
            if (slot != -2 && !launchIgnitionRejected) {
                if (slot >= 0) {
                    player.getInventory().setSelectedSlot(slot);
                }
                var result = ElytraLaunchPlanner.useWithAim(player, actualAim,
                        () -> baritone().getPlayerContext().playerController().processRightClick(player, player.level(),
                                slot < 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
                if (result.consumesAction()) {
                    launchAim = null;
                }
            }
        } else if (glideAt == 0 && player.getDeltaMovement().y < 0 && player.tryToStartFallFlying()) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            glideAt = player.tickCount;
        }
        return WAIT;
    }

    private void prepareLaunch(LocalPlayer player, List<BetterBlockPos> path,
                               Function<BlockPos, CompletableFuture<Void>> prepareRoute) {
        if (player.tickCount < retryAt) {
            return;
        }
        if (launchRoute != null && !launchRoute.isDone()) {
            return;
        }
        Vec3 ground = player.position();
        if (!ElytraLaunchPlanner.hasHeadroom(player, ground)) {
            retryAt = player.tickCount + 40;
            return;
        }
        if (launchRoute == null || launchOrigin.distanceToSqr(ground) >= 0.25) {
            launchOrigin = ground;
            launchRoute = prepareRoute.apply(ElytraLaunchPlanner.routeStart(ground));
            return;
        }
        if (!launchRoute.isCompletedExceptionally()) {
            launchAim = ElytraLaunchPlanner.plan(player, ground, path);
        }
        launchRoute = null;
        launchOrigin = null;
        retryAt = player.tickCount + 40;
        launchAt = player.tickCount;
        glideAt = 0;
        if (launchAim != null) {
            launchIgnitionRejected = false;
        }
    }

    private void refill(LocalPlayer player) {
        var options = ElytraPanelOptions.get();
        if (!options.elytraRefillHotbar.get() || player.tickCount < nextRefill || player.isUsingItem()
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || Minecraft.getInstance().screen != null) {
            return;
        }
        int rocketSlot = Math.clamp(options.elytraRocketSlot.get(), 1, 9) - 1;
        int foodSlot = Math.clamp(options.elytraFoodSlot.get(), 1, 9) - 1;
        if (refillSlot(player, rocketSlot, true) || (foodSlot != rocketSlot && refillSlot(player, foodSlot, false))) {
            nextRefill = player.tickCount + 8;
        }
    }

    private static boolean refillSlot(LocalPlayer player, int slot, boolean rockets) {
        var items = player.getInventory().getNonEquipmentItems();
        if (!items.get(slot).isEmpty()) {
            return false;
        }
        for (int index = 9; index < 36; index++) {
            ItemStack stack = items.get(index);
            if (rockets ? rocket(stack) : stack.has(DataComponents.FOOD) && stack.has(DataComponents.CONSUMABLE)) {
                baritone().getPlayerContext().playerController().windowClick(player.inventoryMenu.containerId,
                        index, slot, ClickType.SWAP, player);
                return true;
            }
        }
        return false;
    }

    private static int countRockets(LocalPlayer player) {
        int count = player.getInventory().getNonEquipmentItems().stream().filter(AddonFlightState::rocket)
                .mapToInt(ItemStack::getCount).sum();
        ItemStack offhand = player.getOffhandItem();
        return count + (rocket(offhand) ? offhand.getCount() : 0);
    }

    private static int rocketSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (rocket(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return rocket(player.getOffhandItem()) ? -1 : -2;
    }

    private static boolean rocket(ItemStack stack) {
        var firework = stack.get(DataComponents.FIREWORKS);
        return stack.is(Items.FIREWORK_ROCKET) && firework != null && firework.explosions().isEmpty();
    }
}
