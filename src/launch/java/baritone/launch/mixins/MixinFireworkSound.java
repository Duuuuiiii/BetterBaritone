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
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class MixinFireworkSound {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void betterBaritone$muteFirework(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
        var id = sound.getIdentifier();
        if (id.getNamespace().equals("minecraft") && id.getPath().startsWith("entity.firework_rocket.")
                && ElytraPanelOptions.get().elytraMuteRocketSound.get()) {
            callback.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
