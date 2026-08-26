package org.claudecodestudio;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle.
 * Holds the singleton instance and initializes preference defaults.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.claudecodestudio.plugin";

    // Preference keys
    public static final String PREF_CLAUDE_PATH         = "claudePath";
    public static final String PREF_AUTO_LAUNCH         = "autoLaunch";
    public static final String PREF_EXTRA_ARGS          = "extraArgs";
    public static final String PREF_MODEL               = "model";
    public static final String PREF_SUMMARY_INTERVAL    = "summaryInterval";
    public static final String PREF_RESPONSE_TIMEOUT    = "responseTimeout";
    public static final String PREF_AUTO_LOAD_HISTORY   = "autoLoadHistory";

    // Default values
    public static final String DEFAULT_CLAUDE_PATH      = "";   // empty = use PATH
    public static final boolean DEFAULT_AUTO_LAUNCH     = true;
    public static final String DEFAULT_EXTRA_ARGS       = "";
    public static final String DEFAULT_MODEL            = "claude-opus-4-8[1m]"; // Opus 4.8, 1M context
    public static final int DEFAULT_SUMMARY_INTERVAL    = 30;   // minutes
    public static final int DEFAULT_RESPONSE_TIMEOUT    = 5;    // minutes
    public static final boolean DEFAULT_AUTO_LOAD_HISTORY = true;

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    /** Returns the shared plugin instance. */
    public static Activator getDefault() {
        return plugin;
    }

    @Override
    protected void initializeDefaultPreferences(IPreferenceStore store) {
        store.setDefault(PREF_CLAUDE_PATH,       DEFAULT_CLAUDE_PATH);
        store.setDefault(PREF_AUTO_LAUNCH,       DEFAULT_AUTO_LAUNCH);
        store.setDefault(PREF_EXTRA_ARGS,        DEFAULT_EXTRA_ARGS);
        store.setDefault(PREF_MODEL,             DEFAULT_MODEL);
        store.setDefault(PREF_SUMMARY_INTERVAL,  DEFAULT_SUMMARY_INTERVAL);
        store.setDefault(PREF_RESPONSE_TIMEOUT,  DEFAULT_RESPONSE_TIMEOUT);
        store.setDefault(PREF_AUTO_LOAD_HISTORY, DEFAULT_AUTO_LOAD_HISTORY);
    }
}
