package net.fabricmc.fabric.api.object.builder.v1.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricBlockSettingsTest {

    @Test
    void exposesChainableBlockSettingsBuilder() {
        Object soundType = new Object();
        FabricBlockSettings settings = FabricBlockSettings.create()
            .strength(2.0f, 6.0f)
            .sounds(soundType)
            .requiresTool()
            .noCollision()
            .luminance(12);

        assertEquals(2.0f, settings.property("hardness"));
        assertEquals(6.0f, settings.property("resistance"));
        assertSame(soundType, settings.property("soundType"));
        assertEquals(false, settings.property("collidable"));
        assertEquals(12, settings.property("luminance"));
        assertTrue(settings.snapshotProperties().containsKey("requiresCorrectToolForDrops"));
    }
}
