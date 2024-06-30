package com.github.jikoo.portableshulkerbox;

import org.bukkit.Bukkit;
import org.bukkit.Tag;
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
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A simple plugin allowing players to open shulker boxes by right-clicking air.
 *
 * @author Jikoo
 */
public class PortableShulkerBox extends JavaPlugin implements Listener {

	private final Map<UUID, ActiveShulker> playersOpeningBoxes = new HashMap<>();

	@Override
	public void onEnable() {
		this.getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		this.playersOpeningBoxes.forEach((mapping, value) -> {
			Player player = Bukkit.getPlayer(mapping);
			if (player != null) {
				player.closeInventory();
			} else {
				getLogger().severe(() -> String.format("Inventory not properly closed for player with UUID %s! Possible dupe bug!", mapping));
			}
		});
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

		Hand hand = Hand.of(event.getHand());

		if (hand == null) {
			// Just in case another plugin does something weird.
			return;
		}

		ItemStack itemStack = hand.get(player);

		if (!isShulkerBox(itemStack)) {
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
		InventoryView view = player.openInventory(inventory);

		if (view == null) {
			player.closeInventory();
			return;
		}

		// To alleviate confusion when the box has not been placed since naming, use item name.
		if (itemMeta.hasDisplayName()) {
			view.setTitle(itemMeta.getDisplayName());
		}

		this.playersOpeningBoxes.put(player.getUniqueId(), new ActiveShulker(inventory, hand));

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
	public void onInventoryDrag(@NotNull InventoryDragEvent event) {
		ActiveShulker activeShulker = this.playersOpeningBoxes.get(event.getWhoClicked().getUniqueId());
		if (activeShulker == null) {
			return;
		}
		for (int slot : event.getRawSlots()) {
			if (slot < event.getView().getTopInventory().getSize()) {
				// If the drag affects the shulker, update its content.
				this.saveShulkerLater(event, activeShulker);
				return;
			}
		}
		// If the content has not changed, just check that the shulker is still available.
		checkShulker(event, activeShulker);
	}

	@EventHandler(ignoreCancelled = true)
	public void onInventoryClick(@NotNull InventoryClickEvent event) {
		ActiveShulker activeShulker = this.playersOpeningBoxes.get(event.getWhoClicked().getUniqueId());
		if (activeShulker == null) {
			return;
		}

		// Disallow movement of shulker boxes in general, not just the open one.
		if (isShulkerBox(event.getCurrentItem())
				|| event.getClick() == ClickType.NUMBER_KEY && isShulkerBox(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))
				|| event.getClick() == ClickType.SWAP_OFFHAND && isShulkerBox(event.getWhoClicked().getInventory().getItemInOffHand())) {
			event.setCancelled(true);
			return;
		}

		boolean updateContent = switch (event.getClick()) {
			case WINDOW_BORDER_LEFT:
			case WINDOW_BORDER_RIGHT:
				// When clicking outside of inventory, shulker unaffected.
				yield false;
			case LEFT:
			case RIGHT:
			case MIDDLE:
			case NUMBER_KEY:
			case DROP:
			case CONTROL_DROP:
			case CREATIVE:
			case SWAP_OFFHAND:
				// If bottom inventory, shulker unaffected.
				yield event.getSlot() == event.getRawSlot();
			default:
				// For shift clicks, guaranteed at least attempting a change.
				// Double click gathering to cursor may or may not affect ths shulker, but we can't check with the API.
				// For anything else, just update to be safe.
				yield true;
		};

		if (updateContent) {
			// If the content has changed, update the shulker.
			this.saveShulkerLater(event, activeShulker);
		} else {
			// If the content has not changed, just check that the shulker is still available.
			checkShulker(event, activeShulker);
		}
	}

	private void checkShulker(@NotNull InventoryInteractEvent event, @NotNull ActiveShulker activeShulker) {
		ItemStack itemStack = activeShulker.hand().get(event.getWhoClicked());
		if (!isShulkerBox(itemStack)) {
			event.setCancelled(true);
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(event.getWhoClicked());
		}
	}

	private void saveShulkerLater(@NotNull InventoryInteractEvent event, @NotNull ActiveShulker activeShulker) {
		checkShulker(event, activeShulker);
		this.getServer().getScheduler().runTask(this, () -> this.saveShulker(event.getWhoClicked()));
	}

	private void saveShulker(HumanEntity player) {
		// Re-obtain active shulker to prevent duplicate saves.
		ActiveShulker activeShulker = this.playersOpeningBoxes.get(player.getUniqueId());
		if (activeShulker == null) {
			return;
		}

		ItemStack itemStack = activeShulker.hand().get(player);

		if (!isShulkerBox(itemStack)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		ItemMeta itemMeta = itemStack.getItemMeta();
		if (!(itemMeta instanceof BlockStateMeta blockStateMeta)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		BlockState blockState = blockStateMeta.getBlockState();

		if (!(blockState instanceof InventoryHolder holder)) {
			noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(player);
			return;
		}

		holder.getInventory().setContents(activeShulker.inventory().getContents());
		blockStateMeta.setBlockState(blockState);
		itemStack.setItemMeta(blockStateMeta);

		activeShulker.hand().set(player, itemStack);
	}

	private void noThatIsItIAmStoppingTheServerShutItAllDownNoMoreFunForAnyoneYouAreAllBanned(HumanEntity nastyPerson) {
		getLogger().severe(() -> String.format("Can't find shulker box for %s! Possible dupe bug!", nastyPerson.getName()));
		this.playersOpeningBoxes.remove(nastyPerson.getUniqueId());
		nastyPerson.closeInventory();
	}

	private static boolean isShulkerBox(@Nullable ItemStack itemStack) {
		return itemStack != null && Tag.SHULKER_BOXES.isTagged(itemStack.getType());
	}

}
