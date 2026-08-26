package org.claudecodestudio.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.claudecodestudio.Activator;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Manages the Claude Code CLI process.
 *
 * Each user message is a separate process invocation using
 * {@code claude --print --output-format stream-json}.
 *
 * Conversation continuity is maintained via per-context session IDs stored in
 * {@code ~/.claude/studio-sessions.properties}. The first message in a context
 * uses {@code --session-id <uuid>} to establish a known ID; subsequent messages
 * use {@code --resume <uuid>} to continue that exact session — surviving Studio
 * restarts, reboots, and crashes.
 *
 * The context key is a sorted, pipe-delimited list of project names
 * (e.g. {@code "ss-datalake-sapi|ss-datalake-xapi"}), so each unique
 * combination of projects gets its own persistent session.
 *
 * Call {@link #newSession(String)} to start a fresh conversation for a context
 * (e.g. when the user clicks New Chat).
 */
public class ClaudeProcessManager {

    private static final String SESSION_STORE =
            System.getProperty("user.home") + "/.claude/studio-sessions.properties";

    /**
     * Appended to Claude's default system prompt on every invocation.
     * Instructs Claude to keep CLAUDE.md up to date with project facts so
     * that future sessions can pick up without re-exploration.
     * No shell-special characters — safe to wrap in single quotes.
     */
    private static final String SYSTEM_PROMPT =
            "After completing any significant action such as editing a file, pushing to git, " +
            "or making a key technical decision, update CLAUDE.md in the project root with a " +
            "concise note capturing what was done and why. Focus on durable facts: API structures, " +
            "field mappings, flow decisions, and anything that would help you pick up this project " +
            "in a future session without re-exploring. Omit conversational filler.";

    private volatile Process currentProcess;

    // -------------------------------------------------------------------------
    // Launch
    // -------------------------------------------------------------------------

    /**
     * Launches {@code claude --print} for one user message.
     *
     * @param userMessage      the text prompt to send
     * @param workingDirectory absolute path of the project directory, or null for home
     * @param contextKey       sorted pipe-delimited project names identifying this context
     * @return the started Process
     * @throws IOException if the binary cannot be launched
     */
    public synchronized Process launch(String userMessage, String workingDirectory,
            String contextKey) throws IOException {

        String binary = resolveBinary();
        File workDir = resolveWorkDir(workingDirectory);
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        String sessionId = existingSessionId(contextKey);
        boolean isNew = (sessionId == null);
        if (isNew) sessionId = createSessionId(contextKey);

        List<String> cmd = new ArrayList<>();

        if (isWindows) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(binary);
        } else {
            // On macOS, launch via a login shell so that the user's shell profile
            // (.zshrc, .bash_profile, etc.) is sourced and Vertex AI credentials
            // are available to the child process.
            StringBuilder shellCmd = new StringBuilder();
            shellCmd.append(binary);
            shellCmd.append(" -p --output-format stream-json --verbose --dangerously-skip-permissions");

            if (isNew) {
                shellCmd.append(" --session-id ").append(sessionId);
            } else {
                shellCmd.append(" --resume ").append(sessionId);
            }

            // Single-quoted — SYSTEM_PROMPT must not contain single quotes.
            shellCmd.append(" --append-system-prompt '").append(SYSTEM_PROMPT).append("'");

            IPreferenceStore store = Activator.getDefault().getPreferenceStore();

            // Single-quote the model — the "[1m]" suffix contains glob chars
            // that zsh would otherwise try to expand. Model has no single quotes.
            String model = store.getString(Activator.PREF_MODEL);
            if (model != null && !model.isBlank()) {
                shellCmd.append(" --model '").append(model.trim()).append("'");
            }

            String extraArgs = store.getString(Activator.PREF_EXTRA_ARGS);
            if (extraArgs != null && !extraArgs.isBlank()) {
                shellCmd.append(" ").append(extraArgs.trim());
            }

            String shell = new File("/bin/zsh").exists() ? "/bin/zsh" : "/bin/bash";
            cmd.add(shell);
            cmd.add("-l");
            cmd.add("-c");
            cmd.add(shellCmd.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("NO_COLOR", "1");
            env.put("TERM", "dumb");
            currentProcess = pb.start();
            try (OutputStream stdin = currentProcess.getOutputStream()) {
                stdin.write(userMessage.getBytes(StandardCharsets.UTF_8));
            }
            return currentProcess;
        }

        // Windows-only path continues below
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--dangerously-skip-permissions");

        if (isNew) {
            cmd.add("--session-id");
            cmd.add(sessionId);
        } else {
            cmd.add("--resume");
            cmd.add(sessionId);
        }

        cmd.add("--append-system-prompt");
        cmd.add(SYSTEM_PROMPT);

        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        String model = store.getString(Activator.PREF_MODEL);
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model.trim());
        }

        String extraArgs = store.getString(Activator.PREF_EXTRA_ARGS);
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String arg : extraArgs.trim().split("\\s+")) {
                cmd.add(arg);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");

        currentProcess = pb.start();
        try (OutputStream stdin = currentProcess.getOutputStream()) {
            stdin.write(userMessage.getBytes(StandardCharsets.UTF_8));
        }

        return currentProcess;
    }

    // -------------------------------------------------------------------------
    // Session control
    // -------------------------------------------------------------------------

    /** Kills the currently-running process. Does not affect stored session IDs. */
    public synchronized void stop() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) p.destroyForcibly();
        currentProcess = null;
    }

    /**
     * Stops the process and deletes the stored session ID for this context.
     * The next {@link #launch} call will start a brand-new conversation.
     */
    public synchronized void newSession(String contextKey) {
        stop();
        clearSessionId(contextKey);
    }

    public boolean isRunning() {
        Process p = currentProcess;
        return p != null && p.isAlive();
    }

    // -------------------------------------------------------------------------
    // Session ID persistence
    // -------------------------------------------------------------------------

    /**
     * Returns the stored session ID for this context, or null if none exists yet.
     */
    private String existingSessionId(String contextKey) {
        return loadStore().getProperty(normalizeKey(contextKey));
    }

    /**
     * Generates a new UUID for this context, persists it, and returns it.
     */
    private String createSessionId(String contextKey) {
        String id = UUID.randomUUID().toString();
        Properties props = loadStore();
        props.setProperty(normalizeKey(contextKey), id);
        saveStore(props);
        return id;
    }

    private void clearSessionId(String contextKey) {
        Properties props = loadStore();
        props.remove(normalizeKey(contextKey));
        saveStore(props);
    }

    private static String normalizeKey(String contextKey) {
        return (contextKey == null || contextKey.isBlank()) ? "__default__" : contextKey;
    }

    private Properties loadStore() {
        Properties props = new Properties();
        File f = new File(SESSION_STORE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                props.load(in);
            } catch (IOException ignored) {}
        }
        return props;
    }

    private void saveStore(Properties props) {
        File f = new File(SESSION_STORE);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            props.store(out, "Claude Code Studio — session IDs per context");
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Binary / working directory resolution
    // -------------------------------------------------------------------------

    public String resolveBinary() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String configured = store.getString(Activator.PREF_CLAUDE_PATH);
        if (configured != null && !configured.isBlank()) return configured;

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "claude";
        }

        String home = System.getProperty("user.home", "");
        String[] candidates = {
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            home + "/.local/bin/claude",
            home + "/.npm-global/bin/claude",
            home + "/node_modules/.bin/claude",
        };
        for (String path : candidates) {
            if (new File(path).canExecute()) return path;
        }
        return "claude";
    }

    private File resolveWorkDir(String workingDirectory) {
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            File f = new File(workingDirectory);
            if (f.isDirectory()) return f;
        }
        return new File(System.getProperty("user.home"));
    }
}
