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

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

public final class ElytraMileage {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElytraMileage.class);
    public static final int ETA_BLOCKS = 10_000;
    private static final int SEGMENT_BLOCKS = 100;
    private static final double LEGACY_ROUTE_ALLOWANCE = 1.25;
    private final Deque<Segment> recent = new ArrayDeque<>();
    private double distance;
    private double seconds;
    private long rockets;
    private long wear;
    private double recentBlocks;
    private double recentSeconds;
    private double tripSecondsPerTenThousand;
    private double tripRocketsPerTenThousand = -1;
    private double tripWearPerTenThousand = -1;
    private double segmentBlocks;
    private double segmentSeconds;
    private Vec3 previousPosition;
    private long previousTime;
    private int previousDurability = -1;
    private boolean dirty;

    public int sample(LocalPlayer player) {
        // Transfers between chest, inventory and cursor preserve the total durability.
        int durability = durability(player.getItemBySlot(EquipmentSlot.CHEST))
                + durability(player.getOffhandItem()) + durability(player.containerMenu.getCarried());
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            durability += durability(stack);
        }
        return sample(player.position(), durability, System.nanoTime());
    }

    private static int durability(ItemStack stack) {
        return stack.is(Items.ELYTRA) ? stack.getMaxDamage() - stack.getDamageValue() : 0;
    }

    int sample(Vec3 position, int durability, long now) {
        int wearUsed = 0;
        if (this.previousPosition != null) {
            double deltaSeconds = Math.max(0, (now - this.previousTime) / 1e9);
            double blocks = position.subtract(this.previousPosition).horizontalDistance();
            // Ignore teleports and gaps when the client was suspended.
            if (deltaSeconds <= 3 && blocks < 20) {
                append(blocks, deltaSeconds);
                int used = this.previousDurability - durability;
                if (used > 0 && used <= 4) {
                    wearUsed = used;
                    this.wear += used;
                }
            }
        }
        this.previousPosition = position;
        this.previousTime = now;
        this.previousDurability = durability;
        return wearUsed;
    }

    public void interrupt() {
        this.previousPosition = null;
        this.previousDurability = -1;
    }

    public void rocketUsed() {
        this.rockets++;
        this.dirty = true;
    }

    void append(double blocks, double flightSeconds) {
        this.distance += blocks;
        this.seconds += flightSeconds;
        this.segmentBlocks += blocks;
        this.segmentSeconds += flightSeconds;
        this.dirty = true;
        if (this.segmentBlocks >= SEGMENT_BLOCKS) {
            commitSegment();
        }
    }

    private void commitSegment() {
        if (this.segmentBlocks <= 0) {
            return;
        }
        addRecent(this.segmentBlocks, this.segmentSeconds);
        this.segmentBlocks = 0;
        this.segmentSeconds = 0;
    }

    private void addRecent(double blocks, double duration) {
        this.recent.addLast(new Segment(blocks, duration));
        this.recentBlocks += blocks;
        this.recentSeconds += duration;
        while (!this.recent.isEmpty() && this.recentBlocks - this.recent.getFirst().blocks >= ETA_BLOCKS) {
            Segment first = this.recent.removeFirst();
            this.recentBlocks -= first.blocks;
            this.recentSeconds -= first.seconds;
        }
    }

    public double recentSecondsPerTenThousand() {
        double windowBlocks = this.recentBlocks + this.segmentBlocks;
        if (windowBlocks < ETA_BLOCKS) {
            return -1;
        }
        double duration = this.recentSeconds + this.segmentSeconds;
        double extra = windowBlocks - ETA_BLOCKS;
        for (Segment segment : this.recent) {
            if (extra <= 0) {
                break;
            }
            double take = Math.min(extra, segment.blocks);
            duration -= segment.seconds * take / segment.blocks;
            extra -= take;
        }
        return Math.max(0, duration);
    }

    public void recordTripRates(ElytraTripForecast trip) {
        double measuredTime = trip.calibratedSecondsPerTenThousand();
        if (measuredTime <= 0) {
            return;
        }
        double measuredRockets = trip.calibratedRocketsPerTenThousand();
        double measuredWear = trip.calibratedWearPerTenThousand();
        if (measuredTime != this.tripSecondsPerTenThousand || measuredRockets != this.tripRocketsPerTenThousand
                || measuredWear != this.tripWearPerTenThousand) {
            this.tripSecondsPerTenThousand = measuredTime;
            this.tripRocketsPerTenThousand = measuredRockets;
            this.tripWearPerTenThousand = measuredWear;
            this.dirty = true;
        }
    }

    public ElytraFlightStats.Estimate estimate(double remaining) {
        return new ElytraFlightStats.Estimate(estimateSeconds(remaining),
                estimateConsumption(remaining, this.tripRocketsPerTenThousand, this.rockets),
                estimateConsumption(remaining, this.tripWearPerTenThousand, this.wear));
    }

    private long estimateConsumption(double remaining, double rate, long historical) {
        if (remaining <= 0) {
            return 0;
        }
        if (rate >= 0) {
            return (long) Math.ceil(rate * remaining / ETA_BLOCKS);
        }
        return this.distance >= 64 && historical > 0
                ? (long) Math.ceil(historical * remaining / this.distance * LEGACY_ROUTE_ALLOWANCE)
                : -1;
    }

    public long estimateSeconds(double remaining) {
        double pace = this.tripSecondsPerTenThousand;
        if (pace <= 0) {
            double historical = recentSecondsPerTenThousand();
            if (historical <= 0 && this.distance >= 64 && this.seconds > 0) {
                historical = ETA_BLOCKS * this.seconds / this.distance;
            }
            // Older profiles record traveled distance and cannot account for detours.
            pace = historical > 0 ? historical * LEGACY_ROUTE_ALLOWANCE : 1_000;
        }
        return Math.round(pace * remaining / ETA_BLOCKS);
    }

    public Summary summary() {
        return new Summary(this.distance, this.seconds, this.rockets, this.wear);
    }

    public void save(Path path) {
        if (!this.dirty) {
            return;
        }
        commitSegment();
        Properties properties = new Properties();
        properties.setProperty("distance", Double.toString(this.distance));
        properties.setProperty("seconds", Double.toString(this.seconds));
        properties.setProperty("rockets", Long.toString(this.rockets));
        properties.setProperty("wear", Long.toString(this.wear));
        properties.setProperty("tripSecondsPerTenThousand", Double.toString(this.tripSecondsPerTenThousand));
        if (this.tripRocketsPerTenThousand >= 0) {
            properties.setProperty("tripRocketsPerTenThousand", Double.toString(this.tripRocketsPerTenThousand));
        }
        if (this.tripWearPerTenThousand >= 0) {
            properties.setProperty("tripWearPerTenThousand", Double.toString(this.tripWearPerTenThousand));
        }
        StringBuilder window = new StringBuilder();
        for (Segment segment : this.recent) {
            window.append(segment.blocks).append(',').append(segment.seconds).append(';');
        }
        properties.setProperty("recent", window.toString());
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "flight-", ".tmp");
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "BetterBaritone flight statistics");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            this.dirty = false;
        } catch (IOException failure) {
            LOGGER.warn("Could not save flight mileage", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static ElytraMileage load(Path path) {
        if (!Files.isRegularFile(path)) {
            return new ElytraMileage();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            ElytraMileage result = new ElytraMileage();
            result.distance = nonnegative(properties.getProperty("distance", "0"));
            result.seconds = nonnegative(properties.getProperty("seconds", "0"));
            result.rockets = (long) nonnegative(properties.getProperty("rockets", "0"));
            result.wear = (long) nonnegative(properties.getProperty("wear", "0"));
            result.tripSecondsPerTenThousand = nonnegative(properties.getProperty("tripSecondsPerTenThousand", "0"));
            if (properties.containsKey("tripRocketsPerTenThousand")) {
                result.tripRocketsPerTenThousand = nonnegative(properties.getProperty("tripRocketsPerTenThousand"));
            }
            if (properties.containsKey("tripWearPerTenThousand")) {
                result.tripWearPerTenThousand = nonnegative(properties.getProperty("tripWearPerTenThousand"));
            }
            for (String encoded : properties.getProperty("recent", "").split(";")) {
                if (!encoded.isEmpty()) {
                    String[] pair = encoded.split(",", -1);
                    if (pair.length != 2) {
                        throw new IllegalArgumentException("Invalid mileage segment");
                    }
                    double blocks = nonnegative(pair[0]);
                    if (blocks > 0) {
                        result.addRecent(blocks, nonnegative(pair[1]));
                    }
                }
            }
            return result;
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not read flight mileage", failure);
            return new ElytraMileage();
        }
    }

    private static double nonnegative(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid flight statistic");
        }
        return value;
    }

    private record Segment(double blocks, double seconds) {}

    public record Summary(double blocks, double seconds, long rockets, long wear) {
        public double blocksPerMinute() {
            return seconds > 0 ? blocks / seconds * 60 : 0;
        }
    }
}
