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

    enum Style {
        PILL("胶囊", "Pill"), CARD("双行卡片", "Card"), TEXT("纯文字", "Text"), MINIMAL("极简", "Minimal");

        private final String chinese;
        private final String english;

        Style(String chinese, String english) {
            this.chinese = chinese;
            this.english = english;
        }

        String label() {
            return text(this.chinese, this.english);
        }
    }

    static Style style() {
        return Style.values()[Math.clamp(SETTINGS.elytraHudStyle.get(), 0, Style.values().length - 1)];
    }

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
            this.time = estimate == null ? null : timeForStyle(estimate.seconds());
            this.distance = distanceForStyle(process.remainingDistance());
            this.sampledTick = tick;
        }
        drawFlight(graphics, this.time, this.distance, null);
    }

    static void preview(GuiGraphics graphics, int centerX, int centerY) {
        if (SETTINGS.elytraFlightHud.get()) {
            drawFlight(graphics, timeForStyle(4800), distanceForStyle(23500), new Position(centerX, centerY));
        }
    }

    private static void drawPaused(GuiGraphics graphics, long now) {
        var font = Minecraft.getInstance().font;
        String label = style() == Style.MINIMAL ? "" : text("已暂停", "Paused");
        int width = label.isEmpty() ? 22 : ElytraUi.width(font, label) + 29;
        Position pos = locate(graphics, width, 16);
        double pulse = 0.85 + 0.15 * Math.cos(now / 3_200_000_000.0 * Math.TAU);
        int red = ElytraUi.withOpacity(SETTINGS.elytraDarkTheme.get() ? 0xFFFF8A8A : 0xFFB62E39, pulse);
        if (style() != Style.TEXT && style() != Style.MINIMAL) {
            ElytraUi.rounded(graphics, pos.x, pos.y, width, 16, ElytraUi.colors().panel(), 7);
        }
        graphics.fill(pos.x + 8, pos.y + 5, pos.x + 10, pos.y + 11, red);
        graphics.fill(pos.x + 12, pos.y + 5, pos.x + 14, pos.y + 11, red);
        ElytraUi.drawText(graphics, font, label, pos.x + 20, pos.y + 4, red);
    }

    private static void drawFlight(GuiGraphics graphics, String time, String distance, Position previewCenter) {
        var font = Minecraft.getInstance().font;
        ElytraUi.Palette colors = ElytraUi.colors();
        Style style = style();
        if (style == Style.MINIMAL) {
            String content = time == null ? distance : time + " · " + distance;
            int width = ElytraUi.width(font, content) + 10;
            Position pos = previewCenter == null ? locate(graphics, width, 13)
                    : new Position(previewCenter.x - width / 2, previewCenter.y - 6);
            ElytraUi.rounded(graphics, pos.x, pos.y, width, 13, ElytraUi.withOpacity(colors.panel(), 0.84), 6);
            ElytraUi.drawText(graphics, font, content, pos.x + 5, pos.y + 2, colors.text());
            return;
        }
        String timeLabel = time == null ? "" : text("预计剩余", "ETA") + "  " + time;
        int timeWidth = ElytraUi.width(font, timeLabel);
        int distanceWidth = ElytraUi.width(font, distance);
        boolean stacked = style == Style.CARD && time != null;
        int gap = time == null || stacked ? 0 : 16;
        int width = (stacked ? Math.max(timeWidth, distanceWidth) : timeWidth + gap + distanceWidth) + 22;
        int height = stacked ? 30 : 16;
        Position pos = previewCenter == null ? locate(graphics, width, height)
                : new Position(previewCenter.x - width / 2, previewCenter.y - height / 2);

        if (style != Style.TEXT) {
            ElytraUi.rounded(graphics, pos.x, pos.y, width, height, colors.panel(), 8);
        }
        ElytraUi.rounded(graphics, pos.x + 7, pos.y + 6, 4, 4, colors.accent(), 2);
        if (time != null) {
            ElytraUi.drawText(graphics, font, timeLabel, pos.x + 15, pos.y + 4, colors.text());
            if (!stacked) {
                int divider = pos.x + 15 + timeWidth + gap / 2;
                graphics.fill(divider, pos.y + 4, divider + 1, pos.y + 12, colors.muted());
            }
        }
        int distanceX = pos.x + 15 + (stacked ? 0 : timeWidth + gap);
        int distanceY = pos.y + (stacked ? 17 : 4);
        ElytraUi.drawText(graphics, font, distance, distanceX, distanceY, stacked ? colors.muted() : colors.text());
    }

    private static Position locate(GuiGraphics graphics, int width, int height) {
        int anchor = Math.clamp(SETTINGS.elytraHudAnchor.get(), 0, 8);
        return new Position(anchorAxis(anchor % 3, graphics.guiWidth(), width, SETTINGS.elytraHudOffsetX.get()),
                anchorAxis(anchor / 3, graphics.guiHeight(), height, SETTINGS.elytraHudOffsetY.get()));
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

    private static String timeForStyle(long seconds) {
        if (style() != Style.MINIMAL) {
            return formatMinutes(seconds);
        }
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        return minutes >= 60 ? minutes / 60 + "h" + minutes % 60 + "m" : minutes + "m";
    }

    private static String distanceForStyle(double blocks) {
        if (style() == Style.MINIMAL) {
            if (blocks < 1000) {
                return Math.round(blocks) + text("格", "b");
            }
            return SETTINGS.elytraUseTenThousands.get() && !SETTINGS.elytraEnglishUi.get()
                    ? String.format(java.util.Locale.ROOT, "%.1f万", blocks / 10_000)
                    : String.format(java.util.Locale.ROOT, "%.1fk", blocks / 1000);
        }
        return text("剩余 ", "Left ") + ElytraFlightScreen.distance(blocks);
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
