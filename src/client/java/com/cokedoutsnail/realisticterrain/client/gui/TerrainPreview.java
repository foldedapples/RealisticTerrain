package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import net.minecraft.client.gui.DrawContext;

final class TerrainPreview {
    private static final int CELLS = 64;
    private static final int[] COLORS = new int[CELLS * CELLS];
    private static TerrainSettings cachedSettings;
    private static long cachedSeed = Long.MIN_VALUE;

    static void render(DrawContext ctx,int x,int y,int w,int h,long seed,TerrainSettings settings){
        if (cachedSeed != seed || !settings.equals(cachedSettings)) {
            rebuild(seed, settings);
        }
        for(int py=0;py<CELLS;py++) for(int px=0;px<CELLS;px++){
            int x0=x+px*w/CELLS,y0=y+py*h/CELLS,x1=x+(px+1)*w/CELLS+1,y1=y+(py+1)*h/CELLS+1;
            ctx.fill(x0,y0,x1,y1,COLORS[py * CELLS + px]);
        }
        ctx.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        ctx.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        ctx.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
    }

    private static void rebuild(long seed, TerrainSettings settings) {
        double sampleScale=3600.0/CELLS;
        for(int py=0;py<CELLS;py++) for(int px=0;px<CELLS;px++){
            TerrainModel.Sample sample=TerrainModel.sample(seed,(px-CELLS/2.0)*sampleScale,(py-CELLS/2.0)*sampleScale,settings);
            COLORS[py * CELLS + px]=color(sample,settings);
        }
        cachedSeed=seed;
        cachedSettings=settings;
    }
    private static int color(TerrainModel.Sample s,TerrainSettings set){
        double h=s.height(); if(h<set.seaLevel()-28)return 0xFF173E73; if(h<s.waterLevel()-12)return 0xFF225F91; if(h<s.waterLevel())return 0xFF49AFC0;
        if(h>set.snowLine()+100)return 0xFFF1F5F8; if(s.ridge()>.7)return 0xFF777A73; if(h>330)return 0xFF60784E; return s.moisture()>.1?0xFF397044:0xFF7F8B55;
    }
}
