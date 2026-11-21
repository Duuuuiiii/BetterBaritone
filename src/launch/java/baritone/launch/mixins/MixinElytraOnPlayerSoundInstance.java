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

package baritone.launch.mixins;

import baritone.process.elytra.ElytraPanelOptions;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.ElytraOnPlayerSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ElytraOnPlayerSoundInstance.class)
public abstract class MixinElytraOnPlayerSoundInstance extends AbstractTickableSoundInstance {
    protected MixinElytraOnPlayerSoundInstance(SoundEvent sound, SoundSource source, RandomSource random) {
        super(sound, source, random);
    }

    @Override
    public float getVolume() {
        return ElytraPanelOptions.get().elytraMuteGlideSound.get() ? 0 : super.getVolume();
    }

    @Override
    public boolean canStartSilent() {
        return ElytraPanelOptions.get().elytraMuteGlideSound.get() || super.canStartSilent();
    }
}
