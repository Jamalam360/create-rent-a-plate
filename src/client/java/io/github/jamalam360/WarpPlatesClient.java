package io.github.jamalam360;

import io.github.jamalam360.block.PlateBlock;
import io.github.jamalam360.block.WarpPlateBlockEntityRenderer;
import io.github.jamalam360.screen.WarpPlateRentScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

public class WarpPlatesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(WarpPlates.WARP_PLATE_RENT_MENU, WarpPlateRentScreen::new);
		BlockEntityRenderers.register(WarpPlates.WARP_PLATE_BLOCK_ENTITY, WarpPlateBlockEntityRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(WarpPlates.PARTICLE_PACKET, (client, handler, buf, responseSender) -> {
			BlockPos pos1 = buf.readBlockPos();
			BlockPos pos2 = buf.readBlockPos();			
			client.execute(() -> {
				PlateBlock.spawnTeleportParticles(client.level, pos1);
				PlateBlock.spawnTeleportParticles(client.level, pos2);
			});
		});
		
		ClientPlayNetworking.registerGlobalReceiver(WarpPlates.CONFIG_SYNC_PACKET, (client, handler, buf, responseSender) -> {
			int size = buf.readInt();
			
			for (int i = 0; i < size; i++) {
				String key = buf.readUtf();
				String value = buf.readUtf();
				WarpPlatesConfig.INSTANCE.set(key, value);
			}
		});
	}
}
