package ced.cedclient.render;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wraps every block model while Cosmetic Blocks needs re-texturing (Keep Block Size / brush shapes), ported from
 * the cb mod's CFMEModelWrapper:
 *  - retexture: draw this position's geometry with another block's texture (stairs that look like glass, "|shape=")
 *  - original shape: draw the original stair/slab geometry with the replacement's texture (see-through replacements)
 *  - otherwise: the normal model, with a cull test that doesn't hide faces next to replaced stairs/slabs.
 */
public final class CosmeticModelWrapper implements BlockStateModel {
    private static final List<Direction> DIRECTIONS;
    static {
        List<Direction> d = new ArrayList<>(Arrays.asList(Direction.values()));
        d.add(null);
        DIRECTIONS = Collections.unmodifiableList(d);
    }

    private final BlockStateModel wrapped;

    public CosmeticModelWrapper(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    public BlockStateModel getWrapped() {
        return wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        BlockState retexture = CosmeticHooks.retextureAt(pos.getX(), pos.getY(), pos.getZ());
        if (retexture != null) {
            emitRetextured(emitter, wrapped, random, cullTest, particle(retexture), true, Collections.emptySet());
            return;
        }
        BlockState original = CosmeticHooks.originalShapeAt(pos.getX(), pos.getY(), pos.getZ());
        if (original != null) {
            BlockStateModel originalModel = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(original);
            if (originalModel instanceof CosmeticModelWrapper w) originalModel = w.wrapped;
            Set<Direction> connected = EnumSet.noneOf(Direction.class);
            for (Direction dir : Direction.values()) {
                BlockState adj = blockView.getBlockState(pos.relative(dir));
                if (state.skipRendering(adj, dir)) connected.add(dir);
            }
            emitRetextured(emitter, originalModel, random, cullTest, particle(state), true, connected);
            return;
        }
        Predicate<Direction> modified = dir -> {
            if (dir == null) return false;
            int ax = pos.getX() + dir.getStepX(), ay = pos.getY() + dir.getStepY(), az = pos.getZ() + dir.getStepZ();
            BlockState adjReplacement = CosmeticHooks.replacementAt(ax, ay, az, false);
            if (adjReplacement != null) {
                if (adjReplacement.getBlock() instanceof StairBlock || adjReplacement.getBlock() instanceof SlabBlock) return false;
                if (CosmeticHooks.originalShapeAt(ax, ay, az) != null) return false;
            }
            BlockState adj = blockView.getBlockState(new BlockPos(ax, ay, az));
            if (state.skipRendering(adj, dir)) return true;
            if (state.getBlock() == adj.getBlock() && state.getBlock() instanceof IronBarsBlock) return true;
            return cullTest.test(dir);
        };
        wrapped.emitQuads(emitter, blockView, pos, state, random, modified);
    }

    private static Material.Baked particle(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state);
    }

    private static void emitRetextured(QuadEmitter emitter, BlockStateModel geometry, RandomSource random, Predicate<Direction> cullTest,
                                       Material.Baked material, boolean skipCull, Set<Direction> connected) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        geometry.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            TriState ao = part.useAmbientOcclusion() ? TriState.DEFAULT : TriState.FALSE;
            for (Direction dir : DIRECTIONS) {
                if (dir != null && connected.contains(dir)) continue;
                if (!skipCull && cullTest.test(dir)) continue;
                for (BakedQuad quad : part.getQuads(dir)) {
                    emitter.cullFace(skipCull ? null : dir);
                    emitter.fromBakedQuad(quad);
                    normalizeUVs(emitter, quad);
                    emitter.materialBake(material, 32);
                    emitter.tintIndex(-1);
                    emitter.ambientOcclusion(ao);
                    emitter.shadeMode(ShadeMode.VANILLA);
                    emitter.emit();
                }
            }
        }
    }

    /** Turns the quad's atlas UVs back into 0..1 so materialBake can map them onto the new sprite. */
    private static void normalizeUVs(QuadEmitter emitter, BakedQuad quad) {
        TextureAtlasSprite old = quad.materialInfo().sprite();
        float u0 = old.getU0(), v0 = old.getV0();
        float ur = old.getU1() - u0, vr = old.getV1() - v0;
        if (ur <= 0f || vr <= 0f) return;
        for (int v = 0; v < 4; v++) {
            emitter.uv(v, (emitter.u(v) - u0) / ur, (emitter.v(v) - v0) / vr);
        }
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        wrapped.collectParts(random, output);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped.particleMaterial();
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags() {
        return wrapped.materialFlags();
    }
}
