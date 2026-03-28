package org.intermed.core.monitor;

import java.util.concurrent.ConcurrentLinkedQueue;

public class EventFlyweightPool {

    // Класс-пустышка для шины событий, который мы будем переиспользовать
    public static class InterMedEvent {
        public String eventName;
        public Object payload;

        public void reset() {
            this.eventName = null;
            this.payload = null;
        }
    }

    // Потокобезопасная очередь для пула
    private static final ConcurrentLinkedQueue<InterMedEvent> POOL = new ConcurrentLinkedQueue<>();
    private static final int MAX_POOL_SIZE = 1000;
    private static int currentSize = 0;

    // Инициализация пула (вызывается из Preparator'а)
    public static void preallocate() {
        for (int i = 0; i < 500; i++) {
            POOL.add(new InterMedEvent());
            currentSize++;
        }
        System.out.println("[Event Pool] Flyweight пул инициализирован (размер: " + currentSize + ")");
    }

    public static InterMedEvent acquire(String name, Object payload) {
        InterMedEvent event = POOL.poll();
        if (event == null) {
            // Если пул пуст (пиковая нагрузка), создаем новый
            event = new InterMedEvent();
        } else {
            currentSize--;
        }
        event.eventName = name;
        event.payload = payload;
        return event;
    }

    public static void release(InterMedEvent event) {
        if (currentSize < MAX_POOL_SIZE) {
            event.reset();
            POOL.add(event);
            currentSize++;
        }
    }
}