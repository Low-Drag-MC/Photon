package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.light.DynamicLightManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client-side block change passes through here; the voxelised shadow occluders follow it. */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void photon$markVoxelDirty(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) {
            DynamicLightManager.blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
