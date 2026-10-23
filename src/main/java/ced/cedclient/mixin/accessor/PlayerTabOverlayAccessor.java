package ced.cedclient.mixin.accessor;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.client.multiplayer.PlayerInfo;
import java.util.List;

@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("header")
    @Nullable Component ced$getHeader();

    @Accessor("footer")
    @Nullable Component ced$getFooter();

    /** The tab list in the order it is drawn (online players are in no particular order). */
    @Invoker("getPlayerInfos")
    List<PlayerInfo> ced$playerInfos();
}
