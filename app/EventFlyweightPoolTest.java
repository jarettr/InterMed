package org.intermed.core.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventFlyweightPoolTest {

    @BeforeEach
    void setUp() {
        // Выделяем объекты перед каждым тестом
        EventFlyweightPool.preallocate();
    }

    @Test
    void testAcquireAndRelease() {
        String testName = "TestEvent";
        Object testPayload = new Object();

        EventFlyweightPool.InterMedEvent event = EventFlyweightPool.acquire(testName, testPayload);
        
        assertNotNull(event);
        assertEquals(testName, event.eventName);
        assertEquals(testPayload, event.payload);

        EventFlyweightPool.release(event);
        assertNull(event.eventName, "После вызова release eventName должен быть очищен");
        assertNull(event.payload, "После вызова release payload должен быть очищен");
    }
}