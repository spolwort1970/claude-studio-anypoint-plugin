package org.claudecodestudio.ui;

import java.util.Set;

import org.claudecodestudio.context.ProjectContextManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Toolbar drop-down action that lets the user select which projects are in scope.
 *
 * Each open project gets a menu item with a ☑/☐ prefix. A separator followed
 * by a "Use Workspace Root" item is appended at the bottom.
 */
public class ContextSelectorAction extends Action implements IMenuCreator {

    private final ProjectContextManager contextManager;
    private Menu menu;

    /**
     * Optional callback invoked after the button text changes so the caller
     * can force the toolbar to re-measure and resize the button.
     */
    private Runnable toolbarRefresh;

    public ContextSelectorAction(ProjectContextManager contextManager) {
        super("Context: (auto)", Action.AS_DROP_DOWN_MENU);
        this.contextManager = contextManager;
        setMenuCreator(this);
        setToolTipText("Select active project context");
    }

    /** Supply a callback that triggers a toolbar re-layout after text changes. */
    public void setToolbarRefresh(Runnable r) {
        this.toolbarRefresh = r;
    }

    // -------------------------------------------------------------------------
    // IMenuCreator
    // -------------------------------------------------------------------------

    @Override
    public Menu getMenu(Control parent) {
        disposeMenu();
        menu = new Menu(parent);
        populateMenu(menu);
        return menu;
    }

    @Override
    public Menu getMenu(Menu parent) {
        disposeMenu();
        menu = new Menu(parent);
        populateMenu(menu);
        return menu;
    }

    @Override
    public void dispose() {
        disposeMenu();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void populateMenu(Menu m) {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        Set<IProject> selected = contextManager.getSelectedProjects();

        for (IProject project : projects) {
            if (!project.isOpen()) continue;

            boolean checked = selected.contains(project);
            MenuItem item = new MenuItem(m, SWT.PUSH);
            item.setText((checked ? "\u2611 " : "\u2610 ") + project.getName());
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    contextManager.toggleProject(project);
                    updateText();
                }
            });
        }

        new MenuItem(m, SWT.SEPARATOR);

        MenuItem workspaceItem = new MenuItem(m, SWT.PUSH);
        workspaceItem.setText("Use Workspace Root");
        workspaceItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                contextManager.useWorkspaceRoot();
                updateText();
            }
        });
    }

    private void disposeMenu() {
        if (menu != null && !menu.isDisposed()) menu.dispose();
        menu = null;
    }

    /**
     * Updates the toolbar button text to reflect current context and triggers
     * a toolbar re-layout so the button resizes to fit the new text.
     */
    public void updateText() {
        Set<IProject> selected = contextManager.getSelectedProjects();
        if (selected.isEmpty()) {
            setText("Context: (auto)");
        } else if (selected.size() == 1) {
            setText("Context: " + selected.iterator().next().getName());
        } else {
            setText("Context: (multi)");
        }
        // Force the toolbar to remeasure — without this the button keeps its
        // old width after a text change until the user clicks elsewhere.
        if (toolbarRefresh != null) toolbarRefresh.run();
    }
}
