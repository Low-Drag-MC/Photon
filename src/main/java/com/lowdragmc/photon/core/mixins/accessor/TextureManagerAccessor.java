package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The loaded textures. 26.1's {@code getTexture} loads on a miss, so it cannot tell whether one is loaded yet. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("byPath")
    Map<Identifier, AbstractTexture> photon$byPath();
}
