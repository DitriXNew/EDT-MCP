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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.SelectionDialog;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dialog for filtering navigator by tags.
 * Shows a tree of projects with their tags as checkboxes.
 * Similar to EDT's "Filter by Subsystems" dialog.
 */
public class FilterByTagDialog extends SelectionDialog {
    
    private static final int TURN_OFF_ID = 1024;
    
    private CheckboxTreeViewer treeViewer;
    private final TagService tagService;
    private final IV8ProjectManager v8ProjectManager;
    
    // Selected tags per project
    private Map<IProject, Set<Tag>> selectedTags = new HashMap<>();
    
    // Result flags
    private boolean isFilterEnabled = false;
    private boolean isTurnedOff = false;
    private boolean showUntaggedOnly = false;
    
    // ResourceManager for image lifecycle
    private ResourceManager resourceManager;
    // Additional images to dispose (from icon descriptors not using factory)
    private List<Image> disposableImages = new ArrayList<>();
    
    // Search filter
    private Text searchText;
    private String searchPattern = "";
    
    /**
     * Creates a new filter dialog.
     * 
     * @param parentShell the parent shell
     * @param v8ProjectManager the V8 project manager
     */
    public FilterByTagDialog(Shell parentShell, IV8ProjectManager v8ProjectManager) {
        super(parentShell);
        this.tagService = TagService.getInstance();
        this.v8ProjectManager = v8ProjectManager;
        setTitle(Messages.FilterByTagDialog_Title);
    }
    
    /**
     * Returns whether filter was turned on.
     */
    public boolean isFilterEnabled() {
        return isFilterEnabled;
    }
    
    /**
     * Returns whether filter was turned off.
     */
    public boolean isTurnedOff() {
        return isTurnedOff;
    }
    
    /**
     * Returns the selected tags per project.
     */
    public Map<IProject, Set<Tag>> getSelectedTags() {
        return selectedTags;
    }
    
    /**
     * Returns whether "show untagged objects only" is selected.
     */
    public boolean isShowUntaggedOnly() {
        return showUntaggedOnly;
    }
    
    /**
     * Sets the initial selection (currently selected tags).
     */
    public void setInitialSelection(Map<IProject, Set<Tag>> initialSelection) {
        if (initialSelection != null) {
            this.selectedTags = new HashMap<>(initialSelection);
        }
    }
    
    /**
     * Sets the initial state for "show untagged only" option.
     */
    public void setInitialShowUntaggedOnly(boolean showUntaggedOnly) {
        this.showUntaggedOnly = showUntaggedOnly;
    }
    
    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == TURN_OFF_ID) {
            isTurnedOff = true;
            turnOffPressed();
        } else if (buttonId == IDialogConstants.OK_ID) {
            isFilterEnabled = true;
            okPressed();
        } else {
            cancelPressed();
        }
    }
    
    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, Messages.FilterByTagDialog_SetButton, true);
        createButton(parent, TURN_OFF_ID, Messages.FilterByTagDialog_TurnOffButton, false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).applyTo(container);
        
        // Create resource manager tied to container lifecycle
        resourceManager = new LocalResourceManager(TagColorIconFactory.getJFaceResources(), container);
        
        // Info label
        Label infoLabel = new Label(container, SWT.WRAP);
        infoLabel.setText(Messages.FilterByTagDialog_Description);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(infoLabel);
        
        // Search bar with icon buttons
        Composite searchBar = new Composite(container, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(5, 0).applyTo(searchBar);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(searchBar);
        
        // Load icons for Select All / Deselect All buttons
        ImageDescriptor selectAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/check_all.png");
        ImageDescriptor deselectAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/uncheck_all.png");
        
        // Select All button (icon only)
        Button selectAllButton = new Button(searchBar, SWT.PUSH);
        selectAllButton.setToolTipText(Messages.FilterByTagDialog_SelectAll);
        if (selectAllIcon != null) {
            selectAllButton.setImage(resourceManager.get(selectAllIcon));
        }
        selectAllButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                selectAll(true);
            }
        });
        
        // Deselect All button (icon only)
        Button deselectAllButton = new Button(searchBar, SWT.PUSH);
        deselectAllButton.setToolTipText(Messages.FilterByTagDialog_DeselectAll);
        if (deselectAllIcon != null) {
            deselectAllButton.setImage(resourceManager.get(deselectAllIcon));
        }
        deselectAllButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                selectAll(false);
            }
        });
        
        // Search filter text field
        searchText = new Text(searchBar, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        searchText.setMessage(Messages.FilterByTagDialog_SearchPlaceholder);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(searchText);
        searchText.addModifyListener((ModifyListener) e -> {
            searchPattern = searchText.getText().toLowerCase().trim();
            treeViewer.refresh();
            if (!searchPattern.isEmpty()) {
                treeViewer.expandAll();
            }
        });
        
        // Show untagged objects only checkbox
        Button showUntaggedCheckbox = new Button(container, SWT.CHECK);
        showUntaggedCheckbox.setText(Messages.FilterByTagDialog_ShowUntaggedOnly);
        showUntaggedCheckbox.setToolTipText(Messages.FilterByTagDialog_ShowUntaggedOnlyTooltip);
        showUntaggedCheckbox.setSelection(showUntaggedOnly);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(showUntaggedCheckbox);
        showUntaggedCheckbox.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showUntaggedOnly = showUntaggedCheckbox.getSelection();
                // Disable tag tree when showing untagged only
                treeViewer.getTree().setEnabled(!showUntaggedOnly);
            }
        });
        
        // Tree viewer with checkboxes and columns
        Tree tree = new Tree(container, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.CHECK | SWT.FULL_SELECTION);
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 300).applyTo(tree);
        
        treeViewer = new CheckboxTreeViewer(tree);
        treeViewer.setContentProvider(new TagTreeContentProvider());
        
        // Name column
        TreeViewerColumn nameColumn = new TreeViewerColumn(treeViewer, SWT.NONE);
        nameColumn.getColumn().setText("Name");
        nameColumn.getColumn().setWidth(180);
        nameColumn.setLabelProvider(new TagNameLabelProvider());
        
        // Description column
        TreeViewerColumn descColumn = new TreeViewerColumn(treeViewer, SWT.NONE);
        descColumn.getColumn().setText("Description");
        descColumn.getColumn().setWidth(200);
        descColumn.setLabelProvider(new TagDescriptionLabelProvider());
        
        treeViewer.addFilter(new TagSearchViewerFilter());
        treeViewer.setInput(ResourcesPlugin.getWorkspace().getRoot());
        
        // Set initial checked state
        applyInitialSelection();
        
        // Handle check state changes
        treeViewer.addCheckStateListener(new ICheckStateListener() {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event) {
                handleCheckStateChange(event);
            }
        });
        
        // Context menu for editing tags
        createContextMenu(tree);
        
        // Expand all
        treeViewer.expandAll();
        
        return container;
    }
    
    /**
     * Creates context menu for the tree.
     */
    private void createContextMenu(Tree tree) {
        MenuManager menuManager = new MenuManager();
        
        // Edit Tag action
        Action editTagAction = new Action(Messages.FilterByTagDialog_EditTag) {
            @Override
            public void run() {
                IStructuredSelection selection = (IStructuredSelection) treeViewer.getSelection();
                Object firstElement = selection.getFirstElement();
                if (firstElement instanceof TagEntry tagEntry) {
                    String oldName = tagEntry.tag.getName();
                    EditTagDialog dialog = new EditTagDialog(getShell(), tagEntry.tag);
                    if (dialog.open() == EditTagDialog.OK) {
                        // Update tag using TagService
                        tagService.updateTag(
                            tagEntry.project, 
                            oldName,
                            dialog.getTagName(), 
                            dialog.getTagColor(), 
                            dialog.getTagDescription()
                        );
                        // Refresh tree
                        treeViewer.refresh();
                    }
                }
            }
        };
        menuManager.add(editTagAction);
        
        Menu menu = menuManager.createContextMenu(tree);
        tree.setMenu(menu);
        
        // Enable/disable based on selection
        tree.addMenuDetectListener(e -> {
            IStructuredSelection selection = (IStructuredSelection) treeViewer.getSelection();
            Object firstElement = selection.getFirstElement();
            editTagAction.setEnabled(firstElement instanceof TagEntry);
        });
    }
    
    @Override
    protected Point getInitialSize() {
        return new Point(600, 500);
    }
    
    @Override
    protected void okPressed() {
        // Collect selected tags
        collectSelectedTags();
        super.okPressed();
    }
    
    protected void turnOffPressed() {
        selectedTags.clear();
        super.okPressed();
    }
    
    @Override
    public boolean close() {
        // Dispose additional images not managed by ResourceManager
        for (Image img : disposableImages) {
            if (img != null && !img.isDisposed()) {
                img.dispose();
            }
        }
        disposableImages.clear();
        // ResourceManager is tied to container lifecycle, no explicit disposal needed
        return super.close();
    }
    
    private void applyInitialSelection() {
        for (Map.Entry<IProject, Set<Tag>> entry : selectedTags.entrySet()) {
            for (Tag tag : entry.getValue()) {
                TagEntry tagEntry = new TagEntry(entry.getKey(), tag);
                treeViewer.setChecked(tagEntry, true);
            }
            // Update project grayed state
            updateProjectCheckState(entry.getKey());
        }
    }
    
    private void handleCheckStateChange(CheckStateChangedEvent event) {
        Object element = event.getElement();
        boolean checked = event.getChecked();
        
        if (element instanceof IProject project) {
            // Check/uncheck all tags in this project
            List<Tag> tags = tagService.getTags(project);
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                treeViewer.setChecked(tagEntry, checked);
            }
            treeViewer.setGrayed(project, false);
        } else if (element instanceof TagEntry tagEntry) {
            // Update parent project grayed state
            updateProjectCheckState(tagEntry.project);
        }
    }
    
    private void updateProjectCheckState(IProject project) {
        List<Tag> tags = tagService.getTags(project);
        if (tags.isEmpty()) {
            treeViewer.setChecked(project, false);
            treeViewer.setGrayed(project, false);
            return;
        }
        
        int checkedCount = 0;
        for (Tag tag : tags) {
            TagEntry tagEntry = new TagEntry(project, tag);
            if (treeViewer.getChecked(tagEntry)) {
                checkedCount++;
            }
        }
        
        if (checkedCount == 0) {
            treeViewer.setChecked(project, false);
            treeViewer.setGrayed(project, false);
        } else if (checkedCount == tags.size()) {
            treeViewer.setChecked(project, true);
            treeViewer.setGrayed(project, false);
        } else {
            treeViewer.setChecked(project, true);
            treeViewer.setGrayed(project, true);
        }
    }
    
    private void selectAll(boolean select) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project == null) continue;
            
            treeViewer.setChecked(project, select);
            treeViewer.setGrayed(project, false);
            
            List<Tag> tags = tagService.getTags(project);
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                treeViewer.setChecked(tagEntry, select);
            }
        }
    }
    
    private void collectSelectedTags() {
        selectedTags.clear();
        
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project == null) continue;
            
            List<Tag> tags = tagService.getTags(project);
            Set<Tag> selected = new HashSet<>();
            
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                if (treeViewer.getChecked(tagEntry) && !treeViewer.getGrayed(tagEntry)) {
                    selected.add(tag);
                }
            }
            
            if (!selected.isEmpty()) {
                selectedTags.put(project, selected);
            }
        }
    }
    
    private Image createColorIcon(String hexColor) {
        return resourceManager.get(TagColorIconFactory.getColorIcon(hexColor, 12));
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
     * Content provider for the tag tree.
     */
    private class TagTreeContentProvider implements ITreeContentProvider {
        
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof IWorkspaceRoot root) {
                List<IProject> projects = new ArrayList<>();
                for (IProject project : root.getProjects()) {
                    if (!project.isOpen()) continue;
                    IV8Project v8Project = v8ProjectManager.getProject(project);
                    if (v8Project == null) continue;
                    
                    // Only show projects that have tags
                    List<Tag> tags = tagService.getTags(project);
                    if (!tags.isEmpty()) {
                        projects.add(project);
                    }
                }
                return projects.toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof IProject project) {
                List<Tag> tags = tagService.getTags(project);
                List<TagEntry> entries = new ArrayList<>();
                for (Tag tag : tags) {
                    entries.add(new TagEntry(project, tag));
                }
                return entries.toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object getParent(Object element) {
            if (element instanceof TagEntry tagEntry) {
                return tagEntry.project;
            }
            if (element instanceof IProject) {
                return ResourcesPlugin.getWorkspace().getRoot();
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
    
    /**
     * Label provider for Name column.
     * Shows project icon for projects, color icon for tags.
     */
    private class TagNameLabelProvider extends ColumnLabelProvider {
        
        @Override
        public String getText(Object element) {
            if (element instanceof IProject project) {
                return project.getName();
            }
            if (element instanceof TagEntry tagEntry) {
                return tagEntry.tag.getName();
            }
            return "";
        }
        
        @Override
        public Image getImage(Object element) {
            if (element instanceof IProject) {
                return PlatformUI.getWorkbench().getSharedImages()
                    .getImage(IDE.SharedImages.IMG_OBJ_PROJECT);
            }
            if (element instanceof TagEntry tagEntry) {
                return createColorIcon(tagEntry.tag.getColor());
            }
            return null;
        }
    }
    
    /**
     * Label provider for Description column.
     */
    private class TagDescriptionLabelProvider extends ColumnLabelProvider {
        
        @Override
        public String getText(Object element) {
            if (element instanceof TagEntry tagEntry) {
                String description = tagEntry.tag.getDescription();
                return description != null ? description : "";
            }
            return "";
        }
    }
    
    /**
     * Filter for searching tags by name or description.
     */
    private class TagSearchViewerFilter extends ViewerFilter {
        
        @Override
        public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element) {
            if (searchPattern.isEmpty()) {
                return true;
            }
            
            if (element instanceof IProject project) {
                // Show project if any of its tags match
                List<Tag> tags = tagService.getTags(project);
                for (Tag tag : tags) {
                    if (matchesSearch(tag)) {
                        return true;
                    }
                }
                return false;
            }
            
            if (element instanceof TagEntry tagEntry) {
                return matchesSearch(tagEntry.tag);
            }
            
            return true;
        }
        
        private boolean matchesSearch(Tag tag) {
            if (tag.getName().toLowerCase().contains(searchPattern)) {
                return true;
            }
            String description = tag.getDescription();
            if (description != null && description.toLowerCase().contains(searchPattern)) {
                return true;
            }
            return false;
        }
    }
}
