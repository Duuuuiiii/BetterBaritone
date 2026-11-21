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

package baritone.launch.addon;

import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.ElytraPanelOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;
import java.util.List;

// Baritone's unoptimized classes retain these method names; Minecraft types in the descriptors use intermediary.
@Mixin(value = ElytraBehavior.class, remap = false)
public abstract class MixinAddonRendering {
    @Shadow private List<?> visiblePath;

    @Redirect(method = "onRenderPass", at = @At(value = "FIELD",
            target = "Lbaritone/process/elytra/ElytraBehavior;visiblePath:Ljava/util/List;", opcode = 180))
    private List<?> betterBaritone$visiblePath(ElytraBehavior behavior) {
        return ElytraPanelOptions.get().elytraRenderRoute.get() ? visiblePath : null;
    }

    @ModifyArg(method = "onRenderPass", at = @At(value = "INVOKE",
            target = "Lbaritone/utils/PathRenderer;drawPath(Lnet/minecraft/class_4587;Ljava/util/List;ILjava/awt/Color;ZIID)V"), index = 3)
    private Color betterBaritone$routeColor(Color original) {
        return ElytraPanelOptions.get().elytraRouteColor.get();
    }

    @ModifyArg(method = "onRenderPass", at = @At(value = "INVOKE",
            target = "Lbaritone/utils/IRenderer;startLines(Ljava/awt/Color;)Lnet/minecraft/class_287;", ordinal = 2), index = 0)
    private Color betterBaritone$simulationColor(Color original) {
        return ElytraPanelOptions.get().elytraSimulationColor.get();
    }
}
