package com.lowdragmc.photon.client.light.dev;

import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.FogProvider;
import com.lowdragmc.photon.client.light.FogSink;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.LightSink;
import com.lowdragmc.photon.client.light.PhotonLights;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dev test bed: a courtyard with a statue and three orbiting RGB lights, an arcade, a two-storey house lit
 * upstairs, a tunnel, a searchlight and a fence row. Offsets are (east, up, south) from the origin block.
 */
@OnlyIn(Dist.CLIENT)
public final class LightDemoScene {
    /** A camera spot: feet position relative to the origin, plus look direction. */
    public record View(String name, double x, double y, double z, float yaw, float pitch) {
    }

    public static final List<View> VIEWS = List.of(
            new View("overview", 0.5, 12, -7.5, 0, 35),
            new View("courtyard", 0.5, 3, 1.5, 0, 25),
            new View("arcade", -6.5, 1, 12.5, 90, 5),
            // same spot, two pitches: looking level keeps the ceiling on screen, looking down loses it
            new View("house_level", 11.5, 1, 6.5, -45, 0),
            new View("house_floor", 11.5, 1, 6.5, -45, 45),
            new View("tunnel_top", 0.5, 14, 27.5, 0, 89),
            new View("fence", 0.5, 1, -3.5, 0, 20),
            // for the haze: the searchlight's beam from beside its tower, and the house's upstairs windows
            new View("searchlight", -4.5, 6, 20.5, -120, 10),
            new View("windows", 1.5, 7, 0.5, -60, 5));

    /** {@code >= 0} pins the clock the demo lights animate on. */
    public static float frozenSeconds = -1f;
    /** The demo lights' volumetric strength. */
    public static float volumetric = 0f;
    /** The density of a fog box over the courtyard; 0 for none. */
    public static float fogDensity = 0f;

    private static final LightProvider PROVIDER = LightDemoScene::submit;
    private static final FogProvider FOG_PROVIDER = LightDemoScene::submitFog;
    private static final List<DynamicLight> SCATTERED = new ArrayList<>();
    private static final float[][] RGB = {{1f, 0.12f, 0.08f}, {0.15f, 1f, 0.2f}, {0.2f, 0.35f, 1f}};
    @Nullable
    private static BlockPos ground;

    private LightDemoScene() {
    }

    @Nullable
    public static BlockPos ground() {
        return ground;
    }

    @Nullable
    public static View view(String name) {
        return VIEWS.stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
    }

    /** Server thread. Wipes a 45 x 18 x 41 box around {@code origin}: use a throwaway world. */
    public static void build(ServerLevel level, BlockPos origin) {
        var b = new Builder(level, origin);
        b.fill(-22, 1, -6, 22, 18, 34, Blocks.AIR.defaultBlockState());
        b.fill(-22, 0, -6, 22, 0, 34, Blocks.STONE_BRICKS.defaultBlockState());

        // courtyard + statue
        b.fill(-6, 0, 6, 6, 0, 18, Blocks.WHITE_CONCRETE.defaultBlockState());
        b.fill(0, 1, 12, 0, 3, 12, Blocks.QUARTZ_PILLAR.defaultBlockState());
        b.set(0, 4, 12, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
        b.fill(-3, 1, 15, -3, 2, 15, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        b.set(3, 1, 9, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        b.set(-2, 1, 9, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        b.set(2, 1, 15, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState());
        b.fill(-2, 1, 17, 2, 1, 17, Blocks.OAK_FENCE.defaultBlockState());

        // arcade: back wall, pillars, slab roof
        b.fill(-16, 0, 3, -9, 0, 21, Blocks.POLISHED_ANDESITE.defaultBlockState());
        b.fill(-15, 1, 4, -15, 5, 20, Blocks.WHITE_CONCRETE.defaultBlockState());
        for (int z = 4; z <= 19; z += 3) {
            b.fill(-12, 1, z, -12, 4, z, Blocks.STONE_BRICKS.defaultBlockState());
        }
        b.fill(-14, 5, 4, -12, 5, 20, Blocks.STONE_BRICK_SLAB.defaultBlockState());

        // two-storey house, door on the west, open windows upstairs only
        b.fill(9, 1, 4, 19, 9, 16, Blocks.STONE_BRICKS.defaultBlockState());
        b.fill(10, 1, 5, 18, 9, 15, Blocks.AIR.defaultBlockState());
        b.fill(10, 0, 5, 18, 0, 15, Blocks.OAK_PLANKS.defaultBlockState());
        b.fill(10, 5, 5, 18, 5, 15, Blocks.SPRUCE_PLANKS.defaultBlockState());
        b.fill(9, 10, 4, 19, 10, 16, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        b.fill(9, 1, 9, 9, 3, 11, Blocks.AIR.defaultBlockState());
        b.fill(9, 7, 7, 9, 8, 13, Blocks.AIR.defaultBlockState());
        b.fill(12, 7, 4, 16, 8, 4, Blocks.AIR.defaultBlockState());
        b.fill(12, 7, 16, 16, 8, 16, Blocks.AIR.defaultBlockState());
        b.fill(18, 1, 7, 18, 3, 8, Blocks.BOOKSHELF.defaultBlockState());
        b.set(16, 1, 13, Blocks.CRAFTING_TABLE.defaultBlockState());
        b.set(17, 1, 13, Blocks.BARREL.defaultBlockState());
        b.fill(11, 6, 6, 17, 6, 14, Blocks.RED_CARPET.defaultBlockState());
        b.set(7, 1, 8, Blocks.TORCH.defaultBlockState());

        // tunnel, open at both ends
        b.fill(-10, 0, 24, 10, 0, 30, Blocks.COBBLESTONE.defaultBlockState());
        b.fill(-9, 1, 25, 9, 4, 29, Blocks.STONE.defaultBlockState());
        b.fill(-9, 1, 26, 9, 3, 28, Blocks.AIR.defaultBlockState());

        // searchlight tower between the courtyard and the tunnel
        b.fill(0, 1, 21, 0, 8, 21, Blocks.STONE_BRICKS.defaultBlockState());
        b.set(0, 9, 21, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());

        // small things for contact shadows, by the fire
        b.fill(-4, 1, 3, 4, 1, 3, Blocks.OAK_FENCE.defaultBlockState());
        b.set(-7, 1, 3, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
        b.set(-6, 1, 3, Blocks.STONE_BRICK_SLAB.defaultBlockState());
        b.set(6, 1, 3, Blocks.COBBLESTONE_WALL.defaultBlockState());

        b.connectShapes();
        level.setDayTime(18000);
        level.setWeatherParameters(12000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
    }

    /** Server thread. */
    public static void teleport(ServerPlayer player, BlockPos origin, View view) {
        player.getAbilities().flying = player.getAbilities().mayfly;
        player.onUpdateAbilities();
        player.teleportTo(player.serverLevel(), origin.getX() + view.x(), origin.getY() + 1 + view.y(),
                origin.getZ() + view.z(), view.yaw(), view.pitch());
    }

    /** Render thread. Starts the demo lights around {@code origin}. */
    public static void startLights(BlockPos origin) {
        ground = origin;
        SCATTERED.clear();
        PhotonLights.addProvider(PROVIDER);
        PhotonLights.addFogProvider(FOG_PROVIDER);
    }

    public static void stopLights() {
        SCATTERED.clear();
        PhotonLights.removeProvider(PROVIDER);
        PhotonLights.removeFogProvider(FOG_PROVIDER);
    }

    private static void submitFog(FogSink sink, float partialTick) {
        if (fogDensity <= 0 || ground == null) return;
        sink.next().at(ground.getX() + 0.5, ground.getY() + 3.5, ground.getZ() + 12.5).size(14, 5, 12).density(fogDensity);
    }

    /** Static lights over the courtyard, for load tests; the first {@code shadowed} cast shadows. */
    public static void addScatteredLights(BlockPos origin, int count, int shadowed, long seed) {
        var random = new Random(seed);
        double ox = origin.getX() + 0.5;
        double oy = origin.getY() + 1;
        double oz = origin.getZ() + 12.5;
        for (int i = 0; i < count; i++) {
            int rgb = Mth.hsvToRgb(random.nextFloat(), 0.8f, 1f);
            SCATTERED.add(new DynamicLight()
                    .color((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f)
                    .intensity(6).range(5).shadows(i < shadowed)
                    .at(ox + (random.nextDouble() * 2 - 1) * 6, oy + 0.6 + random.nextDouble() * 2.5,
                            oz + (random.nextDouble() * 2 - 1) * 6));
        }
    }

    private static void submit(LightSink pool, float partialTick) {
        LightSink sink = () -> pool.next().volumetric(volumetric);
        var level = Minecraft.getInstance().level;
        if (ground == null || level == null) return;
        float t = frozenSeconds >= 0 ? frozenSeconds : (level.getGameTime() + partialTick) / 20f;
        double ox = ground.getX(), oy = ground.getY() + 1, oz = ground.getZ();
        for (int k = 0; k < 3; k++) {
            double angle = t * 0.45 + k * Math.PI * 2 / 3;
            sink.next().color(RGB[k][0], RGB[k][1], RGB[k][2]).intensity(30).range(14).sourceRadius(0.35f)
                    .at(ox + 0.5 + Math.cos(angle) * 4.2, oy + 2.5 + 0.4 * Math.sin(t * 0.9 + k), oz + 12.5 + Math.sin(angle) * 4.2);
        }
        // arcade, upstairs (a smoky room: six times the haze, so what leaks out of the windows shows), tunnel
        sink.next().color(0.2f, 1f, 0.75f).intensity(35).range(16).at(ox - 8.5, oy + 1.5, oz + 12 + 7.5 * Math.sin(t * 0.35));
        sink.next().color(1f, 0.18f, 0.08f).intensity(20 * (0.85f + 0.15f * Mth.sin(t * 3f))).range(12).at(ox + 14.5, oy + 6.5, oz + 10.5)
                .volumetric(volumetric * 6);
        sink.next().color(0.7f, 0.25f, 1f).intensity(20).range(10).at(ox + 0.5 + 7 * Math.sin(t * 0.3), oy + 1.2, oz + 27.5);
        // sweeps across the courtyard from the top of the tower
        sink.next().color(1f, 0.92f, 0.7f).intensity(300).range(30).spot(10, 16).at(ox + 0.5, oy + 9.6, oz + 21.5)
                .direction((float) (7 * Math.sin(t * 0.5)), -9.6f, -9f);
        // the fire by the fence row
        sink.next().color(1f, 0.45f, 0.12f).intensity(10 * (0.8f + 0.12f * Mth.sin(t * 13f) + 0.08f * Mth.sin(t * 29f + 1.3f)))
                .range(8).at(ox + 0.5, oy + 0.3, oz + 1.6);
        for (var light : SCATTERED) {
            sink.next().set(light).volumetric(volumetric);
        }
    }

    private static final class Builder {
        private final ServerLevel level;
        private final BlockPos origin;
        private final List<BlockPos> connectable = new ArrayList<>();

        private Builder(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        private void set(int x, int y, int z, BlockState state) {
            var pos = origin.offset(x, y, z);
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            if (state.is(Blocks.OAK_FENCE) || state.is(Blocks.COBBLESTONE_WALL) || state.getBlock() instanceof StairBlock) {
                connectable.add(pos);
            }
        }

        private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        set(x, y, z, state);
                    }
                }
            }
        }

        private void connectShapes() {
            for (var pos : connectable) {
                var state = level.getBlockState(pos);
                level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_CLIENTS);
            }
        }
    }
}
