package org.claudecodestudio.preferences;

import org.claudecodestudio.Activator;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Preference page for the Claude Code Studio plugin.
 *
 * Accessible via:
 *   Window → Preferences → Claude Code
 */
public class ClaudePreferencePage
        extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public ClaudePreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure the Claude Studio plugin.");
    }

    @Override
    protected void createFieldEditors() {

        // --- Claude binary path ---
        FileFieldEditor pathEditor = new FileFieldEditor(
                Activator.PREF_CLAUDE_PATH,
                "Claude CLI Path:",
                true,   // enforce existing file
                getFieldEditorParent());
        pathEditor.setEmptyStringAllowed(true);
        pathEditor.setErrorMessage(
                "The specified path does not point to an existing file.");
        // Hint shown below the field
        addField(pathEditor);

        // Additional helper label (not a field editor — use a plain label via
        // addField with a read-only StringFieldEditor workaround)
        StringFieldEditor hintEditor = new StringFieldEditor(
                Activator.PREF_CLAUDE_PATH + ".hint",
                "",
                getFieldEditorParent()) {
            @Override
            protected void doLoad() { /* no-op */ }
            @Override
            protected void doLoadDefault() { /* no-op */ }
            @Override
            protected void doStore() { /* no-op */ }
            @Override
            public boolean isValid() { return true; }
        };
        hintEditor.getLabelControl(getFieldEditorParent())
                .setText("Leave empty to find 'claude' (or 'claude.cmd') on PATH.");
        // We don't actually add this as a field — it's just for the label.

        // --- Auto-launch ---
        addField(new BooleanFieldEditor(
                Activator.PREF_AUTO_LAUNCH,
                "Automatically launch Claude Studio when the view is opened",
                getFieldEditorParent()));

        // --- Model ---
        addField(new StringFieldEditor(
                Activator.PREF_MODEL,
                "Model (e.g. claude-opus-4-8[1m]):",
                getFieldEditorParent()));

        // --- Extra startup arguments ---
        addField(new StringFieldEditor(
                Activator.PREF_EXTRA_ARGS,
                "Extra startup arguments:",
                getFieldEditorParent()));

        // --- Summary interval ---
        IntegerFieldEditor summaryEditor = new IntegerFieldEditor(
                Activator.PREF_SUMMARY_INTERVAL,
                "Auto-summary interval (minutes):",
                getFieldEditorParent());
        summaryEditor.setValidRange(1, 240);
        addField(summaryEditor);

        // --- Response timeout ---
        IntegerFieldEditor timeoutEditor = new IntegerFieldEditor(
                Activator.PREF_RESPONSE_TIMEOUT,
                "Response timeout (minutes):",
                getFieldEditorParent());
        timeoutEditor.setValidRange(1, 60);
        addField(timeoutEditor);

        // --- Auto-load history ---
        addField(new BooleanFieldEditor(
                Activator.PREF_AUTO_LOAD_HISTORY,
                "Auto-load conversation history when opening a project",
                getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialise from the workbench
    }
}
