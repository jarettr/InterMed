package org.intermed.core.classloading;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread-local context for the class currently passing through the InterMed
 * transformation pipeline.
 */
public final class TransformationContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TransformationContext() {}

    public static Scope enter(String modId, String className) {
        State previous = CURRENT.get();
        CURRENT.set(new State(modId, className));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<State> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String currentModIdOr(String fallback) {
        return current().map(State::modId).filter(id -> id != null && !id.isBlank()).orElse(fallback);
    }

    public static String currentClassNameOr(String fallback) {
        return current().map(State::className).filter(name -> name != null && !name.isBlank()).orElse(fallback);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record State(String modId, String className) {
        public State {
            Objects.requireNonNull(className, "className");
        }
    }
}
