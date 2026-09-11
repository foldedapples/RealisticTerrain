package com.cokedoutsnail.realisticterrain.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import java.util.function.DoubleConsumer;

/**
 * A slider for one {@link com.cokedoutsnail.realisticterrain.worldgen.TerrainSetting}. The label is
 * translated and the value is formatted as an integer when the setting is an integer one, so "Sea
 * level: 96" does not read as "Sea level: 96.00".
 */
@Environment(EnvType.CLIENT)
final class DoubleSlider extends SliderWidget {
    private final Text label;
    private final double min, max;
    private final boolean integral;
    private final DoubleConsumer changed;

    DoubleSlider(int x, int y, int w, Text label, double min, double max, double current,
                 boolean integral, DoubleConsumer changed) {
        super(x, y, w, 20, Text.empty(), (current - min) / (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.integral = integral;
        this.changed = changed;
        updateMessage();
    }

    double actual() { return min + (max - min) * value; }

    @Override protected void updateMessage() {
        double v = actual();
        String number = integral ? Integer.toString((int) Math.rint(v)) : String.format("%.2f", v);
        setMessage(Text.literal(label.getString() + ": " + number));
    }

    @Override protected void applyValue() { changed.accept(actual()); }
}
