package com.lowdragmc.photon.client;

import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.fx.BlockEffect;
import com.lowdragmc.photon.client.fx.EntityEffect;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.core.mixins.accessor.ParticleEngineAccessor;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.lowdragmc.lowdraglib.client.ClientCommands.createLiteral;

/**
 * @author KilaBash
 * @date 2023/2/9
 * @implNote ClientCommands
 */
@Environment(EnvType.CLIENT)
public class ClientCommands {

    @SuppressWarnings("unchecked")
    public static <S> List<LiteralArgumentBuilder<S>> createClientCommands() {
        return List.of(
                (LiteralArgumentBuilder<S>) createLiteral("photon_client")
                        .then(createLiteral("clear_particles")
                                .executes(context -> {
                                    if (Minecraft.getInstance().particleEngine instanceof ParticleEngineAccessor accessor) {
                                        accessor.getParticles().entrySet().removeIf(entry -> entry.getKey() instanceof PhotonParticleRenderType);
                                    }
                                    EntityEffect.CACHE.clear();
                                    BlockEffect.CACHE.clear();
                                    return 1;
                                }))
                        .then(createLiteral("clear_client_fx_cache")
                                .executes(context -> {
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.sendSystemMessage(Component.literal("clear client cache fx: " + FXHelper.clearCache()));
                                    } else {
                                        FXHelper.clearCache();
                                    }
                                    return 1;
                                }))
        );
    }
}
