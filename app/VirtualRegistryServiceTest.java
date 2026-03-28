package org.intermed.core.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualRegistryServiceTest {

    @Test
    void testResolveVirtualId_ConsistentForSameKey() {
        int firstCall = VirtualRegistryService.resolveVirtualId("minecraft:stone", -1);
        int secondCall = VirtualRegistryService.resolveVirtualId("minecraft:stone", -1);
        
        assertEquals(firstCall, secondCall, "Вызовы с одинаковым ключом должны возвращать один и тот же ID");
    }

    @Test
    void testResolveVirtualId_UniqueForDifferentKeys() {
        int id1 = VirtualRegistryService.resolveVirtualId("mod1:itemA", -1);
        int id2 = VirtualRegistryService.resolveVirtualId("mod2:itemB", -1);
        
        assertNotEquals(id1, id2, "Разные ключи должны получать уникальные ID");
        assertTrue(id1 >= 100000 && id2 >= 100000, "Сгенерированные ID должны начинаться с безопасного диапазона (100000+)");
    }
}