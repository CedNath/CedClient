package ced.cedclient.render;

import ced.cedclient.features.impl.render.ChinaHat;
import ced.cedclient.render.pipeline.CustomRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ced.cedclient.features.impl.render.HardcodedCosmetics;
import ced.cedclient.state.CosmeticOverride;
import ced.cedclient.state.CosmeticsSync;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * China Hat module: a rainbow cone above the head of you, and players the site gave one. Same head-bone space and custom geometry
 * pipeline as HaloLayer; each slice of the cone is a triangle written as a quad with its first corner twice.
 */
public class ChinaHatLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final int SEGMENTS = 32;
    // Model Y points down; the head top is at -0.5.
    private static final float BASE_Y = -0.5F - 0.02F;
    // Height of the vertical rim around the base, so the hat has an edge instead of being paper thin.
    private static final float RIM = 0.03F;

    public ChinaHatLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Your own, from the module; anyone else's only if the site gave them one (Cosmetics > China Hats).
        boolean own = state.id == mc.player.getId() && ChinaHat.INSTANCE.isEnabled();
        boolean straw = own && ChinaHat.straw();
        int fixed = -1; // 0xRRGGBB set on the site, -1 = rainbow
        if (!own) {
            if (!HardcodedCosmetics.INSTANCE.isEnabled() || !HardcodedCosmetics.INSTANCE.getChinaHats().getValue()) return;
            Entity entity = mc.level.getEntity(state.id);
            if (!(entity instanceof Player player)) return;
            CosmeticOverride o = CosmeticsSync.INSTANCE.getOverride(player.getGameProfile().name());
            if (o == null || !o.getChinaHat()) return;
            straw = o.getChinaStraw();
            if (o.getChinaColor() != null) fixed = o.getChinaColor();
        }

        float radius = ChinaHat.radius();
        float apexY = BASE_Y - ChinaHat.height();
        float spin = (System.currentTimeMillis() % 3_600_000L) / 1000.0F * ChinaHat.speed();
        final int fixedColor = fixed;

        poseStack.pushPose();
        PlayerModel model = getParentModel();
        model.root().translateAndRotate(poseStack);
        model.translateToHead(poseStack);
        if (straw) {
            collector.submitCustomGeometry(poseStack, CustomRenderType.INSTANCE.getSOLID_QUADS(), (pose, buffer) -> straw(pose, buffer, radius, apexY));
            poseStack.popPose();
            return;
        }
        collector.submitCustomGeometry(poseStack, CustomRenderType.INSTANCE.getSOLID_QUADS(), (pose, buffer) -> {
            for (int i = 0; i < SEGMENTS; i++) {
                float a0 = (float) (2 * Math.PI * i / SEGMENTS);
                float a1 = (float) (2 * Math.PI * (i + 1) / SEGMENTS);
                int color = fixedColor >= 0 ? fixedColor : Mth.hsvToRgb(((float) i / SEGMENTS + spin) % 1.0F, 0.8F, 1.0F) & 0xFFFFFF;
                float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius;
                float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
                // A touch of shading per slice so the cone reads as a cone and not a flat disc.
                float shade = 0.75F + 0.25F * Mth.cos(a0 + a1);
                triangle(pose, buffer, color, shade, 0, apexY, 0, x1, BASE_Y, z1, x0, BASE_Y, z0);
                // Rim: a short vertical band under the cone's edge.
                triangle(pose, buffer, color, shade * 0.9F, x0, BASE_Y, z0, x1, BASE_Y, z1, x1, BASE_Y + RIM, z1);
                triangle(pose, buffer, color, shade * 0.9F, x0, BASE_Y, z0, x1, BASE_Y + RIM, z1, x0, BASE_Y + RIM, z0);
            }
        });
        poseStack.popPose();
    }

    private static final int WEAVE_RINGS = 12, WEAVE_RIBS = 48;

    /** Woven straw cone: checker of light/dark straw cells, thin ribs, a red ribbon band and a knob at the tip. */
    private static void straw(PoseStack.Pose pose, VertexConsumer b, float radius, float apexY) {
        float height = BASE_Y - apexY;
        for (int k = 0; k < WEAVE_RINGS; k++) {
            float t0 = (float) k / WEAVE_RINGS, t1 = (float) (k + 1) / WEAVE_RINGS;
            float r0 = radius * (1 - t0), r1 = radius * (1 - t1);
            float y0 = BASE_Y - height * t0;
            float y1 = BASE_Y - height * t1;
            boolean ribbon = k == 1;
            for (int i = 0; i < WEAVE_RIBS; i++) {
                float a0 = (float) (2 * Math.PI * i / WEAVE_RIBS), a1 = (float) (2 * Math.PI * (i + 1) / WEAVE_RIBS);
                int c;
                if (ribbon) c = (i & 1) == 0 ? 0xA01818 : 0x8A1212;
                else if (i % 4 == 0) c = 0x8A6A2C; // dark rib line
                else c = ((i + k) & 1) == 0 ? 0xE2C072 : 0xD2AE5C;
                float shade = 0.7F + 0.3F * Mth.cos((a0 + a1) / 2 - 0.8F);
                quad(pose, b, c, shade,
                        Mth.cos(a1) * r1, y1, Mth.sin(a1) * r1, Mth.cos(a1) * r0, y0, Mth.sin(a1) * r0,
                        Mth.cos(a0) * r0, y0, Mth.sin(a0) * r0, Mth.cos(a0) * r1, y1, Mth.sin(a0) * r1);
                if (k == 0) { // brim edge
                    float rr = r0;
                    quad(pose, b, 0xB8934A, shade * 0.85F,
                            Mth.cos(a1) * rr, y0, Mth.sin(a1) * rr, Mth.cos(a1) * rr, y0 + RIM, Mth.sin(a1) * rr,
                            Mth.cos(a0) * rr, y0 + RIM, Mth.sin(a0) * rr, Mth.cos(a0) * rr, y0, Mth.sin(a0) * rr);
                }
            }
        }
        // Knob on top.
        float kr = radius * 0.08F, ky = apexY - 0.03F;
        for (int i = 0; i < 8; i++) {
            float a0 = (float) (2 * Math.PI * i / 8), a1 = (float) (2 * Math.PI * (i + 1) / 8);
            triangle(pose, b, 0x8A6A2C, 0.9F, 0, ky, 0, Mth.cos(a1) * kr, apexY, Mth.sin(a1) * kr, Mth.cos(a0) * kr, apexY, Mth.sin(a0) * kr);
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer b, int color, float shade,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        int r = (int) ((color >> 16 & 0xFF) * shade), g = (int) ((color >> 8 & 0xFF) * shade), bl = (int) ((color & 0xFF) * shade);
        b.addVertex(pose, ax, ay, az).setColor(r, g, bl, 255);
        b.addVertex(pose, bx, by, bz).setColor(r, g, bl, 255);
        b.addVertex(pose, cx, cy, cz).setColor(r, g, bl, 255);
        b.addVertex(pose, dx, dy, dz).setColor(r, g, bl, 255);
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
