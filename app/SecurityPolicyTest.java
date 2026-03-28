package org.intermed.core.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {

    @Test
    void testNullModIdHasPermissions() {
        // Для null (системный класс Java/Forge) доступ должен быть разрешён всегда
        assertTrue(SecurityPolicy.hasPermission(null, Capability.FILE_READ));
    }

    @Test
    void testAppleskinHasFileReadPermission() {
        // AppleSkin имеет право на чтение файлов (согласно заглушке)
        assertTrue(SecurityPolicy.hasPermission("appleskin", Capability.FILE_READ));
    }

    @Test
    void testUnknownModHasNoPermissions() {
        // Если мода нет в списке MOD_CAPABILITIES, права у него отсутствуют
        assertFalse(SecurityPolicy.hasPermission("unknown_mod", Capability.FILE_READ));
    }
}