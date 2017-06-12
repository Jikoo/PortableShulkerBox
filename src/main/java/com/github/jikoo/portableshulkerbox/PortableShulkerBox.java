package com.github.jikoo.portableshulkerbox;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A simple plugin allowing players to open shulker boxes by right clicking air.
 *
 * @author Jikoo
 */
public class PortableShulkerBox extends JavaPlugin implements Listener {

	private final Map<UUID, Pair<InventoryView, EquipmentSlot>> playersOpeningBoxes = new HashMap<>();

	@Override
	public void onEnable() {
		this.getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		playersOpeningBoxes.forEach((mapping, value) -> Bukkit.getPlayer(mapping).closeInventory());
		playersOpeningBoxes.clear();
	}

	@EventHandler
	public void onPlayerInteract(final PlayerInteractEvent event) {

		Player player = event.getPlayer();

		if (this.playersOpeningBoxes.containsKey(player.getUniqueId())) {
			// Block all interaction for players with boxes open.
			event.setCancelled(true);
			return;
		}

		if (event.getAction() != Action.RIGHT_CLICK_AIR || !player.hasPermission("portableshulkerbox.open")) {
			return;
		}

		ItemStack itemStack;
		PlayerInventory playerInventory = player.getInventory();
		if (event.getHand() == EquipmentSlot.HAND) {
			itemStack = playerInventory.getItemInMainHand();
		} else if (event.getHand() == EquipmentSlot.OFF_HAND) {
			itemStack = playerInventory.getItemInOffHand();
		} else {
			// Just in case another plugin does something weird.
			return;
		}

		if (!itemStack.getType().name().endsWith("_SHULKER_BOX")) {
			return;
		}

		// No check for item meta in advance - new shulker boxes will not have meta yet.
		ItemMeta itemMeta = itemStack.getItemMeta();
		if (!(itemMeta instanceof BlockStateMeta)) {
			return;
		}

		BlockState blockState = ((BlockStateMeta) itemMeta).getBlockState();
		if (!(blockState instanceof InventoryHolder)) {
			return;
		}

		Inventory inventory = ((InventoryHolder) blockState).getInventory();
		Inventory opened = this.getServer().createInventory(player, InventoryType.SHULKER_BOX, inventory.getTitle());

		opened.setContents(inventory.getContents());

		InventoryView view = player.openInventory(opened);

		this.playersOpeningBoxes.put(player.getUniqueId(), new ImmutablePair<>(view, event.getHand()));

	}

	@EventHandler
	public void onInventoryClose(final InventoryCloseEvent event) {
		if (playersOpeningBoxes.containsKey(event.getPlayer().getUniqueId())) {
			saveShulker(event.getPlayer());
			playersOpeningBoxes.remove(event.getPlayer().getUniqueId());
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onItemHeldChange(final PlayerItemHeldEvent event) {
		if (this.playersOpeningBoxes.containsKey(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onHandSwap(final PlayerSwapHandItemsEvent event) {
		if (this.playersOpeningBoxes.containsKey(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onInventoryDrag(final InventoryDragEvent event) {
		if (this.playersOpeningBoxes.containsKey(event.getWhoClicked().getUniqueId())) {
			// TODO: only save if top inventory is affected
			this.saveShulkerLater(event.getWhoClicked());
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onInventoryClick(final InventoryClickEvent event) {
		if (!this.playersOpeningBoxes.containsKey(event.getWhoClicked().getUniqueId())) {
			return;
		}

		if (event.getCurrentItem().getType().name().endsWith("_SHULKER_BOX")
				|| event.getClick() == ClickType.NUMBER_KEY && event.getWhoClicked().getInventory().getItem(event.getHotbarButton()).getType().name().endsWith("_SHULKER_BOX")) {
			event.setCancelled(true);
		}

		if (this.playersOpeningBoxes.containsKey(event.getWhoClicked().getUniqueId())) {
			// TODO: only save if top inventory is affected
			this.saveShulkerLater(event.getWhoClicked());
		}
	}

	private void saveShulkerLater(final HumanEntity player) {
		this.getServer().getScheduler().runTask(this, () -> this.saveShulker(player));
	}

	private void saveShulker(HumanEntity player) {
		if (!this.playersOpeningBoxes.containsKey(player.getUniqueId())) {
			return;
		}

		Pair<InventoryView, EquipmentSlot> pair = this.playersOpeningBoxes.get(player.getUniqueId());

		ItemStack itemStack;
		if (pair.getRight() == EquipmentSlot.HAND) {
			itemStack = player.getInventory().getItemInMainHand();
		} else if (pair.getRight() == EquipmentSlot.OFF_HAND) {
			itemStack = player.getInventory().getItemInOffHand();
		} else {
			System.err.println(String.format("Found unexpected EquipmentSlot %s for %s! Possible dupe bug!",
					pair.getRight().name(), player.getName()));
			return;
		}

		if (!itemStack.getType().name().endsWith("_SHULKER_BOX")) {
			System.err.println(String.format("Can't find shulker box for %s! Possible dupe bug!", player.getName()));
			return;
		}

		ItemMeta itemMeta = itemStack.getItemMeta();
		if (!(itemMeta instanceof BlockStateMeta)) {
			System.err.println(String.format("Can't find shulker box for %s! Possible dupe bug!", player.getName()));
			return;
		}

		BlockStateMeta blockStateMeta = (BlockStateMeta) itemMeta;
		BlockState blockState = blockStateMeta.getBlockState();

		if (!(blockState instanceof InventoryHolder)) {
			System.err.println(String.format("Can't find shulker box for %s! Possible dupe bug!", player.getName()));
			return;
		}

		((InventoryHolder) blockState).getInventory().setContents(pair.getLeft().getTopInventory().getContents());
		// TODO: does BlockState need to be re-set?
		blockStateMeta.setBlockState(blockState);
		itemStack.setItemMeta(blockStateMeta);
		// TODO: does item in hand need to be re-set?
		if (pair.getRight() == EquipmentSlot.HAND) {
			player.getInventory().setItemInMainHand(itemStack);
		} else if (pair.getRight() == EquipmentSlot.OFF_HAND) {
			player.getInventory().setItemInOffHand(itemStack);
		}

		if (player instanceof Player) {
			((Player) player).updateInventory();
		}

	}

}
