package ced.cedclient.mixin.entity;

import ced.cedclient.features.impl.render.nametag.CustomNametag;
import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public abstract class EntityMixin {

    /*
     * Redirects the this.getName() call inside Entity#getDisplayName(),
     * BEFORE vanilla wraps it with PlayerTeam.formatNameForTeam() (see
     * PlayerTeam.getFormattedName() -- prefix + name + suffix). Swapping
     * the bare name out here means vanilla's own team-prefix/suffix
     * wrapping (SkyBlock's level number, the lobby rank tag, status
     * icons, whatever the server attaches via scoreboard team) still
     * happens completely untouched, around our recolored name -- no
     * splicing or text-matching required, because we never touch the
     * assembled Component at all.
     */
    @Redirect(
            method = "getDisplayName()Lnet/minecraft/network/chat/Component;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getName()Lnet/minecraft/network/chat/Component;"
            )
    )
    private Component cedclient$overrideNameForDisplayName(Entity self) {
        Component vanillaName = self.getName();
        if (self instanceof Player player) {
            Component override = CustomNametagText.INSTANCE.overrideBareName(player);
            // TEMP DEBUG -- gated behind CustomNametag's "Log Nametag Debug"
            // setting; toggle it in the mod's ClickGUI (Render category).
            // Shows exactly what key overrideBareName is looking up other
            // players by (player.getGameProfile().name()) versus the bare
            // name vanilla actually resolved (vanillaName), and whether a
            // CosmeticsSync/own-tag override matched. If gameProfile.name()
            // here doesn't match the player's real IGN, that confirms
            // SkyBlock is faking it for entities too, same as tab-list rows.
            if (CustomNametag.INSTANCE.getLogNametagDebug().getValue()) {
                System.out.println(
                        "[CedClient NameTag DEBUG] getDisplayName: gameProfileName=\"" + player.getGameProfile().name()
                                + "\" vanillaName=\"" + vanillaName.getString()
                                + "\" overrideMatched=" + (override != null)
                );
            }
            if (override != null) {
                return override;
            }
        }
        return vanillaName;
    }
}