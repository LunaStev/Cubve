import java.nio.file.*;
import jdk.jfr.Configuration;
class MakeSurvivalJfc {
    public static void main(String[] args) throws Exception {
        String additions = "<event name=\"minecraft.ChunkGeneration\"><setting name=\"enabled\">true</setting></event>"
            + "<event name=\"cubve.ServerTick\"><setting name=\"enabled\">true</setting><setting name=\"stackTrace\">false</setting></event>";
        String profile = Configuration.getConfiguration("profile").getContents();
        Files.writeString(Path.of(args[0]), profile.replace("</configuration>", additions + "</configuration>"));
    }
}
