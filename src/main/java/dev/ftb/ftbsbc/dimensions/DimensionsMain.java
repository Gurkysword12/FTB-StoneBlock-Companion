package dev.ftb.ftbsbc.dimensions;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.ftb.ftbsbc.config.FTBSBConfig;
import dev.ftb.ftbsbc.dimensions.kubejs.StoneBlockDataKjs;
import dev.ftb.ftbsbc.dimensions.level.DimensionStorage;
import dev.ftb.ftbsbc.dimensions.level.DynamicDimensionManager;
import dev.ftb.ftbsbc.dimensions.level.stoneblock.StartStructure;
import dev.ftb.ftbsbc.dimensions.level.stoneblock.StartStructurePiece;
import dev.ftb.mods.ftbteams.data.Team;
import dev.ftb.mods.ftbteams.data.TeamType;
import dev.ftb.mods.ftbteams.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.event.TeamEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityLeaveWorldEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber
public class DimensionsMain {
    public static final int SIZE = 128;
    public static final int HEIGHT = SIZE * 2;

    public static final Logger LOGGER = LogManager.getLogger();
    private static final ResourceLocation OVERWORLD = new ResourceLocation("overworld");

    public static void setup() {
        TeamEvent.PLAYER_LEFT_PARTY.register(DimensionsMain::teamPlayerLeftParty);
        TeamEvent.PLAYER_JOINED_PARTY.register(DimensionsMain::teamPlayerJoin);
        LifecycleEvent.SERVER_STARTED.register(DimensionsMain::serverStart);
    }

    private static void teamPlayerJoin(PlayerJoinedPartyTeamEvent event) {
        Team team = event.getTeam();
        if (team.getType() != TeamType.PARTY || team.getOwner() == event.getPlayer().getUUID()) {
            return;
        }

        ResourceKey<Level> dimension = DimensionsManager.INSTANCE.getDimension(team);
        if (dimension == null) {
            return;
        }

        BlockPos blockPos = DimensionStorage.get().getDimensionSpawnLocations(dimension.location());
        if (blockPos != null) {
            event.getPlayer().setRespawnPosition(dimension, blockPos, 0, true, false);
            DynamicDimensionManager.teleport(event.getPlayer(), dimension);
        }
    }

    private static void serverStart(MinecraftServer minecraftServer) {
        ServerLevel level = minecraftServer.getLevel(Level.OVERWORLD);
        if (level == null) {
            LOGGER.warn("Missed spawn reset event due to overworld being null");
            return;
        }

        if (DimensionStorage.get().isLobbySpawned() && !level.getSharedSpawnPos().equals(DimensionStorage.get().getLobbySpawnPos())) {
            level.setDefaultSpawnPos(DimensionStorage.get().getLobbySpawnPos(), 180F);
            LOGGER.info("Updating shared spawn to the lobby location");
        }
    }

    @SubscribeEvent
    public static void onPlayerDamage(LivingDamageEvent event) {
        if (event.getEntity().level.dimension().equals(Level.OVERWORLD) && event.getSource() != DamageSource.OUT_OF_WORLD) {
            event.setCanceled(true);
        }
    }


    @SubscribeEvent
    public static void onLevelLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof ServerLevel level)) {
            return;
        }

        if (!level.dimension().location().equals(OVERWORLD) || StoneBlockDataKjs.lobbyStructure == null || DimensionStorage.get().isLobbySpawned()) {
            if (StoneBlockDataKjs.lobbyStructure == null) {
                LOGGER.warn("Lobby structure not defined in kubejs");
            }
            return;
        }

        if (!level.dimension().location().equals(OVERWORLD)) {
            BlockPos dimSpawn = DimensionStorage.get().getDimensionSpawnLocations(((ServerLevel) event.getWorld()).dimension().location());
            if (!level.getSharedSpawnPos().equals(dimSpawn)) {
                level.setDefaultSpawnPos(dimSpawn == null ? BlockPos.ZERO.above(1) : dimSpawn, 0f);
            }
        }

        // Spawn the dim
        StructureTemplate lobby = level.getStructureManager().getOrCreate(StoneBlockDataKjs.lobbyStructure);
        StructurePlaceSettings placeSettings = StartStructurePiece.makeSettings(lobby);
        BlockPos spawnPos = StartStructure.locateSpawn(lobby, placeSettings);
        BlockPos lobbyLoc = BlockPos.ZERO.offset(-(lobby.getSize().getX() / 2), 0, -(lobby.getSize().getZ() / 2));
        BlockPos playerSpawn = spawnPos.offset(lobbyLoc.getX(), lobbyLoc.getY(), lobbyLoc.getZ());

        lobby.placeInWorld(level, lobbyLoc, lobbyLoc, placeSettings, level.random, Block.UPDATE_ALL);

        DimensionStorage.get().setLobbySpawnPos(playerSpawn);
        DimensionStorage.get().setLobbySpawned(true);

        level.removeBlock(playerSpawn, false);
        LOGGER.info("Spawned lobby structure");
    }

    private static void teamPlayerLeftParty(PlayerLeftPartyTeamEvent event) {
        ServerPlayer serverPlayer = event.getPlayer();
        if (serverPlayer != null) {
            ResourceKey<Level> dimensionId = DimensionStorage.get().getDimensionId(event.getTeam());
            if (dimensionId == null) {
                return;
            }

            if (FTBSBConfig.DIMENSIONS.clearPlayerInventory.get()) {
                serverPlayer.getInventory().clearOrCountMatchingItems(arg -> true, -1, serverPlayer.inventoryMenu.getCraftSlots());
                serverPlayer.containerMenu.broadcastChanges();
                serverPlayer.inventoryMenu.slotsChanged(serverPlayer.getInventory());
            }

            if (event.getTeamDeleted()) {
                DimensionStorage.get().archiveDimension(event.getTeam());
            }

            DynamicDimensionManager.teleport(serverPlayer, Level.OVERWORLD);
            serverPlayer.setRespawnPosition(Level.OVERWORLD, DimensionStorage.get().getLobbySpawnPos(), 180F, true, false);
        }
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinWorldEvent event) {
        if (event.getWorld().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
             return;
        }

        if (event.getWorld().dimension().location().equals(OVERWORLD) && player.getRespawnDimension().location().equals(OVERWORLD)) {
            // Assume this is their first time joining the world as otherwise their respawn dimension would be their own dimension
            BlockPos lobbySpawnPos = DimensionStorage.get().getLobbySpawnPos();
            if (player.getRespawnPosition() == null || !player.getRespawnPosition().equals(lobbySpawnPos)) {
                player.setRespawnPosition(event.getWorld().dimension(), lobbySpawnPos, -180, true, false);
                player.teleportTo((ServerLevel) event.getWorld(), lobbySpawnPos.getX(), lobbySpawnPos.getY(), lobbySpawnPos.getZ(), -180F, -10F);
            }
        }

        swapGameMode(event.getWorld().dimension(), player);
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveWorldEvent event) {
        if (event.getWorld().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        swapGameMode(event.getWorld().dimension(), player);
    }

    /**
     * When entering the overworld (lobby) the player will be switch to Adventure mode as long as they're not in creative mode,
     * upon the overworld whilst the game mode is set to adventure, we'll switch back to survival
     *
     * @param dimension dimension entering from or leaving
     * @param player    the player leaving or entering a dimension.
     */
    private static void swapGameMode(ResourceKey<Level> dimension, ServerPlayer player) {
        if (dimension.location().equals(OVERWORLD) && player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE && player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            player.setGameMode(GameType.ADVENTURE);
        }

        if (!dimension.location().equals(OVERWORLD) && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            player.setGameMode(GameType.SURVIVAL);
        }
    }
}
