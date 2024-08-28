package dev.imb11.sync;

import dev.imb11.Glass;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public enum GPackets {
    TERMINAL_CHANNEL_CHANGED(null, GPackets::onTerminalChannelChanged, null),
    REMOVE_LINKED_CHANNEL(null, GPackets::onRemoveLinkedChannel, null),
    POPULATE_DEFAULT_CHANNEL(null, GPackets::onPopulateDefaultChannel, null),

    DELETE_CHANNEL(null, GPackets::onDeleteChannel, null),
    CREATE_CHANNEL(null, GPackets::onCreateChannel, null),

    PROJECTOR_CHANNEL_CHANGED(null, GPackets::onProjectorChannelChanged, null);

    public final Identifier ID;
    private final EnvType env;
    private final ServerPlayNetworking.PlayChannelHandler serverAction;
    private final ClientPlayNetworking.PlayChannelHandler clientAction;

    GPackets(@Nullable EnvType envType, @Nullable ServerPlayNetworking.PlayChannelHandler serverAction, @Nullable ClientPlayNetworking.PlayChannelHandler clientAction) {
        this.ID = new Identifier("glass", this.name().toLowerCase());
        this.env = envType;
        this.serverAction = serverAction;
        this.clientAction = clientAction;
    }

    public static void initialize() {
        for (GPackets packet : values()) {
            packet.register();
        }
    }

    public void register() {
        if (this.env == null) {
            // Both SERVER + CLIENT

            if (serverAction != null) {
                ServerPlayNetworking.registerGlobalReceiver(ID, serverAction);
            }

            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT && clientAction != null) {
                ClientPlayNetworking.registerGlobalReceiver(ID, clientAction);
            }
        } else if (this.env == EnvType.CLIENT && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT && clientAction != null) {
            ClientPlayNetworking.registerGlobalReceiver(ID, clientAction);
        } else if (this.env == EnvType.SERVER && FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER && serverAction != null) {
            ServerPlayNetworking.registerGlobalReceiver(ID, serverAction);
        }

        Glass.LOGGER.info("Registered Packet: " + this.ID);
    }


    private static void onTerminalChannelChanged(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        GlobalPos pos = buf.readGlobalPos();
        String channel = buf.readString();

        server.executeSync(() -> {
            var entity = server.getWorld(pos.getDimension()).getBlockEntity(pos.getPos());

            if (entity instanceof TerminalBlockEntity terminal) {
                terminal.channel = channel;
                terminal.markDirty();

                var channelManager = ChannelManagerPersistence.get(player.getWorld());

                AtomicReference<Channel> old = new AtomicReference<>();

                channelManager.forEach(channel1 -> {
                    if (channel1.linkedBlock() != null && channel1.dimension() != null) {
                        if (channel1.linkedBlock().asLong() == pos.getPos().asLong() &&
                                channel1.dimension() == pos.getDimension()) {
                            old.set(channel1);
                        }
                    }
                });

                if (old.get() != null) {
                    Channel channel1 = old.get();
                    channelManager.remove(channel1);
                }

                channelManager.removeIf(channels -> channels.name().equals(channel));

                channelManager.add(new Channel(channel, pos.getPos(), pos.getDimension()));
            }
        });
    }

    private static void onProjectorChannelChanged(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        GlobalPos pos = buf.readGlobalPos();
        String channel = buf.readString();

        server.executeSync(() -> {
            var entity = server.getWorld(pos.getDimension()).getBlockEntity(pos.getPos());

            if (entity instanceof ProjectorBlockEntity projector) {
                projector.channel = channel;
                projector.markDirty();
            }
        });
    }

    private static void onRemoveLinkedChannel(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        GlobalPos pos = buf.readGlobalPos();

        server.executeSync(() -> {
            var channelManager = ChannelManagerPersistence.get(player.getWorld());

            var entity = server.getWorld(pos.getDimension()).getBlockEntity(pos.getPos());

            String cachedChannel = "";

            if (entity instanceof TerminalBlockEntity terminal) {
                cachedChannel = new String(terminal.channel.toCharArray());
                terminal.channel = "";
                terminal.markDirty();
            }

            channelManager.removeIf(channel -> channel.linkedBlock() != null && channel.dimension() != null &&
                    channel.linkedBlock().asLong() == pos.getPos().asLong() &&
                    channel.dimension() == pos.getDimension());
            channelManager.add(new Channel(cachedChannel, null, player.getWorld().getRegistryKey()));

        });
    }

    private static void onPopulateDefaultChannel(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        if (channelManager.stream().anyMatch(channel -> channel.name().equals("Default"))) {
            return;
        }

        channelManager.add(new Channel("Default", null, player.getWorld().getRegistryKey()));
    }

    private static void onDeleteChannel(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        String channel = buf.readString();

        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        channelManager.removeIf(channel1 -> Objects.equals(channel1.name(), channel));
    }

    private static void onCreateChannel(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        String channel = buf.readString();

        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        channelManager.add(new Channel(channel, null, player.getWorld().getRegistryKey()));
    }
}