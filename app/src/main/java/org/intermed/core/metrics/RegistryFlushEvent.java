package org.intermed.core.metrics;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * JFR event emitted each time InterMed flushes the pending-registration
 * queue into a Forge {@code RegisterEvent}. Useful for spotting unusually
 * large registry batches or slow flush paths.
 */
@Name("org.intermed.RegistryFlush")
@Label("Registry Flush")
@Category({"InterMed", "Registry"})
public class RegistryFlushEvent extends Event {

    @Label("Registry Key")
    public String registryKey;

    @Label("Object Count")
    public int objectCount;
}
