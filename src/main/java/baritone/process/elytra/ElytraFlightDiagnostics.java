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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ElytraFlightDiagnostics {
    private static final long LIMIT = 2L * 1024 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterBaritone/Flight");
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), work -> {
                Thread thread = new Thread(work, "BetterBaritone flight log");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private ElytraFlightDiagnostics() {}

    public static void record(Path baritoneDirectory, String details) {
        Path file = baritoneDirectory.resolve("logs").resolve("flight.log");
        String line = OffsetDateTime.now() + " " + details + System.lineSeparator();
        WRITER.execute(() -> {
            try {
                append(file, line, LIMIT);
            } catch (IOException failure) {
                LOGGER.warn("Could not write BetterBaritone flight log", failure);
            }
        });
    }

    static void append(Path file, String line, long limit) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) >= limit) {
            Path previous = file.resolveSibling("flight.1.log");
            if (Files.isRegularFile(previous)) {
                Files.move(previous, file.resolveSibling("flight.2.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
