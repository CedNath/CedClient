package ced.cedclient.render;

import ced.cedclient.accessor.CedClientPlayerRenderStateAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import ced.cedclient.render.pipeline.CustomRenderType;

/**
 * Halo cosmetic: a pixel-art ring of 1/16-block cubes above the head of any player whose CosmeticsSync entry has a
 * halo color (set on the site's dashboard, off with the Cosmetics module). Placed in head-bone space so it turns and
 * tilts with the head.
 */
public class HaloLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final float PX = 1.0F / 16.0F;
    // Block units in head space; model Y points down, head top is at -0.5.
    private static final float HEIGHT = -0.72F;
    // Top-down view of the ring, one char per pixel. 10 px wide on an 8 px head.
    private static final String[] SHAPE = {
            "...####...",
            ".##....##.",
            ".#......#.",
            "#........#",
            "#........#",
            "#........#",
            "#........#",
            ".#......#.",
            ".##....##.",
            "...####...",
    };
    // Vanilla block face shading: top, bottom, north/south, east/west.
    private static final float TOP = 1.0F, BOTTOM = 0.5F, NS = 0.8F, EW = 0.6F;

    public HaloLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        int color = ((CedClientPlayerRenderStateAccessor) state).cedclient$getHaloColor();
        if (color < 0 || state.isInvisible) return;

        poseStack.pushPose();
        PlayerModel model = getParentModel();
        model.root().translateAndRotate(poseStack);
        model.translateToHead(poseStack);
        poseStack.translate(-SHAPE.length * PX / 2, HEIGHT, -SHAPE.length * PX / 2);
        collector.submitCustomGeometry(poseStack, CustomRenderType.INSTANCE.getSOLID_QUADS(), (pose, buffer) -> {
            for (int z = 0; z < SHAPE.length; z++)
                for (int x = 0; x < SHAPE[z].length(); x++)
                    if (SHAPE[z].charAt(x) == '#') cube(pose, buffer, color, x * PX, z * PX);
        });
        poseStack.popPose();
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer b, int color, float x0, float z0) {
        float x1 = x0 + PX, z1 = z0 + PX, y0 = -PX / 2, y1 = PX / 2; // y0 = top (model Y is down)
        quad(pose, b, color, TOP, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(pose, b, color, BOTTOM, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(pose, b, color, NS, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(pose, b, color, NS, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(pose, b, color, EW, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(pose, b, color, EW, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer b, int color, float shade, float... v) {
        int r = (int) ((color >> 16 & 0xFF) * shade), g = (int) ((color >> 8 & 0xFF) * shade), bl = (int) ((color & 0xFF) * shade);
        for (int i = 0; i < 12; i += 3) b.addVertex(pose, v[i], v[i + 1], v[i + 2]).setColor(r, g, bl, 255);
    }
}
