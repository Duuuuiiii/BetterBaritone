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
import baritone.process.ElytraProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;

public final class ElytraFlightHud {
    private static final String LABEL = "预计剩余";
    private int sampledTick = Integer.MIN_VALUE;
    private String time;

    public void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!Baritone.settings().elytraFlightHud.value || client.options.hideGui || (client.screen != null && !(client.screen instanceof ChatScreen))
                || client.player == null || client.getDebugOverlay().showDebugScreen()
                || !(BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() instanceof ElytraProcess process)
                || (!process.isActive() && !process.isFlightPaused())) {
            this.sampledTick = Integer.MIN_VALUE;
            return;
        }

        int tick = client.player.tickCount;
        if (this.sampledTick == Integer.MIN_VALUE || tick < this.sampledTick || tick - this.sampledTick >= 10) {
            ElytraFlightStats.Estimate estimate = process.flightEstimate();
            this.time = process.isFlightPaused() ? process.flightStatus() : estimate == null ? null : formatMinutes(estimate.seconds());
            this.sampledTick = tick;
        }
        if (this.time == null) {
            return;
        }

        var font = client.font;
        String label = process.isFlightPaused() ? "自动飞行" : LABEL;
        int width = ElytraUi.width(font, label) + ElytraUi.width(font, this.time) + 26;
        int left = (graphics.guiWidth() - width) / 2;
        int right = left + width;
        int top = graphics.guiHeight() / 2 + 23;

        ElytraUi.animateTheme(System.nanoTime());
        ElytraUi.Palette c = ElytraUi.colors();
        ElytraUi.rounded(graphics, left, top, width, 16, c.panel(), 7);
        graphics.fill(left + 7, top + 6, left + 10, top + 9, c.accent());
        ElytraUi.drawText(graphics, font, label, left + 15, top + 4, c.muted());
        ElytraUi.drawText(graphics, font, this.time, right - 6 - ElytraUi.width(font, this.time), top + 4, c.text());
    }

    static String formatMinutes(long seconds) {
        if (seconds < 60) {
            return "不到 1 分钟";
        }
        long minutes = Math.round(seconds / 60.0);
        long hours = minutes / 60;
        return hours > 0 ? hours + " 小时 " + minutes % 60 + " 分钟" : minutes + " 分钟";
    }
}
