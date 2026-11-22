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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
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

    private static final long ANIMATION = 230_000_000L;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final ElytraPanelOptions settings = ElytraPanelOptions.get();
    private final long openedAt = System.nanoTime();
    private Page page;
    private long pageChangedAt;
    private long closingAt;
    private int tabDirection;
    private float tabFrom;
    private int tabWidth;
    private int tabY;
    private final List<Button> chrome = new ArrayList<>();
    private Menu menu;
    private boolean draggingHud;
    private int dragOffsetX;
    private int dragOffsetY;
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
        this.panelWidth = Math.min(this.width - 16, 468);
        this.panelHeight = Math.min(this.height - 16, 264);
        this.top = (this.height - this.panelHeight) / 2;
        this.left = (this.width - this.panelWidth) / 2;
        boolean compact = this.panelHeight < 225;
        this.contentTop = this.top + (compact ? 61 : 72);
        this.step = compact ? 18 : 25;
        this.chrome.clear();
        this.menu = null;
        int tabX = this.left + 16;
        this.tabY = this.top + (compact ? 29 : 36);
        this.tabWidth = (this.panelWidth - 32) / Page.values().length;
        for (Page item : Page.values()) {
            boolean selected = this.page == item;
            chromeButton(text(item.chinese, item.english), tabX + 2, this.tabY + 2,
                    this.tabWidth - 4, 19, selected, () -> openPage(item));
            tabX += this.tabWidth;
        }
        chromeButton("×", this.left + this.panelWidth - 34, this.top + 9, 18, 18, false, this::onClose);

        switch (this.page) {
            case FLIGHT -> flightControls(compact);
            case KEYS -> {
                this.step = Math.min(this.step, Math.max(16, (this.top + this.panelHeight - this.contentTop - 22) / 6));
                keyControls();
            }
            case LINES -> lineControls();
            case HUD -> hudControls();
            case STATS -> { }
        }
    }

    private void openPage(Page selected) {
        if (selected != this.page) {
            this.tabFrom = tabPosition(System.nanoTime());
            this.tabDirection = Integer.signum(selected.ordinal() - this.page.ordinal());
            this.pageChangedAt = System.nanoTime();
            this.page = selected;
            this.draggingHud = false;
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
        keyButton(this.settings.elytraMuteRocketSound.get() ? text("关闭", "Off") : text("开启", "On"), 6, () -> {
            this.settings.elytraMuteRocketSound.set(!this.settings.elytraMuteRocketSound.get());
            rebuild();
        });
    }

    private void lineControls() {
        this.routeColorField = lineControls(this.settings.elytraRenderRoute, this.settings.elytraRouteColor, text("规划路线颜色", "Route color"), 0);
        this.simulationColorField = lineControls(this.settings.elytraRenderSimulation, this.settings.elytraSimulationColor, text("飞行预测线颜色", "Prediction color"), 1);
    }

    private void hudControls() {
        int x = hudControlX();
        int w = hudControlWidth();
        segments(x, hudRow(0), w, text("显示", "Show"), text("隐藏", "Hide"), this.settings.elytraFlightHud.get(), value -> this.settings.elytraFlightHud.set(value));
        String[] anchors = {text("左上", "Top left"), text("上中", "Top"), text("右上", "Top right"),
                text("左中", "Left"), text("居中", "Center"), text("右中", "Right"),
                text("左下", "Bottom left"), text("下中", "Bottom"), text("右下", "Bottom right")};
        int anchor = Math.clamp(this.settings.elytraHudAnchor.get(), 0, 8);
        button(anchors[anchor] + " ▾", x, hudRow(1), w, 18, false, () -> openMenu(x, hudRow(1), w, 1,
                anchors, anchor, -1, option -> {
                    this.settings.elytraHudAnchor.set(option);
                    this.settings.elytraHudOffsetX.set(0);
                    this.settings.elytraHudOffsetY.set(0);
                    rebuild();
                }));
        int fieldWidth = (w - 4) / 2;
        hudOffset("X", x, fieldWidth, this.settings.elytraHudOffsetX);
        hudOffset("Y", x + fieldWidth + 4, fieldWidth, this.settings.elytraHudOffsetY);
        segments(x, hudRow(3), w, text("浅色", "Light"), text("深色", "Dark"), !this.settings.elytraDarkTheme.get(), value -> this.settings.elytraDarkTheme.set(!value));
        segments(x, hudRow(4), w, "中文", "English", !this.settings.elytraEnglishUi.get(), value -> this.settings.elytraEnglishUi.set(!value));
        segments(x, hudRow(5), w, text("万格", "10k"), text("千格", "1k"), this.settings.elytraUseTenThousands.get(), value -> this.settings.elytraUseTenThousands.set(value));
    }

    private void segments(int x, int y, int width, String first, String second, boolean leftSelected,
                          java.util.function.Consumer<Boolean> select) {
        int half = (width - 2) / 2;
        button(first, x, y, half, 18, leftSelected, () -> {
            select.accept(true);
            rebuild();
        });
        button(second, x + half + 2, y, width - half - 2, 18, !leftSelected, () -> {
            select.accept(false);
            rebuild();
        });
    }

    private int hudControlX() {
        return this.left + (this.panelWidth >= 400 ? 116 : 83);
    }

    private int hudControlWidth() {
        return this.panelWidth >= 400 ? 110 : 90;
    }

    private int hudRow(int row) {
        return this.contentTop + row * Math.clamp((this.panelHeight - (this.contentTop - this.top) - 27) / 5, 19, 25);
    }

    private void hudOffset(String axis, int x, int width, ElytraPanelOptions.Option<Integer> setting) {
        EditBox field = ElytraUi.input(this.font, x, hudRow(2), width,
                Component.literal(axis + text(" 偏移", " offset")), axis, 18);
        field.setMaxLength(6);
        field.setFilter(value -> value.matches("-?[0-9]{0,5}"));
        field.setValue(Integer.toString(Math.clamp(setting.get(), -99_999, 99_999)));
        field.setResponder(value -> {
            if (!value.isEmpty() && !value.equals("-")) {
                setting.set(Integer.parseInt(value));
            }
        });
        addRenderableWidget(field);
    }

    private EditBox lineControls(ElytraPanelOptions.Option<Boolean> visible, ElytraPanelOptions.Option<Color> color, String name, int row) {
        int y = this.contentTop + row * 64;
        int right = this.left + this.panelWidth - 16;
        button(visible.get() ? text("已显示", "Visible") : text("已隐藏", "Hidden"), right - 53, y + 2, 53, 22, visible.get(), () -> {
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
        return input(name, "#", String.format(Locale.ROOT, "%06X", color.get().getRGB() & 0xFFFFFF), right - 151, y, 88, 6, value -> {
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
        int width = Math.min(120, this.panelWidth / 3);
        int x = this.left + this.panelWidth - 16 - width;
        int y = this.contentTop + row * this.step - 4;
        int slot = Math.clamp(setting.get(), 1, 9);
        String[] choices = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
        button(slot + " ▾", x, y, width, this.step - 3, false, () ->
                openMenu(x, y, width, 3, choices, slot - 1, Math.clamp(other.get(), 1, 9) - 1, option -> {
                    if (option + 1 != Math.clamp(other.get(), 1, 9)) {
                        setting.set(option + 1);
                    }
                    rebuild();
                }));
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

    private void chromeButton(String label, int x, int y, int width, int height, boolean primary, Runnable action) {
        Button button = ElytraUi.button(Component.literal(label), x, y, width, height, primary, ignored -> {
            if (this.closingAt == 0) {
                action.run();
            }
        });
        this.chrome.add(addWidget(button));
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

    private void openMenu(int x, int anchorY, int width, int columns, String[] options, int selected, int disabled,
                          java.util.function.IntConsumer choose) {
        int rows = (options.length + columns - 1) / columns;
        int menuHeight = rows * Menu.ROW_HEIGHT + 8;
        int below = anchorY + 22;
        int y = below + menuHeight <= this.height - 6 ? below : Math.max(6, anchorY - menuHeight - 4);
        menu = new Menu(x, y, width, columns, options, selected, disabled, choose);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.closingAt != 0) {
            return true;
        }
        if (this.menu != null) {
            int option = this.menu.itemAt(event.x(), event.y());
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && option == this.menu.disabled && option >= 0) {
                return true;
            }
            Menu opened = this.menu;
            this.menu = null;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && option >= 0) {
                opened.choose.accept(option);
            }
            return true;
        }
        if (this.page == Page.HUD && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Preview preview = previewBounds();
            if (preview.contains(event.x(), event.y())) {
                if (getFocused() != null) {
                    getFocused().setFocused(false);
                    setFocused(null);
                }
                this.draggingHud = true;
                moveHud(event.x(), event.y(), preview);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.draggingHud) {
            moveHud(event.x(), event.y(), previewBounds());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingHud && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            moveHud(event.x(), event.y(), previewBounds());
            this.draggingHud = false;
            this.settings.elytraHudOffsetX.set(this.dragOffsetX);
            this.settings.elytraHudOffsetY.set(this.dragOffsetY);
            rebuild();
            return true;
        }
        return super.mouseReleased(event);
    }

    private void moveHud(double x, double y, Preview preview) {
        ElytraFlightHud.Layout layout = ElytraFlightHud.previewLayout();
        int anchor = Math.clamp(this.settings.elytraHudAnchor.get(), 0, 8);
        int hudX = (int) Math.round((x - preview.x) / preview.scale - layout.width() / 2.0);
        int hudY = (int) Math.round((y - preview.y) / preview.scale - layout.height() / 2.0);
        this.dragOffsetX = ElytraFlightHud.offsetForPosition(anchor % 3, this.width, layout.width(), hudX);
        this.dragOffsetY = ElytraFlightHud.offsetForVerticalPosition(anchor, this.height, layout.height(), hudY);
    }

    private Preview previewBounds() {
        int left = hudControlX() + hudControlWidth() + 15;
        int availableWidth = Math.max(1, this.left + this.panelWidth - 16 - left);
        int top = this.contentTop + 14;
        int availableHeight = Math.max(1, this.top + this.panelHeight - 28 - top);
        double scale = Math.min(availableWidth / (double) this.width, availableHeight / (double) this.height);
        int w = (int) Math.round(this.width * scale);
        int h = (int) Math.round(this.height * scale);
        return new Preview(left + (availableWidth - w) / 2, top + (availableHeight - h) / 2, w, h, scale);
    }

    private record Preview(int x, int y, int width, int height, double scale) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private static final class Menu {
        private static final int ROW_HEIGHT = 16;
        private final int x;
        private final int y;
        private final int width;
        private final int columns;
        private final String[] labels;
        private final int selected;
        private final int disabled;
        private final java.util.function.IntConsumer choose;
        private final long openedAt = System.nanoTime();
        private int focused;

        Menu(int x, int y, int width, int columns, String[] labels, int selected, int disabled,
             java.util.function.IntConsumer choose) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.columns = columns;
            this.labels = labels;
            this.selected = selected;
            this.disabled = disabled;
            this.choose = choose;
            this.focused = selected;
        }

        int height() {
            return ROW_HEIGHT * ((labels.length + columns - 1) / columns) + 8;
        }

        int cellWidth() {
            return (width - 8) / columns;
        }

        int itemAt(double mouseX, double mouseY) {
            int innerX = (int) Math.floor(mouseX - x - 4);
            int innerY = (int) Math.floor(mouseY - y - 4);
            if (innerX < 0 || innerX >= cellWidth() * columns || innerY < 0 || innerY >= height() - 8) {
                return -1;
            }
            int item = innerY / ROW_HEIGHT * columns + innerX / cellWidth();
            return item < labels.length ? item : -1;
        }

        void move(int delta) {
            int next = Math.floorMod(focused + delta, labels.length);
            if (next == disabled) {
                next = Math.floorMod(next + Integer.signum(delta), labels.length);
            }
            focused = next;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.closingAt > 0) {
            return true;
        }
        if (this.menu != null) {
            int key = event.key();
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                this.menu = null;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
                Menu opened = this.menu;
                if (opened.focused != opened.disabled) {
                    this.menu = null;
                    opened.choose.accept(opened.focused);
                }
            } else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                this.menu.move(key == GLFW.GLFW_KEY_UP ? -this.menu.columns : this.menu.columns);
            } else if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                this.menu.move(key == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            }
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
            }
            this.binding = null;
            rebuild();
            return true;
        }
        if (this.page == Page.FLIGHT && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            if (this.speedField.isFocused()) {
                applySpeed();
                return true;
            }
            if (this.xField.isFocused() || this.zField.isFocused()) {
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
        float slideY = (float) ((1 - visible) * 11);
        float pageProgress = (float) ease((now - this.pageChangedAt) / (double) ANIMATION);
        float contentSlide = this.tabDirection * 9 * (1 - pageProgress);
        graphics.fill(0, 0, this.width, this.height, (int) (visible * 101) << 24);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, slideY);
        ElytraUi.rounded(graphics, this.left - 3, this.top + 5, this.panelWidth + 6,
                this.panelHeight + 4, ElytraUi.withOpacity(0x32000000, visible), 18);
        ElytraUi.rounded(graphics, this.left, this.top, this.panelWidth, this.panelHeight, c.line(), 16);
        ElytraUi.rounded(graphics, this.left + 1, this.top + 1, this.panelWidth - 2, this.panelHeight - 2, c.panel(), 15);
        ElytraUi.drawText(graphics, this.font, this.title.getString(), this.left + 18, this.top + 15, c.text());
        int railX = this.left + 16;
        ElytraUi.rounded(graphics, railX, this.tabY, this.tabWidth * Page.values().length, 23, c.surface(), 9);
        for (Button button : this.chrome) {
            button.render(graphics, mouseX, Math.round(mouseY - slideY), partialTick);
        }
        int indicatorX = railX + 9 + Math.round(this.tabWidth * tabPosition(now));
        ElytraUi.rounded(graphics, indicatorX, this.tabY + 22, this.tabWidth - 18, 2, c.accent(), 1);
        graphics.pose().pushMatrix();
        graphics.pose().translate(contentSlide, 0);
        ElytraUi.setOpacity(visible * (0.45 + 0.55 * pageProgress));
        ElytraUi.Palette content = ElytraUi.colors();
        switch (this.page) {
            case FLIGHT -> drawFlight(graphics, content);
            case KEYS -> drawKeys(graphics, content);
            case STATS -> drawStats(graphics, content);
            case LINES -> drawLines(graphics, content);
            case HUD -> drawHudSettings(graphics, content);
        }
        super.render(graphics, Math.round(mouseX - contentSlide), Math.round(mouseY - slideY), partialTick);
        graphics.pose().popMatrix();
        ElytraUi.setOpacity(visible);
        drawMenu(graphics, mouseX, Math.round(mouseY - slideY), now);
        graphics.pose().popMatrix();
        ElytraUi.setOpacity(1);
    }

    private float tabPosition(long now) {
        float phase = (float) ease((now - this.pageChangedAt) / (double) ANIMATION);
        return this.tabFrom + (this.page.ordinal() - this.tabFrom) * phase;
    }

    private void drawMenu(GuiGraphics graphics, int mouseX, int mouseY, long now) {
        if (this.menu == null) {
            return;
        }
        Menu opened = this.menu;
        double enter = ease((now - opened.openedAt) / 140_000_000.0);
        var c = ElytraUi.colors();
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (5 * (1 - enter)));
        ElytraUi.rounded(graphics, opened.x - 2, opened.y + 3, opened.width + 4, opened.height() + 2, 0x40000000, 9);
        ElytraUi.rounded(graphics, opened.x, opened.y, opened.width, opened.height(), c.line(), 8);
        ElytraUi.rounded(graphics, opened.x + 1, opened.y + 1, opened.width - 2, opened.height() - 2, c.panel(), 7);
        int hovered = opened.itemAt(mouseX, mouseY);
        for (int i = 0; i < opened.labels.length; i++) {
            int x = opened.x + 4 + i % opened.columns * opened.cellWidth();
            int y = opened.y + 4 + i / opened.columns * Menu.ROW_HEIGHT;
            boolean enabled = i != opened.disabled;
            boolean selected = i == opened.selected;
            if (enabled && (selected || i == hovered || (hovered < 0 && i == opened.focused))) {
                ElytraUi.rounded(graphics, x, y, opened.cellWidth() - 1, Menu.ROW_HEIGHT - 1, selected ? c.surface() : c.hover(), 5);
            }
            String label = opened.labels[i];
            int textX = x + (opened.columns == 1 ? 7 : (opened.cellWidth() - ElytraUi.width(this.font, label)) / 2);
            ElytraUi.drawText(graphics, this.font, label, textX, y + 4,
                    !enabled ? ElytraUi.withOpacity(c.muted(), 0.38) : selected ? c.accent() : c.text());
        }
        graphics.pose().popMatrix();
    }

    private void drawFlight(GuiGraphics graphics, ElytraUi.Palette c) {
        if (this.minecraft.player == null || !(this.baritone.getElytraProcess() instanceof ElytraPanelFlight process)) {
            return;
        }
        int gap = this.panelHeight < 225 ? 37 : 49;
        String speedLabel = text("烟花消耗速度", "Boost threshold");
        ElytraUi.drawText(graphics, this.font, speedLabel, this.left + 16, this.contentTop + gap - 9, c.muted());
        int y = this.top + this.panelHeight - 42;
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
            String rocketLabel = process.isActive() ? text("预计还需烟花  ", "Rockets left  ") : text("预计消耗烟花  ", "Estimated rockets  ");
            ElytraUi.drawText(graphics, this.font, rocketLabel + rocketAmount(estimate.rockets(), this.settings.elytraEnglishUi.get()),
                    this.left + 16, y + 13, c.muted());
            if (process.isActive() || process.isFlightPaused()) {
                String used = text("本趟已用 ", "Used ") + process.flightRocketsUsed() + text(" 发", " rockets");
                ElytraUi.drawText(graphics, this.font, used, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, used),
                        y + 13, c.text());
            }
            int durability = Items.ELYTRA.getDefaultInstance().getMaxDamage();
            String elytras = fullElytras(estimate.durability(), durability, this.settings.elytraEnglishUi.get());
            ElytraUi.drawText(graphics, this.font, text("预计消耗满耐久鞘翅  ", "Full elytras of wear  ") + elytras,
                    this.left + 16, y + 25, c.muted());
        }
    }

    private void drawKeys(GuiGraphics graphics, ElytraUi.Palette c) {
        String[] labels = {text("打开设置界面", "Open settings"), text("暂停 / 恢复飞行", "Pause / resume"), text("食物槽", "Food slot"), text("烟花槽", "Rocket slot"), text("背包补给", "Hotbar refill"), text("鞘翅滑翔声", "Elytra glide sound"), text("烟花音效", "Firework sound")};
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
        String[] labels = {text("飞行 HUD", "Flight HUD"), text("位置", "Position"),
                text("偏移", "Offset"), text("主题", "Theme"), text("语言", "Language"), text("距离单位", "Units")};
        for (int i = 0; i < labels.length; i++) {
            ElytraUi.drawText(graphics, this.font, labels[i], this.left + 17, hudRow(i) + 5, c.muted());
        }
        Preview bounds = previewBounds();
        String title = text("屏幕预览", "Screen preview");
        ElytraUi.drawText(graphics, this.font, title, bounds.x, bounds.y - 13, c.muted());
        ElytraUi.rounded(graphics, bounds.x, bounds.y, bounds.width, bounds.height, c.line(), 9);
        ElytraUi.rounded(graphics, bounds.x + 1, bounds.y + 1, bounds.width - 2, bounds.height - 2, c.surface(), 8);
        int grid = ElytraUi.withOpacity(c.muted(), 0.20);
        for (int i = 1; i < 3; i++) {
            int x = bounds.x + i * bounds.width / 3;
            int y = bounds.y + i * bounds.height / 3;
            graphics.fill(x, bounds.y + 4, x + 1, bounds.y + bounds.height - 4, grid);
            graphics.fill(bounds.x + 4, y, bounds.x + bounds.width - 4, y + 1, grid);
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(bounds.x, bounds.y);
        graphics.pose().scale((float) bounds.scale, (float) bounds.scale);
        ElytraFlightHud.preview(graphics, ElytraFlightHud.previewLayout(),
                this.draggingHud ? this.dragOffsetX : this.settings.elytraHudOffsetX.get(),
                this.draggingHud ? this.dragOffsetY : this.settings.elytraHudOffsetY.get());
        graphics.pose().popMatrix();
        String hint = this.draggingHud ? "X " + this.dragOffsetX + "  ·  Y " + this.dragOffsetY
                : text("拖动调整 HUD 位置", "Drag to position the HUD");
        if (ElytraUi.width(this.font, hint) <= bounds.width) {
            ElytraUi.drawText(graphics, this.font, hint, bounds.x, bounds.y + bounds.height + 8, c.muted());
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
        int y = this.contentTop + i * this.step;
        ElytraUi.drawText(graphics, this.font, label, this.left + 16, y, c.muted());
        ElytraUi.drawText(graphics, this.font, value, this.left + this.panelWidth - 16 - ElytraUi.width(this.font, value), y, c.text());
        if (i < (this.page == Page.KEYS ? 6 : 4)) {
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

    static String fullElytras(long wear, int fullDurability, boolean english) {
        if (wear < 0) {
            return english ? "More flight data needed" : "飞行数据不足";
        }
        double equivalent = (double) wear / fullDurability;
        return wear > 0 && equivalent < 0.01
                ? (english ? "<0.01" : "不到 0.01 个")
                : String.format(Locale.ROOT, english ? "%.2f" : "%.2f 个", equivalent);
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
