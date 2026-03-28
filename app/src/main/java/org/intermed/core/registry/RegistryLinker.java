package org.intermed.core.registry;

import java.lang.invoke.*;

public class RegistryLinker {
    /**
     * Bootstrap метод для invokedynamic (Req 4.3).
     */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) {
        Object value = VirtualRegistry.getFast(name);
        
        // Если объект еще не зарегистрирован, создаем динамический Target
        MethodHandle target = MethodHandles.constant(type.returnType(), value);
        return new ConstantCallSite(target);
    }
}