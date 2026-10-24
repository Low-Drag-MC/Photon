package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-side block changes: the voxelised shadow occluders and the collision snapshots follow them. */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void photon$markVoxelDirty(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) {
            DynamicLightManager.blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // every real change whatever its flags; sendBlockUpdated is skipped for some (pistons among them)
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void photon$markCollisionDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        CollisionWorld.blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
    }
}
