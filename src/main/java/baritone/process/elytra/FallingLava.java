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

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.function.Function;

/** Detects known lava that has an open vertical column into a proposed flight corridor. */
final class FallingLava {
    private static final byte CLEAR = 1;
    private static final byte FLOW = 2;
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
    private final int ceiling;
    private final Function<BlockPos, Cell> cell;

    FallingLava(Level world) {
        this(world.getMaxY(), pos -> {
            if (!world.isLoaded(pos)) {
                return Cell.BLOCKED;
            }
            var state = world.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return Cell.LAVA;
            }
            return state.isCollisionShapeFullBlock(world, pos) ? Cell.BLOCKED : Cell.OPEN;
        });
    }

    FallingLava(int ceiling, Function<BlockPos, Cell> cell) {
        this.ceiling = ceiling;
        this.cell = cell;
    }

    boolean threatens(AABB box) {
        int top = Mth.floor(box.maxY);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                if (couldFlowInto(x, top, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean couldFlowInto(int x, int y, int z) {
        if (y > ceiling) {
            return false;
        }
        long key = BlockPos.asLong(x, y, z);
        byte known = cache.get(key);
        if (known != 0) {
            return known == FLOW;
        }
        Cell block = cell.apply(new BlockPos(x, y, z));
        boolean flows = block == Cell.LAVA || block == Cell.OPEN && couldFlowInto(x, y + 1, z);
        cache.put(key, flows ? FLOW : CLEAR);
        return flows;
    }

    enum Cell { OPEN, BLOCKED, LAVA }
}
