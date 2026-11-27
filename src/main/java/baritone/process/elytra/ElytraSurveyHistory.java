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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Properties;

final class ElytraSurveyHistory {
    private final Path file;
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private final StringBuilder pending = new StringBuilder();

    ElytraSurveyHistory(Path file) throws IOException {
        this.file = file;
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    if (line.endsWith(";")) {
                        visited.add(Long.parseLong(line.substring(0, line.length() - 1)));
                    }
                } catch (NumberFormatException ignored) {
                    // An interrupted append may leave an incomplete record.
                }
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            // The first visit creates the journal.
        }
    }

    static ElytraSurveyPlan.Config loadConfig(Path journal) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(configPath(journal))) {
            properties.load(reader);
            var center = new ElytraSurveyPlan.Point(number(properties, "x"), number(properties, "z"));
            var config = new ElytraSurveyPlan.Config(center,
                    number(properties, "radius"), number(properties, "spacing"), number(properties, "limit"),
                    number(properties, "switchDistance"), Boolean.parseBoolean(properties.getProperty("random")),
                    number(properties, "minLeg"), number(properties, "maxLeg"),
                    number(properties, "direction"), Boolean.parseBoolean(properties.getProperty("clockwise")));
            return config.valid() ? config : null;
        } catch (java.nio.file.NoSuchFileException | IllegalArgumentException ignored) {
            return null;
        }
    }

    void saveConfig(ElytraSurveyPlan.Config config) throws IOException {
        var properties = new Properties();
        properties.setProperty("x", Integer.toString(config.center().x()));
        properties.setProperty("z", Integer.toString(config.center().z()));
        properties.setProperty("radius", Integer.toString(config.radius()));
        properties.setProperty("spacing", Integer.toString(config.spacing()));
        properties.setProperty("limit", Integer.toString(config.limit()));
        properties.setProperty("switchDistance", Integer.toString(config.switchDistance()));
        properties.setProperty("random", Boolean.toString(config.random()));
        properties.setProperty("minLeg", Integer.toString(config.minLeg()));
        properties.setProperty("maxLeg", Integer.toString(config.maxLeg()));
        properties.setProperty("direction", Integer.toString(config.direction()));
        properties.setProperty("clockwise", Boolean.toString(config.clockwise()));
        Path target = configPath(file);
        Path temporary = Files.createTempFile(file.getParent(), "elytra-survey-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "BetterBaritone");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path configPath(Path journal) {
        String name = journal.getFileName().toString();
        return journal.resolveSibling(name.substring(0, name.length() - 4) + ".properties");
    }

    private static int number(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key));
    }

    boolean contains(ElytraSurveyPlan.Point point) {
        return visited.contains(point.cell());
    }

    void mark(ElytraSurveyPlan.Point point) {
        if (visited.add(point.cell())) {
            pending.append(point.cell()).append(";\n");
        }
    }

    void flush() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        Files.writeString(file, "\n" + pending, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        pending.setLength(0);
    }
}
