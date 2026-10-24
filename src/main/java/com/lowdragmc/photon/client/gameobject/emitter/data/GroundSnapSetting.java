package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.ClipContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Drops each new particle onto the surface below its spawn point. Game thread, once per particle. */
@Getter
@Setter
public class GroundSnapSetting extends ToggleGroup {

    public enum Direction {
        /** straight down in the world */
        WorldDown,
        /** the emitter's own -Y, for emitters on walls and ceilings */
        EmitterDown
    }

    @Configurable(name = "GroundSnapSetting.direction", tips = "photon.emitter.config.shape.groundSnap.direction")
    protected Direction direction = Direction.WorldDown;
    @Configurable(name = "GroundSnapSetting.maxDistance", tips = "photon.emitter.config.shape.groundSnap.maxDistance")
    @ConfigNumber(range = {0, 256}, wheel = 0.5)
    protected float maxDistance = 8;
    @Configurable(name = "GroundSnapSetting.searchAbove", tips = "photon.emitter.config.shape.groundSnap.searchAbove")
    @ConfigNumber(range = {0, 64}, wheel = 0.5)
    protected float searchAbove = 1;
    @Configurable(name = "GroundSnapSetting.offset", tips = "photon.emitter.config.shape.groundSnap.offset")
    @ConfigNumber(range = {-16, 16}, wheel = 0.05)
    protected float offset = 0;
    @Configurable(name = "GroundSnapSetting.includeFluids", tips = "photon.emitter.config.shape.groundSnap.includeFluids")
    protected boolean includeFluids = false;
    @Configurable(name = "GroundSnapSetting.alignToNormal", tips = "photon.emitter.config.shape.groundSnap.alignToNormal")
    protected boolean alignToNormal = false;
    @Configurable(name = "GroundSnapSetting.discardWithoutGround", tips = "photon.emitter.config.shape.groundSnap.discardWithoutGround")
    protected boolean discardWithoutGround = false;

    /** Moves a freshly set-up particle onto the ground, or removes it when there is none and it should not live. */
    public void snap(TileParticle particle, IParticleEmitter emitter) {
        var level = emitter.getLevel();
        if (level == null) return;
        var position = particle.getSimPosWithoutNoise().mulPosition(particle.getSpaceTransform());
        var down = direction == Direction.WorldDown ? new Vector3f(0, -1, 0)
                : emitter.transform().localToWorldMatrix().transformDirection(new Vector3f(0, -1, 0)).normalize();
        var world = CollisionWorld.of(level);
        var contact = new Contact();
        float above = Math.max(0, searchAbove);
        int result = probe(world, position, down, above, contact);
        // the search started inside a block (under a low ceiling, in a tree): search from the spawn point
        if (result == CollisionQuery.HIT && contact.t == 0 && above > 0) result = probe(world, position, down, 0, contact);
        if (result != CollisionQuery.HIT) {
            if (discardWithoutGround) particle.setRemoved(true);
            return;
        }
        var ground = new Vector3f((float) (contact.px + contact.nx * offset), (float) (contact.py + contact.ny * offset),
                (float) (contact.pz + contact.nz * offset));
        particle.setSimPos(ground.mulPosition(particle.getSpaceTransformInverse()), true);
        if (alignToNormal) {
            // what the shape aimed "up" (away from the probe) now leaves the surface along its normal
            var turn = new Quaternionf().rotationTo(-down.x, -down.y, -down.z,
                    (float) contact.nx, (float) contact.ny, (float) contact.nz);
            var velocity = particle.simDirToWorld(particle.getStoredVelocity());
            particle.setInternalVelocity(particle.worldDirToSim(turn.transform(velocity)));
        }
    }

    private int probe(CollisionWorld world, Vector3f position, Vector3f down, float above, Contact contact) {
        float fromX = position.x - down.x * above, fromY = position.y - down.y * above, fromZ = position.z - down.z * above;
        float reach = above + Math.max(0, maxDistance);
        return CollisionQuery.get().raycast(world, fromX, fromY, fromZ, fromX + down.x * reach, fromY + down.y * reach,
                fromZ + down.z * reach, ClipContext.Block.COLLIDER,
                includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, contact);
    }
}
