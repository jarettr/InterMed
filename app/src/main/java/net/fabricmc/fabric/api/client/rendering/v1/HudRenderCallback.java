package net.fabricmc.fabric.api.client.rendering.v1;

import java.util.ArrayList;
import java.util.List;

public interface HudRenderCallback {
    List<HudRenderCallback> LISTENERS = new ArrayList<>();

    // Принимаем Object, чтобы мост был универсальным
    void onHudRender(Object drawContext, float tickDelta);

    static void register(HudRenderCallback callback) {
        LISTENERS.add(callback);
    }
}