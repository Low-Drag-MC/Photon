package com.lowdragmc.photon.gui;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.photon.gui.editor.ParticleEditor;
import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

public class ParticleEditorFactory extends UIFactory<ParticleEditorFactory> implements IUIHolder {

	public static final ParticleEditorFactory INSTANCE = new ParticleEditorFactory();

	private ParticleEditorFactory(){

	}

	@Override
	protected ModularUI createUITemplate(ParticleEditorFactory holder, Player entityPlayer) {
		return createUI(entityPlayer);
	}

	@Override
	protected ParticleEditorFactory readHolderFromSyncData(FriendlyByteBuf syncData) {
		return this;
	}

	@Override
	protected void writeHolderToSyncData(FriendlyByteBuf syncData, ParticleEditorFactory holder) {

	}

	@Override
	public ModularUI createUI(Player entityPlayer) {
		return new ModularUI(this, entityPlayer)
				.widget(new ParticleEditor(LDLib.location));
	}

	@Override
	public boolean isInvalid() {
		return false;
	}

	@Override
	public boolean isRemote() {
		return LDLib.isRemote();
	}

	@Override
	public void markAsDirty() {

	}
}
