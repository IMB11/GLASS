package dev.imb11.client.gui;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.packets.C2SPopulateDefaultChannelPacket;
import dev.imb11.sync.packets.C2SProjectorChannelChangedPacket;
import io.github.cottonmc.cotton.gui.SyncedGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WListPanel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
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
import java.util.concurrent.atomic.AtomicReference;

public class ProjectorBlockGUI extends SyncedGuiDescription {
    public static final MenuType<ProjectorBlockGUI> SCREEN_HANDLER_TYPE = new ExtendedScreenHandlerType<>((ExtendedScreenHandlerType.ExtendedFactory) (syncId, inventory, data) -> new ProjectorBlockGUI(syncId, inventory, (ProjectorBlockEntity.ScreenHandlerData) data), ProjectorBlockEntity.ScreenHandlerData.CODEC);

    private static final int WIDTH = 7*18*2;
    private static final int HEIGHT = 6*18*2;
    public ProjectorBlockGUI(int syncId, Inventory playerInventory, ProjectorBlockEntity.ScreenHandlerData data) {
        super(SCREEN_HANDLER_TYPE, syncId, playerInventory);

        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        root.setSize(WIDTH, HEIGHT);
        root.setInsets(Insets.ROOT_PANEL);

        AtomicReference<String> selectedChannel = new AtomicReference<>(data.channel());
        BlockPos pos = data.pos();
        CompoundTag nbt = data.compound();

        ArrayList<Channel> channels = new ArrayList<>();

        assert nbt != null;
        ListTag _channels = nbt.getList("channels", Tag.TAG_COMPOUND);

        for (int i = 0; i < _channels.size(); i++) {
            CompoundTag channel = _channels.getCompound(i);
            @Nullable BlockPos bpos = (!channel.contains("linked_pos")) ? null : ChannelManagerPersistence.getFromIntArrayNBT("linked_pos", channel);
            Channel channel1 = new Channel(channel.getString("name"), bpos);
            channels.add(channel1);
        }

        if(channels.size() == 0) {
            ClientPlayNetworking.send(new C2SPopulateDefaultChannelPacket());
            channels.add(new Channel("Default", null));
        }

        ArrayList<WButtonTooltip> channelButtons = new ArrayList<>();

        WListPanel<Channel, WButtonTooltip> channelList = new WListPanel<>(channels, WButtonTooltip::new, (Channel channel, WButtonTooltip btn) -> {

            btn.setOnClick(() -> {
                selectedChannel.set(btn.getLabel().getString());

                ClientPlayNetworking.send(new C2SProjectorChannelChangedPacket(pos, btn.getLabel().getString()));

                for (WButton channelButton : channelButtons) {
                    if(!channelButton.isEnabled()) {
                        btn.setEnabled(true);
                    }
                }

                btn.setEnabled(false);
            });

            if(Objects.equals(channel.name(), selectedChannel.get()) || channel.linkedBlock() == null) {
                btn.setEnabled(false);
            }

            btn.setLabel(Component.literal(channel.name()));

            if(channel.linkedBlock() == null) {
                btn.setLabel(Component.literal(channel.name()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
                btn.setTooltip(Component.literal("Channel not linked to a terminal.").withStyle(ChatFormatting.RED));
            } else {
                btn.setTooltip(Component.literal("Terminal Position: ").append(Component.literal(channel.linkedBlock().toShortString()).withStyle(ChatFormatting.GRAY)));
            }

            channelButtons.add(btn);
        });

        channelList.setListItemHeight(18);
        root.add(channelList, 5, 10, WIDTH - 10, HEIGHT - 10);

        root.validate(this);
    }
}