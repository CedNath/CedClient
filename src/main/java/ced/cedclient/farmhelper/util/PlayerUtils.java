/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient (rewarp / spawn points stored in the module). See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.features.impl.funqol.FarmHelper;
import ced.cedclient.utils.SkyblockItemKt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class PlayerUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    public static final Clock changeItemEveryClock = new Clock();
    public static boolean itemChangedByStaff = false;

    public static boolean isInventoryFull() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isCropBlock(Block block) {
        return block instanceof CropBlock || block instanceof SugarCaneBlock || block instanceof CocoaBlock
                || block instanceof NetherWartBlock || block == Blocks.MELON || block == Blocks.PUMPKIN
                || block instanceof MushroomBlock || block instanceof CactusBlock || block instanceof TallFlowerBlock;
    }

    public static FarmConfig.CropEnum cropOf(Block block) {
        if (block == Blocks.WHEAT) return FarmConfig.CropEnum.WHEAT;
        if (block == Blocks.CARROTS) return FarmConfig.CropEnum.CARROT;
        if (block == Blocks.POTATOES) return FarmConfig.CropEnum.POTATO;
        if (block == Blocks.NETHER_WART) return FarmConfig.CropEnum.NETHER_WART;
        if (block == Blocks.SUGAR_CANE) return FarmConfig.CropEnum.SUGAR_CANE;
        if (block == Blocks.COCOA) return FarmConfig.CropEnum.COCOA_BEANS;
        if (block == Blocks.MELON) return FarmConfig.CropEnum.MELON;
        if (block == Blocks.PUMPKIN) return FarmConfig.CropEnum.PUMPKIN;
        if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM) return FarmConfig.CropEnum.MUSHROOM;
        if (block == Blocks.CACTUS) return FarmConfig.CropEnum.CACTUS;
        // Same flower block: Sunflower by day, Moonflower by night, from the world's time (night = ticks 13000-23000).
        if (block == Blocks.SUNFLOWER) {
            var level = Minecraft.getInstance().level;
            long t = level == null ? 0 : Math.floorMod(level.getOverworldClockTime(), 24000L);
            return t >= 13000 && t < 23000 ? FarmConfig.CropEnum.MOONFLOWER : FarmConfig.CropEnum.SUNFLOWER;
        }
        if (block == Blocks.ROSE_BUSH) return FarmConfig.CropEnum.ROSE;
        return FarmConfig.CropEnum.NONE;
    }

    public static FarmConfig.CropEnum getFarmingCrop() {
        Block closest = null;
        BlockPos closestPos = null;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Block block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
            if (isCropBlock(block)) {
                closest = block;
                closestPos = hit.getBlockPos();
            }
        }
        if (closest == null) {
            float yaw;
            if (MacroHandler.getInstance().getCurrentMacro().isPresent()) {
                yaw = MacroHandler.getInstance().getCurrentMacro().get().getClosest90Deg().orElse(AngleUtils.getClosest());
            } else {
                yaw = AngleUtils.getClosest();
            }
            Vec3 me = mc.player.position();
            for (int x = -3; x < 3; x++) {
                for (int y = -1; y < 5; y++) {
                    for (int z = 0; z < 3; z++) {
                        BlockPos pos = BlockUtils.getRelativeBlockPos(x, y, z, yaw);
                        Block block = mc.level.getBlockState(pos).getBlock();
                        if (!isCropBlock(block)) continue;
                        Vec3 c = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        if (closestPos == null || me.distanceTo(c) < me.distanceTo(new Vec3(closestPos.getX() + 0.5, closestPos.getY(), closestPos.getZ() + 0.5))) {
                            closest = block;
                            closestPos = pos;
                        }
                    }
                }
            }
        }
        if (closest != null) {
            FarmConfig.CropEnum crop = cropOf(closest);
            if (crop != FarmConfig.CropEnum.NONE) return crop;
        }
        LogUtils.sendError("Can't detect crop type! Lower average BPS failsafe will be disabled!");
        return FarmConfig.CropEnum.NONE;
    }

    public static void getTool() {
        // Staff swapped the slot: leave it, swapping back right away would look like a macro.
        if (itemChangedByStaff) return;
        if (changeItemEveryClock.isScheduled() && !changeItemEveryClock.passed()) return;
        changeItemEveryClock.schedule(1_500L);
        int id = getFarmingTool(MacroHandler.getInstance().getCrop(), true, false);
        if (id == -1) id = getFarmingTool(MacroHandler.getInstance().getCrop(), true, true);
        if (id == -1) {
            LogUtils.sendError("No tool found!");
            return;
        }
        if (id == mc.player.getInventory().getSelectedSlot()) return;
        mc.player.getInventory().setSelectedSlot(id);
    }

    public static FarmConfig.CropEnum getCropBasedOnMouseOver() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return FarmConfig.CropEnum.NONE;
        FarmConfig.CropEnum crop = cropOf(mc.level.getBlockState(hit.getBlockPos()).getBlock());
        return crop == FarmConfig.CropEnum.SUNFLOWER || crop == FarmConfig.CropEnum.MOONFLOWER || crop == FarmConfig.CropEnum.ROSE ? FarmConfig.CropEnum.NONE : crop;
    }

    public static int getFarmingTool(FarmConfig.CropEnum crop, boolean withError, boolean anyHoe) {
        if (crop == null) return withError ? -1 : 0;
        for (int i = 0; i < 8; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String name = SkyblockItemKt.getSkyblockId(stack);
            if (name == null) continue;
            if (anyHoe) {
                if (name.contains("HOE") || name.contains("DICER") || name.contains("CHOPPER") || name.contains("FUNGI")
                        || name.contains("DAEDALUS_AXE") || name.contains("KNIFE")) return i;
                continue;
            }
            switch (crop) {
                case NETHER_WART: if (name.contains("HOE_WARTS")) return i; break;
                case CARROT: if (name.contains("HOE_CARROT")) return i; break;
                case WHEAT: if (name.contains("HOE_WHEAT")) return i; break;
                case POTATO: if (name.contains("HOE_POTATO")) return i; break;
                case SUGAR_CANE: if (name.contains("HOE_CANE")) return i; break;
                case CACTUS: if (name.contains("CACTUS_KNIFE")) return i; break;
                case MUSHROOM: if (name.contains("FUNGI_CUTTER") || name.contains("DAEDALUS_AXE")) return i; break;
                case PUMPKIN_MELON_UNKNOWN: if (name.contains("_DICER")) return i; break;
                case MELON: if (name.contains("MELON_DICER")) return i; break;
                case PUMPKIN: if (name.contains("PUMPKIN_DICER")) return i; break;
                case COCOA_BEANS: if (name.contains("COCO_CHOPPER")) return i; break;
                // Eclipse Sickle (both flowers) / Wild Rose Cutter, IDs from the SkyBlock wiki.
                case SUNFLOWER: case MOONFLOWER: if (name.contains("HOE_SUNFLOWER")) return i; break;
                case ROSE: if (name.contains("HOE_WILD_ROSE")) return i; break;
                default: break;
            }
        }
        for (int i = 0; i < 9; i++) {
            String display = mc.player.getInventory().getItem(i).getHoverName().getString();
            if (display.contains("Gardening Hoe") || display.contains("Gardening Axe")) return i;
        }
        return withError ? -1 : 0;
    }

    public static boolean isRewarpLocationSet() {
        return !FarmHelper.rewarps().isEmpty();
    }

    public static boolean isStandingOnRewarpLocation() {
        if (!isRewarpLocationSet()) return false;
        return FarmHelper.rewarps().contains(BlockUtils.getRelativeBlockPos(0, 0, 0));
    }

    public static boolean isSpawnLocationSet() {
        return FarmHelper.spawn() != null;
    }

    public static boolean isStandingOnSpawnPoint() {
        BlockPos spawn = FarmHelper.spawn();
        return spawn != null && BlockUtils.getRelativeBlockPos(0, 0, 0).equals(spawn);
    }

    public static void setSpawnLocation() {
        if (mc.player == null) return;
        FarmHelper.setSpawn(BlockUtils.getRelativeBlockPos(0, 0, 0));
    }

    public static boolean isPlayerSuffocating() {
        return !mc.level.noCollision(mc.player, mc.player.getBoundingBox().deflate(0.15));
    }

    public static void closeScreen() {
        if (mc.screen != null && mc.player != null) mc.execute(() -> mc.player.closeContainer());
    }
}
