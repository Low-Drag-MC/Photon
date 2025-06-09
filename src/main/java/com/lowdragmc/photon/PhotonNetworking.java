package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.networking.INetworking;
import com.lowdragmc.lowdraglib2.networking.LDLNetworking;
import com.lowdragmc.lowdraglib2.networking.both.PacketRPCBlockEntity;
import com.lowdragmc.lowdraglib2.networking.c2s.CPacketUIClientAction;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntity;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketUIOpen;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketUIWidgetUpdate;
import com.lowdragmc.photon.command.BlockEffectCommand;
import com.lowdragmc.photon.command.EntityEffectCommand;
import com.lowdragmc.photon.command.RemoveBlockEffectCommand;
import com.lowdragmc.photon.command.RemoveEntityEffectCommand;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class PhotonNetworking {

    public static final INetworking NETWORK = LDLNetworking.createNetworking(new ResourceLocation(Photon.MOD_ID, "networking"), "0.0.1");

    public static void init() {
        NETWORK.registerS2C(BlockEffectCommand.class);
        NETWORK.registerS2C(EntityEffectCommand.class);
        NETWORK.registerS2C(RemoveBlockEffectCommand.class);
        NETWORK.registerS2C(RemoveEntityEffectCommand.class);
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registar = event.registrar(LDLib2.MOD_ID);

        registar.playToClient(SPacketUIOpen.TYPE, SPacketUIOpen.CODEC, SPacketUIOpen::execute);
        registar.playToClient(SPacketUIWidgetUpdate.TYPE, SPacketUIWidgetUpdate.CODEC, SPacketUIWidgetUpdate::execute);
        registar.playToClient(SPacketAutoSyncBlockEntity.TYPE, SPacketAutoSyncBlockEntity.CODEC, SPacketAutoSyncBlockEntity::execute);

        registar.playToServer(CPacketUIClientAction.TYPE, CPacketUIClientAction.CODEC, CPacketUIClientAction::execute);

        registar.playBidirectional(PacketRPCBlockEntity.TYPE, PacketRPCBlockEntity.CODEC, PacketRPCBlockEntity::execute);
    }
}
