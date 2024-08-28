package dev.imb11.client.gui;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GPackets;
import io.github.cottonmc.cotton.gui.SyncedGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WListPanel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
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
import java.util.concurrent.atomic.AtomicReference;

public class ProjectorBlockGUI extends SyncedGuiDescription {
    public static final ScreenHandlerType<ProjectorBlockGUI> SCREEN_HANDLER_TYPE = ScreenHandlerRegistry.registerExtended(new Identifier("glass", "projector_screen"), ProjectorBlockGUI::new);

    private static final int WIDTH = 7*18*2;
    private static final int HEIGHT = 6*18*2;
    public ProjectorBlockGUI(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        super(SCREEN_HANDLER_TYPE, syncId, playerInventory);

        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        root.setSize(WIDTH, HEIGHT);
        root.setInsets(Insets.ROOT_PANEL);

        AtomicReference<String> selectedChannel = new AtomicReference<>(buf.readString());
        GlobalPos pos = buf.readGlobalPos();
        NbtCompound nbt = buf.readNbt();

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

        ArrayList<WButtonTooltip> channelButtons = new ArrayList<>();

        WListPanel<Channel, WButtonTooltip> channelList = new WListPanel<>(channels, WButtonTooltip::new, (Channel channel, WButtonTooltip btn) -> {

            btn.setOnClick(() -> {
                PacketByteBuf bufe = PacketByteBufs.create();
                bufe.writeGlobalPos(pos);
                bufe.writeString(btn.getLabel().getString());

                selectedChannel.set(btn.getLabel().getString());

                ClientPlayNetworking.send(GPackets.PROJECTOR_CHANNEL_CHANGED.ID, bufe);

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