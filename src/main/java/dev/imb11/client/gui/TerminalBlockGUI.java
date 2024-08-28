package dev.imb11.client.gui;

import dev.imb11.Glass;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GPackets;
import io.github.cottonmc.cotton.gui.SyncedGuiDescription;
import io.github.cottonmc.cotton.gui.widget.*;
import io.github.cottonmc.cotton.gui.widget.data.Insets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

public class TerminalBlockGUI extends SyncedGuiDescription {
    public static final ScreenHandlerType<TerminalBlockGUI> SCREEN_HANDLER_TYPE = ScreenHandlerRegistry.registerExtended(new Identifier("glass", "terminal_screen"), TerminalBlockGUI::new);

    public GlobalPos pos;

    private static final int WIDTH = 7*18*2;
    private static final int HEIGHT = 6*18*2;

    private WButton unlinkChannelButton;

    public TerminalBlockGUI(int syncId, PlayerInventory playerInventory, PacketByteBuf context) {
        super(SCREEN_HANDLER_TYPE, syncId, playerInventory);

        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        root.setSize(WIDTH, HEIGHT);
        root.setInsets(Insets.ROOT_PANEL);

        pos = context.readGlobalPos();

        NbtCompound nbt = context.readNbt();

        ArrayList<Channel> channels = new ArrayList<>();

        assert nbt != null;
        NbtList _channels = nbt.getList("channels", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < _channels.size(); i++) {
            NbtCompound channelNbt = _channels.getCompound(i);
            Channel.CODEC.parse(NbtOps.INSTANCE, channelNbt)
                    .resultOrPartial(System.out::println) // TODO: Proper logging
                    .ifPresent(channels::add);
        }

        if (channels.size() == 0) {
            ClientPlayNetworking.send(GPackets.POPULATE_DEFAULT_CHANNEL.ID, PacketByteBufs.empty());

            channels.add(new Channel("Default", null, World.OVERWORLD));
        }

        Glass.LOGGER.info("[GUI-CHANNELS] " + channels + " [WORLD] " + world);

        ArrayList<WButtonTooltip> channelButtons = new ArrayList<>();

//        WPortalFrame previewFrame = new WPortalFrame(this.world.getRegistryKey(), new Vec3d(pos.getX(), pos.getY(), pos.getZ()));
//
//        root.add(previewFrame, (WIDTH/2), 120, (WIDTH/2) - 5, HEIGHT - 120 - 5);
        // Doesn't work

        WListPanel<Channel, WButtonTooltip> channelList = new WListPanel<>(channels, WButtonTooltip::new, (Channel channel, WButtonTooltip btn) -> {

            btn.setOnClick(() -> {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeGlobalPos(pos);
                buf.writeString(btn.getLabel().getString());

                ClientPlayNetworking.send(GPackets.TERMINAL_CHANNEL_CHANGED.ID, buf);

                for (WButton channelButton : channelButtons) {
                    if (!channelButton.isEnabled()) {
                        for (Channel channeles : channels) {
                            if (Objects.equals(channel.name(), channelButton.getLabel().getString())) {
                                if (channeles.linkedBlock() == null || channeles.dimension() == null) {
                                    channelButton.setEnabled(true);
                                }
                                break;
                            }
                        }

                    }
                }

                btn.setEnabled(false);
                unlinkChannelButton.setEnabled(true);

                this.onClosed(playerInventory.player);
            });

            if (channel.linkedBlock() != null && channel.dimension() != null) {
                if (pos.getPos().asLong() == channel.linkedBlock().asLong() &&
                        pos.getDimension() == channel.dimension()) {
                    btn.setEnabled(false);
                } else {
                    btn.setEnabled(false);
                    btn.setTooltip(Text.literal("Channel is being used by another terminal."), Text.literal(channel.linkedBlock().toShortString()).formatted(Formatting.GRAY, Formatting.ITALIC));
                }
            }

            btn.setLabel(Text.literal(channel.name()));

            channelButtons.add(btn);
        });

        channelList.setListItemHeight(18);
        root.add(channelList, 5, 10, (WIDTH/2) - 10, HEIGHT - 10);

        unlinkChannelButton = new WButton();

        unlinkChannelButton.setOnClick(() -> {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeGlobalPos(pos);
            buf.writeString("");

            ClientPlayNetworking.send(GPackets.REMOVE_LINKED_CHANNEL.ID, buf);

            for (WButton channelButton : channelButtons) {
                if(!channelButton.isEnabled()) {
                    for (Channel channel : channels) {
                        if(Objects.equals(channel.name(), channelButton.getLabel().getString())) {
                            channelButton.setEnabled(channel.linkedBlock() == null);
                            break;
                        }
                    }
                }
            }

            unlinkChannelButton.setEnabled(false);
        });

        unlinkChannelButton.setLabel(Text.literal("Unlink Terminal"));

        unlinkChannelButton.setEnabled(false);

        for (Channel channel : channels) {
            if (channel.linkedBlock() != null && channel.dimension() != null) {
                if (channel.linkedBlock().asLong() == pos.getPos().asLong() &&
                        channel.dimension() == pos.getDimension()) {
                    unlinkChannelButton.setEnabled(true);
                    break;
                }
            }
        }

        AtomicReference<String> channelNameBoxValue = new AtomicReference<>("");

        WTextField channelNameBox = new WTextField(Text.literal("Channel Name"));
        channelNameBox.setChangedListener(channelNameBoxValue::set);

        root.add(channelNameBox, (WIDTH/2), 10, (WIDTH/2) - 6, 20);

        WButton addChannel = new WButton();
        addChannel.setOnClick(() -> {
            String val = channelNameBoxValue.get();
            if (val.isBlank()) {
                addChannel.setLabel(Text.literal("Invalid Channel Name").formatted(Formatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        addChannel.setLabel(Text.literal("Add Channel"));
                    }
                }, 1000);
            } else {

                channels.add(new Channel(channelNameBoxValue.get(), null, World.OVERWORLD));

                ClientPlayNetworking.send(GPackets.CREATE_CHANNEL.ID, PacketByteBufs.create().writeString(channelNameBoxValue.get()));

                channelList.layout();
                channelNameBoxValue.set("");
                channelNameBox.setText("");
                channelNameBox.releaseFocus();
                addChannel.setLabel(Text.literal("Added!").formatted(Formatting.GREEN));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        addChannel.setLabel(Text.literal("Add Channel"));
                    }
                }, 1000);
            }
        });

        addChannel.setLabel(Text.literal("Add Channel"));

        root.add(addChannel, (WIDTH/2), 35, (WIDTH/2) - 5, 20);

        WButton removeChannel = new WButton();
        removeChannel.setOnClick(() -> {
            String val = channelNameBoxValue.get();
            if(val.isBlank()) {
                removeChannel.setLabel(Text.literal("Invalid Channel Name").formatted(Formatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        removeChannel.setLabel(Text.literal("Remove Channel"));
                    }
                }, 1000);
            } else {
                for (Channel channel : channels) {
                    if(Objects.equals(channel.name(), val)) {
                        ClientPlayNetworking.send(GPackets.DELETE_CHANNEL.ID, PacketByteBufs.create().writeString(channel.name()));
                        channels.remove(channel);
                        channelList.layout();
                        removeChannel.setLabel(Text.literal("Removed!").formatted(Formatting.GREEN));
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                removeChannel.setLabel(Text.literal("Remove Channel"));
                            }
                        }, 1000);
                        return;
                    }
                }
                removeChannel.setLabel(Text.literal("Invalid Channel").formatted(Formatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        removeChannel.setLabel(Text.literal("Remove Channel"));
                    }
                }, 1000);
            }
        });
        removeChannel.setLabel(Text.literal("Remove Channel"));

        root.add(removeChannel, (WIDTH/2), 60, (WIDTH/2) - 5, 20);
        root.add(unlinkChannelButton, (WIDTH/2), 85, (WIDTH/2) - 5, 20);

        WLabel previewLabel = new WLabel(Text.literal("Preview").formatted(Formatting.GRAY));

        root.add(previewLabel, (WIDTH/2), 110, (WIDTH/2) - 5, 5);

        root.validate(this);
    }
}