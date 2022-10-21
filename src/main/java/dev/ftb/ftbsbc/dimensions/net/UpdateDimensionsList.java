package dev.ftb.ftbsbc.dimensions.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.ftbsbc.FTBStoneBlock;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Set;

public class UpdateDimensionsList extends BaseS2CMessage {
	private final boolean add;
	private final ResourceKey<Level> dimension;

	public UpdateDimensionsList(ResourceKey<Level> key, boolean add) {
		this.dimension = key;
		this.add = add;
	}

	public UpdateDimensionsList(FriendlyByteBuf buf) {
		this.dimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, buf.readResourceLocation());
		this.add = buf.readBoolean();
	}

	@Override
	public MessageType getType() {
		return FTBStoneBlock.UPDATE_DIMENSION_LIST;
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeResourceLocation(this.dimension.location());
		buf.writeBoolean(this.add);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		context.queue(() -> {
			Set<ResourceKey<Level>> levels = ((LocalPlayer) context.getPlayer()).connection.levels();
			if (add) levels.add(dimension);
			else levels.remove(dimension);
		});
	}
}
