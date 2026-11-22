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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;

import static baritone.process.elytra.ElytraUi.text;

public final class ElytraFlightHud {
    private static final ElytraPanelOptions SETTINGS = ElytraPanelOptions.get();
    private int sampledTick = Integer.MIN_VALUE;
    private String time;
    private String distance;

    public void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!SETTINGS.elytraFlightHud.get() || client.options.hideGui || (client.screen != null && !(client.screen instanceof ChatScreen))
                || client.player == null || client.getDebugOverlay().showDebugScreen()
                || !(BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() instanceof ElytraPanelFlight process)
                || (!process.isActive() && !process.isFlightPaused())) {
            this.sampledTick = Integer.MIN_VALUE;
            return;
        }

        long now = System.nanoTime();
        ElytraUi.animateTheme(now);
        if (process.isFlightPaused()) {
            this.sampledTick = Integer.MIN_VALUE;
            drawPaused(graphics, now);
            return;
        }

        int tick = client.player.tickCount;
        if (this.sampledTick == Integer.MIN_VALUE || tick < this.sampledTick || tick - this.sampledTick >= 10) {
            ElytraFlightStats.Estimate estimate = process.flightEstimate();
            this.time = estimate == null ? null : compactTime(estimate.seconds());
            this.distance = compactDistance(process.remainingDistance());
            this.sampledTick = tick;
        }
        Layout layout = layout(this.time, this.distance);
        drawFlight(graphics, layout, locate(graphics, layout.width, layout.height,
                SETTINGS.elytraHudOffsetX.get(), SETTINGS.elytraHudOffsetY.get()));
    }

    static final long PREVIEW_SECONDS = 4800;
    static final int PREVIEW_BLOCKS = 23500;

    static Layout previewLayout() {
        return layout(compactTime(PREVIEW_SECONDS), compactDistance(PREVIEW_BLOCKS));
    }

    static void preview(GuiGraphics graphics, Layout layout, int offsetX, int offsetY) {
        drawFlight(graphics, layout, locate(graphics, layout.width, layout.height, offsetX, offsetY));
    }

    static int offsetForPosition(int align, int viewport, int extent, int position) {
        int clamped = Math.clamp(position, 0, Math.max(0, viewport - extent));
        return clamped - anchorAxis(align, viewport, extent, 0);
    }

    private static void drawPaused(GuiGraphics graphics, long now) {
        Position pos = locate(graphics, 22, 16, SETTINGS.elytraHudOffsetX.get(), SETTINGS.elytraHudOffsetY.get());
        double pulse = 0.85 + 0.15 * Math.cos(now / 3_200_000_000.0 * Math.TAU);
        int red = ElytraUi.withOpacity(SETTINGS.elytraDarkTheme.get() ? 0xFFFF8A8A : 0xFFB62E39, pulse);
        graphics.fill(pos.x + 8, pos.y + 5, pos.x + 10, pos.y + 11, red);
        graphics.fill(pos.x + 12, pos.y + 5, pos.x + 14, pos.y + 11, red);
    }

    private static Layout layout(String time, String distance) {
        var font = Minecraft.getInstance().font;
        String content = time == null ? distance : time + " · " + distance;
        return new Layout(content, ElytraUi.width(font, content) + 10, 13);
    }

    private static void drawFlight(GuiGraphics graphics, Layout layout, Position pos) {
        var colors = ElytraUi.colors();
        ElytraUi.rounded(graphics, pos.x, pos.y, layout.width, layout.height, ElytraUi.withOpacity(colors.panel(), 0.84), 6);
        ElytraUi.drawText(graphics, Minecraft.getInstance().font, layout.content, pos.x + 5, pos.y + 2, colors.text());
    }

    private static Position locate(GuiGraphics graphics, int width, int height, int offsetX, int offsetY) {
        int anchor = Math.clamp(SETTINGS.elytraHudAnchor.get(), 0, 8);
        return new Position(anchorAxis(anchor % 3, graphics.guiWidth(), width, offsetX),
                anchorY(anchor, graphics.guiHeight(), height, offsetY));
    }

    record Layout(String content, int width, int height) {}

    static int offsetForVerticalPosition(int anchor, int viewport, int extent, int position) {
        int clamped = Math.clamp(position, 0, Math.max(0, viewport - extent));
        return clamped - anchorY(anchor, viewport, extent, 0);
    }

    private static int anchorY(int anchor, int viewport, int extent, int offset) {
        int origin = anchorAxis(anchor / 3, viewport, extent, 0);
        int inset = anchor == 4 ? 23 : 0;
        origin = Math.min(origin + inset, Math.max(0, viewport - extent));
        return Math.clamp((long) origin + offset, 0, Math.max(0, viewport - extent));
    }

    static int anchorAxis(int align, int viewport, int extent, int offset) {
        int edge = Math.max(0, viewport - extent);
        int margin = Math.min(8, edge / 2);
        int origin = switch (align) {
            case 0 -> margin;
            case 2 -> edge - margin;
            default -> edge / 2;
        };
        return Math.clamp((long) origin + offset, 0, edge);
    }

    private record Position(int x, int y) {}

    private static String compactTime(long seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        return minutes >= 60 ? minutes / 60 + "h" + minutes % 60 + "m" : minutes + "m";
    }

    private static String compactDistance(double blocks) {
        if (blocks < 1000) {
            return Math.round(blocks) + text("格", "b");
        }
        return SETTINGS.elytraUseTenThousands.get() && !SETTINGS.elytraEnglishUi.get()
                ? String.format(java.util.Locale.ROOT, "%.1f万", blocks / 10_000)
                : String.format(java.util.Locale.ROOT, "%.1fk", blocks / 1000);
    }

    static String formatMinutes(long seconds) {
        if (seconds < 60) {
            return text("不到 1 分钟", "under 1 min");
        }
        long minutes = Math.round(seconds / 60.0);
        long hours = minutes / 60;
        return hours > 0 ? hours + text(" 小时 ", " h ") + minutes % 60 + text(" 分钟", " min")
                : minutes + text(" 分钟", " min");
    }
}
