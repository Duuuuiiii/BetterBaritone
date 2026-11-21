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
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.SettingsUtil;
import baritone.process.ElytraProcess;
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

import java.util.Locale;

public final class ElytraFlightScreen extends Screen {
    public enum Page {
        FLIGHT("飞行"), KEYS("按键设置"), STATS("飞行统计");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private static final long ANIMATION = 190_000_000L;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Settings settings = Baritone.settings();
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
    private Settings.Setting<Integer> binding;
    private EditBox xField;
    private EditBox zField;
    private EditBox speedField;
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
            this.draftSpeed = Double.toString(this.settings.elytraFireworkSpeed.value);
            if (this.baritone.getElytraProcess() instanceof ElytraProcess process && process.flightDestination() != null) {
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
        for (Page item : Page.values()) {
            boolean selected = this.page == item;
            button(item.label, tabX, this.top + (compact ? 29 : 37), compact ? 59 : 72, 19, selected,
                    () -> openPage(item));
            tabX += compact ? 64 : 78;
        }
        button(this.settings.elytraDarkTheme.value ? "浅色" : "深色", this.left + this.panelWidth - 79,
                this.top + 9, 42, 18, false, () -> {
                    this.settings.elytraDarkTheme.value = !this.settings.elytraDarkTheme.value;
                    SettingsUtil.save(this.settings);
                    rebuild();
                });
        button("×", this.left + this.panelWidth - 31, this.top + 9, 18, 18, false, this::onClose);

        switch (this.page) {
            case FLIGHT -> flightControls(compact);
            case KEYS -> keyControls();
            case STATS -> button(this.settings.elytraUseTenThousands.value ? "单位 · 万格" : "单位 · 千格",
                    this.left + this.panelWidth - 171, this.top + 9, 86, 18, false, () -> {
                        this.settings.elytraUseTenThousands.value = !this.settings.elytraUseTenThousands.value;
                        SettingsUtil.save(this.settings);
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
        this.xField = input("X", this.draftX, this.left + 16, this.contentTop, col, 9,
                value -> this.draftX = value);
        this.zField = input("Z", this.draftZ, this.left + 24 + col, this.contentTop, col, 9,
                value -> this.draftZ = value);
        int controlsY = this.contentTop + (compact ? 38 : 51);
        this.speedField = input("烟花消耗速度", this.draftSpeed, this.left + 16, controlsY, col, 12,
                value -> this.draftSpeed = value);
        int startX = this.left + 24 + col;
        button("保存", startX, controlsY + 2, 47, 22, false, this::applySpeed);
        button("开始飞行", startX + 54, controlsY + 2, col - 54, 22, true, this::startFlight);
    }

    private EditBox input(String name, String value, int x, int y, int width, int maxLength,
                          java.util.function.Consumer<String> onChange) {
        EditBox field = ElytraUi.input(this.font, x, y, width, Component.literal(name));
        field.setMaxLength(maxLength);
        field.setHint(ElytraUi.label(name));
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
        keyButton(this.settings.elytraRefillHotbar.value ? "开启" : "关闭", 4, () -> {
            this.settings.elytraRefillHotbar.value = !this.settings.elytraRefillHotbar.value;
            SettingsUtil.save(this.settings);
            rebuild();
        });
    }

    private void key(Settings.Setting<Integer> setting, int row) {
        String keyName = this.binding == setting ? "按下新键" : setting.value < 0 ? "未绑定"
                : InputConstants.Type.KEYSYM.getOrCreate(setting.value).getDisplayName().getString();
        keyButton(keyName, row, () -> {
            this.binding = setting;
            rebuild();
        });
    }

    private void slot(Settings.Setting<Integer> setting, Settings.Setting<Integer> other, int row) {
        keyButton(Integer.toString(Math.clamp(setting.value, 1, 9)), row, () -> {
            int previous = Math.clamp(setting.value, 1, 9);
            int next = previous % 9 + 1;
            if (other.value == next) {
                other.value = previous;
            }
            setting.value = next;
            SettingsUtil.save(this.settings);
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
            this.settings.elytraFireworkSpeed.value = speed;
            SettingsUtil.save(this.settings);
            return true;
        } catch (NumberFormatException invalid) {
            this.error = "请输入大于或等于 0 的速度";
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
            this.error = "进入地狱后才能起飞";
            return;
        }
        if (!this.baritone.getElytraProcess().isLoaded()) {
            this.error = "鞘翅寻路不可用";
            return;
        }
        GoalXZ goal = readDestination();
        if (goal == null) {
            this.error = "X 和 Z 必须是 ±30,000,000 内的整数";
            return;
        }
        if (!applySpeed()) {
            return;
        }
        boolean active = this.baritone.getElytraProcess() instanceof ElytraProcess process && process.isActive();
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
                Settings.Setting<Integer> other = this.binding == this.settings.elytraSettingsKey
                        ? this.settings.elytraPauseKey : this.settings.elytraSettingsKey;
                if (value >= 0 && other.value == value) {
                    other.value = this.binding.value;
                }
                this.binding.value = value;
                SettingsUtil.save(this.settings);
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
        ElytraUi.rounded(graphics, this.left - 1, this.top + 4, this.panelWidth + 2,
                this.panelHeight + 2, ElytraUi.withOpacity(0x44000000, visible), 14);
        ElytraUi.rounded(graphics, this.left, this.top, this.panelWidth, this.panelHeight, c.panel(), 13);
        ElytraUi.drawText(graphics, this.font, this.title.getString(), this.left + 16, this.top + 14, c.text());
        graphics.fill(this.left + 16, this.contentTop - 9, this.left + this.panelWidth - 16,
                this.contentTop - 8, c.line());
        switch (this.page) {
            case FLIGHT -> drawFlight(graphics, c);
            case KEYS -> drawKeys(graphics, c);
            case STATS -> drawStats(graphics, c);
        }
        super.render(graphics, Math.round(mouseX - slideX), Math.round(mouseY - slideY), partialTick);
        graphics.pose().popMatrix();
        ElytraUi.setOpacity(1);
    }

    private void drawFlight(GuiGraphics graphics, ElytraUi.Palette c) {
        if (this.minecraft.player == null || !(this.baritone.getElytraProcess() instanceof ElytraProcess process)) {
            return;
        }
        int gap = this.panelHeight < 205 ? 37 : 49;
        String speedLabel = "烟花消耗速度  ·  格/tick";
        ElytraUi.drawText(graphics, this.font, speedLabel, this.left + 16, this.contentTop + gap - 9, c.muted());
        int y = this.top + this.panelHeight - 23;
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
                : process.isFlightPaused() ? "已暂停"
                : process.isActive() && process.remainingDistance() == 0 ? process.flightStatus()
                : "输入 X / Z 后显示";
        String title = "预计用时  " + time;
        ElytraUi.drawText(graphics, this.font, title, this.left + 16, y, c.text());
        if (distance > 0) {
            String length = distance(distance);
            ElytraUi.drawText(graphics, this.font, length, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, length),
                    y, c.muted());
        }
    }

    private void drawKeys(GuiGraphics graphics, ElytraUi.Palette c) {
        String[] labels = {"打开设置界面", "暂停 / 恢复飞行", "食物槽", "烟花槽", "背包补给"};
        for (int i = 0; i < labels.length; i++) {
            row(graphics, c, labels[i], "", i);
        }
    }

    private void drawStats(GuiGraphics graphics, ElytraUi.Palette c) {
        if (!(this.baritone.getElytraProcess() instanceof ElytraProcess process)) {
            return;
        }
        ElytraMileage.Summary summary = process.mileage().summary();
        int unit = this.settings.elytraUseTenThousands.value ? 10_000 : 1_000;
        String scale = this.settings.elytraUseTenThousands.value ? "每万格" : "每千格";
        double blocks = summary.blocks();
        double seconds = summary.seconds();
        row(graphics, c, "累计飞行", distance(blocks), 0);
        row(graphics, c, "平均速度", distance(summary.blocksPerMinute()) + "/分钟", 1);
        row(graphics, c, scale + "耗时", blocks > 0 ? time(Math.round(seconds * unit / blocks)) : "—", 2);
        row(graphics, c, scale + "烟花", summary.rockets() > 0 && blocks > 0
                ? String.format(Locale.ROOT, "%.1f 个", summary.rockets() * unit / blocks) : "—", 3);
        int maximum = Items.ELYTRA.getDefaultInstance().getMaxDamage();
        String life = summary.wear() > 0 ? distance(blocks * maximum / summary.wear())
                + "  ·  " + time(Math.round(seconds * maximum / summary.wear())) : "—";
        row(graphics, c, "鞘翅预计寿命", life, 4);
    }

    private void row(GuiGraphics graphics, ElytraUi.Palette c, String label, String value, int i) {
        int y = this.contentTop + i * this.step;
        ElytraUi.drawText(graphics, this.font, label, this.left + 16, y, c.muted());
        ElytraUi.drawText(graphics, this.font, value, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, value), y, c.text());
        if (i < 4) {
            graphics.fill(this.left + 16, y + this.step - 7, this.left + this.panelWidth - 16, y + this.step - 6, c.line());
        }
    }

    static String distance(double blocks) {
        int unit = Baritone.settings().elytraUseTenThousands.value ? 10_000 : 1_000;
        if (blocks < 1000) {
            return Math.round(blocks) + " 格";
        }
        return String.format(Locale.ROOT, "%.2f %s", blocks / unit, unit == 10_000 ? "万格" : "千格");
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
