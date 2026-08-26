package org.claudecodestudio.views;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.claudecodestudio.Activator;
import org.claudecodestudio.context.ProjectContextManager;
import org.claudecodestudio.process.ClaudeProcessManager;
import org.claudecodestudio.ui.ContextSelectorAction;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * The Claude Code chat view.
 *
 * Sends user messages to {@code claude --print --output-format stream-json}
 * and renders the JSON responses in a native SWT UI. No terminal emulation,
 * no PTY — just structured I/O and clean Eclipse-native rendering.
 *
 * Layout:
 *   - Header label showing active project context
 *   - Scrollable StyledText for conversation history
 *   - Multi-line Text input + Send button at the bottom
 *   - Toolbar: context selector | new conversation | preferences
 */
public class ClaudeTerminalView extends ViewPart {

    public static final String ID = "org.claudecodestudio.views.ClaudeTerminalView";

    private ClaudeProcessManager processManager;
    private ProjectContextManager contextManager;
    private ContextSelectorAction contextSelectorAction;
    private String currentWorkingDir;

    // Widgets
    private Label headerLabel;
    private StyledText conversationText;
    private Text inputText;
    private Button sendButton;

    // True while a claude process is running; used to toggle Send ↔ Stop.
    private volatile boolean isWaiting = false;

    // Auto-summary state
    private ScheduledExecutorService summaryScheduler;
    private volatile long lastActivityTime = 0;
    private volatile long lastSummaryTime = 0;
    private boolean timerStarted = false;

    private static final int SUMMARY_INTERVAL_MINUTES = 30; // fallback if prefs unavailable
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Tab icons — swapped on part activation/deactivation
    private Image activeTitleImage;
    private Image inactiveTitleImage;
    private org.eclipse.ui.IPartListener2 partListener;

    // SWT resources (must be disposed)
    private Font codeFont;
    private Color userColor;
    private Color claudeColor;
    private Color codeBgColor;
    private Color codeTextColor;
    private Color sendBgColor;
    private Color stopBgColor;
    private Color buttonFgColor;
    private Color sashBgColor;
    private Color sashGripColor;
    private Color timestampColor;
    private Color saveHistoryBgColor;
    private Color newChatBgColor;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Sentinel text inserted while waiting for a response
    private static final String THINKING_TEXT = "\u2026 Claude is thinking\u2026\n";

    // -------------------------------------------------------------------------
    // ViewPart lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void createPartControl(Composite parent) {
        processManager = new ClaudeProcessManager();
        contextManager = new ProjectContextManager();

        getSite().getWorkbenchWindow()
                 .getSelectionService()
                 .addSelectionListener(contextManager);
        contextManager.setContextChangeListener(
                (projects, workingDir, isExplicit) -> onContextChanged(projects, workingDir, isExplicit));

        currentWorkingDir = contextManager.resolveWorkingDirectory();

        buildResources(parent.getDisplay());
        buildUI(parent);
        buildToolbar();
        initTitleIcons(parent.getDisplay());
        registerPartListener();
    }

    @Override
    public void setFocus() {
        if (inputText != null && !inputText.isDisposed()) {
            inputText.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (partListener != null && getSite() != null && getSite().getPage() != null) {
            getSite().getPage().removePartListener(partListener);
        }
        if (getSite() != null && getSite().getWorkbenchWindow() != null) {
            getSite().getWorkbenchWindow()
                     .getSelectionService()
                     .removeSelectionListener(contextManager);
        }
        // Best-effort shutdown summary before stopping
        if (lastActivityTime > lastSummaryTime && currentWorkingDir != null) {
            triggerAutoSummary();
        }
        if (summaryScheduler != null) {
            summaryScheduler.shutdownNow();
        }
        processManager.stop();
        if (activeTitleImage != null)   activeTitleImage.dispose();
        if (inactiveTitleImage != null) inactiveTitleImage.dispose();
        if (codeFont != null)      codeFont.dispose();
        if (userColor != null)     userColor.dispose();
        if (claudeColor != null)   claudeColor.dispose();
        if (codeBgColor != null)   codeBgColor.dispose();
        if (codeTextColor != null) codeTextColor.dispose();
        if (sendBgColor != null)   sendBgColor.dispose();
        if (stopBgColor != null)   stopBgColor.dispose();
        if (buttonFgColor != null) buttonFgColor.dispose();
        if (sashBgColor != null)    sashBgColor.dispose();
        if (sashGripColor != null)  sashGripColor.dispose();
        if (timestampColor != null)     timestampColor.dispose();
        if (saveHistoryBgColor != null) saveHistoryBgColor.dispose();
        if (newChatBgColor != null)     newChatBgColor.dispose();
        super.dispose();
    }

    // -------------------------------------------------------------------------
    // Tab icon management
    // -------------------------------------------------------------------------

    private void initTitleIcons(Display display) {
        try {
            java.net.URL iconUrl = Activator.getDefault().getBundle().getEntry("icons/claude.png");
            ImageData srcData = new ImageData(iconUrl.openStream());

            // Active tab: transparent PNG as-is
            activeTitleImage = new Image(display, srcData);

            // Inactive tab: replace transparent pixels with gray (241,241,241) directly
            // in the pixel data — avoids relying on GC alpha compositing which
            // doesn't work correctly on Windows SWT.
            ImageData inactiveData = (ImageData) srcData.clone();
            int grayPixel = inactiveData.palette.getPixel(new RGB(232, 233, 235));
            for (int y = 0; y < inactiveData.height; y++) {
                for (int x = 0; x < inactiveData.width; x++) {
                    int alpha = inactiveData.getAlpha(x, y);
                    if (alpha < 128) {
                        inactiveData.setPixel(x, y, grayPixel);
                    }
                    inactiveData.setAlpha(x, y, 255); // fully opaque — no alpha needed
                }
            }
            inactiveTitleImage = new Image(display, inactiveData);

            // Start with inactive until the part gets focus
            setTitleImage(inactiveTitleImage);
        } catch (Exception ignored) {}
    }

    private void registerPartListener() {
        partListener = new org.eclipse.ui.IPartListener2() {
            @Override
            public void partActivated(org.eclipse.ui.IWorkbenchPartReference ref) {
                if (ID.equals(ref.getId()) && activeTitleImage != null && !activeTitleImage.isDisposed())
                    Display.getDefault().asyncExec(() -> setTitleImage(activeTitleImage));
            }
            @Override
            public void partDeactivated(org.eclipse.ui.IWorkbenchPartReference ref) {
                if (ID.equals(ref.getId()) && inactiveTitleImage != null && !inactiveTitleImage.isDisposed())
                    Display.getDefault().asyncExec(() -> setTitleImage(inactiveTitleImage));
            }
            @Override public void partBroughtToTop(org.eclipse.ui.IWorkbenchPartReference ref) {}
            @Override public void partClosed(org.eclipse.ui.IWorkbenchPartReference ref) {}
            @Override public void partHidden(org.eclipse.ui.IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(org.eclipse.ui.IWorkbenchPartReference ref) {}
            @Override public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref) {}
            @Override public void partVisible(org.eclipse.ui.IWorkbenchPartReference ref) {}
        };
        getSite().getPage().addPartListener(partListener);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildResources(Display display) {
        FontData[] fd = display.getSystemFont().getFontData();
        int baseSize = fd[0].getHeight();
        codeFont      = new Font(display, new FontData("Courier New", baseSize, SWT.NORMAL));
        userColor     = new Color(display, 31,  97, 196);  // blue
        claudeColor   = new Color(display, 16, 128,  70);  // green
        codeBgColor   = new Color(display, 40,  44,  52);  // dark (One Dark-ish)
        codeTextColor = new Color(display, 220, 220, 220);  // near-white for code
        sendBgColor   = new Color(display,  34, 139,  34);  // forest green
        stopBgColor   = new Color(display, 200,  30,  30);  // red
        buttonFgColor = new Color(display, 255, 255, 255);  // white text
        sashBgColor   = new Color(display, 210, 220, 235);  // light blue-gray sash
        sashGripColor = new Color(display, 100, 120, 150);  // darker blue-gray grip dots
        timestampColor    = new Color(display, 150, 150, 150); // muted gray for timestamps
        saveHistoryBgColor = new Color(display,  70, 130, 180); // steel blue
        newChatBgColor     = new Color(display, 200, 130,   0); // amber
    }

    private void buildUI(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        // Header row: context label on the left, action buttons on the right
        Composite headerRow = new Composite(parent, SWT.NONE);
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginHeight = 2;
        headerLayout.marginWidth = 4;
        headerRow.setLayout(headerLayout);
        headerRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        headerLabel = new Label(headerRow, SWT.NONE);
        headerLabel.setText("Context: (none \u2014 open a project file to auto-select)");
        headerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button exportButton = new Button(headerRow, SWT.PUSH);
        exportButton.setText("Export");
        exportButton.setToolTipText("Export full conversation to a text file");
        exportButton.setBackground(saveHistoryBgColor);
        exportButton.setForeground(buttonFgColor);
        exportButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        exportButton.addListener(SWT.Selection, e -> exportHistory());

        Button archiveButton = new Button(headerRow, SWT.PUSH);
        archiveButton.setText("Archive");
        archiveButton.setToolTipText("Move chat history files to archive — next session starts fresh");
        archiveButton.setBackground(newChatBgColor);
        archiveButton.setForeground(buttonFgColor);
        archiveButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        archiveButton.addListener(SWT.Selection, e -> archiveHistory());

        // Conversation area — fills all available vertical space
        conversationText = new StyledText(parent,
                SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        conversationText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        conversationText.setEditable(false);
        conversationText.setMargins(8, 8, 8, 8);
        conversationText.setCaret(null); // hide caret — widget is read-only

        // Explicit sash divider placed directly between conversation and input
        final int SASH_HEIGHT = 6;
        final int MIN_INPUT_HEIGHT = 40;
        final int MIN_CONV_HEIGHT = 80;

        Sash divider = new Sash(parent, SWT.HORIZONTAL);
        GridData dividerGD = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dividerGD.heightHint = SASH_HEIGHT;
        divider.setLayoutData(dividerGD);

        divider.addPaintListener(e -> {
            org.eclipse.swt.graphics.Point size = divider.getSize();
            int width = size.x;
            int height = size.y;
            // Blue tint on the 50px centered grip area only
            int gripW = 50;
            int gripX = (width - gripW) / 2;
            e.gc.setBackground(sashBgColor);
            e.gc.fillRectangle(gripX, 0, gripW, height);
            // Three dots centered in the grip
            e.gc.setBackground(sashGripColor);
            int dotSize = 4;
            int gap = 6;
            int dotsW = 3 * dotSize + 2 * gap;
            int dotX = (width - dotsW) / 2;
            int dotY = (height - dotSize) / 2;
            for (int i = 0; i < 3; i++) {
                e.gc.fillOval(dotX + i * (dotSize + gap), dotY, dotSize, dotSize);
            }
        });

        // Input row — fixed height, expands when user drags the sash up
        final GridData inputRowGD = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputRowGD.heightHint = 80;
        Composite inputRow = new Composite(parent, SWT.NONE);
        inputRow.setLayoutData(inputRowGD);
        GridLayout inputLayout = new GridLayout(3, false);
        inputLayout.marginHeight = 4;
        inputRow.setLayout(inputLayout);

        // Drag handler — recomputes input height from the sash drop position
        divider.addListener(SWT.Selection, e -> {
            org.eclipse.swt.graphics.Rectangle parentArea = parent.getClientArea();
            int newInputHeight = parentArea.height - e.y - SASH_HEIGHT;
            newInputHeight = Math.max(MIN_INPUT_HEIGHT,
                    Math.min(newInputHeight, parentArea.height - MIN_CONV_HEIGHT - SASH_HEIGHT));
            inputRowGD.heightHint = newInputHeight;
            parent.layout();
        });

        inputText = new Text(inputRow, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        inputText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        inputText.setMessage("Type a message\u2026 (Enter to send, Shift+Enter for newline)");
        inputText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                        && (e.stateMask & (SWT.SHIFT | SWT.CTRL)) == 0) {
                    e.doit = false;
                    sendMessage();
                }
            }
        });

        Button attachButton = new Button(inputRow, SWT.PUSH);
        attachButton.setText("\uD83D\uDCCE"); // 📎
        attachButton.setToolTipText("Attach a file — inserts its path into your message");
        attachButton.setLayoutData(new GridData(SWT.CENTER, SWT.BOTTOM, false, false));
        attachButton.addListener(SWT.Selection, e -> attachFile());

        sendButton = new Button(inputRow, SWT.FLAT);
        sendButton.setText("Send");
        sendButton.setBackground(sendBgColor);
        sendButton.setForeground(buttonFgColor);
        GridData sendGD = new GridData(SWT.CENTER, SWT.BOTTOM, false, false);
        sendGD.widthHint = 64;
        sendGD.heightHint = 40;
        sendButton.setLayoutData(sendGD);
        sendButton.addListener(SWT.Selection, e -> {
            if (isWaiting) stopResponse();
            else sendMessage();
        });
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    private void sendMessage() {
        if (inputText == null || inputText.isDisposed()) return;
        String message = inputText.getText().trim();
        if (message.isEmpty()) return;

        inputText.setText("");
        lastActivityTime = System.currentTimeMillis();
        startSummaryTimer();
        setInputEnabled(false);

        appendUserMessage(message);
        appendThinking();

        final String workDir = currentWorkingDir;
        final String contextKey = computeContextKey();

        Thread t = new Thread(() -> {
            String response;
            try {
                response = launchAndRead(message, workDir, contextKey);
            } catch (IOException ex) {
                response = "[Error launching Claude Code]\n\n"
                        + ex.getMessage()
                        + "\n\nCheck the Claude CLI Path in Window \u2192 Preferences \u2192 Claude Code.";
            }

            final String finalResponse = response;
            Display.getDefault().asyncExec(() -> {
                if (conversationText == null || conversationText.isDisposed()) return;
                if (!isWaiting) return; // user already clicked Stop — discard
                removeThinking();
                appendClaudeMessage(finalResponse != null ? finalResponse : "(empty response)");
                lastActivityTime = System.currentTimeMillis();
                setInputEnabled(true);
                if (inputText != null && !inputText.isDisposed()) inputText.setFocus();
            });
        }, "claude-query");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Opens a file chooser and appends the selected file path to the input box.
     * Claude Code can then read the file using its built-in tools when the
     * message is sent.
     */
    private void attachFile() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Attach File");
        dialog.setFilterExtensions(new String[]{
            "*.xlsx;*.xls;*.csv", "*.pdf", "*.docx;*.doc", "*.txt;*.log;*.xml;*.json;*.yaml;*.yml", "*.*"
        });
        dialog.setFilterNames(new String[]{
            "Spreadsheets (xlsx, xls, csv)", "PDF", "Word Documents (docx, doc)",
            "Text & Config Files", "All Files"
        });
        String path = dialog.open();
        if (path == null) return;

        if (inputText == null || inputText.isDisposed()) return;
        String current = inputText.getText();
        String separator = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        inputText.setText(current + separator + path);
        inputText.setFocus();
        inputText.setSelection(inputText.getText().length());
    }

    /** Maximum time to wait for a claude response before giving up. */
    private long getResponseTimeoutMs() {
        if (Activator.getDefault() == null) return 300_000L;
        int minutes = Activator.getDefault().getPreferenceStore()
                .getInt(Activator.PREF_RESPONSE_TIMEOUT);
        return (minutes > 0 ? minutes : 5) * 60_000L;
    }

    /**
     * Result of reading a Claude response: the display text, plus a flag
     * indicating the failure was a stale {@code --resume} (the stored session
     * no longer exists on the CLI side) so the caller can self-heal.
     */
    private static final class Response {
        final String text;
        final boolean staleResume;
        Response(String text, boolean staleResume) {
            this.text = text;
            this.staleResume = staleResume;
        }
    }

    /**
     * Launches a query and, if it fails because the context's stored session
     * no longer exists on the CLI side, clears that session and retries once
     * with a brand-new conversation. Returns the final display text.
     */
    private String launchAndRead(String message, String workDir, String contextKey) throws IOException {
        Response r = readResponse(processManager.launch(message, workDir, contextKey));
        if (r.staleResume) {
            // The session this context pointed to was cleaned up / expired.
            // Drop it and retry fresh so the user isn't stuck on a dead session.
            processManager.newSession(contextKey);
            r = readResponse(processManager.launch(message, workDir, contextKey));
        }
        return r.text;
    }

    /**
     * Reads stdout from a {@code claude -p --output-format stream-json}
     * process and returns the final response text.
     *
     * Each stdout line is a JSON event. We collect the {@code result} field
     * from the terminal {@code {"type":"result",...}} event.
     * If no result event appears (e.g. startup error), the raw output is
     * returned so the user can see what went wrong.
     */
    private Response readResponse(Process process) throws IOException {
        String result = null;
        boolean staleResume = false;
        StringBuilder raw = new StringBuilder();
        long deadline = System.currentTimeMillis() + getResponseTimeoutMs();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                raw.append(line).append('\n');

                if (line.contains("\"type\":\"result\"")) {
                    if (line.contains("\"is_error\":true")) {
                        String err = extractFirstError(line);
                        // A stale --resume surfaces as "No conversation found
                        // with session ID: ...". Flag it so the caller retries.
                        if (err != null && err.contains("No conversation found")) {
                            staleResume = true;
                        }
                        result = err != null ? "[Claude Error]\n\n" + err
                                             : "[Claude returned an error — check the Claude CLI or try again.]";
                    } else {
                        String extracted = extractJsonString(line, "result");
                        if (extracted != null) result = extracted;
                    }
                }

                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly();
                    return new Response("[Timed out waiting for Claude response — the task may still have completed. Try asking Claude to continue or summarize what was done.]", false);
                }
            }
        }

        try {
            boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
        } catch (InterruptedException ignored) {}

        if (result == null) {
            String rawStr = raw.toString().trim();
            result = rawStr.isEmpty()
                    ? "[No response received]\n\nIs 'claude' on the PATH that Anypoint Studio sees?\n"
                    + "Try setting the full path in Window \u2192 Preferences \u2192 Claude Code."
                    : "[Could not parse a response — the task may still have completed. Try asking Claude to continue or summarize what was done.]";
        }

        return new Response(result, staleResume);
    }

    // -------------------------------------------------------------------------
    // StyledText rendering
    // -------------------------------------------------------------------------

    private void appendUserMessage(String message) {
        appendLabel("You", userColor);
        conversationText.append(message + "\n\n");
        scrollToBottom();
    }

    private void appendThinking() {
        int start = conversationText.getCharCount();
        conversationText.append(THINKING_TEXT);
        StyleRange sr = new StyleRange(start, THINKING_TEXT.length() - 1, null, null);
        sr.fontStyle = SWT.ITALIC;
        conversationText.setStyleRange(sr);
        scrollToBottom();
    }

    private void removeThinking() {
        String full = conversationText.getText();
        int idx = full.lastIndexOf(THINKING_TEXT);
        if (idx >= 0) {
            conversationText.replaceTextRange(idx, THINKING_TEXT.length(), "");
        }
    }

    private void appendClaudeMessage(String message) {
        appendLabel("Claude", claudeColor);
        appendMarkdown(message);
        conversationText.append("\n\n");
        scrollToBottom();
    }

    private void appendLabel(String label, Color color) {
        // Bold colored name
        int start = conversationText.getCharCount();
        String nameText = label + ": ";
        conversationText.append(nameText);
        StyleRange sr = new StyleRange(start, nameText.length(), color, null);
        sr.fontStyle = SWT.BOLD;
        conversationText.setStyleRange(sr);

        // Muted gray timestamp
        String ts = "[" + LocalDateTime.now().format(TIME_FMT) + "]  ";
        int tsStart = conversationText.getCharCount();
        conversationText.append(ts);
        StyleRange tsr = new StyleRange(tsStart, ts.length(), timestampColor, null);
        conversationText.setStyleRange(tsr);
    }

    /**
     * Appends markdown-ish text with basic code block rendering.
     *
     * Segments delimited by triple-backtick fences are rendered in monospace
     * with a dark background. Everything else is appended as plain text.
     */
    private void appendMarkdown(String markdown) {
        String[] parts = markdown.split("```", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) {
                // Normal text
                conversationText.append(parts[i]);
            } else {
                // Code block — strip optional language tag on first line
                String code = parts[i];
                int nl = code.indexOf('\n');
                if (nl >= 0) code = code.substring(nl + 1); // drop "java", "xml", etc.
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);

                // Pad with a leading newline if needed for visual separation
                if (!conversationText.getText().endsWith("\n")) {
                    conversationText.append("\n");
                }

                int start = conversationText.getCharCount();
                conversationText.append(code);
                int len = code.length();

                StyleRange sr = new StyleRange(start, len, codeTextColor, codeBgColor);
                sr.font = codeFont;
                conversationText.setStyleRange(sr);

                conversationText.append("\n");
            }
        }
    }

    private void scrollToBottom() {
        conversationText.setTopIndex(conversationText.getLineCount() - 1);
        org.eclipse.swt.widgets.ScrollBar vBar = conversationText.getVerticalBar();
        if (vBar != null) vBar.setSelection(vBar.getMaximum());
    }

    /**
     * Kills the running process and updates the UI immediately.
     * The background thread's asyncExec will see isWaiting=false and bail out.
     */
    private void stopResponse() {
        isWaiting = false;
        processManager.stop();
        removeThinking();
        int start = conversationText.getCharCount();
        String stopped = "Claude:   [Stopped]\n\n";
        conversationText.append(stopped);
        StyleRange sr = new StyleRange(start, stopped.length(), claudeColor, null);
        sr.fontStyle = SWT.ITALIC;
        conversationText.setStyleRange(sr);
        scrollToBottom();
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setText("Send");
            sendButton.setBackground(sendBgColor);
        }
        if (inputText  != null && !inputText.isDisposed())  {
            inputText.setEnabled(true);
            inputText.setFocus();
        }
    }

    private void archiveHistory() {
        File chatDir = getChatHistoryDir();
        if (chatDir == null || !chatDir.exists()) {
            MessageDialog.openInformation(getSite().getShell(), "Archive", "No chat history to archive.");
            return;
        }
        File[] files = chatDir.listFiles((d, name) -> name.matches("chat-history-\\d{4}-\\d{2}-\\d{2}\\.txt"));
        if (files == null || files.length == 0) {
            MessageDialog.openInformation(getSite().getShell(), "Archive", "No chat history files found.");
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(getSite().getShell(), "Archive Chat History",
                "Move " + files.length + " chat history file(s) to archive?\nNext session will start fresh.");
        if (!confirmed) return;
        File archiveDir = new File(chatDir, "archive");
        archiveDir.mkdirs();
        int moved = 0;
        for (File f : files) {
            if (f.renameTo(new File(archiveDir, f.getName()))) moved++;
        }
        MessageDialog.openInformation(getSite().getShell(), "Archive",
                moved + " file(s) archived to:\n" + archiveDir.getAbsolutePath());
    }

    private void exportHistory() {
        if (conversationText == null || conversationText.isDisposed()) return;
        String history = conversationText.getText().trim();
        if (history.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Export", "Nothing to export — the conversation is empty.");
            return;
        }
        org.eclipse.swt.widgets.FileDialog dialog = new org.eclipse.swt.widgets.FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export Chat History");
        dialog.setFileName("ClaudeStudioExport_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");
        dialog.setFilterExtensions(new String[]{"*.txt"});
        String path = dialog.open();
        if (path == null) return;
        try (BufferedWriter writer = Files.newBufferedWriter(new File(path).toPath(), StandardCharsets.UTF_8)) {
            writer.write(history);
            MessageDialog.openInformation(getSite().getShell(), "Export", "Exported to:\n" + path);
        } catch (IOException ex) {
            MessageDialog.openError(getSite().getShell(), "Export", "Could not export:\n" + ex.getMessage());
        }
    }

    private void setInputEnabled(boolean enabled) {
        isWaiting = !enabled;
        if (inputText  != null && !inputText.isDisposed())  inputText.setEnabled(enabled);
        if (sendButton != null && !sendButton.isDisposed()) {
            if (enabled) {
                sendButton.setText("Send");
                sendButton.setBackground(sendBgColor);
            } else {
                sendButton.setText("Stop");
                sendButton.setBackground(stopBgColor);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chat history — auto-summary, startup load, archive, export
    // -------------------------------------------------------------------------

    private File getChatHistoryDir() {
        if (currentWorkingDir == null || currentWorkingDir.isEmpty()) return null;
        File dir = new File(currentWorkingDir, "chat_history");
        if (!dir.exists()) {
            dir.mkdirs();
            ensureGitignore(new File(currentWorkingDir));
        }
        return dir;
    }

    private File getTodayHistoryFile() {
        File dir = getChatHistoryDir();
        if (dir == null) return null;
        return new File(dir, "chat-history-" + LocalDate.now().format(DATE_FMT) + ".txt");
    }

    private File getMostRecentHistoryFile() {
        File dir = getChatHistoryDir();
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles((d, name) -> name.matches("chat-history-\\d{4}-\\d{2}-\\d{2}\\.txt"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files[0];
    }

    private void ensureGitignore(File projectDir) {
        File gitignore = new File(projectDir, ".gitignore");
        String entry = "chat_history/";
        try {
            if (gitignore.exists()) {
                String content = Files.readString(gitignore.toPath(), StandardCharsets.UTF_8);
                if (content.contains(entry)) return;
                String appended = (content.endsWith("\n") ? content : content + "\n") + entry + "\n";
                Files.writeString(gitignore.toPath(), appended, StandardCharsets.UTF_8);
            } else {
                Files.writeString(gitignore.toPath(), entry + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {}
    }

    private void startSummaryTimer() {
        if (timerStarted) return;
        timerStarted = true;
        summaryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-summary-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduleSummaryTick();
    }

    private void scheduleSummaryTick() {
        if (summaryScheduler == null || summaryScheduler.isShutdown()) return;
        int interval = SUMMARY_INTERVAL_MINUTES;
        if (Activator.getDefault() != null) {
            int pref = Activator.getDefault().getPreferenceStore()
                    .getInt(Activator.PREF_SUMMARY_INTERVAL);
            if (pref > 0) interval = pref;
        }
        summaryScheduler.schedule(() -> {
            triggerAutoSummary();
            scheduleSummaryTick(); // re-read pref for next tick
        }, interval, TimeUnit.MINUTES);
    }

    private void triggerAutoSummary() {
        if (lastActivityTime <= lastSummaryTime) return; // nothing new since last summary
        if (isWaiting) return;                           // CS is mid-response
        String workDir = currentWorkingDir;
        if (workDir == null || workDir.isEmpty()) return;
        File historyFile = getTodayHistoryFile();
        if (historyFile == null) return;
        String contextKey = computeContextKey();

        String prompt = "Please write a concise technical summary of our conversation so far, "
                + "suitable for resuming work in a future session. Cover: what we are working on, "
                + "key decisions made, current state of any work in progress, and important context "
                + "to remember. Be brief and factual. Omit conversational filler and pleasantries.";

        Thread t = new Thread(() -> {
            try {
                String summary = launchAndRead(prompt, workDir, contextKey);
                if (summary != null && !summary.startsWith("[")) {
                    String content = "=== " + LocalDate.now().format(DATE_FMT) + " ===\n\n" + summary + "\n";
                    Files.writeString(historyFile.toPath(), content, StandardCharsets.UTF_8);
                    lastSummaryTime = System.currentTimeMillis();
                    String indicator = "[auto-summary saved " + LocalDateTime.now().format(TIME_FMT) + "]\n";
                    Display.getDefault().asyncExec(() -> {
                        if (conversationText == null || conversationText.isDisposed()) return;
                        int start = conversationText.getCharCount();
                        conversationText.append(indicator);
                        StyleRange sr = new StyleRange(start, indicator.length(), timestampColor, null);
                        sr.fontStyle = SWT.ITALIC;
                        conversationText.setStyleRange(sr);
                        scrollToBottom();
                    });
                }
            } catch (IOException ignored) {}
        }, "claude-summary");
        t.setDaemon(true);
        t.start();
    }

    private void loadHistoryOnStartup() {
        if (Activator.getDefault() != null &&
                !Activator.getDefault().getPreferenceStore()
                        .getBoolean(Activator.PREF_AUTO_LOAD_HISTORY)) return;
        File historyFile = getMostRecentHistoryFile();
        if (historyFile == null) return; // no history — start fresh
        String workDir = currentWorkingDir;
        String contextKey = computeContextKey();

        String prompt = "Please read the file at " + historyFile.getAbsolutePath()
                + ". It contains a summary of our recent conversation history. "
                + "Give me a brief 2-3 sentence overview of what we were working on, "
                + "then confirm you are ready to continue.";

        Thread t = new Thread(() -> {
            try {
                String response = launchAndRead(prompt, workDir, contextKey);
                Display.getDefault().asyncExec(() -> {
                    if (conversationText == null || conversationText.isDisposed()) return;
                    appendClaudeMessage(response != null ? response : "(Could not load history summary)");
                    lastActivityTime = System.currentTimeMillis();
                });
            } catch (IOException ignored) {}
        }, "claude-history-load");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // JSON extraction (minimal — avoids adding a JSON library dependency)
    // -------------------------------------------------------------------------

    /**
     * Extracts the string value of a top-level field from a JSON object line.
     * Handles all standard JSON string escape sequences.
     * Returns null if the field is absent or its value is not a string.
     */
    /**
     * Extracts a human-readable error from a {@code result} event. Newer CLI
     * versions report failures in an {@code "errors":[...]} array; older ones
     * used a scalar {@code "error"} field. Try the scalar first, then the
     * first element of the array.
     */
    private static String extractFirstError(String json) {
        String scalar = extractJsonString(json, "error");
        if (scalar != null && !scalar.isBlank()) return scalar;

        String key = "\"errors\":[";
        int arr = json.indexOf(key);
        if (arr == -1) return null;
        int q = json.indexOf('"', arr + key.length());
        if (q == -1) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char esc = json.charAt(++i);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String extractJsonString(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int start = json.indexOf(key);
        if (start == -1) return null;
        start += key.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char esc = json.charAt(++i);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {}
                        }
                        break;
                    default: sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

    private void buildToolbar() {
        IActionBars bars = getViewSite().getActionBars();
        IToolBarManager toolbar = bars.getToolBarManager();

        contextSelectorAction = new ContextSelectorAction(contextManager);
        contextSelectorAction.updateText();
        // Force the toolbar to remeasure the button whenever its text changes.
        contextSelectorAction.setToolbarRefresh(() -> {
            toolbar.update(true);
            bars.updateActionBars();
        });
        toolbar.add(contextSelectorAction);
        toolbar.add(new Separator());

        Action prefsAction = new Action("Claude Studio Settings") {
            @Override
            public void run() {
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                        getSite().getShell(),
                        "org.claudecodestudio.preferences",
                        new String[]{"org.claudecodestudio.preferences"}, null).open();
            }
        };
        prefsAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_DEF_VIEW));
        prefsAction.setToolTipText("Claude Studio preferences");
        toolbar.add(prefsAction);

        bars.updateActionBars();
    }

    // -------------------------------------------------------------------------
    // Context change
    // -------------------------------------------------------------------------

    /**
     * Called when the user selects a different project context.
     * Updates the header and resets session state so the next message
     * starts a fresh Claude conversation in the new working directory.
     * Does NOT clear conversation history — previous exchanges remain visible.
     */
    /**
     * Builds a stable context key from the currently selected projects.
     * Project names are sorted alphabetically so that the same combination
     * always produces the same key regardless of selection order.
     */
    private String computeContextKey() {
        Set<IProject> projects = contextManager.getSelectedProjects();
        if (projects.isEmpty()) return "__default__";
        return projects.stream()
                .map(IProject::getName)
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private void onContextChanged(Set<IProject> projects, String workingDirectory, boolean isExplicit) {
        String previousDir = currentWorkingDir;
        currentWorkingDir = workingDirectory;

        // Stop any in-flight process when the user explicitly switches context,
        // but preserve the session ID — the next launch will resume the session
        // for the new context automatically.
        if (isExplicit) {
            processManager.stop();
        }

        // Load history when switching into a new project directory
        if (workingDirectory != null && !workingDirectory.equals(previousDir)) {
            loadHistoryOnStartup();
        }

        if (headerLabel == null || headerLabel.isDisposed()) return;
        headerLabel.getDisplay().asyncExec(() -> {
            if (headerLabel.isDisposed()) return;
            String contextText;
            if (projects.isEmpty()) {
                contextText = "Context: (none \u2014 open a project file to auto-select)";
            } else if (projects.size() == 1) {
                contextText = "Context: " + projects.iterator().next().getName();
            } else {
                contextText = "Context: (multi)";
            }
            headerLabel.setText(contextText);

            // Only show the separator when the user explicitly switched context.
            if (isExplicit && conversationText != null && !conversationText.isDisposed()
                    && conversationText.getCharCount() > 0) {
                int start = conversationText.getCharCount();
                String sep = "\n\u2500\u2500 " + contextText + " \u2500\u2500\n\n";
                conversationText.append(sep);
                StyleRange sr = new StyleRange(start, sep.length(), null, null);
                sr.fontStyle = SWT.ITALIC;
                conversationText.setStyleRange(sr);
                scrollToBottom();
            }

            if (contextSelectorAction != null) contextSelectorAction.updateText();
        });
    }
}
