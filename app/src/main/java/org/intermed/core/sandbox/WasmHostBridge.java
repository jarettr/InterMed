package org.intermed.core.sandbox;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lowers WIT-compatible {@link org.intermed.api.InterMedAPI} methods into raw
 * Chicory host imports for Wasm guests.
 */
final class WasmHostBridge {

    private final Map<Instance, Integer> stringHeapOffsets = new ConcurrentHashMap<>();

    ImportFunction[] hostFunctions() {
        List<WitContractCatalog.HostMethod> methods = WitContractCatalog.hostMethods();
        ImportFunction[] functions = new ImportFunction[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            functions[i] = toHostFunction(methods.get(i));
        }
        return functions;
    }

    void reset() {
        stringHeapOffsets.clear();
    }

    private ImportFunction toHostFunction(WitContractCatalog.HostMethod hostMethod) {
        Method method = hostMethod.method();
        return new HostFunction(
            WitContractCatalog.packageName(),
            hostMethod.name(),
            FunctionType.of(lowerParameters(method), lowerReturns(method)),
            (instance, args) -> invoke(method, instance, args)
        );
    }

    private long[] invoke(Method method, Instance instance, long[] rawArgs) {
        try {
            Object[] decodedArgs = decodeArguments(method, instance, rawArgs);
            Object result = method.invoke(null, decodedArgs);
            return encodeResult(method.getReturnType(), instance, result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("intermed-host-call-failed:" + method.getName(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("intermed-host-call-reflection-failed:" + method.getName(), e);
        }
    }

    private Object[] decodeArguments(Method method, Instance instance, long[] rawArgs) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] decoded = new Object[parameterTypes.length];
        int cursor = 0;
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            if (type == boolean.class || type == Boolean.class) {
                decoded[index] = rawI32(rawArgs, cursor++) != 0;
            } else if (type == int.class || type == Integer.class) {
                decoded[index] = rawI32(rawArgs, cursor++);
            } else if (type == long.class || type == Long.class) {
                decoded[index] = rawI64(rawArgs, cursor++);
            } else if (type == float.class || type == Float.class) {
                decoded[index] = Float.intBitsToFloat(rawI32(rawArgs, cursor++));
            } else if (type == double.class || type == Double.class) {
                decoded[index] = Double.longBitsToDouble(rawI64(rawArgs, cursor++));
            } else if (type == String.class) {
                int ptr = rawI32(rawArgs, cursor++);
                int len = rawI32(rawArgs, cursor++);
                decoded[index] = readGuestString(instance, ptr, len);
            } else {
                throw new IllegalStateException("unsupported-wasm-host-parameter:" + type.getName());
            }
        }
        return decoded;
    }

    private long[] encodeResult(Class<?> type, Instance instance, Object value) {
        if (type == Void.TYPE || type == Void.class) {
            return new long[0];
        }
        if (type == boolean.class || type == Boolean.class) {
            return new long[] { Boolean.TRUE.equals(value) ? 1L : 0L };
        }
        if (type == int.class || type == Integer.class) {
            return new long[] { value == null ? 0L : ((Number) value).intValue() };
        }
        if (type == long.class || type == Long.class) {
            return new long[] { value == null ? 0L : ((Number) value).longValue() };
        }
        if (type == float.class || type == Float.class) {
            float floatValue = value == null ? 0.0f : ((Number) value).floatValue();
            return new long[] { Integer.toUnsignedLong(Float.floatToRawIntBits(floatValue)) };
        }
        if (type == double.class || type == Double.class) {
            double doubleValue = value == null ? 0.0d : ((Number) value).doubleValue();
            return new long[] { Double.doubleToRawLongBits(doubleValue) };
        }
        if (type == String.class) {
            return allocateGuestString(instance, value == null ? "" : value.toString());
        }
        throw new IllegalStateException("unsupported-wasm-host-return:" + type.getName());
    }

    private List<ValType> lowerParameters(Method method) {
        List<ValType> lowered = new ArrayList<>();
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType == String.class) {
                lowered.add(ValType.I32);
                lowered.add(ValType.I32);
            } else {
                lowered.add(lowerScalar(parameterType));
            }
        }
        return lowered;
    }

    private List<ValType> lowerReturns(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE || returnType == Void.class) {
            return List.of();
        }
        if (returnType == String.class) {
            return List.of(ValType.I32, ValType.I32);
        }
        return List.of(lowerScalar(returnType));
    }

    private ValType lowerScalar(Class<?> type) {
        if (type == boolean.class || type == Boolean.class || type == int.class || type == Integer.class) {
            return ValType.I32;
        }
        if (type == long.class || type == Long.class) {
            return ValType.I64;
        }
        if (type == float.class || type == Float.class) {
            return ValType.F32;
        }
        if (type == double.class || type == Double.class) {
            return ValType.F64;
        }
        throw new IllegalStateException("unsupported-wasm-host-type:" + type.getName());
    }

    private String readGuestString(Instance instance, int pointer, int length) {
        if (length <= 0) {
            return "";
        }
        if (pointer < 0 || length < 0) {
            throw new IllegalStateException("guest-string-out-of-bounds");
        }
        Memory memory = requireGuestMemory(instance, "read-string");
        byte[] bytes = memory.readBytes(pointer, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private long[] allocateGuestString(Instance instance, String value) {
        if (value == null || value.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Memory memory = requireGuestMemory(instance, "write-string");
        int pointer = stringHeapOffsets.compute(instance, (ignored, previous) -> {
            int cursor = previous == null ? currentMemoryEnd(memory) : previous;
            ensureCapacity(memory, cursor + bytes.length);
            return cursor + bytes.length;
        }) - bytes.length;
        memory.write(pointer, bytes);
        return new long[] { Integer.toUnsignedLong(pointer), Integer.toUnsignedLong(bytes.length) };
    }

    private int currentMemoryEnd(Memory memory) {
        return memory.pages() * Memory.PAGE_SIZE;
    }

    private void ensureCapacity(Memory memory, int requiredBytes) {
        int requiredPages = (requiredBytes + Memory.PAGE_SIZE - 1) / Memory.PAGE_SIZE;
        int missingPages = requiredPages - memory.pages();
        if (missingPages > 0) {
            int grown = memory.grow(missingPages);
            if (grown < 0) {
                throw new IllegalStateException("guest-memory-grow-failed");
            }
        }
    }

    private Memory requireGuestMemory(Instance instance, String operation) {
        if (instance == null) {
            throw new IllegalStateException("guest-instance-missing-for-" + operation);
        }
        Memory memory = instance.memory();
        if (memory == null) {
            throw new IllegalStateException("guest-memory-unavailable-for-" + operation);
        }
        return memory;
    }

    private int rawI32(long[] rawArgs, int index) {
        if (rawArgs == null || index >= rawArgs.length) {
            throw new IllegalStateException("wasm-host-argument-missing");
        }
        return (int) rawArgs[index];
    }

    private long rawI64(long[] rawArgs, int index) {
        if (rawArgs == null || index >= rawArgs.length) {
            throw new IllegalStateException("wasm-host-argument-missing");
        }
        return rawArgs[index];
    }
}
