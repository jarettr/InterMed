package net.fabricmc.fabric.api.object.builder.v1.block;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

public final class FabricBlockSettings {

    private final BlockBehaviour.Properties delegate;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private FabricBlockSettings() {
        this(createDelegate());
    }

    private FabricBlockSettings(BlockBehaviour.Properties delegate) {
        this.delegate = delegate;
    }

    public static FabricBlockSettings create() {
        return new FabricBlockSettings();
    }

    public static FabricBlockSettings of() {
        return create();
    }

    public static FabricBlockSettings copyOf(Object source) {
        if (source instanceof BlockBehaviour blockBehaviour) {
            try {
                return new FabricBlockSettings(BlockBehaviour.Properties.copy(blockBehaviour))
                    .property("copyOf", source);
            } catch (Throwable ignored) {
                return create().property("copyOf", source);
            }
        }
        return create().property("copyOf", source);
    }

    public FabricBlockSettings strength(float hardness) {
        return strength(hardness, hardness);
    }

    public FabricBlockSettings strength(float hardness, float resistance) {
        if (delegate != null) {
            delegate.strength(hardness, resistance);
        }
        properties.put("hardness", hardness);
        properties.put("resistance", resistance);
        return this;
    }

    public FabricBlockSettings breakInstantly() {
        if (delegate != null) {
            delegate.instabreak();
        }
        return strength(0.0f);
    }

    public FabricBlockSettings requiresTool() {
        return requiresCorrectToolForDrops();
    }

    public FabricBlockSettings requiresCorrectToolForDrops() {
        if (delegate != null) {
            delegate.requiresCorrectToolForDrops();
        }
        return property("requiresCorrectToolForDrops", true);
    }

    public FabricBlockSettings dropsNothing() {
        if (delegate != null) {
            delegate.noLootTable();
        }
        return property("dropsNothing", true);
    }

    public FabricBlockSettings collidable(boolean collidable) {
        return property("collidable", collidable);
    }

    public FabricBlockSettings noCollision() {
        if (delegate != null) {
            delegate.noCollission();
        }
        return collidable(false);
    }

    public FabricBlockSettings nonOpaque() {
        return noOcclusion();
    }

    public FabricBlockSettings noOcclusion() {
        if (delegate != null) {
            delegate.noOcclusion();
        }
        return property("noOcclusion", true);
    }

    public FabricBlockSettings luminance(int luminance) {
        if (delegate != null) {
            delegate.lightLevel(ignored -> luminance);
        }
        return property("luminance", luminance);
    }

    public FabricBlockSettings lightLevel(ToIntFunction<BlockState> lightLevel) {
        if (delegate != null) {
            delegate.lightLevel(lightLevel);
        }
        return property("lightLevel", lightLevel);
    }

    public FabricBlockSettings mapColor(DyeColor dyeColor) {
        if (delegate != null) {
            delegate.mapColor(dyeColor);
        }
        return property("mapColor", dyeColor);
    }

    public FabricBlockSettings mapColor(MapColor mapColor) {
        if (delegate != null) {
            delegate.mapColor(mapColor);
        }
        return property("mapColor", mapColor);
    }

    public FabricBlockSettings mapColor(Object mapColor) {
        return property("mapColor", mapColor);
    }

    public FabricBlockSettings sounds(SoundType soundType) {
        if (delegate != null) {
            delegate.sound(soundType);
        }
        return property("soundType", soundType);
    }

    public FabricBlockSettings sounds(Object soundType) {
        return property("soundType", soundType);
    }

    public FabricBlockSettings ticksRandomly() {
        if (delegate != null) {
            delegate.randomTicks();
        }
        return property("ticksRandomly", true);
    }

    public FabricBlockSettings dynamicShape() {
        if (delegate != null) {
            delegate.dynamicShape();
        }
        return property("dynamicShape", true);
    }

    public FabricBlockSettings dropsLike(Block block) {
        if (delegate != null) {
            delegate.dropsLike(block);
        }
        return property("dropsLike", block);
    }

    public FabricBlockSettings property(String key, Object value) {
        if (key != null && !key.isBlank()) {
            properties.put(key, value);
        }
        return this;
    }

    public Object property(String key) {
        return properties.get(key);
    }

    public Map<String, Object> snapshotProperties() {
        return Map.copyOf(properties);
    }

    public BlockBehaviour.Properties toProperties() {
        return delegate;
    }

    private static BlockBehaviour.Properties createDelegate() {
        try {
            return BlockBehaviour.Properties.of();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
