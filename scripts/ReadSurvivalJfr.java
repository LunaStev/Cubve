import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming local JFR summary; no profiler data is uploaded. */
class ReadSurvivalJfr {
    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static String top(Map<String, Long> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(12)
            .map(e -> "{\"frame\":" + quote(e.getKey()) + ",\"samples\":" + e.getValue() + "}")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    static String hotPaths(Map<String, Long> counts) {
        Map<String, Long> paths = new HashMap<>();
        counts.forEach((frame, count) -> {
            if ((frame.startsWith("net.minecraft.world.") || frame.startsWith("ca.spottedleaf.moonrise."))
                && !frame.contains("$$Lambda")) paths.put(frame, count);
        });
        return top(paths);
    }
    public static void main(String[] args) throws Exception {
        Instant start = Instant.ofEpochMilli(Long.parseLong(args[1]));
        Instant end = Instant.ofEpochMilli(Long.parseLong(args[2]));
        List<Double> ticks = new ArrayList<>();
        Map<String, Long> mainFrames = new HashMap<>(), allFrames = new HashMap<>();
        long samples=0, mainSamples=0, generated=0, gc=0;
        double gcMs=0;
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Instant timestamp = event.getStartTime();
                if (timestamp.isBefore(start) || !timestamp.isBefore(end)) continue;
                switch (event.getEventType().getName()) {
                    case "cubve.ServerTick" -> ticks.add(event.getLong("durationNanos") / 1_000_000.0);
                    case "minecraft.ChunkGeneration" -> generated++;
                    case "jdk.GarbageCollection" -> { gc++; gcMs += event.getDuration().toNanos()/1_000_000.0; }
                    case "jdk.ExecutionSample" -> {
                        RecordedStackTrace trace = event.getStackTrace();
                        if (trace == null) continue;
                        RecordedThread thread = event.getThread("sampledThread");
                        boolean main = thread != null && "Server thread".equals(thread.getJavaName());
                        samples++; if (main) mainSamples++;
                        Set<String> seen = new HashSet<>();
                        for (RecordedFrame frame : trace.getFrames()) {
                            RecordedMethod method = frame.getMethod();
                            String name = method.getType().getName()+"."+method.getName();
                            if (seen.add(name)) {
                                allFrames.merge(name, 1L, Long::sum);
                                if (main) mainFrames.merge(name, 1L, Long::sum);
                            }
                        }
                    }
                }
            }
        }
        Collections.sort(ticks);
        double mean = ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = ticks.isEmpty()?0:ticks.get(Math.min(ticks.size()-1,(int)Math.ceil(ticks.size()*.95)-1));
        double p99 = ticks.isEmpty()?0:ticks.get(Math.min(ticks.size()-1,(int)Math.ceil(ticks.size()*.99)-1));
        double max = ticks.isEmpty()?0:ticks.get(ticks.size()-1);
        System.out.printf(Locale.ROOT,
            "{\"ticks\":%d,\"meanMs\":%.5f,\"p95Ms\":%.5f,\"p99Ms\":%.5f,\"maxMs\":%.5f,\"ticksOver50Ms\":%d,\"executionSamples\":%d,\"mainThreadSamples\":%d,\"chunkStageEvents\":%d,\"gcCount\":%d,\"gcMs\":%.5f,\"mainThreadFrames\":%s,\"allThreadFrames\":%s,\"mainHotPathFrames\":%s,\"allHotPathFrames\":%s}%n",
            ticks.size(),mean,p95,p99,max,ticks.stream().filter(t -> t > 50).count(),samples,mainSamples,generated,gc,gcMs,top(mainFrames),top(allFrames),hotPaths(mainFrames),hotPaths(allFrames));
    }
}
