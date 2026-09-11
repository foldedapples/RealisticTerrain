package com.cokedoutsnail.realisticterrain.worldgen;

/**
 * The physics half of the tectonic model: what a plate <em>is</em>, and what happens where two of them
 * meet.
 *
 * <p>Before this, the engine knew only a single blended "plate trait" per column, and whether a margin
 * collided or rifted was read from a smooth world-space field. That produced plausible terrain but no
 * actual plate properties: every plate was identical, oceanic and continental crust were not
 * distinguished, and there was no way to express a shear boundary at all.
 *
 * <p>Here each plate owns a small, deterministic set of properties derived from its cell id - crust
 * type, base elevation, a velocity vector, crust age and erosion resistance - and a boundary is
 * classified from the physics: the component of the <em>relative</em> velocity along the boundary
 * normal decides convergence against divergence, and the component along the boundary itself decides
 * whether the margin is a transform fault. That is the standard plate-boundary classification, and it
 * is why a strike-slip margin can now exist as a distinct kind of terrain.
 *
 * <p>Everything is a pure function of the plate ids and the boundary normal, so it is deterministic
 * across threads and reloads. The caller blends the result spatially (see {@code TerrainCache}); this
 * class deliberately knows nothing about position.
 */
public final class PlateTectonics {
    private PlateTectonics() {}

    /**
     * One plate's own, unchanging properties. Every component is read by the terrain model - crust age
     * and activity are folded into {@code baseElevation} and {@code resistance} rather than exposed,
     * because a property nothing reads is just dead payload.
     *
     * @param id            the plate's identity (the cellular cell id it was hashed from)
     * @param continental   thick buoyant crust rather than dense oceanic crust
     * @param baseElevation regional crustal bias in blocks: already includes the crust-age subsidence
     * @param velocityX     velocity vector, in arbitrary but consistent units
     * @param velocityZ     velocity vector, in arbitrary but consistent units
     * @param resistance    0..1 erosion resistance, already increased for older crust
     */
    public record Plate(
            long id,
            boolean continental,
            double baseElevation,
            double velocityX,
            double velocityZ,
            double resistance
    ) {}

    /**
     * How a margin behaves, as three weights that always sum to one. Blending them into the terrain is
     * what turns a plate boundary into a specific landform: convergence folds a range, divergence opens
     * a rift or a trench, transform shears a fault corridor.
     */
    public record Boundary(double convergent, double divergent, double transform) {
        public static final Boundary NONE = new Boundary(0, 0, 0);

        /** Total margin strength, useful when only "is there an active boundary here" matters. */
        public double total() { return convergent + divergent + transform; }
    }

    /** Share of plates that carry continental crust. A little more than half, so land is the norm. */
    private static final double CONTINENTAL_FRACTION = 0.62;
    /**
     * Band over which a head-on margin becomes fully convergent, in units of the closing <em>direction</em>
     * (the closing rate divided by the relative speed). Wider than 1 so a somewhat oblique collision
     * still counts as a collision rather than being split with transform.
     */
    private static final double CONVERGENCE_BAND = 1.2;
    /**
     * |closing direction| below which a boundary may be taken over by transform motion. Above it the
     * margin keeps its convergent or divergent character even though it also shears.
     */
    private static final double TRANSFORM_DIRECTION_CUTOFF = 0.35;

    /** The properties of a plate, derived once from its id. */
    public static Plate plate(long id) {
        boolean continental = unit(id, 0x9E37L) < CONTINENTAL_FRACTION;
        double elevationRoll = unit(id, 0x51EDL);
        double angleRoll = unit(id, 0x2545L);
        double speedRoll = unit(id, 0xC2B2L);
        double ageRoll = unit(id, 0xA9E3L);
        double resistanceRoll = unit(id, 0x7F4AL);

        double angle = angleRoll * Math.PI * 2.0;
        // The velocity magnitude IS the plate's activity: how fast it moves. There is deliberately no
        // separate activity property, because the tectonic_activity setting already scales margin
        // vigour globally and a second, correlated knob would only double-count it.
        double velocityX = Math.cos(angle) * speedRoll;
        double velocityZ = Math.sin(angle) * speedRoll;

        // Old crust is cooler and denser, so it sits lower and resists erosion better. Continental
        // crust is thicker, so it stands higher than oceanic crust of the same age - which is what makes
        // a continental/oceanic margin a subduction zone rather than a fold belt. Both effects are baked
        // into the two properties below so that crust age is not carried around unread.
        double age = ageRoll;
        double baseElevation = continental
                ? 30.0 + 90.0 * elevationRoll - 40.0 * age
                : -20.0 - 70.0 * elevationRoll - 40.0 * age;
        double resistance = (0.30 + 0.70 * resistanceRoll) * (0.85 + 0.30 * age);

        return new Plate(id, continental, baseElevation, velocityX, velocityZ, Math.min(1.0, resistance));
    }

    /**
     * Classifies the margin between two plates from their relative velocity and the boundary normal.
     *
     * @param normalX boundary normal, pointing from {@code a} towards {@code b}; need not be unit length
     * @param normalZ boundary normal, pointing from {@code a} towards {@code b}; need not be unit length
     * @return the three weights, summing to one, or {@link Boundary#NONE} for a degenerate normal
     */
    public static Boundary classify(Plate a, Plate b, double normalX, double normalZ) {
        double length = Math.sqrt(normalX * normalX + normalZ * normalZ);
        if (!(length > 1e-9)) return Boundary.NONE;
        double nx = normalX / length;
        double nz = normalZ / length;

        double relativeX = b.velocityX() - a.velocityX();
        double relativeZ = b.velocityZ() - a.velocityZ();
        // Along the normal; negated so a positive number means the plates are closing on each other.
        double closing = -(relativeX * nx + relativeZ * nz);
        // Along the boundary itself: the shearing part of the motion.
        double shear = Math.abs(relativeX * -nz + relativeZ * nx);

        double motion = Math.sqrt(relativeX * relativeX + relativeZ * relativeZ) + 1e-9;
        // Classify on the DIRECTION of relative motion, not its speed: a slow collision is still a
        // collision. Using the raw closing rate made every range depend on how fast the two plates
        // happened to be drifting, which systematically halved the fold amplitude because most random
        // velocity pairs close slowly. How fast plates move sets how ACTIVE a margin is (the activity
        // property); the direction sets which KIND of margin it is.
        double closingDirection = closing / motion;
        double convergent = sstep(clamp(closingDirection / CONVERGENCE_BAND + 0.5));
        double divergent = 1.0 - convergent;

        double tangentialShare = clamp(shear / motion);
        double normalShare = clamp(Math.abs(closingDirection) / TRANSFORM_DIRECTION_CUTOFF);
        double transform = tangentialShare * (1.0 - normalShare);

        // Rebalance so the three always sum to one.
        double nonTransform = 1.0 - transform;
        return new Boundary(convergent * nonTransform, divergent * nonTransform, transform);
    }

    /**
     * How oceanic a margin is: 0 when both plates are continental, 0.5 at a continental/oceanic margin
     * (a subduction zone), 1 when both are oceanic (an island arc with a trench in front of it).
     */
    public static double oceanicFraction(Plate a, Plate b) {
        return ((a.continental() ? 0.0 : 1.0) + (b.continental() ? 0.0 : 1.0)) * 0.5;
    }

    /** Uniform 0..1 drawn from the plate id and a salt, so each property is independent. */
    private static double unit(long id, long salt) {
        long h = mix(id ^ (salt * 0x9E3779B97F4A7C15L));
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    /** 64-bit avalanche finalizer, shared with the other caches. */
    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double sstep(double v) {
        double t = clamp(v);
        return t * t * (3 - 2 * t);
    }
}
