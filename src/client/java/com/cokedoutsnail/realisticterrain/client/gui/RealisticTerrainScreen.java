package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import com.cokedoutsnail.realisticterrain.worldgen.RealisticChunkGenerator;
import com.cokedoutsnail.realisticterrain.worldgen.ScaledBiomeSource;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainBiomeSource;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Client editor. Values update the preview immediately; applying stores the settings for the selected preset. */
public final class RealisticTerrainScreen extends Screen {
    private final CreateWorldScreen parent; private TerrainSettings s; private long previewSeed=0x5245414c49535449L;
    public RealisticTerrainScreen(CreateWorldScreen parent, GeneratorOptionsHolder holder){
        super(Text.translatable("realisticterrain.customize.title"));
        this.parent=parent;
        this.s = holder.selectedDimensions().getChunkGenerator() instanceof RealisticChunkGenerator generator
                ? generator.settings() : TerrainSettings.DEFAULT;
    }
    /**
     * Every slider routes through here. The index to setting mapping lives in
     * {@link TerrainSettings#withValue(int, double)}, so adding a setting never means writing
     * another seventeen-argument constructor call in the GUI.
     */
    private TerrainSettings set(int idx,double v){ return s.withValue(idx,v); }
    @Override protected void init(){
        // World-type profile buttons: one click loads a full named style.
        int by=26;
        int bw=Math.max(64,(width-40-(TerrainSettings.PROFILES.size()-1)*4)/TerrainSettings.PROFILES.size());
        int bx=20;
        for(TerrainSettings.Profile p:TerrainSettings.PROFILES){
            addDrawableChild(ButtonWidget.builder(Text.translatable(p.nameKey()),b->{s=p.settings(); clearAndInit();}).dimensions(bx,by,bw,20).build());
            bx+=bw+4;
        }
        // Sixteen sliders no longer fit in one column at a normal GUI scale, so they are laid out in
        // two columns of eight, sized to the window. Every row is data-driven: the index is the only
        // thing tying a slider to a setting (see TerrainSettings.withValue).
        int top=by+30, gap=24;
        int colW=Math.min(210,Math.max(140,(width-48)/2));
        slider(0, 20, top, colW, gap, "Mountain height",    .25, 3);
        slider(1, 20, top, colW, gap, "Mountain frequency", .25, 3);
        slider(2, 20, top, colW, gap, "Ridge sharpness",    .25, 3);
        slider(3, 20, top, colW, gap, "Erosion",            0,   2.5);
        slider(4, 20, top, colW, gap, "River width",        .25, 4);
        slider(5, 20, top, colW, gap, "River frequency",    .25, 3);
        slider(6, 20, top, colW, gap, "River depth",        .25, 3);
        slider(7, 20, top, colW, gap, "Snow line",          96,  1800);
        slider(8, 20, top, colW, gap, "Biome scale",        .25, 4);
        slider(9, 20, top, colW, gap, "Sea level",          -32, 512);
        slider(10, 20, top, colW, gap, "Roughness",         .2,  3);
        slider(11, 20, top, colW, gap, "Vegetation",        0,   3);
        slider(12, 20, top, colW, gap, "Continental scale", .5,  2.5);
        slider(13, 20, top, colW, gap, "Canyon depth",      0,   2.5);
        // The two ReTerraForged-style "control points" that let a player fix a world that came out
        // as endless ocean: Coast line moves the shoreline, Ocean depth deepens the abyssal plain.
        slider(14, 20, top, colW, gap, "Coast line",        -.35, .15);
        slider(15, 20, top, colW, gap, "Ocean depth",       0,   300);
        addDrawableChild(ButtonWidget.builder(Text.translatable("realisticterrain.customize.reset"),b->{s=TerrainSettings.DEFAULT; clearAndInit();}).dimensions(20,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("realisticterrain.customize.done"),b->{
            TerrainSettings applied = s;
            parent.getWorldCreator().applyModifier((registries, dimensions) -> {
                var oldGenerator = dimensions.getChunkGenerator();
                var biomeSource = oldGenerator.getBiomeSource();
                if (biomeSource instanceof TerrainBiomeSource tbs) {
                    // The biome source carries its own copy of the settings (scale + climate), so
                    // it must be updated in lockstep with the generator or biomes drift from terrain.
                    biomeSource = tbs.withSettings(applied);
                } else if (biomeSource instanceof ScaledBiomeSource scaled) {
                    biomeSource = scaled.withScale(applied.biomeScale());
                }
                return dimensions.with(registries, new RealisticChunkGenerator(biomeSource, applied));
            });
            client.setScreen(parent);
        }).dimensions(width-120,height-28,100,20).build());
    }
    /** Places slider {@code index} in a two-column grid: indices 0-7 in the left column, 8-15 right. */
    private void slider(int index,int left,int top,int colW,int gap,String label,double min,double max){
        int row=index%8, col=index/8;
        addDrawableChild(new DoubleSlider(left+col*(colW+8),top+row*gap,colW,label,min,max,
                s.getValue(index),v->s=set(index,v)));
    }
    @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta){
        super.render(ctx,mouseX,mouseY,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,14,0xFFFFFF);
        // The slider columns already own the left half of the window, so the preview only gets
        // drawn when there is genuinely room for it instead of being squashed to nothing.
        int colW=Math.min(210,Math.max(140,(width-48)/2));
        int px=20+2*(colW+8)+16, py=42, pw=width-px-20, ph=height-90;
        if(pw<140) return;
        TerrainPreview.render(ctx,textRenderer,px,py,pw,ph,previewSeed,s);
        ctx.drawTextWithShadow(textRenderer,Text.translatable("realisticterrain.customize.preview"),px,py-14,0xFFFFFF);
    }
    @Override public void close(){ client.setScreen(parent); }
}
