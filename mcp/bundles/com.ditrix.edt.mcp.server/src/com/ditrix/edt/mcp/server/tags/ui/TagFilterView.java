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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.part.ViewPart;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagService.ITagChangeListener;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * View for filtering and browsing metadata objects by tags.
 */
public class TagFilterView extends ViewPart implements ITagChangeListener {
    
    /** The view ID as defined in plugin.xml */
    public static final String ID = "com.ditrix.edt.mcp.server.tags.filterView";
    
    private static final int COLOR_ICON_SIZE = 16;
    
    private TagService tagService;
    private IProject selectedProject;
    
    private CheckboxTableViewer tagsViewer;
    private TableViewer resultsViewer;
    
    private Set<String> selectedTags = new HashSet<>();
    private List<String> filteredObjects = new ArrayList<>();
    
    private Action refreshAction;
    private Action selectAllTagsAction;
    private Action deselectAllTagsAction;
    
    @Override
    public void createPartControl(Composite parent) {
        tagService = TagService.getInstance();
        tagService.addTagChangeListener(this);
        
        parent.setLayout(new FillLayout());
        
        SashForm sashForm = new SashForm(parent, SWT.HORIZONTAL);
        
        // Left panel - Tags
        createTagsPanel(sashForm);
        
        // Right panel - Filtered results
        createResultsPanel(sashForm);
        
        sashForm.setWeights(30, 70);
        
        // Actions
        createActions();
        contributeToActionBars();
        
        // Initial data load
        refreshProjects();
    }
    
    private void createTagsPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Filter by Tags");
        group.setLayout(new FillLayout());
        
        Table table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        
        tagsViewer = new CheckboxTableViewer(table);
        tagsViewer.setContentProvider(ArrayContentProvider.getInstance());
        
        // Color column
        TableViewerColumn colorColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        colorColumn.getColumn().setText("");
        colorColumn.getColumn().setWidth(30);
        colorColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public Image getImage(Object element) {
                if (element instanceof Tag tag) {
                    return createColorIcon(tag.getColor());
                }
                return null;
            }
            
            @Override
            public String getText(Object element) {
                return "";
            }
        });
        
        // Name column
        TableViewerColumn nameColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        nameColumn.getColumn().setText("Tag");
        nameColumn.getColumn().setWidth(120);
        nameColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag) {
                    return tag.getName();
                }
                return "";
            }
        });
        
        // Count column
        TableViewerColumn countColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        countColumn.getColumn().setText("Count");
        countColumn.getColumn().setWidth(50);
        countColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag && selectedProject != null) {
                    Set<String> objects = tagService.findObjectsByTag(selectedProject, tag.getName());
                    return String.valueOf(objects.size());
                }
                return "0";
            }
        });
        
        // Handle checkbox changes
        tagsViewer.addCheckStateListener(new ICheckStateListener() {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event) {
                if (event.getElement() instanceof Tag tag) {
                    if (event.getChecked()) {
                        selectedTags.add(tag.getName());
                    } else {
                        selectedTags.remove(tag.getName());
                    }
                    updateFilteredResults();
                }
            }
        });
    }
    
    private void createResultsPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Matching Objects");
        group.setLayout(new FillLayout());
        
        Table table = new Table(group, SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        
        resultsViewer = new TableViewer(table);
        resultsViewer.setContentProvider(ArrayContentProvider.getInstance());
        
        // FQN column
        TableViewerColumn fqnColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        fqnColumn.getColumn().setText("Object");
        fqnColumn.getColumn().setWidth(300);
        fqnColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element.toString();
            }
        });
        
        // Tags column
        TableViewerColumn tagsColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        tagsColumn.getColumn().setText("Tags");
        tagsColumn.getColumn().setWidth(200);
        tagsColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof String fqn && selectedProject != null) {
                    Set<Tag> tags = tagService.getObjectTags(selectedProject, fqn);
                    return tags.stream()
                        .map(Tag::getName)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                }
                return "";
            }
        });
        
        // Double-click to navigate
        resultsViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                // TODO: Navigate to object in EDT when double-clicked
            }
        });
        
        // Context menu
        createContextMenu();
    }
    
    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                fillContextMenu(manager);
            }
        });
        
        Menu menu = menuMgr.createContextMenu(resultsViewer.getControl());
        resultsViewer.getControl().setMenu(menu);
    }
    
    private void fillContextMenu(IMenuManager manager) {
        IStructuredSelection selection = resultsViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            return;
        }
        
        manager.add(new Action("Copy FQN") {
            @Override
            public void run() {
                String fqn = selection.getFirstElement().toString();
                org.eclipse.swt.dnd.Clipboard clipboard = 
                    new org.eclipse.swt.dnd.Clipboard(Display.getCurrent());
                clipboard.setContents(
                    new Object[] { fqn },
                    new org.eclipse.swt.dnd.Transfer[] { 
                        org.eclipse.swt.dnd.TextTransfer.getInstance() 
                    });
                clipboard.dispose();
            }
        });
    }
    
    private void createActions() {
        refreshAction = new Action("Refresh") {
            @Override
            public void run() {
                refreshProjects();
            }
        };
        refreshAction.setToolTipText("Refresh tags");
        
        selectAllTagsAction = new Action("Select All") {
            @Override
            public void run() {
                tagsViewer.setAllChecked(true);
                selectedTags.clear();
                for (Object element : (List<?>) tagsViewer.getInput()) {
                    if (element instanceof Tag tag) {
                        selectedTags.add(tag.getName());
                    }
                }
                updateFilteredResults();
            }
        };
        
        deselectAllTagsAction = new Action("Deselect All") {
            @Override
            public void run() {
                tagsViewer.setAllChecked(false);
                selectedTags.clear();
                updateFilteredResults();
            }
        };
    }
    
    private void contributeToActionBars() {
        IActionBars bars = getViewSite().getActionBars();
        IToolBarManager manager = bars.getToolBarManager();
        manager.add(refreshAction);
        manager.add(new Separator());
        manager.add(selectAllTagsAction);
        manager.add(deselectAllTagsAction);
    }
    
    private void refreshProjects() {
        // Get first EDT project
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (project.isOpen()) {
                selectedProject = project;
                break;
            }
        }
        
        if (selectedProject != null) {
            refreshTags();
        }
    }
    
    private void refreshTags() {
        if (selectedProject == null) {
            tagsViewer.setInput(new ArrayList<>());
            return;
        }
        
        List<Tag> tags = tagService.getTags(selectedProject);
        tagsViewer.setInput(tags);
        
        // Restore checked state
        for (Tag tag : tags) {
            tagsViewer.setChecked(tag, selectedTags.contains(tag.getName()));
        }
        
        updateFilteredResults();
    }
    
    private void updateFilteredResults() {
        filteredObjects.clear();
        
        if (selectedProject == null || selectedTags.isEmpty()) {
            resultsViewer.setInput(filteredObjects);
            updateResultsCount();
            return;
        }
        
        // Find objects that have ANY of the selected tags
        Map<String, Set<Tag>> matches = tagService.findObjectsByTags(selectedProject, selectedTags);
        filteredObjects.addAll(matches.keySet());
        filteredObjects.sort(String::compareTo);
        
        resultsViewer.setInput(filteredObjects);
        updateResultsCount();
    }
    
    private void updateResultsCount() {
        setPartName("Tag Filter (" + filteredObjects.size() + ")");
    }
    
    private Image createColorIcon(String hexColor) {
        Display display = Display.getCurrent();
        Image image = new Image(display, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
        GC gc = new GC(image);
        
        RGB rgb = hexToRgb(hexColor);
        Color color = new Color(display, rgb);
        gc.setBackground(color);
        gc.fillRectangle(0, 0, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
        gc.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
        gc.drawRectangle(0, 0, COLOR_ICON_SIZE - 1, COLOR_ICON_SIZE - 1);
        
        gc.dispose();
        color.dispose();
        
        return image;
    }
    
    private RGB hexToRgb(String hex) {
        hex = hex.replace("#", "");
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new RGB(r, g, b);
        } catch (Exception e) {
            return new RGB(128, 128, 128);
        }
    }
    
    @Override
    public void setFocus() {
        tagsViewer.getControl().setFocus();
    }
    
    @Override
    public void dispose() {
        tagService.removeTagChangeListener(this);
        super.dispose();
    }
    
    @Override
    public void onTagsChanged(IProject project) {
        Display.getDefault().asyncExec(() -> {
            if (project.equals(selectedProject)) {
                refreshTags();
            }
        });
    }
    
    @Override
    public void onAssignmentsChanged(IProject project, String objectFqn) {
        Display.getDefault().asyncExec(() -> {
            if (project.equals(selectedProject)) {
                updateFilteredResults();
            }
        });
    }
}
