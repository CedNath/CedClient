package ced.cedclient.utils

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand

fun Entity.getLorenzVec(): LorenzVec =
    LorenzVec(this.x, this.y, this.z)

fun ArmorStand.getWornSkullTexture(): String? =
    this.getItemBySlot(EquipmentSlot.HEAD).getSkullTexture()

fun ArmorStand.wearingSkullTexture(texture: String): Boolean =
    getWornSkullTexture() == texture
