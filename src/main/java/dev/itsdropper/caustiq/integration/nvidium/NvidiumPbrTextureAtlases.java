package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import dev.itsdropper.caustiq.mixin.TextureAtlasAccessorMixin;
import dev.itsdropper.caustiq.mixin.SpriteAnimationStateAccessorMixin;
import dev.itsdropper.caustiq.mixin.SpriteAnimatedTextureAccessorMixin;
import dev.itsdropper.caustiq.mixin.SpriteFrameInfoAccessorMixin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Iris-compatible resource-pack normal/specular block atlases without an Iris dependency. */
public final class NvidiumPbrTextureAtlases {
    private static final int NORMAL_DEFAULT = 0x8080FFFF;
    private static final int SPECULAR_DEFAULT = 0x00000000;
    private static final long MAX_ATLAS_BYTES = 256L * 1024L * 1024L;
    private static Object cachedAtlas;
    private static ResourceManager cachedResources;
    private static long cachedFingerprint = Long.MIN_VALUE;
    private static int normalsTexture;
    private static int specularTexture;
    private static boolean warnedResourceFailure;
    private static Map<Identifier, PbrSprite> cachedPbrSprites = Map.of();
    private static Map<Identifier, Integer> cachedAnimationFrames = Map.of();

    private NvidiumPbrTextureAtlases() { }

    public static int normalsTexture() { ensureCurrent(); return normalsTexture; }
    public static int specularTexture() { ensureCurrent(); return specularTexture; }

    static Identifier pbrResourceId(Identifier sprite, boolean normal) {
        String path = sprite.getPath() + (normal ? "_n" : "_s");
        String resourcePath = path.startsWith("optifine/cit/")
                ? path + ".png" : "textures/" + path + ".png";
        return Identifier.fromNamespaceAndPath(sprite.getNamespace(), resourcePath);
    }

    /** Runs the real atlas layout and GL upload path from the Minecraft CI client. */
    public static void validateForTests() {
        try {
            int normal = normalsTexture();
            int specular = specularTexture();
            if (normal == 0 || specular == 0
                    || !GL11C.glIsTexture(normal) || !GL11C.glIsTexture(specular)) {
                throw new IllegalStateException("PBR atlas upload did not produce valid OpenGL textures.");
            }
            int active = GL11C.glGetInteger(0x84E0);
            int binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            try {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, normal);
                int normalWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
                int normalHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, specular);
                int specularWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
                int specularHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
                if (normalWidth <= 0 || normalHeight <= 0
                        || normalWidth != specularWidth || normalHeight != specularHeight) {
                    throw new IllegalStateException("PBR atlas dimensions are invalid or inconsistent.");
                }
            } finally {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding);
                GL13C.glActiveTexture(active);
            }
        } finally {
            destroy();
        }
    }

    public static void destroy() {
        if (normalsTexture != 0) GL11C.glDeleteTextures(normalsTexture);
        if (specularTexture != 0) GL11C.glDeleteTextures(specularTexture);
        normalsTexture = 0; specularTexture = 0;
        cachedAtlas = null; cachedResources = null; cachedFingerprint = Long.MIN_VALUE;
        cachedPbrSprites = Map.of(); cachedAnimationFrames = Map.of();
    }

    /** Invalidates cached PBR textures before Minecraft replaces the block atlas contents. */
    public static void onAtlasReload(TextureAtlas atlas) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) destroy();
    }

    /** Mirrors Minecraft's animated block-sprite frame changes once per atlas tick. */
    public static void onAtlasTick(TextureAtlas atlas) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (atlas != cachedAtlas || minecraft.getResourceManager() != cachedResources
                || normalsTexture == 0 || specularTexture == 0) {
            ensureCurrent();
            return;
        }
        Map<Identifier, Integer> currentFrames = new HashMap<>();
        readAnimationInfo(atlas).forEach((id, info) -> {
            if (info.frameCount() > 1) currentFrames.put(id, info.currentFrame());
        });
        syncAnimationFrames(Map.copyOf(currentFrames));
    }

    private static void ensureCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        ResourceManager resources = minecraft.getResourceManager();
        if (atlas == cachedAtlas && resources == cachedResources
                && normalsTexture != 0 && specularTexture != 0) return;
        AtlasLayout layout = readAtlasLayout(atlas);
        Map<Identifier, PbrSprite> nextSprites = loadPbrSprites(layout, resources);
        int nextNormals = 0, nextSpecular = 0;
        try {
            nextNormals = buildAtlas(layout, nextSprites, true);
            nextSpecular = buildAtlas(layout, nextSprites, false);
        } catch (RuntimeException exception) {
            if (nextNormals != 0) GL11C.glDeleteTextures(nextNormals);
            if (nextSpecular != 0) GL11C.glDeleteTextures(nextSpecular);
            throw exception;
        }
        if (normalsTexture != 0) GL11C.glDeleteTextures(normalsTexture);
        if (specularTexture != 0) GL11C.glDeleteTextures(specularTexture);
        normalsTexture = nextNormals; specularTexture = nextSpecular;
        cachedAtlas = atlas; cachedResources = resources; cachedFingerprint = layout.fingerprint();
        cachedPbrSprites = Map.copyOf(nextSprites);
        cachedAnimationFrames = layout.animationFrames();
    }

    private static AtlasLayout readAtlasLayout(TextureAtlas atlas) {
        TextureAtlasAccessorMixin accessor = (TextureAtlasAccessorMixin) (Object) atlas;
        int width = accessor.caustiq$getWidth();
        int height = accessor.caustiq$getHeight();
        Map<Identifier, TextureAtlasSprite> spriteMap = accessor.caustiq$getTexturesByName();
        if (spriteMap == null) throw new IllegalStateException("Minecraft block atlas does not expose its sprite map.");
        Map<Identifier, AnimationInfo> animationInfo = readAnimationInfo(atlas);
        Map<Identifier, Integer> animationFrames = new HashMap<>();
        List<Sprite> sprites = new ArrayList<>(spriteMap.size());
        long fingerprint = 0xcbf29ce484222325L;
        for (TextureAtlasSprite sprite : spriteMap.values()) {
            Identifier name = sprite.contents().name();
            int x = sprite.getX(), y = sprite.getY();
            int sw = sprite.contents().width(), sh = sprite.contents().height();
            if (x < 0 || y < 0 || sw <= 0 || sh <= 0 || x + sw > width || y + sh > height) continue;
            AnimationInfo animation = animationInfo.getOrDefault(name, new AnimationInfo(1, 1, 0));
            sprites.add(new Sprite(name, x, y, sw, sh, animation.columns(), animation.frameCount()));
            if (animation.frameCount() > 1) animationFrames.put(name, animation.currentFrame());
            fingerprint = mix(fingerprint, name.toString().hashCode());
            fingerprint = mix(fingerprint, x); fingerprint = mix(fingerprint, y);
            fingerprint = mix(fingerprint, sw); fingerprint = mix(fingerprint, sh);
            fingerprint = mix(fingerprint, animation.columns());
            fingerprint = mix(fingerprint, animation.frameCount());
        }
        return new AtlasLayout(width, height, List.copyOf(sprites), fingerprint, Map.copyOf(animationFrames));
    }

    private static Map<Identifier, AnimationInfo> readAnimationInfo(TextureAtlas atlas) {
        TextureAtlasAccessorMixin accessor = (TextureAtlasAccessorMixin) (Object) atlas;
        Iterator<?> states = accessor.caustiq$getAnimatedTextureStates().iterator();
        Map<Identifier, AnimationInfo> animations = new HashMap<>();
        for (TextureAtlasSprite sprite : accessor.caustiq$getSprites()) {
            if (!sprite.contents().isAnimated()) continue;
            if (!states.hasNext()) {
                throw new IllegalStateException("Minecraft's animated block-sprite list and animation-state list are out of sync.");
            }
            SpriteAnimationStateAccessorMixin state = (SpriteAnimationStateAccessorMixin) states.next();
            Object animation = state.caustiq$getAnimationInfo();
            SpriteAnimatedTextureAccessorMixin animated =
                    (SpriteAnimatedTextureAccessorMixin) animation;
            List<?> frameInfos = animated.caustiq$getFrames();
            int maxFrame = -1;
            for (Object frameInfo : frameInfos) {
                maxFrame = Math.max(maxFrame,
                        ((SpriteFrameInfoAccessorMixin) frameInfo).caustiq$getIndex());
            }
            int frame = state.caustiq$getFrame();
            if (frame < 0 || frame >= frameInfos.size()) continue;
            int sourceFrame = ((SpriteFrameInfoAccessorMixin) frameInfos.get(frame)).caustiq$getIndex();
            animations.put(sprite.contents().name(), new AnimationInfo(
                    Math.max(1, animated.caustiq$getFrameRowSize()), maxFrame + 1, sourceFrame));
        }
        if (states.hasNext()) {
            throw new IllegalStateException("Minecraft's animated block-sprite list contains unmatched animation states.");
        }
        return Map.copyOf(animations);
    }

    private static Map<Identifier, PbrSprite> loadPbrSprites(AtlasLayout layout, ResourceManager resources) {
        Map<Identifier, PbrSprite> loaded = new HashMap<>();
        for (Sprite sprite : layout.sprites()) {
            BufferedImage normal = readPbrImage(resources, pbrResourceId(sprite.name(), true));
            BufferedImage specular = readPbrImage(resources, pbrResourceId(sprite.name(), false));
            if (normal != null || specular != null) {
                loaded.put(sprite.name(), new PbrSprite(sprite, normal, specular));
            }
        }
        return Map.copyOf(loaded);
    }

    private static BufferedImage readPbrImage(ResourceManager resources, Identifier id) {
        Optional<Resource> resource = resources.getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream stream = resource.get().open()) {
            return ImageIO.read(stream);
        } catch (IOException | RuntimeException exception) {
            if (!warnedResourceFailure) {
                warnedResourceFailure = true;
                CaustiqClient.LOGGER.warn("Some resource-pack PBR maps could not be loaded; missing sprites use Iris-compatible defaults.", exception);
            }
            return null;
        }
    }

    private static int buildAtlas(AtlasLayout layout, Map<Identifier, PbrSprite> sprites, boolean normal) {
        long byteCount = (long) layout.width() * layout.height() * 4L;
        if (layout.width() <= 0 || layout.height() <= 0 || byteCount > MAX_ATLAS_BYTES)
            throw new IllegalStateException("Block atlas dimensions exceed CAUSTIQ's bounded PBR atlas allocation.");
        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.toIntExact(byteCount));
        int color = normal ? NORMAL_DEFAULT : SPECULAR_DEFAULT;
        byte r = (byte)(color >>> 24), g = (byte)(color >>> 16), b = (byte)(color >>> 8), a = (byte)color;
        for (int i = 0; i < layout.width() * layout.height(); i++) pixels.put(r).put(g).put(b).put(a);
        for (PbrSprite pbrSprite : sprites.values()) {
            BufferedImage image = normal ? pbrSprite.normal() : pbrSprite.specular();
            if (image != null) blit(image, pixels, layout.width(), pbrSprite.sprite(), 0);
        }
        pixels.flip();
        return upload(layout.width(), layout.height(), pixels);
    }

    private static void blit(BufferedImage image, ByteBuffer atlas, int atlasWidth, Sprite sprite, int frameIndex) {
        FrameRegion frame = frameRegion(image, sprite, frameIndex);
        for (int y = 0; y < sprite.height(); y++) {
            int sy = frame.y() + Math.min(frame.height() - 1, (int)((long)y * frame.height() / sprite.height()));
            for (int x = 0; x < sprite.width(); x++) {
                int sx = frame.x() + Math.min(frame.width() - 1, (int)((long)x * frame.width() / sprite.width()));
                int argb = image.getRGB(sx, sy);
                int dst = ((sprite.y() + y) * atlasWidth + sprite.x() + x) * 4;
                atlas.put(dst, (byte)(argb >>> 16)); atlas.put(dst + 1, (byte)(argb >>> 8));
                atlas.put(dst + 2, (byte)argb); atlas.put(dst + 3, (byte)(argb >>> 24));
            }
        }
    }

    private static FrameRegion frameRegion(BufferedImage image, Sprite sprite, int frameIndex) {
        if (sprite.frameCount() <= 1) {
            return new FrameRegion(0, 0, image.getWidth(), image.getHeight());
        }
        int columns = sprite.frameColumns();
        if (columns <= 0 || image.getWidth() % columns != 0) {
            return new FrameRegion(0, 0, image.getWidth(), image.getHeight());
        }
        int frameWidth = image.getWidth() / columns;
        if (frameWidth < sprite.width() || frameWidth % sprite.width() != 0) {
            return new FrameRegion(0, 0, image.getWidth(), image.getHeight());
        }
        int scale = frameWidth / sprite.width();
        int frameHeight = Math.multiplyExact(sprite.height(), scale);
        if (frameHeight <= 0 || image.getHeight() % frameHeight != 0) {
            return new FrameRegion(0, 0, image.getWidth(), image.getHeight());
        }
        int rows = image.getHeight() / frameHeight;
        int availableFrames = Math.multiplyExact(columns, rows);
        int frame = Math.max(0, Math.min(frameIndex, Math.min(sprite.frameCount(), availableFrames) - 1));
        return new FrameRegion((frame % columns) * frameWidth,
                (frame / columns) * frameHeight, frameWidth, frameHeight);
    }

    private static void syncAnimationFrames(Map<Identifier, Integer> current) {
        if (current.equals(cachedAnimationFrames)) return;
        boolean normalsChanged = false;
        boolean specularChanged = false;
        for (Map.Entry<Identifier, Integer> entry : current.entrySet()) {
            Integer previous = cachedAnimationFrames.get(entry.getKey());
            if (previous != null && previous.intValue() == entry.getValue()) continue;
            PbrSprite pbrSprite = cachedPbrSprites.get(entry.getKey());
            if (pbrSprite == null) continue;
            if (pbrSprite.normal() != null) {
                uploadSpriteRegion(normalsTexture, pbrSprite.normal(), pbrSprite.sprite(), entry.getValue());
                normalsChanged = true;
            }
            if (pbrSprite.specular() != null) {
                uploadSpriteRegion(specularTexture, pbrSprite.specular(), pbrSprite.sprite(), entry.getValue());
                specularChanged = true;
            }
        }
        if (normalsChanged) regenerateMipmaps(normalsTexture);
        if (specularChanged) regenerateMipmaps(specularTexture);
        cachedAnimationFrames = Map.copyOf(current);
    }

    private static void uploadSpriteRegion(int texture, BufferedImage image, Sprite sprite, int frameIndex) {
        FrameRegion frame = frameRegion(image, sprite, frameIndex);
        ByteBuffer pixels = ByteBuffer.allocateDirect(sprite.width() * sprite.height() * 4);
        for (int y = 0; y < sprite.height(); y++) {
            int sy = frame.y() + Math.min(frame.height() - 1, (int)((long)y * frame.height() / sprite.height()));
            for (int x = 0; x < sprite.width(); x++) {
                int sx = frame.x() + Math.min(frame.width() - 1, (int)((long)x * frame.width() / sprite.width()));
                int argb = image.getRGB(sx, sy);
                pixels.put((byte)(argb >>> 16)).put((byte)(argb >>> 8))
                        .put((byte)argb).put((byte)(argb >>> 24));
            }
        }
        pixels.flip();
        int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, sprite.x(), sprite.y(),
                    sprite.width(), sprite.height(), GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding);
            GL13C.glActiveTexture(active);
        }
    }

    private static void regenerateMipmaps(int texture) {
        int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding);
            GL13C.glActiveTexture(active);
        }
    }

    static int[] frameRegionForTests(BufferedImage image, int spriteWidth, int spriteHeight,
                                     int frameIndex, int frameColumns, int frameCount) {
        Sprite sprite = new Sprite(Identifier.withDefaultNamespace("test"), 0, 0,
                spriteWidth, spriteHeight, frameColumns, frameCount);
        FrameRegion frame = frameRegion(image, sprite, frameIndex);
        return new int[]{frame.x(), frame.y(), frame.width(), frame.height()};
    }

    private record PbrSprite(Sprite sprite, BufferedImage normal, BufferedImage specular) { }
    private record FrameRegion(int x, int y, int width, int height) { }

    private static int upload(int width, int height, ByteBuffer pixels) {
        int active = GL11C.glGetInteger(0x84E0), binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int texture = GL11C.glGenTextures();
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            return texture;
        } catch (RuntimeException exception) {
            GL11C.glDeleteTextures(texture); throw exception;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding); GL13C.glActiveTexture(active);
        }
    }

    private static long mix(long hash, int value) { return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L; }
    private record Sprite(Identifier name, int x, int y, int width, int height,
                          int frameColumns, int frameCount) { }
    private record AnimationInfo(int columns, int frameCount, int currentFrame) { }
    private record AtlasLayout(int width, int height, List<Sprite> sprites, long fingerprint,
                               Map<Identifier, Integer> animationFrames) { }
}
