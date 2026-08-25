package me.cortex.voxy.client.core.model;

public class IdNotYetComputedException extends RuntimeException {
    public final int id;
    public IdNotYetComputedException(int id) {
        super(null, null, false, false);
        this.id = id;
    }
}
