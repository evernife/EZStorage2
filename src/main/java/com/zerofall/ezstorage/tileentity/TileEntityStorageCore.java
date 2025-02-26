package com.zerofall.ezstorage.tileentity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.zerofall.ezstorage.EZStorage;
import com.zerofall.ezstorage.block.BlockCraftingBox;
import com.zerofall.ezstorage.block.BlockEjectPort;
import com.zerofall.ezstorage.block.BlockExtractPort;
import com.zerofall.ezstorage.block.BlockInputPort;
import com.zerofall.ezstorage.block.BlockSearchBox;
import com.zerofall.ezstorage.block.BlockSortBox;
import com.zerofall.ezstorage.block.BlockStorage;
import com.zerofall.ezstorage.block.BlockStorageCore;
import com.zerofall.ezstorage.block.StorageMultiblock;
import com.zerofall.ezstorage.gui.server.InventoryExtractList;
import com.zerofall.ezstorage.init.EZBlocks;
import com.zerofall.ezstorage.network.MessageFilterUpdate;
import com.zerofall.ezstorage.ref.Log;
import com.zerofall.ezstorage.tileentity.TileEntityExtractPort.EnumListMode;
import com.zerofall.ezstorage.util.BlockRef;
import com.zerofall.ezstorage.util.EZInventory;
import com.zerofall.ezstorage.util.EZStorageUtils;
import com.zerofall.ezstorage.util.ItemGroup;
import com.zerofall.ezstorage.util.ItemGroup.EnumSortMode;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextComponentString;

/** The storage core tile entity */
public class TileEntityStorageCore extends TileEntityBase {

	public EZInventory inventory;

	Set<BlockRef> multiblock = new HashSet<BlockRef>();
	public boolean disabled = false;
	private boolean firstTick = false;
	public boolean hasCraftBox = false;
	public boolean hasSearchBox = false;
	public EnumSortMode sortMode = EnumSortMode.COUNT;
	public boolean hasSortBox = false;
	public boolean jeiLink = false;
	
	private int robinIndex = 0;

	public TileEntityStorageCore() {
		inventory = new EZInventory(this);
	}

	/** Inputs a stack to the inventory (not from the player) */
	public ItemStack input(ItemStack stack) {
		ItemStack result = this.inventory.input(stack, false);
		return result;
	}

	/** Retrieves the first applicable stack in the inventory with a set amount */
	public ItemStack getFirstStack(int size, EnumListMode mode, boolean roundRobin, InventoryExtractList list) {
		// make sure the round robin mode resets properly
		if(robinIndex >= this.inventory.slotCount()) robinIndex = 0;
		
		if (this.inventory.inventory.isEmpty())
			return ItemStack.EMPTY; // make sure the inventory isn't empty
		switch (mode) {
		case IGNORE: // get the first item no matter what
			return this.inventory.getItemsAt(roundRobin ? robinIndex++ : 0, 0, size);
		default: // find a matching item
			return this.inventory.getItemsExtractList(mode, roundRobin, list, size);
		}
	}

	/** Peeks the first applicable stack in the inventory */
	public ItemStack peekFirstStack(EnumListMode mode, InventoryExtractList list) {
		if (this.inventory.inventory.isEmpty())
			return ItemStack.EMPTY; // make sure the inventory isn't empty
		switch (mode) {
		case IGNORE: // get the first item no matter what
			ItemGroup g = this.inventory.inventory.get(0);
			int count = (int) Math.min(g.itemStack.getMaxStackSize(), g.count);
			ItemStack ret = g.itemStack.copy();
			ret.setCount(count);
			return ret;
		default: // peek a matching item
			return this.inventory.peekItemsExtractList(mode, false, list);
		}
	}

	/** Sorts the inventory on change and tells clients to update their filtered lists */
	public void sortInventory() {
		if (!this.world.isRemote) {
			this.inventory.sort();
			updateInventory();
		}
	}

	/** Creates a block update and sends client data to update their filtered lists */
	public void updateInventory() {
		updateTileEntity();
		EZStorage.nw.sendToDimension(new MessageFilterUpdate(this), world.provider.getDimension());
	}

	/** Updates the tile entity position in the world and marks it to be saved */
	public void updateTileEntity() {
		EZStorageUtils.notifyBlockUpdate(this);
		this.markDirty();
	}

	@Override
	public NBTTagCompound writeDataToNBT(NBTTagCompound nbt) {
		NBTTagList nbttaglist = new NBTTagList();
		for (int i = 0; i < this.inventory.slotCount(); ++i) {
			ItemGroup group = this.inventory.inventory.get(i);
			if (group != null && !group.itemStack.isEmpty() && group.count > 0) {
				NBTTagCompound tag = new NBTTagCompound();
				tag.setByte("Index", (byte) i);
				group.itemStack.writeToNBT(tag);
				tag.setLong("InternalCount", group.count);
				nbttaglist.appendTag(tag);
			}
		}
		nbt.setTag("Internal", nbttaglist);
		nbt.setLong("InternalMax", this.inventory.maxItems);
		nbt.setBoolean("hasSearchBox", this.hasSearchBox);
		nbt.setBoolean("isDisabled", this.disabled);
		nbt.setInteger("sortMode", this.sortMode.ordinal());
		nbt.setBoolean("hasSortBox", this.hasSortBox);
		return nbt;
	}

	@Override
	public void readDataFromNBT(NBTTagCompound nbt) {
		NBTTagList nbttaglist = nbt.getTagList("Internal", 10);

		if (nbttaglist != null) {
			inventory.inventory = new ArrayList<ItemGroup>();
			for (int i = 0; i < nbttaglist.tagCount(); ++i) {
				NBTTagCompound tag = nbttaglist.getCompoundTagAt(i);
				int j = tag.getByte("Index") & 255;
				ItemStack stack = new ItemStack(tag);
				long count = tag.getLong("InternalCount");
				String name = tag.getString("id");
				ItemGroup group = new ItemGroup(stack, count, name);
				this.inventory.inventory.add(group);
			}
		}
		long maxItems = nbt.getLong("InternalMax");
		this.inventory.maxItems = maxItems;
		this.hasSearchBox = nbt.getBoolean("hasSearchBox");
		this.disabled = nbt.getBoolean("isDisabled");
		this.sortMode = EnumSortMode.fromInt(nbt.getInteger("sortMode"));
		this.hasSortBox = nbt.getBoolean("hasSortBox");
	}
	

	// process things on first-tick here only
	@Override
	public void update() {
		if (!firstTick) {
			if(world != null) {
				firstTick = true;
				scanMultiblock(); // scan the multiblock
				scanInventory(); // make sure the inventory has valid items
			}
		}
	}
	
	// remove invalid inventory items as needed
	public void scanInventory() {
		Iterator<ItemGroup> iterator = inventory.inventory.iterator();
		while(iterator.hasNext()) {
			ItemGroup g = iterator.next();
			if(g.itemStack.isEmpty() || g.itemStack.getItem() == null) {
				Log.logger.warn("Removing " + g + " due to block/item removal!");
				iterator.remove();
			}
		}
	}

	/** Scans the multiblock structure for valid blocks */
	public void scanMultiblock() {
		inventory.maxItems = 0;
		this.hasCraftBox = false;
		this.hasSearchBox = false;
		this.hasSortBox = false;
		multiblock = new HashSet<BlockRef>();
		BlockRef ref = new BlockRef(this);
		multiblock.add(ref);
		getValidNeighbors(ref);
		for (BlockRef blockRef : multiblock) {
			if (blockRef.block instanceof BlockStorage) {
				BlockStorage sb = (BlockStorage) blockRef.block;
				inventory.maxItems += sb.getCapacity();
			}
		}
		EZStorageUtils.notifyBlockUpdate(this);
	}

	/** Recursive function that scans a block's neighbors, and adds valid blocks to the multiblock list
	 * 
	 * @param br */
	private void getValidNeighbors(BlockRef br) {
		List<BlockRef> neighbors = EZStorageUtils.getNeighbors(br.pos.getX(), br.pos.getY(), br.pos.getZ(), world);
		for (BlockRef blockRef : neighbors) {
			if (blockRef.block instanceof StorageMultiblock) {
				if (multiblock.add(blockRef) == true && validateSystem() == true) {
					if (blockRef.block instanceof BlockInputPort) {
						TileEntityInputPort entity = (TileEntityInputPort) this.world.getTileEntity(blockRef.pos);
						entity.core = this;
					}
					if (blockRef.block instanceof BlockEjectPort) {
						TileEntityEjectPort entity = (TileEntityEjectPort) this.world.getTileEntity(blockRef.pos);
						entity.core = this;
					}
					if (blockRef.block instanceof BlockExtractPort) {
						TileEntityExtractPort entity = (TileEntityExtractPort) this.world.getTileEntity(blockRef.pos);
						entity.core = this;
					}
					if (blockRef.block instanceof BlockCraftingBox) {
						this.hasCraftBox = true;
					}
					if (blockRef.block instanceof BlockSearchBox) {
						this.hasSearchBox = true;
					}
					if (blockRef.block instanceof BlockSortBox) {
						this.hasSortBox = true;
					}
					getValidNeighbors(blockRef);
				}
			}
		}
	}

	/** Makes sure the storage system doesn't have multiple storage cores */
	public boolean validateSystem() {
		int count = 0;
		for (BlockRef ref : multiblock) {
			if (ref.block instanceof BlockStorageCore) {
				count++;
			}
			if (count > 1) {
				if (world.isRemote) {
					if (Minecraft.getMinecraft() != null && Minecraft.getMinecraft().player != null) {
						Minecraft.getMinecraft().player.sendMessage(new TextComponentString("§e§l ▶ §cApenas um StorageCore é permitido por sistema!"));
					}
				} else if (world.getBlockState(pos).getBlock() == EZBlocks.storage_core) {
					world.setBlockToAir(getPos());
					world.spawnEntity(new EntityItem(world, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(EZBlocks.storage_core)));
				}
				return false;
			}
		}
		return true;
	}

	public boolean isPartOfMultiblock(BlockRef blockRef) {
		if (multiblock != null) {
			if (multiblock.contains(blockRef)) {
				return true;
			}
		}
		return false;
	}

}
