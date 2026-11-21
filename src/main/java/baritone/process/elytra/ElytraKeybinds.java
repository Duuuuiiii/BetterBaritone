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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.process.ElytraProcess;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ElytraKeybinds {
    private boolean settingsDown;
    private boolean takeoverDown;
    private boolean landDown;
    private boolean pauseDown;

    public void tick(Minecraft client) {
        Settings settings = Baritone.settings();
        boolean open = down(client, settings.elytraSettingsKey.value);
        boolean takeover = down(client, settings.elytraTakeoverKey.value);
        boolean pause = down(client, settings.elytraPauseKey.value);
        boolean land = down(client, settings.elytraLandKey.value);
        if (client.player != null && client.screen == null && client.isWindowActive()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() instanceof ElytraProcess process) {
                if (pause && !this.pauseDown) {
                    ElytraUi.playFeedback(client.getSoundManager());
                    process.toggleFlightPause();
                } else if (takeover && !this.takeoverDown) {
                    process.takeOver();
                } else if (land && !this.landDown) {
                    process.requestLanding();
                }
            }
            if (open && !this.settingsDown) {
                ElytraUi.playFeedback(client.getSoundManager());
                client.setScreen(new ElytraFlightScreen());
            }
        }
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
