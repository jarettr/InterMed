package org.intermed.core.monitor;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * TZ Req 14: Адаптивный мониторинг производительности.
 */
public class MetricsManager {

    @Name("org.intermed.LoadEvent")
    @Label("InterMed Load Step")
    @Category({"InterMed", "Startup"})
    public static class LoadEvent extends Event {
        @Label("Step Name")
        public String stepName;
        @Label("Duration MS")
        public long duration;
    }

    public static void recordStep(String name, long ms) {
        LoadEvent event = new LoadEvent();
        event.stepName = name;
        event.duration = ms;
        event.commit();

        MetricsRegistry.get().histogramRaw(
            "intermed_load_step_duration_ms{step=\"" + sanitize(name) + "\"}", ms);

        if (ms > 500) {
            System.err.println("\033[1;31m[Metrics] Performance Anomaly detected in: "
                + name + " (" + ms + "ms)\033[0m");
        }
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replace('"', '\'').replace('\\', '/');
    }
}
