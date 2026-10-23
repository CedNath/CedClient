package ced.cedclient.mixin.accessor;

import java.util.Map;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The map's icons by key: on Hypixel the key's last digit is the teammate's place in the party order (see HotbarDungeonMap). */
@Mixin(MapItemSavedData.class)
public interface MapItemSavedDataAccessor {
    @Accessor("decorations")
    Map<String, MapDecoration> ced$decorations();
}
