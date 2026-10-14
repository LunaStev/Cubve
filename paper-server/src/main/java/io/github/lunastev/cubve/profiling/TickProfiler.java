package io.github.lunastev.cubve.profiling;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Opt-in local profiling; the final flag lets the JIT remove the normal-server path. */
public final class TickProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("cubve.profile.ticks");

    private TickProfiler() {
    }

    public static void recordTick(final long durationNanos) {
        if (ENABLED) {
            final TickEvent event = new TickEvent();
            if (event.isEnabled()) {
                event.durationNanos = durationNanos;
                event.commit();
            }
        }
    }

    @Name("cubve.ServerTick")
    @Label("Cubve server tick")
    @Category({"Cubve", "Ticking"})
    @StackTrace(false)
    public static final class TickEvent extends Event {
        @Label("Tick work time")
        @Timespan(Timespan.NANOSECONDS)
        public long durationNanos;
    }
}
