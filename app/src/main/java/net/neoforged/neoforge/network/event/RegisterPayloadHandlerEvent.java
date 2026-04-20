package net.neoforged.neoforge.network.event;

public final class RegisterPayloadHandlerEvent {

    public PayloadRegistrar registrar(String token) {
        return new PayloadRegistrar(token);
    }

    public record PayloadRegistrar(String token) {
    }
}
