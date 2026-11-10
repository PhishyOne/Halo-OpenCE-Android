package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Small, user-initiated debug report for intermittent co-op/LAN issues.
 * No config.toml, keys, accounts, chats or raw game assets are exported.
 * The file is written only to the Android document destination the player
 * chooses. Players should review the report before sharing it with anyone.
 */
final class HaloDiagnostics {
    private static final int MAX_LOG_TAIL = 160 * 1024;
    private static final int MAX_REPORTED_LINES = 160;
    private static final int MAX_LINE = 250;
    private static final int MAX_FILE_NAMES = 48;
    private static final Pattern RELEVANT = Pattern.compile(
        "(?i)(co-op:|network objects|distributed:|respawn|spectat|"
        + "cache file|game_load\\(\\)|map sharing|network connection|"
        + "network went down|objects:|failed to load|host has local player)");
    private static final Pattern PRIVATE_LINE = Pattern.compile(
        "(?i)(password|authorization|bearer|secret|token|credential|"
        + "hardware.id|session.key|invitation.key)");
    private static final Pattern IP = Pattern.compile(
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private HaloDiagnostics() { }

    private static File safeRegular(File directory, String name) {
        if (directory == null || !directory.isDirectory())
            return null;
        File file = new File(directory, name);
        return file.isFile() && !Files.isSymbolicLink(file.toPath())
            ? file : null;
    }

    private static List<String> selectLogLines(File file) throws IOException {
        List<String> relevant = new ArrayList<>();
        if (file == null)
            return relevant;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0, length - MAX_LOG_TAIL);
            input.seek(start);
            byte[] bytes = new byte[(int)(length - start)];
            input.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\\r?\\n", -1);
            int from = start > 0 ? 1 : 0; // skip partial first line
            for (int i = from; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || PRIVATE_LINE.matcher(line).find() ||
                    !RELEVANT.matcher(line).find())
                    continue;
                if (line.length() > MAX_LINE)
                    line = line.substring(0, MAX_LINE) + "...";
                relevant.add(EMAIL.matcher(IP.matcher(line)
                    .replaceAll("[IP]")).replaceAll("[email]"));
            }
        }
        if (relevant.size() > MAX_REPORTED_LINES)
            relevant = new ArrayList<>(relevant.subList(
                relevant.size() - MAX_REPORTED_LINES, relevant.size()));
        return relevant;
    }

    static void write(File root, OutputStream output) throws IOException {
        if (root == null || !root.isDirectory() || output == null)
            throw new IOException("Halo storage or report destination is unavailable.");
        StringBuilder report = new StringBuilder(40 * 1024);
        report.append("Halo local diagnostic report\n");
        report.append("Generated: ").append(Instant.now().toString()).append('\n');
        report.append("This report was saved at the player's request.\n");
        report.append("Check it for private information before sending it.\n\n");

        for (String directoryName : new String[] {"maps", "custom_maps"}) {
            report.append("## ").append(directoryName).append(" inventory\n");
            File directory = new File(root, directoryName);
            File[] items = directory.isDirectory() &&
                !Files.isSymbolicLink(directory.toPath()) ? directory.listFiles() : null;
            if (items == null) {
                report.append("(missing or unavailable)\n");
                continue;
            }
            int count = 0;
            long bytes = 0;
            for (File item : items)
                if (item.isFile() && !Files.isSymbolicLink(item.toPath()) &&
                    item.getName().toLowerCase(Locale.ROOT).endsWith(".map")) {
                    count++;
                    bytes += item.length();
                }
            report.append(count).append(" .map files, ")
                .append(bytes / 1048576).append(" MiB total\n");
            int listed = 0;
            for (File item : items) {
                if (listed >= MAX_FILE_NAMES)
                    break;
                if (item.isFile() && !Files.isSymbolicLink(item.toPath()) &&
                    item.getName().toLowerCase(Locale.ROOT).endsWith(".map")) {
                    report.append("  ")
                        .append(item.getName().replaceAll("[\\r\\n]", "?"))
                        .append(" (").append(item.length()).append(" bytes)\n");
                    listed++;
                }
            }
            if (listed < count)
                report.append("  ... more maps omitted\n");
        }

        report.append("\n## Relevant recent game log lines\n");
        int found = 0;
        for (String name : new String[] {"debug_previous.txt", "debug.txt"}) {
            File log = safeRegular(root, name);
            if (log == null)
                log = safeRegular(new File(root, "logs"), name);
            if (log == null)
                continue;
            List<String> lines = selectLogLines(log);
            report.append("-- ").append(name).append(": ")
                .append(lines.size()).append(" relevant lines --\n");
            for (String line : lines) {
                report.append(line).append('\n');
                found++;
            }
        }
        if (found == 0)
            report.append("(No qualifying log lines were found.)\n");
        report.append("\nNo config.toml, credentials, or map contents included.\n");
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128 * 1024)
            throw new IOException("Diagnostic report exceeds safe output limit.");
        output.write(bytes);
        output.flush();
    }
}
