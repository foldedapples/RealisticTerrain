package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import net.minecraft.client.gui.DrawContext;

final class TerrainPreview {
    static void render(DrawContext ctx,int x,int y,int w,int h,long seed,TerrainSettings settings){
        int cells=64; double sx=3600.0/cells;
        for(int py=0;py<cells;py++) for(int px=0;px<cells;px++){
            TerrainModel.Sample s=TerrainModel.sample(seed,(px-cells/2.0)*sx,(py-cells/2.0)*sx,settings);
            int c=color(s,settings); int x0=x+px*w/cells,y0=y+py*h/cells,x1=x+(px+1)*w/cells+1,y1=y+(py+1)*h/cells+1;
            ctx.fill(x0,y0,x1,y1,c);
        }
        ctx.drawBorder(x,y,w,h,0xFFFFFFFF);
    }
    private static int color(TerrainModel.Sample s,TerrainSettings set){
        double h=s.height(); if(h<set.seaLevel()-28)return 0xFF173E73; if(h<set.seaLevel())return 0xFF2D79A4; if(s.river()>.5)return 0xFF49AFC0;
        if(h>set.snowLine()+100)return 0xFFF1F5F8; if(s.ridge()>.7)return 0xFF777A73; if(h>330)return 0xFF60784E; return s.moisture()>.1?0xFF397044:0xFF7F8B55;
    }
}
