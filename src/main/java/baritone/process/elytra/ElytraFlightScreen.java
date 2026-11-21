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
import baritone.api.pathing.goals.GoalXZ;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.Locale;
import java.util.StringJoiner;

import static baritone.process.elytra.ElytraUi.text;

public final class ElytraFlightScreen extends Screen {
    public enum Page {
        FLIGHT("飞行", "Flight"), KEYS("设置", "Settings"), STATS("飞行统计", "Stats"), LINES("航线显示", "Lines"), HUD("HUD", "HUD");

        private final String chinese;
        private final String english;

        Page(String chinese, String english) {
            this.chinese = chinese;
            this.english = english;
        }
    }

    private static final long ANIMATION = 190_000_000L;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final ElytraPanelOptions settings = ElytraPanelOptions.get();
    private final long openedAt = System.nanoTime();
    private Page page;
    private long pageChangedAt;
    private long closingAt;
    private int tabDirection;
    private String draftX = "";
    private String draftZ = "";
    private String draftSpeed;
    private String error = "";
    private boolean initialized;
    private ElytraPanelOptions.Option<Integer> binding;
    private EditBox xField;
    private EditBox zField;
    private EditBox speedField;
    private EditBox routeColorField;
    private EditBox simulationColorField;
    private final boolean[] customColor = new boolean[2];
    private static final int[] LINE_PRESETS = {0xF27DB2, 0x58C9D6, 0x7DCFAB, 0xF1BC68, 0xF4F4F5};
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int step;

    public ElytraFlightScreen() {
        this(Page.FLIGHT);
    }

    public ElytraFlightScreen(Page page) {
        super(Component.literal("BetterBaritone"));
        this.page = page;
    }

    @Override
    protected void init() {
        if (!this.initialized) {
            this.initialized = true;
            this.draftSpeed = Double.toString(this.settings.elytraFireworkSpeed.get());
            if (this.baritone.getElytraProcess() instanceof ElytraPanelFlight process && process.flightDestination() != null) {
                this.draftX = Integer.toString(process.flightDestination().getX());
                this.draftZ = Integer.toString(process.flightDestination().getZ());
            }
        }
        this.panelWidth = Math.min(this.width - 16, 410);
        this.panelHeight = Math.min(this.height - 16, 236);
        this.top = (this.height - this.panelHeight) / 2;
        this.left = (this.width - this.panelWidth) / 2;
        boolean compact = this.panelHeight < 205;
        this.contentTop = this.top + (compact ? 60 : 77);
        this.step = compact ? 18 : 25;
        int tabX = this.left + 16;
        int tabWidth = Math.min(compact ? 59 : 72, (this.panelWidth - 47) / Page.values().length);
        for (Page item : Page.values()) {
            boolean selected = this.page == item;
            button(text(item.chinese, item.english), tabX, this.top + (compact ? 29 : 37), tabWidth, 19, selected,
                    () -> openPage(item));
            tabX += tabWidth + 5;
        }
        button(this.settings.elytraDarkTheme.get() ? text("浅色", "Light") : text("深色", "Dark"), this.left + this.panelWidth - 79,
                this.top + 9, 42, 18, false, () -> {
                    this.settings.elytraDarkTheme.set(!this.settings.elytraDarkTheme.get());
                    rebuild();
                });
        button("×", this.left + this.panelWidth - 31, this.top + 9, 18, 18, false, this::onClose);
        button(this.settings.elytraEnglishUi.get() ? "EN / CN" : "CN / EN", this.left + this.panelWidth - 142,
                this.top + 9, 56, 18, false, () -> {
                    this.settings.elytraEnglishUi.set(!this.settings.elytraEnglishUi.get());
                    this.error = "";
                    rebuild();
                });

        switch (this.page) {
            case FLIGHT -> flightControls(compact);
            case KEYS -> {
                this.step = Math.min(this.step, Math.max(16, (this.top + this.panelHeight - this.contentTop - 22) / 5));
                keyControls();
            }
            case LINES -> lineControls();
            case HUD -> hudControls();
            case STATS -> button(this.settings.elytraUseTenThousands.get() ? text("单位 · 万格", "Unit · 10k") : text("单位 · 千格", "Unit · 1k"),
                    this.left + this.panelWidth - 16 - 86, this.contentTop - 2, 86, 18, false, () -> {
                        this.settings.elytraUseTenThousands.set(!this.settings.elytraUseTenThousands.get());
                        rebuild();
                    });
        }
    }

    private void openPage(Page selected) {
        if (selected != this.page) {
            this.tabDirection = Integer.signum(selected.ordinal() - this.page.ordinal());
            this.pageChangedAt = System.nanoTime();
            this.page = selected;
            this.binding = null;
            this.error = "";
            rebuild();
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void flightControls(boolean compact) {
        int col = (this.panelWidth - 40) / 2;
        this.xField = input("X", "X", this.draftX, this.left + 16, this.contentTop, col, 9,
                value -> this.draftX = value);
        this.zField = input("Z", "Z", this.draftZ, this.left + 24 + col, this.contentTop, col, 9,
                value -> this.draftZ = value);
        int controlsY = this.contentTop + (compact ? 38 : 51);
        this.speedField = input(text("烟花消耗速度", "Boost threshold"), "", this.draftSpeed, this.left + 16, controlsY, col, 12,
                value -> this.draftSpeed = value);
        int startX = this.left + 24 + col;
        button(text("保存", "Save"), startX, controlsY + 2, 47, 22, false, this::applySpeed);
        button(text("开始飞行", "Start flight"), startX + 54, controlsY + 2, col - 54, 22, true, this::startFlight);
    }

    private EditBox input(String name, String prefix, String value, int x, int y, int width, int maxLength,
                          java.util.function.Consumer<String> onChange) {
        EditBox field = ElytraUi.input(this.font, x, y, width, Component.literal(name), prefix);
        field.setMaxLength(maxLength);
        field.setValue(value);
        field.setResponder(text -> {
            onChange.accept(text);
            this.error = "";
        });
        return addRenderableWidget(field);
    }

    private void keyControls() {
        key(this.settings.elytraSettingsKey, 0);
        key(this.settings.elytraPauseKey, 1);
        slot(this.settings.elytraFoodSlot, this.settings.elytraRocketSlot, 2);
        slot(this.settings.elytraRocketSlot, this.settings.elytraFoodSlot, 3);
        keyButton(this.settings.elytraRefillHotbar.get() ? text("开启", "On") : text("关闭", "Off"), 4, () -> {
            this.settings.elytraRefillHotbar.set(!this.settings.elytraRefillHotbar.get());
            rebuild();
        });
        keyButton(this.settings.elytraMuteGlideSound.get() ? text("关闭", "Off") : text("开启", "On"), 5, () -> {
            this.settings.elytraMuteGlideSound.set(!this.settings.elytraMuteGlideSound.get());
            rebuild();
        });
    }

    private void lineControls() {
        this.routeColorField = lineControls(this.settings.elytraRenderRoute, this.settings.elytraRouteColor, text("规划路线颜色", "Route color"), 0);
        this.simulationColorField = lineControls(this.settings.elytraRenderSimulation, this.settings.elytraSimulationColor, text("飞行预测线颜色", "Prediction color"), 1);
    }

    private void hudControls() {
        String[] anchors = {text("左上", "TL"), text("上中", "TC"), text("右上", "TR"), text("左中", "ML"), text("居中", "Center"), text("右中", "MR"), text("左下", "BL"), text("下中", "BC"), text("右下", "BR")};
        for (int i = 0; i < anchors.length; i++) {
            int anchor = i;
            button(anchors[i], this.left + 16 + 43 * (i % 3), this.contentTop + 16 + 22 * (i / 3),
                    39, 18, i == Math.clamp(this.settings.elytraHudAnchor.get(), 0, 8), () -> {
                        this.settings.elytraHudAnchor.set(anchor);
                        rebuild();
                    });
        }
        int right = this.left + this.panelWidth - 16;
        button(this.settings.elytraFlightHud.get() ? text("显示", "Show") : text("隐藏", "Hide"), right - 61, this.contentTop + 12,
                61, 20, this.settings.elytraFlightHud.get(), () -> {
                    this.settings.elytraFlightHud.set(!this.settings.elytraFlightHud.get());
                    rebuild();
                });
        button(ElytraFlightHud.style().label(), right - 61, this.contentTop + 37, 61, 20, false, () -> {
            this.settings.elytraHudStyle.set((ElytraFlightHud.style().ordinal() + 1) % ElytraFlightHud.Style.values().length);
            rebuild();
        });
        hudOffset("X", right - 132, this.settings.elytraHudOffsetX);
        hudOffset("Y", right - 64, this.settings.elytraHudOffsetY);
    }

    private void hudOffset(String axis, int x, ElytraPanelOptions.Option<Integer> setting) {
        EditBox field = input(axis + text(" 偏移", " offset"), axis, Integer.toString(setting.get()), x, this.contentTop + 73, 64, 6, value -> {
            if (value.isEmpty() || value.equals("-")) {
                return;
            }
            setting.set(Integer.parseInt(value));
        });
        field.setFilter(value -> value.matches("-?[0-9]{0,5}"));
    }

    private EditBox lineControls(ElytraPanelOptions.Option<Boolean> visible, ElytraPanelOptions.Option<Color> color, String name, int row) {
        int y = this.contentTop + row * 64;
        int right = this.left + this.panelWidth - 16;
        button(visible.get() ? text("显示", "Show") : text("隐藏", "Hide"), right - 43, y + 2, 43, 22, visible.get(), () -> {
            visible.set(!visible.get());
            rebuild();
        });
        String[] labels = {text("粉", "Rose"), text("青", "Cyan"), text("绿", "Mint"), text("金", "Gold"), text("白", "White")};
        for (int i = 0; i < LINE_PRESETS.length; i++) {
            int rgb = LINE_PRESETS[i];
            button(labels[i], this.left + 16 + i * 34, y + 30, 31, 18, (color.get().getRGB() & 0xFFFFFF) == rgb, () -> {
                color.set(new Color(rgb));
                this.customColor[row] = false;
                rebuild();
            });
        }
        button(text("自定义", "Custom"), this.left + 16 + 170, y + 30, 54, 18, this.customColor[row], () -> {
            this.customColor[row] = !this.customColor[row];
            rebuild();
            if (this.customColor[row]) {
                EditBox field = row == 0 ? this.routeColorField : this.simulationColorField;
                setFocused(field);
                field.setFocused(true);
                field.setHighlightPos(0);
            }
        });
        if (!this.customColor[row]) {
            return null;
        }
        return input(name, "#", String.format(Locale.ROOT, "%06X", color.get().getRGB() & 0xFFFFFF), right - 139, y, 88, 6, value -> {
            if (value.length() == 6 && value.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
                color.set(new Color(Integer.parseInt(value, 16)));
            }
        });
    }

    private void key(ElytraPanelOptions.Option<Integer> setting, int row) {
        String keyName = this.binding == setting ? text("按下新键", "Press a key") : setting.get() < 0 ? text("未绑定", "Unbound")
                : InputConstants.Type.KEYSYM.getOrCreate(setting.get()).getDisplayName().getString();
        keyButton(keyName, row, () -> {
            this.binding = setting;
            rebuild();
        });
    }

    private void slot(ElytraPanelOptions.Option<Integer> setting, ElytraPanelOptions.Option<Integer> other, int row) {
        keyButton(Integer.toString(Math.clamp(setting.get(), 1, 9)), row, () -> {
            int previous = Math.clamp(setting.get(), 1, 9);
            int next = previous % 9 + 1;
            if (other.get() == next) {
                other.set(previous);
            }
            setting.set(next);
            rebuild();
        });
    }

    private void keyButton(String text, int row, Runnable action) {
        int width = Math.min(120, this.panelWidth / 3);
        button(text, this.left + this.panelWidth - 16 - width, this.contentTop + row * this.step - 4, width,
                this.step - 3, false, action);
    }

    private void button(String text, int x, int y, int width, int height, boolean primary, Runnable action) {
        addRenderableWidget(ElytraUi.button(Component.literal(text), x, y, width, height, primary, ignored -> {
            if (this.closingAt == 0) {
                action.run();
            }
        }));
    }

    private boolean applySpeed() {
        try {
            double speed = Double.parseDouble(this.draftSpeed.trim());
            if (!Double.isFinite(speed) || speed < 0) {
                throw new NumberFormatException();
            }
            this.settings.elytraFireworkSpeed.set(speed);
            return true;
        } catch (NumberFormatException invalid) {
            this.error = text("请输入大于或等于 0 的速度", "Enter a speed of 0 or greater");
            return false;
        }
    }

    private GoalXZ readDestination() {
        try {
            int x = Integer.parseInt(this.draftX.trim());
            int z = Integer.parseInt(this.draftZ.trim());
            if (Math.abs((long) x) <= 30_000_000 && Math.abs((long) z) <= 30_000_000) {
                return new GoalXZ(x, z);
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private void startFlight() {
        if (this.minecraft.level == null || this.minecraft.level.dimension() != Level.NETHER) {
            this.error = text("进入地狱后才能起飞", "Enter the Nether to start flying");
            return;
        }
        if (!this.baritone.getElytraProcess().isLoaded()) {
            this.error = text("鞘翅寻路不可用", "Elytra pathfinding is unavailable");
            return;
        }
        GoalXZ goal = readDestination();
        if (goal == null) {
            this.error = text("X 和 Z 必须是 ±30,000,000 内的整数", "X and Z must be integers within ±30,000,000");
            return;
        }
        if (!applySpeed()) {
            return;
        }
        boolean active = this.baritone.getElytraProcess() instanceof ElytraPanelFlight process && process.isActive();
        this.baritone.getCustomGoalProcess().setGoal(goal);
        if (!active) {
            this.baritone.getCommandManager().execute("elytra");
        }
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.closingAt > 0) {
            return true;
        }
        if (this.binding != null) {
            int key = event.key();
            if (key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_UNKNOWN) {
                int value = key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE ? -1 : key;
                ElytraPanelOptions.Option<Integer> other = this.binding == this.settings.elytraSettingsKey
                        ? this.settings.elytraPauseKey : this.settings.elytraSettingsKey;
                if (value >= 0 && other.get() == value) {
                    other.set(this.binding.get());
                }
                this.binding.set(value);
                ElytraUi.playFeedback(this.minecraft.getSoundManager());
            }
            this.binding = null;
            rebuild();
            return true;
        }
        if (this.page == Page.FLIGHT && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            if (this.speedField.isFocused()) {
                ElytraUi.playFeedback(this.minecraft.getSoundManager());
                applySpeed();
                return true;
            }
            if (this.xField.isFocused() || this.zField.isFocused()) {
                ElytraUi.playFeedback(this.minecraft.getSoundManager());
                startFlight();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The game remains visible behind the panel.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        if (this.closingAt > 0 && now - this.closingAt >= ANIMATION) {
            this.minecraft.setScreen(null);
            return;
        }
        ElytraUi.animateTheme(now);
        double visible = this.closingAt == 0 ? ease((now - this.openedAt) / (double) ANIMATION)
                : 1 - ease((now - this.closingAt) / (double) ANIMATION);
        ElytraUi.setOpacity(visible);
        ElytraUi.Palette c = ElytraUi.colors();
        float slideY = (float) ((1 - visible) * 8);
        float slideX = (float) (this.tabDirection * 5 * (1 - ease((now - this.pageChangedAt) / (double) ANIMATION)));
        graphics.fill(0, 0, this.width, this.height, (int) (visible * 78) << 24);
        graphics.pose().pushMatrix();
        graphics.pose().translate(slideX, slideY);
        ElytraUi.rounded(graphics, this.left - 2, this.top + 4, this.panelWidth + 4,
                this.panelHeight + 4, ElytraUi.withOpacity(0x26000000, visible), 17);
        ElytraUi.rounded(graphics, this.left, this.top, this.panelWidth, this.panelHeight, c.panel(), 15);
        ElytraUi.drawText(graphics, this.font, this.title.getString(), this.left + 16, this.top + 14, c.text());
        graphics.fill(this.left + 16, this.contentTop - 9, this.left + this.panelWidth - 16,
                this.contentTop - 8, c.line());
        switch (this.page) {
            case FLIGHT -> drawFlight(graphics, c);
            case KEYS -> drawKeys(graphics, c);
            case STATS -> drawStats(graphics, c);
            case LINES -> drawLines(graphics, c);
            case HUD -> drawHudSettings(graphics, c);
        }
        super.render(graphics, Math.round(mouseX - slideX), Math.round(mouseY - slideY), partialTick);
        graphics.pose().popMatrix();
        ElytraUi.setOpacity(1);
    }

    private void drawFlight(GuiGraphics graphics, ElytraUi.Palette c) {
        if (this.minecraft.player == null || !(this.baritone.getElytraProcess() instanceof ElytraPanelFlight process)) {
            return;
        }
        int gap = this.panelHeight < 205 ? 37 : 49;
        String speedLabel = text("烟花消耗速度  ·  格/tick", "Boost threshold · blocks/tick");
        ElytraUi.drawText(graphics, this.font, speedLabel, this.left + 16, this.contentTop + gap - 9, c.muted());
        int y = this.top + this.panelHeight - 36;
        if (!this.error.isEmpty()) {
            ElytraUi.drawText(graphics, this.font, this.error, this.left + 16, y, c.accent());
            return;
        }
        GoalXZ target = readDestination();
        ElytraFlightStats.Estimate estimate = process.flightEstimate();
        double distance = process.remainingDistance();
        if (!process.isActive() && target != null) {
            distance = process.distanceToArrival(new BlockPos(target.getX(), 64, target.getZ()), true);
            estimate = process.estimateForDistance(distance);
        }
        String time = estimate != null ? time(estimate.seconds())
                : process.isFlightPaused() ? text("已暂停", "Paused")
                : process.isActive() && process.remainingDistance() == 0 ? process.flightStatus()
                : text("输入 X / Z 后显示", "Enter X / Z");
        String title = text("预计用时  ", "ETA  ") + time;
        ElytraUi.drawText(graphics, this.font, title, this.left + 16, y, c.text());
        if (target != null || process.isActive()) {
            String length = (process.isActive() ? text("剩余 ", "Left ") : text("距离 ", "Distance ")) + distance(distance);
            ElytraUi.drawText(graphics, this.font, length, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, length),
                    y, c.muted());
        }
        if (estimate != null) {
            ElytraUi.drawText(graphics, this.font, text("预计消耗烟花  ", "Estimated rockets  ") + rocketAmount(estimate.rockets(), this.settings.elytraEnglishUi.get()),
                    this.left + 16, y + 15, c.muted());
        }
    }

    private void drawKeys(GuiGraphics graphics, ElytraUi.Palette c) {
        String[] labels = {text("打开设置界面", "Open settings"), text("暂停 / 恢复飞行", "Pause / resume"), text("食物槽", "Food slot"), text("烟花槽", "Rocket slot"), text("背包补给", "Hotbar refill"), text("鞘翅滑翔声", "Elytra glide sound")};
        for (int i = 0; i < labels.length; i++) {
            row(graphics, c, labels[i], "", i);
        }
    }

    private void drawLines(GuiGraphics graphics, ElytraUi.Palette c) {
        drawLineRow(graphics, c, text("规划路线", "Flight route"), this.settings.elytraRouteColor.get(), 0);
        drawLineRow(graphics, c, text("飞行预测线", "Flight prediction"), this.settings.elytraSimulationColor.get(), 1);
        if (invalidColor(this.routeColorField) || invalidColor(this.simulationColorField)) {
            ElytraUi.drawText(graphics, this.font, text("输入 6 位十六进制颜色，例如 F27DB2", "Enter six hex digits, e.g. F27DB2"), this.left + 16,
                    this.contentTop + 121, c.muted());
        }
    }

    private static boolean invalidColor(EditBox field) {
        return field != null && !field.getValue().matches("[0-9a-fA-F]{6}");
    }

    private void drawHudSettings(GuiGraphics graphics, ElytraUi.Palette c) {
        int right = this.left + this.panelWidth - 16;
        ElytraUi.drawText(graphics, this.font, text("位置", "Position"), this.left + 16, this.contentTop, c.muted());
        ElytraUi.drawText(graphics, this.font, text("飞行 HUD", "Flight HUD"), right - 132, this.contentTop + 18, c.muted());
        ElytraUi.drawText(graphics, this.font, text("样式", "Style"), right - 132, this.contentTop + 43, c.muted());
        ElytraUi.drawText(graphics, this.font, text("偏移 · 向右 / 向下为正", "Positive: right / down"), right - 132, this.contentTop + 62, c.muted());
        int previewCenter = this.top + this.panelHeight - 24;
        if (previewCenter - this.contentTop >= 111) {
            ElytraFlightHud.preview(graphics, this.left + this.panelWidth / 2, previewCenter);
        }
    }

    private void drawLineRow(GuiGraphics graphics, ElytraUi.Palette c, String label, Color color, int row) {
        int y = this.contentTop + row * 64;
        ElytraUi.rounded(graphics, this.left + 16, y + 9, 8, 8, color.getRGB(), 3);
        ElytraUi.drawText(graphics, this.font, label, this.left + 31, y + 9, c.text());
    }

    private void drawStats(GuiGraphics graphics, ElytraUi.Palette c) {
        if (!(this.baritone.getElytraProcess() instanceof ElytraPanelFlight process)) {
            return;
        }
        ElytraMileage.Summary summary = process.mileage().summary();
        int unit = this.settings.elytraUseTenThousands.get() ? 10_000 : 1_000;
        String scale = this.settings.elytraUseTenThousands.get() ? text("每万格", "Per 10k") : text("每千格", "Per 1k");
        double blocks = summary.blocks();
        double seconds = summary.seconds();
        row(graphics, c, text("累计飞行", "Total distance"), distance(blocks), 0);
        row(graphics, c, text("平均速度", "Average speed"), distance(summary.blocksPerMinute()) + text("/分钟", "/min"), 1);
        row(graphics, c, scale + text("耗时", " time"), blocks > 0 ? time(Math.round(seconds * unit / blocks)) : "—", 2);
        row(graphics, c, scale + text("烟花", " rockets"), summary.rockets() > 0 && blocks > 0
                ? String.format(Locale.ROOT, text("%.1f 个", "%.1f"), summary.rockets() * unit / blocks) : "—", 3);
        int maximum = Items.ELYTRA.getDefaultInstance().getMaxDamage();
        String life = summary.wear() > 0 ? distance(blocks * maximum / summary.wear())
                + "  ·  " + time(Math.round(seconds * maximum / summary.wear())) : "—";
        row(graphics, c, text("鞘翅预计寿命", "Estimated elytra lifespan"), life, 4);
    }

    private void row(GuiGraphics graphics, ElytraUi.Palette c, String label, String value, int i) {
        int y = this.contentTop + i * this.step + (this.page == Page.STATS ? 22 : 0);
        ElytraUi.drawText(graphics, this.font, label, this.left + 16, y, c.muted());
        ElytraUi.drawText(graphics, this.font, value, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, value), y, c.text());
        if (i < (this.page == Page.KEYS ? 5 : 4)) {
            graphics.fill(this.left + 16, y + this.step - 7, this.left + this.panelWidth - 16, y + this.step - 6, c.line());
        }
    }

    static String distance(double blocks) {
        int unit = ElytraPanelOptions.get().elytraUseTenThousands.get() ? 10_000 : 1_000;
        if (blocks < 1000) {
            return Math.round(blocks) + text(" 格", " b");
        }
        return String.format(Locale.ROOT, "%.2f %s", blocks / unit, unit == 10_000 ? text("万格", "×10k") : text("千格", "k"));
    }

    static String rocketAmount(long count, boolean english) {
        if (count < 0) {
            return english ? "More flight data needed" : "飞行数据不足";
        }
        if (count == 0) {
            return english ? "0 rockets" : "0 个";
        }
        long boxes = count / (27 * 64);
        long stacks = count / 64 % 27;
        long loose = count % 64;
        StringJoiner amount = new StringJoiner(" ");
        if (boxes > 0) amount.add(boxes + (english ? (boxes == 1 ? " box" : " boxes") : " 盒"));
        if (stacks > 0) amount.add(stacks + (english ? " stk" : " 组"));
        if (loose > 0) amount.add(loose + (english ? " loose" : " 个"));
        return amount.toString();
    }

    private static String time(long seconds) {
        return ElytraFlightHud.formatMinutes(seconds);
    }

    private static double ease(double t) {
        double remainder = 1 - Math.clamp(t, 0, 1);
        return 1 - remainder * remainder * remainder;
    }

    @Override
    public void onClose() {
        if (this.closingAt == 0) {
            this.closingAt = System.nanoTime();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
