package org.intermed.core.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventFlyweightPoolTest {

    @BeforeEach
    void setUp() {
        EventFlyweightPool.resetForTests();
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
        assertNull(event.eventName, "После release eventName должен быть очищен");
        assertNull(event.payload, "После release payload должен быть очищен");
    }

    @Test
    void testPoolReuse() {
        EventFlyweightPool.InterMedEvent first = EventFlyweightPool.acquire("A", "p1");
        EventFlyweightPool.release(first);
        EventFlyweightPool.InterMedEvent second = EventFlyweightPool.acquire("B", "p2");
        // The same object should be reused from the pool
        assertSame(first, second, "Освобождённый объект должен быть переиспользован из пула");
    }
}
