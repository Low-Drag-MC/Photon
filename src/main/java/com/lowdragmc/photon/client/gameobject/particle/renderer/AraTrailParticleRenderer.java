package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailAdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle.CurveFrame;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle.Point;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.ElasticArray;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle.EPSILON;

/**
 * Renders {@link AraTrailParticle}s: builds the trail-space triangle mesh from the simulated
 * points each frame and emits it camera-relative. STATEFUL (mesh scratch buffers) — one instance
 * per config's render pass; rendering is confined to the render thread, so shared scratch is safe.
 * The particle itself only simulates points; all mesh generation lives here.
 */
@ParametersAreNonnullByDefault
public class AraTrailParticleRenderer {

    private final AraTrailConfig config;
    private final AraTrailInstanceRenderer instanceBackend;

    public AraTrailParticleRenderer(AraTrailConfig config) {
        this.config = config;
        this.instanceBackend = new AraTrailInstanceRenderer(config);
    }

    // ---- mesh scratch (rebuilt per trail per frame) ----
    private final List<Vector3f> vertices = new ArrayList<>();
    private final List<Vector3f> normals = new ArrayList<>();
    private final List<Vector4f> tangents = new ArrayList<>();
    private final List<Vector2f> uvs = new ArrayList<>();
    private final List<Vector4f> vertColors = new ArrayList<>();
    private final IntList tris = new IntArrayList();
    private final IntList discontinuities = new IntArrayList();
    private final ElasticArray<Point> renderablePoints = new ElasticArray<>(Point.class);

    // ---- per-point frame state (mutated while walking the trail) ----
    private final Vector3f nextV = new Vector3f(0);
    private final Vector3f prevV = new Vector3f(0);
    private final Vector3f vertex = new Vector3f(0);
    private Vector3f normal = new Vector3f(0);
    private Vector3f bitangent = new Vector3f(0);
    private Vector4f tangent = new Vector4f(0, 0, 0, 1);
    private final Vector4f texTangent = new Vector4f(0);
    private final Vector2f uv = new Vector2f(0);
    private Vector4f color;
    // ---- per-point walk outputs shared between the mesh path and the instanced collector ----
    private float walkSectionThickness;
    private float walkNormalizedLength;
    private float walkNormalizedSegmentLife;
    private int flatVa;
    private int flatVb;

    // reused per-trail life snapshot for renderAgedSpans (render thread only)
    private float[] savedLivesPool = new float[0];

    // ---- instanced-collection scratch (active during uploadInstances; render thread only) ----
    private final Matrix4f collectMatrix = new Matrix4f();
    private final Vector3f collectTmp = new Vector3f();
    @javax.annotation.Nullable
    private java.nio.FloatBuffer collectPointBuffer;
    @javax.annotation.Nullable
    private java.nio.FloatBuffer collectDataBuffer;
    @javax.annotation.Nullable
    private java.nio.FloatBuffer collectCustomBuffer;
    private boolean collectTube;
    private int collectPointCount;
    private int collectSpanBase;
    private int collectSpanCount;
    private int collectInstanceCount;
    private float[] spanPointT = new float[0];
    private float[] spanPointLife = new float[0];

    /** Visits one discontinuity-delimited span [start..end] (inclusive) of the aged point list. */
    private interface SpanVisitor {
        void visit(int start, int end);
    }

    /** Receives one walked point of a span, with the frame state staged in the walk fields. */
    private interface PointSink {
        void point(AraTrailParticle particle, Point[] data, int i, int count, CurveFrame frame, float vCoord);
    }

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        for (var particle : particles) {
            if (particle instanceof AraTrailParticle trail) {
                renderTrail(buffer, trail, camera, partialTicks);
            }
        }
    }

    /**
     * Updates the trail mesh to be seen from the camera and emits it. Draw-only: the trail is
     * simulated deterministically in the particle's updateTick, never here.
     */
    private void renderTrail(VertexConsumer buffer, AraTrailParticle particle, Camera camera, float partialTicks) {
        particle.updateDynamicData(partialTicks);
        clearMeshData();

        var points = particle.getPoints();

        // We need at least two points to create a trail mesh.
        if (points.size() > 1) {
            var worldToTrail = particle.getWorldToTrail();
            Vector3f localCamPosition = worldToTrail.transformPosition(camera.position().toVector3f());

            renderAgedSpans(particle, worldToTrail, partialTicks,
                    (start, end) -> updateSegmentMesh(particle, start, end, localCamPosition));

            // safe after the state restore: reads only the mesh lists, worldToTrail and the camera
            renderMesh(particle, buffer, camera);
        }
    }

    /**
     * Runs the render-time point mutations (partial-tick aging, tail retract, head snap), visits
     * every discontinuity-delimited span, and restores the points — shared by the CPU mesh build
     * and the instanced collector so both see identical aged point state.
     */
    private void renderAgedSpans(AraTrailParticle particle, Matrix4f worldToTrail, float partialTicks, SpanVisitor visitor) {
        var config = particle.config;
        var points = particle.getPoints();

        // ---- per-frame tail smoothing (render-only, non-destructive; restored in finally) ----
        // The sim removes points at the 20 TPS tick, so the tail would pop a whole segment each 0.05s.
        // Here we "age" the trail by the current frame fraction so the tail recedes continuously between
        // ticks: (1) subtract the elapsed life from every point (the smoothness>1 spline then trims dying
        // sub-points via its life-cull, and segment-over-time tapers advance smoothly), and (2) for the
        // flat smoothness<=1 path, geometrically retract the oldest point toward the next as it dies so its
        // last segment shrinks to zero instead of vanishing. All reverted after the mesh build.
        float ageAmt = partialTicks * particle.emitter.timeScale() / 20f;   // life (seconds) elapsed since last tick
        if (savedLivesPool.length < points.size()) {
            savedLivesPool = new float[Math.max(points.size(), savedLivesPool.length * 2)];
        }
        float[] savedLives = savedLivesPool; // scoped to this trail: restored in the finally below
        for (int i = 0; i < points.size(); ++i) {
            savedLives[i] = points.get(i).life;
            points.get(i).life -= ageAmt;
        }
        Point tail = points.getFirst();
        Vector3f savedTail = null;
        if (config.smoothness <= 1 && !tail.discontinuous && tail.life < 0) {
            var next = points.get(1);
            float lerpDur = next.life - tail.life;   // segment life span (aging cancels in the difference)
            if (lerpDur > EPSILON) {
                float f = Mth.clamp(-tail.life / lerpDur, 0f, 1f);
                savedTail = new Vector3f(tail.position);
                tail.position = new Vector3f(savedTail).lerp(next.position, f);
            }
        }

        // Keep the head smooth between ticks: move the stored last point to the partial-tick-interpolated
        // emitter position for the mesh build only, then restore it so the tick sim never sees it (render
        // and tick are both on the client thread, sequential — no concurrent observation).
        Point head = (!particle.isRemoved() && config.emit && !points.getLast().discontinuous) ? points.getLast() : null;
        Vector3f savedHead = head == null ? null : new Vector3f(head.position);
        if (head != null) head.position = worldToTrail.transformPosition(particle.getWorldPosition());
        try {
            // get discontinuous point indices:
            discontinuities.clear();
            for (int i = 0; i < points.size(); ++i)
                if (points.get(i).discontinuous || i == points.size() - 1) discontinuities.add(i);

            // visit each trail segment span:
            int start = 0;
            for (int i = 0; i < discontinuities.size(); ++i) {
                visitor.visit(start, discontinuities.getInt(i));
                start = discontinuities.getInt(i) + 1;
            }
        } finally {
            for (int i = 0; i < points.size(); ++i) points.get(i).life = savedLives[i];
            if (savedTail != null) tail.position = savedTail;
            if (head != null) head.position = savedHead; // restore before any tick observes it
        }
    }

    /**
     * Clears all mesh data: vertices, normals, tangents, etc. Called at the beginning of every trail.
     */
    private void clearMeshData() {
        vertices.clear();
        normals.clear();
        tangents.clear();
        uvs.clear();
        vertColors.clear();
        tris.clear();
    }

    private ElasticArray<Point> getRenderablePoints(AraTrailParticle particle, int start, int end) {
        var config = particle.config;
        var points = particle.getPoints();
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

                    var copied = interpolated.copy();
                    renderablePoints.add(copied);
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
    private CurveFrame initializeCurveFrame(AraTrailParticle particle, Vector3f point, Vector3f nextPoint) {
        Vector3f tgnt = new Vector3f(nextPoint).sub(point);

        // Calculate tangent proximity to the normal vector of the frame (transform.forward).
        float tangentProximity = Math.abs(tgnt.normalize(new Vector3f()).dot(particle.getWorldForward()));

        // If both vectors are dangerously close, skew the tangent a bit so that a proper frame can be formed:
        if (Math.abs(tangentProximity - 1.0f) < 0.0001f) {
            Vector3f rightVector = particle.getWorldRight();
            Vector3f offset = rightVector.mul(0.01f, new Vector3f());
            tgnt.add(offset);
        }

        // Generate and return the frame:
        return new CurveFrame(point, particle.getWorldForward(), particle.getWorldUp(), tgnt);
    }

    private void renderMesh(AraTrailParticle particle, VertexConsumer buffer, Camera cam) {
        if (vertices.isEmpty() || tris.isEmpty()) {
            return;
        }

        var renderMatrix = particle.getWorldToTrail().invert(new Matrix4f()).translateLocal(cam.position().toVector3f().negate());

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
                .setLight(net.minecraft.util.LightCoordsUtil.FULL_BRIGHT)
                .setNormal(normal.x, normal.y, normal.z);
    }

    /**
     * Updates mesh for one trail segment:
     */
    private void updateSegmentMesh(AraTrailParticle particle, int start, int end, Vector3f localCamPosition) {
        walkSpan(particle, start, end, localCamPosition, this::appendMeshPoint);
    }

    /** The mesh path's point sink: appends the walked point's ribbon pair or tube ring. */
    private void appendMeshPoint(AraTrailParticle particle, Point[] data, int i, int count, CurveFrame frame, float vCoord) {
        var config = particle.config;
        if (config.section.isEnable()) {
            appendSection(config, data, frame, i, count, walkSectionThickness, vCoord);
        } else {
            var modified = appendFlatTrail(config, data, i, count, walkSectionThickness, vCoord, flatVa, flatVb);
            flatVa = modified[0];
            flatVb = modified[1];
        }
    }

    /**
     * Walks one span's renderable points (newest→oldest, after the optional NewerOnTop reverse),
     * computing the per-point frame (tangent/normal/bitangent), composed color, thickness and
     * texture coordinate exactly as the historical mesh build did, and hands each point to the
     * sink. Shared by the CPU mesh path and the instanced collector so the math cannot diverge.
     * Per-point results are staged in the walk fields ({@link #walkSectionThickness},
     * {@link #walkNormalizedLength}, {@link #walkNormalizedSegmentLife}, tangent/normal/bitangent/color).
     */
    private void walkSpan(AraTrailParticle particle, int start, int end, Vector3f localCamPosition, PointSink sink) {
        var config = particle.config;
        var runtime = particle.runtime;

        // Get a list of the actual points to render: either the original, unsmoothed points or the smoothed curve.
        ElasticArray<Point> trail = getRenderablePoints(particle, start, end);

        if (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop)
            trail.reverse();

        var data = trail.getData();

        if (trail.size() > 1) {
            float totalLength = 0;
            for (int i = 0; i < trail.size() - 1; ++i)
                totalLength += data[i].position.distance(data[i + 1].position); // read-only, no copy needed

            totalLength = Math.max(totalLength, EPSILON);
            float partialLength = 0;
            // U runs along the trail length. Anchor U at the head (newest point) and decrease toward the
            // tail so the direction matches TrailParticle: head -> U=1, tail -> U=0 (WorldTile derives U
            // from texcoord below and is unaffected).
            float vCoord = config.textureMode == AraTrailConfig.TextureMode.Stretch ?
                    config.uvFactor :
                    config.uvFactor * totalLength * (1 - config.tileAnchor);

            if (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop)
                vCoord = 1 - vCoord;

            // Initialize curve frame using the first two points to calculate the first tangent vector:
            CurveFrame frame = initializeCurveFrame(particle, data[trail.size() - 1].position,
                    data[trail.size() - 2].position);

            flatVa = 1;
            flatVb = 0;

            // ---- hoisted loop invariants (bit-identical: these were recomputed per point) ----
            float normalizedLife = Math.clamp(particle.emitter.getT(), 0, 1);
            float lifeTime = particle.getLifeTime();
            var colorMultiplier = particle.getColorMultiplier();
            float thicknessMultiplier = particle.getThicknessMultiplier();
            float baseThickness = runtime.thickness.get();
            var colorOverLength = runtime.colorOverLength.get();
            var colorOverSegmentTime = runtime.colorOverSegmentTime.get();
            var thicknessOverSegmentTime = runtime.thicknessOverSegmentTime.get();
            var thicknessOverLength = runtime.thicknessOverLength.get();
            java.util.function.Supplier<Float> lengthColorRandom = () -> particle.getMemRandom("trails-colorOverLength");
            java.util.function.Supplier<Float> segmentColorRandom = () -> particle.getMemRandom("trails-colorOverSegmentTime");
            java.util.function.Supplier<Float> segmentThicknessRandom = () -> particle.getMemRandom("trails-thicknessOverSegmentTime");
            java.util.function.Supplier<Float> lengthThicknessRandom = () -> particle.getMemRandom("trails-thicknessOverLength");
            // colorOverTime / thicknessOverTime sample the (per-trail constant) emitter T
            var timeColor = runtime.colorOverTime.get().get(normalizedLife, () -> particle.getMemRandom("trails-colorOverTime")).intValue();
            float timeColorR = ColorUtils.red(timeColor);
            float timeColorG = ColorUtils.green(timeColor);
            float timeColorB = ColorUtils.blue(timeColor);
            float timeColorA = ColorUtils.alpha(timeColor);
            float timeThickness = runtime.thicknessOverTime.get().get(normalizedLife, () -> particle.getMemRandom("trails-thicknessOverTime")).floatValue();

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

                // Guard the trail ends: nextIndex/prevIndex clamp to i there, so one neighbour vector is
                // (0,0,0), and normalize((0,0,0)) yields NaN which poisons the tangent/bitangent -> NaN
                // end-cap vertices. Fall back to the available direction so the end cap keeps a valid frame.
                boolean nextZero = nextV.lengthSquared() < EPSILON;
                boolean prevZero = prevV.lengthSquared() < EPSILON;
                if (!nextZero) nextV.normalize();
                if (!prevZero) prevV.normalize();
                if (nextZero) nextV.set(prevV);   // tail end: reuse the (normalized) prev direction
                if (prevZero) prevV.set(nextV);   // head end: reuse the (normalized) next direction

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
                walkNormalizedLength = config.sorting == AraTrailConfig.TrailSorting.OlderOnTop ?
                        partialLength / totalLength :
                        (totalLength - partialLength) / totalLength;
                partialLength += sectionLength;
                walkNormalizedSegmentLife = Float.isInfinite(lifeTime) ? 1 : Mth.clamp(1 - data[i].life / lifeTime, 0, 1);

                // Calculate vertex color:
                var lengthColor = colorOverLength.get(walkNormalizedLength, lengthColorRandom).intValue();
                var segmentColor = colorOverSegmentTime.get(walkNormalizedSegmentLife, segmentColorRandom).intValue();
                color = new Vector4f(data[i].color).mul(colorMultiplier).mul(
                        timeColorR * ColorUtils.red(lengthColor) * ColorUtils.red(segmentColor),
                        timeColorG * ColorUtils.green(lengthColor) * ColorUtils.green(segmentColor),
                        timeColorB * ColorUtils.blue(lengthColor) * ColorUtils.blue(segmentColor),
                        timeColorA * ColorUtils.alpha(lengthColor) * ColorUtils.alpha(segmentColor)
                );

                // Calculate final thickness:
                walkSectionThickness = baseThickness * thicknessMultiplier * data[i].thickness *
                        timeThickness *
                        thicknessOverSegmentTime.get(walkNormalizedSegmentLife, segmentThicknessRandom).floatValue() *
                        thicknessOverLength.get(walkNormalizedLength, lengthThicknessRandom).floatValue();

                // In world tile mode, override texture coordinate with the point's one:
                if (config.textureMode == AraTrailConfig.TextureMode.WorldTile)
                    vCoord = config.tileAnchor + data[i].texcoord * config.uvFactor;

                sink.point(particle, data, i, trail.size(), frame, vCoord);

                // Update vcoord:
                float uvDelta = (config.textureMode == AraTrailConfig.TextureMode.Stretch ? sectionLength / totalLength : sectionLength);
                vCoord += config.uvFactor * (config.sorting == AraTrailConfig.TrailSorting.NewerOnTop ? uvDelta : -uvDelta);
            }
        }
    }

    private void appendSection(AraTrailConfig config, Point[] data, CurveFrame frame, int i, int count, float sectionThickness, float vCoord) {
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

            uv.x = (j / (float) sectionSegments) * config.uvWidthFactor;
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

    private int[] appendFlatTrail(AraTrailConfig config, Point[] data, int i, int count, float sectionThickness, float vCoord, int va, int vb) {
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
                float angle = (i == 0 || i == count - 1) ? (float) Math.PI :
                        (float) Math.acos(Math.max(-1, Math.min(1, nextSectionBitangent.dot(prevSectionBitangent))));

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

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /**
     * Fill and upload one instance per rendered trail segment, with the per-point data (position,
     * frame, color, uv — all CPU-computed through the same {@link #walkSpan} as the mesh path)
     * uploaded once into the point buffer texture. Returns true if any instance was uploaded (the
     * VAO is left bound for {@link #drawInstanced}). The flat/tube variant follows the pass-owning
     * config (the base mesh is baked from it); per-point math uses each particle's own config,
     * exactly like the CPU path.
     */
    public boolean uploadInstances(Collection<IParticle> particles, Camera camera, float partialTicks) {
        // capacity pre-scan (proven upper bound: renderable points per trail <= size * smoothness + 2)
        var pointCapacity = 0;
        for (var p : particles) {
            if (p instanceof AraTrailParticle trail && trail.getPoints().size() > 1) {
                pointCapacity += trail.getPoints().size() * Math.max(1, trail.config.smoothness) + 2;
            }
        }
        if (pointCapacity == 0) return false;

        var buffer = instanceBackend.beginUpload(pointCapacity); // instances per span = points - 1
        if (buffer == null) return false;
        var pointBuffer = instanceBackend.beginPointUpload(pointCapacity);
        if (pointBuffer == null) return false;

        var setting = config.additionalGPUDataSetting;
        var dataBuffer = setting.hasDataRecord() ? instanceBackend.beginDataUpload(pointCapacity) : null;
        var customBuffer = setting.hasCustomRecord() ? instanceBackend.beginCustomUpload(pointCapacity) : null;
        collectTube = instanceBackend.isTubeMode();
        collectPointBuffer = pointBuffer;
        collectDataBuffer = dataBuffer;
        collectCustomBuffer = customBuffer;
        collectPointCount = 0;
        collectInstanceCount = 0;
        var cameraPos = camera.position().toVector3f();

        for (var p : particles) {
            if (!(p instanceof AraTrailParticle trail) || trail.getPoints().size() <= 1) continue;
            trail.updateDynamicData(partialTicks);
            var worldToTrail = trail.getWorldToTrail();
            Vector3f localCamPosition = worldToTrail.transformPosition(new Vector3f(cameraPos));
            // same matrix renderMesh builds: trail space -> camera-relative world
            worldToTrail.invert(collectMatrix).translateLocal(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            renderAgedSpans(trail, worldToTrail, partialTicks, (start, end) -> {
                collectSpanBase = collectPointCount;
                collectSpanCount = 0;
                walkSpan(trail, start, end, localCamPosition, this::collectPoint);
                emitSpanInstances(buffer, trail, partialTicks, setting);
            });
        }

        collectPointBuffer = null;
        collectDataBuffer = null;
        collectCustomBuffer = null;
        if (dataBuffer != null) {
            instanceBackend.endDataUpload(dataBuffer);
        }
        if (customBuffer != null) {
            instanceBackend.endCustomUpload(customBuffer);
        }
        instanceBackend.endPointUpload(pointBuffer);
        instanceBackend.endUpload(buffer, collectInstanceCount);
        return collectInstanceCount > 0;
    }

    /**
     * The collector's point sink: writes one point's 4 texels (camera-relative, dest-arg JOML
     * only — in-place ops would corrupt the sim points or {@code frame.bitangent}) and stages the
     * per-point channel values.
     */
    private void collectPoint(AraTrailParticle particle, Point[] data, int i, int count, CurveFrame frame, float vCoord) {
        var pointBuffer = collectPointBuffer;
        assert pointBuffer != null;

        var pos = collectMatrix.transformPosition(data[i].position, collectTmp);
        if (collectTube) {
            // T0: pos + thickness
            pointBuffer.put(pos.x).put(pos.y).put(pos.z).put(walkSectionThickness);
            // T1: bitangent (direction-transformed) + vCoord
            var direction = collectMatrix.transformDirection(bitangent, collectTmp);
            pointBuffer.put(direction.x).put(direction.y).put(direction.z).put(vCoord);
            // T2: tangent — UNNORMALIZED, as the CPU ring formula uses it
            collectTmp.set(tangent.x, tangent.y, tangent.z);
            direction = collectMatrix.transformDirection(collectTmp);
            pointBuffer.put(direction.x).put(direction.y).put(direction.z).put(0f);
        } else {
            // T0: pos + u (vCoord runs along the trail)
            pointBuffer.put(pos.x).put(pos.y).put(pos.z).put(vCoord);
            // T1: ribbon offset = bitangent * thickness (direction-transformed)
            collectTmp.set(bitangent).mul(walkSectionThickness);
            var offset = collectMatrix.transformDirection(collectTmp);
            pointBuffer.put(offset.x).put(offset.y).put(offset.z).put(0f);
            // T2: frame normal (direction-transformed, not renormalized — mirrors renderVertex)
            var pointNormal = collectMatrix.transformDirection(normal, collectTmp);
            pointBuffer.put(pointNormal.x).put(pointNormal.y).put(pointNormal.z).put(0f);
        }
        // T3: composed color
        pointBuffer.put(color.x).put(color.y).put(color.z).put(color.w);

        if (spanPointT.length <= collectSpanCount) {
            var capacity = Math.max(collectSpanCount + 1, Math.max(spanPointT.length * 2, 64));
            spanPointT = java.util.Arrays.copyOf(spanPointT, capacity);
            spanPointLife = java.util.Arrays.copyOf(spanPointLife, capacity);
        }
        spanPointT[collectSpanCount] = 1 - walkNormalizedLength; // head = 1, tail = 0 (arc length)
        spanPointLife[collectSpanCount] = walkNormalizedSegmentLife;
        collectSpanCount++;
        collectPointCount++;
    }

    /** Emits one instance per consecutive point pair of the just-collected span. */
    private void emitSpanInstances(java.nio.FloatBuffer buffer, AraTrailParticle trail, float partialTicks,
                                   AraTrailAdditionalGPUDataSetting setting) {
        if (collectSpanCount < 2) return;
        // cross-ribbon v pair, per particle — mirrors appendFlatTrail's +side / -side uv.y
        var particleConfig = trail.config;
        var newerOnTop = particleConfig.sorting == AraTrailConfig.TrailSorting.NewerOnTop;
        float vA = newerOnTop ? particleConfig.uvWidthFactor : 0;
        float vB = newerOnTop ? 0 : particleConfig.uvWidthFactor;

        for (int w = 0; w < collectSpanCount - 1; w++) {
            // iSeg int (point index of the segment's curr point)
            buffer.put(Float.intBitsToFloat(collectSpanBase + w));
            if (!collectTube) {
                // iSegV vec2 (vA, vB)
                buffer.put(vA).put(vB);
            }
            // stage the per-point channel pair before any upload — also the representative segment
            // t/length the per-segment custom-data sampling reads (curr endpoint)
            if (setting.hasAttribs() || collectDataBuffer != null || collectCustomBuffer != null) {
                setting.setSegmentValues(spanPointT[w], spanPointT[w + 1], spanPointLife[w], spanPointLife[w + 1]);
            }
            if (setting.hasAttribs()) {
                setting.uploadAttribs(trail, buffer, partialTicks);
            }
            if (collectDataBuffer != null) {
                setting.uploadDataRecord(trail, collectDataBuffer, partialTicks);
            }
            if (collectCustomBuffer != null) {
                setting.uploadCustomRecord(trail, collectCustomBuffer, partialTicks);
            }
            collectInstanceCount++;
        }
    }

    // TODO(M2): drawInstanced — re-expressed as a RenderPass.drawIndexed(instanceCount) draw with
    // the material pipeline when the instancing backend moves off raw GL.

    /** Whether the baked instanced geometry no longer matches the config (mode / section / uvWidthFactor). */
    public boolean geometryStale() {
        return instanceBackend.geometryStale();
    }

    /**
     * Full GL teardown of the instanced resources. Call when the instance layout or the baked
     * geometry changes (not for capacity growth).
     */
    public void dispose() {
        instanceBackend.dispose();
    }
}
