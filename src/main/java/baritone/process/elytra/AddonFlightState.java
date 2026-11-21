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
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;

public final class AddonFlightState {
    private static final PathingCommand WAIT = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    private final IElytraProcess process;
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
        if (mileage != null) {
            mileage.interrupt();
            saveMileage();
        }
        destination = null;
        paused = null;
        launchAim = null;
        retryAt = 0;
        previousRockets = -1;
    }

    public boolean isPaused() { return paused != null; }

    public void togglePause() {
        if (paused != null) {
            BlockPos target = paused;
            paused = null;
            process.pathTo(target);
        } else if (process.isActive()) {
            BlockPos target = destination();
            takeOver();
            paused = target;
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
        return process.isActive() ? estimate(remainingDistance()) : null;
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
        return new ElytraFlightStats.Estimate(Math.round(seconds * distance / 10_000), rockets, -1);
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

    public PathingCommand tick(boolean landing) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !process.isActive()) {
            return null;
        }
        refill(player);
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (player.isFallFlying() && !player.onGround()) {
            mileage().sample(player.position(), chest.is(Items.ELYTRA) ? chest.getDamageValue() : -1);
            int rockets = player.getInventory().getNonEquipmentItems().stream().filter(AddonFlightState::rocket)
                    .mapToInt(ItemStack::getCount).sum();
            rockets += rocket(player.getOffhandItem()) ? player.getOffhandItem().getCount() : 0;
            if (previousRockets - rockets == 1) {
                mileage.rocketUsed();
            }
            previousRockets = rockets;
            if (System.nanoTime() - savedAt > 30_000_000_000L) {
                saveMileage();
            }
        } else if (mileage != null) {
            mileage.interrupt();
        }
        if (landing || player.isInLava() || player.isInWater() || player.isOnFire()) {
            launchAim = null;
            return null;
        }
        if (launchAim == null && player.onGround() && !player.isFallFlying()
                && chest.is(Items.ELYTRA) && chest.getMaxDamage() - chest.getDamageValue() > 1
                && player.tickCount >= retryAt && rocketSlot(player) != -2) {
            retryAt = player.tickCount + 40;
            launchAim = launchDirection(player);
            launchAt = player.tickCount;
            glideAt = 0;
        }
        if (launchAim == null) {
            return null;
        }
        int elapsed = player.tickCount - launchAt;
        if (elapsed > 22 || (elapsed > 6 && player.onGround())) {
            launchAim = null;
            baritone().getInputOverrideHandler().clearAllKeys();
            return null;
        }
        var inputs = baritone().getInputOverrideHandler();
        inputs.clearAllKeys();
        baritone().getLookBehavior().updateTarget(launchAim, true);
        if (player.onGround()) {
            inputs.setInputForceState(Input.JUMP, true);
        } else if (player.isFallFlying() && elapsed > 1) {
            int slot = rocketSlot(player);
            if (slot >= 0) {
                player.getInventory().setSelectedSlot(slot);
            }
            if (slot != -2) {
                baritone().getPlayerContext().playerController().processRightClick(player, player.level(),
                        slot < 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            }
            launchAim = null;
        } else if (glideAt == 0 && player.getDeltaMovement().y < 0 && player.tryToStartFallFlying()) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            glideAt = player.tickCount;
        }
        return WAIT;
    }

    private Rotation launchDirection(LocalPlayer player) {
        Vec3 position = player.position();
        BlockPos target = destination();
        float yaw = target == null ? player.getYRot() : RotationUtils.calcRotationFromVec3d(position,
                Vec3.atCenterOf(target), baritone().getPlayerContext().playerRotations()).getYaw();
        AABB body = player.getDimensions(Pose.STANDING).makeBoundingBox(position);
        if (!clear(player, body.expandTowards(0, 1.3, 0))) {
            return null;
        }
        for (int offset : new int[]{0, 45, -45, 90, -90, 135, -135, 180}) {
            Vec3 forward = Vec3.directionFromRotation(-10, yaw + offset);
            boolean safe = true;
            for (int step = 0; step <= 12; step++) {
                if (!clear(player, body.move(forward.scale(step * 0.5)).move(0, 0.85, 0))) {
                    safe = false;
                    break;
                }
            }
            if (safe) {
                return new Rotation(yaw + offset, -10);
            }
        }
        return null;
    }

    private static boolean clear(LocalPlayer player, AABB box) {
        var world = player.level();
        if (!world.noCollision(player, box)) {
            return false;
        }
        for (BlockPos block : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!world.isLoaded(block) || world.getFluidState(block).is(FluidTags.LAVA) || world.getBlockState(block).is(BlockTags.FIRE)) {
                return false;
            }
        }
        return true;
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
