package org.intermed.core.remapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MappingDictionaryTest {

    private MappingDictionary dict;

    @BeforeEach
    void setUp() {
        dict = new MappingDictionary();
    }

    @Test
    void testAddAndMapClass() {
        dict.addClass("class_1234", "net/minecraft/world/level/block/Block");
        String mapped = dict.map("class_1234");
        assertEquals("net/minecraft/world/level/block/Block", mapped);
    }

    @Test
    void testUnknownClassReturnsNull() {
        assertNull(dict.map("class_9999"));
    }

    @Test
    void testAddMethod() {
        dict.addClass("class_42", "net/minecraft/server/level/ServerPlayer");
        dict.addMethod("class_42", "method_1000", "()V", "tick");
        assertEquals("net/minecraft/server/level/ServerPlayer", dict.map("class_42"));
        assertEquals("tick", dict.mapMethodName("class_42", "method_1000", "()V"));
    }

    @Test
    void testAddField() {
        dict.addClass("class_77", "net/minecraft/world/item/ItemStack");
        dict.addField("class_77", "field_500", "I", "count");
        assertEquals("net/minecraft/world/item/ItemStack", dict.map("class_77"));
        assertEquals("count", dict.mapFieldName("class_77", "field_500", "I"));
    }

    @Test
    void testGetClassCountIncrementsPerEntry() {
        int before = dict.getClassCount();
        dict.addClass("class_new1", "com/example/A");
        dict.addClass("class_new2", "com/example/B");
        assertEquals(before + 2, dict.getClassCount());
    }

    @Test
    void testUpdateClass() {
        dict.addClass("class_upd", "old/ClassName");
        dict.updateClass("class_upd", "new/ClassName");
        assertEquals("new/ClassName", dict.map("class_upd"));
    }

    @Test
    void fingerprintChangesWhenMappingsChange() {
        String empty = dict.fingerprint();
        dict.addClass("class_fp", "mapped/Class");
        String updated = dict.fingerprint();
        assertNotEquals(empty, updated);
    }
}
