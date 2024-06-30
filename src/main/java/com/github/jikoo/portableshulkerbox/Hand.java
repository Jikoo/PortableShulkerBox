package com.github.jikoo.portableshulkerbox;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;

enum Hand {
  MAIN(player -> player.getInventory().getItemInMainHand(), (player, item) -> player.getInventory().setItemInMainHand(item)),
  OFF(player -> player.getInventory().getItemInOffHand(), (player, item) -> player.getInventory().setItemInOffHand(item));

  private final Function<HumanEntity, ItemStack> getter;
  private final BiConsumer<HumanEntity, ItemStack> setter;

  Hand(Function<HumanEntity, ItemStack> getter, BiConsumer<HumanEntity, ItemStack> setter) {
    this.getter = getter;
    this.setter = setter;
  }

  public @NotNull ItemStack get(@NotNull HumanEntity humanEntity) {
    return getter.apply(humanEntity);
  }

  public void set(@NotNull HumanEntity humanEntity, @Nullable ItemStack itemStack) {
    setter.accept(humanEntity, itemStack);
  }

  static @Nullable Hand of(@Nullable EquipmentSlot equipmentSlot) {
    if (equipmentSlot == null) {
      return null;
    }
    return switch (equipmentSlot) {
      case HAND -> MAIN;
      case OFF_HAND -> OFF;
      default -> null;
    };
  }
}
