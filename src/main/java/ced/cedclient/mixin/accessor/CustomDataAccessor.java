package ced.cedclient.mixin.accessor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The item's custom data without copyTag()'s full copy (read-only use: see SkyblockItem.kt). */
@Mixin(CustomData.class)
public interface CustomDataAccessor {
    @Accessor("tag")
    CompoundTag ced$tag();
}
