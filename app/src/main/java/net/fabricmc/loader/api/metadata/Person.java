package net.fabricmc.loader.api.metadata;

public interface Person {
    String getName();

    default ContactInformation getContact() {
        return ContactInformation.EMPTY;
    }
}
