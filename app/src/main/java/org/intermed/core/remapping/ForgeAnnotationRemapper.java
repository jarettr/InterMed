package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Remaps class/method/field name strings embedded in Forge and Mixin annotations.
 *
 * <p>Mixin and Forge use annotation attributes to reference game classes by name
 * as plain {@code String} values.  These strings are not caught by the regular
 * LDC-constant or {@code invokedynamic} instrumentation in
 * {@link ReflectionTransformer} because they live inside annotation structures,
 * not in executable method bodies.  This transformer walks every annotation
 * (class, method, and field level) and remaps the known string-valued attributes
 * that carry bytecode-level names.
 *
 * <h3>Handled annotations and attributes</h3>
 * <table border="1">
 *   <tr><th>Annotation</th><th>Attribute(s)</th><th>Content</th></tr>
 *   <tr><td>{@code @Mixin}</td><td>{@code targets}</td>
 *       <td>Array of fully-qualified class name strings
 *           ({@code "net.minecraft.class_XXX"})</td></tr>
 *   <tr><td>{@code @At}</td><td>{@code target}</td>
 *       <td>Method/field descriptor string
 *           ({@code "Lnet/minecraft/class_XXX;method_YYY(I)V"})</td></tr>
 *   <tr><td>{@code @ObjectHolder}</td><td>{@code value}</td>
 *       <td>Registry key / resource-location string (remapped conservatively)</td></tr>
 *   <tr><td>{@code @Mod.EventBusSubscriber} / {@code @EventBusSubscriber}</td>
 *       <td>{@code modid}</td>
 *       <td>Mod-ID string — NOT remapped (not a class reference)</td></tr>
 * </table>
 *
 * <p>Each remapped string is passed through
 * {@link InterMedRemapper#translateRuntimeString(String)} which consults the
 * active {@link MappingDictionary}.  Strings that the remapper does not know
 * about are returned unchanged, so unknown annotations are safe to pass through.
 */
public final class ForgeAnnotationRemapper implements BytecodeTransformer {

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            boolean changed = false;

            changed |= remapAnnotations(node.visibleAnnotations);
            changed |= remapAnnotations(node.invisibleAnnotations);

            for (MethodNode method : node.methods) {
                changed |= remapAnnotations(method.visibleAnnotations);
                changed |= remapAnnotations(method.invisibleAnnotations);
                if (method.visibleParameterAnnotations != null) {
                    for (List<AnnotationNode> paramAnns : method.visibleParameterAnnotations) {
                        changed |= remapAnnotations(paramAnns);
                    }
                }
                if (method.invisibleParameterAnnotations != null) {
                    for (List<AnnotationNode> paramAnns : method.invisibleParameterAnnotations) {
                        changed |= remapAnnotations(paramAnns);
                    }
                }
            }

            for (FieldNode field : node.fields) {
                changed |= remapAnnotations(field.visibleAnnotations);
                changed |= remapAnnotations(field.invisibleAnnotations);
            }

            if (!changed) return originalBytes;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Exception e) {
            return originalBytes;
        }
    }

    // =========================================================================
    // Core remapping logic
    // =========================================================================

    /**
     * Walks an annotation list and remaps known string-valued attributes.
     *
     * @return {@code true} if at least one value was changed.
     */
    private static boolean remapAnnotations(List<AnnotationNode> annotations) {
        if (annotations == null || annotations.isEmpty()) return false;
        boolean changed = false;
        for (AnnotationNode ann : annotations) {
            changed |= remapAnnotation(ann);
        }
        return changed;
    }

    private static boolean remapAnnotation(AnnotationNode ann) {
        if (ann == null || ann.values == null || ann.values.isEmpty()) return false;
        boolean changed = false;

        if (isMixinAnnotation(ann.desc)) {
            changed |= remapArrayAttribute(ann, "targets", true);
        }

        if (isAtAnnotation(ann.desc)) {
            changed |= remapStringAttribute(ann, "target", false);
        }

        if (isObjectHolderAnnotation(ann.desc)) {
            changed |= remapStringAttribute(ann, "value", true);
        }

        // Recurse into nested annotations (e.g. @Inject contains @At arrays)
        for (int i = 1; i < ann.values.size(); i += 2) {
            Object val = ann.values.get(i);
            if (val instanceof AnnotationNode nested) {
                changed |= remapAnnotation(nested);
            } else if (val instanceof List<?> list) {
                changed |= remapList(list);
            }
        }

        return changed;
    }

    @SuppressWarnings("unchecked")
    private static boolean remapList(List<?> list) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object item = ((List<Object>) list).get(i);
            if (item instanceof AnnotationNode nested) {
                changed |= remapAnnotation(nested);
            }
        }
        return changed;
    }

    /**
     * Remaps a single {@code String}-valued attribute by name.
     *
     * @param classBinaryForm {@code true} if the value is a binary class name
     *                        ({@code "net.minecraft.class_XXX"} style);
     *                        {@code false} if it is a JVM descriptor fragment
     *                        ({@code "Lnet/minecraft/...;method_YYY(I)V"}).
     */
    private static boolean remapStringAttribute(AnnotationNode ann, String attrName,
                                                boolean classBinaryForm) {
        for (int i = 0; i < ann.values.size() - 1; i += 2) {
            if (!attrName.equals(ann.values.get(i))) continue;
            Object value = ann.values.get(i + 1);
            if (!(value instanceof String s)) continue;

            String remapped = classBinaryForm
                ? InterMedRemapper.remapBinaryClassName(s)
                : remapDescriptorFragment(s);
            if (!remapped.equals(s)) {
                ann.values.set(i + 1, remapped);
                return true;
            }
        }
        return false;
    }

    /**
     * Remaps every element in a {@code String[]}-valued annotation attribute.
     */
    @SuppressWarnings("unchecked")
    private static boolean remapArrayAttribute(AnnotationNode ann, String attrName,
                                               boolean classBinaryForm) {
        for (int i = 0; i < ann.values.size() - 1; i += 2) {
            if (!attrName.equals(ann.values.get(i))) continue;
            Object value = ann.values.get(i + 1);
            if (!(value instanceof List)) continue;

            List<Object> list = (List<Object>) value;
            boolean changed = false;
            for (int j = 0; j < list.size(); j++) {
                if (!(list.get(j) instanceof String s)) continue;
                String remapped = classBinaryForm
                    ? InterMedRemapper.remapBinaryClassName(s)
                    : remapDescriptorFragment(s);
                if (!remapped.equals(s)) {
                    list.set(j, remapped);
                    changed = true;
                }
            }
            return changed;
        }
        return false;
    }

    /**
     * Remaps a Mixin AT (injection-point target) descriptor of the form
     * <pre>
     *   Lnet/minecraft/class_XXX;method_YYY(I)V          — method reference
     *   Lnet/minecraft/class_XXX;field_YYY:Ljava/lang/Object;  — field reference
     * </pre>
     *
     * <p>Each structural component is remapped independently:
     * <ol>
     *   <li>Owner internal name — {@code net/minecraft/class_XXX} is treated as a
     *       binary class name and passed through
     *       {@link InterMedRemapper#remapBinaryClassName}.</li>
     *   <li>Member name — the method or field identifier is passed through
     *       {@link InterMedRemapper#translateRuntimeString}.</li>
     *   <li>Descriptor / type — every {@code L…;} reference embedded in the
     *       remainder is remapped recursively by {@link #remapTypeDescriptor}.</li>
     * </ol>
     *
     * <p>Strings that do not match the {@code L…;name…} pattern (e.g. plain
     * {@code "net.minecraft.class_XXX"} or Forge-style
     * {@code "net/minecraft/block/Block"}) fall back to
     * {@link InterMedRemapper#translateRuntimeString} so they are handled by the
     * general translation heuristics.
     */
    private static String remapDescriptorFragment(String target) {
        if (target == null || target.isBlank()) return target;

        // Structured AT descriptor: "Lowner;name..." (starts with 'L', contains ';')
        if (target.startsWith("L")) {
            int semi = target.indexOf(';');
            if (semi > 1) {
                String ownerInternal = target.substring(1, semi);          // "net/minecraft/class_XXX"
                String memberAndRest  = target.substring(semi + 1);        // "method_YYY(I)V" | "field_YYY:T"

                String remappedOwner = remapInternalName(ownerInternal);

                // Method ref: "name(desc)ret"
                int parenOpen = memberAndRest.indexOf('(');
                if (parenOpen >= 0) {
                    String memberName = memberAndRest.substring(0, parenOpen);
                    String descriptor = memberAndRest.substring(parenOpen);
                    String remappedMember = InterMedRemapper.translateRuntimeString(memberName);
                    String remappedDesc  = remapTypeDescriptor(descriptor);
                    return "L" + remappedOwner + ";" + remappedMember + remappedDesc;
                }

                // Field ref: "name:type"
                int colon = memberAndRest.indexOf(':');
                if (colon >= 0) {
                    String memberName = memberAndRest.substring(0, colon);
                    String fieldType  = memberAndRest.substring(colon + 1);
                    String remappedMember = InterMedRemapper.translateRuntimeString(memberName);
                    String remappedType   = remapTypeDescriptor(fieldType);
                    return "L" + remappedOwner + ";" + remappedMember + ":" + remappedType;
                }

                // Bare member reference: "Lowner;memberName" (no descriptor)
                String remappedMember = InterMedRemapper.translateRuntimeString(memberAndRest);
                return "L" + remappedOwner + ";" + remappedMember;
            }
        }

        // Fallback: plain class/member string — use the general translator
        return InterMedRemapper.translateRuntimeString(target);
    }

    /**
     * Remaps an internal class name ({@code "net/minecraft/class_XXX"}) to its
     * mapped equivalent, delegating through
     * {@link InterMedRemapper#remapBinaryClassName} which operates on
     * dot-separated names and converts the result back to slash form.
     */
    private static String remapInternalName(String internalName) {
        // remapBinaryClassName expects dot-separated ("net.minecraft.class_XXX")
        String dotForm    = internalName.replace('/', '.');
        String remapped   = InterMedRemapper.remapBinaryClassName(dotForm);
        return remapped.replace('.', '/');
    }

    /**
     * Walks a JVM type descriptor ({@code "(ILnet/minecraft/class_XXX;Z)V"} or
     * {@code "Lnet/minecraft/class_YYY;"}) and remaps every embedded
     * {@code L<internalName>;} reference.
     */
    private static String remapTypeDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return descriptor;
        StringBuilder sb   = new StringBuilder();
        int           i    = 0;
        int           len  = descriptor.length();
        while (i < len) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i + 1);
                if (semi < 0) {
                    // Malformed — copy remainder as-is
                    sb.append(descriptor, i, len);
                    break;
                }
                String raw      = descriptor.substring(i + 1, semi);
                String remapped = remapInternalName(raw);
                sb.append('L').append(remapped).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Annotation descriptor matchers
    // =========================================================================

    private static boolean isMixinAnnotation(String desc) {
        return desc != null && (
            desc.endsWith("/Mixin;")                            // Sponge Mixin
            || desc.endsWith("/MixinInfo;")
        );
    }

    private static boolean isAtAnnotation(String desc) {
        return desc != null && (
            desc.endsWith("/At;")                               // @At in @Inject, etc.
            || desc.endsWith("/injection/At;")
        );
    }

    private static boolean isObjectHolderAnnotation(String desc) {
        return desc != null && (
            desc.endsWith("/ObjectHolder;")                     // Forge @ObjectHolder
            || desc.endsWith("/registries/ObjectHolder;")
        );
    }
}
