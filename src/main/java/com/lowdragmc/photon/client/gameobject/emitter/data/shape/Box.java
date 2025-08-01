package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.opengl.GL11;
import oshi.util.tuples.Pair;

import java.util.List;

@LDLRegisterClient(name = "box", registry = "photon:shape")
public class Box implements IShape {
    public enum Type {
        Volume,
        Shell,
        Edge
    }

    @Getter @Setter
    @Configurable(name = "Box.emitForm")
    private Type emitFrom = Type.Volume;

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var random = particle.getRandomSource();
        scale = new Vector3f(Math.abs(scale.x), Math.abs(scale.y), Math.abs(scale.z)).mul(0.5f);
        Vector3f pos = new Vector3f(random.nextFloat() * 2 * scale.x - scale.x,
                random.nextFloat() * 2 * scale.y - scale.y,
                random.nextFloat() * 2 * scale.z - scale.z);
        if (emitFrom == Type.Shell) {
            double xy = scale.x * scale.y;
            double yz = scale.y * scale.z;
            double xz = scale.x * scale.z;
            var randomValue = random.nextDouble() * (xy + yz + xz);
            if (randomValue < xy) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
            } else if (randomValue < yz + xy) {
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
            } else {
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            }
        } else if (emitFrom == Type.Edge) {
            var randomValue = random.nextDouble() * (scale.x + scale.y + scale.z);
            if (randomValue < scale.x) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            } else if (randomValue < scale.x + scale.y) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
            } else {
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            }
        }
        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(0, 0.05f, 0), rotation));
    }

    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        scale = new Vector3f(Math.abs(scale.x), Math.abs(scale.y), Math.abs(scale.z)).mul(0.5f);
        var lines = new java.util.ArrayList<Pair<Vector3f, Vector3f>>();
        // calculate 8 corners
        Vector3f[] corners = new Vector3f[8];
        corners[0] = Vector3fHelper.rotateYXY(new Vector3f(-scale.x, -scale.y, -scale.z), rotation).add(position);
        corners[1] = Vector3fHelper.rotateYXY(new Vector3f(scale.x, -scale.y, -scale.z), rotation).add(position);
        corners[2] = Vector3fHelper.rotateYXY(new Vector3f(scale.x, -scale.y, scale.z), rotation).add(position);
        corners[3] = Vector3fHelper.rotateYXY(new Vector3f(-scale.x, -scale.y, scale.z), rotation).add(position);
        corners[4] = Vector3fHelper.rotateYXY(new Vector3f(-scale.x, scale.y, -scale.z), rotation).add(position);
        corners[5] = Vector3fHelper.rotateYXY(new Vector3f(scale.x, scale.y, -scale.z), rotation).add(position);
        corners[6] = Vector3fHelper.rotateYXY(new Vector3f(scale.x, scale.y, scale.z), rotation).add(position);
        corners[7] = Vector3fHelper.rotateYXY(new Vector3f(-scale.x, scale.y, scale.z), rotation).add(position);
        // bottom face
        lines.add(new Pair<>(new Vector3f(corners[0]), new Vector3f(corners[1])));
        lines.add(new Pair<>(new Vector3f(corners[1]), new Vector3f(corners[2])));
        lines.add(new Pair<>(new Vector3f(corners[2]), new Vector3f(corners[3])));
        lines.add(new Pair<>(new Vector3f(corners[3]), new Vector3f(corners[0])));
        // top face
        lines.add(new Pair<>(new Vector3f(corners[4]), new Vector3f(corners[5])));
        lines.add(new Pair<>(new Vector3f(corners[5]), new Vector3f(corners[6])));
        lines.add(new Pair<>(new Vector3f(corners[6]), new Vector3f(corners[7])));
        lines.add(new Pair<>(new Vector3f(corners[7]), new Vector3f(corners[4])));
        // vertical edges
        lines.add(new Pair<>(new Vector3f(corners[0]), new Vector3f(corners[4])));
        lines.add(new Pair<>(new Vector3f(corners[1]), new Vector3f(corners[5])));
        lines.add(new Pair<>(new Vector3f(corners[2]), new Vector3f(corners[6])));
        lines.add(new Pair<>(new Vector3f(corners[3]), new Vector3f(corners[7])));
        return lines;
    }
}
