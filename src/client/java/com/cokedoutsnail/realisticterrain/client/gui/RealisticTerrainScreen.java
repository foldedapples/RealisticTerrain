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
    private TerrainSettings set(int idx,double v){ float f=(float)v; return switch(idx){
        case 0->new TerrainSettings(f,s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 1->new TerrainSettings(s.mountainHeight(),f,s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 2->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),f,s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 3->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),f,s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 4->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),f,s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 5->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),f,s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 6->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),f,s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 7->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),(int)v,s.biomeScale(),s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 8->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),f,s.seaLevel(),s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 9->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),(int)v,s.roughness(),s.vegetationDensity(),s.seedSalt());
        case 10->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),f,s.vegetationDensity(),s.seedSalt());
        default->new TerrainSettings(s.mountainHeight(),s.mountainFrequency(),s.ridgeSharpness(),s.erosionIntensity(),s.riverWidth(),s.riverFrequency(),s.riverDepth(),s.snowLine(),s.biomeScale(),s.seaLevel(),s.roughness(),f,s.seedSalt());}; }
    @Override protected void init(){ int x=20,w=220,g=22;
        // World-type profile buttons: one click loads a full named style.
        int by=26;
        int bw=Math.max(64,(width-40-(TerrainSettings.PROFILES.size()-1)*4)/TerrainSettings.PROFILES.size());
        int bx=20;
        for(TerrainSettings.Profile p:TerrainSettings.PROFILES){
            addDrawableChild(ButtonWidget.builder(Text.translatable(p.nameKey()),b->{s=p.settings(); clearAndInit();}).dimensions(bx,by,bw,20).build());
            bx+=bw+4;
        }
        int sy=by+28;
        addDrawableChild(new DoubleSlider(x,sy+g*0,w,"Mountain height",.25,3,s.mountainHeight(),v->s=set(0,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*1,w,"Mountain frequency",.25,3,s.mountainFrequency(),v->s=set(1,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*2,w,"Ridge sharpness",.25,3,s.ridgeSharpness(),v->s=set(2,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*3,w,"Erosion",0,2.5,s.erosionIntensity(),v->s=set(3,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*4,w,"River width",.25,4,s.riverWidth(),v->s=set(4,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*5,w,"River frequency",.25,3,s.riverFrequency(),v->s=set(5,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*6,w,"River depth",.25,3,s.riverDepth(),v->s=set(6,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*7,w,"Snow line",96,1800,s.snowLine(),v->s=set(7,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*8,w,"Biome scale",.25,4,s.biomeScale(),v->s=set(8,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*9,w,"Sea level",-32,512,s.seaLevel(),v->s=set(9,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*10,w,"Roughness",.2,3,s.roughness(),v->s=set(10,v)));
        addDrawableChild(new DoubleSlider(x,sy+g*11,w,"Vegetation",0,3,s.vegetationDensity(),v->s=set(11,v)));
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
    @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta){ super.render(ctx,mouseX,mouseY,delta); ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,14,0xFFFFFF); int px=270,py=42,pw=Math.max(160,width-px-20),ph=Math.max(180,height-90); TerrainPreview.render(ctx,textRenderer,px,py,pw,ph,previewSeed,s); ctx.drawTextWithShadow(textRenderer,Text.translatable("realisticterrain.customize.preview"),px,py-14,0xFFFFFF); }
    @Override public void close(){ client.setScreen(parent); }
}
