package dev.ftb.ftbsbc.dimensions.level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.math.Vector3d;
import com.mojang.serialization.Lifecycle;
import dev.ftb.ftbsbc.FTBStoneBlock;
import dev.ftb.ftbsbc.dimensions.level.stoneblock.StoneblockChunkGenerator;
import dev.ftb.ftbsbc.dimensions.net.UpdateDimensionsList;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Thanks to McJty and Commoble for providing this code.
 * See original DynamicDimensionManager in RF Tools Dimensions for comments and more generic example.
 */
public class DynamicDimensionManager {
	public static ServerLevel create(MinecraftServer server, ResourceKey<Level> key, ResourceLocation prebuiltStructure) {
		@SuppressWarnings("deprecation")
		Map<ResourceKey<Level>, ServerLevel> map = server.forgeGetWorldMap();

		ServerLevel existingLevel = map.get(key);

		if (existingLevel != null) {
			return existingLevel;
		}

		RegistryAccess registryAccess = server.registryAccess();

		ServerLevel overworld = server.getLevel(Level.OVERWORLD);

		ResourceKey<LevelStem> dimensionKey = ResourceKey.create(Registry.LEVEL_STEM_REGISTRY, key.location());
		Holder<DimensionType> typeHolder = registryAccess.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY).getHolderOrThrow(
				ResourceKey.create(Registry.DIMENSION_TYPE_REGISTRY, new ResourceLocation(FTBStoneBlock.MOD_ID, "stoneblock"))
		);
		StoneblockChunkGenerator chunkGenerator = new StoneblockChunkGenerator(
				registryAccess.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY),
				registryAccess.registryOrThrow(Registry.NOISE_REGISTRY),
				registryAccess.registryOrThrow(Registry.BIOME_REGISTRY),
				prebuiltStructure
		);
		LevelStem dimension = new LevelStem(typeHolder, chunkGenerator);

		ChunkProgressListener chunkProgressListener = server.progressListenerFactory.create(11);
		WorldData worldData = server.getWorldData();
		WorldGenSettings worldGenSettings = worldData.worldGenSettings();
		DerivedLevelData derivedLevelData = new DerivedLevelData(worldData, worldData.overworldData());

		((MappedRegistry<LevelStem>) worldGenSettings.dimensions()).register(dimensionKey, dimension, Lifecycle.stable());

		ServerLevel newWorld = new ServerLevel(
				server,
				server.executor,
				server.storageSource,
				derivedLevelData,
				key,
				dimension.typeHolder(),
				chunkProgressListener,
				dimension.generator(),
				worldGenSettings.isDebug(),
				net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(worldGenSettings.seed()),
				ImmutableList.of(),
				false
		);

		overworld.getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(newWorld.getWorldBorder()));
		map.put(key, newWorld);
		server.markWorldsDirty();
		MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(newWorld));
		new UpdateDimensionsList(key, true).sendToAll(server);
		return newWorld;
	}

	public static ServerLevel destroy(MinecraftServer server, ResourceKey<Level> key) {
		WorldGenSettings worldGenSettings = server.getWorldData().worldGenSettings();
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		Path dimensionPath = server.storageSource.getDimensionPath(key);

		ServerLevel removedLevel = server.forgeGetWorldMap().remove(key);

		if (removedLevel == null) {
			return null;
		}

		for (ServerPlayer player : Lists.newArrayList(removedLevel.players())) {
			ResourceKey<Level> respawnKey = Level.OVERWORLD;
			ServerLevel destinationLevel = server.getLevel(respawnKey);
			BlockPos destinationPos = player.getRespawnPosition();

			if (destinationPos == null) {
				destinationPos = destinationLevel.getSharedSpawnPos();
			}

			float respawnAngle = player.getRespawnAngle();
			player.teleportTo(destinationLevel, destinationPos.getX(), destinationPos.getY(), destinationPos.getZ(), respawnAngle, 0F);
		}

		removedLevel.save(null, false, removedLevel.noSave());
		MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(removedLevel));

		WorldBorder overworldBorder = overworld.getWorldBorder();
		WorldBorder removedWorldBorder = removedLevel.getWorldBorder();
		BorderChangeListener targetListener = null;

		for (BorderChangeListener listener : overworldBorder.listeners) {
			if (listener instanceof BorderChangeListener.DelegateBorderChangeListener && removedWorldBorder == ((BorderChangeListener.DelegateBorderChangeListener) listener).worldBorder) {
				targetListener = listener;
				break;
			}
		}

		if (targetListener != null) {
			overworldBorder.removeListener(targetListener);
		}

		Registry<LevelStem> oldRegistry = worldGenSettings.dimensions();
		MappedRegistry<LevelStem> newRegistry = new MappedRegistry<>(Registry.LEVEL_STEM_REGISTRY, oldRegistry.elementsLifecycle(), null);

		for (var entry : oldRegistry.entrySet()) {
			ResourceKey<LevelStem> oldKey = entry.getKey();
			ResourceKey<Level> oldLevelKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, oldKey.location());
			LevelStem dimension = entry.getValue();

			if (oldKey != null && dimension != null && oldLevelKey != key) {
				newRegistry.register(oldKey, dimension, oldRegistry.lifecycle(dimension));
			}
		}

		if (Files.exists(dimensionPath)) {
			try {
				FileUtils.deleteDirectory(dimensionPath.toFile());
			} catch (IOException e) {
				FTBStoneBlock.LOGGER.error("Failed to delete dimension file for {} at {}", key, dimensionPath, e);
			}
		}

		worldGenSettings.dimensions = newRegistry;
		server.markWorldsDirty();
		new UpdateDimensionsList(key, false).sendToAll(server);
		return removedLevel;
	}

	public static boolean teleport(ServerPlayer player, ResourceKey<Level> key) {
		ServerLevel level = player.server.getLevel(key);

		if (level != null) {
			if (key.equals(Level.OVERWORLD)) {
				BlockPos lobbySpawnPos = DimensionStorage.get().getLobbySpawnPos();
				player.teleportTo(level, lobbySpawnPos.getX() + .5D, lobbySpawnPos.getY() + .01D, lobbySpawnPos.getZ() + .5D, player.getYRot(), player.getXRot());
			} else {
				Vector3d vec = new Vector3d(0.5D, 1.1D, 0.5D);
				BlockPos respawnPosition = player.getRespawnPosition();
				if (player.getRespawnDimension().equals(key) && respawnPosition != null) {
					vec.add(new Vector3d(respawnPosition.getX(), respawnPosition.getY(), respawnPosition.getZ()));
				} else {
					BlockPos levelSharedSpawn = DimensionStorage.get().getDimensionSpawnLocations(level.dimension().location());
					if (levelSharedSpawn == null) {
						levelSharedSpawn = BlockPos.ZERO;
					}

					vec.add(new Vector3d(levelSharedSpawn.getX(), levelSharedSpawn.getY(), levelSharedSpawn.getZ()));
				}

				player.teleportTo(level, vec.x, vec.y, vec.z, player.getYRot(), player.getXRot());
			}
			return true;
		} else {
			FTBStoneBlock.LOGGER.error("Failed to teleport " + player.getScoreboardName() + " to " + key.location());
			return false;
		}
	}
}
