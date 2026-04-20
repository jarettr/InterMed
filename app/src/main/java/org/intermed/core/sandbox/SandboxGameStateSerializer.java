package org.intermed.core.sandbox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts Minecraft runtime objects to a flat
 * {@link SandboxSharedExecutionContext.SharedStateGraph} using reflective
 * property extraction, avoiding deep object-graph serialization (ТЗ 3.4.3).
 *
 * <p>The key insight is that sandbox guests (Espresso/Wasm) need only a handful
 * of scalar fields from complex MC objects — position, health, dimension, etc.
 * Extracting those into the shared off-heap buffer avoids copying the full heap
 * graph into the guest runtime.  All extraction is best-effort via reflection;
 * any inaccessible field or thrown exception results in an empty/default value
 * rather than a propagated failure.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SharedStateGraph graph = SandboxGameStateSerializer.builder()
 *     .player(serverPlayer)
 *     .world(serverLevel)
 *     .blockPos(blockPos)
 *     .build();
 *
 * try (SandboxSharedExecutionContext.ExecutionFrame frame =
 *         SandboxSharedExecutionContext.open(
 *             modId, key, target,
 *             requestedMode, effectiveMode,
 *             planReason, hotPath, risky, fallbackApplied,
 *             graph)) {
 *     SandboxSharedExecutionContext.bind(frame, () -> sandbox.execute(...));
 * }
 * }</pre>
 *
 * <h3>Node types</h3>
 * <ul>
 *   <li>{@value #NODE_INVOCATION} — top-level invocation context</li>
 *   <li>{@value #NODE_PLAYER}     — player entity state</li>
 *   <li>{@value #NODE_WORLD}      — server/client level metadata</li>
 *   <li>{@value #NODE_BLOCK_POS}  — immutable block position</li>
 *   <li>{@value #NODE_BLOCK_STATE}— block state registry key</li>
 *   <li>{@value #NODE_ENTITY}     — generic entity snapshot</li>
 *   <li>{@value #NODE_ITEM_STACK} — item stack descriptor</li>
 * </ul>
 */
public final class SandboxGameStateSerializer {

    // ---- Node-type constants (read by guests via currentSharedGraphNodeType) ----
    public static final String NODE_INVOCATION  = "invocation";
    public static final String NODE_PLAYER      = "player";
    public static final String NODE_WORLD       = "world";
    public static final String NODE_BLOCK_POS   = "block_pos";
    public static final String NODE_BLOCK_STATE = "block_state";
    public static final String NODE_ENTITY      = "entity";
    public static final String NODE_ITEM_STACK  = "item_stack";

    // ---- Common property keys guests can query via currentSharedGraphNodeProperty ----
    public static final String PROP_MOD_ID      = "modId";
    public static final String PROP_TARGET      = "target";
    public static final String PROP_VERSION     = "intermedVersion";
    public static final String PROP_UUID        = "uuid";
    public static final String PROP_GAME_MODE   = "gameMode";
    public static final String PROP_HEALTH      = "health";
    public static final String PROP_MAX_HEALTH  = "maxHealth";
    public static final String PROP_FOOD_LEVEL  = "foodLevel";
    public static final String PROP_DIMENSION   = "dimension";
    public static final String PROP_DAY_TIME    = "dayTime";
    public static final String PROP_RAINING     = "raining";
    public static final String PROP_THUNDERING  = "thundering";
    public static final String PROP_SEED        = "seed";
    public static final String PROP_PLAYER_COUNT = "playerCount";
    public static final String PROP_X           = "x";
    public static final String PROP_Y           = "y";
    public static final String PROP_Z           = "z";
    public static final String PROP_BLOCK_ID    = "blockId";
    public static final String PROP_ENTITY_TYPE = "entityType";
    public static final String PROP_ENTITY_ID   = "entityId";
    public static final String PROP_ITEM_ID     = "itemId";
    public static final String PROP_ITEM_COUNT  = "itemCount";
    public static final String PROP_ITEM_DAMAGE = "itemDamage";

    private static final String UNKNOWN         = "unknown";
    private static final String INTERMED_VERSION = "8.0";

    private SandboxGameStateSerializer() {}

    // ---- Public factory API ------------------------------------------------

    /**
     * Returns a {@link Builder} for constructing the state graph incrementally.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Minimal invocation-only graph — no game objects attached.
     * Suitable as a zero-overhead baseline when no MC state needs crossing.
     */
    public static SandboxSharedExecutionContext.SharedStateGraph invocationOnly(
            String modId, String target) {
        SandboxSharedExecutionContext.SharedStateNode root =
            SandboxSharedExecutionContext.SharedStateNode.of(NODE_INVOCATION, sanitize(target))
                .withProperty(PROP_MOD_ID, sanitize(modId))
                .withProperty(PROP_TARGET, sanitize(target))
                .withProperty(PROP_VERSION, INTERMED_VERSION);
        return SandboxSharedExecutionContext.SharedStateGraph.ofRoot(root);
    }

    /**
     * Full state graph using the fluent builder.
     *
     * @param modId   the mod whose logic runs in the sandbox
     * @param target  the class or method being invoked
     * @param player  Minecraft player object (may be null)
     * @param world   Minecraft level/world object (may be null)
     * @param pos     Minecraft BlockPos (may be null)
     */
    public static SandboxSharedExecutionContext.SharedStateGraph buildFor(
            String modId,
            String target,
            Object player,
            Object world,
            Object pos) {
        return builder()
            .modId(modId)
            .target(target)
            .player(player)
            .world(world)
            .blockPos(pos)
            .build();
    }

    // ---- Extraction helpers ------------------------------------------------

    /** Extracts a player node from any AbstractPlayer/ServerPlayer MC object. */
    public static SandboxSharedExecutionContext.SharedStateNode extractPlayer(Object playerObject) {
        if (playerObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_PLAYER, "null");
        }

        String name      = reflectPlayerName(playerObject);
        String uuid      = reflectString(playerObject, "uuid", "getStringUUID");
        String gameMode  = reflectGameMode(playerObject);
        double health    = reflectDouble(playerObject, "health", "getHealth");
        double maxHealth = reflectDouble(playerObject, "maxHealth", "getMaxHealth");
        int foodLevel    = reflectInt(playerObject, "foodLevel", "getFoodLevel");
        double x         = reflectDouble(playerObject, "x", "getX");
        double y         = reflectDouble(playerObject, "y", "getY");
        double z         = reflectDouble(playerObject, "z", "getZ");
        String dimension = reflectDimensionId(playerObject);

        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_PLAYER, name)
            .withProperty(PROP_UUID,        sanitize(uuid))
            .withProperty(PROP_GAME_MODE,   sanitize(gameMode))
            .withProperty(PROP_HEALTH,      Double.toString(health))
            .withProperty(PROP_MAX_HEALTH,  Double.toString(maxHealth))
            .withProperty(PROP_FOOD_LEVEL,  Integer.toString(foodLevel))
            .withProperty(PROP_DIMENSION,   sanitize(dimension))
            .withProperty(PROP_X,           Double.toString(x))
            .withProperty(PROP_Y,           Double.toString(y))
            .withProperty(PROP_Z,           Double.toString(z));
    }

    /** Extracts world/level metadata from any Level/ServerLevel/ClientLevel. */
    public static SandboxSharedExecutionContext.SharedStateNode extractWorld(Object levelObject) {
        if (levelObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_WORLD, "null");
        }

        String dimension  = reflectDimensionId(levelObject);
        long dayTime      = reflectLong(levelObject, "dayTime", "getDayTime");
        boolean raining   = reflectBoolean(levelObject, "raining", "isRaining");
        boolean thunder   = reflectBoolean(levelObject, "thundering", "isThundering");
        long seed         = reflectLong(levelObject, "seed", "getSeed");
        int playerCount   = reflectInt(levelObject, "playerCount", "getPlayerCount");

        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_WORLD, sanitize(dimension))
            .withProperty(PROP_DIMENSION,     sanitize(dimension))
            .withProperty(PROP_DAY_TIME,      Long.toString(dayTime))
            .withProperty(PROP_RAINING,       Boolean.toString(raining))
            .withProperty(PROP_THUNDERING,    Boolean.toString(thunder))
            .withProperty(PROP_SEED,          Long.toString(seed))
            .withProperty(PROP_PLAYER_COUNT,  Integer.toString(playerCount));
    }

    /** Extracts coordinates from any BlockPos-like object. */
    public static SandboxSharedExecutionContext.SharedStateNode extractBlockPos(Object blockPosObject) {
        if (blockPosObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_BLOCK_POS, "null");
        }
        int x = reflectInt(blockPosObject, "x", "getX");
        int y = reflectInt(blockPosObject, "y", "getY");
        int z = reflectInt(blockPosObject, "z", "getZ");
        String label = x + "," + y + "," + z;
        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_BLOCK_POS, label)
            .withProperty(PROP_X, Integer.toString(x))
            .withProperty(PROP_Y, Integer.toString(y))
            .withProperty(PROP_Z, Integer.toString(z));
    }

    /** Extracts the registry key string from any BlockState object. */
    public static SandboxSharedExecutionContext.SharedStateNode extractBlockState(Object blockStateObject) {
        if (blockStateObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_BLOCK_STATE, "null");
        }
        String blockId = reflectBlockId(blockStateObject);
        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_BLOCK_STATE, sanitize(blockId))
            .withProperty(PROP_BLOCK_ID, sanitize(blockId));
    }

    /** Extracts a generic entity snapshot from any Entity subclass. */
    public static SandboxSharedExecutionContext.SharedStateNode extractEntity(Object entityObject) {
        if (entityObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_ENTITY, "null");
        }
        String entityType = reflectEntityType(entityObject);
        String entityId   = reflectString(entityObject, "id", "getId", "getStringUUID");
        double x          = reflectDouble(entityObject, "x", "getX");
        double y          = reflectDouble(entityObject, "y", "getY");
        double z          = reflectDouble(entityObject, "z", "getZ");
        double health     = reflectDouble(entityObject, "health", "getHealth");

        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_ENTITY, sanitize(entityType))
            .withProperty(PROP_ENTITY_TYPE, sanitize(entityType))
            .withProperty(PROP_ENTITY_ID,   sanitize(entityId))
            .withProperty(PROP_X,           Double.toString(x))
            .withProperty(PROP_Y,           Double.toString(y))
            .withProperty(PROP_Z,           Double.toString(z))
            .withProperty(PROP_HEALTH,      Double.toString(health));
    }

    /** Extracts item metadata from any ItemStack object. */
    public static SandboxSharedExecutionContext.SharedStateNode extractItemStack(Object itemStackObject) {
        if (itemStackObject == null) {
            return SandboxSharedExecutionContext.SharedStateNode.of(NODE_ITEM_STACK, "null");
        }
        String itemId = reflectItemId(itemStackObject);
        int count     = reflectInt(itemStackObject, "count", "getCount");
        int damage    = reflectInt(itemStackObject, "damage", "getDamageValue");

        return SandboxSharedExecutionContext.SharedStateNode.of(NODE_ITEM_STACK, sanitize(itemId))
            .withProperty(PROP_ITEM_ID,    sanitize(itemId))
            .withProperty(PROP_ITEM_COUNT, Integer.toString(count))
            .withProperty(PROP_ITEM_DAMAGE, Integer.toString(damage));
    }

    // ---- Fluent Builder ---------------------------------------------------

    public static final class Builder {
        private String modId  = UNKNOWN;
        private String target = "";
        private final List<SandboxSharedExecutionContext.SharedStateNode> children = new ArrayList<>(8);

        private Builder() {}

        public Builder modId(String modId) {
            this.modId = sanitize(modId);
            return this;
        }

        public Builder target(String target) {
            this.target = sanitize(target);
            return this;
        }

        public Builder player(Object playerObject) {
            if (playerObject != null) {
                children.add(extractPlayer(playerObject));
            }
            return this;
        }

        public Builder world(Object levelObject) {
            if (levelObject != null) {
                children.add(extractWorld(levelObject));
            }
            return this;
        }

        public Builder blockPos(Object blockPosObject) {
            if (blockPosObject != null) {
                children.add(extractBlockPos(blockPosObject));
            }
            return this;
        }

        public Builder blockState(Object blockStateObject) {
            if (blockStateObject != null) {
                children.add(extractBlockState(blockStateObject));
            }
            return this;
        }

        public Builder entity(Object entityObject) {
            if (entityObject != null) {
                children.add(extractEntity(entityObject));
            }
            return this;
        }

        public Builder itemStack(Object itemStackObject) {
            if (itemStackObject != null) {
                children.add(extractItemStack(itemStackObject));
            }
            return this;
        }

        /** Attach a pre-built node (e.g. from a mod that has its own extractor). */
        public Builder node(SandboxSharedExecutionContext.SharedStateNode node) {
            if (node != null) {
                children.add(node);
            }
            return this;
        }

        public SandboxSharedExecutionContext.SharedStateGraph build() {
            SandboxSharedExecutionContext.SharedStateNode root =
                SandboxSharedExecutionContext.SharedStateNode.of(NODE_INVOCATION, target)
                    .withProperty(PROP_MOD_ID,  modId)
                    .withProperty(PROP_TARGET,  target)
                    .withProperty(PROP_VERSION, INTERMED_VERSION)
                    .withChildren(children);
            return SandboxSharedExecutionContext.SharedStateGraph.ofRoot(root);
        }
    }

    // ---- Reflective extraction helpers ------------------------------------

    private static String reflectPlayerName(Object player) {
        // Try getName() on the GameProfile object first (most reliable)
        Object profile = reflectValue(player, null, "getGameProfile");
        if (profile != null) {
            String name = reflectString(profile, "name", "getName");
            if (!UNKNOWN.equals(name) && !name.isEmpty()) {
                return name;
            }
        }
        return reflectString(player, "name", "getName", "getStringUUID");
    }

    private static String reflectGameMode(Object player) {
        // gameMode field may be an enum or wrapped in a GameType
        Object gameModeField = reflectValue(player, "gameMode", "getGameModeForPlayer");
        if (gameModeField != null) {
            // Forge/Fabric: GameType enum with getName()
            String name = reflectString(gameModeField, "name", "getName", "getSerializedName");
            if (!UNKNOWN.equals(name)) {
                return name;
            }
            return gameModeField.toString();
        }
        return UNKNOWN;
    }

    private static String reflectDimensionId(Object levelOrPlayer) {
        // Try level().dimension().location().toString()
        try {
            Method getLevel = tryMethod(levelOrPlayer.getClass(), "level", "getLevel");
            Object level = getLevel != null ? getLevel.invoke(levelOrPlayer) : levelOrPlayer;
            if (level != null) {
                Method getDimension = tryMethod(level.getClass(), "dimension", "getDimension");
                if (getDimension != null) {
                    Object dimension = getDimension.invoke(level);
                    if (dimension != null) {
                        Method location = tryMethod(dimension.getClass(), "location");
                        if (location != null) {
                            Object loc = location.invoke(dimension);
                            if (loc != null) {
                                return loc.toString();
                            }
                        }
                        return dimension.toString();
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflective access failed; fall through to simpler approaches.
        }
        return UNKNOWN;
    }

    private static String reflectBlockId(Object blockState) {
        // BlockState.getBlock().getRegistryName() (Forge) or
        // BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString() (Fabric)
        try {
            Method getBlock = tryMethod(blockState.getClass(), "getBlock");
            if (getBlock != null) {
                Object block = getBlock.invoke(blockState);
                if (block != null) {
                    // Forge: block.getRegistryName()
                    Method getRegistryName = tryMethod(block.getClass(), "getRegistryName");
                    if (getRegistryName != null) {
                        Object rn = getRegistryName.invoke(block);
                        if (rn != null) return rn.toString();
                    }
                    // Fallback: toString on the block object
                    return block.toString();
                }
            }
        } catch (Exception ignored) {}
        return UNKNOWN;
    }

    private static String reflectEntityType(Object entity) {
        try {
            Method getType = tryMethod(entity.getClass(), "getType");
            if (getType != null) {
                Object type = getType.invoke(entity);
                if (type != null) {
                    Method location = tryMethod(type.getClass(), "getRegistryName", "location");
                    if (location != null) {
                        Object loc = location.invoke(type);
                        if (loc != null) return loc.toString();
                    }
                    return type.toString();
                }
            }
        } catch (Exception ignored) {}
        return UNKNOWN;
    }

    private static String reflectItemId(Object itemStack) {
        try {
            Method getItem = tryMethod(itemStack.getClass(), "getItem");
            if (getItem != null) {
                Object item = getItem.invoke(itemStack);
                if (item != null) {
                    Method registryName = tryMethod(item.getClass(), "getRegistryName", "builtInRegistryHolder");
                    if (registryName != null) {
                        Object rn = registryName.invoke(item);
                        if (rn != null) return rn.toString();
                    }
                    return item.toString();
                }
            }
        } catch (Exception ignored) {}
        return UNKNOWN;
    }

    private static String reflectString(Object target, String... fieldOrMethodNames) {
        if (target == null) {
            return UNKNOWN;
        }
        for (String name : fieldOrMethodNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            // Try as no-arg method first
            try {
                Method m = tryMethod(target.getClass(), name);
                if (m != null) {
                    Object value = m.invoke(target);
                    if (value != null) {
                        String str = value.toString();
                        if (!str.isBlank()) {
                            return str;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Try as field
            try {
                Field f = tryField(target.getClass(), name);
                if (f != null) {
                    f.setAccessible(true);
                    Object value = f.get(target);
                    if (value != null) {
                        String str = value.toString();
                        if (!str.isBlank()) {
                            return str;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return UNKNOWN;
    }

    private static double reflectDouble(Object target, String... fieldOrMethodNames) {
        if (target == null) {
            return 0.0d;
        }
        for (String name : fieldOrMethodNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Method m = tryMethod(target.getClass(), name);
                if (m != null && (m.getReturnType() == double.class || m.getReturnType() == float.class)) {
                    Object value = m.invoke(target);
                    if (value instanceof Number n) {
                        return n.doubleValue();
                    }
                }
            } catch (Exception ignored) {}

            try {
                Field f = tryField(target.getClass(), name);
                if (f != null && (f.getType() == double.class || f.getType() == float.class)) {
                    f.setAccessible(true);
                    return f.getDouble(target);
                }
            } catch (Exception ignored) {}
        }
        return 0.0d;
    }

    private static long reflectLong(Object target, String... fieldOrMethodNames) {
        if (target == null) {
            return 0L;
        }
        for (String name : fieldOrMethodNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Method m = tryMethod(target.getClass(), name);
                if (m != null && (m.getReturnType() == long.class || m.getReturnType() == int.class)) {
                    Object value = m.invoke(target);
                    if (value instanceof Number n) {
                        return n.longValue();
                    }
                }
            } catch (Exception ignored) {}

            try {
                Field f = tryField(target.getClass(), name);
                if (f != null && (f.getType() == long.class || f.getType() == int.class)) {
                    f.setAccessible(true);
                    return f.getLong(target);
                }
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static int reflectInt(Object target, String... fieldOrMethodNames) {
        return (int) reflectLong(target, fieldOrMethodNames);
    }

    private static boolean reflectBoolean(Object target, String... fieldOrMethodNames) {
        if (target == null) {
            return false;
        }
        for (String name : fieldOrMethodNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Method m = tryMethod(target.getClass(), name);
                if (m != null && m.getReturnType() == boolean.class) {
                    Object value = m.invoke(target);
                    if (value instanceof Boolean b) {
                        return b;
                    }
                }
            } catch (Exception ignored) {}

            try {
                Field f = tryField(target.getClass(), name);
                if (f != null && f.getType() == boolean.class) {
                    f.setAccessible(true);
                    return f.getBoolean(target);
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static Object reflectValue(Object target, String fieldName, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String name : methodNames) {
            try {
                Method m = tryMethod(target.getClass(), name);
                if (m != null) {
                    return m.invoke(target);
                }
            } catch (Exception ignored) {}
        }
        if (fieldName != null) {
            try {
                Field f = tryField(target.getClass(), fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    return f.get(target);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Method tryMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            Class<?> cursor = clazz;
            while (cursor != null && cursor != Object.class) {
                try {
                    Method m = cursor.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
        }
        return null;
    }

    private static Field tryField(Class<?> clazz, String name) {
        Class<?> cursor = clazz;
        while (cursor != null && cursor != Object.class) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }
}
