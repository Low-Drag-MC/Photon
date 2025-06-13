package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.client.utils.RenderUtils;
import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.ui.menu.ViewMenu;
import com.lowdragmc.lowdraglib2.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib2.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib2.utils.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.TrackedDummyWorld;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.FXProjectEffect;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

public class ParticleScenePanel extends WidgetGroup {
    public final FXEditor editor;
    public final FXProject project;
    @Getter
    @Nullable
    protected FXObjectsList fxObjectsList;
    @Getter
    protected final ParticleScene scene;
    @Getter
    protected final TrackedDummyWorld level;
    @Getter
    protected final FXData fxData;
    protected final FXProjectEffect effect;

    // runtime
    @Getter
    protected final FXRuntime runtime;
    @Getter
    protected boolean hoverSelected, draggingSelected;
    @Getter
    protected final ParticleInfoView particleInfoView;

    public ParticleScenePanel(FXEditor editor, FXProject project, FXData fxData) {
        super(0, editor.getMenuPanel().getSizeHeight() + 16, editor.getSize().getWidth() - editor.getConfigPanel().getSizeWidth(),
                editor.getSize().height - editor.getMenuPanel().getSizeHeight() - 16);
        this.fxData = fxData;
        this.editor = editor;
        this.project = project;
        this.runtime = new FXRuntime(project.fx, fxData, false, false);
        this.runtime.root.updatePos(new Vector3f(0.5f, 2, 0.5f));
        addWidget(scene = new ParticleScene(0, 0, this.getSize().width, this.getSize().height));
        scene.setRenderFacing(false);
        scene.setRenderSelect(false);
        scene.useCacheBuffer();
        scene.createScene(level = new TrackedDummyWorld());
        scene.setAfterWorldRender(this::renderAfterWorld);
        this.effect = new FXProjectEffect(level);
        this.particleInfoView = new ParticleInfoView(this);
        this.particleInfoView.setSelfPosition(
                editor.getSizeWidth() - editor.getConfigPanel().getSizeWidth() - particleInfoView.getSizeWidth() - 5,
                editor.getSizeHeight() - editor.getResourcePanel().getSizeHeight() - particleInfoView.getSizeHeight() - 5);
    }

    /**
     * Called when the panel is selected/switched to.
     */
    public void onPanelSelected() {
        editor.getConfigPanel().clearAllConfigurators();
        editor.getToolPanel().clearAllWidgets();
        editor.getToolPanel().setTitle("photon.gui.editor.fx.particle_panel.fx_object_list");
        editor.getToolPanel().addNewToolBox("photon.gui.editor.fx.particle_panel.fx_object_list", Icons.WIDGET_CUSTOM, size -> fxObjectsList = new FXObjectsList(this, size));
        if (editor.getToolPanel().inAnimate()) {
            editor.getToolPanel().getAnimation().appendOnFinish(() -> editor.getToolPanel().show());
        } else {
            editor.getToolPanel().show();
        }
        if (editor.getMenuPanel().getTabs().get("view") instanceof ViewMenu viewMenu) {
            viewMenu.removeView("particle_info");
            viewMenu.openView(particleInfoView);
        }
        resetScene();
    }

    /**
     * Called when the panel is deselected/switched from.
     */
    public void onPanelDeselected() {
        editor.getToolPanel().setTitle("ldlib.gui.editor.group.tool_box");
        editor.getToolPanel().hide();
        editor.getToolPanel().clearAllWidgets();
        editor.getConfigPanel().clearAllConfigurators();
    }

    /**
     * Call this method to restart Emitters emission.
     */
    public void restartEmitters() {
        scene.getParticleManager().clearAllParticles();
        runtime.emmit(effect);
    }

    /**
     * Reset the scene blocks according to the {@link SceneMenu}.
     */
    public void resetScene() {
        this.level.clear();

        if (editor.getMenuPanel().getTabs().get("scene") instanceof SceneMenu sceneMenu) {
            scene.setRenderedCore(sceneMenu.createScene(level), null);
        } else {
            Set<BlockPos> plane = new HashSet<>();
            int i = 0;
            for (int x = -5; x < 6; x++) {
                for (int z = -5; z < 6; z++) {
                    plane.add(new BlockPos(x, 0, z));
                    level.addBlock(new BlockPos(x, 0, z), BlockInfo.fromBlock(i % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE));
                    i++;
                }
            }
            scene.setRenderedCore(plane, null);
        }

        var center = new Vector3f(0.5F, 2, 0.5F);
        var zoom = scene.getZoom();
        var range = scene.getRange();
        scene.setCenter(center);
        scene.getRenderer().setCameraOrtho(range * zoom, scene.getRange() * zoom, range * zoom);
        scene.getRenderer().setCameraLookAt(center, scene.camZoom(), Math.toRadians(scene.getRotationPitch()), Math.toRadians(scene.getRotationYaw()));
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

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSelected) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingSelected = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public void renderAfterWorld(SceneWidget _scene) {
        hoverSelected = false;
        renderBox(new PoseStack(), new AABB(0, 0, 0, 0, 0, 0), 0, 0, 0);
        if (project.isDraggable() && fxObjectsList != null) {
            var selected = fxObjectsList.getSelected();
            if (selected != null) {
                PoseStack poseStack = new PoseStack();
                var position = selected.transform().position();
                var aabb = new AABB(position.x - 0.1, position.y - 0.1, position.z - 0.1, position.x + 0.1, position.y + 0.1, position.z + 0.1);
                renderBox(poseStack, aabb, 1, 0, 0);

                //un project
                var ray = scene.unProject(scene.getLastMouseX(), scene.getLastMouseY());
                if (draggingSelected) {
                    var pos = selected.transform().position();
                    var vec = new Vector3f(ray.endPos()).sub(new Vector3f(ray.startPos()));
                    var lookVec = pos.sub(new Vector3f(ray.startPos()));
                    var mag = lookVec.length();
                    var draggedPos = new Vector3f(ray.startPos()).add(Vector3fHelper.project(lookVec, vec).normalize().mul(mag));
                    selected.updatePos(draggedPos);
                } else {
                    ray = ray.toInfinite();
                    var result = aabb.clip(new Vec3(ray.startPos()), new Vec3(ray.endPos()));
                    if (result.isPresent()) {
                        hoverSelected = true;
                    }
                }
            }
        }
        if (project.isRenderCullBox() && fxObjectsList != null) {
            var selected = fxObjectsList.getSelected();
            if (selected instanceof IParticleEmitter emitter) {
                PoseStack poseStack = new PoseStack();
                var aabb = emitter.getCullBox(Minecraft.getInstance().getFrameTime());
                if (aabb != null) {
                    renderBox(poseStack, aabb, 0.5f, 0.5f, 0.5f);
                }
            }
        }
    }

    public static void renderBox(PoseStack poseStack, AABB aabb, float r, float g, float b) {
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
