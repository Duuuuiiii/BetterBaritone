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

import baritone.process.elytra.ElytraFlightScreen;
import baritone.process.elytra.ElytraSurveyScreen;
import baritone.process.elytra.ElytraUi;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ElytraFlightScreen.class, remap = false)
public abstract class MixinElytraSurveyScreen {
    @Shadow private int left;
    @Shadow private int top;
    @Shadow private int panelWidth;

    @Shadow
    private void chromeButton(String label, int x, int y, int width, int height, boolean primary, Runnable action) {
        throw new AssertionError();
    }

    @Inject(method = "flightControls", at = @At("RETURN"))
    private void addSurveyButton(boolean compact, CallbackInfo ci) {
        String label = ElytraUi.text("探索", "Explore");
        chromeButton(label, left + panelWidth - 97, top + 9, 56, 18, false,
                () -> Minecraft.getInstance().setScreen(new ElytraSurveyScreen((ElytraFlightScreen) (Object) this)));
    }
}
