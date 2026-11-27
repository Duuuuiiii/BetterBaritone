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
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ElytraKeybinds {
    private boolean settingsDown;
    private boolean takeoverDown;
    private boolean landDown;
    private boolean pauseDown;

    public void tick(Minecraft client) {
        ElytraPanelOptions settings = ElytraPanelOptions.get();
        boolean open = down(client, settings.elytraSettingsKey.get());
        boolean takeover = down(client, settings.elytraTakeoverKey.get());
        boolean pause = down(client, settings.elytraPauseKey.get());
        boolean land = down(client, settings.elytraLandKey.get());
        if (client.player != null && client.screen == null && client.isWindowActive()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() instanceof ElytraPanelFlight process) {
                if (pause && !this.pauseDown) {
                    process.toggleFlightPause();
                } else if (takeover && !this.takeoverDown) {
                    if (ElytraSurvey.INSTANCE.isActive()) {
                        ElytraSurvey.INSTANCE.stop();
                    } else {
                        process.takeOver();
                    }
                } else if (land && !this.landDown) {
                    if (ElytraSurvey.INSTANCE.isActive()) {
                        ElytraSurvey.INSTANCE.finish(ElytraUi.text("探索已结束，正在降落", "Exploration ended, landing"));
                    }
                    process.requestLanding();
                }
            }
            if (open && !this.settingsDown) {
                client.setScreen(new ElytraFlightScreen());
            }
        }
        ElytraSurvey.INSTANCE.tick(client);
        this.settingsDown = open;
        this.takeoverDown = takeover;
        this.landDown = land;
        this.pauseDown = pause;
    }

    private boolean down(Minecraft client, int key) {
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                && GLFW.glfwGetKey(client.getWindow().handle(), key) == GLFW.GLFW_PRESS;
    }
}
