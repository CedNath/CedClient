package ced.cedclient.utils

import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot

fun signSlot(front: Boolean): SignTextSlot = if (front) SignTextSlot.FRONT else SignTextSlot.BACK

fun SignBlockEntity.textOf(front: Boolean): SignText = getText(signSlot(front))
