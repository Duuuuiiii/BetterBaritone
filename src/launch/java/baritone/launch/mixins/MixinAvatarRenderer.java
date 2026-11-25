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

import baritone.api.BaritoneAPI;
import baritone.api.utils.Rotation;
import baritone.behavior.LookBehavior;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class MixinAvatarRenderer {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"))
    private void renderAutomatedGlide(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!(avatar instanceof LocalPlayer player) || !player.isFallFlying() || state.isAutoSpinAttack) {
            return;
        }
        var baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        if (baritone == null) {
            return;
        }
        Rotation flight = ((LookBehavior) baritone.getLookBehavior()).getFlightRenderRotation(partialTick);
        if (flight != null) {
            state.bodyRot = flight.getYaw();
            state.yRot = 0;
            state.xRot = state.isUpsideDown ? -flight.getPitch() : flight.getPitch();
            state.shouldApplyFlyingYRot = false;
        }
    }
}
