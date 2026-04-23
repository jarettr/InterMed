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
    void methodLookupFallsBackToUnambiguousNameAliasWhenDescriptorWasAlreadyRemapped() {
        dict.addClass("net/minecraft/class_2378", "net/minecraft/core/Registry");
        dict.addClass("net/minecraft/class_5321", "net/minecraft/resources/ResourceKey");
        dict.addMethod(
            "net/minecraft/class_2378",
            "method_30517",
            "()Lnet/minecraft/class_5321;",
            "m_123023_"
        );
        dict.addMethod(
            "net/minecraft/core/Registry",
            "method_30517",
            "()Lnet/minecraft/class_5321;",
            "m_123023_"
        );

        assertEquals(
            "m_123023_",
            dict.mapMethodName(
                "net/minecraft/core/Registry",
                "method_30517",
                "()Lnet/minecraft/resources/ResourceKey;"
            )
        );
    }

    @Test
    void methodNameAliasDoesNotOverrideAmbiguousMethodMappings() {
        dict.addMethod("class_42", "method_1000", "()V", "tick");
        dict.addMethod("class_42", "method_1000", "(I)V", "tickWithCount");

        assertEquals("tick", dict.mapMethodName("class_42", "method_1000", "()V"));
        assertEquals("tickWithCount", dict.mapMethodName("class_42", "method_1000", "(I)V"));
        assertEquals("m_1000_", dict.mapMethodName("class_42", "method_1000", "(Ljava/lang/String;)V"));
    }

    @Test
    void methodLookupFallsBackToInheritedOwnerWhenSubclassDeclaresNoMapping() {
        String attributeOwner = internalName(TestAttribute.class);
        String rangedOwner = internalName(TestRangedAttribute.class);

        dict.addClass("test/class_1320", attributeOwner);
        dict.addClass("test/class_1329", rangedOwner);
        dict.addMethod(attributeOwner, "method_26829", "(Z)Ltest/class_1320;", "m_22084_");

        assertEquals("m_22084_", dict.mapMethodName("test/class_1329", "method_26829", "(Z)Ltest/class_1320;"));
    }

    @Test
    void inheritedMethodAliasStillWorksAfterDescriptorWasAlreadyRemapped() {
        String attributeOwner = internalName(TestAttribute.class);
        String rangedOwner = internalName(TestRangedAttribute.class);

        dict.addClass("test/class_1320", attributeOwner);
        dict.addClass("test/class_1329", rangedOwner);
        dict.addMethod(attributeOwner, "method_26829", "(Z)Ltest/class_1320;", "m_22084_");

        assertEquals(
            "m_22084_",
            dict.mapMethodName(
                rangedOwner,
                "method_26829",
                "(Z)L" + attributeOwner + ";"
            )
        );
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

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static class TestAttribute {}

    static class TestRangedAttribute extends TestAttribute {}
}
