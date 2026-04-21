package net.fabricmc.fabric.api.event.player;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.ObservabilityMonitor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface UseBlockCallback {

    UseBlockEvent EVENT = new UseBlockEvent();

    InteractionResult interact(Player player, Level world, InteractionHand hand, BlockHitResult hitResult);

    static void resetForTests() {
        EVENT.clear();
    }

    final class UseBlockEvent {
        private final List<Registration> listeners = new CopyOnWriteArrayList<>();

        public void register(UseBlockCallback listener) {
            if (listener != null) {
                listeners.add(new Registration(EventRegistrationSupport.captureRegistrationModId(), listener));
            }
        }

        public UseBlockCallback invoker() {
            return (player, world, hand, hitResult) -> {
                for (Registration registration : listeners) {
                    if (ObservabilityMonitor.isModHardThrottled(registration.modId())) {
                        continue;
                    }
                    InteractionResult result = registration.listener().interact(player, world, hand, hitResult);
                    if (result != null && result != InteractionResult.PASS) {
                        return result;
                    }
                }
                return InteractionResult.PASS;
            };
        }

        public int listenerCount() {
            return listeners.size();
        }

        public void clear() {
            listeners.clear();
        }

        private record Registration(String modId, UseBlockCallback listener) {
        }
    }
}
