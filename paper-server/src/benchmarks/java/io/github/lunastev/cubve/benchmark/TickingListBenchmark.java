package io.github.lunastev.cubve.benchmark;

import ca.spottedleaf.moonrise.common.list.ShortList;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Hot mutation control for deferred allocation of a section's ticking-block index. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class TickingListBenchmark {
    private ShortList list;

    @Setup(Level.Trial)
    public void setup() {
        this.list = new ShortList();
        for (short i = 0; i < 4096; i++) this.list.add(i);
    }

    @Benchmark
    @OperationsPerInvocation(4096)
    public void removeAndRestore(Blackhole blackhole) {
        for (short i = 0; i < 4096; i++) {
            blackhole.consume(this.list.remove(i));
            blackhole.consume(this.list.add(i));
        }
    }
}
