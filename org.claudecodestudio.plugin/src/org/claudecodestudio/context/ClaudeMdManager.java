package org.claudecodestudio.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;

/**
 * Manages the dynamic project context block written into CLAUDE.md.
 *
 * The block is delimited by BEGIN/END markers so this class never touches
 * content outside those markers. If no CLAUDE.md exists, one is created.
 */
public class ClaudeMdManager {

    static final String MARKER_BEGIN =
            "<!-- BEGIN: Claude Code Studio - managed automatically, do not edit -->";
    static final String MARKER_END =
            "<!-- END: Claude Code Studio -->";

    private static final Pattern BLOCK_PATTERN = Pattern.compile(
            Pattern.quote(MARKER_BEGIN) + ".*?" + Pattern.quote(MARKER_END),
            Pattern.DOTALL);

    /**
     * Writes (or updates) the managed context block in CLAUDE.md located at
     * {@code directoryPath}. The block lists all projects in
     * {@code selectedProjects}.
     *
     * @param directoryPath   absolute path to the directory containing CLAUDE.md
     * @param selectedProjects projects currently in scope
     */
    public void updateContext(String directoryPath, Set<IProject> selectedProjects) {
        if (directoryPath == null || directoryPath.isBlank()) return;

        Path claudeMd = Paths.get(directoryPath, "CLAUDE.md");

        String existingContent = "";
        if (Files.exists(claudeMd)) {
            try {
                existingContent = Files.readString(claudeMd, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logError("Failed to read CLAUDE.md: " + e.getMessage());
                return;
            }
        }

        String block = buildBlock(selectedProjects);
        String newContent = replaceOrAppendBlock(existingContent, block);

        try {
            // Atomic write via a temp file to avoid partial writes
            Path tempFile = claudeMd.getParent()
                    .resolve(".CLAUDE.md.claudecodestudio.tmp");
            Files.writeString(tempFile, newContent, StandardCharsets.UTF_8);
            Files.move(tempFile, claudeMd,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logError("Failed to write CLAUDE.md: " + e.getMessage());
        }
    }

    /**
     * Removes the managed block from CLAUDE.md (called when all context is
     * cleared, e.g. on plugin shutdown or explicit deselection).
     */
    public void clearContext(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) return;

        Path claudeMd = Paths.get(directoryPath, "CLAUDE.md");
        if (!Files.exists(claudeMd)) return;

        try {
            String content = Files.readString(claudeMd, StandardCharsets.UTF_8);
            Matcher m = BLOCK_PATTERN.matcher(content);
            if (m.find()) {
                String cleaned = m.replaceAll("").stripTrailing();
                Files.writeString(claudeMd, cleaned.isEmpty() ? "" : cleaned + "\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logError("Failed to clear CLAUDE.md block: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    String buildBlock(Set<IProject> projects) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_BEGIN).append("\n");
        sb.append("## Active Project Context (Claude Code Studio Plugin)\n");
        sb.append("The following Mule projects are currently in scope:\n");
        for (IProject project : projects) {
            String location = project.getLocation() != null
                    ? project.getLocation().toOSString()
                    : project.getName();
            sb.append("- ").append(location).append("\n");
        }
        sb.append(MARKER_END);
        return sb.toString();
    }

    String replaceOrAppendBlock(String existing, String block) {
        Matcher m = BLOCK_PATTERN.matcher(existing);
        if (m.find()) {
            return m.replaceAll(Matcher.quoteReplacement(block));
        }
        // No existing block: append with a blank line separator
        if (existing.isBlank()) {
            return block + "\n";
        }
        String trimmed = existing.stripTrailing();
        return trimmed + "\n\n" + block + "\n";
    }

    private void logError(String message) {
        System.err.println("[ClaudeCodeStudio] ClaudeMdManager: " + message);
    }
}
