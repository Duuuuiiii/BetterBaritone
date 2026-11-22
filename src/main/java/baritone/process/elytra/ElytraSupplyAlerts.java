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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static baritone.process.elytra.ElytraUi.text;

public final class ElytraSupplyAlerts {
    private static final int LOW = 1;
    private static final int URGENT = 2;
    private final int[] levels = new int[Supply.values().length];
    private final EnumSet<Supply> pending = EnumSet.noneOf(Supply.class);
    private Supply playing;
    private int note;
    private int nextNote;

    public void update(LocalPlayer player, int rockets, ElytraFlightStats.Estimate estimate, Consumer<String> notify) {
        var settings = BaritoneAPI.getSettings();
        int food = nutrition(player.getOffhandItem());
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            food += nutrition(stack);
        }
        int hunger = player.getFoodData().getFoodLevel();
        int foodLevel = (hunger <= 6 || food == 0 && hunger <= 10) ? URGENT : food <= 8 ? LOW : 0;
        update(Supply.FOOD, foodLevel, notify);

        int rocketBudget = Math.max(0, rockets - settings.elytraMinFireworksBeforeLanding.value);
        long requiredRockets = estimate == null ? -1 : estimate.rockets();
        update(Supply.ROCKETS, rocketBudget <= 8 ? URGENT : requiredRockets > rocketBudget ? LOW : 0, notify);

        int reserve = settings.elytraMinimumDurability.value;
        int durabilityBudget = usableDurability(player.getItemBySlot(EquipmentSlot.CHEST), reserve);
        if (settings.elytraAutoSwap.value) {
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                durabilityBudget += usableDurability(stack, reserve);
            }
        }
        long requiredWear = estimate == null ? -1 : estimate.durability();
        update(Supply.ELYTRA, durabilityBudget <= 20 ? URGENT : requiredWear > durabilityBudget ? LOW : 0, notify);
    }

    private void update(Supply supply, int level, Consumer<String> notify) {
        int before = levels[supply.ordinal()];
        levels[supply.ordinal()] = level;
        if (level == 0) {
            pending.remove(supply);
        } else if (level > before) {
            notify.accept(text(supply.chinese, supply.english)
                    + (level == URGENT ? text("告急", " critically low") : text("不足", " low")));
            pending.add(supply);
        }
    }

    public void tick(LocalPlayer player) {
        if (!ElytraPanelOptions.get().elytraSupplyAlertSound.get()) {
            pending.clear();
            playing = null;
            return;
        }
        if (playing != null && levels[playing.ordinal()] == 0) {
            playing = null;
        }
        if (player.tickCount < nextNote) {
            return;
        }
        if (playing == null) {
            for (Supply supply : pending) {
                if (playing == null || levels[supply.ordinal()] > levels[playing.ordinal()]) {
                    playing = supply;
                }
            }
            if (playing == null) {
                return;
            }
            pending.remove(playing);
            note = 0;
        }
        boolean urgent = levels[playing.ordinal()] == URGENT;
        float pitch = playing.pitches[note] + (urgent ? 0.13f : 0);
        player.playSound(playing.sound.get(), urgent ? 0.58f : 0.48f, pitch);
        if (++note >= (urgent ? 3 : 2)) {
            playing = null;
            nextNote = player.tickCount + 12;
        } else {
            nextNote = player.tickCount + (urgent ? 3 : 6);
        }
    }

    public void reset() {
        Arrays.fill(levels, 0);
        pending.clear();
        playing = null;
        nextNote = 0;
    }

    private static int nutrition(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        return food == null || !stack.has(DataComponents.CONSUMABLE) ? 0 : food.nutrition() * stack.getCount();
    }

    private static int usableDurability(ItemStack stack, int reserve) {
        return stack.is(Items.ELYTRA) ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue() - reserve) : 0;
    }

    private enum Supply {
        FOOD("食物", "Food", SoundEvents.NOTE_BLOCK_HARP::value, 0.93f, 1.16f, 1.16f),
        ROCKETS("烟花", "Fireworks", () -> SoundEvents.AMETHYST_BLOCK_CHIME, 1.24f, 1.49f, 1.64f),
        ELYTRA("鞘翅耐久", "Elytra durability", SoundEvents.NOTE_BLOCK_BELL::value, 1.23f, 0.97f, 0.97f);

        final String chinese;
        final String english;
        final Supplier<SoundEvent> sound;
        final float[] pitches;

        Supply(String chinese, String english, Supplier<SoundEvent> sound, float... pitches) {
            this.chinese = chinese;
            this.english = english;
            this.sound = sound;
            this.pitches = pitches;
        }
    }
}
