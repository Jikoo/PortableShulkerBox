package com.github.jikoo.portableshulkerbox;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
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
		this.playersOpeningBoxes.forEach((mapping, value) -> Bukkit.getPlayer(mapping).closeInventory());
		this.playersOpeningBoxes.clear();
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

		if (!this.isShulkerBox(itemStack)) {
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

		// To support translations (and alleviate confusion when the box has not been placed since naming), use item name.
		Inventory opened = this.getServer().createInventory(player, InventoryType.SHULKER_BOX, itemMeta.getDisplayName());

		opened.setContents(inventory.getContents());

		InventoryView view = player.openInventory(opened);

		this.playersOpeningBoxes.put(player.getUniqueId(), new ImmutablePair<>(view, event.getHand()));

	}

	@EventHandler(ignoreCancelled = true)
	public void onItemDrop(PlayerDropItemEvent event) {
		// Dupe bug prevention: Right click, then quickly press drop key before inventory opens.
		if (this.playersOpeningBoxes.containsKey(event.getPlayer().getUniqueId())
				&& isShulkerBox(event.getItemDrop().getItemStack())) {
			event.getPlayer().closeInventory();
		}
	}

	@EventHandler
	public void onInventoryClose(final InventoryCloseEvent event) {
		if (this.playersOpeningBoxes.containsKey(event.getPlayer().getUniqueId())) {
			saveShulker(event.getPlayer());
			this.playersOpeningBoxes.remove(event.getPlayer().getUniqueId());
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

		// Disallow movement of shulker boxes in general, not just the open one.
		if (this.isShulkerBox(event.getCurrentItem()) || event.getClick() == ClickType.NUMBER_KEY
				&& this.isShulkerBox(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
			event.setCancelled(true);
			return;
		}

			if (this.playersOpeningBoxes.containsKey(event.getWhoClicked().getUniqueId())) {
			// TODO: only save if top inventory is affected
			this.saveShulkerLater(event.getWhoClicked());
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void preventServerCrash(final InventoryClickEvent event) {
		/*
		 * In 1.11, shift clicking caused a nasty NPE or StackOverflowError.
		 * In 1.12, shift clicking crashes the server.
		 *
		 * All of these things are Bad Things. We do not like Bad Things.
		 *
		 * To prevent this, we manually mimic vanilla behavior on priority MONITORso other plugins do not interpret our
		 * event cancellation as transfer prevention.
		 */
		if (!this.playersOpeningBoxes.containsKey(event.getWhoClicked().getUniqueId())
				|| event.getSlotType() != InventoryType.SlotType.CONTAINER || !event.isShiftClick()
				|| event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
			return;
		}

		Inventory clicked = event.getClickedInventory();
		if (clicked == null) {
			return;
		}

		Inventory clickedTo = event.getView().getTopInventory().equals(clicked)
				? event.getView().getBottomInventory() : event.getView().getTopInventory();

		event.setCancelled(true);
		ItemStack moved = event.getCurrentItem().clone();
		event.setCurrentItem(null);

		// TODO: inventory#addItem starts on the wrong side of the hotbar when adding to player inventory
		HashMap<Integer, ItemStack> failedAdditions = clickedTo.addItem(moved);

		if (!failedAdditions.isEmpty()) {
			event.setCurrentItem(failedAdditions.values().iterator().next());
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
			this.playersOpeningBoxes.remove(player.getUniqueId());
			player.closeInventory();
			return;
		}

		if (!isShulkerBox(itemStack)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		ItemMeta itemMeta = itemStack.getItemMeta();
		if (!(itemMeta instanceof BlockStateMeta)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		BlockStateMeta blockStateMeta = (BlockStateMeta) itemMeta;
		BlockState blockState = blockStateMeta.getBlockState();

		if (!(blockState instanceof InventoryHolder)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		((InventoryHolder) blockState).getInventory().setContents(pair.getLeft().getTopInventory().getContents());
		blockStateMeta.setBlockState(blockState);
		itemStack.setItemMeta(blockStateMeta);

		if (pair.getRight() == EquipmentSlot.HAND) {
			player.getInventory().setItemInMainHand(itemStack);
		} else if (pair.getRight() == EquipmentSlot.OFF_HAND) {
			player.getInventory().setItemInOffHand(itemStack);
		}

		if (player instanceof Player) {
			((Player) player).updateInventory();
		}

	}

	private void noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(HumanEntity player) {
		System.err.println(String.format("Can't find shulker box for %s! Possible dupe bug!", player.getName()));
		this.playersOpeningBoxes.remove(player.getUniqueId());
		player.closeInventory();
	}

	private boolean isShulkerBox(ItemStack itemStack) {
		if (itemStack == null) {
			return false;
		}
		switch (itemStack.getType()) {
			case BLACK_SHULKER_BOX:
			case BLUE_SHULKER_BOX:
			case BROWN_SHULKER_BOX:
			case CYAN_SHULKER_BOX:
			case GRAY_SHULKER_BOX:
			case GREEN_SHULKER_BOX:
			case LIGHT_BLUE_SHULKER_BOX:
			case LIME_SHULKER_BOX:
			case MAGENTA_SHULKER_BOX:
			case ORANGE_SHULKER_BOX:
			case PINK_SHULKER_BOX:
			case PURPLE_SHULKER_BOX:
			case RED_SHULKER_BOX:
			case SILVER_SHULKER_BOX:
			case WHITE_SHULKER_BOX:
			case YELLOW_SHULKER_BOX:
				return true;
			default:
				return false;
		}
	}

}
