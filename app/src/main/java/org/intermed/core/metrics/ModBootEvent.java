package org.intermed.core.metrics;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * JFR event emitted once per Fabric mod entrypoint boot.
 * Captures the class name and timing so boot latency can be
 * inspected in JDK Mission Control or {@code jfr print}.
 */
@Name("org.intermed.ModBoot")
@Label("Mod Boot")
@Category({"InterMed", "Boot"})
public class ModBootEvent extends Event {

    @Label("Entrypoint Class")
    public String className;
}
