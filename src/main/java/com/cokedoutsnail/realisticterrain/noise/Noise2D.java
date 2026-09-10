package com.cokedoutsnail.realisticterrain.noise;

/** Deterministic allocation-free value/fractal noise for worldgen and the GUI preview. */
public final class Noise2D {
    private Noise2D() {}
    private static long mix(long x) { x ^= x >>> 33; x *= 0xff51afd7ed558ccdl; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53l; return x ^ (x >>> 33); }
    private static double rnd(int x, int z, long seed) { long h=mix(seed ^ (x*0x9E3779B97F4A7C15L) ^ (z*0xC2B2AE3D27D4EB4FL)); return ((h>>>11)*(1.0/(1L<<53)))*2.0-1.0; }
    private static double fade(double t){ return t*t*t*(t*(t*6-15)+10); }
    private static double lerp(double a,double b,double t){ return a+(b-a)*t; }
    public static double value(double x,double z,long seed){ int x0=(int)Math.floor(x),z0=(int)Math.floor(z); double tx=fade(x-x0),tz=fade(z-z0); return lerp(lerp(rnd(x0,z0,seed),rnd(x0+1,z0,seed),tx),lerp(rnd(x0,z0+1,seed),rnd(x0+1,z0+1,seed),tx),tz); }
    public static double fbm(double x,double z,long seed,int oct,double lac,double gain){ double a=.5,f=1,s=0,n=0; for(int i=0;i<oct;i++){s+=value(x*f,z*f,seed+i*1013L)*a;n+=a;f*=lac;a*=gain;} return s/n; }
    public static double ridged(double x,double z,long seed,int oct){ double n=fbm(x,z,seed,oct,2.02,.5); return 1.0-Math.abs(n); }
}
