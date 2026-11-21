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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public record ElytraFlightProfile(double blocksPerMinute, double rocketsPerThousand,
                                  double durabilityPerThousand) {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElytraFlightProfile.class);

    public ElytraFlightProfile {
        if (!inRange(blocksPerMinute, 1, 100_000) || !inRange(rocketsPerThousand, 0, 10_000)
                || !inRange(durabilityPerThousand, 0, 20_000)) {
            throw new IllegalArgumentException("Flight rates are out of range");
        }
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    public ElytraFlightProfile learn(ElytraFlightProfile sample) {
        return new ElytraFlightProfile(
                blend(blocksPerMinute, sample.blocksPerMinute),
                blend(rocketsPerThousand, sample.rocketsPerThousand),
                blend(durabilityPerThousand, sample.durabilityPerThousand));
    }

    private static double blend(double previous, double sample) {
        return previous * 0.8 + sample * 0.2;
    }

    public ElytraFlightStats.Estimate estimate(double distance) {
        return new ElytraFlightStats.Estimate(
                Math.round(Math.max(0, distance) / blocksPerMinute * 60),
                (long) Math.ceil(Math.max(0, distance) * rocketsPerThousand / 1000),
                (long) Math.ceil(Math.max(0, distance) * durabilityPerThousand / 1000));
    }

    public static ElytraFlightProfile load(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            return new ElytraFlightProfile(
                    Double.parseDouble(properties.getProperty("blocksPerMinute", "NaN")),
                    Double.parseDouble(properties.getProperty("rocketsPerThousand", "NaN")),
                    Double.parseDouble(properties.getProperty("durabilityPerThousand", "NaN")));
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not read Elytra flight profile", failure);
            return null;
        }
    }

    public boolean save(Path path) {
        Properties properties = new Properties();
        properties.setProperty("blocksPerMinute", Double.toString(blocksPerMinute));
        properties.setProperty("rocketsPerThousand", Double.toString(rocketsPerThousand));
        properties.setProperty("durabilityPerThousand", Double.toString(durabilityPerThousand));
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "flight-", ".tmp");
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Baritone Elytra flight rates");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException failure) {
            LOGGER.warn("Could not save Elytra flight profile", failure);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
