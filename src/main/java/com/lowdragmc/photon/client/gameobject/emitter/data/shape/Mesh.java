package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import oshi.util.tuples.Pair;

import java.util.List;

@LDLRegisterClient(name = "mesh", registry = "photon:shape")
public class Mesh implements IShape {
    public enum Type {
        Vertex,
        Edge,
        Triangle
    }

    @Getter
    @Setter
    @Configurable(name = "Mesh.type", tips = "photon.emitter.config.shape.mesh.type")
    private Type type = Type.Triangle;

    @Getter
    @Setter
    @Persisted
    private MeshData meshData = new MeshData();

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        Vector3f pos = null;
        var random = particle.getRandomSource();
        var t = random.nextFloat();
        var meshData = getMeshData();
        if (type == Type.Vertex) {
            pos = meshData.getRandomVertex(t);
            if (pos != null) {
                pos = new Vector3f(pos);
            }
        } else if (type == Type.Edge) {
            var edge = meshData.getRandomEdge(t);
            if (edge != null) {
                pos = new Vector3f(edge.b).sub(edge.a).mul(random.nextFloat()).add(edge.a);
            }
        } else if (type == Type.Triangle) {
            var triangle = meshData.getRandomTriangle(t);
            if (triangle != null) {
                var sqrtR = (float) Math.sqrt(random.nextFloat());
                var A = (1 - sqrtR);
                var r2 = random.nextFloat();
                var B = (sqrtR * (1 - r2));
                var C = (sqrtR * r2);
                var x = A * triangle.a.x + B * triangle.b.x + C * triangle.c.x;
                var y = A * triangle.a.y + B * triangle.b.y + C * triangle.c.y;
                var z = A * triangle.a.z + B * triangle.b.z + C * triangle.c.z;
                pos = new Vector3f(x, y, z);
            }
        }
        if (pos != null) {
            pos.mul(scale);
            particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
            particle.setInternalVelocity(new Vector3f(0, 0, 0));
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IShape.super.buildConfigurator(father);
        father.addConfigurator(new MeshDataConfigurator("mesh", this::getMeshData, this::setMeshData, new MeshData(), true));
    }

    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        return getMeshData().getEdges().stream().map(edge -> {
            var a = new Vector3f(edge.a);
            var b = new Vector3f(edge.b);
            a.mul(scale);
            b.mul(scale);
            a = Vector3fHelper.rotateYXY(a, rotation).add(position);
            b = Vector3fHelper.rotateYXY(b, rotation).add(position);
            return new Pair<>(a, b);
        }).toList();
    }
}
