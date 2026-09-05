package dev.imb11.client.remote;

import com.mojang.logging.LogUtils;
import dev.imb11.sync.remote.C2SRemoteSubscribePacket;
import dev.imb11.sync.remote.C2SRemoteUnsubscribePacket;
import dev.imb11.sync.remote.C2SRemoteUpdatePacket;
import dev.imb11.sync.remote.C2SRemoteChunkAckPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteWorldState;
import dev.imb11.sync.remote.S2CRemoteBlockUpdatesPacket;
import dev.imb11.sync.remote.S2CRemoteChunkPacket;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import dev.imb11.sync.remote.S2CRemoteBlockEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import dev.imb11.sync.remote.S2CRemoteLightPacket;
import dev.imb11.sync.remote.S2CRemoteSubscriptionPacket;
import dev.imb11.sync.remote.S2CRemoteUnavailablePacket;
import dev.imb11.sync.remote.S2CRemoteUnloadPacket;
import dev.imb11.sync.remote.S2CRemoteWorldStatePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RemoteSceneClientManager {
    public static final int MAX_PENDING_PER_SUBSCRIPTION = 64;
    public static final int MAX_PENDING_GLOBAL = 256;
    public static final int MAX_APPLIES_PER_TICK = 8;
    public static final long MAX_PENDING_BYTES_PER_SUBSCRIPTION = 8L * 1024L * 1024L;
    public static final long MAX_PENDING_BYTES_GLOBAL = 32L * 1024L * 1024L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 6;
    private static final int HEARTBEAT_TICKS = 40;
    private static final Map<RemoteSubscriptionId, SubscriptionState> SUBSCRIPTIONS = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, RemoteClientScene> SCENES = new LinkedHashMap<>();
    private static final ArrayDeque<PendingApply> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<C2SRemoteChunkAckPacket> ACKNOWLEDGEMENTS = new ArrayDeque<>();
    private static final long APPLY_BUDGET_NANOS = 2_000_000L;
    private static long nextLocalToken;
    private static long clientTicks;
    private static long pendingBytes;
    private static ClientLevel mainLevel;
    private static boolean receiversRegistered;

    private RemoteSceneClientManager() {
    }

    public static void registerReceivers() {
        if (receiversRegistered) {
            return;
        }
        receiversRegistered = true;
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteSubscriptionPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteUnavailablePacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteChunkPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteEntitiesPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteBlockEntitiesPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteUnloadPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteBlockUpdatesPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteLightPacket.PACKET_ID, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(S2CRemoteWorldStatePacket.PACKET_ID, (packet, context) -> accept(packet));
    }

    public static RemoteSceneHandle acquire(RemoteSubscriptionId subscription, ChunkPos cameraCenter, int requestedRadius) {
        Objects.requireNonNull(subscription);
        Objects.requireNonNull(cameraCenter);
        int radius = Math.clamp(requestedRadius, MIN_RADIUS, MAX_RADIUS);
        SubscriptionState state = SUBSCRIPTIONS.get(subscription);
        if (state == null) {
            state = new SubscriptionState(subscription, ++nextLocalToken, cameraCenter, radius);
            SUBSCRIPTIONS.put(subscription, state);
            state.subscribed = send(new C2SRemoteSubscribePacket(subscription, cameraCenter, radius));
            state.lastSendTick = clientTicks;
            LOGGER.info("[GLASS projector] client subscribe id={} center={} radius={} sent={}", subscription, cameraCenter, radius, state.subscribed);
        } else {
            state.references++;
            updateState(state, cameraCenter, radius, false);
        }
        return new RemoteSceneHandle(subscription, state.localToken);
    }

    public static void update(RemoteSceneHandle handle, ChunkPos cameraCenter, int requestedRadius) {
        Objects.requireNonNull(cameraCenter);
        SubscriptionState state = state(handle);
        if (state == null) {
            return;
        }
        updateState(state, cameraCenter, Math.clamp(requestedRadius, MIN_RADIUS, MAX_RADIUS), false);
    }

    public static void release(RemoteSceneHandle handle) {
        if (handle == null || !handle.markReleased()) {
            return;
        }
        SubscriptionState state = state(handle.subscription(), handle.localToken());
        if (state == null) {
            return;
        }
        state.references--;
        if (state.references > 0) {
            return;
        }
        SUBSCRIPTIONS.remove(state.id);
        LOGGER.info("[GLASS projector] client release id={} pending={} pendingBytes={} lastSequence={} unavailable={}",
                state.id, state.pendingCount, state.pendingBytes, state.highestSequence, state.unavailable);
        if (state.subscribed) {
            send(new C2SRemoteUnsubscribePacket(state.id));
        }
        removePending(state);
        detachState(state);
    }

    public static void attachRenderer(RemoteSceneHandle handle, LevelRenderer renderer) {
        Objects.requireNonNull(renderer);
        SubscriptionState state = state(handle);
        if (state == null) {
            return;
        }
        state.renderers.merge(renderer, 1, Integer::sum);
        if (state.scene != null) {
            state.scene.attachRenderer(state.id, renderer);
        }
    }

    public static void detachRenderer(RemoteSceneHandle handle, LevelRenderer renderer) {
        Objects.requireNonNull(renderer);
        SubscriptionState state = state(handle);
        if (state == null) {
            return;
        }
        Integer count = state.renderers.get(renderer);
        if (count == null) {
            return;
        }
        if (count == 1) {
            state.renderers.remove(renderer);
        } else {
            state.renderers.put(renderer, count - 1);
        }
        if (state.scene != null) {
            state.scene.detachRenderer(state.id, renderer);
        }
    }

    public static void tick(Minecraft minecraft) {
        clientTicks++;
        if (!minecraft.isPaused()) {
            for (RemoteClientScene scene : List.copyOf(SCENES.values())) {
                try {
                    scene.advanceClock();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to tick remote scene {}", scene.source().key(), exception);
                    failScene(scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                }
            }
        }
        int applied = 0;
        long applyStarted = System.nanoTime();
        while (applied < MAX_APPLIES_PER_TICK && System.nanoTime() - applyStarted < APPLY_BUDGET_NANOS) {
            PendingApply pending = PENDING.pollFirst();
            if (pending == null) {
                break;
            }
            accountDequeued(pending.state, pending.command.bytes());
            SubscriptionState current = SUBSCRIPTIONS.get(pending.state.id);
            if (current == pending.state && current.references > 0 && current.scene != null && !current.terminalFailure) {
                try {
                    pending.command.apply(current);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to apply remote scene payload {}", current.id, exception);
                    failScene(current.scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                }
            } else {
                pending.state.discardedApplies++;
            }
            applied++;
        }
        for (RemoteClientScene scene : List.copyOf(SCENES.values())) {
            try {
                scene.flushUpdates();
            } catch (RuntimeException exception) {
                LOGGER.warn("[GLASS projector] failed to finish remote scene updates {}", scene.source().key(), exception);
                failScene(scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
            }
        }
        while (!ACKNOWLEDGEMENTS.isEmpty()) {
            send(ACKNOWLEDGEMENTS.removeFirst());
        }
        for (SubscriptionState state : List.copyOf(SUBSCRIPTIONS.values())) {
            if (state.references < 1 || state.terminalFailure || clientTicks - state.lastSendTick < HEARTBEAT_TICKS) {
                continue;
            }
            if (!state.subscribed) {
                state.subscribed = send(new C2SRemoteSubscribePacket(state.id, state.requestedCenter, state.requestedRadius));
            } else {
                send(new C2SRemoteUpdatePacket(state.id, state.requestedCenter, state.requestedRadius));
            }
            state.lastSendTick = clientTicks;
        }
    }

    public static void onVanillaEntityAdded(ClientLevel level, Entity entity) {
        RemoteClientScene scene = SCENES.get(level.dimension());
        if (scene != null && scene.level() == level) {
            scene.onVanillaEntityAdded(entity);
        }
    }

    public static boolean onVanillaEntityRemoved(ClientLevel level, int id) {
        RemoteClientScene scene = SCENES.get(level.dimension());
        return scene != null && scene.level() == level && scene.onVanillaEntityRemoved(id);
    }

    public static void onMainLevelChanged(@Nullable ClientLevel level) {
        if (mainLevel == level) {
            return;
        }
        if (mainLevel != null || level == null) {
            clear(true);
        }
        mainLevel = level;
    }

    public static void onResourceReload() {
        clear(true);
    }

    public static void clearConnection() {
        clear(false);
        mainLevel = null;
    }

    static @Nullable ClientLevel level(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null || state.scene == null ? null : state.scene.level();
    }

    static @Nullable LightTexture lightTexture(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null || state.scene == null ? null : state.scene.lightTexture();
    }

    static @Nullable RemoteWorldState worldState(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null || state.scene == null ? null : state.scene.worldState();
    }

    static @Nullable ChunkPos grantedCenter(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null ? null : state.grantedCenter;
    }

    static int grantedRadius(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null ? 0 : state.grantedRadius;
    }

    static boolean isReady(RemoteSceneHandle handle, ChunkPos cameraCenter) {
        SubscriptionState state = state(handle);
        return state != null
                && state.scene != null
                && state.unavailable == null
                && state.withinGrant(cameraCenter, 0)
                && state.scene.isReady(cameraCenter);
    }

    static boolean isLoading(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state != null
                && state.unavailable == null
                && (state.scene == null || !state.scene.isReady(state.requestedCenter));
    }

    static boolean isComplete(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state != null
                && state.scene != null
                && state.unavailable == null
                && state.grantedCenter != null
                && state.scene.isReady(state.grantedCenter, state.grantedRadius + 1);
    }

    static boolean isUnavailable(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null || state.unavailable != null;
    }

    static boolean isTerminalFailure(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        return state == null || state.terminalFailure;
    }

    static String diagnostics(RemoteSceneHandle handle) {
        SubscriptionState state = state(handle);
        if (state == null) {
            return "epoch=" + handle.subscription().epoch() + " state=missing released=" + handle.released();
        }
        return "epoch=" + state.id.epoch() + " subscribed=" + state.subscribed + " unavailable=" + state.unavailable
                + " terminal=" + state.terminalFailure + " requestedCenter=" + state.requestedCenter
                + " requestedRadius=" + state.requestedRadius + " grantedCenter=" + state.grantedCenter
                + " grantedRadius=" + state.grantedRadius + " cameraWithinGrant=" + state.withinGrant(state.requestedCenter, 0)
                + " pending=" + state.pendingCount + " pendingBytes=" + state.pendingBytes
                + " globalPending=" + PENDING.size() + " globalPendingBytes=" + pendingBytes
                + " lastSequence=" + state.highestSequence + " stalePackets=" + state.stalePackets
                + " discardedApplies=" + state.discardedApplies + " lastSendAgeTicks=" + (clientTicks - state.lastSendTick)
                + " " + (state.scene == null || state.grantedCenter == null ? "chunks=unavailable"
                : state.scene.chunkDiagnostics(state.grantedCenter, state.grantedRadius + 1));
    }

    private static void accept(S2CRemoteSubscriptionPacket packet) {
        SubscriptionState state = SUBSCRIPTIONS.get(packet.subscription());
        if (state == null || state.references < 1 || state.terminalFailure) {
            return;
        }
        RemoteClientScene scene = SCENES.get(state.id.source().dimension());
        if (scene != null && !scene.matches(packet)) {
            fail(state, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED, true);
            return;
        }
        if (scene == null) {
            try {
                scene = RemoteClientScene.create(Minecraft.getInstance(), packet);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to create remote scene {}", state.id.source().key(), exception);
                fail(state, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED, true);
                return;
            }
            SCENES.put(state.id.source().dimension(), scene);
        }
        if (state.scene != scene) {
            if (state.scene != null) {
                detachStateScene(state);
            }
            state.scene = scene;
            for (Map.Entry<LevelRenderer, Integer> renderer : state.renderers.entrySet()) {
                for (int i = 0; i < renderer.getValue(); i++) {
                    scene.attachRenderer(state.id, renderer.getKey());
                }
            }
        }
        state.grantedCenter = packet.grantedCenter();
        state.grantedRadius = packet.grantedRadius();
        state.unavailable = null;
        state.subscribed = true;
        scene.setRegion(state.id, state.grantedCenter, state.grantedRadius);
        scene.applyWorldState(packet.state());
        long now = System.nanoTime();
        if (state.lastGrantLog == 0L || now - state.lastGrantLog >= 5_000_000_000L) {
            LOGGER.info("[GLASS projector] client grant id={} center={} radius={} requestedCenter={} requestedRadius={}",
                    state.id, state.grantedCenter, state.grantedRadius, state.requestedCenter, state.requestedRadius);
            state.lastGrantLog = now;
        }
    }

    private static void accept(S2CRemoteUnavailablePacket packet) {
        SubscriptionState state = SUBSCRIPTIONS.get(packet.subscription());
        if (state == null) {
            return;
        }
        boolean terminal = packet.reason() != S2CRemoteUnavailablePacket.Reason.BUDGET;
        fail(state, packet.reason(), terminal);
    }

    private static void accept(S2CRemoteChunkPacket packet) {
        enqueue(packet.subscription(), new ChunkUpdate(packet.sequence(),
                new ChunkPos(packet.data().getX(), packet.data().getZ()), packet.data(), packet.encodedBytes()));
    }

    private static void accept(S2CRemoteUnloadPacket packet) {
        enqueue(packet.subscription(), new UnloadUpdate(packet.sequence(), new ChunkPos(packet.chunkX(), packet.chunkZ())));
    }

    private static void accept(S2CRemoteEntitiesPacket packet) {
        enqueue(packet.subscription(), new EntityUpdates(packet.sequence(), packet.messages()));
    }

    private static void accept(S2CRemoteBlockEntitiesPacket packet) {
        enqueue(packet.subscription(), new BlockEntityUpdates(packet.sequence(), packet.updates()));
    }

    private static void accept(S2CRemoteBlockUpdatesPacket packet) {
        enqueue(packet.subscription(), new BlockUpdate(
                packet.sequence(),
                packet.sectionX(),
                packet.sectionY(),
                packet.sectionZ(),
                packet.updates()
        ));
    }

    private static void accept(S2CRemoteLightPacket packet) {
        enqueue(packet.subscription(), new LightUpdate(
                packet.sequence(),
                new ChunkPos(packet.data().getX(), packet.data().getZ()),
                packet.data().getLightData()
        ));
    }

    private static void accept(S2CRemoteWorldStatePacket packet) {
        enqueue(packet.subscription(), new WorldStateUpdate(packet.sequence(), packet.state()));
    }

    private static void enqueue(RemoteSubscriptionId id, SceneUpdate command) {
        SubscriptionState state = SUBSCRIPTIONS.get(id);
        if (state == null || state.references < 1 || state.terminalFailure) {
            return;
        }
        if (command.sequence() <= state.highestSequence) {
            state.stalePackets++;
            return;
        }
        state.highestSequence = command.sequence();
        if (state.pendingCount >= MAX_PENDING_PER_SUBSCRIPTION
                || PENDING.size() >= MAX_PENDING_GLOBAL
                || state.pendingBytes + command.bytes() > MAX_PENDING_BYTES_PER_SUBSCRIPTION
                || pendingBytes + command.bytes() > MAX_PENDING_BYTES_GLOBAL) {
            fail(state, S2CRemoteUnavailablePacket.Reason.BUDGET, true);
            return;
        }
        if (command instanceof ChunkControl control) {
            state.chunkControls.put(control.chunk().toLong(), new Control(command.sequence(), control.loads()));
        }
        PENDING.addLast(new PendingApply(state, command));
        state.pendingCount++;
        state.pendingBytes += command.bytes();
        pendingBytes += command.bytes();
    }

    private static void updateState(SubscriptionState state, ChunkPos cameraCenter, int radius, boolean force) {
        boolean moved = !state.requestedCenter.equals(cameraCenter);
        boolean radiusChanged = state.requestedRadius != radius;
        if (moved) {
            state.requestedCenter = cameraCenter;
        }
        state.requestedRadius = radius;
        if (!force && !moved && !radiusChanged) {
            return;
        }
        if (state.subscribed && !state.terminalFailure) {
            send(new C2SRemoteUpdatePacket(state.id, state.requestedCenter, radius));
            state.lastSendTick = clientTicks;
        }
    }

    private static void fail(SubscriptionState state, S2CRemoteUnavailablePacket.Reason reason, boolean terminal) {
        LOGGER.warn("[GLASS projector] client unavailable id={} reason={} terminal={} pending={} pendingBytes={} globalPending={} globalPendingBytes={} lastSequence={} grantedCenter={} grantedRadius={}",
                state.id, reason, terminal, state.pendingCount, state.pendingBytes, PENDING.size(), pendingBytes,
                state.highestSequence, state.grantedCenter, state.grantedRadius);
        if (terminal && state.subscribed) {
            send(new C2SRemoteUnsubscribePacket(state.id));
        }
        removePending(state);
        state.grantedCenter = null;
        state.grantedRadius = 0;
        state.unavailable = reason;
        state.terminalFailure = terminal;
        if (terminal) {
            state.subscribed = false;
        }
        if (state.scene != null) {
            try {
                state.scene.releaseSubscription(state.id);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to release remote scene subscription {}", state.id, exception);
            }
        }
    }

    private static void failScene(RemoteClientScene scene, S2CRemoteUnavailablePacket.Reason reason) {
        List<SubscriptionState> affected = SUBSCRIPTIONS.values().stream()
                .filter(state -> state.scene == scene)
                .toList();
        for (SubscriptionState state : affected) {
            fail(state, reason, true);
        }
    }

    private static void detachState(SubscriptionState state) {
        if (state.scene != null) {
            detachStateScene(state);
        }
        state.renderers.clear();
    }

    private static void detachStateScene(SubscriptionState state) {
        RemoteClientScene scene = state.scene;
        state.scene = null;
        try {
            scene.releaseSubscription(state.id);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release detached remote scene subscription {}", state.id, exception);
        }
        for (Map.Entry<LevelRenderer, Integer> renderer : state.renderers.entrySet()) {
            for (int i = 0; i < renderer.getValue(); i++) {
                scene.detachRenderer(state.id, renderer.getKey());
            }
        }
        if (SUBSCRIPTIONS.values().stream().noneMatch(candidate -> candidate.scene == scene)) {
            SCENES.remove(scene.source().dimension(), scene);
            try {
                scene.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close remote scene {}", scene.source().key(), exception);
            }
        }
    }

    private static void removePending(SubscriptionState state) {
        Iterator<PendingApply> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingApply pending = iterator.next();
            if (pending.state != state) {
                continue;
            }
            iterator.remove();
            accountDequeued(state, pending.command.bytes());
        }
    }

    private static void accountDequeued(SubscriptionState state, long bytes) {
        state.pendingCount = Math.max(0, state.pendingCount - 1);
        state.pendingBytes = Math.max(0L, state.pendingBytes - bytes);
        pendingBytes = Math.max(0L, pendingBytes - bytes);
    }

    private static void clear(boolean notifyServer) {
        if (notifyServer) {
            for (SubscriptionState state : SUBSCRIPTIONS.values()) {
                if (state.subscribed) {
                    send(new C2SRemoteUnsubscribePacket(state.id));
                }
            }
        }
        PENDING.clear();
        ACKNOWLEDGEMENTS.clear();
        pendingBytes = 0L;
        for (RemoteClientScene scene : new ArrayList<>(SCENES.values())) {
            try {
                scene.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close remote scene {}", scene.source().key(), exception);
            }
        }
        SCENES.clear();
        SUBSCRIPTIONS.clear();
    }

    private static boolean send(CustomPacketPayload packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return false;
        }
        try {
            if (!ClientPlayNetworking.canSend(packet.type())) {
                return false;
            }
            ClientPlayNetworking.send(packet);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static @Nullable SubscriptionState state(RemoteSceneHandle handle) {
        if (handle == null || handle.released()) {
            return null;
        }
        return state(handle.subscription(), handle.localToken());
    }

    private static @Nullable SubscriptionState state(RemoteSubscriptionId id, long localToken) {
        SubscriptionState state = SUBSCRIPTIONS.get(id);
        return state != null && state.localToken == localToken ? state : null;
    }

    private static long lightBytes(ClientboundLightUpdatePacketData data) {
        return 128L + (long) (data.getSkyYMask().cardinality() + data.getBlockYMask().cardinality()) * 2048;
    }

    private static final class SubscriptionState {
        private final RemoteSubscriptionId id;
        private final long localToken;
        private final IdentityHashMap<LevelRenderer, Integer> renderers = new IdentityHashMap<>();
        private final Map<Long, Control> chunkControls = new LinkedHashMap<>();
        private int references = 1;
        private ChunkPos requestedCenter;
        private int requestedRadius;
        private ChunkPos grantedCenter;
        private int grantedRadius;
        private RemoteClientScene scene;
        private S2CRemoteUnavailablePacket.Reason unavailable;
        private long highestSequence = -1L;
        private long lastSendTick;
        private long lastGrantLog;
        private long stalePackets;
        private long discardedApplies;
        private int pendingCount;
        private long pendingBytes;
        private boolean subscribed;
        private boolean terminalFailure;

        private SubscriptionState(RemoteSubscriptionId id, long localToken, ChunkPos requestedCenter, int requestedRadius) {
            this.id = id;
            this.localToken = localToken;
            this.requestedCenter = requestedCenter;
            this.requestedRadius = requestedRadius;
        }

        private boolean withinGrant(ChunkPos pos, int padding) {
            return grantedCenter != null
                    && Math.abs(pos.x - grantedCenter.x) <= grantedRadius + padding
                    && Math.abs(pos.z - grantedCenter.z) <= grantedRadius + padding;
        }

        private boolean controlCurrent(ChunkControl command) {
            Control control = chunkControls.get(command.chunk().toLong());
            return control != null && control.sequence == command.sequence() && control.load == command.loads();
        }

        private boolean updateCurrent(SceneUpdate command, ChunkPos chunk) {
            Control control = chunkControls.get(chunk.toLong());
            return control == null || command.sequence() >= control.sequence;
        }
    }

    private record Control(long sequence, boolean load) {
    }

    private record PendingApply(SubscriptionState state, SceneUpdate command) {
    }

    private sealed interface SceneUpdate permits ChunkControl, BlockUpdate, LightUpdate, WorldStateUpdate, EntityUpdates, BlockEntityUpdates {
        long sequence();

        long bytes();

        void apply(SubscriptionState state);
    }

    private sealed interface ChunkControl extends SceneUpdate permits ChunkUpdate, UnloadUpdate {
        ChunkPos chunk();

        boolean loads();
    }

    private record ChunkUpdate(long sequence, ChunkPos chunk, ClientboundLevelChunkWithLightPacket data, int encodedBytes) implements ChunkControl {
        @Override
        public long bytes() {
            return 64L + encodedBytes;
        }

        @Override
        public boolean loads() {
            return true;
        }

        @Override
        public void apply(SubscriptionState state) {
            if (state.controlCurrent(this) && state.withinGrant(chunk, 1)) {
                state.scene.applyChunk(state.id, data);
                ACKNOWLEDGEMENTS.add(new C2SRemoteChunkAckPacket(state.id, sequence, chunk));
            }
        }
    }

    private record UnloadUpdate(long sequence, ChunkPos chunk) implements ChunkControl {
        @Override
        public long bytes() {
            return 32L;
        }

        @Override
        public boolean loads() {
            return false;
        }

        @Override
        public void apply(SubscriptionState state) {
            if (state.controlCurrent(this) && !state.withinGrant(chunk, 1)) {
                state.scene.unloadChunk(state.id, chunk);
            }
        }
    }

    private record BlockUpdate(
            long sequence,
            int sectionX,
            int sectionY,
            int sectionZ,
            ClientboundSectionBlocksUpdatePacket updates
    ) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + S2CRemoteBlockUpdatesPacket.MAX_UPDATES * 16L;
        }

        @Override
        public void apply(SubscriptionState state) {
            ChunkPos chunk = new ChunkPos(sectionX, sectionZ);
            if (state.updateCurrent(this, chunk)) {
                state.scene.applyBlockUpdates(state.id, sectionX, sectionY, sectionZ, updates);
            }
        }
    }

    private record LightUpdate(long sequence, ChunkPos chunk, ClientboundLightUpdatePacketData light) implements SceneUpdate {
        @Override
        public long bytes() {
            return lightBytes(light);
        }

        @Override
        public void apply(SubscriptionState state) {
            if (state.updateCurrent(this, chunk)) {
                state.scene.applyLight(state.id, chunk, light);
            }
        }
    }

    private record WorldStateUpdate(long sequence, RemoteWorldState worldState) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L;
        }

        @Override
        public void apply(SubscriptionState state) {
            state.scene.applyWorldState(worldState);
        }
    }

    private record EntityUpdates(long sequence, List<S2CRemoteEntitiesPacket.EntityMessage> messages) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + messages.size() * 1024L;
        }

        @Override
        public void apply(SubscriptionState state) {
            state.scene.applyEntities(state.id, messages);
        }
    }

    private record BlockEntityUpdates(long sequence, List<ClientboundBlockEntityDataPacket> updates) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + updates.size() * 4096L;
        }

        @Override
        public void apply(SubscriptionState state) {
            state.scene.applyBlockEntities(state.id, updates.stream()
                    .filter(update -> state.updateCurrent(this, new ChunkPos(update.getPos())))
                    .toList());
        }
    }
}
