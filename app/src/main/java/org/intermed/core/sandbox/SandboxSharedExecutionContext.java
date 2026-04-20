package org.intermed.core.sandbox;

import org.intermed.core.config.RuntimeConfig;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Shared off-heap execution context for sandbox invocations.
 *
 * <p>The host writes a compact invocation frame into off-heap memory once, then
 * exposes lightweight accessors to Espresso/Wasm guests via {@code InterMedAPI}
 * instead of repeatedly constructing deep serialized graphs.
 *
 * <p>When the runtime exposes the Foreign Function & Memory API we allocate
 * shared segments reflectively; otherwise we fall back to pooled direct byte
 * buffers while keeping the same layout and lifecycle semantics.
 */
public final class SandboxSharedExecutionContext {

    private static final int FORMAT_VERSION = 1;
    private static final int FLAG_HOT_PATH = 1;
    private static final int FLAG_RISKY = 1 << 1;
    private static final int FLAG_FALLBACK_APPLIED = 1 << 2;

    private static final int VERSION_OFFSET = 0;
    private static final int REQUESTED_MODE_ID_OFFSET = VERSION_OFFSET + Integer.BYTES;
    private static final int EFFECTIVE_MODE_ID_OFFSET = REQUESTED_MODE_ID_OFFSET + Integer.BYTES;
    private static final int FLAGS_OFFSET = EFFECTIVE_MODE_ID_OFFSET + Integer.BYTES;
    private static final int MOD_ID_OFFSET_FIELD = FLAGS_OFFSET + Integer.BYTES;
    private static final int MOD_ID_LENGTH_FIELD = MOD_ID_OFFSET_FIELD + Integer.BYTES;
    private static final int KEY_OFFSET_FIELD = MOD_ID_LENGTH_FIELD + Integer.BYTES;
    private static final int KEY_LENGTH_FIELD = KEY_OFFSET_FIELD + Integer.BYTES;
    private static final int TARGET_OFFSET_FIELD = KEY_LENGTH_FIELD + Integer.BYTES;
    private static final int TARGET_LENGTH_FIELD = TARGET_OFFSET_FIELD + Integer.BYTES;
    private static final int PLAN_REASON_OFFSET_FIELD = TARGET_LENGTH_FIELD + Integer.BYTES;
    private static final int PLAN_REASON_LENGTH_FIELD = PLAN_REASON_OFFSET_FIELD + Integer.BYTES;
    private static final int GRAPH_NODE_COUNT_FIELD = PLAN_REASON_LENGTH_FIELD + Integer.BYTES;
    private static final int GRAPH_PROPERTY_COUNT_FIELD = GRAPH_NODE_COUNT_FIELD + Integer.BYTES;
    private static final int GRAPH_NODE_TABLE_OFFSET_FIELD = GRAPH_PROPERTY_COUNT_FIELD + Integer.BYTES;
    private static final int GRAPH_PROPERTY_TABLE_OFFSET_FIELD = GRAPH_NODE_TABLE_OFFSET_FIELD + Integer.BYTES;
    private static final int GRAPH_STRING_TABLE_OFFSET_FIELD = GRAPH_PROPERTY_TABLE_OFFSET_FIELD + Integer.BYTES;
    private static final int GRAPH_STRING_TABLE_LENGTH_FIELD = GRAPH_STRING_TABLE_OFFSET_FIELD + Integer.BYTES;
    private static final int HEADER_BYTES = GRAPH_STRING_TABLE_LENGTH_FIELD + Integer.BYTES;

    private static final int NODE_PARENT_INDEX_OFFSET = 0;
    private static final int NODE_TYPE_OFFSET_FIELD = NODE_PARENT_INDEX_OFFSET + Integer.BYTES;
    private static final int NODE_TYPE_LENGTH_FIELD = NODE_TYPE_OFFSET_FIELD + Integer.BYTES;
    private static final int NODE_NAME_OFFSET_FIELD = NODE_TYPE_LENGTH_FIELD + Integer.BYTES;
    private static final int NODE_NAME_LENGTH_FIELD = NODE_NAME_OFFSET_FIELD + Integer.BYTES;
    private static final int NODE_FIRST_CHILD_INDEX_FIELD = NODE_NAME_LENGTH_FIELD + Integer.BYTES;
    private static final int NODE_CHILD_COUNT_FIELD = NODE_FIRST_CHILD_INDEX_FIELD + Integer.BYTES;
    private static final int NODE_FIRST_PROPERTY_INDEX_FIELD = NODE_CHILD_COUNT_FIELD + Integer.BYTES;
    private static final int NODE_PROPERTY_COUNT_FIELD = NODE_FIRST_PROPERTY_INDEX_FIELD + Integer.BYTES;
    private static final int NODE_FLAGS_FIELD = NODE_PROPERTY_COUNT_FIELD + Integer.BYTES;
    private static final int NODE_POSITION_X_FIELD = NODE_FLAGS_FIELD + Integer.BYTES;
    private static final int NODE_POSITION_Y_FIELD = NODE_POSITION_X_FIELD + Double.BYTES;
    private static final int NODE_POSITION_Z_FIELD = NODE_POSITION_Y_FIELD + Double.BYTES;
    private static final int NODE_RECORD_BYTES = NODE_POSITION_Z_FIELD + Double.BYTES;

    private static final int PROPERTY_NODE_INDEX_FIELD = 0;
    private static final int PROPERTY_KEY_OFFSET_FIELD = PROPERTY_NODE_INDEX_FIELD + Integer.BYTES;
    private static final int PROPERTY_KEY_LENGTH_FIELD = PROPERTY_KEY_OFFSET_FIELD + Integer.BYTES;
    private static final int PROPERTY_VALUE_OFFSET_FIELD = PROPERTY_KEY_LENGTH_FIELD + Integer.BYTES;
    private static final int PROPERTY_VALUE_LENGTH_FIELD = PROPERTY_VALUE_OFFSET_FIELD + Integer.BYTES;
    private static final int PROPERTY_RECORD_BYTES = PROPERTY_VALUE_LENGTH_FIELD + Integer.BYTES;

    private static final ThreadLocal<ExecutionFrame> CURRENT = new ThreadLocal<>();
    private static final ConcurrentLinkedQueue<SharedRegion> POOLED_REGIONS = new ConcurrentLinkedQueue<>();
    private static final TransportFactory TRANSPORT_FACTORY = TransportFactory.detect();

    private SandboxSharedExecutionContext() {}

    public static ExecutionFrame open(String modId,
                                      String key,
                                      String target,
                                      SandboxMode requestedMode,
                                      SandboxMode effectiveMode,
                                      String planReason,
                                      boolean hotPath,
                                      boolean risky,
                                      boolean fallbackApplied) {
        return open(
            modId,
            key,
            target,
            requestedMode,
            effectiveMode,
            planReason,
            hotPath,
            risky,
            fallbackApplied,
            SharedStateGraph.empty()
        );
    }

    public static ExecutionFrame open(String modId,
                                      String key,
                                      String target,
                                      SandboxMode requestedMode,
                                      SandboxMode effectiveMode,
                                      String planReason,
                                      boolean hotPath,
                                      boolean risky,
                                      boolean fallbackApplied,
                                      SharedStateGraph sharedState) {
        byte[] modIdBytes = utf8(sanitize(modId, "unknown"));
        byte[] keyBytes = utf8(sanitize(key, "main"));
        byte[] targetBytes = utf8(sanitize(target, ""));
        byte[] reasonBytes = utf8(sanitize(planReason, ""));
        SerializedGraph graph = SerializedGraph.of(sharedState);

        int stringAreaOffset = HEADER_BYTES
            + graph.nodeCount() * NODE_RECORD_BYTES
            + graph.propertyCount() * PROPERTY_RECORD_BYTES;
        int payloadBytes = modIdBytes.length
            + keyBytes.length
            + targetBytes.length
            + reasonBytes.length
            + graph.totalStringBytes();
        int requiredBytes = stringAreaOffset + payloadBytes;
        SharedRegion region = acquireRegion(requiredBytes);
        ByteBuffer buffer = region.buffer().duplicate();
        buffer.clear();

        int cursor = stringAreaOffset;
        cursor = writeField(buffer, MOD_ID_OFFSET_FIELD, MOD_ID_LENGTH_FIELD, modIdBytes, cursor);
        cursor = writeField(buffer, KEY_OFFSET_FIELD, KEY_LENGTH_FIELD, keyBytes, cursor);
        cursor = writeField(buffer, TARGET_OFFSET_FIELD, TARGET_LENGTH_FIELD, targetBytes, cursor);
        cursor = writeField(buffer, PLAN_REASON_OFFSET_FIELD, PLAN_REASON_LENGTH_FIELD, reasonBytes, cursor);
        int graphStringTableOffset = cursor;
        cursor = writeGraph(buffer, graph, cursor);

        buffer.putInt(VERSION_OFFSET, FORMAT_VERSION);
        buffer.putInt(REQUESTED_MODE_ID_OFFSET, toModeId(requestedMode));
        buffer.putInt(EFFECTIVE_MODE_ID_OFFSET, toModeId(effectiveMode));
        buffer.putInt(FLAGS_OFFSET, encodeFlags(hotPath, risky, fallbackApplied));
        buffer.putInt(GRAPH_NODE_COUNT_FIELD, graph.nodeCount());
        buffer.putInt(GRAPH_PROPERTY_COUNT_FIELD, graph.propertyCount());
        buffer.putInt(GRAPH_NODE_TABLE_OFFSET_FIELD, HEADER_BYTES);
        buffer.putInt(GRAPH_PROPERTY_TABLE_OFFSET_FIELD, HEADER_BYTES + graph.nodeCount() * NODE_RECORD_BYTES);
        buffer.putInt(GRAPH_STRING_TABLE_OFFSET_FIELD, graphStringTableOffset);
        buffer.putInt(GRAPH_STRING_TABLE_LENGTH_FIELD, cursor - graphStringTableOffset);
        return new ExecutionFrame(region, cursor);
    }

    public static <T> T bind(ExecutionFrame frame, Supplier<T> action) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(action, "action");
        ExecutionFrame previous = CURRENT.get();
        CURRENT.set(frame);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static String currentModId() {
        return currentString(MOD_ID_OFFSET_FIELD, MOD_ID_LENGTH_FIELD, "unknown");
    }

    public static String currentInvocationKey() {
        return currentString(KEY_OFFSET_FIELD, KEY_LENGTH_FIELD, "main");
    }

    public static int currentInvocationKeyLength() {
        return currentLength(KEY_LENGTH_FIELD);
    }

    public static String currentTarget() {
        return currentString(TARGET_OFFSET_FIELD, TARGET_LENGTH_FIELD, "");
    }

    public static int currentTargetLength() {
        return currentLength(TARGET_LENGTH_FIELD);
    }

    public static String currentPlanReason() {
        return currentString(PLAN_REASON_OFFSET_FIELD, PLAN_REASON_LENGTH_FIELD, "");
    }

    public static int currentRequestedModeId() {
        return currentInt(REQUESTED_MODE_ID_OFFSET, 0);
    }

    public static int currentEffectiveModeId() {
        return currentInt(EFFECTIVE_MODE_ID_OFFSET, 0);
    }

    public static boolean isCurrentHotPath() {
        return (currentInt(FLAGS_OFFSET, 0) & FLAG_HOT_PATH) != 0;
    }

    public static boolean isCurrentRisky() {
        return (currentInt(FLAGS_OFFSET, 0) & FLAG_RISKY) != 0;
    }

    public static boolean isCurrentFallbackApplied() {
        return (currentInt(FLAGS_OFFSET, 0) & FLAG_FALLBACK_APPLIED) != 0;
    }

    public static int currentSharedStateBytes() {
        ExecutionFrame frame = CURRENT.get();
        return frame == null ? 0 : frame.bytesUsed();
    }

    public static int currentSharedGraphNodeCount() {
        return currentInt(GRAPH_NODE_COUNT_FIELD, 0);
    }

    public static int currentSharedGraphPropertyCount() {
        return currentInt(GRAPH_PROPERTY_COUNT_FIELD, 0);
    }

    public static String currentSharedGraphRootType() {
        return currentSharedGraphNodeType(0);
    }

    public static String currentSharedGraphRootName() {
        return currentSharedGraphNodeName(0);
    }

    public static String currentSharedGraphNodeType(int nodeIndex) {
        return readGraphNodeString(nodeIndex, NODE_TYPE_OFFSET_FIELD, NODE_TYPE_LENGTH_FIELD);
    }

    public static String currentSharedGraphNodeName(int nodeIndex) {
        return readGraphNodeString(nodeIndex, NODE_NAME_OFFSET_FIELD, NODE_NAME_LENGTH_FIELD);
    }

    public static int currentSharedGraphNodeParentIndex(int nodeIndex) {
        return readGraphNodeInt(nodeIndex, NODE_PARENT_INDEX_OFFSET, -1);
    }

    public static int currentSharedGraphNodeFirstChildIndex(int nodeIndex) {
        return readGraphNodeInt(nodeIndex, NODE_FIRST_CHILD_INDEX_FIELD, -1);
    }

    public static int currentSharedGraphNodeChildCount(int nodeIndex) {
        return readGraphNodeInt(nodeIndex, NODE_CHILD_COUNT_FIELD, 0);
    }

    public static int currentSharedGraphNodeFlags(int nodeIndex) {
        return readGraphNodeInt(nodeIndex, NODE_FLAGS_FIELD, 0);
    }

    public static double currentSharedGraphNodeX(int nodeIndex) {
        return readGraphNodeDouble(nodeIndex, NODE_POSITION_X_FIELD, 0.0d);
    }

    public static double currentSharedGraphNodeY(int nodeIndex) {
        return readGraphNodeDouble(nodeIndex, NODE_POSITION_Y_FIELD, 0.0d);
    }

    public static double currentSharedGraphNodeZ(int nodeIndex) {
        return readGraphNodeDouble(nodeIndex, NODE_POSITION_Z_FIELD, 0.0d);
    }

    public static String currentSharedGraphNodeProperty(int nodeIndex, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        ExecutionFrame frame = CURRENT.get();
        if (frame == null || !hasGraphNode(nodeIndex)) {
            return "";
        }
        int firstPropertyIndex = readGraphNodeInt(nodeIndex, NODE_FIRST_PROPERTY_INDEX_FIELD, -1);
        int propertyCount = readGraphNodeInt(nodeIndex, NODE_PROPERTY_COUNT_FIELD, 0);
        if (firstPropertyIndex < 0 || propertyCount <= 0) {
            return "";
        }
        String normalizedKey = key.trim();
        for (int i = 0; i < propertyCount; i++) {
            int propertyIndex = firstPropertyIndex + i;
            String candidateKey = readGraphPropertyString(frame, propertyIndex, PROPERTY_KEY_OFFSET_FIELD, PROPERTY_KEY_LENGTH_FIELD);
            if (normalizedKey.equals(candidateKey)) {
                return readGraphPropertyString(frame, propertyIndex, PROPERTY_VALUE_OFFSET_FIELD, PROPERTY_VALUE_LENGTH_FIELD);
            }
        }
        return "";
    }

    public static String currentTransportKind() {
        ExecutionFrame frame = CURRENT.get();
        return frame == null ? "none" : frame.transportKind();
    }

    public static void invalidatePool() {
        CURRENT.remove();
        SharedRegion region;
        while ((region = POOLED_REGIONS.poll()) != null) {
            closeQuietly(region);
        }
    }

    static void resetForTests() {
        invalidatePool();
    }

    private static SharedRegion acquireRegion(int requiredBytes) {
        int pooledCapacity = RuntimeConfig.get().getSandboxSharedRegionBytes();
        if (requiredBytes <= pooledCapacity) {
            SharedRegion pooled = POOLED_REGIONS.poll();
            if (pooled != null) {
                return pooled;
            }
            return TRANSPORT_FACTORY.allocate(pooledCapacity);
        }
        return TRANSPORT_FACTORY.allocate(requiredBytes);
    }

    private static void releaseRegion(SharedRegion region, int bytesUsed) {
        if (region == null) {
            return;
        }
        wipe(region.buffer(), Math.min(region.capacity(), Math.max(bytesUsed, 0)));
        int pooledCapacity = RuntimeConfig.get().getSandboxSharedRegionBytes();
        int maxPoolSize = RuntimeConfig.get().getSandboxSharedRegionPoolMax();
        if (region.capacity() == pooledCapacity && POOLED_REGIONS.size() < maxPoolSize) {
            POOLED_REGIONS.offer(region);
            return;
        }
        closeQuietly(region);
    }

    private static String currentString(int offsetField, int lengthField, String fallback) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null) {
            return fallback;
        }
        int offset = frame.buffer().getInt(offsetField);
        int length = frame.buffer().getInt(lengthField);
        return readString(frame.buffer(), offset, length, fallback);
    }

    private static int currentLength(int lengthField) {
        ExecutionFrame frame = CURRENT.get();
        return frame == null ? 0 : frame.buffer().getInt(lengthField);
    }

    private static int currentInt(int offset, int fallback) {
        ExecutionFrame frame = CURRENT.get();
        return frame == null ? fallback : frame.buffer().getInt(offset);
    }

    private static boolean hasGraphNode(int nodeIndex) {
        return nodeIndex >= 0 && nodeIndex < currentSharedGraphNodeCount();
    }

    private static String readGraphNodeString(int nodeIndex, int offsetField, int lengthField) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null || !hasGraphNode(nodeIndex)) {
            return "";
        }
        int nodeBase = graphNodeOffset(nodeIndex);
        ByteBuffer buffer = frame.buffer();
        int offset = buffer.getInt(nodeBase + offsetField);
        int length = buffer.getInt(nodeBase + lengthField);
        return readString(buffer, offset, length, "");
    }

    private static int readGraphNodeInt(int nodeIndex, int fieldOffset, int fallback) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null || !hasGraphNode(nodeIndex)) {
            return fallback;
        }
        return frame.buffer().getInt(graphNodeOffset(nodeIndex) + fieldOffset);
    }

    private static double readGraphNodeDouble(int nodeIndex, int fieldOffset, double fallback) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null || !hasGraphNode(nodeIndex)) {
            return fallback;
        }
        return frame.buffer().getDouble(graphNodeOffset(nodeIndex) + fieldOffset);
    }

    private static String readGraphPropertyString(ExecutionFrame frame,
                                                  int propertyIndex,
                                                  int offsetField,
                                                  int lengthField) {
        if (frame == null || propertyIndex < 0 || propertyIndex >= currentSharedGraphPropertyCount()) {
            return "";
        }
        int propertyBase = graphPropertyOffset(propertyIndex);
        ByteBuffer buffer = frame.buffer();
        int offset = buffer.getInt(propertyBase + offsetField);
        int length = buffer.getInt(propertyBase + lengthField);
        return readString(buffer, offset, length, "");
    }

    private static int graphNodeOffset(int nodeIndex) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null) {
            return -1;
        }
        return frame.buffer().getInt(GRAPH_NODE_TABLE_OFFSET_FIELD) + nodeIndex * NODE_RECORD_BYTES;
    }

    private static int graphPropertyOffset(int propertyIndex) {
        ExecutionFrame frame = CURRENT.get();
        if (frame == null) {
            return -1;
        }
        return frame.buffer().getInt(GRAPH_PROPERTY_TABLE_OFFSET_FIELD) + propertyIndex * PROPERTY_RECORD_BYTES;
    }

    private static String readString(ByteBuffer source, int offset, int length, String fallback) {
        if (source == null || length <= 0 || offset < 0 || offset + length > source.capacity()) {
            return fallback;
        }
        byte[] bytes = new byte[length];
        ByteBuffer copy = source.duplicate();
        copy.position(offset);
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int encodeFlags(boolean hotPath, boolean risky, boolean fallbackApplied) {
        int flags = 0;
        if (hotPath) {
            flags |= FLAG_HOT_PATH;
        }
        if (risky) {
            flags |= FLAG_RISKY;
        }
        if (fallbackApplied) {
            flags |= FLAG_FALLBACK_APPLIED;
        }
        return flags;
    }

    private static int writeField(ByteBuffer buffer,
                                  int offsetField,
                                  int lengthField,
                                  byte[] bytes,
                                  int cursor) {
        buffer.putInt(offsetField, cursor);
        buffer.putInt(lengthField, bytes.length);
        buffer.position(cursor);
        buffer.put(bytes);
        return cursor + bytes.length;
    }

    private static int writeGraph(ByteBuffer buffer, SerializedGraph graph, int stringCursor) {
        GraphStringWriter stringWriter = new GraphStringWriter(buffer, stringCursor);
        int nodeTableOffset = HEADER_BYTES;
        int propertyTableOffset = nodeTableOffset + graph.nodeCount() * NODE_RECORD_BYTES;

        for (int i = 0; i < graph.nodes().size(); i++) {
            FlatNode node = graph.nodes().get(i);
            int base = nodeTableOffset + i * NODE_RECORD_BYTES;
            writeStringField(buffer, base + NODE_TYPE_OFFSET_FIELD, base + NODE_TYPE_LENGTH_FIELD,
                node.type(), stringWriter);
            writeStringField(buffer, base + NODE_NAME_OFFSET_FIELD, base + NODE_NAME_LENGTH_FIELD,
                node.name(), stringWriter);
            buffer.putInt(base + NODE_PARENT_INDEX_OFFSET, node.parentIndex());
            buffer.putInt(base + NODE_FIRST_CHILD_INDEX_FIELD, node.firstChildIndex());
            buffer.putInt(base + NODE_CHILD_COUNT_FIELD, node.childCount());
            buffer.putInt(base + NODE_FIRST_PROPERTY_INDEX_FIELD, node.firstPropertyIndex());
            buffer.putInt(base + NODE_PROPERTY_COUNT_FIELD, node.propertyCount());
            buffer.putInt(base + NODE_FLAGS_FIELD, node.flags());
            buffer.putDouble(base + NODE_POSITION_X_FIELD, node.x());
            buffer.putDouble(base + NODE_POSITION_Y_FIELD, node.y());
            buffer.putDouble(base + NODE_POSITION_Z_FIELD, node.z());
        }

        for (int i = 0; i < graph.properties().size(); i++) {
            FlatProperty property = graph.properties().get(i);
            int base = propertyTableOffset + i * PROPERTY_RECORD_BYTES;
            buffer.putInt(base + PROPERTY_NODE_INDEX_FIELD, property.nodeIndex());
            writeStringField(buffer, base + PROPERTY_KEY_OFFSET_FIELD, base + PROPERTY_KEY_LENGTH_FIELD,
                property.key(), stringWriter);
            writeStringField(buffer, base + PROPERTY_VALUE_OFFSET_FIELD, base + PROPERTY_VALUE_LENGTH_FIELD,
                property.value(), stringWriter);
        }

        return stringWriter.cursor();
    }

    private static void writeStringField(ByteBuffer buffer,
                                         int offsetField,
                                         int lengthField,
                                         String value,
                                         GraphStringWriter stringWriter) {
        StringRef ref = stringWriter.write(value);
        buffer.putInt(offsetField, ref.offset());
        buffer.putInt(lengthField, ref.length());
    }

    private static void wipe(ByteBuffer source, int bytes) {
        ByteBuffer buffer = source.duplicate();
        buffer.clear();
        buffer.limit(Math.max(0, Math.min(bytes, buffer.capacity())));
        while (buffer.remaining() >= Long.BYTES) {
            buffer.putLong(0L);
        }
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int toModeId(SandboxMode mode) {
        if (mode == SandboxMode.ESPRESSO) {
            return 1;
        }
        if (mode == SandboxMode.WASM) {
            return 2;
        }
        return 0;
    }

    private static void closeQuietly(SharedRegion region) {
        try {
            region.close();
        } catch (Exception ignored) {
        }
    }

    public static final class ExecutionFrame implements AutoCloseable {

        private final SharedRegion region;
        private final ByteBuffer buffer;
        private final int bytesUsed;
        private boolean closed;

        private ExecutionFrame(SharedRegion region, int bytesUsed) {
            this.region = region;
            ByteBuffer source = region.buffer().duplicate();
            source.clear();
            this.buffer = source.asReadOnlyBuffer();
            this.bytesUsed = bytesUsed;
        }

        public String transportKind() {
            return region.transportKind();
        }

        public int bytesUsed() {
            return bytesUsed;
        }

        private ByteBuffer buffer() {
            return buffer.duplicate();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseRegion(region, bytesUsed);
        }
    }

    public record SharedStateGraph(SharedStateNode root) {
        public static SharedStateGraph empty() {
            return new SharedStateGraph(null);
        }

        public boolean isEmpty() {
            return root == null;
        }

        public static SharedStateGraph ofRoot(SharedStateNode root) {
            return new SharedStateGraph(root);
        }
    }

    public record SharedStateNode(String type,
                                  String name,
                                  Map<String, String> properties,
                                  List<SharedStateNode> children,
                                  int flags,
                                  double x,
                                  double y,
                                  double z) {

        public SharedStateNode {
            type = sanitize(type, "node");
            name = sanitize(name, "");
            LinkedHashMap<String, String> normalizedProperties = new LinkedHashMap<>();
            if (properties != null) {
                properties.forEach((key, value) ->
                    normalizedProperties.put(sanitize(key, "key"), sanitize(value, "")));
            }
            ArrayList<SharedStateNode> normalizedChildren = new ArrayList<>();
            if (children != null) {
                for (SharedStateNode child : children) {
                    if (child != null) {
                        normalizedChildren.add(child);
                    }
                }
            }
            properties = Map.copyOf(normalizedProperties);
            children = List.copyOf(normalizedChildren);
        }

        public static SharedStateNode of(String type, String name) {
            return new SharedStateNode(type, name, Map.of(), List.of(), 0, 0.0d, 0.0d, 0.0d);
        }

        public SharedStateNode withProperty(String key, String value) {
            LinkedHashMap<String, String> next = new LinkedHashMap<>(properties);
            next.put(sanitize(key, "key"), sanitize(value, ""));
            return new SharedStateNode(type, name, next, children, flags, x, y, z);
        }

        public SharedStateNode withProperties(Map<String, String> additional) {
            if (additional == null || additional.isEmpty()) {
                return this;
            }
            LinkedHashMap<String, String> next = new LinkedHashMap<>(properties);
            additional.forEach((key, value) -> next.put(sanitize(key, "key"), sanitize(value, "")));
            return new SharedStateNode(type, name, next, children, flags, x, y, z);
        }

        public SharedStateNode withChild(SharedStateNode child) {
            if (child == null) {
                return this;
            }
            ArrayList<SharedStateNode> next = new ArrayList<>(children);
            next.add(child);
            return new SharedStateNode(type, name, properties, next, flags, x, y, z);
        }

        public SharedStateNode withChildren(List<SharedStateNode> additional) {
            if (additional == null || additional.isEmpty()) {
                return this;
            }
            ArrayList<SharedStateNode> next = new ArrayList<>(children);
            for (SharedStateNode child : additional) {
                if (child != null) {
                    next.add(child);
                }
            }
            return new SharedStateNode(type, name, properties, next, flags, x, y, z);
        }

        public SharedStateNode withFlags(int updatedFlags) {
            return new SharedStateNode(type, name, properties, children, updatedFlags, x, y, z);
        }

        public SharedStateNode withPosition(double updatedX, double updatedY, double updatedZ) {
            return new SharedStateNode(type, name, properties, children, flags, updatedX, updatedY, updatedZ);
        }
    }

    private record FlatNode(String type,
                            String name,
                            int parentIndex,
                            int firstChildIndex,
                            int childCount,
                            int firstPropertyIndex,
                            int propertyCount,
                            int flags,
                            double x,
                            double y,
                            double z) {
    }

    private record FlatProperty(int nodeIndex, String key, String value) {
    }

    private record StringRef(int offset, int length) {
    }

    private static final class GraphStringWriter {

        private final ByteBuffer buffer;
        private final Map<String, StringRef> cache = new LinkedHashMap<>();
        private int cursor;

        private GraphStringWriter(ByteBuffer buffer, int startOffset) {
            this.buffer = buffer;
            this.cursor = startOffset;
        }

        private StringRef write(String rawValue) {
            String value = sanitize(rawValue, "");
            StringRef cached = cache.get(value);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = utf8(value);
            int offset = cursor;
            buffer.position(offset);
            buffer.put(bytes);
            cursor += bytes.length;
            StringRef ref = new StringRef(offset, bytes.length);
            cache.put(value, ref);
            return ref;
        }

        private int cursor() {
            return cursor;
        }
    }

    private record SerializedGraph(List<FlatNode> nodes,
                                   List<FlatProperty> properties,
                                   int totalStringBytes) {

        private SerializedGraph {
            nodes = List.copyOf(nodes);
            properties = List.copyOf(properties);
        }

        private static SerializedGraph of(SharedStateGraph graph) {
            if (graph == null || graph.isEmpty()) {
                return new SerializedGraph(List.of(), List.of(), 0);
            }

            ArrayList<FlatNode> nodes = new ArrayList<>();
            ArrayList<FlatProperty> properties = new ArrayList<>();
            LinkedHashMap<String, Integer> uniqueStringBytes = new LinkedHashMap<>();

            ArrayDeque<PendingNode> queue = new ArrayDeque<>();
            queue.add(new PendingNode(graph.root(), -1));

            while (!queue.isEmpty()) {
                PendingNode pending = queue.removeFirst();
                SharedStateNode node = pending.node();
                if (node == null) {
                    continue;
                }

                int nodeIndex = nodes.size();
                List<SharedStateNode> children = node.children();
                int firstChildIndex = children.isEmpty() ? -1 : (queue.size() + nodes.size() + 1);

                int firstPropertyIndex = properties.size();
                if (!node.properties().isEmpty()) {
                    node.properties().forEach((key, value) -> properties.add(
                        new FlatProperty(nodeIndex, sanitize(key, "key"), sanitize(value, ""))
                    ));
                }

                nodes.add(new FlatNode(
                    sanitize(node.type(), "node"),
                    sanitize(node.name(), ""),
                    pending.parentIndex(),
                    firstChildIndex,
                    children.size(),
                    node.properties().isEmpty() ? -1 : firstPropertyIndex,
                    node.properties().size(),
                    node.flags(),
                    node.x(),
                    node.y(),
                    node.z()
                ));

                registerString(uniqueStringBytes, node.type());
                registerString(uniqueStringBytes, node.name());
                node.properties().forEach((key, value) -> {
                    registerString(uniqueStringBytes, key);
                    registerString(uniqueStringBytes, value);
                });

                for (SharedStateNode child : children) {
                    queue.addLast(new PendingNode(child, nodeIndex));
                }
            }

            int totalStringBytes = uniqueStringBytes.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
            return new SerializedGraph(nodes, properties, totalStringBytes);
        }

        private int nodeCount() {
            return nodes.size();
        }

        private int propertyCount() {
            return properties.size();
        }

        private static void registerString(Map<String, Integer> uniqueStringBytes, String rawValue) {
            String value = sanitize(rawValue, "");
            uniqueStringBytes.putIfAbsent(value, utf8(value).length);
        }
    }

    private record PendingNode(SharedStateNode node, int parentIndex) {
    }

    private interface SharedRegion extends AutoCloseable {
        ByteBuffer buffer();
        int capacity();
        String transportKind();
    }

    private interface TransportFactory {
        SharedRegion allocate(int capacity);

        static TransportFactory detect() {
            try {
                Class<?> arenaClass = Class.forName("java.lang.foreign.Arena");
                Class<?> segmentClass = Class.forName("java.lang.foreign.MemorySegment");
                Method ofShared = arenaClass.getMethod("ofShared");
                Method allocate = arenaClass.getMethod("allocate", long.class, long.class);
                Method asByteBuffer = segmentClass.getMethod("asByteBuffer");
                Method close = arenaClass.getMethod("close");
                Object arena = ofShared.invoke(null);
                Object segment = allocate.invoke(arena, 16L, 8L);
                ByteBuffer probeBuffer = (ByteBuffer) asByteBuffer.invoke(segment);
                if (probeBuffer == null || probeBuffer.capacity() < 16) {
                    throw new IllegalStateException("ffm-probe-capacity");
                }
                close.invoke(arena);
                return new FfmTransportFactory(ofShared, allocate, asByteBuffer, close);
            } catch (Throwable ignored) {
                return DirectTransportFactory.INSTANCE;
            }
        }
    }

    private static final class DirectTransportFactory implements TransportFactory {
        private static final DirectTransportFactory INSTANCE = new DirectTransportFactory();

        @Override
        public SharedRegion allocate(int capacity) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            return new DirectSharedRegion(buffer);
        }
    }

    private static final class FfmTransportFactory implements TransportFactory {

        private final Method ofShared;
        private final Method allocate;
        private final Method asByteBuffer;
        private final Method close;

        private FfmTransportFactory(Method ofShared,
                                    Method allocate,
                                    Method asByteBuffer,
                                    Method close) {
            this.ofShared = ofShared;
            this.allocate = allocate;
            this.asByteBuffer = asByteBuffer;
            this.close = close;
        }

        @Override
        public SharedRegion allocate(int capacity) {
            try {
                Object arena = ofShared.invoke(null);
                Object segment = allocate.invoke(arena, (long) capacity, 8L);
                ByteBuffer buffer = (ByteBuffer) asByteBuffer.invoke(segment);
                buffer.clear();
                return new FfmSharedRegion(arena, buffer, close);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("ffm-shared-allocation-failed", e);
            }
        }
    }

    private static final class DirectSharedRegion implements SharedRegion {

        private final ByteBuffer buffer;

        private DirectSharedRegion(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public ByteBuffer buffer() {
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.clear();
            return duplicate;
        }

        @Override
        public int capacity() {
            return buffer.capacity();
        }

        @Override
        public String transportKind() {
            return "direct-buffer";
        }

        @Override
        public void close() {
        }
    }

    private static final class FfmSharedRegion implements SharedRegion {

        private final Object arena;
        private final ByteBuffer buffer;
        private final Method closeMethod;

        private FfmSharedRegion(Object arena, ByteBuffer buffer, Method closeMethod) {
            this.arena = arena;
            this.buffer = buffer;
            this.closeMethod = closeMethod;
        }

        @Override
        public ByteBuffer buffer() {
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.clear();
            return duplicate;
        }

        @Override
        public int capacity() {
            return buffer.capacity();
        }

        @Override
        public String transportKind() {
            return "ffm-shared-memory";
        }

        @Override
        public void close() throws Exception {
            closeMethod.invoke(arena);
        }
    }
}
