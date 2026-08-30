package dev.imb11.client.gui;

import dev.imb11.Glass;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.packets.*;
import io.github.cottonmc.cotton.gui.SyncedGuiDescription;
import io.github.cottonmc.cotton.gui.widget.*;
import io.github.cottonmc.cotton.gui.widget.data.Insets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

public class TerminalBlockGUI extends SyncedGuiDescription {
    public static final MenuType<TerminalBlockGUI> SCREEN_HANDLER_TYPE = new ExtendedScreenHandlerType<>((ExtendedScreenHandlerType.ExtendedFactory) (syncId, inventory, data) -> new TerminalBlockGUI(syncId, inventory, (TerminalBlockEntity.ScreenHandlerData) data), TerminalBlockEntity.ScreenHandlerData.CODEC);

    public BlockPos pos;

    private static final int WIDTH = 7*18*2;
    private static final int HEIGHT = 6*18*2;

    private WButton unlinkChannelButton;

    public TerminalBlockGUI(int syncId, Inventory playerInventory, TerminalBlockEntity.ScreenHandlerData context) {
        super(SCREEN_HANDLER_TYPE, syncId, playerInventory);

        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        root.setSize(WIDTH, HEIGHT);
        root.setInsets(Insets.ROOT_PANEL);

        pos = context.pos();

        CompoundTag nbt = context.channelManagerNbt();

        ArrayList<ChannelOption> channels = new ArrayList<>();

        assert nbt != null;
        ListTag _channels = nbt.getList("channels", Tag.TAG_COMPOUND);

        for (int i = 0; i < _channels.size(); i++) {
            CompoundTag channel = _channels.getCompound(i);
            @Nullable BlockPos bpos = (!channel.contains("linked_pos")) ? null : ChannelManagerPersistence.getFromIntArrayNBT("linked_pos", channel);
            ChannelOption channel1 = new ChannelOption(channel.getString("name"), bpos);
            channels.add(channel1);
        }

        if(channels.isEmpty()) {
            channels.add(new ChannelOption(ChannelManagerPersistence.DEFAULT_CHANNEL, null));
        }

        Glass.LOGGER.info("[GUI-CHANNELS] {} [WORLD] {}", channels, world);

        ArrayList<WButtonTooltip> channelButtons = new ArrayList<>();

        WListPanel<ChannelOption, WButtonTooltip> channelList = new WListPanel<>(channels, WButtonTooltip::new, (ChannelOption channel, WButtonTooltip btn) -> {

            btn.setOnClick(() -> {
                ClientPlayNetworking.send(new C2STerminalChannelChangedPacket(pos, btn.getLabel().getString()));

                for (WButton channelButton : channelButtons) {
                    if(!channelButton.isEnabled()) {
                        for (ChannelOption channeles : channels) {
                            if(Objects.equals(channel.name(), channelButton.getLabel().getString())) {
                                if (channeles.linkedBlock() == null) {
                                    channelButton.setEnabled(true);
                                }
                                break;
                            }
                        }

                    }
                }

                btn.setEnabled(false);
                unlinkChannelButton.setEnabled(true);

                this.removed(playerInventory.player);
            });

            if (channel.linkedBlock() != null) {
                if (pos.asLong() == channel.linkedBlock().asLong()) {
                    btn.setEnabled(false);
                }
                else {
                    btn.setEnabled(false);
                    btn.setTooltip(Component.literal("Channel is being used by another terminal."), Component.literal(channel.linkedBlock().toShortString()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }

            btn.setLabel(Component.literal(channel.name()));

            channelButtons.add(btn);
        });

        channelList.setListItemHeight(18);
        root.add(channelList, 5, 10, (WIDTH/2) - 10, HEIGHT - 10);

        unlinkChannelButton = new WButton();

        unlinkChannelButton.setOnClick(() -> {
            ClientPlayNetworking.send(new C2SRemoveLinkedChannelPacket(pos));

            for (WButton channelButton : channelButtons) {
                if(!channelButton.isEnabled()) {
                    for (ChannelOption channel : channels) {
                        if(Objects.equals(channel.name(), channelButton.getLabel().getString())) {
                            channelButton.setEnabled(channel.linkedBlock() == null);
                            break;
                        }
                    }
                }
            }

            unlinkChannelButton.setEnabled(false);
        });

        unlinkChannelButton.setLabel(Component.literal("Unlink Terminal"));

        unlinkChannelButton.setEnabled(false);

        for (ChannelOption channel : channels) {
            if(channel.linkedBlock() != null) {
                if(channel.linkedBlock().asLong() == pos.asLong())
                {
                    unlinkChannelButton.setEnabled(true);
                    break;
                }
            }
        }

        AtomicReference<String> channelNameBoxValue = new AtomicReference<>("");

        WTextField channelNameBox = new WTextField(Component.literal("Channel Name"));
        channelNameBox.setChangedListener(channelNameBoxValue::set);

        root.add(channelNameBox, (WIDTH/2), 10, (WIDTH/2) - 6, 20);

        WButton addChannel = new WButton();
        addChannel.setOnClick(() -> {
            String val = ChannelManagerPersistence.canonicalChannelName(channelNameBoxValue.get());
            if(val == null) {
                addChannel.setLabel(Component.literal("Invalid Channel Name").withStyle(ChatFormatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        addChannel.setLabel(Component.literal("Add Channel"));
                    }
                }, 1000);
            } else {

                channels.add(new ChannelOption(val, null));

                ClientPlayNetworking.send(new C2SCreateChannelPacket(pos, val));

                channelList.layout();
                channelNameBoxValue.set("");
                channelNameBox.setText("");
                channelNameBox.releaseFocus();
                addChannel.setLabel(Component.literal("Added!").withStyle(ChatFormatting.GREEN));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        addChannel.setLabel(Component.literal("Add Channel"));
                    }
                }, 1000);
            }
        });

        addChannel.setLabel(Component.literal("Add Channel"));

        root.add(addChannel, (WIDTH/2), 35, (WIDTH/2) - 5, 20);

        WButton removeChannel = new WButton();
        removeChannel.setOnClick(() -> {
            String val = ChannelManagerPersistence.canonicalChannelName(channelNameBoxValue.get());
            if(val == null) {
                removeChannel.setLabel(Component.literal("Invalid Channel Name").withStyle(ChatFormatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        removeChannel.setLabel(Component.literal("Remove Channel"));
                    }
                }, 1000);
            } else {
                for (ChannelOption channel : channels) {
                    if(Objects.equals(channel.name(), val)) {

                        ClientPlayNetworking.send(new C2SDeleteChannelPacket(pos, channel.name()));
                        channels.remove(channel);
                        channelList.layout();
                        removeChannel.setLabel(Component.literal("Removed!").withStyle(ChatFormatting.GREEN));
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                removeChannel.setLabel(Component.literal("Remove Channel"));
                            }
                        }, 1000);
                        return;
                    }
                }
                removeChannel.setLabel(Component.literal("Invalid Channel").withStyle(ChatFormatting.RED));
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        removeChannel.setLabel(Component.literal("Remove Channel"));
                    }
                }, 1000);
            }
        });
        removeChannel.setLabel(Component.literal("Remove Channel"));

        root.add(removeChannel, (WIDTH/2), 60, (WIDTH/2) - 5, 20);
        root.add(unlinkChannelButton, (WIDTH/2), 85, (WIDTH/2) - 5, 20);

        WLabel previewLabel = new WLabel(Component.literal("Preview").withStyle(ChatFormatting.GRAY));

        root.add(previewLabel, (WIDTH/2), 110, (WIDTH/2) - 5, 5);

        root.validate(this);
    }

    public boolean isFor(BlockPos pos) {
        return this.pos.equals(pos);
    }

    private record ChannelOption(String name, @Nullable BlockPos linkedBlock) {
    }
}
