package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.Platform;
import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib.client.utils.RenderUtils;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.MenuPanel;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import com.lowdragmc.lowdraglib.utils.Vector3fHelper;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector3f;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote ParticleScene
 */
@Environment(EnvType.CLIENT)
public class ParticleScene extends SceneWidget {
    @Getter
    protected final TrackedDummyWorld level = new TrackedDummyWorld();
    @Getter
    protected final PhotonParticleManager particleManager = new PhotonParticleManager();
    @Getter
    protected final ParticleEditor editor;
    @Getter
    protected boolean hoverSelected, draggingSelected;
    // runtime
    private boolean lockX, lockY, lockZ;

    public ParticleScene(ParticleEditor editor) {
        super(0, MenuPanel.HEIGHT, editor.getSize().getWidth() - ConfigPanel.WIDTH, editor.getSize().height - MenuPanel.HEIGHT, null);
        this.editor = editor;
        setRenderFacing(false);
        setRenderSelect(false);
        if (!Photon.isShaderModInstalled() || Platform.isForge()) {
            useCacheBuffer();
        }
        resetScene();
        var buttonGroup = initButtons();
        buttonGroup.addSelfPosition((getSize().width - buttonGroup.getSize().width) / 2, 10);
        addWidget(buttonGroup);
    }

    private WidgetGroup initButtons() {
        WidgetGroup group = new WidgetGroup(0, 0, 70, 20);
        // X Y Z
        var lockXButton = new SwitchWidget(0, 0, 20, 20, (cd, pressed) -> {
            lockX = pressed;
            if (pressed) {
                lockY = false;
                lockZ = false;
                setCameraYawAndPitchAnima(0, 0, 20);
            }
        }).setSupplier(() -> lockX).setTexture(
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("X")),
                new GuiTextureGroup(ColorPattern.T_GREEN.rectTexture().setRadius(5), new TextTexture("X")));
        group.addWidget(lockXButton);
        var lockYButton = new SwitchWidget(25, 0, 20, 20, (cd, pressed) -> {
            lockY = pressed;
            if (pressed) {
                lockX = false;
                lockZ = false;
                setCameraYawAndPitchAnima(89.9f, 0, 20);
            }
        }).setSupplier(() -> lockY).setTexture(
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("Y")),
                new GuiTextureGroup(ColorPattern.T_GREEN.rectTexture().setRadius(5), new TextTexture("Y")));
        group.addWidget(lockYButton);
        var lockZButton = new SwitchWidget(50, 0, 20, 20, (cd, pressed) -> {
            lockZ = pressed;
            if (pressed) {
                lockX = false;
                lockY = false;
                setCameraYawAndPitchAnima(0, 90, 20);
            }
        }).setSupplier(() -> lockZ).setTexture(
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("Z")),
                new GuiTextureGroup(ColorPattern.T_GREEN.rectTexture().setRadius(5), new TextTexture("Z")));
        group.addWidget(lockZButton);
        return group;
    }

    @Override
    protected ParticleManager createParticleManager() {
        return particleManager;
    }

    public void resetScene() {
        this.level.clear();
        createScene(level);
        renderer.setOnLookingAt(null);
        Set<BlockPos> plane = new HashSet<>();
        for (int x = -5; x < 6; x++) {
            for (int z = -5; z < 6; z++) {
                plane.add(new BlockPos(x, 0, z));
                level.addBlock(new BlockPos(x, 0, z), BlockInfo.fromBlock(Blocks.GRASS_BLOCK));
            }
        }
        plane.add(new BlockPos(0, 6, 0));
        setRenderedCore(plane, null);
    }

    @Override
    public void renderBlockOverLay(WorldSceneRenderer renderer) {
        hoverSelected = false;
        if (editor.isDraggable() && editor.getEmittersList() != null) {
            var selected = editor.getEmittersList().getSelected();
            if (selected != null) {
                PoseStack poseStack = new PoseStack();
                var position = selected.self().getPos(Minecraft.getInstance().getFrameTime());
                var aabb = new AABB(position.x - 0.1, position.y - 0.1, position.z - 0.1, position.x + 0.1, position.y + 0.1, position.z + 0.1);
                renderSelectedEmitter(poseStack, aabb, 1, 0, 0);

                //un project
                Vector3f hitPos = unProject(currentMouseX, currentMouseY);

                if (draggingSelected) {
                    var pos = selected.self().getPos();
                    var vec = new Vector3f(hitPos).sub(new Vector3f(renderer.getEyePos()));
                    var lookVec = pos.sub(new Vector3f(renderer.getEyePos()));
                    var mag = lookVec.length();
                    var draggedPos = new Vector3f(renderer.getEyePos()).add(Vector3fHelper.project(lookVec, vec).normalize().mul(mag));
                    selected.self().setPos(draggedPos, true);
                    if (editor.isDragAll() && editor.getCurrentProject() instanceof ParticleProject project) {
                        for (IParticleEmitter emitter : project.getEmitters()) {
                            emitter.self().setPos(draggedPos, true);
                        }
                    }
                } else {
                    Vec3 startPos = new Vec3(renderer.getEyePos());
                    hitPos.mul(2); // Double view range to ensure pos can be seen.
                    Vec3 endPos = new Vec3((hitPos.x() - startPos.x), (hitPos.y() - startPos.y), (hitPos.z() - startPos.z));
                    var result = aabb.clip(startPos, endPos);

                    if (result.isPresent()) {
                        hoverSelected = true;
                    }
                }
            }
        }
    }

    private Vector3f unProject(double mouseX, double mouseY) {
        Window window = Minecraft.getInstance().getWindow();
        int windowX = (int) (mouseX / (window.getGuiScaledWidth() * 1.0) * window.getWidth());
        int windowY = window.getHeight() - (int) (mouseY / (window.getGuiScaledHeight() * 1.0) * window.getHeight());
        return renderer.unProject(windowX, windowY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOverElement(mouseX, mouseY)) {
            if (hoverSelected) {
                draggingSelected = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSelected) {
            return true;
        }
        if (lockY || lockZ || lockX) return false;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingSelected = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public static void renderSelectedEmitter(PoseStack poseStack, AABB aabb, float r, float g, float b) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        poseStack.pushPose();

        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder buffer = tessellator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        RenderUtils.renderCubeFace(poseStack, buffer, (float) aabb.minX, (float) aabb.minY, (float) aabb.minZ, (float) aabb.maxX, (float) aabb.maxY, (float) aabb.maxZ, r, g, b, 1);
        tessellator.end();

        poseStack.popPose();

        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }
}
