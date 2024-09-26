package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import lombok.Getter;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class Transform {
    public static double D2A = Mth.PI / 180;

    private static double toRadians(double degrees) {
        return degrees * D2A;
    }

    private static float toRadiansF(double degrees) {
        return (float) (degrees * D2A);
    }

    @Configurable
    @NumberRange(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    @Getter @Nonnull
    private Vector3f position;
    @Configurable
    @NumberRange(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    @Getter @Nonnull
    private Vector3f rotation;
    @Configurable
    @NumberRange(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    @Getter @Nonnull
    private Vector3f scale;

    private Matrix4f matrix;

    public Transform() {
        this(new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1));
    }

    public Transform(@Nonnull Vector3f position, @Nonnull Vector3f rotation, @Nonnull Vector3f scale) {
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
    }

    @ConfigSetter(field = "position")
    public void setPosition(Vector3f position) {
        this.position = position;
        this.matrix = null;
    }

    @ConfigSetter(field = "rotation")
    public void setRotation(Vector3f rotation) {
        this.rotation = rotation;
        this.matrix = null;
    }

    @ConfigSetter(field = "scale")
    public void setScale(Vector3f scale) {
        this.scale = scale;
        this.matrix = null;
    }

    public Matrix4f getMatrix() {
        if (matrix == null) {
            matrix = compose(position, rotation, scale);
        }
        return matrix;
    }

    public static Matrix4f compose(@Nullable Vector3f translation, @Nullable Vector3f rotation, @Nullable Vector3f scale) {
        Matrix4f matrix4f = new Matrix4f();
        if (translation != null) {
            matrix4f.translation(translation);
        }

        if (rotation != null) {

            matrix4f.rotate(toRadiansF(rotation.x), new Vector3f(1.0F, 0.0F, 0.0F));
            matrix4f.rotate(toRadiansF(rotation.y), new Vector3f(0.0F, 1.0F, 0.0F));
            matrix4f.rotate(toRadiansF(rotation.z), new Vector3f(0.0F, 0.0F, 1.0F));
        }

        if (scale != null) {
            matrix4f.scale(scale);
        }

        return matrix4f;
    }

}
