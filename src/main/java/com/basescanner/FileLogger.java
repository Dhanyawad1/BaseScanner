package com.basescanner;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes discovered base claims to a text file in the .minecraft directory.
 * Deduplicates entries so the same chunk is never written twice.
 *
 * Output format:
 *   FactionName | X | Z | Dimension | Timestamp
 */
public class FileLogger {

    private PrintWriter writer;
    private int totalFound = 0;
    private File logFile;

    // Deduplication: key = "X_Z"
    private final Set<String> logged = new HashSet<>();

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    public void open(String dimension) {
        try {
            File mcDir = Minecraft.getMinecraft().mcDataDir;
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            logFile = new File(mcDir, "BaseScanner_" + dimension + "_" + ts + ".txt");

            writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)));
            writeLine("=================================================");
            writeLine("  PIKA NETWORK OP-FACTIONS BASE SCANNER LOG");
            writeLine("  Dimension : " + dimension.toUpperCase());
            writeLine("  Started   : " + new Date());
            writeLine("  Format    : FactionName | X | Z | Dimension | Time");
            writeLine("=================================================");
            writer.flush();

            totalFound = 0;
            logged.clear();
        } catch (IOException e) {
            System.err.println("[BaseScanner] Failed to open log file: " + e.getMessage());
        }
    }

    /**
     * Logs a blue base claim.
     * Silently skips if this coordinate was already logged (dedup).
     */
    public void log(String factionName, int x, int z, String dimension) {
        if (writer == null) return;

        String key = x + "_" + z;
        if (logged.contains(key)) return; // already logged
        logged.add(key);

        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        writeLine(factionName + " | " + x + " | " + z + " | " + dimension + " | " + ts);
        writer.flush();
        totalFound++;
    }

    public void close() {
        if (writer == null) return;
        writeLine("=================================================");
        writeLine("  SCAN COMPLETE — " + totalFound + " unique base claims found");
        writeLine("=================================================");
        writer.flush();
        writer.close();
        writer = null;
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public int getTotalFound() {
        return totalFound;
    }

    public String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "not opened";
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private void writeLine(String line) {
        if (writer != null) writer.println(line);
    }
}
