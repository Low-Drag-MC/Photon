package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.gui.editor.ParticleEditor;
import com.lowdragmc.photon.gui.editor.ParticleProject;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote EditorEffect
 */
public class EditorEffect implements IEffect {
    final ParticleEditor editor;
    final Map<String, IParticleEmitter> cache = new HashMap<>();

    public EditorEffect(ParticleEditor particleEditor) {
        this.editor = particleEditor;
    }

    @Override
    public List<IParticleEmitter> getEmitters() {
        return editor.getCurrentProject() instanceof ParticleProject particleProject ? particleProject.getEmitters() : Collections.emptyList();
    }

    @Override
    public boolean updateEmitter(IParticleEmitter emitter) {
        return false;
    }

    @Nullable
    @Override
    public IParticleEmitter getEmitterByName(String name) {
        return cache.computeIfAbsent(name, s -> IEffect.super.getEmitterByName(name));
    }

}
