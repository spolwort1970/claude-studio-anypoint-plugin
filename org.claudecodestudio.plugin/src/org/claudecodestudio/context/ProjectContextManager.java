package org.claudecodestudio.context;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Tracks which projects are in scope for the Claude Code session.
 *
 * Listens to the workbench selection service and auto-switches to the project
 * containing the selected resource when the user has not manually pinned a
 * context via the dropdown.
 *
 * The working directory passed to the process is:
 *  - Single project  → project root
 *  - Multiple projects → longest common ancestor of their root paths
 *
 * Clients register a {@link ContextChangeListener} to be notified of changes.
 */
public class ProjectContextManager implements ISelectionListener {

    /** Callback when the resolved working directory or project set changes. */
    public interface ContextChangeListener {
        /**
         * @param isExplicit true when the user chose a context via the dropdown;
         *                   false when the change was triggered by a passive
         *                   Package Explorer click (auto-follow).
         */
        void onContextChanged(Set<IProject> projects, String workingDirectory, boolean isExplicit);
    }

    private final ClaudeMdManager claudeMdManager = new ClaudeMdManager();

    /** Projects explicitly chosen by the user via the toolbar dropdown. */
    private final Set<IProject> selectedProjects = new LinkedHashSet<>();

    /**
     * When true the selection listener does NOT auto-switch; the user has
     * explicitly pinned one or more projects.
     */
    private boolean userPinned = false;

    private ContextChangeListener listener;

    public void setContextChangeListener(ContextChangeListener l) {
        this.listener = l;
    }

    // -------------------------------------------------------------------------
    // ISelectionListener — auto-follow Package Explorer selection
    // -------------------------------------------------------------------------

    @Override
    public void selectionChanged(IWorkbenchPart part, ISelection selection) {
        if (userPinned) return;
        if (!(selection instanceof IStructuredSelection)) return;

        IStructuredSelection structured = (IStructuredSelection) selection;
        Object first = structured.getFirstElement();
        if (first == null) return;

        IProject project = extractProject(first);
        if (project == null || !project.isOpen()) return;

        selectedProjects.clear();
        selectedProjects.add(project);
        notifyChange(false); // auto-follow — do NOT reset the active session
    }

    // -------------------------------------------------------------------------
    // Explicit project selection (from toolbar dropdown)
    // -------------------------------------------------------------------------

    /**
     * Replaces the selected projects with the given set.
     * Passing {@code null} or an empty set reverts to auto-follow mode.
     */
    public void setSelectedProjects(Set<IProject> projects) {
        selectedProjects.clear();
        if (projects != null && !projects.isEmpty()) {
            selectedProjects.addAll(projects);
            userPinned = true;
        } else {
            userPinned = false;
        }
        notifyChange(true); // explicit user action — reset session
    }

    /** Toggles a single project's membership in the selected set. */
    public void toggleProject(IProject project) {
        if (selectedProjects.contains(project)) {
            selectedProjects.remove(project);
        } else {
            selectedProjects.add(project);
        }
        userPinned = !selectedProjects.isEmpty();
        notifyChange(true); // explicit user action — reset session
    }

    /** Clears all explicit selections, returning to auto-follow mode. */
    public void useWorkspaceRoot() {
        selectedProjects.clear();
        userPinned = false;
        notifyChange(true); // explicit user action — reset session
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Set<IProject> getSelectedProjects() {
        return new LinkedHashSet<>(selectedProjects);
    }

    public boolean isUserPinned() {
        return userPinned;
    }

    /**
     * Computes the working directory for the current context.
     *
     * @return absolute OS path string, or null if nothing is selected
     */
    public String resolveWorkingDirectory() {
        if (selectedProjects.isEmpty()) return null;

        if (selectedProjects.size() == 1) {
            IProject p = selectedProjects.iterator().next();
            return p.getLocation() != null ? p.getLocation().toOSString() : null;
        }

        // Multiple projects: find the longest common path ancestor
        String[] paths = selectedProjects.stream()
                .filter(p -> p.getLocation() != null)
                .map(p -> p.getLocation().toOSString())
                .toArray(String[]::new);
        return longestCommonAncestor(paths);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void notifyChange(boolean isExplicit) {
        String workingDir = resolveWorkingDirectory();

        // Update CLAUDE.md in the working directory
        if (workingDir != null && !selectedProjects.isEmpty()) {
            claudeMdManager.updateContext(workingDir, selectedProjects);
        }

        if (listener != null) {
            listener.onContextChanged(new LinkedHashSet<>(selectedProjects), workingDir, isExplicit);
        }
    }

    private IProject extractProject(Object element) {
        if (element instanceof IProject) {
            return (IProject) element;
        }
        if (element instanceof IResource) {
            return ((IResource) element).getProject();
        }
        // Support IAdaptable (many Eclipse model elements implement this)
        if (element instanceof org.eclipse.core.runtime.IAdaptable) {
            IResource resource = ((org.eclipse.core.runtime.IAdaptable) element)
                    .getAdapter(IResource.class);
            if (resource != null) return resource.getProject();
        }
        return null;
    }

    /**
     * Returns the longest common filesystem ancestor of the given paths,
     * comparing by path segments.
     */
    static String longestCommonAncestor(String[] paths) {
        if (paths == null || paths.length == 0) return null;
        if (paths.length == 1) return paths[0];

        // Normalise separators and split into segments
        String[][] segments = Arrays.stream(paths)
                .map(p -> p.replace('\\', '/').split("/"))
                .toArray(String[][]::new);

        StringBuilder common = new StringBuilder();
        int minLen = Arrays.stream(segments)
                .mapToInt(s -> s.length)
                .min()
                .orElse(0);

        for (int i = 0; i < minLen; i++) {
            String seg = segments[0][i];
            final int idx = i;
            boolean allMatch = Arrays.stream(segments)
                    .allMatch(s -> s[idx].equals(seg));
            if (!allMatch) break;
            if (common.length() > 0) common.append('/');
            common.append(seg);
        }

        if (common.length() == 0) return null;

        // Re-apply the original separator style
        String result = common.toString();
        // If original paths used backslash (Windows) restore it
        if (paths[0].contains("\\")) {
            result = result.replace('/', '\\');
        }
        return result;
    }
}
