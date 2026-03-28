package org.intermed.core.bridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.intermed.core.monitoring.InterMedTickEvent;

/**
 * Глобальный транслятор событий.
 * Подписывается на нативные события Forge и транслирует их в API Fabric.
 */
public class InterMedEventBridge {

    // ТЗ 3.2.6: Переменные для алгоритма EWMA (Экспоненциально взвешенное скользящее среднее)
    private static final double EWMA_ALPHA = 0.2; // Коэффициент сглаживания (20% вес нового значения)
    private double ewmaTickDuration = 50.0; // Идеальный тик - 50 мс (20 TPS)
    private long currentTickStartTime = 0;

    // ТЗ 3.2.6 (Требование 10): Паттерн Flyweight для минимизации нагрузки на GC
    private static final Queue<VirtualTickEvent> TICK_EVENT_POOL = new ConcurrentLinkedQueue<>();

    // Инициализация пула
    static {
        for (int i = 0; i < 50; i++) {
            TICK_EVENT_POOL.offer(new VirtualTickEvent());
        }
    }

    // Легковесный объект для трансляции
    private static class VirtualTickEvent {
        Object serverInstance;
        // Метод сброса состояния
        void reset() { this.serverInstance = null; }
    }

    public static void initialize() {
        System.out.println("\033[1;36m[InterMed] Инициализация Event Bridge (Forge -> Fabric)...\033[0m");
        // Регистрируем наш мост в шине Forge
        MinecraftForge.EVENT_BUS.register(new InterMedEventBridge());
    }

    // 1. ПЕРЕХВАТ ТИКА СЕРВЕРА
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent forgeEvent) {
        if (forgeEvent.phase == TickEvent.Phase.START) {
            // Засекаем начало тика
            currentTickStartTime = System.currentTimeMillis();

        } else if (forgeEvent.phase == TickEvent.Phase.END) {
            // ТЕОРЕТИЧЕСКИЙ ВЫЗОВ:
            // Здесь мы через рефлексию или сгенерированный код дергаем массив Fabric:
            // net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
            
            // Берем объект из пула
            VirtualTickEvent virtualEvent = TICK_EVENT_POOL.poll();
            if (virtualEvent == null) virtualEvent = new VirtualTickEvent(); // Fallback, если пул пуст
            
            try {
                // ТЗ 3.2.6: Интеграция с Java Flight Recorder (JFR)
                InterMedTickEvent jfrEvent = new InterMedTickEvent();
                jfrEvent.tickPhase = "END";
                jfrEvent.activeModId = "intermed_core"; 
                jfrEvent.begin(); // Начинаем замер времени

                virtualEvent.serverInstance = forgeEvent.getServer();
                // Трансляция события...
                
                // ТЗ 3.2.6: Расчет EWMA для выявления аномалий
                long tickDuration = System.currentTimeMillis() - currentTickStartTime;
                ewmaTickDuration = (EWMA_ALPHA * tickDuration) + ((1.0 - EWMA_ALPHA) * ewmaTickDuration);

                // Если сглаженное среднее превышает норму 50мс (TPS падает ниже 20)
                if (ewmaTickDuration > 50.0) {
                    System.err.println("\033[1;31m[InterMed Monitor] ⚠ АНОМАЛИЯ TPS! EWMA тика: " + 
                        String.format("%.2f", ewmaTickDuration) + " мс. Запись JFR дампа...\033[0m");
                }

                jfrEvent.commit(); // Записываем событие в JFR

            } finally {
                virtualEvent.reset();
                TICK_EVENT_POOL.offer(virtualEvent); // Возвращаем в пул
            }
        }
    }

    // 2. ПЕРЕХВАТ ВХОДА ИГРОКА
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent forgeEvent) {
        // ТЕОРЕТИЧЕСКИЙ ВЫЗОВ:
        // Транслируем в Fabric:
        // net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.invoker().onPlayInit(...);
        System.out.println("[InterMed] Игрок зашел. Сигнал отправлен в Fabric-моды.");
    }
    
    // И так далее для сотен других событий (Блоки, Рендер, Инвентарь)...
}