package ced.cedclient.render;

import ced.cedclient.features.impl.render.ChinaHat;
import ced.cedclient.render.pipeline.CustomRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * China Hat module: a rainbow cone above the head of the local player only. Same head-bone space and custom geometry
 * pipeline as HaloLayer; each slice of the cone is a triangle written as a quad with its first corner twice.
 */
public class ChinaHatLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final int SEGMENTS = 32;
    // Model Y points down; the head top is at -0.5.
    private static final float BASE_Y = -0.5F - 0.02F;

    public ChinaHatLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        if (!ChinaHat.INSTANCE.isEnabled() || state.isInvisible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId()) return;

        float radius = ChinaHat.radius();
        float apexY = BASE_Y - ChinaHat.height();
        float spin = (System.currentTimeMillis() % 3_600_000L) / 1000.0F * ChinaHat.speed();

        poseStack.pushPose();
        PlayerModel model = getParentModel();
        model.root().translateAndRotate(poseStack);
        model.translateToHead(poseStack);
        collector.submitCustomGeometry(poseStack, CustomRenderType.INSTANCE.getSOLID_QUADS(), (pose, buffer) -> {
            for (int i = 0; i < SEGMENTS; i++) {
                float a0 = (float) (2 * Math.PI * i / SEGMENTS);
                float a1 = (float) (2 * Math.PI * (i + 1) / SEGMENTS);
                int color = Mth.hsvToRgb(((float) i / SEGMENTS + spin) % 1.0F, 0.8F, 1.0F) & 0xFFFFFF;
                float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius;
                float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
                // A touch of shading per slice so the cone reads as a cone and not a flat disc.
                float shade = 0.75F + 0.25F * Mth.cos(a0 + a1);
                triangle(pose, buffer, color, shade, 0, apexY, 0, x1, BASE_Y, z1, x0, BASE_Y, z0);
            }
        });
        poseStack.popPose();
    }

    private static void triangle(PoseStack.Pose pose, VertexConsumer b, int color, float shade,
                                 float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
        int r = (int) ((color >> 16 & 0xFF) * shade), g = (int) ((color >> 8 & 0xFF) * shade), bl = (int) ((color & 0xFF) * shade);
        b.addVertex(pose, ax, ay, az).setColor(r, g, bl, 255);
        b.addVertex(pose, bx, by, bz).setColor(r, g, bl, 255);
        b.addVertex(pose, cx, cy, cz).setColor(r, g, bl, 255);
        b.addVertex(pose, ax, ay, az).setColor(r, g, bl, 255);
    }
}
