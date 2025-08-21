package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.lowdragmc.photon.gui.editor.FXProject;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

import java.util.function.BiFunction;

public final class PhotonFXProjectDataFixer {
    private static final BiFunction<Integer, Schema, Schema> SAME = Schema::new;
    public static final PhotonFXProjectDataFixer INSTANCE = new PhotonFXProjectDataFixer();
    private final DataFixer dataFixer;
    
    public PhotonFXProjectDataFixer() {
        DataFixerBuilder builder = new DataFixerBuilder(FXProject.VERSION);

        var schema1 = builder.addSchema(1, PhotonSchemas.V1::new);
        var schema2 = builder.addSchema(2, SAME);
        var schema3 = builder.addSchema(3, SAME);

        builder.addFixer(new MaterialToRendererMaterialsFix(schema2));
        builder.addFixer(new UVAnimationTilesFix(schema3));
        this.dataFixer = builder.build().fixer();
    }
    
    public CompoundTag applyFixes(int version, int targetVersion, CompoundTag oldData) {
        // convert to dynamic
        var dynamic = new Dynamic<>(NbtOps.INSTANCE, oldData);
        
        // apply fixer
        Dynamic<?> fixed = dataFixer.update(
                PhotonReferences.FX_PROJECT,
                dynamic,
                version,
                targetVersion
        );
        return (CompoundTag) fixed.getValue();
    }
}
