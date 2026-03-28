package org.intermed.core.monitor;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("org.intermed.HeavyModTick")
@Label("Heavy Mod Tick")
@Category({"InterMed", "Performance"})
@StackTrace(false)
public class HeavyModTickEvent extends Event {
    @Label("Mod ID")
    public String modId;

    @Label("Duration MS")
    public double durationMs;

    @Label("EWMA Time")
    public double ewmaTickTime;

    // Пустой конструктор обязателен для JFR
    public HeavyModTickEvent() {}
}