package dev.imb11.client.remote;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionSections;
import dev.imb11.mixins.BufferSourceAccessor;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.LightTextureAccessor;
import dev.imb11.mixins.ViewAreaInvoker;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteWorldState;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import dev.imb11.sync.remote.S2CRemoteSubscriptionPacket;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import dev.imb11.projection.ProjectionChunkRegion;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RemoteClientScene implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STORAGE_VIEW_DISTANCE = 2;
    private final boolean mainWorld;
    private final ProjectionSource source;
    private final Minecraft minecraft;
    private final ResourceKey<DimensionType> dimensionType;
    private final boolean hardcore;
    private final boolean debug;
    private final boolean flat;
    private final long seedHash;
    private final ClientLevel.ClientLevelData levelData;
    private final RenderBuffers sinkRenderBuffers;
    private final RemoteSceneSinkRenderer sinkRenderer;
    private final ClientLevel level;
    private final ClientChunkCache chunkCache;
    private final LevelLightEngine lightEngine;
    private final LightTexture lightTexture;
    private final RemoteSceneEntities entities;
    private final Map<Long, LinkedHashSet<RemoteSubscriptionId>> chunkOwners = new LinkedHashMap<>();
    private final Map<RemoteSubscriptionId, IdentityHashMap<LevelRenderer, Integer>> renderers = new LinkedHashMap<>();
    private final LinkedHashSet<Long> deferredLightSections = new LinkedHashSet<>();
    private final Map<LevelRenderer, Set<Long>> pendingDirtySections = new IdentityHashMap<>();
    private RemoteWorldState state;
    private boolean applying;
    private boolean closed;

    private RemoteClientScene(
            ProjectionSource source,
            Minecraft minecraft,
            ResourceKey<DimensionType> dimensionType,
            boolean hardcore,
            boolean debug,
            boolean flat,
            long seedHash,
            ClientLevel.ClientLevelData levelData,
            RenderBuffers sinkRenderBuffers,
            RemoteSceneSinkRenderer sinkRenderer,
            ClientLevel level,
            LightTexture lightTexture,
            RemoteWorldState state
    ) {
        this.source = source;
        this.minecraft = minecraft;
        this.dimensionType = dimensionType;
        this.hardcore = hardcore;
        this.debug = debug;
        this.flat = flat;
        this.seedHash = seedHash;
        this.levelData = levelData;
        this.sinkRenderBuffers = sinkRenderBuffers;
        this.sinkRenderer = sinkRenderer;
        this.level = level;
        this.mainWorld = level == minecraft.level;
        this.chunkCache = level.getChunkSource();
        this.lightEngine = chunkCache.getLightEngine();
        this.lightTexture = lightTexture;
        this.entities = new RemoteSceneEntities(level);
        this.state = state;
    }

    static RemoteClientScene create(Minecraft minecraft, S2CRemoteSubscriptionPacket packet) {
        ClientPacketListener connection = Objects.requireNonNull(minecraft.getConnection());
        Holder<DimensionType> dimensionType = connection.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(packet.dimensionType());
        if (minecraft.level != null && minecraft.level.dimension().equals(packet.subscription().source().dimension())) {
            return new RemoteClientScene(packet.subscription().source(), minecraft, packet.dimensionType(),
                    packet.hardcore(), packet.debug(), packet.flat(), packet.seedHash(), null, null, null,
                    minecraft.level, minecraft.gameRenderer.lightTexture(), packet.state());
        }
        RenderBuffers renderBuffers = new RenderBuffers(1);
        RemoteSceneSinkRenderer renderer = null;
        LightTexture lightTexture = null;
        try {
            renderer = new RemoteSceneSinkRenderer(minecraft, renderBuffers);
            ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(packet.difficulty(), packet.hardcore(), packet.flat());
            ClientLevel level = new RemoteSceneLevel(
                    connection,
                    levelData,
                    packet.subscription().source().dimension(),
                    dimensionType,
                    STORAGE_VIEW_DISTANCE,
                    minecraft::getProfiler,
                    renderer,
                    packet.debug(),
                    packet.seedHash()
            );
            ChunkPos sourceChunk = new ChunkPos(packet.subscription().source().pos());
            level.getChunkSource().updateViewCenter(sourceChunk.x, sourceChunk.z);
            level.getChunkSource().updateViewRadius(STORAGE_VIEW_DISTANCE);
            lightTexture = new LightTexture(minecraft.gameRenderer, minecraft);
            RemoteClientScene scene = new RemoteClientScene(
                    packet.subscription().source(),
                    minecraft,
                    packet.dimensionType(),
                    packet.hardcore(),
                    packet.debug(),
                    packet.flat(),
                    packet.seedHash(),
                    levelData,
                    renderBuffers,
                    renderer,
                    level,
                    lightTexture,
                    packet.state()
            );
            renderer.bind(scene);
            scene.applyWorldState(packet.state());
            ProjectionRenderContext.registerRemoteLightUpdates(scene.chunkCache, scene::onLightUpdate);
            return scene;
        } catch (RuntimeException exception) {
            if (lightTexture != null) {
                LightTexture failedLightTexture = lightTexture;
                cleanupCreation("light texture", () -> closeLightTexture(minecraft, failedLightTexture));
            }
            if (renderer != null) {
                RemoteSceneSinkRenderer failedRenderer = renderer;
                cleanupCreation("renderer global buffers", () -> closeGlobalBuffers(failedRenderer));
                cleanupCreation("sink renderer", failedRenderer::close);
            }
            cleanupCreation("render buffers", () -> closeRenderBuffers(renderBuffers));
            throw exception;
        }
    }

    ProjectionSource source() {
        return source;
    }

    ClientLevel level() {
        return level;
    }

    LightTexture lightTexture() {
        return lightTexture;
    }

    RemoteWorldState worldState() {
        return new RemoteWorldState(
                level.getGameTime(),
                level.getDayTime(),
                state.tickDayTime(),
                level.getRainLevel(1.0F),
                level.getThunderLevel(1.0F),
                level.getSkyFlashTime()
        );
    }

    boolean matches(S2CRemoteSubscriptionPacket packet) {
        return source.dimension().equals(packet.subscription().source().dimension())
                && dimensionType.equals(packet.dimensionType())
                && hardcore == packet.hardcore()
                && debug == packet.debug()
                && flat == packet.flat()
                && seedHash == packet.seedHash();
    }

    void attachRenderer(RemoteSubscriptionId subscription, LevelRenderer renderer) {
        if (closed) {
            return;
        }
        boolean alreadyAttached = renderers.values().stream().anyMatch(attached -> attached.containsKey(renderer));
        IdentityHashMap<LevelRenderer, Integer> attached = renderers.computeIfAbsent(subscription, ignored -> new IdentityHashMap<>());
        int count = attached.getOrDefault(renderer, 0);
        attached.put(renderer, count + 1);
        if (count == 0 && !alreadyAttached && rendererActive(renderer)) {
            for (Map.Entry<Long, LinkedHashSet<RemoteSubscriptionId>> entry : chunkOwners.entrySet()) {
                if (entry.getValue().contains(subscription)) {
                    notifyRendererChunk(renderer, new ChunkPos(entry.getKey()));
                }
            }
        }
    }

    void detachRenderer(RemoteSubscriptionId subscription, LevelRenderer renderer) {
        IdentityHashMap<LevelRenderer, Integer> attached = renderers.get(subscription);
        if (attached == null) {
            return;
        }
        Integer count = attached.get(renderer);
        if (count == null) {
            return;
        }
        if (count == 1) {
            attached.remove(renderer);
        } else {
            attached.put(renderer, count - 1);
        }
        if (attached.isEmpty()) {
            renderers.remove(subscription);
        }
    }

    void setRegion(RemoteSubscriptionId subscription, ChunkPos center, int radius) {
        Set<ChunkPos> next = new ProjectionChunkRegion(center, radius).withNeighbors().chunks();
        for (ChunkPos pos : next) {
            LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.computeIfAbsent(pos.toLong(), ignored -> new LinkedHashSet<>());
            if (owners.isEmpty()) {
                ProjectionChunkStorage.of(level).retain(pos);
            }
            owners.add(subscription);
        }
        for (long packed : List.copyOf(chunkOwners.keySet())) {
            ChunkPos pos = new ChunkPos(packed);
            if (!next.contains(pos)) {
                unloadChunk(subscription, pos);
            }
        }
    }

    void applyChunk(RemoteSubscriptionId subscription, ClientboundLevelChunkWithLightPacket packet) {
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        if (closed || !ownedBy(subscription, pos)) {
            return;
        }
        ProjectionChunkStorage storage = ProjectionChunkStorage.of(level);
        if (storage.vanilla(pos)) {
            return;
        }
        beginApply();
        boolean committed = false;
        try {
            var data = packet.getChunkData();
            FriendlyByteBuf buffer = data.getReadBuffer();
            LevelChunk chunk;
            try {
                chunk = storage.replace(level, pos.x, pos.z, buffer, data.getHeightmaps(),
                        data.getBlockEntitiesTagsConsumer(pos.x, pos.z), false);
            } finally {
                buffer.release();
            }
            applyLightLayers(pos, packet.getLightData());
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                lightEngine.updateSectionStatus(SectionPos.of(pos, level.getSectionYFromSectionIndex(index)), sections[index].hasOnlyAir());
            }
            chunk.setLightCorrect(true);
            committed = true;
        } finally {
            finishApply(committed);
        }
        if (committed) {
            notifyOwnersChunk(pos);
        }
    }

    void unloadChunk(RemoteSubscriptionId subscription, ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.toLong());
        if (owners == null || !owners.contains(subscription)) {
            return;
        }
        if (owners.size() == 1) {
            if (!ProjectionChunkStorage.of(level).vanilla(pos)) {
                resetOwnersChunk(pos);
            }
            chunkOwners.remove(pos.toLong());
            ProjectionChunkStorage.of(level).release(level, pos);
        } else {
            owners.remove(subscription);
        }
    }

    void applyBlockUpdates(
            RemoteSubscriptionId subscription,
            int sectionX,
            int sectionY,
            int sectionZ,
            ClientboundSectionBlocksUpdatePacket updates
    ) {
        ChunkPos pos = new ChunkPos(sectionX, sectionZ);
        if (closed || !ownedBy(subscription, pos) || ProjectionChunkStorage.of(level).vanilla(pos)
                || sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            return;
        }
        updates.runUpdates((block, state) -> {
            if (SectionPos.blockToSectionCoord(block.getX()) == sectionX
                    && SectionPos.blockToSectionCoord(block.getY()) == sectionY
                    && SectionPos.blockToSectionCoord(block.getZ()) == sectionZ) {
                level.setServerVerifiedBlockState(block, state, 19);
            }
        });
    }

    void applyLight(RemoteSubscriptionId subscription, ChunkPos pos, ClientboundLightUpdatePacketData light) {
        if (!ownedBy(subscription, pos) || ProjectionChunkStorage.of(level).vanilla(pos)) {
            return;
        }
        beginApply();
        boolean committed = false;
        try {
            applyLightLayers(pos, light);
            committed = true;
        } finally {
            finishApply(committed);
        }
    }

    void applyWorldState(RemoteWorldState state) {
        if (closed) {
            return;
        }
        this.state = state;
        if (mainWorld) {
            return;
        }
        level.setGameTime(state.gameTime());
        level.setDayTime(state.dayTime());
        level.setRainLevel(state.rainLevel());
        level.setThunderLevel(state.thunderLevel());
        level.setSkyFlashTime(state.skyFlashTime());
        levelData.setRaining(state.rainLevel() > 0.0F);
        level.updateSkyBrightness();
        lightTexture.tick();
    }

    void advanceClock() {
        if (closed || mainWorld || state == null) {
            return;
        }
        level.setGameTime(level.getGameTime() + 1L);
        if (state.tickDayTime()) {
            level.setDayTime(level.getDayTime() + 1L);
        }
        if (level.getSkyFlashTime() > 0) {
            level.setSkyFlashTime(level.getSkyFlashTime() - 1);
        }
        level.updateSkyBrightness();
        lightTexture.tick();
        entities.tick();
    }

    void onVanillaEntityAdded(net.minecraft.world.entity.Entity entity) {
        entities.onVanillaAdded(entity);
    }

    boolean onVanillaEntityRemoved(int id) {
        return entities.onVanillaRemoved(id);
    }

    void applyEntities(RemoteSubscriptionId subscription, List<S2CRemoteEntitiesPacket.EntityMessage> messages) {
        if (!closed) {
            entities.apply(subscription, messages);
        }
    }

    void applyBlockEntities(RemoteSubscriptionId subscription, List<ClientboundBlockEntityDataPacket> updates) {
        for (ClientboundBlockEntityDataPacket update : updates) {
            BlockPos pos = update.getPos();
            if (closed || !ownedBy(subscription, new ChunkPos(pos)) || ProjectionChunkStorage.of(level).vanilla(new ChunkPos(pos))) {
                continue;
            }
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null && blockEntity.getType() == update.getType()) {
                blockEntity.loadWithComponents(update.getTag(), level.registryAccess());
                dirtyOwnersSection(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                        SectionPos.blockToSectionCoord(pos.getZ()), false);
            }
        }
    }

    boolean isReady(ChunkPos cameraCenter) {
        return isReady(cameraCenter, 1);
    }

    boolean isReady(ChunkPos center, int radius) {
        if (closed) {
            return false;
        }
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                ChunkPos pos = new ChunkPos(center.x + offsetX, center.z + offsetZ);
                LevelChunk chunk = chunkCache.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
                if (chunk == null
                        || !chunkOwners.containsKey(pos.toLong())
                        || !lightEngine.lightOnInSection(SectionPos.of(pos, level.getMinSection()))) {
                    return false;
                }
            }
        }
        return true;
    }

    String chunkDiagnostics(ChunkPos center, int radius) {
        int missing = 0;
        int unlit = 0;
        ChunkPos firstMissing = null;
        ChunkPos firstUnlit = null;
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                ChunkPos pos = new ChunkPos(center.x + offsetX, center.z + offsetZ);
                LevelChunk chunk = chunkCache.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
                if (chunk == null || !chunkOwners.containsKey(pos.toLong())) {
                    missing++;
                    if (firstMissing == null) {
                        firstMissing = pos;
                    }
                } else if (!lightEngine.lightOnInSection(SectionPos.of(pos, level.getMinSection()))) {
                    unlit++;
                    if (firstUnlit == null) {
                        firstUnlit = pos;
                    }
                }
            }
        }
        return "storage=" + (mainWorld ? "player-world" : "remote-dimension")
                + " sharedSubscriptions=" + chunkOwners.values().stream().flatMap(Set::stream).distinct().count()
                + " closed=" + closed + " expectedChunks=" + (radius * 2 + 1) * (radius * 2 + 1)
                + " missingChunks=" + missing + " unlitChunks=" + unlit
                + " firstMissingChunk=" + firstMissing + " firstUnlitChunk=" + firstUnlit;
    }

    void releaseSubscription(RemoteSubscriptionId subscription) {
        entities.release(subscription);
        List<ChunkPos> owned = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashSet<RemoteSubscriptionId>> entry : chunkOwners.entrySet()) {
            if (entry.getValue().contains(subscription)) {
                owned.add(new ChunkPos(entry.getKey()));
            }
        }
        for (ChunkPos pos : owned) {
            unloadChunk(subscription, pos);
        }
    }

    void dirtyBlock(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!applying && !closed) {
            dirtyBlocks(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    void dirtyBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (applying || closed) {
            return;
        }
        int minSectionX = SectionPos.blockToSectionCoord(minX - 1);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - 1);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + 1);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + 1);
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    dirtyOwnersSection(sectionX, sectionY, sectionZ, false);
                }
            }
        }
    }

    void dirtySectionWithNeighbors(int sectionX, int sectionY, int sectionZ) {
        if (!applying && !closed) {
            dirtyOwnersSection(sectionX, sectionY, sectionZ, true);
        }
    }

    void dirtySection(int sectionX, int sectionY, int sectionZ) {
        if (!applying && !closed) {
            dirtyOwnersSection(sectionX, sectionY, sectionZ, false);
        }
    }

    void chunkLoaded(ChunkPos pos) {
        if (!applying && !closed) {
            notifyOwnersChunk(pos);
        }
    }

    private void applyLightLayers(ChunkPos pos, ClientboundLightUpdatePacketData light) {
        int sectionCount = lightEngine.getLightSectionCount();
        BitSet skyMask = light.getSkyYMask();
        BitSet emptySkyMask = light.getEmptySkyYMask();
        BitSet blockMask = light.getBlockYMask();
        BitSet emptyBlockMask = light.getEmptyBlockYMask();
        if (skyMask.length() > sectionCount
                || emptySkyMask.length() > sectionCount
                || blockMask.length() > sectionCount
                || emptyBlockMask.length() > sectionCount) {
            throw new IllegalArgumentException("remote light section count mismatch");
        }
        applyLightLayer(pos, LightLayer.SKY, skyMask, emptySkyMask, light.getSkyUpdates());
        applyLightLayer(pos, LightLayer.BLOCK, blockMask, emptyBlockMask, light.getBlockUpdates());
        lightEngine.setLightEnabled(pos, true);
    }

    private void applyLightLayer(ChunkPos pos, LightLayer layer, BitSet dataMask, BitSet emptyMask, List<byte[]> updates) {
        Iterator<byte[]> updateIterator = updates.iterator();
        for (int index = 0; index < lightEngine.getLightSectionCount(); index++) {
            boolean hasData = dataMask.get(index);
            if (!hasData && !emptyMask.get(index)) {
                continue;
            }
            DataLayer data = hasData ? new DataLayer(updateIterator.next()) : new DataLayer();
            int sectionY = lightEngine.getMinLightSection() + index;
            lightEngine.queueSectionData(layer, SectionPos.of(pos, sectionY), data);
        }
        if (updateIterator.hasNext()) {
            throw new IllegalArgumentException("remote light update count mismatch");
        }
    }

    private void beginApply() {
        applying = true;
        deferredLightSections.clear();
    }

    private void finishApply(boolean committed) {
        applying = false;
        if (!committed) {
            deferredLightSections.clear();
            return;
        }
        for (long section : deferredLightSections) {
            SectionPos pos = SectionPos.of(section);
            dirtyOwnersSection(pos.x(), pos.y(), pos.z(), true);
        }
        deferredLightSections.clear();
    }

    private void onLightUpdate(LightLayer layer, SectionPos sectionPos) {
        if (closed) {
            return;
        }
        if (applying) {
            deferredLightSections.add(sectionPos.asLong());
        } else {
            dirtyOwnersSection(sectionPos.x(), sectionPos.y(), sectionPos.z(), true);
        }
    }

    private boolean ownedBy(RemoteSubscriptionId subscription, ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.toLong());
        return owners != null && owners.contains(subscription);
    }

    private void notifyOwnersChunk(ChunkPos pos) {
        for (LevelRenderer renderer : ownerRenderers(pos)) {
            notifyRendererChunk(renderer, pos);
        }
    }

    private void notifyRendererChunk(LevelRenderer renderer, ChunkPos pos) {
        renderer.onChunkLoaded(pos);
        queueDirtyChunk(renderer, pos, true);
    }

    private void queueDirtyChunk(LevelRenderer renderer, ChunkPos pos, boolean neighbors) {
        int padding = neighbors ? 1 : 0;
        for (int x = pos.x - padding; x <= pos.x + padding; x++) {
            for (int z = pos.z - padding; z <= pos.z + padding; z++) {
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
                    queueDirtySection(renderer, x, y, z, false);
                }
            }
        }
    }

    private void dirtyOwnersSection(int sectionX, int sectionY, int sectionZ, boolean neighbors) {
        ChunkPos chunk = new ChunkPos(sectionX, sectionZ);
        for (LevelRenderer renderer : ownerRenderers(chunk)) {
            queueDirtySection(renderer, sectionX, sectionY, sectionZ, neighbors);
        }
    }

    private void queueDirtySection(LevelRenderer renderer, int sectionX, int sectionY, int sectionZ, boolean neighbors) {
        int padding = neighbors ? 1 : 0;
        for (int x = sectionX - padding; x <= sectionX + padding; x++) {
            for (int y = sectionY - padding; y <= sectionY + padding; y++) {
                for (int z = sectionZ - padding; z <= sectionZ + padding; z++) {
                    if (ProjectionSections.find(renderer, x, y, z) != null) {
                        pendingDirtySections.computeIfAbsent(renderer, ignored -> new LinkedHashSet<>()).add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    void flushUpdates() {
        if (closed) {
            return;
        }
        if (!mainWorld && lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
        }
        for (Map.Entry<LevelRenderer, Set<Long>> entry : pendingDirtySections.entrySet()) {
            for (long packed : entry.getValue()) {
                SectionPos pos = SectionPos.of(packed);
                SectionRenderDispatcher.RenderSection section = ProjectionSections.find(entry.getKey(), pos.x(), pos.y(), pos.z());
                if (section != null) {
                    section.setDirty(false);
                }
            }
        }
        pendingDirtySections.clear();
    }

    private Set<LevelRenderer> ownerRenderers(ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.toLong());
        if (owners == null || owners.isEmpty()) {
            return Set.of();
        }
        Set<LevelRenderer> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RemoteSubscriptionId owner : owners) {
            IdentityHashMap<LevelRenderer, Integer> attached = renderers.get(owner);
            if (attached == null) {
                continue;
            }
            for (LevelRenderer renderer : attached.keySet()) {
                if (rendererActive(renderer)) {
                    result.add(renderer);
                }
            }
        }
        return result;
    }

    private static boolean rendererActive(LevelRenderer renderer) {
        return ((LevelRendererBufferAccessor) renderer).glass$getViewArea() != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pendingDirtySections.clear();
        cleanup("entities", entities::clear);
        cleanup("chunk and light storage", () -> {
            for (long packed : List.copyOf(chunkOwners.keySet())) {
                ProjectionChunkStorage.of(level).release(level, new ChunkPos(packed));
            }
        });
        if (!mainWorld) {
            ProjectionRenderContext.unregisterRemoteLightUpdates(chunkCache);
            try {
                level.close();
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to close remote client level {}", source.key(), exception);
            }
        }
        chunkOwners.clear();
        renderers.clear();
        deferredLightSections.clear();
        if (mainWorld) {
            return;
        }
        cleanup("tint caches", level::clearTintCaches);
        cleanup("light texture", () -> closeLightTexture(minecraft, lightTexture));
        cleanup("renderer global buffers", () -> closeGlobalBuffers(sinkRenderer));
        cleanup("sink renderer", sinkRenderer::close);
        cleanup("render buffers", () -> closeRenderBuffers(sinkRenderBuffers));
    }

    private static void closeGlobalBuffers(LevelRenderer renderer) {
        LevelRendererBufferAccessor buffers = (LevelRendererBufferAccessor) renderer;
        closeBuffer(buffers.glass$getStarBuffer());
        closeBuffer(buffers.glass$getSkyBuffer());
        closeBuffer(buffers.glass$getDarkBuffer());
        closeBuffer(buffers.glass$getCloudBuffer());
    }

    private static void closeBuffer(VertexBuffer buffer) {
        if (buffer != null) {
            try {
                buffer.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close remote scene vertex buffer", exception);
            }
        }
    }

    private void resetOwnersChunk(ChunkPos pos) {
        for (LevelRenderer renderer : ownerRenderers(pos)) {
            ViewArea viewArea = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
            if (viewArea == null) {
                continue;
            }
            ViewAreaInvoker invoker = (ViewAreaInvoker) viewArea;
            int originX = SectionPos.sectionToBlockCoord(pos.x);
            int originZ = SectionPos.sectionToBlockCoord(pos.z);
            boolean reset = false;
            for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
                int originY = SectionPos.sectionToBlockCoord(sectionY);
                BlockPos origin = new BlockPos(originX, originY, originZ);
                SectionRenderDispatcher.RenderSection section = invoker.glass$getRenderSectionAt(origin);
                if (section != null && section.getOrigin().equals(origin)) {
                    section.setOrigin(originX, originY, originZ);
                    reset = true;
                }
            }
            if (reset) {
                renderer.needsUpdate();
            }
        }
    }

    private static void closeLightTexture(Minecraft minecraft, LightTexture lightTexture) {
        try {
            minecraft.getTextureManager().release(((LightTextureAccessor) lightTexture).glass$getLightTextureLocation());
        } finally {
            lightTexture.close();
        }
    }

    private void cleanup(String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close remote scene {} {}", source.key(), resource, exception);
        }
    }

    private static void cleanupCreation(String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close incomplete remote scene {}", resource, exception);
        }
    }

    private static void closeRenderBuffers(RenderBuffers renderBuffers) {
        Set<ByteBufferBuilder> builders = Collections.newSetFromMap(new IdentityHashMap<>());
        SectionBufferBuilderPack fixed = renderBuffers.fixedBufferPack();
        for (RenderType renderType : RenderType.chunkBufferLayers()) {
            builders.add(fixed.buffer(renderType));
        }
        addBufferSourceBuilders(builders, renderBuffers.bufferSource());
        addBufferSourceBuilders(builders, renderBuffers.crumblingBufferSource());
        builders.forEach(ByteBufferBuilder::close);
        SectionBufferBuilderPool pool = renderBuffers.sectionBufferPool();
        SectionBufferBuilderPack pooled;
        while ((pooled = pool.acquire()) != null) {
            pooled.close();
        }
    }

    private static void addBufferSourceBuilders(Set<ByteBufferBuilder> builders, MultiBufferSource.BufferSource source) {
        BufferSourceAccessor accessor = (BufferSourceAccessor) source;
        builders.add(accessor.glass$getSharedBuffer());
        builders.addAll(accessor.glass$getFixedBuffers().values());
    }
}
