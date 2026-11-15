package dev.itsdropper.caustiq.integration.nvidium;

/**
 * Shared commit bit for the Nvidium vertex encoder, stride mixins and shader
 * compiler bridge. Kept independent of Sodium-linked vertex classes so
 * compile-boundary unit tests can exercise the gate without loading Sodium.
 */
final class NvidiumVertexAbiState {
    private static volatile boolean committed;

    private NvidiumVertexAbiState() { }

    static boolean isCommitted() {
        return committed;
    }

    static void commit() {
        committed = true;
    }

    static void clear() {
        committed = false;
    }

    static void setCommittedForTests(boolean value) {
        committed = value;
    }
}
