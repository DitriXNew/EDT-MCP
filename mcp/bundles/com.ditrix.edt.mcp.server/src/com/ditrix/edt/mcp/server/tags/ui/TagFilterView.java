/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagService.ITagChangeListener;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

/**
 * View for filtering and browsing metadata objects by tags.
 */
public class TagFilterView extends ViewPart implements ITagChangeListener {
    
    /** The view ID as defined in plugin.xml */
    public static final String ID = "com.ditrix.edt.mcp.server.tags.filterView";
    
    /** Size of color icons for tags */
    private static final int COLOR_ICON_SIZE = 16;
    
    private TagService tagService;
    private IV8ProjectManager v8ProjectManager;
    
    // Tree viewer with projects and tags
    private CheckboxTreeViewer tagsTreeViewer;
    private TableViewer resultsViewer;
    
    // Selected tags per project (for filtering)
    private Map<IProject, Set<String>> selectedTagsByProject = new HashMap<>();
    
    // Filtered results with project info
    private List<ObjectEntry> filteredObjects = new ArrayList<>();
    
    /** Search filter text */
    private Text searchText;
    
    /** Current search pattern (regex) */
    private Pattern searchPattern;
    
    /** Search all tags checkbox */
    private Button searchAllTagsCheckbox;
    
    // ResourceManager for image lifecycle
    private ResourceManager resourceManager;
    // Additional images to dispose (not managed by factory)
    private List<Image> colorIcons = new ArrayList<>();
    
    /**
     * Wrapper for result objects with project info.
     */
    private record ObjectEntry(IProject project, String fqn, Set<Tag> tags) {}
    
    @Override
    public void createPartControl(Composite parent) {
        tagService = TagService.getInstance();
        tagService.addTagChangeListener(this);
        v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        
        // Create resource manager tied to parent lifecycle
        resourceManager = new LocalResourceManager(TagColorIconFactory.getJFaceResources(), parent);
        
        parent.setLayout(new FillLayout());
        
        SashForm sashForm = new SashForm(parent, SWT.HORIZONTAL);
        
        // Left panel - Tags tree
        createTagsPanel(sashForm);
        
        // Right panel - Filtered results
        createResultsPanel(sashForm);
        
        sashForm.setWeights(30, 70);
        
        // Initial data load
        refreshTagsTree();
    }
    
    private void createTagsPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Filter by Tags");
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        group.setLayout(layout);
        
        // Load icons
        ImageDescriptor refreshIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/restart.png");
        ImageDescriptor selectAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/check_all.png");
        ImageDescriptor deselectAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/uncheck_all.png");
        
        // Buttons row - Select All and Deselect All as icon-only buttons
        Button selectAllBtn = new Button(group, SWT.PUSH);
        selectAllBtn.setToolTipText("Select all tags");
        if (selectAllIcon != null) {
            selectAllBtn.setImage(selectAllIcon.createImage());
        }
        selectAllBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                selectAllTags(true);
            }
        });
        
        Button deselectAllBtn = new Button(group, SWT.PUSH);
        deselectAllBtn.setToolTipText("Deselect all tags");
        if (deselectAllIcon != null) {
            deselectAllBtn.setImage(deselectAllIcon.createImage());
        }
        deselectAllBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                selectAllTags(false);
            }
        });
        
        // Refresh button aligned to the right
        Button refreshBtn = new Button(group, SWT.PUSH);
        refreshBtn.setToolTipText("Refresh tags from YAML files");
        if (refreshIcon != null) {
            refreshBtn.setImage(refreshIcon.createImage());
        }
        GridData refreshData = new GridData(SWT.RIGHT, SWT.CENTER, true, false);
        refreshBtn.setLayoutData(refreshData);
        refreshBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                refreshTagsTree();
            }
        });
        
        // Tree with checkboxes
        Tree tree = new Tree(group, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        tree.setHeaderVisible(true);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        tree.setLayoutData(treeData);
        
        tagsTreeViewer = new CheckboxTreeViewer(tree);
        tagsTreeViewer.setContentProvider(new TagTreeContentProvider());
        
        // Name column with project/tag icons
        TreeViewerColumn nameColumn = new TreeViewerColumn(tagsTreeViewer, SWT.NONE);
        nameColumn.getColumn().setText("Project / Tag");
        nameColumn.getColumn().setWidth(200);
        nameColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof IProject project) {
                    return project.getName();
                } else if (element instanceof TagEntry entry) {
                    return entry.tag().getName();
                }
                return "";
            }
            
            @Override
            public Image getImage(Object element) {
                if (element instanceof IProject) {
                    return PlatformUI.getWorkbench().getSharedImages()
                        .getImage(IDE.SharedImages.IMG_OBJ_PROJECT);
                } else if (element instanceof TagEntry entry) {
                    return createColorIcon(entry.tag().getColor());
                }
                return null;
            }
        });
        
        // Count column
        TreeViewerColumn countColumn = new TreeViewerColumn(tagsTreeViewer, SWT.NONE);
        countColumn.getColumn().setText("Count");
        countColumn.getColumn().setWidth(80);
        countColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof TagEntry entry) {
                    Set<String> objects = tagService.findObjectsByTag(entry.project(), entry.tag().getName());
                    if (searchPattern != null) {
                        int filteredCount = 0;
                        int totalCount = objects.size();
                        for (String fqn : objects) {
                            if (matchesSearch(fqn)) {
                                filteredCount++;
                            }
                        }
                        return filteredCount + "/" + totalCount;
                    }
                    return String.valueOf(objects.size());
                } else if (element instanceof IProject project) {
                    // Show total tags count for project
                    List<Tag> tags = tagService.getTags(project);
                    return "(" + tags.size() + " tags)";
                }
                return "";
            }
        });
        
        // Context menu for projects (Open YAML)
        createTreeContextMenu(tree);
        
        // Handle checkbox changes
        tagsTreeViewer.addCheckStateListener(new ICheckStateListener() {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event) {
                Object element = event.getElement();
                boolean checked = event.getChecked();
                
                if (element instanceof IProject project) {
                    // Check/uncheck all tags in this project
                    List<Tag> tags = tagService.getTags(project);
                    Set<String> projectTags = selectedTagsByProject.computeIfAbsent(project, p -> new HashSet<>());
                    
                    if (checked) {
                        for (Tag tag : tags) {
                            projectTags.add(tag.getName());
                            tagsTreeViewer.setChecked(new TagEntry(project, tag), true);
                        }
                    } else {
                        projectTags.clear();
                        for (Tag tag : tags) {
                            tagsTreeViewer.setChecked(new TagEntry(project, tag), false);
                        }
                    }
                    // Reset grayed state when explicitly checking/unchecking project
                    tagsTreeViewer.setGrayed(project, false);
                    
                } else if (element instanceof TagEntry entry) {
                    Set<String> projectTags = selectedTagsByProject.computeIfAbsent(entry.project(), p -> new HashSet<>());
                    if (checked) {
                        projectTags.add(entry.tag().getName());
                    } else {
                        projectTags.remove(entry.tag().getName());
                    }
                    // Update parent project checkbox state
                    updateProjectCheckState(entry.project());
                }
                
                updateFilteredResults();
            }
        });
        
        // Set input
        tagsTreeViewer.setInput(ResourcesPlugin.getWorkspace().getRoot());
        tagsTreeViewer.expandAll();
    }
    
    /**
     * Create context menu for the tags tree.
     */
    private void createTreeContextMenu(Tree tree) {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                IStructuredSelection selection = tagsTreeViewer.getStructuredSelection();
                if (!selection.isEmpty() && selection.getFirstElement() instanceof IProject project) {
                    manager.add(new Action("Open YAML File") {
                        @Override
                        public void run() {
                            openTagsYamlFile(project);
                        }
                    });
                }
            }
        });
        
        Menu menu = menuMgr.createContextMenu(tree);
        tree.setMenu(menu);
    }
    
    /**
     * Update project checkbox to grayed or checked based on children state.
     */
    private void updateProjectCheckState(IProject project) {
        List<Tag> tags = tagService.getTags(project);
        Set<String> selectedTags = selectedTagsByProject.getOrDefault(project, new HashSet<>());
        
        int checkedCount = 0;
        for (Tag tag : tags) {
            if (selectedTags.contains(tag.getName())) {
                checkedCount++;
            }
        }
        
        if (checkedCount == 0) {
            tagsTreeViewer.setChecked(project, false);;
            tagsTreeViewer.setGrayed(project, false);
        } else if (checkedCount == tags.size()) {
            tagsTreeViewer.setChecked(project, true);
            tagsTreeViewer.setGrayed(project, false);
        } else {
            tagsTreeViewer.setChecked(project, true);
            tagsTreeViewer.setGrayed(project, true);
        }
    }
    
    /**
     * Select or deselect all tags.
     */
    private void selectAllTags(boolean select) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            if (v8ProjectManager != null) {
                IV8Project v8Project = v8ProjectManager.getProject(project);
                if (v8Project == null) continue;
            }
            
            List<Tag> tags = tagService.getTags(project);
            Set<String> projectTags = selectedTagsByProject.computeIfAbsent(project, p -> new HashSet<>());
            
            if (select) {
                for (Tag tag : tags) {
                    projectTags.add(tag.getName());
                    tagsTreeViewer.setChecked(new TagEntry(project, tag), true);
                }
                tagsTreeViewer.setChecked(project, true);
                tagsTreeViewer.setGrayed(project, false);
            } else {
                projectTags.clear();
                for (Tag tag : tags) {
                    tagsTreeViewer.setChecked(new TagEntry(project, tag), false);
                }
                tagsTreeViewer.setChecked(project, false);
                tagsTreeViewer.setGrayed(project, false);
            }
        }
        updateFilteredResults();
    }
    
    /**
     * Wrapper class for tag entries in the tree.
     * 
     * <p>Uses record auto-generated equals/hashCode since Tag class
     * already implements proper equality based on tag name.</p>
     */
    private record TagEntry(IProject project, Tag tag) {
    }
    
    /**
     * Content provider for the tags tree.
     */
    private class TagTreeContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof IWorkspaceRoot root) {
                List<IProject> result = new ArrayList<>();
                for (IProject project : root.getProjects()) {
                    if (project.isOpen()) {
                        // Check if it's an EDT project
                        if (v8ProjectManager != null) {
                            IV8Project v8Project = v8ProjectManager.getProject(project);
                            if (v8Project != null) {
                                result.add(project);
                            }
                        } else {
                            result.add(project);
                        }
                    }
                }
                return result.toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof IProject project) {
                List<Tag> tags = tagService.getTags(project);
                
                // Check if we should hide empty tags
                boolean hideEmptyTags = searchAllTagsCheckbox != null && searchAllTagsCheckbox.getSelection();
                
                return tags.stream()
                    .map(tag -> new TagEntry(project, tag))
                    .filter(entry -> {
                        if (!hideEmptyTags) {
                            return true;
                        }
                        // Count matching objects for this tag
                        int count = countMatchingObjects(entry);
                        return count > 0;
                    })
                    .toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object getParent(Object element) {
            if (element instanceof TagEntry entry) {
                return entry.project();
            }
            return null;
        }
        
        @Override
        public boolean hasChildren(Object element) {
            if (element instanceof IProject project) {
                return !tagService.getTags(project).isEmpty();
            }
            return false;
        }
    }
    
    private void createResultsPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Matching Objects");
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        group.setLayout(layout);
        
        // Search field
        searchText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        searchText.setMessage("Search (regex)...");
        GridData searchData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        searchText.setLayoutData(searchData);
        searchText.addModifyListener(e -> {
            updateSearchPattern();
            updateFilteredResults();
        });
        
        // Checkbox to hide tags with no matching objects when search filter is applied
        searchAllTagsCheckbox = new Button(group, SWT.CHECK);
        searchAllTagsCheckbox.setText("Hide empty tags");
        searchAllTagsCheckbox.setToolTipText("When checked, tags with no objects matching the search filter will be hidden in the Tags tree");
        searchAllTagsCheckbox.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                refreshTagsTree();
            }
        });
        
        // Table (MULTI allows multiple selection)
        Table table = new Table(group, SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.MULTI);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        table.setLayoutData(tableData);
        
        resultsViewer = new TableViewer(table);
        resultsViewer.setContentProvider(ArrayContentProvider.getInstance());
        
        // Project column
        TableViewerColumn projectColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        projectColumn.getColumn().setText("Project");
        projectColumn.getColumn().setWidth(100);
        projectColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof ObjectEntry entry) {
                    return entry.project().getName();
                }
                return "";
            }
            
            @Override
            public Image getImage(Object element) {
                if (element instanceof ObjectEntry) {
                    return PlatformUI.getWorkbench().getSharedImages()
                        .getImage(IDE.SharedImages.IMG_OBJ_PROJECT);
                }
                return null;
            }
        });
        
        // FQN column - wider to show full paths, with icon
        TableViewerColumn fqnColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        fqnColumn.getColumn().setText("Object");
        fqnColumn.getColumn().setWidth(350);
        fqnColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof ObjectEntry entry) {
                    return simplifyFqn(entry.fqn());
                }
                return element.toString();
            }
            
            @Override
            public Image getImage(Object element) {
                if (element instanceof ObjectEntry entry) {
                    return getObjectIcon(entry.fqn());
                }
                return null;
            }
        });
        
        // Tags column
        TableViewerColumn tagsColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        tagsColumn.getColumn().setText("Tags");
        tagsColumn.getColumn().setWidth(150);
        tagsColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof ObjectEntry entry) {
                    return entry.tags().stream()
                        .map(Tag::getName)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                }
                return "";
            }
        });
        
        // Double-click to navigate to object
        resultsViewer.addDoubleClickListener(event -> {
            IStructuredSelection selection = resultsViewer.getStructuredSelection();
            if (!selection.isEmpty() && selection.getFirstElement() instanceof ObjectEntry entry) {
                navigateToObject(entry.project(), entry.fqn());
            }
        });
        
        // Context menu
        createResultsContextMenu();
    }
    
    private void createResultsContextMenu() {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                fillResultsContextMenu(manager);
            }
        });
        
        Menu menu = menuMgr.createContextMenu(resultsViewer.getControl());
        resultsViewer.getControl().setMenu(menu);
    }
    
    private void fillResultsContextMenu(IMenuManager manager) {
        IStructuredSelection selection = resultsViewer.getStructuredSelection();
        
        // Copy selected FQNs (works with multiple selection)
        if (!selection.isEmpty()) {
            manager.add(new Action("Copy Selected (" + selection.size() + ")") {
                @Override
                public void run() {
                    StringBuilder sb = new StringBuilder();
                    for (Object obj : selection) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        // Copy simplified FQN as displayed
                        if (obj instanceof ObjectEntry entry) {
                            sb.append(simplifyFqn(entry.fqn()));
                        } else {
                            sb.append(String.valueOf(obj));
                        }
                    }
                    copyToClipboard(sb.toString());
                }
            });
        }
        
        // Copy All FQNs
        if (!filteredObjects.isEmpty()) {
            manager.add(new Action("Copy All (" + filteredObjects.size() + ")") {
                @Override
                public void run() {
                    StringBuilder sb = new StringBuilder();
                    for (ObjectEntry entry : filteredObjects) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(simplifyFqn(entry.fqn()));
                    }
                    copyToClipboard(sb.toString());
                }
            });
        }
    }
    
    /**
     * Copy text to system clipboard.
     */
    private void copyToClipboard(String text) {
        org.eclipse.swt.dnd.Clipboard clipboard = 
            new org.eclipse.swt.dnd.Clipboard(Display.getCurrent());
        clipboard.setContents(
            new Object[] { text },
            new org.eclipse.swt.dnd.Transfer[] { 
                org.eclipse.swt.dnd.TextTransfer.getInstance() 
            });
        clipboard.dispose();
    }
    
    /**
     * Refresh the tags tree.
     */
    private void refreshTagsTree() {
        if (tagsTreeViewer == null || tagsTreeViewer.getControl().isDisposed()) {
            return;
        }
        
        tagsTreeViewer.setInput(ResourcesPlugin.getWorkspace().getRoot());
        tagsTreeViewer.expandAll();
        
        // Restore checked state
        restoreCheckedState();
        
        updateFilteredResults();
    }
    
    /**
     * Restore checked state from selectedTagsByProject map.
     */
    private void restoreCheckedState() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            if (v8ProjectManager != null) {
                IV8Project v8Project = v8ProjectManager.getProject(project);
                if (v8Project == null) continue;
            }
            
            Set<String> selectedTags = selectedTagsByProject.getOrDefault(project, new HashSet<>());
            List<Tag> tags = tagService.getTags(project);
            
            for (Tag tag : tags) {
                TagEntry entry = new TagEntry(project, tag);
                tagsTreeViewer.setChecked(entry, selectedTags.contains(tag.getName()));
            }
            
            updateProjectCheckState(project);
        }
    }
    
    /**
     * Update the search pattern from the search text field.
     */
    private void updateSearchPattern() {
        String text = searchText.getText().trim();
        if (text.isEmpty()) {
            searchPattern = null;
            searchText.setToolTipText("Search (regex)...");
        } else {
            try {
                searchPattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
                searchText.setToolTipText("Search (regex)...");
            } catch (PatternSyntaxException e) {
                // Invalid regex - show error in tooltip
                searchText.setToolTipText("Invalid regex: " + e.getDescription());
                searchPattern = null;
            }
        }
        
        // Refresh tags to update filtered counts
        if (searchAllTagsCheckbox != null && searchAllTagsCheckbox.getSelection()) {
            // Full refresh if filtering is enabled (will call updateFilteredResults)
            refreshTagsTree();
        } else {
            // Just refresh the tags tree to update the count column
            if (tagsTreeViewer != null && !tagsTreeViewer.getControl().isDisposed()) {
                tagsTreeViewer.refresh();
            }
        }
    }
    
    /**
     * Check if a FQN matches the current search pattern.
     */
    private boolean matchesSearch(String fqn) {
        if (searchPattern == null) {
            return true;
        }
        return searchPattern.matcher(fqn).find();
    }
    
    /**
     * Count the number of objects matching the search pattern for a tag.
     */
    private int countMatchingObjects(TagEntry entry) {
        Set<String> objects = tagService.findObjectsByTag(entry.project(), entry.tag().getName());
        if (searchPattern == null) {
            return objects.size();
        }
        int count = 0;
        for (String fqn : objects) {
            if (matchesSearch(fqn)) {
                count++;
            }
        }
        return count;
    }
    
    private void updateFilteredResults() {
        filteredObjects.clear();
        
        // Check if any tags are selected across all projects
        boolean hasSelection = selectedTagsByProject.values().stream()
            .anyMatch(tags -> !tags.isEmpty());
        
        if (!hasSelection) {
            resultsViewer.setInput(filteredObjects);
            updateResultsCount();
            return;
        }
        
        // Iterate over all projects with selected tags (OR logic)
        for (Map.Entry<IProject, Set<String>> entry : selectedTagsByProject.entrySet()) {
            IProject project = entry.getKey();
            Set<String> selectedTags = entry.getValue();
            
            if (selectedTags.isEmpty()) {
                continue;
            }
            
            // Find objects that have ANY of the selected tags for this project
            Map<String, Set<Tag>> matches = tagService.findObjectsByTags(project, selectedTags);
            
            // Apply search filter and add to results
            for (Map.Entry<String, Set<Tag>> match : matches.entrySet()) {
                String fqn = match.getKey();
                if (matchesSearch(fqn)) {
                    filteredObjects.add(new ObjectEntry(project, fqn, match.getValue()));
                }
            }
        }
        
        // Sort by project name, then by FQN
        filteredObjects.sort((a, b) -> {
            int projectCompare = a.project().getName().compareTo(b.project().getName());
            if (projectCompare != 0) {
                return projectCompare;
            }
            return a.fqn().compareTo(b.fqn());
        });
        
        resultsViewer.setInput(filteredObjects);
        updateResultsCount();
    }
    
    private void updateResultsCount() {
        setPartName("Tag Filter (" + filteredObjects.size() + ")");
    }
    
    private Image createColorIcon(String hexColor) {
        return resourceManager.get(TagColorIconFactory.getColorIcon(hexColor, COLOR_ICON_SIZE));
    }
    
    /**
     * Navigate to a metadata object in EDT navigator.
     * Uses EDT's BM model to find and open the object by FQN dynamically.
     * This approach works for all metadata types without hardcoding.
     * 
     * @param project the project containing the object
     * @param fqn the fully qualified name of the object
     */
    private void navigateToObject(IProject project, String fqn) {
        if (project == null || fqn == null) {
            return;
        }
        
        try {
            // Use BmModelManager to get object by FQN dynamically
            com._1c.g5.v8.dt.core.platform.IBmModelManager bmModelManager = 
                Activator.getDefault().getBmModelManager();
            
            if (bmModelManager == null) {
                Activator.logError("BmModelManager not available", null);
                return;
            }
            
            com._1c.g5.v8.bm.integration.IBmModel bmModel = bmModelManager.getModel(project);
            if (bmModel == null) {
                Activator.logError("BM model not found for project: " + project.getName(), null);
                return;
            }
            
            // Execute readonly task to get object by FQN
            final String targetFqn = fqn;
            org.eclipse.emf.ecore.EObject targetObject = (org.eclipse.emf.ecore.EObject) bmModel.executeReadonlyTask(
                new com._1c.g5.v8.bm.integration.AbstractBmTask<com._1c.g5.v8.bm.core.IBmObject>("Get object by FQN") {
                    @Override
                    public com._1c.g5.v8.bm.core.IBmObject execute(
                            com._1c.g5.v8.bm.core.IBmTransaction transaction, 
                            org.eclipse.core.runtime.IProgressMonitor progressMonitor) {
                        return transaction.getTopObjectByFqn(targetFqn);
                    }
                });
            
            if (targetObject != null) {
                // Open in editor using EDT's OpenHelper
                Display.getDefault().asyncExec(() -> {
                    try {
                        com._1c.g5.v8.dt.ui.util.OpenHelper openHelper = 
                            new com._1c.g5.v8.dt.ui.util.OpenHelper();
                        openHelper.openEditor(targetObject);
                    } catch (Exception e) {
                        Activator.logError("Failed to open object: " + fqn, e);
                    }
                });
            } else {
                Activator.logInfo("Object not found by FQN: " + fqn);
            }
        } catch (Exception e) {
            Activator.logError("Failed to navigate to object: " + fqn, e);
        }
    }
    
    /**
     * Simplify FQN for display by removing intermediate type names.
     * Example: "InformationRegister.ItemSegments.InformationRegisterDimension.Segment"
     *       -> "InformationRegister.ItemSegments.Segment"
     * 
     * FQN pattern is always: Type1.Name1.Type2.Name2.Type3.Name3...
     * We keep: Type1.Name1.Name2.Name3...
     */
    private String simplifyFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return fqn;
        }
        
        String[] parts = fqn.split("\\.");
        if (parts.length <= 2) {
            // Simple case: "Document.SalesOrder" - already simplified
            return fqn;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0]); // Type1 (e.g., "InformationRegister")
        
        // Add all name parts (odd indices): Name1, Name2, Name3...
        for (int i = 1; i < parts.length; i += 2) {
            sb.append(".").append(parts[i]);
        }
        
        return sb.toString();
    }
    
    /**
     * Get icon for a metadata object by FQN.
     * Uses EDT's standard icons from MdUiSharedImages.getMdClassImage(EClass).
     * Extracts the type name from FQN and gets EClass from MdClassPackage.
     */
    private Image getObjectIcon(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return null;
        }
        
        String[] parts = fqn.split("\\.");
        
        // FQN pattern: Type1.Name1.Type2.Name2...
        // Types are at even indices (0, 2, 4...)
        // For nested objects, use the last type (at parts.length - 2)
        String typeName = parts[0]; // default to first type
        if (parts.length >= 4) {
            typeName = parts[parts.length - 2];
        }
        
        try {
            // Get EClass from MdClassPackage by type name
            org.eclipse.emf.ecore.EClassifier classifier = 
                com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.eINSTANCE.getEClassifier(typeName);
            
            if (classifier instanceof org.eclipse.emf.ecore.EClass eClass) {
                return MdUiSharedImages.getMdClassImage(eClass);
            }
        } catch (Exception e) {
            // Fallback to null if EClass not found
        }
        
        return null;
    }
    
    /**
     * Open the tags YAML file in an editor.
     * 
     * @param project the project to open YAML file for
     */
    private void openTagsYamlFile(IProject project) {
        if (project == null) {
            return;
        }
        
        try {
            org.eclipse.core.resources.IFile yamlFile = project.getFile(
                new org.eclipse.core.runtime.Path(".settings/metadata-tags.yaml"));
            
            if (yamlFile.exists()) {
                org.eclipse.ui.IWorkbenchPage page = 
                    org.eclipse.ui.PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow()
                        .getActivePage();
                org.eclipse.ui.ide.IDE.openEditor(page, yamlFile);
            } else {
                // Show message that file doesn't exist
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    getSite().getShell(),
                    "Tags File",
                    "Tags file does not exist yet. Assign some tags first.\nExpected path: " + yamlFile.getFullPath());
            }
        } catch (Exception e) {
            Activator.logError("Failed to open tags YAML file", e);
        }
    }

    @Override
    public void setFocus() {
        if (tagsTreeViewer != null && !tagsTreeViewer.getControl().isDisposed()) {
            tagsTreeViewer.getControl().setFocus();
        }
    }
    
    @Override
    public void dispose() {
        tagService.removeTagChangeListener(this);
        // Dispose additional images not managed by ResourceManager
        for (Image img : colorIcons) {
            if (img != null && !img.isDisposed()) {
                img.dispose();
            }
        }
        colorIcons.clear();
        // ResourceManager is tied to parent lifecycle
        super.dispose();
    }
    
    @Override
    public void onTagsChanged(IProject project) {
        Display.getDefault().asyncExec(() -> {
            refreshTagsTree();
        });
    }
    
    @Override
    public void onAssignmentsChanged(IProject project, String objectFqn) {
        Display.getDefault().asyncExec(() -> {
            // Refresh if any project with selected tags changed
            if (selectedTagsByProject.containsKey(project)) {
                updateFilteredResults();
            }
        });
    }
}
