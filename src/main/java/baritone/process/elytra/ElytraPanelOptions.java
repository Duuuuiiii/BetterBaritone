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
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Reuse Baritone options when present and persist add-on-only options separately. */
public final class ElytraPanelOptions {
    private static final class Holder {
        static final ElytraPanelOptions INSTANCE = new ElytraPanelOptions();
    }

    private final Settings settings = BaritoneAPI.getSettings();
    private final Path path = Minecraft.getInstance() == null ? null
            : Minecraft.getInstance().gameDirectory.toPath().resolve("betterbaritone.properties");
    private final Properties properties = load();

    public final Option<Boolean> elytraRenderRoute = option("elytraRenderRoute", true);
    public final Option<Color> elytraRouteColor = option("elytraRouteColor", new Color(0xF27DB2));
    public final Option<Color> elytraSimulationColor = option("elytraSimulationColor", new Color(0x58C9D6));
    public final Option<Boolean> elytraRenderAim = option("elytraRenderAim", false);
    public final Option<Boolean> elytraRenderThroughWalls = option("elytraRenderThroughWalls", false);
    public final Option<Integer> elytraSettingsKey = option("elytraSettingsKey", GLFW.GLFW_KEY_F6);
    public final Option<Integer> elytraTakeoverKey = option("elytraTakeoverKey", -1);
    public final Option<Integer> elytraPauseKey = option("elytraPauseKey", GLFW.GLFW_KEY_U);
    public final Option<Integer> elytraLandKey = option("elytraLandKey", -1);
    public final Option<Boolean> elytraRefillHotbar = option("elytraRefillHotbar", true);
    public final Option<Boolean> elytraDarkTheme = option("elytraDarkTheme", false);
    public final Option<Boolean> elytraUseTenThousands = option("elytraUseTenThousands", true);
    public final Option<Boolean> elytraFlightHud = option("elytraFlightHud", true);
    public final Option<Boolean> elytraEnglishUi = option("elytraEnglishUi", false);
    public final Option<Boolean> elytraMuteGlideSound = option("elytraMuteGlideSound", false);
    public final Option<Integer> elytraHudAnchor = option("elytraHudAnchor", 4);
    public final Option<Integer> elytraHudOffsetX = option("elytraHudOffsetX", 0);
    public final Option<Integer> elytraHudOffsetY = option("elytraHudOffsetY", 31);
    public final Option<Integer> elytraHudStyle = option("elytraHudStyle", 0);
    public final Option<Boolean> elytraSupplyAlertSound = option("elytraSupplyAlertSound", true);
    public final Option<Integer> elytraRocketSlot = option("elytraRocketSlot", 9);
    public final Option<Integer> elytraFoodSlot = option("elytraFoodSlot", 6);
    public final Option<Double> elytraFireworkSpeed = option("elytraFireworkSpeed", 1.2);
    public final Option<Boolean> elytraRenderSimulation = option("elytraRenderSimulation", true);

    public static ElytraPanelOptions get() {
        return Holder.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private <T> Option<T> option(String key, T fallback) {
        return new Option<>(key, fallback, (Settings.Setting<T>) settings.byLowerName.get(key.toLowerCase(Locale.ROOT)));
    }

    private Properties load() {
        Properties result = new Properties();
        if (path != null && Files.isRegularFile(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                result.load(reader);
            } catch (IOException | IllegalArgumentException exception) {
                LoggerFactory.getLogger("BetterBaritone").warn("Could not load UI settings", exception);
            }
        }
        return result;
    }

    public final class Option<T> {
        private final String key;
        private final Settings.Setting<T> upstream;
        private T value;

        @SuppressWarnings("unchecked")
        private Option(String key, T fallback, Settings.Setting<T> upstream) {
            this.key = key;
            this.upstream = upstream;
            this.value = fallback;
            String saved = properties.getProperty(key);
            if (upstream == null && saved != null) {
                try {
                    Object parsed = switch (fallback) {
                        case Color ignored -> new Color(Integer.parseUnsignedInt(saved, 16));
                        case Integer ignored -> Integer.valueOf(saved);
                        case Boolean ignored -> Boolean.valueOf(saved);
                        case Double ignored -> Double.valueOf(saved);
                        default -> fallback;
                    };
                    this.value = (T) parsed;
                } catch (NumberFormatException ignored) {
                    // Keep the default for a malformed setting.
                }
            }
        }

        public T get() {
            return upstream == null ? value : upstream.value;
        }

        public void set(T next) {
            if (upstream != null) {
                upstream.value = next;
                SettingsUtil.save(settings);
                return;
            }
            value = next;
            if (path == null) {
                return;
            }
            properties.setProperty(key, next instanceof Color color
                    ? Integer.toHexString(color.getRGB() & 0xFFFFFF) : next.toString());
            try (var writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "BetterBaritone");
            } catch (IOException exception) {
                LoggerFactory.getLogger("BetterBaritone").warn("Could not save UI settings", exception);
            }
        }
    }
}
