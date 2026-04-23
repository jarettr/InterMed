package org.intermed.core.remapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.intermed.core.lifecycle.LifecycleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SymbolicReflectionFacadeTest {

    @BeforeEach
    void setUp() {
        System.clearProperty("remapping.symbolic.runtime.enabled");
        LifecycleManager.DICTIONARY.clear();

        String runtimeOwner = RuntimeOwner.class.getName().replace('.', '/');
        LifecycleManager.DICTIONARY.addClass("guest/SymbolicOwner", runtimeOwner);
        LifecycleManager.DICTIONARY.addMethod("guest/SymbolicOwner", "method_1000", "()Ljava/lang/String;", "tick");
        LifecycleManager.DICTIONARY.addMethod(runtimeOwner, "method_1000", "()Ljava/lang/String;", "tick");
        LifecycleManager.DICTIONARY.addField("guest/SymbolicOwner", "field_500", "I", "count");
        LifecycleManager.DICTIONARY.addField(runtimeOwner, "field_500", "I", "count");
        InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("remapping.symbolic.runtime.enabled");
        InterMedRemapper.clearCaches();
        LifecycleManager.DICTIONARY.clear();
    }

    @Test
    void resolvesClassMethodAndFieldThroughSymbolicFacade() throws Throwable {
        Class<?> resolvedClass = SymbolicReflectionFacade.forName(
            "guest.SymbolicOwner", false, RuntimeOwner.class.getClassLoader());
        Method resolvedMethod = SymbolicReflectionFacade.getDeclaredMethod(
            RuntimeOwner.class, "method_1000", new Class<?>[0]);
        Field resolvedField = SymbolicReflectionFacade.getDeclaredField(RuntimeOwner.class, "field_500");

        RuntimeOwner instance = new RuntimeOwner();
        MethodHandle handle = SymbolicReflectionFacade.findVirtual(
            MethodHandles.lookup(),
            RuntimeOwner.class,
            "method_1000",
            MethodType.methodType(String.class)
        );

        assertSame(RuntimeOwner.class, resolvedClass);
        assertEquals("tick", resolvedMethod.getName());
        assertEquals("count", resolvedField.getName());
        assertEquals("tick", resolvedMethod.invoke(instance));
        assertEquals("tick", (String) handle.invoke(instance));
        assertEquals(7, resolvedField.getInt(instance));
    }

    static final class RuntimeOwner {
        private final int count = 7;

        private String tick() {
            return "tick";
        }
    }
}
