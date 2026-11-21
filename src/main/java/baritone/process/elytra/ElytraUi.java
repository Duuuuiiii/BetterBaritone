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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

final class ElytraUi {
    private static final Style UI_TEXT = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("betterbaritone", "ui")))
            .withoutShadow();
    private static final Palette LIGHT = new Palette(0xFFFCFBFA, 0xFF222125, 0xFF625F68,
            0xFFF0EEEF, 0xFFE7E3E7, 0xFFEEE1EB, 0xFF8F386D, 0xFF312732, 0xFFFFFFFF);
    private static final Palette DARK = new Palette(0xFF202024, 0xFFF1EEF2, 0xFFB8B2BE,
            0xFF2D2C32, 0xFF3A3840, 0xFF483343, 0xFFF1A5D3, 0xFFE8CDE0, 0xFF2C202B);
    private static double themeMix = Baritone.settings().elytraDarkTheme.value ? 1 : 0;
    private static long previousFrame;
    private static double opacity = 1;

    private ElytraUi() {}

    static Component label(String text) {
        return Component.literal(text).setStyle(UI_TEXT);
    }

    static int width(Font font, String text) {
        return font.width(label(text));
    }

    static void drawText(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawString(font, label(text), x, y, color, false);
    }

    static void animateTheme(long now) {
        double elapsed = previousFrame == 0 ? 0.15 : Math.min(0.15, (now - previousFrame) / 1e9);
        previousFrame = now;
        double target = Baritone.settings().elytraDarkTheme.value ? 1 : 0;
        themeMix += (target - themeMix) * (1 - Math.exp(-elapsed * 19));
    }

    static void setOpacity(double value) {
        opacity = value;
    }

    private static int color(int from, int to) {
        return withOpacity(blend(from, to, themeMix), opacity);
    }

    static int withOpacity(int color, double opacity) {
        return (color & 0x00FFFFFF) | ((int) Math.round((color >>> 24) * opacity) << 24);
    }

    static Palette colors() {
        return new Palette(color(LIGHT.panel, DARK.panel), color(LIGHT.text, DARK.text),
                color(LIGHT.muted, DARK.muted), color(LIGHT.surface, DARK.surface),
                color(LIGHT.line, DARK.line), color(LIGHT.hover, DARK.hover),
                color(LIGHT.accent, DARK.accent), color(LIGHT.primary, DARK.primary),
                color(LIGHT.onPrimary, DARK.onPrimary));
    }

    static int blend(int from, int to, double factor) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = from >>> shift & 255;
            int b = to >>> shift & 255;
            result |= (int) Math.round(a + (b - a) * factor) << shift;
        }
        return result;
    }

    static void rounded(GuiGraphics graphics, int x, int y, int w, int h, int color, int radius) {
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (w <= 0 || h <= 0) {
            return;
        }
        graphics.fill(x, y + r, x + w, y + h - r, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            double edge = r - Math.sqrt(r * r - dy * dy);
            int inset = (int) Math.ceil(edge);
            drawEdgeRow(graphics, x, y + row, w, inset, edge, color);
            drawEdgeRow(graphics, x, y + h - row - 1, w, inset, edge, color);
        }
    }

    private static void drawEdgeRow(GuiGraphics graphics, int x, int y, int w, int inset, double edge, int color) {
        graphics.fill(x + inset, y, x + w - inset, y + 1, color);
        if (inset > edge) {
            int fringe = withOpacity(color, inset - edge);
            graphics.fill(x + inset - 1, y, x + inset, y + 1, fringe);
            graphics.fill(x + w - inset, y, x + w - inset + 1, y + 1, fringe);
        }
    }

    static void playFeedback(SoundManager sounds) {
        sounds.play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_HIT, 1.34f, 0.24f));
    }

    static Button button(Component label, int x, int y, int width, int height, boolean primary, Button.OnPress press) {
        return new FlightButton(ElytraUi.label(label.getString()), x, y, width, height, primary, press);
    }

    private static final class FlightButton extends Button {
        private final boolean primary;
        private long lastFrame;
        private long lastPress;
        private double highlight;

        FlightButton(Component label, int x, int y, int width, int height, boolean primary, OnPress press) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.primary = primary;
        }

        @Override
        public void playDownSound(SoundManager sounds) {
            this.lastPress = System.nanoTime();
            playFeedback(sounds);
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            long now = System.nanoTime();
            double dt = this.lastFrame == 0 ? 0.02 : Math.min(0.15, (now - this.lastFrame) / 1e9);
            this.lastFrame = now;
            this.highlight += ((isHoveredOrFocused() && this.active ? 1 : 0) - this.highlight)
                    * (1 - Math.exp(-dt * 23));
            Palette c = colors();
            double press = this.lastPress <= 0 ? 0 : Math.max(0, 1 - (now - this.lastPress) / 100_000_000.0);
            int surface = primary ? blend(c.primary, c.accent, this.highlight * 0.22 + press * 0.14)
                    : blend(c.surface, c.hover, Math.max(this.highlight, press));
            rounded(graphics, getX(), getY(), getWidth(), getHeight(), surface, 7);
            Font font = Minecraft.getInstance().font;
            int color = !this.active ? c.muted : primary ? c.onPrimary : blend(c.text, c.accent, this.highlight);
            graphics.drawString(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2,
                    getY() + (getHeight() - 8) / 2, color, false);
        }
    }

    static EditBox input(Font font, int x, int y, int width, Component label) {
        EditBox field = new EditBox(font, x + 8, y + 8, width - 16, 12, label) {
            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                Palette c = colors();
                rounded(graphics, getX() - 8, getY() - 8, getWidth() + 16, 26,
                        isFocused() ? c.hover : c.surface, 7);
                setTextColor(c.text);
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            }
        };
        field.setBordered(false);
        field.setTextShadow(false);
        field.addFormatter((text, offset) -> ElytraUi.label(text).getVisualOrderText());
        return field;
    }

    record Palette(int panel, int text, int muted, int surface, int line, int hover, int accent,
                   int primary, int onPrimary) {}
}
