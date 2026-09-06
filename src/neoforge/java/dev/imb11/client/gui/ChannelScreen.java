package dev.imb11.client.gui;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.ClientProjectionSourceRegistry;
import dev.imb11.platform.ClientNetworking;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.packets.C2SCreateChannelPacket;
import dev.imb11.sync.packets.C2SDeleteChannelPacket;
import dev.imb11.sync.packets.C2SProjectorChannelChangedPacket;
import dev.imb11.sync.packets.C2SRemoveLinkedChannelPacket;
import dev.imb11.sync.packets.C2STerminalChannelChangedPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Comparator;
import java.util.List;

public abstract class ChannelScreen<T extends ChannelMenu> extends AbstractContainerScreen<T> {
    private static final int ROWS = 8;
    private final boolean terminal;
    private List<Channel> channels = List.of();
    private long revision = -1L;
    private String selectedChannel = "";
    private int page;
    private EditBox channelName;
    private Button addChannel;
    private Button removeChannel;
    private Button unlink;

    protected ChannelScreen(T menu, Inventory inventory, Component title, boolean terminal) {
        super(menu, inventory, title);
        this.terminal = terminal;
        imageWidth = 300;
        imageHeight = 228;
        if (menu instanceof ProjectorBlockGUI projector) {
            selectedChannel = projector.initialChannel;
        }
    }

    @Override
    protected void init() {
        super.init();
        refreshChannels();
        rebuildControls();
    }

    private void refreshChannels() {
        revision = Math.max(menu.initialRevision, ClientProjectionSourceRegistry.registryRevision());
        channels = (ClientProjectionSourceRegistry.registryRevision() >= menu.initialRevision
                ? ClientProjectionSourceRegistry.channels().values().stream()
                : menu.initialChannels.stream()).sorted(Comparator.comparing(Channel::name)).toList();
        page = Math.min(page, Math.max(0, (channels.size() - 1) / ROWS));
    }

    private boolean linkedHere(Channel channel) {
        return channel.source() != null
                && channel.source().dimension().equals(minecraft.player.level().dimension())
                && channel.source().pos().equals(menu.pos);
    }

    private void rebuildControls() {
        String value = channelName == null ? "" : channelName.getValue();
        boolean focused = channelName != null && channelName.isFocused();
        clearWidgets();
        int listWidth = terminal ? 140 : 276;
        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= channels.size()) {
                break;
            }
            Channel channel = channels.get(index);
            Button button = Button.builder(Component.literal(font.plainSubstrByWidth(channel.name(), listWidth - 12)), ignored -> {
                if (terminal) {
                    ClientNetworking.send(new C2STerminalChannelChangedPacket(menu.pos, channel.name()));
                } else {
                    ClientNetworking.send(new C2SProjectorChannelChangedPacket(menu.pos, channel.name()));
                }
            }).bounds(leftPos + 12, topPos + 28 + row * 21, listWidth, 20).build();
            button.active = terminal ? channel.source() == null
                    : channel.source() != null && !channel.name().equals(selectedChannel);
            Component tooltip = Component.literal(channel.name());
            if (channel.source() != null) {
                tooltip = tooltip.copy().append("\n" + channel.source().dimension().location() + " · " + channel.source().pos().toShortString());
                if (terminal) {
                    tooltip = tooltip.copy().append(linkedHere(channel) ? "\nLinked to this terminal" : "\nChannel is being used by another terminal");
                }
            } else if (!terminal) {
                tooltip = tooltip.copy().append("\nChannel not linked to a terminal");
            }
            button.setTooltip(Tooltip.create(tooltip));
            addRenderableWidget(button);
        }
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> {
            page--;
            rebuildControls();
        }).bounds(leftPos + 12, topPos + 202, 24, 18).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> {
            page++;
            rebuildControls();
        }).bounds(leftPos + listWidth - 12, topPos + 202, 24, 18).build());
        next.active = (page + 1) * ROWS < channels.size();

        if (terminal) {
            channelName = addRenderableWidget(new EditBox(font, leftPos + 160, topPos + 28, 128, 20, Component.literal("Channel Name")));
            channelName.setMaxLength(ChannelManagerPersistence.MAX_CHANNEL_NAME_LENGTH);
            channelName.setHint(Component.literal("Channel Name"));
            channelName.setValue(value);
            addChannel = addRenderableWidget(Button.builder(Component.literal("Add Channel"), ignored ->
                    ClientNetworking.send(new C2SCreateChannelPacket(menu.pos, canonicalName())))
                    .bounds(leftPos + 160, topPos + 54, 128, 20).build());
            removeChannel = addRenderableWidget(Button.builder(Component.literal("Remove Channel"), ignored ->
                    ClientNetworking.send(new C2SDeleteChannelPacket(menu.pos, canonicalName())))
                    .bounds(leftPos + 160, topPos + 79, 128, 20).build());
            unlink = addRenderableWidget(Button.builder(Component.literal("Unlink Terminal"), ignored ->
                    ClientNetworking.send(new C2SRemoveLinkedChannelPacket(menu.pos)))
                    .bounds(leftPos + 160, topPos + 104, 128, 20).build());
            channelName.setResponder(ignored -> updateActions());
            updateActions();
            if (focused) {
                setFocused(channelName);
            }
        }
    }

    private String canonicalName() {
        return ChannelManagerPersistence.canonicalChannelName(channelName.getValue());
    }

    private void updateActions() {
        String name = canonicalName();
        boolean exists = channels.stream().anyMatch(channel -> channel.name().equals(name));
        addChannel.active = name != null && !exists && channels.size() < ChannelManagerPersistence.MAX_CHANNELS;
        removeChannel.active = exists && !ChannelManagerPersistence.DEFAULT_CHANNEL.equals(name);
        unlink.active = channels.stream().anyMatch(this::linkedHere);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean changed = ClientProjectionSourceRegistry.registryRevision() > revision;
        if (!terminal && minecraft.level.getBlockEntity(menu.pos) instanceof ProjectorBlockEntity projector
                && !selectedChannel.equals(projector.getChannel())) {
            selectedChannel = projector.getChannel();
            changed = true;
        }
        if (changed) {
            refreshChannels();
            rebuildControls();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF20242C);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 22, 0xFF343B48);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 8, 0xFFFFFF, false);
        int listWidth = terminal ? 140 : 276;
        graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (channels.size() + ROWS - 1) / ROWS), 12 + listWidth / 2, 207, 0xDDDDDD);
        if (channels.isEmpty()) {
            graphics.drawString(font, "No channels", 12, 34, 0xAAAAAA, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (channelName != null && channelName.isFocused() && keyCode != 256) {
            return channelName.keyPressed(keyCode, scanCode, modifiers) || channelName.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
