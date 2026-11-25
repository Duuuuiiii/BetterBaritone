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
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.function.Consumer;

public final class ElytraRepair {
    private static final int REPAIR_REMAINDER = 22;
    private static final int OFFHAND = -1;
    private static final int NONE = -2;
    private static final int CONFIRM_TICKS = 40;

    private final IPlayerContext ctx;
    private final Consumer<String> trace;
    private boolean repairing;
    private int pendingDamage = -1;
    private int pendingSlot = NONE;
    private int bottlesInBurst;
    private int lastThrowTick;
    private int lastBottleCount;
    private FoodSlotSwap foodSwap;
    private boolean restoringFood;
    private boolean restoreSent;
    private boolean restoreWarning;
    private int restoreNotBefore;
    private int nextAction;
    private int confirmationDeadline;

    public ElytraRepair(IPlayerContext ctx, Consumer<String> trace) {
        this.ctx = ctx;
        this.trace = trace;
    }

    public boolean shouldLand() {
        var player = ctx.player();
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return BaritoneAPI.getSettings().elytraAutoRepair.value && player.tickCount >= nextAction
                && allWingsLow(player, chest) && !otherMendingGearNeedsRepair(player)
                && accessibleBottle(player);
    }

    /** Returns true while the player is waiting for a bottle to repair the equipped elytra. */
    public boolean tickGround() {
        var player = ctx.player();
        if (restoringFood) {
            return !restoreFoodSlot();
        }
        if (foodSwap != null && !repairing) {
            return false;
        }
        if (!BaritoneAPI.getSettings().elytraAutoRepair.value) {
            return finish(player);
        }
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!repairing && (!allWingsLow(player, chest) || player.tickCount < nextAction || !accessibleBottle(player))) {
            return false;
        }
        if (!safeGround(player) || !mendingElytra(chest) || otherMendingGearNeedsRepair(player)) {
            if (repairing) {
                trace.accept("repair-interrupted");
            }
            return finish(player);
        }
        if (pendingSlot >= 0) {
            var inventory = player.getInventory();
            if (!ItemStack.matches(inventory.getItem(pendingSlot), foodSwap.originalBottles)
                    || !ItemStack.matches(inventory.getItem(foodSwap.sourceSlot), foodSwap.originalFood)) {
                if (player.tickCount < confirmationDeadline) {
                    return true;
                }
                trace.accept("repair-inventory-unconfirmed");
                return finish(player);
            }
            pendingSlot = NONE;
        }

        if (foodSwap != null && (!ItemStack.matches(player.getInventory().getItem(foodSwap.sourceSlot), foodSwap.originalFood)
                || !matchesRemainingBottles(player.getInventory().getItem(foodSwap.hotbarSlot)))) {
            return finish(player);
        }
        int damage = chest.getDamageValue();
        int slot = foodSwap == null ? bottleSlot(player) : foodSwap.hotbarSlot;
        if (pendingDamage >= 0) {
            if (damage >= pendingDamage) {
                if (player.tickCount >= confirmationDeadline) {
                    trace.accept("repair-no-confirmation");
                    return finish(player);
                }
                if (!canFollowUp(damage, bottlesInBurst, bottleCount(player, slot), lastBottleCount)
                        || player.tickCount < nextAction) {
                    return true;
                }
            } else {
                if (bottlesInBurst == 2 && player.tickCount - lastThrowTick < 8) {
                    return true;
                }
                trace.accept("repair-confirmed");
                pendingDamage = -1;
                bottlesInBurst = 0;
            }
        }
        if (damage <= REPAIR_REMAINDER) {
            return finish(player);
        }
        if (player.tickCount < nextAction) {
            return true;
        }

        if (foodSwap == null) {
            int source = looseBottleSlot(player);
            int target = foodSlot();
            if (source >= 0 && target >= 0 && source != target) {
                var inventory = player.getInventory();
                foodSwap = new FoodSlotSwap(player, source, target,
                        inventory.getItem(target).copy(), inventory.getItem(source).copy());
                beginBottleTransfer(player, source, target);
                return true;
            }
        }
        int count = bottleCount(player, slot);
        if (count == 0) {
            int source = looseBottleSlot(player);
            if (foodSwap != null && source >= 0) {
                foodSwap = new FoodSlotSwap(player, foodSwap.sourceSlot, foodSwap.hotbarSlot,
                        foodSwap.originalFood, player.getInventory().getItem(source).copy());
                beginBottleTransfer(player, source, slot);
                return true;
            }
            return finish(player);
        }

        var inventory = player.getInventory();
        int previousSlot = inventory.getSelectedSlot();
        InteractionHand hand = slot == OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (slot >= 0) {
            inventory.setSelectedSlot(slot);
        }
        try {
            var result = ElytraLaunchPlanner.useWithAim(player, new Rotation(player.getYRot(), 90),
                    () -> ctx.playerController().processRightClick(player, player.level(), hand));
            if (!result.consumesAction()) {
                return finish(player);
            }
        } finally {
            inventory.setSelectedSlot(previousSlot);
        }
        repairing = true;
        trace.accept("repair-bottle-sent");
        pendingDamage = damage;
        lastBottleCount = count;
        bottlesInBurst++;
        lastThrowTick = player.tickCount;
        confirmationDeadline = player.tickCount + CONFIRM_TICKS;
        nextAction = player.tickCount + 3;
        return true;
    }

    static boolean canFollowUp(int damage, int sent, int now, int before) {
        return damage > 44 && sent == 1 && now > 0 && now == before - 1;
    }

    public boolean ownsHotbarSlot(int slot) {
        return foodSwap != null && (foodSwap.hotbarSlot == slot || foodSwap.sourceSlot == slot);
    }

    public void reset() {
        clearRepair();
        nextAction = 0;
        if (foodSwap != null) {
            restoringFood = true;
            restoreFoodSlot();
        }
    }

    public void tickFoodRestore() {
        var player = ctx.player();
        if (foodSwap != null && !repairing && player != null && player.isFallFlying() && !player.onGround()) {
            restoringFood = true;
        }
        if (restoringFood) {
            restoreFoodSlot();
        }
    }

    private void clearRepair() {
        repairing = false;
        pendingDamage = -1;
        pendingSlot = NONE;
        bottlesInBurst = 0;
    }

    private boolean finish(LocalPlayer player) {
        clearRepair();
        nextAction = player.tickCount + 200;
        return false;
    }

    private boolean restoreFoodSlot() {
        var player = ctx.player();
        if (player == null || foodSwap == null) {
            return false;
        }
        if (player != foodSwap.player) {
            clearFoodSwap();
            return true;
        }
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || ctx.minecraft().screen != null) {
            return false;
        }
        var inventory = player.getInventory();
        var hotbar = inventory.getItem(foodSwap.hotbarSlot);
        var source = inventory.getItem(foodSwap.sourceSlot);
        if (ItemStack.matches(hotbar, foodSwap.originalFood) && matchesRemainingBottles(source)) {
            boolean bothEmpty = foodSwap.originalFood.isEmpty() && source.isEmpty() && hotbar.isEmpty();
            if (!bothEmpty && player.tickCount < (restoreSent ? restoreNotBefore : confirmationDeadline)) {
                return false;
            }
            clearFoodSwap();
            trace.accept("repair-food-slot-restored");
            return true;
        }
        if (!restoreSent && ItemStack.matches(source, foodSwap.originalFood) && matchesRemainingBottles(hotbar)) {
            clickSwap(player, foodSwap.sourceSlot, foodSwap.hotbarSlot);
            restoreSent = true;
            restoreNotBefore = player.tickCount + 3;
            confirmationDeadline = player.tickCount + CONFIRM_TICKS;
        } else if (!restoreWarning && player.tickCount >= confirmationDeadline) {
            restoreWarning = true;
            trace.accept("repair-food-slot-restore-blocked");
            player.displayClientMessage(Component.literal(ElytraUi.text(
                    "食物槽暂未恢复，请检查背包", "Food slot not restored yet. Check your inventory.")), true);
        }
        return false;
    }

    private boolean matchesRemainingBottles(ItemStack stack) {
        return stack.isEmpty() || stack.is(Items.EXPERIENCE_BOTTLE)
                && ItemStack.isSameItemSameComponents(stack, foodSwap.originalBottles)
                && stack.getCount() <= foodSwap.originalBottles.getCount();
    }

    private void clearFoodSwap() {
        foodSwap = null;
        restoringFood = false;
        restoreSent = false;
        restoreWarning = false;
    }

    private void beginBottleTransfer(LocalPlayer player, int source, int target) {
        clickSwap(player, source, target);
        repairing = true;
        pendingSlot = target;
        confirmationDeadline = player.tickCount + CONFIRM_TICKS;
        nextAction = player.tickCount + 3;
    }

    private void clickSwap(LocalPlayer player, int source, int target) {
        int menuSlot = source < 9 ? 36 + source : source;
        ctx.playerController().windowClick(player.inventoryMenu.containerId, menuSlot, target, ClickType.SWAP, player);
    }

    private record FoodSlotSwap(LocalPlayer player, int sourceSlot, int hotbarSlot, ItemStack originalFood,
                                ItemStack originalBottles) {}

    private boolean safeGround(LocalPlayer player) {
        if (!player.onGround() || player.isFallFlying() || player.isInLava() || player.isInWater()
                || player.isOnFire() || player.isUsingItem() || player.getDeltaMovement().horizontalDistanceSqr() > 0.0025
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || ctx.minecraft().screen != null) {
            return false;
        }
        var world = player.level();
        BlockPos floor = player.blockPosition().below();
        if (!world.isLoaded(floor) || !world.getBlockState(floor).isCollisionShapeFullBlock(world, floor)) {
            return false;
        }
        var area = player.getBoundingBox().inflate(1, 0, 1).expandTowards(0, -1, 0);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            if (!world.isLoaded(pos) || world.getFluidState(pos).is(FluidTags.LAVA)
                    || ElytraLaunchPlanner.isHot(world.getBlockState(pos))) {
                return false;
            }
        }
        return !ElytraLaunchPlanner.hasFallingLava(player, area)
                && world.getEntities(player, area.inflate(24), entity -> entity.isAlive()
                        && (entity instanceof Enemy && player.hasLineOfSight(entity)
                        || entity instanceof AbstractHurtingProjectile)).isEmpty();
    }

    private static boolean allWingsLow(LocalPlayer player, ItemStack chest) {
        if (!mendingElytra(chest)) {
            return false;
        }
        int threshold = Math.clamp(BaritoneAPI.getSettings().elytraRepairThreshold.value, 1, chest.getMaxDamage());
        if (aboveThreshold(chest, threshold)
                || aboveThreshold(player.getOffhandItem(), threshold)
                || aboveThreshold(player.containerMenu.getCarried(), threshold)) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (aboveThreshold(stack, threshold)) {
                return false;
            }
        }
        return true;
    }

    private static boolean aboveThreshold(ItemStack stack, int threshold) {
        return stack.is(Items.ELYTRA) && stack.getMaxDamage() - stack.getDamageValue() > threshold;
    }

    private static boolean mendingElytra(ItemStack chest) {
        return chest.is(Items.ELYTRA) && EnchantmentHelper.has(chest, EnchantmentEffectComponents.REPAIR_WITH_XP);
    }

    private static boolean otherMendingGearNeedsRepair(LocalPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.CHEST) {
                continue;
            }
            var stack = player.getItemBySlot(slot);
            if (stack.isDamaged() && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP)) {
                return true;
            }
        }
        return false;
    }

    private static int bottleSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(Items.EXPERIENCE_BOTTLE)) {
                return slot;
            }
        }
        return player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE) ? OFFHAND : NONE;
    }

    private static int foodSlot() {
        var settings = BaritoneAPI.getSettings();
        int food = Math.clamp(settings.elytraFoodSlot.value, 1, 9) - 1;
        int rockets = Math.clamp(settings.elytraRocketSlot.value, 1, 9) - 1;
        return food == rockets ? NONE : food;
    }

    private static int looseBottleSlot(LocalPlayer player) {
        int preferred = foodSlot();
        if (preferred >= 0 && player.getInventory().getItem(preferred).is(Items.EXPERIENCE_BOTTLE)) {
            return preferred;
        }
        int rockets = Math.clamp(BaritoneAPI.getSettings().elytraRocketSlot.value, 1, 9) - 1;
        for (int slot = 0; slot < 36; slot++) {
            if (slot != rockets && player.getInventory().getItem(slot).is(Items.EXPERIENCE_BOTTLE)) {
                return slot;
            }
        }
        return NONE;
    }

    private static int bottleCount(LocalPlayer player, int slot) {
        if (slot == NONE) {
            return 0;
        }
        ItemStack stack = slot == OFFHAND ? player.getOffhandItem() : player.getInventory().getItem(slot);
        return stack.is(Items.EXPERIENCE_BOTTLE) ? stack.getCount() : 0;
    }

    private static boolean accessibleBottle(LocalPlayer player) {
        return bottleSlot(player) != NONE || foodSlot() != NONE && looseBottleSlot(player) >= 0;
    }
}
