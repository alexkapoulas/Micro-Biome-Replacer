package com.example.alexthundercook.microbiomereplacer.profiling;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR custom event for tracking chunk processing performance.
 *
 * This event is emitted once per chunk during biome replacement processing.
 * Use JDK Mission Control or async-profiler to analyze the recorded data.
 *
 * Example JFR recording:
 * java -XX:StartFlightRecording=filename=mbr.jfr,settings=profile ...
 */
@Name("microbiomereplacer.ChunkProcessing")
@Label("Chunk Processing")
@Category({"Micro Biome Replacer", "World Generation"})
@Description("Records the processing time for micro biome replacement in a chunk")
@StackTrace(false)  // Disable stack traces for low overhead
public class ChunkProcessingEvent extends Event {

    @Label("Chunk X")
    @Description("The X coordinate of the chunk being processed")
    public int chunkX;

    @Label("Chunk Z")
    @Description("The Z coordinate of the chunk being processed")
    public int chunkZ;

    @Label("Duration")
    @Description("Time spent processing this chunk")
    @Timespan(Timespan.NANOSECONDS)
    public long durationNanos;

    @Label("Positions Processed")
    @Description("Number of biome positions examined in this chunk")
    public int positionsProcessed;

    @Label("Replacements Made")
    @Description("Number of biome replacements applied in this chunk")
    public int replacementsMade;

    @Label("Skipped (Homogeneous)")
    @Description("True if chunk was skipped due to homogeneous biome check")
    public boolean skippedHomogeneous;
}
