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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

    private NvidiumPbrTextureAtlases() { }

    public static int normalsTexture() { ensureCurrent(); return normalsTexture; }
    public static int specularTexture() { ensureCurrent(); return specularTexture; }

    static Identifier pbrResourceId(Identifier sprite, boolean normal) {
        String path = sprite.getPath() + (normal ? "_n" : "_s");
        String resourcePath = path.startsWith("optifine/cit/")
                ? path + ".png" : "textures/" + path + ".png";
        return Identifier.fromNamespaceAndPath(sprite.getNamespace(), resourcePath);
    }

    public static void destroy() {
        if (normalsTexture != 0) GL11C.glDeleteTextures(normalsTexture);
        if (specularTexture != 0) GL11C.glDeleteTextures(specularTexture);
        normalsTexture = 0; specularTexture = 0;
        cachedAtlas = null; cachedResources = null; cachedFingerprint = Long.MIN_VALUE;
    }

    private static void ensureCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        ResourceManager resources = minecraft.getResourceManager();
        AtlasLayout layout = readAtlasLayout(atlas);
        if (atlas == cachedAtlas && resources == cachedResources && layout.fingerprint() == cachedFingerprint
                && normalsTexture != 0 && specularTexture != 0) return;
        int nextNormals = 0, nextSpecular = 0;
        try {
            nextNormals = buildAtlas(layout, resources, true);
            nextSpecular = buildAtlas(layout, resources, false);
        } catch (RuntimeException exception) {
            if (nextNormals != 0) GL11C.glDeleteTextures(nextNormals);
            if (nextSpecular != 0) GL11C.glDeleteTextures(nextSpecular);
            throw exception;
        }
        if (normalsTexture != 0) GL11C.glDeleteTextures(normalsTexture);
        if (specularTexture != 0) GL11C.glDeleteTextures(specularTexture);
        normalsTexture = nextNormals; specularTexture = nextSpecular;
        cachedAtlas = atlas; cachedResources = resources; cachedFingerprint = layout.fingerprint();
    }

    private static AtlasLayout readAtlasLayout(TextureAtlas atlas) {
        TextureAtlasAccessorMixin accessor = (TextureAtlasAccessorMixin) (Object) atlas;
        int width = accessor.caustiq$getWidth();
        int height = accessor.caustiq$getHeight();
        Map<Identifier, TextureAtlasSprite> spriteMap = accessor.caustiq$getTexturesByName();
        if (spriteMap == null) throw new IllegalStateException("Minecraft block atlas does not expose its sprite map.");
            List<Sprite> sprites = new ArrayList<>(spriteMap.size());
            long fingerprint = 0xcbf29ce484222325L;
            for (TextureAtlasSprite sprite : spriteMap.values()) {
                Identifier name = sprite.contents().name();
                int x = sprite.getX(), y = sprite.getY();
                int sw = sprite.contents().width(), sh = sprite.contents().height();
                if (x < 0 || y < 0 || sw <= 0 || sh <= 0 || x + sw > width || y + sh > height) continue;
                sprites.add(new Sprite(name, x, y, sw, sh));
                fingerprint = mix(fingerprint, name.toString().hashCode());
                fingerprint = mix(fingerprint, x); fingerprint = mix(fingerprint, y);
                fingerprint = mix(fingerprint, sw); fingerprint = mix(fingerprint, sh);
            }
            return new AtlasLayout(width, height, List.copyOf(sprites), fingerprint);
    }

    private static int buildAtlas(AtlasLayout layout, ResourceManager resources, boolean normal) {
        long byteCount = (long) layout.width() * layout.height() * 4L;
        if (layout.width() <= 0 || layout.height() <= 0 || byteCount > MAX_ATLAS_BYTES)
            throw new IllegalStateException("Block atlas dimensions exceed CAUSTIQ's bounded PBR atlas allocation.");
        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.toIntExact(byteCount));
        int color = normal ? NORMAL_DEFAULT : SPECULAR_DEFAULT;
        byte r = (byte)(color >>> 24), g = (byte)(color >>> 16), b = (byte)(color >>> 8), a = (byte)color;
        for (int i = 0; i < layout.width() * layout.height(); i++) pixels.put(r).put(g).put(b).put(a);
        for (Sprite sprite : layout.sprites()) {
            Optional<Resource> resource = resources.getResource(pbrResourceId(sprite.name(), normal));
            if (resource.isEmpty()) continue;
            try (InputStream stream = resource.get().open()) {
                BufferedImage image = ImageIO.read(stream);
                if (image != null) blit(image, pixels, layout.width(), sprite);
            } catch (IOException | RuntimeException exception) {
                if (!warnedResourceFailure) {
                    warnedResourceFailure = true;
                    CaustiqClient.LOGGER.warn("Some resource-pack PBR maps could not be loaded; missing sprites use Iris-compatible defaults.", exception);
                }
            }
        }
        pixels.flip();
        return upload(layout.width(), layout.height(), pixels);
    }

    private static void blit(BufferedImage image, ByteBuffer atlas, int atlasWidth, Sprite sprite) {
        int fw = image.getWidth(), fh = image.getHeight();
        if (fw == sprite.width() && fh > sprite.height() && fh % sprite.height() == 0) fh = sprite.height();
        else if (fh == sprite.height() && fw > sprite.width() && fw % sprite.width() == 0) fw = sprite.width();
        for (int y = 0; y < sprite.height(); y++) {
            int sy = Math.min(fh - 1, (int)((long)y * fh / sprite.height()));
            for (int x = 0; x < sprite.width(); x++) {
                int sx = Math.min(fw - 1, (int)((long)x * fw / sprite.width()));
                int argb = image.getRGB(sx, sy);
                int dst = ((sprite.y() + y) * atlasWidth + sprite.x() + x) * 4;
                atlas.put(dst, (byte)(argb >>> 16)); atlas.put(dst + 1, (byte)(argb >>> 8));
                atlas.put(dst + 2, (byte)argb); atlas.put(dst + 3, (byte)(argb >>> 24));
            }
        }
    }

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
    private record Sprite(Identifier name, int x, int y, int width, int height) { }
    private record AtlasLayout(int width, int height, List<Sprite> sprites, long fingerprint) { }
}
