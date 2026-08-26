package org.claudecodestudio.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler that opens (or brings to front) the Claude Code terminal view.
 * Bound to the {@code org.claudecodestudio.command.openView} command in plugin.xml.
 */
public class OpenViewHandler extends AbstractHandler {

    private static final String VIEW_ID = "org.claudecodestudio.views.ClaudeTerminalView";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        if (page == null) return null;

        try {
            page.showView(VIEW_ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Failed to open Claude Code view", e);
        }
        return null;
    }
}
