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
    private final Deque<Segment> recent = new ArrayDeque<>();
    private double distance;
    private double seconds;
    private long rockets;
    private long wear;
    private double recentBlocks;
    private double recentSeconds;
    private double segmentBlocks;
    private double segmentSeconds;
    private Vec3 previousPosition;
    private long previousTime;
    private int previousWear = -1;
    private boolean dirty;

    public void sample(Vec3 position, int damage) {
        long now = System.nanoTime();
        if (this.previousPosition != null) {
            double deltaSeconds = Math.max(0, (now - this.previousTime) / 1e9);
            double blocks = position.subtract(this.previousPosition).horizontalDistance();
            // Ignore teleports and gaps when the client was suspended.
            if (deltaSeconds <= 3 && blocks < 20) {
                append(blocks, deltaSeconds);
                int used = damage - this.previousWear;
                if (this.previousWear >= 0 && damage >= 0 && used > 0 && used <= 4) {
                    this.wear += used;
                }
            }
        }
        this.previousPosition = position;
        this.previousTime = now;
        this.previousWear = damage;
    }

    public void interrupt() {
        this.previousPosition = null;
        this.previousWear = -1;
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
