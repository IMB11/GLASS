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
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class ProjectorBlockGUI extends SyncedGuiDescription {
    public static final ScreenHandlerType<ProjectorBlockGUI> SCREEN_HANDLER_TYPE = new ExtendedScreenHandlerType<>((ExtendedScreenHandlerType.ExtendedFactory) (syncId, inventory, data) -> new ProjectorBlockGUI(syncId, inventory, (ProjectorBlockEntity.ScreenHandlerData) data), ProjectorBlockEntity.ScreenHandlerData.CODEC);

    private static final int WIDTH = 7*18*2;
    private static final int HEIGHT = 6*18*2;
    public ProjectorBlockGUI(int syncId, PlayerInventory playerInventory, ProjectorBlockEntity.ScreenHandlerData data) {
        super(SCREEN_HANDLER_TYPE, syncId, playerInventory);

        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        root.setSize(WIDTH, HEIGHT);
        root.setInsets(Insets.ROOT_PANEL);

        AtomicReference<String> selectedChannel = new AtomicReference<>(data.channel());
        BlockPos pos = data.pos();
        NbtCompound nbt = data.compound();

        ArrayList<Channel> channels = new ArrayList<>();

        assert nbt != null;
        NbtList _channels = nbt.getList("channels", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < _channels.size(); i++) {
            NbtCompound channel = _channels.getCompound(i);
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

            if (Objects.equals(channel.name(), selectedChannel.get()) || channel.linkedBlock() == null) {
                btn.setEnabled(false);
            }

            btn.setLabel(Text.literal(channel.name()));

            if (channel.linkedBlock() == null) {
                btn.setLabel(Text.literal(channel.name()).formatted(Formatting.DARK_RED, Formatting.ITALIC));
                btn.setTooltip(Text.literal("Channel not linked to a terminal.").formatted(Formatting.RED));
            } else {
                btn.setTooltip(Text.literal("Terminal Position: ").append(Text.literal(channel.linkedBlock().toShortString()).formatted(Formatting.GRAY)));
            }

            channelButtons.add(btn);
        });

        channelList.setListItemHeight(18);
        root.add(channelList, 5, 10, WIDTH - 10, HEIGHT - 10);

        root.validate(this);
    }
}