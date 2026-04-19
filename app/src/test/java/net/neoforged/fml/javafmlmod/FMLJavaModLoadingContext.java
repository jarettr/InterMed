package net.neoforged.fml.javafmlmod;

import net.neoforged.bus.api.EventPriority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class FMLJavaModLoadingContext {

    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    private final FakeEventBus modEventBus = new FakeEventBus();

    private FMLJavaModLoadingContext() {}

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public FakeEventBus getModEventBus() {
        return modEventBus;
    }

    public static void reset() {
        INSTANCE.modEventBus.reset();
    }

    public static final class FakeEventBus {
        private final Map<Class<?>, Consumer<Object>> listeners = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        public <T> void addListener(EventPriority priority,
                                    boolean receiveCanceled,
                                    Class<T> eventType,
                                    Consumer<T> consumer) {
            listeners.put(eventType, (Consumer<Object>) consumer);
        }

        public void dispatch(Object event) {
            Consumer<Object> consumer = listeners.get(event.getClass());
            if (consumer != null) {
                consumer.accept(event);
            }
        }

        public int listenerCount() {
            return listeners.size();
        }

        private void reset() {
            listeners.clear();
        }
    }
}
