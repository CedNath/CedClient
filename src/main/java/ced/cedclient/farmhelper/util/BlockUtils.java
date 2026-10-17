/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient (Mojang block classes and properties). See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BlockUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public enum Direction2 { FORWARD, BACKWARD, LEFT, RIGHT }

    private static boolean isInitialWalkable(Block block) {
        return block == Blocks.AIR || block == Blocks.WATER || block == Blocks.LILY_PAD || block instanceof WallSignBlock
                || block == Blocks.SUGAR_CANE || block == Blocks.PUMPKIN_STEM || block == Blocks.MELON_STEM;
    }

    public static float getUnitX() {
        return getUnitX((mc.player.getYRot() % 360 + 360) % 360);
    }

    public static float getUnitZ() {
        return getUnitZ((mc.player.getYRot() % 360 + 360) % 360);
    }

    public static float getUnitX(float modYaw) {
        float yaw = AngleUtils.get360RotationYaw(modYaw);
        if (yaw < 30) return 0;
        if (yaw < 150) return -1f;
        if (yaw < 210) return 0;
        if (yaw < 330) return 1f;
        return 0;
    }

    public static float getUnitZ(float modYaw) {
        float yaw = AngleUtils.get360RotationYaw(modYaw);
        if (yaw < 60) return 1f;
        if (yaw < 120) return 0;
        if (yaw < 240) return -1f;
        if (yaw < 300) return 0;
        return 1;
    }

    public static BlockState getBlockState(BlockPos pos) {
        return mc.level.getBlockState(pos);
    }

    public static Block getBlock(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock();
    }

    private static double feetY() {
        double y = mc.player.getY();
        return y % 1 > 0.7 ? Math.ceil(y) : y;
    }

    public static Block getRelativeBlock(float x, float y, float z) {
        return getBlock(getRelativeBlockPos(x, y, z));
    }

    public static BlockPos getRelativeBlockPos(float x, float y, float z) {
        return BlockPos.containing(
                mc.player.getX() + getUnitX() * z + getUnitZ() * -1 * x,
                feetY() + y,
                mc.player.getZ() + getUnitZ() * z + getUnitX() * x
        );
    }

    public static Block getRelativeBlock(float x, float y, float z, float yaw) {
        return getBlock(getRelativeBlockPos(x, y, z, yaw));
    }

    public static BlockPos getRelativeBlockPos(float x, float y, float z, float yaw) {
        return BlockPos.containing(
                mc.player.getX() + getUnitX(yaw) * z + getUnitZ(yaw) * -1 * x,
                feetY() + y,
                mc.player.getZ() + getUnitZ(yaw) * z + getUnitX(yaw) * x
        );
    }

    public static int bedrockCount() {
        int count = 0;
        BlockPos base = mc.player.blockPosition();
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                if (getBlock(base.offset(i, 1, j)) == Blocks.BEDROCK) count++;
            }
        }
        return count;
    }

    private static boolean isPassable(BlockState state, BlockPos pos) {
        return state.getCollisionShape(mc.level, pos).isEmpty();
    }

    public static boolean canWalkThrough(BlockPos blockPos) {
        return canWalkThrough(blockPos, null);
    }

    public static boolean canWalkThrough(BlockPos blockPos, Direction2 direction) {
        return canWalkThroughBottom(blockPos, direction) && canWalkThroughAbove(blockPos.above(), direction);
    }

    private static boolean canWalkThroughBottom(BlockPos blockPos, Direction2 direction) {
        BlockState state = mc.level.getBlockState(blockPos);
        Block block = state.getBlock();

        // No blocks down to 65: it's the void edge of the plot, never walk there.
        boolean allAir = true;
        for (int y = blockPos.getY(); y >= 65; y--) {
            if (mc.level.getBlockState(new BlockPos(blockPos.getX(), y, blockPos.getZ())).getBlock() != Blocks.AIR) {
                allAir = false;
                break;
            }
        }
        if (allAir) return false;

        double py = mc.player.getY() % 1;
        if (py >= 0.5 && py <= 0.75) return true;
        if (isInitialWalkable(block)) return true;
        if (block instanceof DoorBlock && direction != null) return canWalkThroughDoor(blockPos, direction);
        if (block instanceof FenceBlock) return false;
        if (block instanceof FenceGateBlock) return state.getValue(FenceGateBlock.OPEN);
        if (block instanceof TrapDoorBlock) return state.getValue(TrapDoorBlock.OPEN) || state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM;
        if (block instanceof SnowLayerBlock) return state.getValue(SnowLayerBlock.LAYERS) <= 5;
        if (block instanceof SlabBlock) {
            // On the bottom half of a slab, bottom slabs are walkable.
            if (py < 0.5) {
                SlabType type = state.getValue(SlabBlock.TYPE);
                if (type == SlabType.DOUBLE) return false;
                return type == SlabType.BOTTOM;
            }
        }
        if (block instanceof CarpetBlock) return true;
        if (block instanceof StairBlock) {
            // Walkable when the stairs face the way the player comes from.
            Direction facing = state.getValue(StairBlock.FACING);
            BlockPos diff = blockPos.subtract(mc.player.blockPosition());
            if (state.getValue(StairBlock.HALF) == Half.TOP) return false;
            if (facing == Direction.NORTH && diff.getZ() < 0) return true;
            if (facing == Direction.SOUTH && diff.getZ() > 0) return true;
            if (facing == Direction.WEST && diff.getX() < 0) return true;
            return facing == Direction.EAST && diff.getX() > 0;
        }
        return isPassable(state, blockPos);
    }

    private static boolean canWalkThroughAbove(BlockPos blockPos, Direction2 direction) {
        BlockState state = mc.level.getBlockState(blockPos);
        Block block = state.getBlock();
        if (block instanceof CarpetBlock) return false;
        if (block instanceof DoorBlock && direction != null) return canWalkThroughDoor(blockPos.below(), direction);
        if (block instanceof FenceBlock) return false;
        if (block instanceof FenceGateBlock) return state.getValue(FenceGateBlock.OPEN);
        if (block instanceof TrapDoorBlock) {
            Direction playerFacing = Direction.fromYRot(mc.player.getYRot());
            Direction doorFacing = state.getValue(TrapDoorBlock.FACING);
            boolean standingOnDoor = getRelativeBlockPos(0, 1, 0).equals(blockPos);
            if (state.getValue(TrapDoorBlock.OPEN) && direction != null) {
                return canWalkThroughDoorWithDirection(direction, playerFacing, doorFacing, standingOnDoor);
            }
            return state.getValue(TrapDoorBlock.HALF) == Half.TOP;
        }
        return isPassable(state, blockPos);
    }

    private static boolean canWalkThroughDoorWithDirection(Direction2 direction, Direction playerFacing, Direction doorFacing, boolean standingOnDoor) {
        switch (direction) {
            case FORWARD:
                if (doorFacing == playerFacing.getOpposite() && standingOnDoor) return false;
                if (!standingOnDoor && doorFacing == playerFacing) return false;
                break;
            case BACKWARD:
                if (doorFacing == playerFacing && standingOnDoor) return false;
                if (!standingOnDoor && doorFacing == playerFacing.getOpposite()) return false;
                break;
            case LEFT:
                if (doorFacing == playerFacing.getClockWise() && standingOnDoor) return false;
                if (!standingOnDoor && doorFacing == playerFacing.getCounterClockWise()) return false;
                break;
            case RIGHT:
                if (doorFacing == playerFacing.getCounterClockWise() && standingOnDoor) return false;
                if (!standingOnDoor && doorFacing == playerFacing.getClockWise()) return false;
                break;
        }
        return true;
    }

    public static boolean canWalkThroughDoor(Direction2 direction) {
        return canWalkThroughDoor(getRelativeBlockPos(0, 0, 0), direction);
    }

    public static boolean canWalkThroughDoor(BlockPos blockPos, Direction2 direction) {
        BlockState state = mc.level.getBlockState(blockPos);
        if (!(state.getBlock() instanceof DoorBlock)) return true;
        Direction playerFacing = Direction.fromYRot(mc.player.getYRot());
        Direction doorFacing = state.getValue(DoorBlock.FACING);
        boolean standingOnDoor = getRelativeBlockPos(0, 0, 0).equals(blockPos);
        return canWalkThroughDoorWithDirection(direction, playerFacing, doorFacing, standingOnDoor);
    }

    public static boolean isWater(Block block) {
        return block == Blocks.WATER;
    }

    private static boolean isReadyCropState(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CropBlock crop && crop.isMaxAge(state)
                || block instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) == 3
                || block instanceof CocoaBlock && state.getValue(CocoaBlock.AGE) == 2
                || block == Blocks.MELON
                || block == Blocks.PUMPKIN
                || block instanceof CactusBlock
                || block instanceof MushroomBlock;
    }

    /** Is [block] a crop the macros harvest (used to tell player breaks from placed blocks). */
    public static boolean isCrop(Block block) {
        return block instanceof CropBlock || block instanceof NetherWartBlock || block instanceof CocoaBlock
                || block == Blocks.MELON || block == Blocks.PUMPKIN || block instanceof CactusBlock
                || block instanceof MushroomBlock || block instanceof SugarCaneBlock || block instanceof StemBlock
                || block instanceof AttachedStemBlock || block instanceof TallFlowerBlock;
    }
}
