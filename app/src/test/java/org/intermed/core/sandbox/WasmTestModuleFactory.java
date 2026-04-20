package org.intermed.core.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class WasmTestModuleFactory {

    private static final String HOST_MODULE = "intermed:sandbox";

    private WasmTestModuleFactory() {}

    static byte[] hasCurrentCapabilityModule(String capability) {
        byte[] capabilityBytes = capability.getBytes(StandardCharsets.UTF_8);
        return module(
            List.of(
                funcType(List.of(0x7f, 0x7f), List.of(0x7f)),
                funcType(List.of(), List.of(0x7f))
            ),
            List.of(importFunction(HOST_MODULE, "has-current-capability", 0)),
            List.of(1),
            1,
            List.of(exportFunction("init_mod", 1)),
            List.of(body(
                i32Const(0),
                i32Const(capabilityBytes.length),
                call(0),
                end()
            )),
            List.of(dataSegment(0, capabilityBytes))
        );
    }

    static byte[] currentModIdRoundTripModule() {
        return module(
            List.of(
                funcType(List.of(), List.of(0x7f, 0x7f)),
                funcType(List.of(0x7f, 0x7f), List.of(0x7f)),
                funcType(List.of(), List.of(0x7f))
            ),
            List.of(
                importFunction(HOST_MODULE, "current-mod-id", 0),
                importFunction(HOST_MODULE, "is-mod-loaded", 1)
            ),
            List.of(2),
            1,
            List.of(exportFunction("init_mod", 2)),
            List.of(body(
                call(0),
                call(1),
                end()
            )),
            List.of()
        );
    }

    static byte[] currentSandboxModeIdModule() {
        return module(
            List.of(funcType(List.of(), List.of(0x7f))),
            List.of(importFunction(HOST_MODULE, "current-sandbox-mode-id", 0)),
            List.of(0),
            0,
            List.of(exportFunction("init_mod", 1)),
            List.of(body(
                call(0),
                end()
            )),
            List.of()
        );
    }

    static byte[] currentSandboxInvocationKeyLengthModule() {
        return module(
            List.of(funcType(List.of(), List.of(0x7f))),
            List.of(importFunction(HOST_MODULE, "current-sandbox-invocation-key-length", 0)),
            List.of(0),
            0,
            List.of(exportFunction("init_mod", 1)),
            List.of(body(
                call(0),
                end()
            )),
            List.of()
        );
    }

    static byte[] currentSandboxSharedStateBytesModule() {
        return module(
            List.of(funcType(List.of(), List.of(0x7f))),
            List.of(importFunction(HOST_MODULE, "current-sandbox-shared-state-bytes", 0)),
            List.of(0),
            0,
            List.of(exportFunction("init_mod", 1)),
            List.of(body(
                call(0),
                end()
            )),
            List.of()
        );
    }

    private static byte[] module(List<byte[]> types,
                                 List<byte[]> imports,
                                 List<Integer> functionTypeIndexes,
                                 int memoryPages,
                                 List<byte[]> exports,
                                 List<byte[]> bodies,
                                 List<byte[]> dataSegments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x00, 0x61, 0x73, 0x6d);
        write(out, 0x01, 0x00, 0x00, 0x00);

        writeSection(out, 1, vector(types));
        if (!imports.isEmpty()) {
            writeSection(out, 2, vector(imports));
        }
        if (!functionTypeIndexes.isEmpty()) {
            ByteArrayOutputStream section = new ByteArrayOutputStream();
            writeUnsigned(section, functionTypeIndexes.size());
            for (int typeIndex : functionTypeIndexes) {
                writeUnsigned(section, typeIndex);
            }
            writeSection(out, 3, section.toByteArray());
        }
        if (memoryPages > 0) {
            ByteArrayOutputStream section = new ByteArrayOutputStream();
            writeUnsigned(section, 1);
            write(section, 0x00);
            writeUnsigned(section, memoryPages);
            writeSection(out, 5, section.toByteArray());
        }
        writeSection(out, 7, vector(exports));

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        writeUnsigned(code, bodies.size());
        for (byte[] body : bodies) {
            writeUnsigned(code, body.length);
            code.writeBytes(body);
        }
        writeSection(out, 10, code.toByteArray());

        if (!dataSegments.isEmpty()) {
            writeSection(out, 11, vector(dataSegments));
        }
        return out.toByteArray();
    }

    private static byte[] funcType(List<Integer> params, List<Integer> returns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x60);
        writeUnsigned(out, params.size());
        for (int param : params) {
            write(out, param);
        }
        writeUnsigned(out, returns.size());
        for (int result : returns) {
            write(out, result);
        }
        return out.toByteArray();
    }

    private static byte[] importFunction(String module, String name, int typeIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeName(out, module);
        writeName(out, name);
        write(out, 0x00);
        writeUnsigned(out, typeIndex);
        return out.toByteArray();
    }

    private static byte[] exportFunction(String name, int functionIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeName(out, name);
        write(out, 0x00);
        writeUnsigned(out, functionIndex);
        return out.toByteArray();
    }

    private static byte[] body(byte[]... instructions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsigned(out, 0);
        for (byte[] instruction : instructions) {
            out.writeBytes(instruction);
        }
        return out.toByteArray();
    }

    private static byte[] dataSegment(int offset, byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x00);
        out.writeBytes(i32Const(offset));
        write(out, 0x0b);
        writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static byte[] i32Const(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x41);
        writeUnsigned(out, value);
        return out.toByteArray();
    }

    private static byte[] call(int functionIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x10);
        writeUnsigned(out, functionIndex);
        return out.toByteArray();
    }

    private static byte[] end() {
        return new byte[] { 0x0b };
    }

    private static byte[] vector(List<byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsigned(out, entries.size());
        for (byte[] entry : entries) {
            out.writeBytes(entry);
        }
        return out.toByteArray();
    }

    private static void writeSection(ByteArrayOutputStream out, int id, byte[] payload) {
        write(out, id);
        writeUnsigned(out, payload.length);
        out.writeBytes(payload);
    }

    private static void writeName(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeUnsigned(ByteArrayOutputStream out, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            write(out, next);
        } while (remaining != 0);
    }

    private static void write(ByteArrayOutputStream out, int... values) {
        for (int value : values) {
            out.write(value);
        }
    }
}
