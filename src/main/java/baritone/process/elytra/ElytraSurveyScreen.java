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
import baritone.api.IBaritone;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import static baritone.process.elytra.ElytraUi.text;

public final class ElytraSurveyScreen extends Screen {
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Screen previous;
    private final long openedAt = System.nanoTime();
    private SurveyDraft survey;
    private String error = "";
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;

    public ElytraSurveyScreen(Screen previous) {
        super(ElytraUi.label(text("地狱探索", "Nether exploration")));
        this.previous = previous;
    }

    @Override
    protected void init() {
        if (this.survey == null) {
            var position = this.minecraft.player == null ? BlockPos.ZERO : this.minecraft.player.blockPosition();
            this.survey = new SurveyDraft(position, ElytraSurvey.INSTANCE.lastConfig(this.minecraft, this.baritone));
        }
        this.panelWidth = Math.min(this.width - 16, 468);
        this.panelHeight = Math.min(this.height - 16, 244);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - this.panelHeight) / 2;
        this.contentTop = this.top + 38;
        button("×", this.left + this.panelWidth - 34, this.top + 10, 18, 18, false, this::onClose);
        surveyControls();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void button(String label, int x, int y, int width, int height, boolean primary, Runnable action) {
        addRenderableWidget(ElytraUi.button(Component.literal(label), x, y, width, height, primary, ignored -> action.run()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        ElytraUi.animateTheme(now);
        double phase = Math.min(1, (now - this.openedAt) / 180_000_000.0);
        ElytraUi.setOpacity(1 - (1 - phase) * (1 - phase));
        try {
            var colors = ElytraUi.colors();
            graphics.fill(0, 0, this.width, this.height, ElytraUi.withOpacity(0x70000000, phase));
            ElytraUi.rounded(graphics, this.left, this.top, this.panelWidth, this.panelHeight, colors.panel(), 13);
            ElytraUi.drawText(graphics, this.font, this.title.getString(), this.left + 16, this.top + 15, colors.text());
            drawSurvey(graphics, colors);
            super.render(graphics, mouseX, mouseY, partialTick);
        } finally {
            ElytraUi.setOpacity(1);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.previous);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void surveyControls() {
        var draft = this.survey;
        int x = this.left + 16;
        int y = this.contentTop;
        int width = this.panelWidth - 32;
        int col = (width - 12) / 3;
        int row = Math.min(30, Math.max(21, (this.top + this.panelHeight - y - 22) / 5));
        button(text("随机探索", "Random"), x, y, width / 2 - 3, 18, draft.random, () -> {
            draft.random = true;
            rebuild();
        });
        button(text("螺旋扫图", "Spiral"), x + width / 2 + 3, y, width - width / 2 - 3, 18, !draft.random, () -> {
            draft.random = false;
            rebuild();
        });
        surveyInput("X", "X", 0, draft.x, x, y + row, col, value -> draft.x = value);
        surveyInput("Z", "Z", 1, draft.z, x, y + row, col, value -> draft.z = value);
        button(text("使用当前位置", "Use current position"), x + 2 * (col + 6), y + row, col, 19, false, () -> {
            if (this.minecraft.player != null) {
                var position = this.minecraft.player.blockPosition();
                draft.x = Integer.toString(position.getX());
                draft.z = Integer.toString(position.getZ());
                rebuild();
            }
        });
        surveyInput("半径", "Radius", 0, draft.radius, x, y + 2 * row, col, value -> draft.radius = value);
        surveyInput(draft.random ? "间距" : "圈距", draft.random ? "Grid" : "Spacing",
                1, draft.spacing, x, y + 2 * row, col, value -> draft.spacing = value);
        surveyInput("上限", "Limit", 2, draft.limit, x, y + 2 * row, col, value -> draft.limit = value);
        surveyInput("换点距离", "Turn at", 0, draft.switchDistance, x, y + 3 * row, col, value -> draft.switchDistance = value);
        if (draft.random) {
            surveyInput("最短航段", "Min leg", 1, draft.minLeg, x, y + 3 * row, col, value -> draft.minLeg = value);
            surveyInput("最长航段", "Max leg", 2, draft.maxLeg, x, y + 3 * row, col, value -> draft.maxLeg = value);
        } else {
            String[] directions = {text("向北", "North"), text("向东", "East"), text("向南", "South"), text("向西", "West")};
            int directionX = x + col + 6;
            int directionY = y + 3 * row;
            button(directions[draft.direction] + " ↻", directionX, directionY, col, 19, false, () -> {
                draft.direction = (draft.direction + 1) % 4;
                rebuild();
            });
            button(draft.clockwise ? text("顺时针", "Clockwise") : text("逆时针", "Counterclockwise"),
                    x + 2 * (col + 6), directionY, col, 19, false, () -> {
                        draft.clockwise = !draft.clockwise;
                        rebuild();
                    });
        }
        button(text("开始探索", "Start exploration"), x, y + 4 * row, width / 2 - 3, 19, true, this::startSurvey);
        button(text("结束并接管", "Stop and take over"), x + width / 2 + 3, y + 4 * row, width - width / 2 - 3, 19, false,
                () -> ElytraSurvey.INSTANCE.stop());
    }

    private void surveyInput(String chinese, String english, int column, String value, int x, int y,
                             int width, java.util.function.Consumer<String> change) {
        String label = text(chinese, english);
        String prefix = width >= 105 ? label : switch (english) {
            case "Min leg" -> text("最短", "Min");
            case "Max leg" -> text("最长", "Max");
            case "Turn at" -> text("换点", "Turn");
            case "Spacing" -> text("圈距", "Gap");
            default -> label;
        };
        var field = ElytraUi.input(this.font, x + column * (width + 6), y, width, Component.literal(label), prefix, 19);
        field.setMaxLength(9);
        field.setValue(value);
        field.setResponder(next -> {
            change.accept(next);
            this.error = "";
        });
        addRenderableWidget(field);
    }

    private void startSurvey() {
        try {
            var config = this.survey.config();
            if (!config.valid()) {
                this.error = text("检查范围、间距与航段长度，换点距离须为 52 至间距的一半",
                        "Check range, spacing and legs. Turn distance must be 52 to half the spacing.");
                return;
            }
            String problem = ElytraSurvey.INSTANCE.start(this.minecraft, this.baritone, config);
            if (problem == null) {
                this.minecraft.setScreen(null);
            } else {
                this.error = problem;
            }
        } catch (NumberFormatException ex) {
            this.error = text("探索参数须填写整数", "Enter whole numbers for exploration settings");
        }
    }

    private void drawSurvey(GuiGraphics graphics, ElytraUi.Palette colors) {
        int x = this.left + 16;
        int bottom = this.top + this.panelHeight;
        String line = this.error.isEmpty() ? ElytraSurvey.INSTANCE.status(this.minecraft) : this.error;
        if (line == null) {
            line = text("距离以格计 · 上限 0 为不限", "Distances in blocks · Limit 0 means unlimited");
        }
        if (ElytraUi.width(this.font, line) > this.panelWidth - 32) {
            while (!line.isEmpty() && ElytraUi.width(this.font, line + "…") > this.panelWidth - 32) {
                line = line.substring(0, line.length() - 1);
            }
            line += "…";
        }
        ElytraUi.drawText(graphics, this.font, line, x, bottom - 14, this.error.isEmpty() ? colors.muted() : colors.accent());
    }

    private static final class SurveyDraft {
        String x;
        String z;
        String radius = "8192";
        String spacing = "512";
        String limit = "0";
        String switchDistance = "56";
        String minLeg = "400";
        String maxLeg = "1800";
        boolean random;
        int direction = 1;
        boolean clockwise = true;

        SurveyDraft(BlockPos position, ElytraSurveyPlan.Config last) {
            x = Integer.toString(position.getX());
            z = Integer.toString(position.getZ());
            if (last != null) {
                x = Integer.toString(last.center().x());
                z = Integer.toString(last.center().z());
                radius = Integer.toString(last.radius());
                spacing = Integer.toString(last.spacing());
                limit = Integer.toString(last.limit());
                switchDistance = Integer.toString(last.switchDistance());
                minLeg = Integer.toString(last.minLeg());
                maxLeg = Integer.toString(last.maxLeg());
                random = last.random();
                direction = last.direction();
                clockwise = last.clockwise();
            }
        }

        ElytraSurveyPlan.Config config() {
            var center = new ElytraSurveyPlan.Point(Integer.parseInt(x), Integer.parseInt(z));
            return new ElytraSurveyPlan.Config(center, Integer.parseInt(radius), Integer.parseInt(spacing),
                    Integer.parseInt(limit), Integer.parseInt(switchDistance), random,
                    random ? Integer.parseInt(minLeg) : 400, random ? Integer.parseInt(maxLeg) : 1800, direction, clockwise);
        }
    }

}
