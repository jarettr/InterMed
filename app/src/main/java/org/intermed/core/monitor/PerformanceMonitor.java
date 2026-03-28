package org.intermed.core.monitor;

import java.util.concurrent.atomic.AtomicLong;

public class PerformanceMonitor {
    private static final AtomicLong lastTickTime = new AtomicLong(0);
    private static double tps = 20.0;
    private static final double ALPHA = 0.05; // Коэффициент сглаживания EWMA

    public static void onServerTick() {
        long currentTime = System.nanoTime();
        long lastTime = lastTickTime.getAndSet(currentTime);

        if (lastTime != 0) {
            long diff = currentTime - lastTime;
            double currentTps = 1_000_000_000.0 / diff;
            
            // Алгоритм EWMA (ТЗ 3.2.6)
            tps = tps + ALPHA * (currentTps - tps);
            
            if (tps < 15.0) {
                System.out.println("\033[1;31m[Observability] LAG DETECTED! Current TPS: " + String.format("%.2f", tps) + "\033[0m");
            }
        }
    }

    public static double getTps() { return tps; }
}