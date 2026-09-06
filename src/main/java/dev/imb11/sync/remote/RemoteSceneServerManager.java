package dev.imb11.sync.remote;

import com.mojang.logging.LogUtils;
import dev.imb11.blocks.GBlocks;
import dev.imb11.projection.ProjectionChunkRegion;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.mixins.ChunkMapAccessor;
import dev.imb11.mixins.TrackedEntityAccessor;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.platform.PlatformNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class RemoteSceneServerManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_RADIUS = 6;
    public static final int MIN_RADIUS = 1;
    public static final int SOURCE_CAMERA_BUDGET = 32;
    public static final int MAX_SUBSCRIPTIONS_PER_PLAYER = 4;
    public static final int MAX_CHUNKS_PER_PLAYER = 512;
    public static final int MAX_CHUNKS_GLOBAL = 4096;
    public static final int SUBSCRIPTION_TIMEOUT_TICKS = 100;
    private static final int TICKET_TIMEOUT_TICKS = 120;
    private static final int TICKET_REFRESH_INTERVAL = 20;
    private static final int STATE_INTERVAL = 20;
    private static final int MAX_CHUNKS_PER_PLAYER_TICK = 4;
    private static final int MAX_CHUNKS_GLOBAL_TICK = 8;
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 16;
    private static final int CHUNK_ACK_TIMEOUT_TICKS = 200;
    private static final int SEND_READY_TICKET_LEVEL = 32;
    private static final long MAX_CHUNK_BYTES_PER_PLAYER_TICK = 1024L * 1024L;
    private static final long MAX_CHUNK_BYTES_GLOBAL_TICK = 4L * 1024L * 1024L;
    private static final int MAX_INCREMENTAL_PACKETS_GLOBAL_TICK = 32;
    private static final int MAX_LOAD_PROBES = 8;
    private static final int MAX_PREPARED_CHUNKS_PER_TICK = 8;
    private static final long PREPARATION_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_CHUNK_COORDINATE = 1_875_000;
    private static final int MAX_DIRTY_SECTIONS = 256;
    private static final int MAX_REMOTE_ENTITIES = 128;
    private static final int MAX_ENTITY_SPAWNS_PER_TICK = 8;
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private static final Comparator<TicketKey> TICKET_COMPARATOR = Comparator
            .comparing((TicketKey key) -> key.dimension().location().toString())
            .thenComparingLong(TicketKey::chunk);
    private static final TicketType<TicketKey> REMOTE_TICKET = TicketType.create("glass_remote_scene", TICKET_COMPARATOR, TICKET_TIMEOUT_TICKS);

    public static void subscribe(ServerPlayer player, C2SRemoteSubscribePacket packet) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            server.execute(() -> subscribe(player, packet));
            return;
        }
        ServerState state = STATES.computeIfAbsent(server, ignored -> new ServerState());
        PlayerState playerState = state.players.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        RemoteSubscriptionId id = packet.subscription();
        ProjectorKey projectorKey = new ProjectorKey(id.projectorDimension(), id.projectorPos());
        Subscription existing = playerState.subscriptions.get(id);
        if (existing != null) {
            update(player, new C2SRemoteUpdatePacket(id, packet.cameraCenter(), packet.requestedRadius()));
            return;
        }
        if (id.epoch() <= playerState.latestEpoch) {
            LOGGER.warn("[GLASS projector] server rejected stale subscribe player={} id={} latestEpoch={}", player.getUUID(), id, playerState.latestEpoch);
            send(player, new S2CRemoteUnavailablePacket(id, S2CRemoteUnavailablePacket.Reason.INVALID));
            return;
        }
        List<Subscription> replacements = playerState.subscriptions.values().stream()
                .filter(subscription -> subscription.projectorKey.equals(projectorKey))
                .toList();
        ServerLevel projectorLevel = server.getLevel(id.projectorDimension());
        ServerLevel sourceLevel = server.getLevel(id.source().dimension());
        boolean subscriptionAuthorized = player.serverLevel().dimension().equals(id.projectorDimension())
                && safeCenter(packet.cameraCenter())
                && projectorLevel != null
                && sourceLevel != null
                && projectorLevel.hasChunkAt(id.projectorPos())
                && projectorLevel.getBlockState(id.projectorPos()).is(GBlocks.PROJECTOR)
                && projectorLevel.getBlockEntity(id.projectorPos()) instanceof ProjectorBlockEntity projector
                && projector.getChannel().equals(id.source().channel())
                && ChannelManagerPersistence.get(server).resolve(id.source().channel()).filter(id.source()::equals).isPresent()
                && PlatformNetworking.tracking(projectorLevel, id.projectorPos()).contains(player)
                && chunkDistance(new ChunkPos(id.source().pos()), packet.cameraCenter()) <= SOURCE_CAMERA_BUDGET;
        if (playerState.subscriptions.size() - replacements.size() >= MAX_SUBSCRIPTIONS_PER_PLAYER
                || packet.requestedRadius() < 1
                || packet.requestedRadius() > MAX_RADIUS
                || !subscriptionAuthorized) {
            LOGGER.warn("[GLASS projector] server rejected subscribe player={} id={} radius={} subscriptions={} replacements={} {}",
                    player.getUUID(), id, packet.requestedRadius(), playerState.subscriptions.size(), replacements.size(),
                    authorizationDiagnostics(server, player, id, packet.cameraCenter()));
            send(player, new S2CRemoteUnavailablePacket(id, playerState.subscriptions.size() - replacements.size() >= MAX_SUBSCRIPTIONS_PER_PLAYER
                    ? S2CRemoteUnavailablePacket.Reason.BUDGET
                    : S2CRemoteUnavailablePacket.Reason.INVALID));
            return;
        }
        for (Subscription replacement : replacements) {
            closeSubscription(server, player, replacement, S2CRemoteUnavailablePacket.Reason.ENDED, true);
            playerState.subscriptions.remove(replacement.id);
        }
        playerState.latestEpoch = id.epoch();
        Subscription subscription = new Subscription(player.getUUID(), id, packet.cameraCenter(), packet.requestedRadius(), server.getTickCount());
        playerState.subscriptions.put(id, subscription);
        LOGGER.info("[GLASS projector] server accepted subscribe player={} id={} center={} radius={}",
                player.getUUID(), id, packet.cameraCenter(), packet.requestedRadius());
        rebalance(server, state);
    }

    public static void update(ServerPlayer player, C2SRemoteUpdatePacket packet) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            server.execute(() -> update(player, packet));
            return;
        }
        ServerState state = STATES.get(server);
        PlayerState playerState = state == null ? null : state.players.get(player.getUUID());
        Subscription subscription = playerState == null ? null : playerState.subscriptions.get(packet.subscription());
        RemoteSubscriptionId id = packet.subscription();
        ServerLevel projectorLevel = server.getLevel(id.projectorDimension());
        ServerLevel sourceLevel = server.getLevel(id.source().dimension());
        boolean subscriptionAuthorized = player.serverLevel().dimension().equals(id.projectorDimension())
                && safeCenter(packet.cameraCenter())
                && projectorLevel != null
                && sourceLevel != null
                && projectorLevel.hasChunkAt(id.projectorPos())
                && projectorLevel.getBlockState(id.projectorPos()).is(GBlocks.PROJECTOR)
                && projectorLevel.getBlockEntity(id.projectorPos()) instanceof ProjectorBlockEntity projector
                && projector.getChannel().equals(id.source().channel())
                && ChannelManagerPersistence.get(server).resolve(id.source().channel()).filter(id.source()::equals).isPresent()
                && PlatformNetworking.tracking(projectorLevel, id.projectorPos()).contains(player)
                && chunkDistance(new ChunkPos(id.source().pos()), packet.cameraCenter()) <= SOURCE_CAMERA_BUDGET;
        if (subscription == null
                || packet.requestedRadius() < 1
                || packet.requestedRadius() > MAX_RADIUS
                || !subscriptionAuthorized) {
            LOGGER.warn("[GLASS projector] server rejected update player={} id={} subscriptionExists={} radius={} {}",
                    player.getUUID(), id, subscription != null, packet.requestedRadius(),
                    authorizationDiagnostics(server, player, id, packet.cameraCenter()));
            send(player, new S2CRemoteUnavailablePacket(packet.subscription(), S2CRemoteUnavailablePacket.Reason.INVALID));
            return;
        }
        boolean changed = subscription.requestedRadius != packet.requestedRadius()
                || !subscription.requestedCenter.equals(packet.cameraCenter());
        subscription.lastSeenTick = server.getTickCount();
        subscription.requestedRadius = packet.requestedRadius();
        if (!subscription.requestedCenter.equals(packet.cameraCenter())) {
            subscription.requestedCenter = packet.cameraCenter();
        }
        if (changed) {
            rebalance(server, state);
        }
    }

    public static void acknowledge(ServerPlayer player, C2SRemoteChunkAckPacket packet) {
        ServerState state = STATES.get(player.getServer());
        PlayerState subscriptions = state == null ? null : state.players.get(player.getUUID());
        Subscription subscription = subscriptions == null ? null : subscriptions.subscriptions.get(packet.subscription());
        InFlight flight = subscription == null ? null : subscription.inFlight.get(packet.chunk());
        if (flight != null && flight.sequence() == packet.sequence()) {
            subscription.inFlight.remove(packet.chunk());
            subscription.acknowledgedChunks.add(packet.chunk());
        }
    }

    public static void unsubscribe(ServerPlayer player, C2SRemoteUnsubscribePacket packet) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            server.execute(() -> unsubscribe(player, packet));
            return;
        }
        ServerState state = STATES.get(server);
        PlayerState playerState = state == null ? null : state.players.get(player.getUUID());
        if (playerState == null) {
            return;
        }
        Subscription subscription = playerState.subscriptions.remove(packet.subscription());
        if (subscription != null) {
            closeSubscription(server, player, subscription, S2CRemoteUnavailablePacket.Reason.ENDED, false);
            rebalance(server, state);
        }
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            BlockPos immutable = pos.immutable();
            server.execute(() -> onBlockChanged(level, immutable));
            return;
        }
        ServerState state = STATES.get(server);
        if (state == null) {
            return;
        }
        ChunkPos chunk = new ChunkPos(pos);
        state.preparedChunks.invalidate(level.dimension(), chunk);
        SectionPos section = SectionPos.of(pos);
        short packed = SectionPos.sectionRelativePos(pos);
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : playerState.subscriptions.values()) {
                if (!subscription.id.source().dimension().equals(level.dimension()) || !subscription.sentChunks.contains(chunk)
                        || vanillaTracks(server.getPlayerList().getPlayer(subscription.player), level, chunk)) {
                    continue;
                }
                if (level.getBlockEntity(pos) != null) {
                    subscription.blockEntities.add(pos.immutable());
                }
                LinkedHashSet<Short> positions = subscription.blockChanges.get(section);
                if (positions == null) {
                    if (subscription.blockChanges.size() >= MAX_DIRTY_SECTIONS) {
                        subscription.resyncChunks.add(chunk);
                        continue;
                    }
                    positions = new LinkedHashSet<>();
                    subscription.blockChanges.put(section, positions);
                }
                if (positions.size() >= S2CRemoteBlockUpdatesPacket.MAX_UPDATES && !positions.contains(packed)) {
                    subscription.blockChanges.remove(section);
                    subscription.resyncChunks.add(chunk);
                } else {
                    positions.add(packed);
                }
            }
        }
    }

    public static void onEntityPacket(Entity entity, Packet<?> packet) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> child : bundle.subPackets()) {
                onEntityPacket(entity, child);
            }
            return;
        }
        if (packet instanceof ClientboundMoveEntityPacket) {
            packet = new ClientboundTeleportEntityPacket(entity);
        }
        if (!S2CRemoteEntitiesPacket.supports(packet)) {
            return;
        }
        ServerState state = STATES.get(level.getServer());
        if (state == null) {
            return;
        }
        Object tracker = ((ChunkMapAccessor) level.getChunkSource().chunkMap).glass$getEntityMap().get(entity.getId());
        for (Map.Entry<UUID, PlayerState> entry : state.players.entrySet()) {
            ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (viewer == entity || (tracker instanceof TrackedEntityAccessor tracked && viewer != null
                    && tracked.glass$getSeenBy().contains(viewer.connection))) {
                continue;
            }
            for (Subscription subscription : entry.getValue().subscriptions.values()) {
                if (subscription.id.source().dimension().equals(level.dimension())
                        && subscription.entities.contains(entity.getId())) {
                    queueEntityPacket(subscription, entity.getId(), packet);
                }
            }
        }
    }

    public static void onEntityRemoved(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ServerState state = STATES.get(level.getServer());
        if (state == null) {
            return;
        }
        for (PlayerState player : state.players.values()) {
            for (Subscription subscription : player.subscriptions.values()) {
                if (subscription.id.source().dimension().equals(level.dimension())
                        && subscription.entities.remove(entity.getId())) {
                    queueEntityPacket(subscription, entity.getId(), new ClientboundRemoveEntitiesPacket(entity.getId()));
                }
            }
        }
    }

    private static void queueEntityPacket(Subscription subscription, int entityId, Packet<?> packet) {
        if (S2CRemoteEntitiesPacket.supports(packet)) {
            if (subscription.entityPackets.size() >= 1024) {
                subscription.entityOverflow = true;
                return;
            }
            subscription.entityPackets.addLast(new S2CRemoteEntitiesPacket.EntityMessage(entityId, packet));
        }
    }

    private static void streamEntities(MinecraftServer server, ServerState state) {
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : List.copyOf(playerState.subscriptions.values())) {
                ServerLevel level = server.getLevel(subscription.id.source().dimension());
                ServerPlayer player = server.getPlayerList().getPlayer(subscription.player);
                if (level == null || player == null || subscription.grantedRadius < 1) {
                    continue;
                }
                if (subscription.entityOverflow) {
                    invalidateSubscription(server, state, subscription, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                    continue;
                }
                Iterator<Integer> existing = subscription.entities.iterator();
                while (existing.hasNext()) {
                    int id = existing.next();
                    Entity entity = level.getEntity(id);
                    if (entity == null || entity.isRemoved() || !subscription.sentChunks.contains(entity.chunkPosition())
                            || !entity.broadcastToPlayer(player)) {
                        existing.remove();
                        queueEntityPacket(subscription, id, new ClientboundRemoveEntitiesPacket(id));
                    }
                }
                if (subscription.entities.size() < MAX_REMOTE_ENTITIES) {
                    int radius = subscription.grantedRadius;
                    ChunkPos center = subscription.grantedCenter;
                    AABB bounds = new AABB((center.x - radius) * 16.0, level.getMinBuildHeight(), (center.z - radius) * 16.0,
                            (center.x + radius + 1) * 16.0, level.getMaxBuildHeight(), (center.z + radius + 1) * 16.0);
                    int spawned = 0;
                    var tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).glass$getEntityMap();
                    for (Entity entity : level.getEntities((Entity) null, bounds, entity -> !entity.isRemoved())) {
                        if (spawned >= MAX_ENTITY_SPAWNS_PER_TICK || subscription.entities.size() >= MAX_REMOTE_ENTITIES) {
                            break;
                        }
                        Object tracker = tracked.get(entity.getId());
                        if (subscription.entities.contains(entity.getId()) || tracker == null
                                || !subscription.sentChunks.contains(entity.chunkPosition()) || !entity.broadcastToPlayer(player)) {
                            continue;
                        }
                        subscription.entities.add(entity.getId());
                        PlatformNetworking.sendPairingData(((TrackedEntityAccessor) tracker).glass$getServerEntity(), player,
                                packet -> queueEntityPacket(subscription, entity.getId(), packet));
                        spawned++;
                    }
                }
                if (!subscription.entityPackets.isEmpty()) {
                    List<S2CRemoteEntitiesPacket.EntityMessage> messages = new ArrayList<>();
                    while (messages.size() < S2CRemoteEntitiesPacket.MAX_MESSAGES && !subscription.entityPackets.isEmpty()) {
                        messages.add(subscription.entityPackets.removeFirst());
                    }
                    send(player, new S2CRemoteEntitiesPacket(subscription.id, subscription.nextSequence(), messages));
                }
            }
        }
    }

    public static void onLightChanged(ServerLevel level, LightLayer layer, SectionPos section) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            SectionPos immutable = SectionPos.of(section.x(), section.y(), section.z());
            server.execute(() -> onLightChanged(level, layer, immutable));
            return;
        }
        ServerState state = STATES.get(server);
        if (state == null) {
            return;
        }
        ChunkPos chunk = section.chunk();
        state.preparedChunks.invalidate(level.dimension(), chunk);
        int index = section.y() - level.getLightEngine().getMinLightSection();
        if (index < 0 || index >= level.getLightEngine().getLightSectionCount()) {
            return;
        }
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : playerState.subscriptions.values()) {
                if (!subscription.id.source().dimension().equals(level.dimension()) || !subscription.sentChunks.contains(chunk)
                        || vanillaTracks(server.getPlayerList().getPlayer(subscription.player), level, chunk)) {
                    continue;
                }
                LightChanges changes = subscription.lightChanges.computeIfAbsent(chunk, ignored -> new LightChanges());
                (layer == LightLayer.SKY ? changes.sky : changes.block).set(index);
            }
        }
    }

    public static void onBiomesChanged(ServerLevel level, Collection<? extends ChunkAccess> chunks) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            List<ChunkPos> positions = chunks.stream().map(ChunkAccess::getPos).toList();
            server.execute(() -> onBiomePositionsChanged(level, positions));
            return;
        }
        onBiomePositionsChanged(level, chunks.stream().map(ChunkAccess::getPos).toList());
    }

    private static void onBiomePositionsChanged(ServerLevel level, Collection<ChunkPos> chunks) {
        ServerState state = STATES.get(level.getServer());
        if (state == null) {
            return;
        }
        for (ChunkPos chunk : chunks) {
            state.preparedChunks.invalidate(level.dimension(), chunk);
        }
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : playerState.subscriptions.values()) {
                if (!subscription.id.source().dimension().equals(level.dimension())) {
                    continue;
                }
                for (ChunkPos chunk : chunks) {
                    if (subscription.sentChunks.contains(chunk)
                            && !vanillaTracks(level.getServer().getPlayerList().getPlayer(subscription.player), level, chunk)) {
                        subscription.resyncChunks.add(chunk);
                    }
                }
            }
        }
    }

    public static void tick(MinecraftServer server) {
        ServerState state = STATES.get(server);
        if (state == null) {
            return;
        }
        int now = server.getTickCount();
        List<Removal> removals = new ArrayList<>();
        for (Map.Entry<UUID, PlayerState> playerEntry : state.players.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            for (Subscription subscription : playerEntry.getValue().subscriptions.values()) {
                for (Map.Entry<ChunkPos, InFlight> flight : List.copyOf(subscription.inFlight.entrySet())) {
                    if (now - flight.getValue().sentTick() >= CHUNK_ACK_TIMEOUT_TICKS) {
                        subscription.inFlight.remove(flight.getKey());
                        subscription.acknowledgedChunks.remove(flight.getKey());
                        subscription.resyncChunks.add(flight.getKey());
                    }
                }
                RemoteSubscriptionId id = subscription.id;
                ServerLevel projectorLevel = server.getLevel(id.projectorDimension());
                ServerLevel sourceLevel = server.getLevel(id.source().dimension());
                boolean subscriptionAuthorized = player != null
                        && player.serverLevel().dimension().equals(id.projectorDimension())
                        && safeCenter(subscription.requestedCenter)
                        && projectorLevel != null
                        && sourceLevel != null
                        && projectorLevel.hasChunkAt(id.projectorPos())
                        && projectorLevel.getBlockState(id.projectorPos()).is(GBlocks.PROJECTOR)
                        && projectorLevel.getBlockEntity(id.projectorPos()) instanceof ProjectorBlockEntity projector
                        && projector.getChannel().equals(id.source().channel())
                        && ChannelManagerPersistence.get(server).resolve(id.source().channel()).filter(id.source()::equals).isPresent()
                        && PlatformNetworking.tracking(projectorLevel, id.projectorPos()).contains(player)
                        && chunkDistance(new ChunkPos(id.source().pos()), subscription.requestedCenter) <= SOURCE_CAMERA_BUDGET;
                if (player == null || now - subscription.lastSeenTick > SUBSCRIPTION_TIMEOUT_TICKS) {
                    removals.add(new Removal(playerEntry.getKey(), subscription, S2CRemoteUnavailablePacket.Reason.TIMEOUT));
                } else if (!subscriptionAuthorized) {
                    removals.add(new Removal(playerEntry.getKey(), subscription, S2CRemoteUnavailablePacket.Reason.INVALID));
                }
            }
        }
        for (Removal removal : removals) {
            PlayerState playerState = state.players.get(removal.player);
            if (playerState == null || playerState.subscriptions.remove(removal.subscription.id) == null) {
                continue;
            }
            closeSubscription(server, server.getPlayerList().getPlayer(removal.player), removal.subscription, removal.reason, true);
        }
        state.players.entrySet().removeIf(entry -> entry.getValue().subscriptions.isEmpty()
                && server.getPlayerList().getPlayer(entry.getKey()) == null);
        if (!removals.isEmpty() || state.rebalanceNeeded) {
            state.rebalanceNeeded = false;
            rebalance(server, state);
        }
        if (now % TICKET_REFRESH_INTERVAL == 0) {
            refreshTickets(server, state);
        }
        flushIncremental(server, state, MAX_INCREMENTAL_PACKETS_GLOBAL_TICK);
        preparePendingChunks(server, state);
        streamChunks(server, state, MAX_CHUNKS_GLOBAL_TICK);
        streamEntities(server, state);
        if (now % STATE_INTERVAL == 0) {
            state.preparedChunks.trim(now);
            sendWorldStates(server, state);
            logStreamProgress(server, state);
        }
    }

    private static String authorizationDiagnostics(MinecraftServer server, ServerPlayer player, RemoteSubscriptionId id, ChunkPos center) {
        ServerLevel projectorLevel = server.getLevel(id.projectorDimension());
        boolean projectorChunkLoaded = projectorLevel != null && projectorLevel.hasChunkAt(id.projectorPos());
        ProjectorBlockEntity projector = projectorChunkLoaded && projectorLevel.getBlockEntity(id.projectorPos()) instanceof ProjectorBlockEntity entity
                ? entity : null;
        return "playerDimension=" + (player == null ? "disconnected" : player.serverLevel().dimension().location())
                + " sourceLevelExists=" + (server.getLevel(id.source().dimension()) != null)
                + " projectorChunkLoaded=" + projectorChunkLoaded + " projectorExists=" + (projector != null)
                + " projectorChannel=" + (projector == null ? "none" : projector.getChannel())
                + " projectorPowered=" + (projector != null && projector.isActive())
                + " currentSource=" + ChannelManagerPersistence.get(server).resolve(id.source().channel()).orElse(null)
                + " tracking=" + (projectorChunkLoaded && player != null && PlatformNetworking.tracking(projectorLevel, id.projectorPos()).contains(player))
                + " center=" + center + " safeCenter=" + safeCenter(center)
                + " sourceCameraDistance=" + chunkDistance(new ChunkPos(id.source().pos()), center);
    }

    private static void logStreamProgress(MinecraftServer server, ServerState state) {
        int now = server.getTickCount();
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : playerState.subscriptions.values()) {
                if (subscription.grantedRadius < 1) {
                    continue;
                }
                if (subscription.pendingChunks.isEmpty() && subscription.inFlight.isEmpty()) {
                    if (!subscription.streamCompleteLogged && now - subscription.lastStreamLogTick >= STATE_INTERVAL) {
                        LOGGER.info("[GLASS projector] server chunks ready id={} center={} radius={} available={}/{} tickets={} sequence={} reusedPlayer={} reusedProjection={} transferred={}",
                                subscription.id, subscription.grantedCenter, subscription.grantedRadius,
                                subscription.sentChunks.size(), subscription.interest.size(), subscription.tickets.size(), subscription.sequence,
                                subscription.reusedPlayerChunks, subscription.reusedProjectionChunks, subscription.transferredChunks);
                        subscription.streamCompleteLogged = true;
                        subscription.lastStreamLogTick = now;
                    }
                    continue;
                }
                subscription.streamCompleteLogged = false;
                if (now - subscription.lastStreamLogTick < 100) {
                    continue;
                }
                subscription.lastStreamLogTick = now;
                ServerLevel level = server.getLevel(subscription.id.source().dimension());
                int missing = 0;
                int unlit = 0;
                for (ChunkPos pos : subscription.pendingChunks) {
                    LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(pos.x, pos.z);
                    if (chunk == null) {
                        missing++;
                    } else if (!chunk.isLightCorrect()) {
                        unlit++;
                    }
                }
                LOGGER.warn("[GLASS projector] server chunk stream pending id={} center={} radius={} sent={}/{} pending={} missing={} unlit={} nextChunk={} tickets={} heartbeatAgeTicks={} sequence={} inFlight={} reusedPlayer={} reusedProjection={} transferred={}",
                        subscription.id, subscription.grantedCenter, subscription.grantedRadius, subscription.sentChunks.size(),
                        subscription.interest.size(), subscription.pendingChunks.size(), missing, unlit,
                        subscription.pendingChunks.peek(), subscription.tickets.size(), now - subscription.lastSeenTick, subscription.sequence,
                        subscription.inFlight.size(), subscription.reusedPlayerChunks, subscription.reusedProjectionChunks, subscription.transferredChunks);
            }
        }
    }

    private static void rebalance(MinecraftServer server, ServerState state) {
        List<Subscription> ordered = state.players.values().stream()
                .flatMap(playerState -> playerState.subscriptions.values().stream())
                .sorted(SUBSCRIPTION_COMPARATOR)
                .toList();
        Map<UUID, Set<TicketKey>> playerChunks = new HashMap<>();
        Set<TicketKey> globalChunks = new HashSet<>();
        Map<Subscription, Integer> grants = new IdentityHashMap<>();
        for (Subscription subscription : ordered) {
            Set<TicketKey> playerUsed = playerChunks.computeIfAbsent(subscription.player, ignored -> new HashSet<>());
            Set<TicketKey> needed = regionKeys(subscription, MIN_RADIUS);
            long additionalPlayer = needed.stream().filter(key -> !playerUsed.contains(key)).count();
            long additionalGlobal = needed.stream().filter(key -> !globalChunks.contains(key)).count();
            if (playerUsed.size() + additionalPlayer <= MAX_CHUNKS_PER_PLAYER
                    && globalChunks.size() + additionalGlobal <= MAX_CHUNKS_GLOBAL) {
                grants.put(subscription, MIN_RADIUS);
                playerUsed.addAll(needed);
                globalChunks.addAll(needed);
            } else {
                grants.put(subscription, 0);
            }
        }
        for (Subscription subscription : ordered) {
            if (grants.get(subscription) == 0) {
                continue;
            }
            Set<TicketKey> playerUsed = playerChunks.get(subscription.player);
            for (int radius = Math.min(MAX_RADIUS, subscription.requestedRadius); radius > MIN_RADIUS; radius--) {
                Set<TicketKey> needed = regionKeys(subscription, radius);
                long additionalPlayer = needed.stream().filter(key -> !playerUsed.contains(key)).count();
                long additionalGlobal = needed.stream().filter(key -> !globalChunks.contains(key)).count();
                if (playerUsed.size() + additionalPlayer <= MAX_CHUNKS_PER_PLAYER
                        && globalChunks.size() + additionalGlobal <= MAX_CHUNKS_GLOBAL) {
                    grants.put(subscription, radius);
                    playerUsed.addAll(needed);
                    globalChunks.addAll(needed);
                    break;
                }
            }
        }
        for (Subscription subscription : ordered) {
            int granted = grants.get(subscription);
            if (subscription.grantedRadius != granted || !subscription.grantedCenter.equals(subscription.requestedCenter)) {
                configureInterest(server, subscription, subscription.requestedCenter, granted);
            }
        }
    }

    private static Set<TicketKey> regionKeys(Subscription subscription, int radius) {
        Set<TicketKey> keys = new HashSet<>();
        for (ChunkPos chunk : new ProjectionChunkRegion(subscription.requestedCenter, radius).withNeighbors().chunks()) {
            keys.add(new TicketKey(subscription.id.source().dimension(), chunk.toLong()));
        }
        return keys;
    }

    private static boolean vanillaTracks(ServerPlayer player, ServerLevel level, ChunkPos chunk) {
        return player != null && player.serverLevel() == level
                && ((ChunkMapAccessor) level.getChunkSource().chunkMap).glass$isChunkTracked(player, chunk.x, chunk.z);
    }

    private static boolean reuseChunk(MinecraftServer server, Subscription subscription, ServerLevel level, ChunkPos chunk) {
        ServerPlayer player = server.getPlayerList().getPlayer(subscription.player);
        if (vanillaTracks(player, level, chunk)) {
            if (subscription.sentChunks.add(chunk)) {
                subscription.reusedPlayerChunks++;
            }
            subscription.acknowledgedChunks.add(chunk);
            return true;
        }
        ServerState state = STATES.get(server);
        PlayerState owner = state == null ? null : state.players.get(subscription.player);
        if (owner != null) {
            for (Subscription other : owner.subscriptions.values()) {
                if (other != subscription && other.id.source().dimension().equals(level.dimension())
                        && other.acknowledgedChunks.contains(chunk)) {
                    if (subscription.sentChunks.add(chunk)) {
                        subscription.reusedProjectionChunks++;
                    }
                    subscription.acknowledgedChunks.add(chunk);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean chunkInFlight(MinecraftServer server, Subscription subscription, ChunkPos chunk) {
        PlayerState player = STATES.get(server).players.get(subscription.player);
        return player.subscriptions.values().stream().anyMatch(other ->
                other.id.source().dimension().equals(subscription.id.source().dimension()) && other.inFlight.containsKey(chunk));
    }

    private static void retainTicket(MinecraftServer server, ServerLevel level, TicketKey key) {
        ServerState state = STATES.get(server);
        if (state.ticketReferences.merge(key, 1, Integer::sum) == 1) {
            level.getChunkSource().chunkMap.getDistanceManager().addTicket(REMOTE_TICKET,
                    new ChunkPos(key.chunk()), SEND_READY_TICKET_LEVEL, key);
        }
    }

    private static void releaseTicket(MinecraftServer server, ServerLevel level, TicketKey key) {
        ServerState state = STATES.get(server);
        int remaining = state == null ? 0 : state.ticketReferences.getOrDefault(key, 1) - 1;
        if (remaining <= 0) {
            if (state != null) {
                state.ticketReferences.remove(key);
            }
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(REMOTE_TICKET,
                    new ChunkPos(key.chunk()), SEND_READY_TICKET_LEVEL, key);
        } else {
            state.ticketReferences.put(key, remaining);
        }
    }

    private static void configureInterest(MinecraftServer server, Subscription subscription, ChunkPos center, int radius) {
        ServerLevel level = server.getLevel(subscription.id.source().dimension());
        if (level == null) {
            return;
        }
        var dimensionType = level.dimensionTypeRegistration().unwrapKey();
        boolean unsupported = radius > 0 && dimensionType.isEmpty();
        Set<ChunkPos> next = radius == 0 || unsupported ? Set.of() : new ProjectionChunkRegion(center, radius).withNeighbors().chunks();
        Set<ChunkPos> removed = new HashSet<>(subscription.interest);
        removed.removeAll(next);
        Set<ChunkPos> added = new HashSet<>(next);
        added.removeAll(subscription.interest);
        for (ChunkPos chunk : added) {
            TicketKey key = new TicketKey(level.dimension(), chunk.toLong());
            retainTicket(server, level, key);
            subscription.tickets.put(chunk, key);
        }
        for (ChunkPos chunk : removed) {
            TicketKey key = subscription.tickets.remove(chunk);
            if (key != null) {
                releaseTicket(server, level, key);
            }
            subscription.pendingChunks.remove(chunk);
            subscription.acknowledgedChunks.remove(chunk);
            subscription.inFlight.remove(chunk);
            subscription.refreshChunks.remove(chunk);
            subscription.resyncChunks.remove(chunk);
            subscription.lightChanges.remove(chunk);
            subscription.blockEntities.removeIf(pos -> new ChunkPos(pos).equals(chunk));
            subscription.blockChanges.keySet().removeIf(section -> section.chunk().equals(chunk));
            if (subscription.sentChunks.remove(chunk)) {
                sendToSubscription(server, subscription, new S2CRemoteUnloadPacket(subscription.id, subscription.nextSequence(), chunk.x, chunk.z));
            }
        }
        subscription.interest.clear();
        subscription.interest.addAll(next);
        subscription.grantedCenter = center;
        subscription.grantedRadius = unsupported ? 0 : radius;
        subscription.pendingChunks = new PriorityQueue<>(chunkComparator(center));
        for (ChunkPos chunk : next) {
            if (subscription.refreshChunks.contains(chunk)
                    || (!subscription.sentChunks.contains(chunk) && !reuseChunk(server, subscription, level, chunk))) {
                subscription.pendingChunks.add(chunk);
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(subscription.player);
        int now = server.getTickCount();
        if (!subscription.grantLogged || now - subscription.lastGrantLogTick >= 100 || radius == 0 || unsupported) {
            LOGGER.info("[GLASS projector] server grant id={} center={} radius={} requestedRadius={} chunks={} added={} removed={} pending={} unsupported={}",
                    subscription.id, center, subscription.grantedRadius, subscription.requestedRadius,
                    next.size(), added.size(), removed.size(), subscription.pendingChunks.size(), unsupported);
            subscription.grantLogged = true;
            subscription.lastGrantLogTick = now;
        }
        if (unsupported) {
            ServerState state = STATES.get(server);
            if (state == null) {
                send(player, new S2CRemoteUnavailablePacket(subscription.id, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED));
            } else {
                invalidateSubscription(server, state, subscription, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
            }
            return;
        }
        if (radius == 0) {
            if (!subscription.budgetUnavailable) {
                send(player, new S2CRemoteUnavailablePacket(subscription.id, S2CRemoteUnavailablePacket.Reason.BUDGET));
                subscription.budgetUnavailable = true;
            }
            return;
        }
        subscription.budgetUnavailable = false;
        send(player, new S2CRemoteSubscriptionPacket(
                subscription.id,
                center,
                radius,
                dimensionType.get(),
                level.getDifficulty(),
                server.isHardcore(),
                level.isDebug(),
                level.isFlat(),
                BiomeManager.obfuscateSeed(level.getSeed()),
                worldState(level)
        ));
    }

    private static void refreshTickets(MinecraftServer server, ServerState state) {
        for (TicketKey key : state.ticketReferences.keySet()) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null) {
                level.getChunkSource().chunkMap.getDistanceManager().addTicket(REMOTE_TICKET,
                        new ChunkPos(key.chunk()), SEND_READY_TICKET_LEVEL, key);
            }
        }
    }

    private static int flushIncremental(MinecraftServer server, ServerState state, int budget) {
        List<Subscription> ordered = state.players.values().stream()
                .flatMap(playerState -> playerState.subscriptions.values().stream())
                .sorted(SUBSCRIPTION_COMPARATOR)
                .toList();
        for (Subscription subscription : ordered) {
            if (budget <= 0) {
                break;
            }
            for (ChunkPos chunk : List.copyOf(subscription.resyncChunks)) {
                subscription.resyncChunks.remove(chunk);
                subscription.lightChanges.remove(chunk);
                subscription.blockChanges.keySet().removeIf(section -> section.chunk().equals(chunk));
                if (subscription.interest.contains(chunk)) {
                    subscription.refreshChunks.add(chunk);
                    subscription.acknowledgedChunks.remove(chunk);
                    subscription.pendingChunks.remove(chunk);
                    subscription.pendingChunks.add(chunk);
                }
            }
            ServerLevel level = server.getLevel(subscription.id.source().dimension());
            if (level == null) {
                continue;
            }
            if (!subscription.blockEntities.isEmpty() && budget > 0) {
                List<ClientboundBlockEntityDataPacket> updates = new ArrayList<>();
                Iterator<BlockPos> positions = subscription.blockEntities.iterator();
                while (positions.hasNext() && updates.size() < S2CRemoteBlockEntitiesPacket.MAX_UPDATES) {
                    BlockPos pos = positions.next();
                    if (subscription.blockChanges.containsKey(SectionPos.of(pos))
                            || subscription.pendingChunks.contains(new ChunkPos(pos))) {
                        continue;
                    }
                    positions.remove();
                    if (subscription.sentChunks.contains(new ChunkPos(pos))
                            && !vanillaTracks(server.getPlayerList().getPlayer(subscription.player), level, new ChunkPos(pos))) {
                        var blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            updates.add(ClientboundBlockEntityDataPacket.create(blockEntity));
                        }
                    }
                }
                if (!updates.isEmpty()) {
                    sendToSubscription(server, subscription, new S2CRemoteBlockEntitiesPacket(subscription.id, subscription.nextSequence(), updates));
                    budget--;
                }
            }
            Iterator<Map.Entry<SectionPos, LinkedHashSet<Short>>> blockIterator = subscription.blockChanges.entrySet().iterator();
            while (budget > 0 && blockIterator.hasNext()) {
                Map.Entry<SectionPos, LinkedHashSet<Short>> entry = blockIterator.next();
                SectionPos section = entry.getKey();
                if (!subscription.sentChunks.contains(section.chunk())
                        || vanillaTracks(server.getPlayerList().getPlayer(subscription.player), level, section.chunk())) {
                    blockIterator.remove();
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(section.x(), section.z());
                if (chunk == null || section.y() < level.getMinSection() || section.y() >= level.getMaxSection()) {
                    blockIterator.remove();
                    continue;
                }
                ClientboundSectionBlocksUpdatePacket updates = new ClientboundSectionBlocksUpdatePacket(section,
                        new ShortOpenHashSet(entry.getValue()), chunk.getSection(level.getSectionIndexFromSectionY(section.y())));
                sendToSubscription(server, subscription, new S2CRemoteBlockUpdatesPacket(subscription.id, subscription.nextSequence(), section.x(), section.y(), section.z(), updates));
                blockIterator.remove();
                budget--;
            }
            Iterator<Map.Entry<ChunkPos, LightChanges>> lightIterator = subscription.lightChanges.entrySet().iterator();
            boolean unsupported = false;
            while (budget > 0 && lightIterator.hasNext()) {
                Map.Entry<ChunkPos, LightChanges> entry = lightIterator.next();
                if (!subscription.sentChunks.contains(entry.getKey())
                        || vanillaTracks(server.getPlayerList().getPlayer(subscription.player), level, entry.getKey())) {
                    lightIterator.remove();
                    continue;
                }
                try {
                    ClientboundLightUpdatePacket light = new ClientboundLightUpdatePacket(entry.getKey(), level.getLightEngine(), entry.getValue().sky, entry.getValue().block);
                    sendToSubscription(server, subscription, new S2CRemoteLightPacket(subscription.id, subscription.nextSequence(), light));
                } catch (IllegalArgumentException exception) {
                    unsupported = true;
                    break;
                }
                lightIterator.remove();
                budget--;
            }
            if (unsupported) {
                invalidateSubscription(server, state, subscription, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
            }
        }
        return budget;
    }

    private static void streamChunks(MinecraftServer server, ServerState state, int globalBudget) {
        List<Map.Entry<UUID, PlayerState>> players = state.players.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (players.isEmpty()) {
            return;
        }
        int playerStart = Math.floorMod(state.playerLoadCursor, players.size());
        int visitedPlayers = 0;
        long globalBytes = 0L;
        for (; visitedPlayers < players.size(); visitedPlayers++) {
            if (globalBudget <= 0 || globalBytes >= MAX_CHUNK_BYTES_GLOBAL_TICK) {
                break;
            }
            Map.Entry<UUID, PlayerState> playerEntry = players.get((playerStart + visitedPlayers) % players.size());
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) {
                continue;
            }
            int inFlight = playerEntry.getValue().subscriptions.values().stream().mapToInt(subscription -> subscription.inFlight.size()).sum();
            int playerBudget = Math.min(MAX_CHUNKS_PER_PLAYER_TICK, MAX_IN_FLIGHT_PER_PLAYER - inFlight);
            long playerBytes = 0L;
            List<Subscription> subscriptions = playerEntry.getValue().subscriptions.values().stream().sorted(SUBSCRIPTION_COMPARATOR).toList();
            if (subscriptions.isEmpty()) {
                continue;
            }
            int start = Math.floorMod(playerEntry.getValue().loadCursor, subscriptions.size());
            int idleSubscriptions = 0;
            for (int offset = 0; idleSubscriptions < subscriptions.size()
                    && playerBudget > 0 && globalBudget > 0
                    && playerBytes < MAX_CHUNK_BYTES_PER_PLAYER_TICK
                    && globalBytes < MAX_CHUNK_BYTES_GLOBAL_TICK; offset++) {
                Subscription subscription = subscriptions.get((start + offset) % subscriptions.size());
                ServerLevel level = server.getLevel(subscription.id.source().dimension());
                if (level == null || subscription.grantedRadius == 0) {
                    idleSubscriptions++;
                    continue;
                }
                LevelChunk chunk = pollReadyChunk(server, level, subscription);
                if (chunk == null) {
                    idleSubscriptions++;
                    continue;
                }
                PreparedChunkCache.PreparedChunk prepared = state.preparedChunks.find(level, chunk);
                if (prepared == null) {
                    subscription.pendingChunks.add(chunk.getPos());
                    idleSubscriptions++;
                    continue;
                }
                S2CRemoteChunkPacket packet = prepared.packet(subscription.id, subscription.nextSequence(), chunk.getPos());
                long packetBytes = packet.terrainAndLightBytes();
                if (playerBytes + packetBytes > MAX_CHUNK_BYTES_PER_PLAYER_TICK
                        || globalBytes + packetBytes > MAX_CHUNK_BYTES_GLOBAL_TICK) {
                    subscription.pendingChunks.add(chunk.getPos());
                    idleSubscriptions++;
                    continue;
                }
                send(player, packet);
                subscription.transferredChunks++;
                subscription.sentChunks.add(chunk.getPos());
                subscription.inFlight.put(chunk.getPos(), new InFlight(packet.sequence(), server.getTickCount()));
                subscription.refreshChunks.remove(chunk.getPos());
                playerEntry.getValue().loadCursor = (start + offset + 1) % subscriptions.size();
                idleSubscriptions = 0;
                playerBytes += packetBytes;
                globalBytes += packetBytes;
                playerBudget--;
                globalBudget--;
            }
        }
        state.playerLoadCursor = (playerStart + Math.max(1, visitedPlayers)) % players.size();
    }

    private static LevelChunk pollReadyChunk(MinecraftServer server, ServerLevel level, Subscription subscription) {
        if (subscription.pendingChunks.isEmpty()) {
            return null;
        }
        List<ChunkPos> deferred = new ArrayList<>(MAX_LOAD_PROBES);
        LevelChunk result = null;
        for (int i = 0; i < MAX_LOAD_PROBES && !subscription.pendingChunks.isEmpty(); i++) {
            ChunkPos pos = subscription.pendingChunks.poll();
            if (!subscription.interest.contains(pos)) {
                continue;
            }
            if (!subscription.refreshChunks.contains(pos) && reuseChunk(server, subscription, level, pos)) {
                continue;
            }
            if (chunkInFlight(server, subscription, pos)) {
                deferred.add(pos);
                continue;
            }
            LevelChunk chunk = ((ChunkMapAccessor) level.getChunkSource().chunkMap).glass$getChunkToSend(pos.toLong());
            if (chunk != null && chunk.isLightCorrect()) {
                result = chunk;
                break;
            }
            deferred.add(pos);
        }
        subscription.pendingChunks.addAll(deferred);
        return result;
    }

    private static void preparePendingChunks(MinecraftServer server, ServerState state) {
        long started = System.nanoTime();
        int prepared = 0;
        List<Subscription> subscriptions = state.players.values().stream()
                .flatMap(player -> player.subscriptions.values().stream())
                .filter(subscription -> subscription.grantedRadius > 0 && !subscription.pendingChunks.isEmpty())
                .sorted(SUBSCRIPTION_COMPARATOR).toList();
        if (subscriptions.isEmpty()) {
            return;
        }
        int start = Math.floorMod(state.preparationCursor++, subscriptions.size());
        for (int i = 0; i < subscriptions.size(); i++) {
            Subscription subscription = subscriptions.get((start + i) % subscriptions.size());
            ServerLevel level = server.getLevel(subscription.id.source().dimension());
            if (level == null) {
                continue;
            }
            for (ChunkPos pos : subscription.pendingChunks.stream().sorted(chunkComparator(subscription.grantedCenter)).limit(16).toList()) {
                if (prepared >= MAX_PREPARED_CHUNKS_PER_TICK || System.nanoTime() - started >= PREPARATION_BUDGET_NANOS) {
                    return;
                }
                if (!subscription.refreshChunks.contains(pos) && reuseChunk(server, subscription, level, pos)) {
                    subscription.pendingChunks.remove(pos);
                    continue;
                }
                LevelChunk chunk = ((ChunkMapAccessor) level.getChunkSource().chunkMap).glass$getChunkToSend(pos.toLong());
                if (chunk == null || !chunk.isLightCorrect() || state.preparedChunks.find(level, chunk) != null) {
                    continue;
                }
                try {
                    state.preparedChunks.prepare(level, chunk);
                } catch (IllegalArgumentException exception) {
                    LOGGER.warn("[GLASS projector] cannot prepare chunk {} for {}", pos, subscription.id, exception);
                    invalidateSubscription(server, state, subscription, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                    return;
                }
                prepared++;
            }
        }
    }

    private static void sendWorldStates(MinecraftServer server, ServerState state) {
        for (PlayerState playerState : state.players.values()) {
            for (Subscription subscription : playerState.subscriptions.values()) {
                if (subscription.grantedRadius == 0) {
                    continue;
                }
                ServerLevel level = server.getLevel(subscription.id.source().dimension());
                if (level != null) {
                    sendToSubscription(server, subscription, new S2CRemoteWorldStatePacket(subscription.id, subscription.nextSequence(), worldState(level)));
                }
            }
        }
    }

    private static RemoteWorldState worldState(ServerLevel level) {
        return new RemoteWorldState(
                level.getGameTime(),
                level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),
                level.getRainLevel(1.0F),
                level.getThunderLevel(1.0F),
                0
        );
    }

    public static void removePlayer(MinecraftServer server, ServerPlayer player, boolean notify) {
        if (!server.isSameThread()) {
            server.execute(() -> removePlayer(server, player, notify));
            return;
        }
        ServerState state = STATES.get(server);
        if (state == null) {
            return;
        }
        PlayerState removed = state.players.remove(player.getUUID());
        if (removed == null) {
            return;
        }
        for (Subscription subscription : removed.subscriptions.values()) {
            closeSubscription(server, player, subscription, S2CRemoteUnavailablePacket.Reason.ENDED, notify);
        }
        rebalance(server, state);
    }

    public static void unloadWorld(MinecraftServer server, ServerLevel world) {
        ServerState state = STATES.get(server);
        if (state == null) {
            return;
        }
        state.preparedChunks.unload(world.dimension());
        List<Removal> removals = new ArrayList<>();
        for (Map.Entry<UUID, PlayerState> playerEntry : state.players.entrySet()) {
            for (Subscription subscription : playerEntry.getValue().subscriptions.values()) {
                if (subscription.id.source().dimension().equals(world.dimension()) || subscription.id.projectorDimension().equals(world.dimension())) {
                    removals.add(new Removal(playerEntry.getKey(), subscription, S2CRemoteUnavailablePacket.Reason.ENDED));
                }
            }
        }
        for (Removal removal : removals) {
            PlayerState playerState = state.players.get(removal.player);
            if (playerState != null && playerState.subscriptions.remove(removal.subscription.id) != null) {
                closeSubscription(server, server.getPlayerList().getPlayer(removal.player), removal.subscription, removal.reason, true);
            }
        }
        rebalance(server, state);
    }

    public static void stop(MinecraftServer server) {
        ServerState state = STATES.remove(server);
        if (state == null) {
            return;
        }
        for (Map.Entry<UUID, PlayerState> playerEntry : state.players.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            for (Subscription subscription : playerEntry.getValue().subscriptions.values()) {
                closeSubscription(server, player, subscription, S2CRemoteUnavailablePacket.Reason.ENDED, false);
            }
        }
    }

    private static void closeSubscription(MinecraftServer server, ServerPlayer player, Subscription subscription, S2CRemoteUnavailablePacket.Reason reason, boolean notify) {
        LOGGER.info("[GLASS projector] server closed id={} reason={} notify={} sent={}/{} pending={} tickets={} heartbeatAgeTicks={} {}",
                subscription.id, reason, notify, subscription.sentChunks.size(), subscription.interest.size(),
                subscription.pendingChunks.size(), subscription.tickets.size(), server.getTickCount() - subscription.lastSeenTick,
                reason == S2CRemoteUnavailablePacket.Reason.INVALID
                        ? authorizationDiagnostics(server, player, subscription.id, subscription.requestedCenter) : "");
        ServerLevel level = server.getLevel(subscription.id.source().dimension());
        if (level != null) {
            for (Map.Entry<ChunkPos, TicketKey> entry : subscription.tickets.entrySet()) {
                releaseTicket(server, level, entry.getValue());
            }
        }
        subscription.tickets.clear();
        subscription.interest.clear();
        subscription.pendingChunks.clear();
        subscription.sentChunks.clear();
        subscription.acknowledgedChunks.clear();
        subscription.inFlight.clear();
        subscription.refreshChunks.clear();
        subscription.blockChanges.clear();
        subscription.lightChanges.clear();
        subscription.resyncChunks.clear();
        subscription.entities.clear();
        subscription.entityPackets.clear();
        subscription.blockEntities.clear();
        if (notify) {
            send(player, new S2CRemoteUnavailablePacket(subscription.id, reason));
        }
    }

    private static void invalidateSubscription(MinecraftServer server, ServerState state, Subscription subscription, S2CRemoteUnavailablePacket.Reason reason) {
        PlayerState playerState = state.players.get(subscription.player);
        if (playerState != null && playerState.subscriptions.remove(subscription.id) != null) {
            closeSubscription(server, server.getPlayerList().getPlayer(subscription.player), subscription, reason, true);
            state.rebalanceNeeded = true;
        }
    }

    private static void sendToSubscription(MinecraftServer server, Subscription subscription, CustomPacketPayload payload) {
        send(server.getPlayerList().getPlayer(subscription.player), payload);
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player != null && PlatformNetworking.canSend(player, payload.type())) {
            PlatformNetworking.send(player, payload);
        }
    }

    private static int chunkDistance(ChunkPos first, ChunkPos second) {
        long x = Math.abs((long) first.x - second.x);
        long z = Math.abs((long) first.z - second.z);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(x, z));
    }

    private static boolean safeCenter(ChunkPos center) {
        int margin = MAX_RADIUS + 1;
        return Math.abs((long) center.x) <= MAX_CHUNK_COORDINATE - margin
                && Math.abs((long) center.z) <= MAX_CHUNK_COORDINATE - margin;
    }

    private static Comparator<ChunkPos> chunkComparator(ChunkPos center) {
        return Comparator.comparingInt((ChunkPos chunk) -> chunkDistance(center, chunk))
                .thenComparingInt(chunk -> chunk.x)
                .thenComparingInt(chunk -> chunk.z);
    }

    private static final Comparator<Subscription> SUBSCRIPTION_COMPARATOR = Comparator
            .comparing((Subscription subscription) -> subscription.player)
            .thenComparing(subscription -> subscription.id.projectorDimension().location().toString())
            .thenComparingLong(subscription -> subscription.id.projectorPos().asLong())
            .thenComparing(subscription -> subscription.id.source().channel())
            .thenComparingLong(subscription -> subscription.id.epoch());

    private static final class ServerState {
        private final Map<UUID, PlayerState> players = new HashMap<>();
        private final PreparedChunkCache preparedChunks = new PreparedChunkCache();
        private final Map<TicketKey, Integer> ticketReferences = new HashMap<>();
        private int playerLoadCursor;
        private int preparationCursor;
        private boolean rebalanceNeeded;
    }

    private static final class PlayerState {
        private final Map<RemoteSubscriptionId, Subscription> subscriptions = new LinkedHashMap<>();
        private long latestEpoch = -1L;
        private int loadCursor;
    }

    private static final class Subscription {
        private final UUID player;
        private final RemoteSubscriptionId id;
        private final ProjectorKey projectorKey;
        private final Set<ChunkPos> interest = new HashSet<>();
        private final Set<ChunkPos> sentChunks = new HashSet<>();
        private final Set<ChunkPos> acknowledgedChunks = new HashSet<>();
        private final Map<ChunkPos, InFlight> inFlight = new HashMap<>();
        private final Set<ChunkPos> refreshChunks = new HashSet<>();
        private final Map<ChunkPos, TicketKey> tickets = new HashMap<>();
        private final Map<SectionPos, LinkedHashSet<Short>> blockChanges = new LinkedHashMap<>();
        private final Map<ChunkPos, LightChanges> lightChanges = new LinkedHashMap<>();
        private final Set<ChunkPos> resyncChunks = new LinkedHashSet<>();
        private final Set<Integer> entities = new HashSet<>();
        private final Set<BlockPos> blockEntities = new LinkedHashSet<>();
        private final ArrayDeque<S2CRemoteEntitiesPacket.EntityMessage> entityPackets = new ArrayDeque<>();
        private boolean entityOverflow;
        private ChunkPos requestedCenter;
        private ChunkPos grantedCenter;
        private PriorityQueue<ChunkPos> pendingChunks;
        private int requestedRadius;
        private int grantedRadius;
        private int lastSeenTick;
        private long sequence;
        private boolean budgetUnavailable;
        private boolean grantLogged;
        private int lastGrantLogTick;
        private int lastStreamLogTick;
        private boolean streamCompleteLogged;
        private long reusedPlayerChunks;
        private long reusedProjectionChunks;
        private long transferredChunks;

        private Subscription(UUID player, RemoteSubscriptionId id, ChunkPos center, int requestedRadius, int now) {
            this.player = player;
            this.id = id;
            this.projectorKey = new ProjectorKey(id.projectorDimension(), id.projectorPos());
            this.requestedCenter = center;
            this.grantedCenter = center;
            this.pendingChunks = new PriorityQueue<>(chunkComparator(center));
            this.requestedRadius = requestedRadius;
            this.grantedRadius = -1;
            this.lastSeenTick = now;
            this.lastStreamLogTick = now;
        }

        private long nextSequence() {
            return sequence++;
        }
    }

    private static final class LightChanges {
        private final BitSet sky = new BitSet();
        private final BitSet block = new BitSet();
    }

    private record ProjectorKey(ResourceKey<Level> dimension, BlockPos pos) {
        private ProjectorKey {
            dimension = Objects.requireNonNull(dimension);
            pos = Objects.requireNonNull(pos).immutable();
        }
    }

    private record InFlight(long sequence, int sentTick) {
    }

    private record TicketKey(ResourceKey<Level> dimension, long chunk) {
    }

    private record Removal(UUID player, Subscription subscription, S2CRemoteUnavailablePacket.Reason reason) {
    }

    private RemoteSceneServerManager() {
    }
}
