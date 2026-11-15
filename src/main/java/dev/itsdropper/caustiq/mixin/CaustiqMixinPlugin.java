package dev.itsdropper.caustiq.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps Nvidium-specific hooks dormant when the optional accelerated renderer
 * is not installed. The common client and shader-pack validation path remains
 * loadable on the vanilla renderer.
 */
public final class CaustiqMixinPlugin implements IMixinConfigPlugin {
    private static final String NVIDIUM_MIXIN_PREFIX = "Nvidium";

    @Override
    public void onLoad(String mixinPackage) { }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return !simpleName.startsWith(NVIDIUM_MIXIN_PREFIX)
                || FabricLoader.getInstance().isModLoaded("nvidium");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) { }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) { }
}
