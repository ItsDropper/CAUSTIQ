package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqClient;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the actual Sodium chunk view/projection matrices supplied to
 * Nvidium and uploads their Iris-compatible uniforms after Nvidium binds its
 * primary terrain program.
 */
public final class NvidiumIrisUniformBridge {
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();
    private static final AtomicInteger FRAME_COUNTER = new AtomicInteger();
    private static volatile long worldStartNanos;
    private static volatile boolean warnedUploadFailure;

    private NvidiumIrisUniformBridge() { }

    /** Called at NvidiumWorldRenderer.renderFrame HEAD, before its GPU draw phases. */
    public static void capture(Object chunkRenderMatrices, double cameraX, double cameraY, double cameraZ) {
        if (chunkRenderMatrices == null) return;
        try {
            Matrix4fc projection = (Matrix4fc) invoke(chunkRenderMatrices, "projection");
            Matrix4fc modelView = (Matrix4fc) invoke(chunkRenderMatrices, "modelView");
            Matrix4f projectionInverse = new Matrix4f(projection).invert();
            Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
            Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();
            long now = System.nanoTime();
            if (worldStartNanos == 0L) worldStartNanos = now;

            CURRENT.set(new Snapshot(
                    matrix(projection),
                    matrix(modelView),
                    matrix(projectionInverse),
                    matrix(modelViewInverse),
                    matrix3(normalMatrix),
                    (float) cameraX,
                    (float) cameraY,
                    (float) cameraZ,
                    (now - worldStartNanos) / 1_000_000_000.0f,
                    FRAME_COUNTER.incrementAndGet()));
        } catch (ReflectiveOperationException | ClassCastException exception) {
            CaustiqClient.LOGGER.error("Could not capture Nvidium's ChunkRenderMatrices for Iris terrain uniforms.", exception);
            CURRENT.set(null);
        }
    }

    /** Called immediately after PrimaryTerrainRasterizer binds its linked GL program. */
    public static void upload(Object rasterizer) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot == null || rasterizer == null) return;
        try {
            Field shaderField = rasterizer.getClass().getDeclaredField("shader");
            shaderField.setAccessible(true);
            Object shader = shaderField.get(rasterizer);
            Method getId = shader.getClass().getMethod("getId");
            int program = ((Number) getId.invoke(shader)).intValue();

            matrix(program, "caustiqProjection", snapshot.projection());
            matrix(program, "caustiqModelView", snapshot.modelView());
            matrix(program, "caustiqProjectionInverse", snapshot.projectionInverse());
            matrix(program, "caustiqModelViewInverse", snapshot.modelViewInverse());
            matrix3(program, "caustiqNormalMatrix", snapshot.normalMatrix());
            vector3(program, "caustiqCameraPosition",
                    snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
            int timeLocation = GL20C.glGetUniformLocation(program, "caustiqFrameTimeCounter");
            if (timeLocation >= 0) GL20C.glUniform1f(timeLocation, snapshot.frameTimeCounter());
            int frameLocation = GL20C.glGetUniformLocation(program, "caustiqFrameCounter");
            if (frameLocation >= 0) GL20C.glUniform1i(frameLocation, snapshot.frameCounter());
            warnedUploadFailure = false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedUploadFailure) {
                warnedUploadFailure = true;
                CaustiqClient.LOGGER.warn("Unable to upload Iris terrain matrices to Nvidium's primary shader.", exception);
            }
        }
    }

    /** Uploads the captured camera/time state to a CAUSTIQ OpenGL post-process program. */
    public static void uploadProgram(int program) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot == null || program == 0) return;
        matrix(program, "gbufferProjection", snapshot.projection());
        matrix(program, "gbufferModelView", snapshot.modelView());
        matrix(program, "gbufferProjectionInverse", snapshot.projectionInverse());
        matrix(program, "gbufferModelViewInverse", snapshot.modelViewInverse());
        matrix(program, "gl_ProjectionMatrix", snapshot.projection());
        matrix(program, "gl_ModelViewMatrix", snapshot.modelView());
        matrix(program, "gl_ProjectionMatrixInverse", snapshot.projectionInverse());
        matrix(program, "gl_ModelViewMatrixInverse", snapshot.modelViewInverse());
        matrix3(program, "gl_NormalMatrix", snapshot.normalMatrix());
        vector3(program, "cameraPosition", snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
        int timeLocation = GL20C.glGetUniformLocation(program, "frameTimeCounter");
        if (timeLocation >= 0) GL20C.glUniform1f(timeLocation, snapshot.frameTimeCounter());
        int frameLocation = GL20C.glGetUniformLocation(program, "frameCounter");
        if (frameLocation >= 0) GL20C.glUniform1i(frameLocation, snapshot.frameCounter());
    }

    static Snapshot snapshot() { return CURRENT.get(); }

    static void resetForTests() {
        CURRENT.set(null);
        FRAME_COUNTER.set(0);
        worldStartNanos = 0L;
        warnedUploadFailure = false;
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static float[] matrix(Matrix4fc matrix) {
        return matrix.get(new float[16]);
    }

    private static float[] matrix3(Matrix3fc matrix) {
        return matrix.get(new float[9]);
    }

    private static void matrix(int program, String name, float[] values) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniformMatrix4fv(location, false, values);
    }

    private static void matrix3(int program, String name, float[] values) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniformMatrix3fv(location, false, values);
    }

    private static void vector3(int program, String name, float x, float y, float z) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform3f(location, x, y, z);
    }

    record Snapshot(
            float[] projection,
            float[] modelView,
            float[] projectionInverse,
            float[] modelViewInverse,
            float[] normalMatrix,
            float cameraX,
            float cameraY,
            float cameraZ,
            float frameTimeCounter,
            int frameCounter) {
        Snapshot {
            projection = projection.clone();
            modelView = modelView.clone();
            projectionInverse = projectionInverse.clone();
            modelViewInverse = modelViewInverse.clone();
            normalMatrix = normalMatrix.clone();
        }
    }
}
