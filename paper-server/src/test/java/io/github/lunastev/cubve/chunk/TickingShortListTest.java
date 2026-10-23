package io.github.lunastev.cubve.chunk;

import ca.spottedleaf.moonrise.common.list.ShortList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Normal
class TickingShortListTest {
    @Test
    void emptyClearRemoveAndCapacityHintsDoNotChangeMembership() {
        ShortList list = new ShortList();
        assertFalse(list.remove((short) 0));
        list.clear();
        list.setMinCapacity(0);
        list.setMinCapacity(-1);
        assertEquals(0, list.size());
        list.setMinCapacity(4096);
        for (short i = 0; i < 4096; i++) assertTrue(list.add(i));
        for (short i = 0; i < 4096; i++) assertFalse(list.add(i));
        for (short i = 0; i < 4096; i++) assertTrue(list.remove(i));
        assertEquals(0, list.size());
        list.clear();
        assertTrue(list.add(Short.MIN_VALUE));
        assertTrue(list.add(Short.MAX_VALUE));
        assertTrue(list.remove(Short.MIN_VALUE));
        assertEquals(Short.MAX_VALUE, list.getRaw(0));
    }

    @Test
    void randomMutationsPreserveUniquenessAndSwappedTailIndices() {
        ShortList list = new ShortList();
        Set<Short> expected = new HashSet<>();
        Random random = new Random(8142026);
        for (int i = 0; i < 30_000; i++) {
            short value = (short) random.nextInt(4096);
            switch (random.nextInt(30)) {
                case 0 -> { expected.clear(); list.clear(); }
                case 1 -> list.setMinCapacity(random.nextInt(4097));
                default -> {
                    if (random.nextBoolean()) assertEquals(expected.add(value), list.add(value));
                    else assertEquals(expected.remove(value), list.remove(value));
                }
            }
            assertEquals(expected.size(), list.size());
            Set<Short> actual = new HashSet<>();
            for (int j = 0; j < list.size(); j++) assertTrue(actual.add(list.getRaw(j)));
            assertEquals(expected, actual);
        }
    }
}
