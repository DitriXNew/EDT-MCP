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
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
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
import org.eclipse.ui.part.ViewPart;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagService.ITagChangeListener;
import com.ditrix.edt.mcp.server.tags.model.Tag;
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
    private IProject selectedProject;
    
    private CheckboxTableViewer tagsViewer;
    private TableViewer resultsViewer;
    
    private Set<String> selectedTags = new HashSet<>();
    private List<String> filteredObjects = new ArrayList<>();
    
    /** Search filter text */
    private Text searchText;
    
    /** Current search pattern (regex) */
    private Pattern searchPattern;
    
    /** Search all tags checkbox */
    private Button searchAllTagsCheckbox;
    
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
        
        // Initial data load
        refreshProjects();
    }
    
    private void createTagsPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Filter by Tags");
        GridLayout layout = new GridLayout(4, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        group.setLayout(layout);
        
        // Buttons row
        Button refreshBtn = new Button(group, SWT.PUSH);
        refreshBtn.setText("Refresh");
        refreshBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                refreshProjects();
            }
        });
        
        Button selectAllBtn = new Button(group, SWT.PUSH);
        selectAllBtn.setText("Select All");
        selectAllBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                tagsViewer.setAllChecked(true);
                selectedTags.clear();
                for (Object element : (java.util.List<?>) tagsViewer.getInput()) {
                    if (element instanceof Tag tag) {
                        selectedTags.add(tag.getName());
                    }
                }
                updateFilteredResults();
            }
        });
        
        Button deselectAllBtn = new Button(group, SWT.PUSH);
        deselectAllBtn.setText("Deselect All");
        deselectAllBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                tagsViewer.setAllChecked(false);
                selectedTags.clear();
                updateFilteredResults();
            }
        });
        
        // Open YAML button
        Button openYamlBtn = new Button(group, SWT.PUSH);
        openYamlBtn.setText("Open YAML");
        openYamlBtn.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                openTagsYamlFile();
            }
        });
        
        // Table
        Table table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1);
        table.setLayoutData(tableData);
        
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
        
        // Name column - wider
        TableViewerColumn nameColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        nameColumn.getColumn().setText("Tag");
        nameColumn.getColumn().setWidth(150);
        nameColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag) {
                    return tag.getName();
                }
                return "";
            }
        });
        
        // Count column - shows filtered count when searching
        TableViewerColumn countColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        countColumn.getColumn().setText("Count");
        countColumn.getColumn().setWidth(60);
        countColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag && selectedProject != null) {
                    Set<String> objects = tagService.findObjectsByTag(selectedProject, tag.getName());
                    // If searching, show filtered count
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
        
        // Search all tags checkbox
        searchAllTagsCheckbox = new Button(group, SWT.CHECK);
        searchAllTagsCheckbox.setText("Filter tags");
        searchAllTagsCheckbox.setToolTipText("When checked, only show tags that have matching objects");
        searchAllTagsCheckbox.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                refreshTags();
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
        
        // FQN column - wider to show full paths, with icon
        TableViewerColumn fqnColumn = new TableViewerColumn(resultsViewer, SWT.NONE);
        fqnColumn.getColumn().setText("Object");
        fqnColumn.getColumn().setWidth(450);
        fqnColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof String fqn) {
                    return simplifyFqn(fqn);
                }
                return element.toString();
            }
            
            @Override
            public Image getImage(Object element) {
                if (element instanceof String fqn) {
                    return getObjectIcon(fqn);
                }
                return null;
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
        
        // Double-click to navigate to object
        resultsViewer.addDoubleClickListener(event -> {
            IStructuredSelection selection = resultsViewer.getStructuredSelection();
            if (!selection.isEmpty() && selection.getFirstElement() instanceof String fqn) {
                navigateToObject(fqn);
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
                        if (obj instanceof String fqn) {
                            sb.append(simplifyFqn(fqn));
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
                    for (String fqn : filteredObjects) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(simplifyFqn(fqn));
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
        
        // If "Filter tags" checkbox is checked, only show tags with matching objects
        if (searchAllTagsCheckbox != null && searchAllTagsCheckbox.getSelection() && searchPattern != null) {
            List<Tag> filteredTags = new ArrayList<>();
            for (Tag tag : tags) {
                // Check if this tag has any objects matching the search pattern
                Set<String> objects = tagService.findObjectsByTag(selectedProject, tag.getName());
                for (String fqn : objects) {
                    if (matchesSearch(fqn)) {
                        filteredTags.add(tag);
                        break;
                    }
                }
            }
            tags = filteredTags;
        }
        
        tagsViewer.setInput(tags);
        
        // Restore checked state
        for (Tag tag : tags) {
            tagsViewer.setChecked(tag, selectedTags.contains(tag.getName()));
        }
        
        updateFilteredResults();
    }
    
    /**
     * Update the search pattern from the search text field.
     */
    private void updateSearchPattern() {
        String text = searchText.getText().trim();
        if (text.isEmpty()) {
            searchPattern = null;
        } else {
            try {
                searchPattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
                searchText.setForeground(null); // Reset to default color
            } catch (PatternSyntaxException e) {
                // Invalid regex - show error color
                searchText.setForeground(Display.getCurrent().getSystemColor(SWT.COLOR_RED));
                searchPattern = null;
            }
        }
        
        // Refresh tags to update filtered counts
        if (searchAllTagsCheckbox != null && searchAllTagsCheckbox.getSelection()) {
            // Full refresh if filtering is enabled (will call updateFilteredResults)
            refreshTags();
        } else {
            // Just refresh the tags table to update the count column
            if (tagsViewer != null && !tagsViewer.getControl().isDisposed()) {
                tagsViewer.refresh();
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
    
    private void updateFilteredResults() {
        filteredObjects.clear();
        
        if (selectedProject == null || selectedTags.isEmpty()) {
            resultsViewer.setInput(filteredObjects);
            updateResultsCount();
            return;
        }
        
        // Find objects that have ANY of the selected tags
        Map<String, Set<Tag>> matches = tagService.findObjectsByTags(selectedProject, selectedTags);
        
        // Apply search filter
        for (String fqn : matches.keySet()) {
            if (matchesSearch(fqn)) {
                filteredObjects.add(fqn);
            }
        }
        
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
    
    /**
     * Navigate to a metadata object in EDT navigator.
     * Uses EDT's IResourceLookup to find and open the object.
     */
    private void navigateToObject(String fqn) {
        if (selectedProject == null || fqn == null) {
            return;
        }
        
        try {
            // Get configuration provider
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider configProvider = 
                Activator.getDefault().getConfigurationProvider();
            
            if (configProvider == null) {
                return;
            }
            
            com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration = 
                configProvider.getConfiguration(selectedProject);
            if (configuration == null) {
                return;
            }
            
            // Find the object by FQN - split into type and name
            String[] parts = fqn.split("\\.", 2);
            if (parts.length != 2) {
                return;
            }
            
            String typeName = parts[0];
            String objectName = parts[1];
            
            // Try to find the object in configuration
            org.eclipse.emf.ecore.EObject targetObject = findMdObject(configuration, typeName, objectName);
            
            if (targetObject != null) {
                // Open in editor using EDT's OpenHelper
                Display.getDefault().asyncExec(() -> {
                    try {
                        // Use OpenHelper to open the object
                        com._1c.g5.v8.dt.ui.util.OpenHelper openHelper = 
                            new com._1c.g5.v8.dt.ui.util.OpenHelper();
                        openHelper.openEditor(targetObject);
                    } catch (Exception e) {
                        Activator.logError("Failed to open object: " + fqn, e);
                    }
                });
            }
        } catch (Exception e) {
            Activator.logError("Failed to navigate to object: " + fqn, e);
        }
    }
    
    /**
     * Find metadata object by type and name.
     */
    private org.eclipse.emf.ecore.EObject findMdObject(
            com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration, 
            String typeName, String objectName) {
        
        // Map type name to configuration feature
        org.eclipse.emf.common.util.EList<?> objects = null;
        
        switch (typeName) {
            case "CommonModule":
                objects = configuration.getCommonModules();
                break;
            case "Catalog":
                objects = configuration.getCatalogs();
                break;
            case "Document":
                objects = configuration.getDocuments();
                break;
            case "InformationRegister":
                objects = configuration.getInformationRegisters();
                break;
            case "AccumulationRegister":
                objects = configuration.getAccumulationRegisters();
                break;
            case "Enum":
                objects = configuration.getEnums();
                break;
            case "Report":
                objects = configuration.getReports();
                break;
            case "DataProcessor":
                objects = configuration.getDataProcessors();
                break;
            case "Constant":
                objects = configuration.getConstants();
                break;
            case "CommonPicture":
                objects = configuration.getCommonPictures();
                break;
            case "CommonTemplate":
                objects = configuration.getCommonTemplates();
                break;
            case "ExchangePlan":
                objects = configuration.getExchangePlans();
                break;
            case "WebService":
                objects = configuration.getWebServices();
                break;
            case "HTTPService":
                objects = configuration.getHttpServices();
                break;
            case "Role":
                objects = configuration.getRoles();
                break;
            case "Subsystem":
                objects = configuration.getSubsystems();
                break;
            case "Style":
                objects = configuration.getStyles();
                break;
            case "StyleItem":
                objects = configuration.getStyleItems();
                break;
            case "CommonForm":
                objects = configuration.getCommonForms();
                break;
            case "CommonCommand":
                objects = configuration.getCommonCommands();
                break;
            case "CommonAttribute":
                objects = configuration.getCommonAttributes();
                break;
            case "SessionParameter":
                objects = configuration.getSessionParameters();
                break;
            case "ScheduledJob":
                objects = configuration.getScheduledJobs();
                break;
            case "FunctionalOption":
                objects = configuration.getFunctionalOptions();
                break;
            case "FunctionalOptionsParameter":
                objects = configuration.getFunctionalOptionsParameters();
                break;
            case "DefinedType":
                objects = configuration.getDefinedTypes();
                break;
            case "EventSubscription":
                objects = configuration.getEventSubscriptions();
                break;
            case "BusinessProcess":
                objects = configuration.getBusinessProcesses();
                break;
            case "Task":
                objects = configuration.getTasks();
                break;
            case "ChartsOfAccounts":
            case "ChartOfAccounts":
                objects = configuration.getChartsOfAccounts();
                break;
            case "ChartsOfCalculationTypes":
            case "ChartOfCalculationTypes":
                objects = configuration.getChartsOfCalculationTypes();
                break;
            case "ChartsOfCharacteristicTypes":
            case "ChartOfCharacteristicTypes":
                objects = configuration.getChartsOfCharacteristicTypes();
                break;
            case "TabularSectionAttribute":
            case "Attribute":
                // These are nested - skip for now
                return null;
            default:
                return null;
        }
        
        if (objects != null) {
            for (Object obj : objects) {
                if (obj instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject mdObj) {
                    if (objectName.equals(mdObj.getName())) {
                        return mdObj;
                    }
                }
            }
        }
        
        return null;
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
     */
    private void openTagsYamlFile() {
        if (selectedProject == null) {
            return;
        }
        
        try {
            org.eclipse.core.resources.IFile yamlFile = selectedProject.getFile(
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
