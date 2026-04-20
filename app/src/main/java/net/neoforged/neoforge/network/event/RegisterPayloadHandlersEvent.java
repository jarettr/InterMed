package net.neoforged.neoforge.network.event;

public final class RegisterPayloadHandlersEvent {

    public PayloadRegistrar registrar(String token) {
        return new PayloadRegistrar(token);
    }

    public record PayloadRegistrar(String token) {
    }
}
