package com.cokedoutsnail.realisticterrain.client.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import java.util.function.DoubleConsumer;

final class DoubleSlider extends SliderWidget {
    private final String label; private final double min,max; private final DoubleConsumer changed;
    DoubleSlider(int x,int y,int w,String label,double min,double max,double current,DoubleConsumer changed){ super(x,y,w,20,Text.empty(),(current-min)/(max-min)); this.label=label;this.min=min;this.max=max;this.changed=changed;updateMessage(); }
    double actual(){return min+(max-min)*value;}
    @Override protected void updateMessage(){ setMessage(Text.literal(label+": "+String.format("%.2f",actual()))); }
    @Override protected void applyValue(){ changed.accept(actual()); }
}
