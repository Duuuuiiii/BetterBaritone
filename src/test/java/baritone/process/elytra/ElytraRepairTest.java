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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElytraRepairTest {
    @Test
    public void permitsOnlyOneFollowUpAfterTheFirstBottleIsConsumed() {
        assertTrue(ElytraRepair.canFollowUp(80, 1, 9, 10));
        assertFalse(ElytraRepair.canFollowUp(80, 1, 10, 10));
        assertFalse(ElytraRepair.canFollowUp(80, 1, 0, 1));
        assertFalse(ElytraRepair.canFollowUp(80, 2, 8, 9));
        assertFalse(ElytraRepair.canFollowUp(44, 1, 9, 10));
    }
}
