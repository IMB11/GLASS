package dev.imb11.client.gui;

import io.github.cottonmc.cotton.gui.widget.TooltipBuilder;
import io.github.cottonmc.cotton.gui.widget.WButton;
import net.minecraft.text.Text;

public class WButtonTooltip extends WButton {
    public Text[] tooltips;

    public void setTooltip(Text... tooltip) {
        tooltips = tooltip;
    }

    @Override
    public void addTooltip(TooltipBuilder tooltip) {
        if(tooltips == null) return;
        tooltip.add(this.tooltips);
    }
}