package com.gearworks.rentaplate.block;

import com.gearworks.rentaplate.RentAPlate;
import com.gearworks.rentaplate.WarpPlatesConfig;
import com.gearworks.rentaplate.data.WarpPlate;
import com.gearworks.rentaplate.data.WarpPlatePair;
import com.gearworks.rentaplate.data.WarpPlatesSavedData;
import com.gearworks.rentaplate.menu.WarpPlateRentMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class WarpPlateBlockEntity extends PlateBlockEntity implements ExtendedScreenHandlerFactory {
	private String warpTitle = "";
	@Nullable
	private UUID renter;

	public WarpPlateBlockEntity(BlockPos pos, BlockState state) {
		super(RentAPlate.WARP_PLATE_BLOCK_ENTITY, pos, state);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, WarpPlateBlockEntity blockEntity) {
		if (blockEntity.isRented() && blockEntity.level instanceof ServerLevel serverLevel) {
			WarpPlatesSavedData data = WarpPlatesSavedData.get(serverLevel);
			WarpPlatePair pair = data.getPair(blockEntity.getId());
			
			if (pair != null && pair.expiryTime() < System.currentTimeMillis()) {
				RentAPlate.LOGGER.info("Warp Plate at {} expired", pos);
				if (blockEntity.renter != null && level.getPlayerByUUID(blockEntity.renter) instanceof ServerPlayer player) {
					player.displayClientMessage(Component.translatable("text.rentaplate.rent_expired", blockEntity.warpTitle), true);
				}

				data.removePair(blockEntity.getId());
				WarpPlate returnPlate = pair.returnPlate();

				if (returnPlate != null) {
					BlockPos returnPos = returnPlate.pos();

					if (serverLevel.getBlockState(returnPos).getBlock() == RentAPlate.RETURN_PLATE_BLOCK) {
						serverLevel.removeBlock(returnPos, false);
					}
				}

				blockEntity.setId(-1);
				blockEntity.renter = null;
				blockEntity.warpTitle = "";
				blockEntity.setChanged();
				level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
			}
		}
	}

	public int rent(Player renter) {
		this.renter = renter.getUUID();

		if (this.level instanceof ServerLevel serverLevel) {
			WarpPlatesSavedData data = WarpPlatesSavedData.get(serverLevel);
			WarpPlatePair pair = data.getPair(this.getId());

			if (pair == null) {
				long expiryTime = System.currentTimeMillis() + WarpPlatesConfig.INSTANCE.getRentDuration();
				pair = new WarpPlatePair(data.getNextId(), expiryTime, new WarpPlate(serverLevel.dimensionTypeId().location(), this.getBlockPos()), null);
				RentAPlate.LOGGER.info("Warp Plate at {} rented with ID {}", this.getBlockPos(), pair.id());
				data.addPair(pair);
				this.setId(pair.id());
			} else {
				pair.setExpiryTime(pair.expiryTime() + WarpPlatesConfig.INSTANCE.getRentDuration());
				RentAPlate.LOGGER.info("Warp Plate at {} rent extended", this.getBlockPos());
			}

			data.setDirty();
			this.setChanged();
		}

		return this.getId();
	}

	public boolean isRented() {
		return this.renter != null;
	}

	public @Nullable UUID getRenter() {
		return this.renter;
	}

	public String getWarpTitle() {
		return this.warpTitle;
	}

	public void setWarpTitle(String warpTitle) {
		this.warpTitle = warpTitle;
		this.setChanged();
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		this.warpTitle = tag.getString("WarpTitle");

		if (tag.contains("Renter")) {
			this.renter = tag.getUUID("Renter");
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		tag.putString("WarpTitle", this.warpTitle);

		if (this.renter != null) {
			tag.putUUID("Renter", this.renter);
		}
	}

	@Override
	public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag() {
		return this.saveWithoutMetadata();
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.warp_plates.warp_plate");
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {
		return new WarpPlateRentMenu(i, inventory, ContainerLevelAccess.NULL, this.getRenter(), (newWarpTitle) -> {
			if (!newWarpTitle.isEmpty()) {
				this.setWarpTitle(newWarpTitle);
			}

			int id = this.rent(player);
			WarpPlatePair pair = WarpPlatesSavedData.get((ServerLevel) this.level).getPair(id);
			
			if (pair == null) {
				RentAPlate.LOGGER.error("Warp Plate at {} rented but pair not found", this.getBlockPos());
				return;
			}

			if (pair.returnPlate() == null) {
				ItemStack returnPlate = RentAPlate.RETURN_PLATE_BLOCK_ITEM.getDefaultInstance();
				CompoundTag tag = new CompoundTag();
				tag.putInt("PlateId", id);
				BlockItem.setBlockEntityData(returnPlate, RentAPlate.RETURN_PLATE_BLOCK_ENTITY, tag);
				returnPlate.setHoverName(returnPlate.getDisplayName().copy().append(Component.literal(" - " + this.warpTitle)));
				player.getInventory().add(returnPlate);
			}

			player.level().sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL);
		});
	}

	@Override
	public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
		WarpPlatePair pair = WarpPlatesSavedData.get((ServerLevel) this.level).getPair(this.getId());
		buf.writeUtf(warpTitle);
		buf.writeLong(pair == null ? -1L : pair.expiryTime());
	}
}
