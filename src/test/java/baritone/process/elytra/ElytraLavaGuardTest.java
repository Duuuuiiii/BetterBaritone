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

import baritone.api.utils.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElytraLavaGuardTest {
    @Test
    public void detectsLavaDuringDescentBeforeThePlayerTouchesIt() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var dive = new Vec3(0.3, -1.2, 0.8);
        assertTrue(ElytraLavaGuard.threatens(player, dive, new Rotation(0, 11), 0, box -> box.minY < 30));
        assertFalse(ElytraLavaGuard.threatens(player, dive, new Rotation(0, 11), 0, box -> false));
    }
}
