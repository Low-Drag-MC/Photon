package com.lowdragmc.photon.client.gameobject.particle.aratrail;

import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.Float2ObjectFunction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.*;

import javax.annotation.Nullable;
import java.lang.Math;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;


public class AraTrailParticle implements IParticle {
    public final static float EPSILON = 0.00001f;

    public final IParticleEmitter emitter;
    public final AraTrailConfig config;

    // runtime
    private float initialThickness = 1;
    private Vector4f initialColor = new Vector4f(1);
    private Vector3f initialVelocity = new Vector3f();
    @Getter
    private final RandomSource randomSource;
    @Getter
    private final ElasticArray<Point> points = new ElasticArray<>(Point.class);
    @Getter
    private final ElasticArray<Point> renderablePoints = new ElasticArray<>(Point.class);
    @Getter
    private final ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter @Setter
    protected boolean isRemoved;
    @Getter @Setter
    protected boolean dieWhenAllTailsRemoved = true;

    private final IntList discontinuities = new IntArrayList();

    @Setter
    private Vector3f velocity = new Vector3f();
    @Setter
    private Vector3f prevPosition = new Vector3f();
    @Getter @Setter
    private float accumTime = 0;

    private final List<Vector3f> vertices = new ArrayList<>();
    private final List<Vector3f> normals = new ArrayList<>();
    private final List<Vector4f> tangents = new ArrayList<>();
    private final List<Vector2f> uvs = new ArrayList<>();
    private final List<Vector4f> vertColors = new ArrayList<>();
    private final IntList tris = new IntArrayList();

    private Vector3f nextV = new Vector3f(0);
    private Vector3f prevV = new Vector3f(0);
    private Vector3f vertex = new Vector3f(0);
    private Vector3f normal = new Vector3f(0);
    private Vector3f bitangent = new Vector3f(0);
    private Vector4f tangent = new Vector4f(0, 0, 0, 1);
    private Vector4f texTangent = new Vector4f(0);
    private Vector2f uv = new Vector2f(0);
    private Vector4f color;

    // for trails setting
    @Getter @Setter @Nullable
    private Runnable onUpdate;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldPositionSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldForwardSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldUpSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldRightSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector4f> colorMultiplierSupplier;
    @Getter @Setter @Nullable
    private Supplier<Float> lifetimeSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Float> thicknessMultiplierSupplier;

    private Vector3f worldPosition = new Vector3f();
    private Vector3f worldForward = new Vector3f();
    private Vector3f worldUp = new Vector3f();
    private Vector3f worldRight = new Vector3f();
    @Getter
    private Vector4f colorMultiplier = new Vector4f(1, 1, 1, 1);
    @Getter
    private float thicknessMultiplier = 1;
    @Getter
    private float lifeTime = 1;

    public AraTrailParticle(IParticleEmitter emitter, AraTrailConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        setup();
    }

    @Override
    public PhotonFXRenderPass getRenderType() {
        return config.particleRenderType;
    }

    @Override
    public boolean isAlive() {
        if (isRemoved) {
            return dieWhenAllTailsRemoved && points.size() > 1;
        }
        return true;
    }

    @Override
    public float getT() {
        return emitter.getT();
    }

    @Override
    public float getT(float partialTicks) {
        return emitter.getT(partialTicks);
    }

    @Override
    public float getMemRandom(Object object) {
        return getMemRandom(object, RandomSource::nextFloat);
    }

    @Override
    public float getMemRandom(Object object, Function<RandomSource, Float> randomFunc) {
        var value = memRandom.get(object);
        if (value == null) return memRandom.computeIfAbsent(object, o -> randomFunc.apply(randomSource));
        return value;
    }

    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    public Vector3f getPrevPosition() {
        return new Vector3f(prevPosition);
    }

    private float getDeltaTime() {
        return emitter.getDeltaTime() / 20;
//        return timescale == Timescale.Unscaled ? Time.getUnscaledDeltaTime() : Time.getDeltaTime();
    }

    private float getFixedDeltaTime() {
//        return timescale == Timescale.Unscaled ? Time.getFixedUnscaledDeltaTime() : Time.getFixedDeltaTime();
        return 0.05f; // 1 / 20f;
    }

    public Matrix4f getWorldToTrail() {
        return switch (config.space) {
            case World -> new Matrix4f(); // identity matrix
            case Local -> getTransform().worldToLocalMatrix();
            case Custom -> {
                var transform = config.customSpace.getTransform(emitter.getScene());
                yield transform != null ? transform.worldToLocalMatrix() : new Matrix4f();
            }
        };
    }

    public Transform getTransform() {
        return emitter.transform();
    }

    private void updateDynamicData(float partialTicks) {
        var transform = getTransform();
        this.worldPosition = worldPositionSupplier == null ? transform.position() : worldPositionSupplier.get(partialTicks);
        this.worldForward = worldForwardSupplier == null ? transform.forward() : worldForwardSupplier.get(partialTicks);
        this.worldUp = worldUpSupplier == null ? transform.up() : worldUpSupplier.get(partialTicks);
        this.worldRight = worldRightSupplier == null ? transform.right() : worldRightSupplier.get(partialTicks);
        this.colorMultiplier = colorMultiplierSupplier == null ? new Vector4f(1) : colorMultiplierSupplier.get(partialTicks);
        this.thicknessMultiplier = thicknessMultiplierSupplier == null ? 1 : thicknessMultiplierSupplier.get(partialTicks);
        this.lifeTime = lifetimeSupplier == null ? config.time : lifetimeSupplier.get();
    }
    
    public Vector3f getWorldPosition() {
        return new Vector3f(worldPosition);
    }
    
    public Vector3f getWorldForward() {
        return new Vector3f(worldForward);
    }
    
    public Vector3f getWorldUp() {
        return new Vector3f(worldUp);
    }
    
    public Vector3f getWorldRight() {
        return new Vector3f(worldRight);
    }

    private void setup() {
        // initialize previous position, for correct velocity estimation in the first frame:
        warmup();
        prevPosition = getWorldPosition();
        velocity = new Vector3f();
        initialVelocity = config.initialVelocity;
        initialThickness = config.initialThickness;
        initialColor = new Vector4f(
                ColorUtils.red(config.initialColor),
                ColorUtils.green(config.initialColor),
                ColorUtils.blue(config.initialColor),
                ColorUtils.alpha(config.initialColor)
        );
    }

    /**
     * Removes all points in the trail, effectively removing any rendered trail segments.
     */
    public void clear() {
        points.clear();
    }

    private void updateVelocity(float deltaTime) {
        if (deltaTime > 0) {
            Vector3f deltaPosition = getWorldPosition().sub(prevPosition);
            Vector3f currentVelocity = deltaPosition.div(deltaTime);
            velocity = velocity.lerp(currentVelocity, config.physicsSetting.velocitySmoothing);
        }

        prevPosition = getWorldPosition();
    }

    @Override
    public void updateTick() {
        updatePhysics();
    }

    /**
     * Updates point physics.
     */
    private void updatePhysics() {
        if (onUpdate != null) onUpdate.run();
        if (!config.physicsSetting.isEnable())
            return;
        physicsStep(0.02f);
    }

    private void emissionStep(float time) {
        // Acumulate the amount of time passed:
        accumTime += time;

        // If enough time has passed since the last emission (>= timeInterval), consider emitting new points.
        if (accumTime >= config.timeInterval) {
            if (config.emit) {
                var position = getWorldToTrail().transformPosition(getWorldPosition());

                // If there's less than 2 points, or if the last 2 points are too far apart, spawn a new one:
                if (points.size() < 1 || (
                        position.distance(points.get(points.size() - 1).position) >= config.minDistance
                )) {
                    emitPoint(position);
                    accumTime = 0;
                }
            }
        }
    }

    private void warmup() {
        if (!config.physicsSetting.isEnable()) return;
        float simulatedTime = config.physicsSetting.warmup;
        var fixedDeltaTime = getFixedDeltaTime();
        updateDynamicData(0);
        while (simulatedTime > fixedDeltaTime) {
            physicsStep(fixedDeltaTime);
            emissionStep(fixedDeltaTime);
            snapLastPointToTransform();
            updatePointsLifecycle(fixedDeltaTime);
            simulatedTime -= fixedDeltaTime;
        }
    }

    private void physicsStep(float timestep) {
        float velocity_scale = (float) Math.pow(1 - Mth.clamp(config.physicsSetting.damping, 0, 1), timestep);

        for (Point point : points) {
            // apply gravity and external forces:
            point.velocity.add(new Vector3f(config.physicsSetting.gravity).mul(timestep));
            point.velocity.mul(velocity_scale);

            // integrate velocity:
            point.position.add(new Vector3f(point.velocity).mul(timestep));
        }
    }


    public void emitPoint(Vector3f position) {
        emitPoint(position, false);
    }
    /**
     * Spawns a new point in the trail.
     */
    public void emitPoint(Vector3f position, boolean skipLast) {
        // Adjust the current end of the trail, if any:
//        if (adjustEnd && points.size() > 1) {
//            Point lastPoint = points[points.Count - 1];
//            lastPoint.position = (position + points[points.Count - 2].position) * 0.5f;
//            points[points.Count - 1] = lastPoint;
//        }

        float texcoord = 0;

        // if there's a previous point in the trail, use its texcoord to calculate ours.
        if (!points.isEmpty())
            texcoord = points.getLast().texcoord + position.distance(points.getLast().position);

        var worldToTrail = getWorldToTrail();
        var nrm = worldToTrail.transformDirection(getWorldForward());
        var tgt = worldToTrail.transformDirection(getWorldRight());
        var point = new Point(position, new Vector3f(velocity).mul(config.physicsSetting.inertia).add(initialVelocity),
                tgt, nrm, initialColor, initialThickness, texcoord, lifeTime);
        if (skipLast && points.size() > 1) {
            points.add(points.size() - 1, point);
        } else {
            points.add(point);
        }
    }

    /**
     * Makes sure the first point is always at the transform's center, and that its orientation matches it.
     */
    private void snapLastPointToTransform() {
        // Last point always coincides with transform:
        if (!points.isEmpty()) {
            Point lastPoint = points.getLast();

            // if we are not emitting, the last point is a discontinuity.
            if (!config.emit)
                lastPoint.discontinuous = true;

            // if the point is not discontinuous, move and orient it according to the transform.
            if (!lastPoint.discontinuous)
            {
                var worldToTrail = getWorldToTrail();
                lastPoint.position = worldToTrail.transformPosition(getWorldPosition());
                lastPoint.normal = worldToTrail.transformDirection(getWorldForward());
                lastPoint.tangent = worldToTrail.transformDirection(getWorldRight());

                // if there's a previous point in the trail, use its texcoord to calculate ours.
                if (points.size() > 1) {
                    lastPoint.texcoord = points.get(points.size() - 2).texcoord +
                            lastPoint.position.distance(points.get(points.size() - 2).position);
                }
            }

            points.set(points.size() - 1, lastPoint);
        }
    }

    /**
     * Updated trail lifetime and removes dead points.
     */
    private void updatePointsLifecycle(float deltaTime) {
        for (int i = points.size() - 1; i >= 0; --i)
        {

            var point = points.get(i);
            point.life -= deltaTime;

            if (point.life <= 0)
            {

                // Unsmoothed trails delete points as soon as they die.
                if (config.smoothness <= 1)
                {
                    points.removeAt(i);
                }
                // Smoothed trails however, should wait until the next 2 points are dead too. This ensures spline continuity.
                else
                {
                    if (points.get(Math.min(i + 1, points.size() - 1)).life <= 0 &&
                            points.get(Math.min(i + 2, points.size() - 1)).life <= 0)
                        points.removeAt(i);
                }

            }
        }
    }

    /**
     * Clears all mesh data: vertices, normals, tangents, etc. This is called at the beginning of UpdateTrailMesh().
     */
    private void clearMeshData() {
        vertices.clear();
        normals.clear();
        tangents.clear();
        uvs.clear();
        vertColors.clear();
        tris.clear();
    }

    private ElasticArray<Point> getRenderablePoints(int start, int end) {
        renderablePoints.clear();

        if (config.smoothness <= 1) {
            for (int i = start; i <= end; ++i)
                renderablePoints.add(points.get(i));
            return renderablePoints;
        }

        var data = points.getData();

        // calculate sample size in normalized coordinates:
        float samplesize = 1.0f / config.smoothness;

        Point interpolated = new Point(new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector4f(1, 1, 1, 1), 0, 0, 0);

        for (int i = start; i < end; ++i) {

            int i_1 = i == start ? start : i - 1;
            int i2 = i == end - 1 ? end : i + 2;
            int i1 = i + 1;

            float pax = data[i_1].position.x(), pay = data[i_1].position.y(), paz = data[i_1].position.z();
            float vax = data[i_1].velocity.x(), vay = data[i_1].velocity.y(), vaz = data[i_1].velocity.z();
            float tax = data[i_1].tangent.x(), tay = data[i_1].tangent.y(), taz = data[i_1].tangent.z();
            float nax = data[i_1].normal.x(), nay = data[i_1].normal.y(), naz = data[i_1].normal.z();
            float cax = data[i_1].color.x();
            float cay = data[i_1].color.y();
            float caz = data[i_1].color.z();
            float caw = data[i_1].color.w();

            float pbx = data[i].position.x(), pby = data[i].position.y(), pbz = data[i].position.z();
            float vbx = data[i].velocity.x(), vby = data[i].velocity.y(), vbz = data[i].velocity.z();
            float tbx = data[i].tangent.x(), tby = data[i].tangent.y(), tbz = data[i].tangent.z();
            float nbx = data[i].normal.x(), nby = data[i].normal.y(), nbz = data[i].normal.z();
            float cbx = data[i].color.x();
            float cby = data[i].color.y();
            float cbz = data[i].color.z();
            float cbw = data[i].color.w();

            float pcx = data[i1].position.x(), pcy = data[i1].position.y(), pcz = data[i1].position.z();
            float vcx = data[i1].velocity.x(), vcy = data[i1].velocity.y(), vcz = data[i1].velocity.z();
            float tcx = data[i1].tangent.x(), tcy = data[i1].tangent.y(), tcz = data[i1].tangent.z();
            float ncx = data[i1].normal.x(), ncy = data[i1].normal.y(), ncz = data[i1].normal.z();
            float ccx = data[i1].color.x();
            float ccy = data[i1].color.y();
            float ccz = data[i1].color.z();
            float ccw = data[i1].color.w();

            float pdx = data[i2].position.x(), pdy = data[i2].position.y(), pdz = data[i2].position.z();
            float vdx = data[i2].velocity.x(), vdy = data[i2].velocity.y(), vdz = data[i2].velocity.z();
            float tdx = data[i2].tangent.x(), tdy = data[i2].tangent.y(), tdz = data[i2].tangent.z();
            float ndx = data[i2].normal.x(), ndy = data[i2].normal.y(), ndz = data[i2].normal.z();
            float cdx = data[i2].color.x();
            float cdy = data[i2].color.y();
            float cdz = data[i2].color.z();
            float cdw = data[i2].color.w();

            for (int j = 0; j < config.smoothness; ++j)
            {
                float t = j * samplesize;

                if (Float.isInfinite(data[i_1].life) || Float.isInfinite(data[i].life) ||
                        Float.isInfinite(data[i1].life) || Float.isInfinite(data[i2].life))
                    interpolated.life = Float.POSITIVE_INFINITY;
                else
                    interpolated.life = Point.catmullRom(data[i_1].life, data[i].life, data[i1].life, data[i2].life, t);

                float dx = pcx - pbx;
                float dy = pcy - pby;
                float dz = pcz - pbz;
                if (dx * dx + dy * dy + dz * dz < config.smoothingDistance * config.smoothingDistance)
                {
                    renderablePoints.add(data[i]);
                    break;
                }

                // only if the interpolated point is alive, we add it to the list of points to render.
                if (interpolated.life > 0)
                {

                    interpolated.position.x = Point.catmullRom(pax, pbx, pcx, pdx, t);
                    interpolated.position.y = Point.catmullRom(pay, pby, pcy, pdy, t);
                    interpolated.position.z = Point.catmullRom(paz, pbz, pcz, pdz, t);

                    interpolated.velocity.x = Point.catmullRom(vax, vbx, vcx, vdx, t);
                    interpolated.velocity.y = Point.catmullRom(vay, vby, vcy, vdy, t);
                    interpolated.velocity.z = Point.catmullRom(vaz, vbz, vcz, vdz, t);

                    interpolated.tangent.x = Point.catmullRom(tax, tbx, tcx, tdx, t);
                    interpolated.tangent.y = Point.catmullRom(tay, tby, tcy, tdy, t);
                    interpolated.tangent.z = Point.catmullRom(taz, tbz, tcz, tdz, t);

                    interpolated.normal.x = Point.catmullRom(nax, nbx, ncx, ndx, t);
                    interpolated.normal.y = Point.catmullRom(nay, nby, ncy, ndy, t);
                    interpolated.normal.z = Point.catmullRom(naz, nbz, ncz, ndz, t);

                    var a = Point.catmullRom(cax, cbx, ccx, cdx, t);
                    var r = Point.catmullRom(cay, cby, ccy, cdy, t);
                    var g = Point.catmullRom(caz, cbz, ccz, cdz, t);
                    var b = Point.catmullRom(caw, cbw, ccw, cdw, t);

                    interpolated.color = new Vector4f(r, g, b, a);

                    interpolated.thickness = Point.catmullRom(data[i_1].thickness, data[i].thickness, data[i1].thickness, data[i2].thickness, t);
                    interpolated.texcoord = Point.catmullRom(data[i_1].texcoord, data[i].texcoord, data[i1].texcoord, data[i2].texcoord, t);

                    renderablePoints.add(interpolated.copy());
                }
            }

        }

        if (points.get(end).life > 0)
            renderablePoints.add(points.get(end));

        return renderablePoints;
    }

    /**
     * Initializes the frame used to generate the locally aligned trail mesh.
     */
    private CurveFrame initializeCurveFrame(Vector3f point, Vector3f nextPoint) {
        Vector3f tgnt = new Vector3f(nextPoint).sub(point);

        // Calculate tangent proximity to the normal vector of the frame (transform.forward).
        float tangentProximity = Math.abs(tgnt.normalize(new Vector3f()).dot(getWorldForward()));

        // If both vectors are dangerously close, skew the tangent a bit so that a proper frame can be formed:
        if (Math.abs(tangentProximity - 1.0f) < 0.0001f) {
            Vector3f rightVector = getWorldRight();
            Vector3f offset = rightVector.mul(0.01f, new Vector3f());
            tgnt.add(offset);
        }


        // Generate and return the frame:
        return new CurveFrame(point, getWorldForward(), getWorldUp(), tgnt);
    }

    /**
     * Updates the trail mesh to be seen from the camera passed to the function.
     */
    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        var deltaTime = getDeltaTime();
        updateDynamicData(partialTicks);
        if (deltaTime > EPSILON) {
            updateVelocity(deltaTime);
            emissionStep(deltaTime);
            snapLastPointToTransform();
            updatePointsLifecycle(deltaTime);
        }
        clearMeshData();

        // We need at least two points to create a trail mesh.
        if (points.size() > 1) {
            var worldToTrail = getWorldToTrail();
            Vector3f localCamPosition = worldToTrail.transformPosition(camera.getPosition().toVector3f());

            // get discontinuous point indices:
            discontinuities.clear();
            for (int i = 0; i < points.size(); ++i)
                if (points.get(i).discontinuous || i == points.size() - 1) discontinuities.add(i);

            // generate mesh for each trail segment:
            int start = 0;
            for (int i = 0; i < discontinuities.size(); ++i) {
                updateSegmentMesh(start, discontinuities.getInt(i), localCamPosition, partialTicks);
                start = discontinuities.getInt(i) + 1;
            }

            renderMesh(buffer, camera);
        }
    }

    /**
     * Asks Unity to render the trail mesh.
     */
    private void renderMesh(VertexConsumer buffer, Camera cam) {
        if (vertices.isEmpty() || tris.isEmpty()) {
            return;
        }

        var renderMatrix = getWorldToTrail().invert(new Matrix4f()).translateLocal(cam.getPosition().toVector3f().negate());

        for (int i = 0; i < tris.size(); i += 3) {
            int i0 = tris.getInt(i);
            int i1 = tris.getInt(i + 1);
            int i2 = tris.getInt(i + 2);

            // Render each vertex of the triangle
            renderVertex(buffer, renderMatrix, i0);
            renderVertex(buffer, renderMatrix, i1);
            renderVertex(buffer, renderMatrix, i2);
        }
    }

    private void renderVertex(VertexConsumer buffer, Matrix4f renderMatrix, int vertexIndex) {
        if (vertexIndex >= vertices.size()) return;

        var pos = new Vector3f(vertices.get(vertexIndex));
        renderMatrix.transformPosition(pos);

        var normal = vertexIndex < normals.size() ?
                new Vector3f(normals.get(vertexIndex)) : new Vector3f(0, 1, 0);
        renderMatrix.transformDirection(normal);

        var uv = vertexIndex < uvs.size() ?
                new Vector2f(uvs.get(vertexIndex)) : new Vector2f(0);

        var color = vertexIndex < vertColors.size() ?
                vertColors.get(vertexIndex) : new Vector4f(1);

        // Add vertex to buffer
        buffer.addVertex(pos.x, pos.y, pos.z)
                .setUv(uv.x, uv.y)
                .setColor(color.x, color.y, color.z, color.w)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(normal.x, normal.y, normal.z);
    }


    /**
     * Updates mesh for one trail segment:
     */
    private void updateSegmentMesh(int start, int end, Vector3f localCamPosition, float partialTicks) {
        // Get a list of the actual points to render: either the original, unsmoothed points or the smoothed curve.
        ElasticArray<Point> trail = getRenderablePoints(start, end);

        if (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop)
            trail.reverse();

        var data = trail.getData();

        if (trail.size() > 1) {
            float totalLength = 0;
            for (int i = 0; i < trail.size() - 1; ++i)
                totalLength += new Vector3f(data[i].position).distance(data[i + 1].position);

            totalLength = Math.max(totalLength, EPSILON);
            float partialLength = 0;
            float vCoord = config.textureMode == AraTrailConfig.TextureMode.Stretch ?
                    0 :
                    -config.uvFactor * totalLength * config.tileAnchor;

            if (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop)
                vCoord = 1 - vCoord;

            // Initialize curve frame using the first two points to calculate the first tangent vector:
            CurveFrame frame = initializeCurveFrame(data[trail.size() - 1].position,
                    data[trail.size() - 2].position);

            int va = 1;
            int vb = 0;

            for (int i = trail.size() - 1; i >= 0; --i)
            {

                // Calculate next and previous point indices:
                int nextIndex = Math.max(i - 1, 0);
                int prevIndex = Math.min(i + 1, trail.size() - 1);

                // Calculate next and previous trail vectors:
                nextV.x = data[nextIndex].position.x - data[i].position.x;
                nextV.y = data[nextIndex].position.y - data[i].position.y;
                nextV.z = data[nextIndex].position.z - data[i].position.z;

                prevV.x = data[i].position.x - data[prevIndex].position.x;
                prevV.y = data[i].position.y - data[prevIndex].position.y;
                prevV.z = data[i].position.z - data[prevIndex].position.z;

                float sectionLength = nextIndex == i ? prevV.length() : nextV.length();

                nextV.normalize();
                prevV.normalize();

                // Calculate tangent vector:
                if (config.alignment == AraTrailConfig.TrailAlignment.Local)
                    tangent = new Vector4f(data[i].tangent.normalize(new Vector3f()), 0);
                else
                {
                    tangent.x = (nextV.x + prevV.x) * 0.5f;
                    tangent.y = (nextV.y + prevV.y) * 0.5f;
                    tangent.z = (nextV.z + prevV.z) * 0.5f;
                }

                // Calculate normal vector:
                normal = new Vector3f(data[i].normal);
                if (config.alignment != AraTrailConfig.TrailAlignment.Local)
                    normal = config.alignment == AraTrailConfig.TrailAlignment.View ?
                            new Vector3f(localCamPosition).sub(data[i].position) :
                            frame.transport(new Vector3f(tangent.x, tangent.y, tangent.z), data[i].position);
                normal.normalize();

                // Calculate bitangent vector:
                if (config.alignment == AraTrailConfig.TrailAlignment.Velocity)
                    bitangent = frame.bitangent;
                else
                {
                    // cross(tangent, normal):
                    bitangent.x = tangent.y * normal.z - tangent.z * normal.y;
                    bitangent.y = tangent.z * normal.x - tangent.x * normal.z;
                    bitangent.z = tangent.x * normal.y - tangent.y * normal.x;
                }
                bitangent.normalize();

                // Calculate this point's normalized (0,1) lenght and life.
                float normalizedLength = config.sorting == AraTrailConfig.TrailSorting.OlderOnTop ?
                        partialLength / totalLength :
                        (totalLength - partialLength) / totalLength;
                float normalizedLife = Float.isInfinite(getLifeTime()) ? 1 : Mth.clamp(1 - data[i].life / getLifeTime(), 0, 1);
                partialLength += sectionLength;

                // Calculate vertex color:
                var timeColor = config.colorOverTime.get(normalizedLife, () -> getMemRandom("trails-colorOverTime")).intValue();
                var lengthColor = config.colorOverLength.get(normalizedLength, () -> getMemRandom("trails-colorOverLength")).intValue();
                color = new Vector4f(data[i].color).mul(colorMultiplier).mul(
                        ColorUtils.red(timeColor) * ColorUtils.red(lengthColor) ,
                        ColorUtils.green(timeColor) * ColorUtils.green(lengthColor),
                        ColorUtils.blue(timeColor) * ColorUtils.blue(lengthColor),
                        ColorUtils.alpha(timeColor) * ColorUtils.alpha(lengthColor)
                );

                // Calculate final thickness:
                float sectionThickness = config.thickness * thicknessMultiplier * data[i].thickness *
                        config.thicknessOverTime.get(normalizedLife, () -> getMemRandom("trails-thicknessOverTime")).floatValue() *
                        config.thicknessOverLength.get(normalizedLength, () -> getMemRandom("trails-thicknessOverLength")).floatValue();

                // In world tile mode, override texture coordinate with the point's one:
                if (config.textureMode == AraTrailConfig.TextureMode.WorldTile)
                    vCoord = config.tileAnchor + data[i].texcoord * config.uvFactor;

                if (config.section.isEnable()) {
                    appendSection(data, frame, i, trail.size(), sectionThickness, vCoord);
                }
                else {
                    var modified = appendFlatTrail(data, frame, i, trail.size(), sectionThickness, vCoord, va, vb);
                    va = modified[0];
                    vb = modified[1];
                }

                // Update vcoord:
                float uvDelta = (config.textureMode == AraTrailConfig.TextureMode.Stretch ? sectionLength / totalLength : sectionLength);
                vCoord += config.uvFactor * (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop ? -uvDelta : uvDelta);
            }
        }
    }

    private void appendSection(Point[] data, CurveFrame frame, int i, int count, float sectionThickness, float vCoord) {
        var section = config.section;
        // Loop around each segment:
        int sectionSegments = section.getSegments();
        int verticesPerSection = sectionSegments + 1;

        int vc = vertices.size();
        var color = new Vector4f(this.color);
        for (int j = 0; j <= sectionSegments; ++j) {
            // calculate normal using section vertex, curve normal and binormal:
            normal.x = (section.vertices.get(j).x * bitangent.x + section.vertices.get(j).y * tangent.x) * sectionThickness;
            normal.y = (section.vertices.get(j).x * bitangent.y + section.vertices.get(j).y * tangent.y) * sectionThickness;
            normal.z = (section.vertices.get(j).x * bitangent.z + section.vertices.get(j).y * tangent.z) * sectionThickness;


            // offset curve position by normal:
            vertex.x = data[i].position.x + normal.x;
            vertex.y = data[i].position.y + normal.y;
            vertex.z = data[i].position.z + normal.z;

            // cross(normal, curve tangent)
            texTangent.x = -(normal.y * frame.tangent.z - normal.z * frame.tangent.y);
            texTangent.y = -(normal.z * frame.tangent.x - normal.x * frame.tangent.z);
            texTangent.z = -(normal.x * frame.tangent.y - normal.y * frame.tangent.x);
            texTangent.w = 1;

            uv.x = (j / (float)sectionSegments) * config.uvWidthFactor;
            uv.y = vCoord;

            vertices.add(new Vector3f(vertex));
            normals.add(new Vector3f(normal));
            tangents.add(new Vector4f(texTangent));
            uvs.add(new Vector2f(uv));
            vertColors.add(color);

            if (j < sectionSegments && i < count - 1) {
                tris.add(vc + j);
                tris.add(vc + (j + 1));
                tris.add(vc - verticesPerSection + j);

                tris.add(vc + (j + 1));
                tris.add(vc - verticesPerSection + (j + 1));
                tris.add(vc - verticesPerSection + j);
            }
        }
    }

    private int[] appendFlatTrail(Point[] data, CurveFrame frame, int i, int count, float sectionThickness, float vCoord, int va, int vb) {
        boolean hqCorners = config.highQualityCorners && config.alignment != AraTrailConfig.TrailAlignment.Local;

        Quaternionf q = new Quaternionf();
        Vector3f corner = new Vector3f();
        float curvatureSign = 0;
        float correctedThickness = sectionThickness;
        Vector3f prevSectionBitangent = bitangent;

        // High-quality corners:
        if (hqCorners) {

            Vector3f nextSectionBitangent = i == 0 ? bitangent :
                    nextV.cross(bitangent.cross(new Vector3f(tangent.x, tangent.y, tangent.z), new Vector3f()), new Vector3f()).normalize();

            // If round corners are enabled:
            if (config.cornerRoundness > 0) {

                prevSectionBitangent = i == count - 1 ? bitangent.negate(new Vector3f()) :
                        prevV.cross(bitangent.cross(new Vector3f(tangent.x, tangent.y, tangent.z), new Vector3f()), new Vector3f()).normalize();

                // Calculate "elbow" angle:
                curvatureSign = (i == 0 || i == count - 1) ? 1 : Math.signum(nextV.dot(prevSectionBitangent.negate(new Vector3f())));
                float angle = (i == 0 || i == count - 1) ? (float)Math.PI :
                        (float)Math.acos(Math.max(-1, Math.min(1, nextSectionBitangent.dot(prevSectionBitangent))));

                // Prepare a quaternion for incremental rotation of the corner vector:
                q.fromAxisAngleRad(normal.mul(curvatureSign, new Vector3f()), angle / config.cornerRoundness);
                corner = prevSectionBitangent.mul(sectionThickness * curvatureSign, new Vector3f());
            }

            // Calculate correct thickness by projecting corner bitangent onto the next section bitangent. This prevents "squeezing"
            if (nextSectionBitangent.lengthSquared() > 0.1f)
                correctedThickness = sectionThickness / Math.max(bitangent.dot(nextSectionBitangent), 0.15f);

        }


        // Append straight section mesh data:
        if (hqCorners && config.cornerRoundness > 0) {
            // bitangents are slightly asymmetrical in case of high-quality round or sharp corners:
            if (curvatureSign > 0) {
                vertices.add(data[i].position.add(prevSectionBitangent.mul(sectionThickness, new Vector3f()), new Vector3f()));
                vertices.add(data[i].position.sub(bitangent.mul(correctedThickness, new Vector3f()), new Vector3f()));
            } else {
                vertices.add(data[i].position.add(bitangent.mul(correctedThickness, new Vector3f()), new Vector3f()));
                vertices.add(data[i].position.sub(prevSectionBitangent.mul(sectionThickness, new Vector3f()), new Vector3f()));
            }
        } else {
            vertices.add(data[i].position.add(bitangent.mul(correctedThickness, new Vector3f()), new Vector3f()));
            vertices.add(data[i].position.sub(bitangent.mul(correctedThickness, new Vector3f()), new Vector3f()));
        }

        var normal = new Vector3f(this.normal);
        normals.add(normal);
        normals.add(normal);

        var tangent = new Vector4f(this.tangent);
        tangents.add(tangent);
        tangents.add(tangent);

        var color = new Vector4f(this.color);
        vertColors.add(color);
        vertColors.add(color);

        uv.set(vCoord, config.sorting == AraTrailConfig.TrailSorting.NewerOnTop ? config.uvWidthFactor : 0);
        uvs.add(new Vector2f(uv));
        uv.set(vCoord, config.sorting == AraTrailConfig.TrailSorting.NewerOnTop ? 0 : config.uvWidthFactor);
        uvs.add(new Vector2f(uv));

        if (i < count - 1) {
            int vc = vertices.size() - 1;
            tris.add(vc);
            tris.add(va);
            tris.add(vb);

            tris.add(vb);
            tris.add(vc - 1);
            tris.add(vc);
        }

        va = vertices.size() - 1;
        vb = vertices.size() - 2;

        // Append smooth corner mesh data:
        if (hqCorners && config.cornerRoundness > 0) {
            for (int p = 0; p <= config.cornerRoundness; ++p) {
                vertices.add(data[i].position.add(corner, new Vector3f()));
                normals.add(normal);
                tangents.add(tangent);
                vertColors.add(color);
                uv.set(vCoord, curvatureSign > 0 ? 0 : 1);
                uvs.add(new Vector2f(uv));

                int vc = vertices.size() - 1;

                tris.add(vc);
                tris.add(va);
                tris.add(vb);

                if (curvatureSign > 0)
                    vb = vc;
                else va = vc;

                // rotate corner point:
                corner = q.transform(corner);
            }
        }
        return new int[]{va, vb};
    }


    /**
     * Spatial frame, consisting of a point an three axis. This is used to implement the parallel transport method
     * along the curve defined by the trail points. Using this instead of a Frenet-esque method avoids flipped frames
     * at points where the curvature changes.
     */
    public static class CurveFrame {
        public Vector3f position;
        public Vector3f normal;
        public Vector3f bitangent;
        public Vector3f tangent;

        public CurveFrame(Vector3f position, Vector3f normal, Vector3f bitangent, Vector3f tangent) {
            this.position = position;
            this.normal = normal;
            this.bitangent = bitangent;
            this.tangent = tangent;
        }

        public Vector3f transport(Vector3f newTangent, Vector3f newPosition) {
            // double-reflection rotation-minimizing frame transport:
            Vector3f v1 = new Vector3f(newPosition).sub(position);
            float c1 = v1.dot(v1);

            Vector3f rL = new Vector3f(normal).sub(
                    new Vector3f(v1).mul(2 / (c1 + EPSILON) * v1.dot(normal))
            );
            Vector3f tL = new Vector3f(tangent).sub(
                    new Vector3f(v1).mul(2 / (c1 + EPSILON) * v1.dot(tangent))
            );

            Vector3f v2 = new Vector3f(newTangent).sub(tL);
            float c2 = v2.dot(v2);

            Vector3f r1 = new Vector3f(rL).sub(
                    new Vector3f(v2).mul(2 / (c2 + EPSILON) * v2.dot(rL))
            );
            Vector3f s1 = new Vector3f(newTangent).cross(r1);

            normal = r1;
            bitangent = s1;
            tangent = newTangent;
            position = newPosition;

            return normal;
        }
    }

    /**
     * Holds information for each point in a trail: position, velocity and remaining lifetime. Points
     * can be added or subtracted, and interpolated using Catmull-Rom spline interpolation.
     */
    public static class Point {
        public Vector3f position;
        public Vector3f velocity;
        public Vector3f tangent;
        public Vector3f normal;
        public Vector4f color;
        public float thickness;
        public float life;
        public float texcoord;
        public boolean discontinuous;

        public Point(Vector3f position, Vector3f velocity, Vector3f tangent, Vector3f normal, Vector4f color, float thickness, float texcoord, float lifetime) {
            this.position = position;
            this.velocity = velocity;
            this.tangent = tangent;
            this.normal = normal;
            this.color = color;
            this.thickness = thickness;
            this.life = lifetime;
            this.texcoord = texcoord;
            this.discontinuous = false;
        }

        public static float catmullRom(float p0, float p1, float p2, float p3, float t) {
            float t2 = t * t;
            return 0.5f * ((2 * p1) +
                    (-p0 + p2) * t +
                    (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                    (-p0 + 3 * p1 - 3 * p2 + p3) * t2 * t);
        }

        public static Point add(Point p1, Point p2) {
            return new Point(
                    new Vector3f(p1.position).add(p2.position),
                    new Vector3f(p1.velocity).add(p2.velocity),
                    new Vector3f(p1.tangent).add(p2.tangent),
                    new Vector3f(p1.normal).add(p2.normal),
                    new Vector4f(p1.color).add(p2.color),
                    p1.thickness + p2.thickness,
                    p1.texcoord + p2.texcoord,
                    p1.life + p2.life
            );
        }

        public static Point subtract(Point p1, Point p2) {
            return new Point(
                    new Vector3f(p1.position).sub(p2.position),
                    new Vector3f(p1.velocity).sub(p2.velocity),
                    new Vector3f(p1.tangent).sub(p2.tangent),
                    new Vector3f(p1.normal).sub(p2.normal),
                    new Vector4f(p1.color).sub(p2.color),
                    p1.thickness - p2.thickness,
                    p1.texcoord - p2.texcoord,
                    p1.life - p2.life
            );
        }

        public Point copy() {
            return new Point(
                    new Vector3f(position),
                    new Vector3f(velocity),
                    new Vector3f(tangent),
                    new Vector3f(normal),
                    new Vector4f(color),
                    thickness,
                    texcoord,
                    life
            );
        }

    }
}
