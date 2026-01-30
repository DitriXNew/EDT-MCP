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

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.navigator.util.NavigatorUtil;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * ViewerFilter that filters navigator elements by tag.
 * When search pattern starts with #, this filter searches by tag name
 * instead of object name.
 */
public class TagSearchFilter extends ViewerFilter {
    
    /** Filter ID as registered in plugin.xml */
    public static final String FILTER_ID = "com.ditrix.edt.mcp.server.tags.TagSearchFilter";
    
    private static final String NAVIGATOR_SEARCH_FILTER_ID = 
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSearchFilter";
    
    private Viewer viewer;
    private String lastPattern = "";
    private Set<String> matchingFqns = new HashSet<>();
    private boolean standardFilterDeactivated = false;
    
    /**
     * Default constructor for extension factory.
     */
    public TagSearchFilter() {
    }
    
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        this.viewer = viewer;
        
        String searchPattern = getActiveSearchPattern();
        
        Activator.logInfo("TagSearchFilter.select() called, pattern: '" + searchPattern + "', element: " + element.getClass().getSimpleName());
        
        // Only handle patterns starting with #
        if (searchPattern == null || !searchPattern.startsWith("#")) {
            // Reset flag when pattern is not a tag search
            standardFilterDeactivated = false;
            return true; // Let other filters handle
        }
        
        // Ensure standard filter is deactivated when we're in tag search mode
        // This is done IMMEDIATELY on first call to prevent race conditions
        if (!standardFilterDeactivated) {
            ensureStandardFilterDeactivated();
        }
        
        String tagPattern = searchPattern.substring(1).toLowerCase().trim();
        if (tagPattern.isEmpty()) {
            return true;
        }
        
        // Recalculate matching FQNs if pattern changed
        if (!searchPattern.equals(lastPattern)) {
            lastPattern = searchPattern;
            recalculateMatchingFqns(tagPattern);
            Activator.logInfo("Recalculated matching FQNs: " + matchingFqns.size() + " objects found");
            if (matchingFqns.size() <= 15) {
                Activator.logInfo("Matching FQNs: " + matchingFqns);
            }
        }
        
        // Projects always visible if they have matching children
        if (element instanceof IProject project) {
            boolean result = hasMatchingChildren(project, tagPattern);
            Activator.logInfo("Project " + project.getName() + " visible: " + result);
            return result;
        }
        
        // Check if element matches
        if (element instanceof EObject eObject) {
            String fqn = extractFqn(eObject);
            if (fqn != null) {
                // Check if this FQN or any parent matches
                boolean result = matchesFqnOrParent(fqn);
                // Only log first few
                if (fqn.split("\\.").length <= 4) {
                    Activator.logInfo("Element " + fqn + " visible: " + result);
                }
                return result;
            } else {
                Activator.logInfo("EObject but extractFqn returned null: " + eObject.eClass().getName());
            }
        } else {
            // Check if this is a Navigator folder (e.g., DocumentNavigatorAdapter$Folder)
            String className = element.getClass().getName();
            
            // Handle navigator folder containers
            if (className.contains("NavigatorAdapter$Folder") || className.contains("NavigatorAdapter") 
                    && !className.endsWith("CommonNavigatorAdapter")) {
                String metadataType = extractMetadataTypeFromFolderClass(className);
                if (metadataType != null) {
                    boolean result = hasMatchingFqnsForType(metadataType);
                    Activator.logInfo("Folder for " + metadataType + " visible: " + result);
                    return result;
                }
            }
            
            // Try to unwrap CommonNavigatorAdapter or similar
            EObject unwrapped = unwrapToEObject(element);
            if (unwrapped != null) {
                String fqn = extractFqn(unwrapped);
                if (fqn != null) {
                    boolean result = matchesFqnOrParent(fqn);
                    Activator.logInfo("Unwrapped to " + fqn + " visible: " + result);
                    return result;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Gets the current search pattern from navigator.
     */
    private String getActiveSearchPattern() {
        try {
            if (viewer instanceof CommonViewer commonViewer) {
                CommonNavigator navigator = commonViewer.getCommonNavigator();
                // Try to get pattern through reflection since Navigator is internal
                if (navigator != null) {
                    var method = navigator.getClass().getMethod("getSearchFilterState");
                    var state = method.invoke(navigator);
                    if (state != null) {
                        var getPattern = state.getClass().getMethod("getActivePattern");
                        Object pattern = getPattern.invoke(state);
                        return pattern != null ? pattern.toString() : "";
                    }
                }
            }
        } catch (Exception e) {
            // Fallback - try to get from workbench
        }
        
        // Fallback: try to get navigator from active window
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null && window.getActivePage() != null) {
                var navigatorView = window.getActivePage().findView("com._1c.g5.v8.dt.ui2.navigator");
                if (navigatorView instanceof CommonNavigator navigator) {
                    var method = navigator.getClass().getMethod("getSearchFilterState");
                    var state = method.invoke(navigator);
                    if (state != null) {
                        var getPattern = state.getClass().getMethod("getActivePattern");
                        Object pattern = getPattern.invoke(state);
                        return pattern != null ? pattern.toString() : "";
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("Failed to get search pattern", e);
        }
        
        return "";
    }
    
    /**
     * Ensures the standard NavigatorSearchFilter and StateProvider are deactivated.
     * This is called from within select() to prevent race conditions.
     */
    private void ensureStandardFilterDeactivated() {
        try {
            Activator.logInfo("ensureStandardFilterDeactivated() called");
            
            // First, deactivate the NavigatorContentProviderStateProvider
            // This is the KEY step - it makes content providers return ALL children
            var stateProvider = Activator.getDefault().getNavigatorStateProvider();
            if (stateProvider != null && stateProvider.isActive()) {
                stateProvider.setActive(false);
                Activator.logInfo("Deactivated NavigatorContentProviderStateProvider from TagSearchFilter.select()");
            }
            
            CommonNavigator navigator = null;
            
            // Try to get navigator from viewer
            if (viewer instanceof CommonViewer commonViewer) {
                navigator = commonViewer.getCommonNavigator();
            }
            
            // Fallback to active window
            if (navigator == null) {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null && window.getActivePage() != null) {
                    var navigatorView = window.getActivePage().findView("com._1c.g5.v8.dt.ui2.navigator");
                    if (navigatorView instanceof CommonNavigator nav) {
                        navigator = nav;
                    }
                }
            }
            
            if (navigator != null) {
                var filterService = navigator.getNavigatorContentService().getFilterService();
                boolean isActive = filterService.isActive(NAVIGATOR_SEARCH_FILTER_ID);
                
                if (isActive) {
                    NavigatorUtil.deactivateFilter(navigator, NAVIGATOR_SEARCH_FILTER_ID);
                    Activator.logInfo("Deactivated standard search filter from TagSearchFilter.select()");
                }
                standardFilterDeactivated = true;
            } else {
                Activator.logInfo("Navigator is null, cannot deactivate standard filter");
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate filters from select()", e);
        }
    }
    
    /**
     * Recalculates matching FQNs across all projects for the given tag pattern.
     */
    private void recalculateMatchingFqns(String tagPattern) {
        matchingFqns.clear();
        
        TagService tagService = TagService.getInstance();
        
        // Search in all open projects
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) {
                continue;
            }
            
            TagStorage storage = tagService.getTagStorage(project);
            
            // Find all tags matching the pattern
            for (Tag tag : storage.getTags()) {
                if (tag.getName().toLowerCase().contains(tagPattern)) {
                    // Add all objects with this tag
                    matchingFqns.addAll(storage.getObjectsByTag(tag.getName()));
                }
            }
        }
    }
    
    /**
     * Checks if project has any children matching the tag pattern.
     */
    private boolean hasMatchingChildren(IProject project, String tagPattern) {
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);
        
        // Check if any tag matches
        for (Tag tag : storage.getTags()) {
            if (tag.getName().toLowerCase().contains(tagPattern)) {
                Set<String> objects = storage.getObjectsByTag(tag.getName());
                if (!objects.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Checks if FQN matches directly or is a parent of a matching FQN.
     */
    private boolean matchesFqnOrParent(String fqn) {
        // Direct match
        if (matchingFqns.contains(fqn)) {
            return true;
        }
        
        // Check if this is a parent of any matching FQN
        String prefix = fqn + ".";
        for (String matchingFqn : matchingFqns) {
            if (matchingFqn.startsWith(prefix)) {
                return true;
            }
        }
        
        // Check if this FQN is part of a matching FQN path
        // (to show intermediate nodes in the tree)
        for (String matchingFqn : matchingFqns) {
            if (matchingFqn.startsWith(fqn)) {
                // This element is on the path to a matching element
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Extracts FQN from an EObject using reflection.
     */
    private String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                String typeName = current.eClass().getName();
                
                if ("Configuration".equals(typeName) || typeName.startsWith("Md")) {
                    break;
                }
                
                String name = getObjectName(current);
                
                if (name != null && !name.isEmpty()) {
                    String part = typeName + "." + name;
                    if (fqnBuilder.length() > 0) {
                        fqnBuilder.insert(0, ".");
                    }
                    fqnBuilder.insert(0, part);
                }
                
                current = current.eContainer();
            }
            
            return fqnBuilder.length() > 0 ? fqnBuilder.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Gets object name using reflection.
     */
    private String getObjectName(EObject eObject) {
        try {
            for (java.lang.reflect.Method m : eObject.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object name = m.invoke(eObject);
                    return name != null ? name.toString() : null;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /**
     * Attempts to unwrap a wrapper object to find an EObject inside.
     * This handles various navigator wrapper classes.
     */
    private EObject unwrapToEObject(Object element) {
        if (element == null) {
            return null;
        }
        
        try {
            // Try common wrapper methods: getTarget(), getData(), getElement(), getValue()
            String[] methodNames = {"getTarget", "getData", "getElement", "getValue", "getObject", "getModel"};
            
            for (String methodName : methodNames) {
                try {
                    var method = element.getClass().getMethod(methodName);
                    Object result = method.invoke(element);
                    if (result instanceof EObject eObj) {
                        return eObj;
                    }
                    // Recurse one level
                    if (result != null && !(result instanceof EObject)) {
                        EObject nested = unwrapToEObject(result);
                        if (nested != null) {
                            return nested;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // Method not found, try next
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return null;
    }
    
    /**
     * Extracts metadata type from Navigator folder class name.
     * E.g., "DocumentNavigatorAdapter$Folder" -> "Document"
     */
    private String extractMetadataTypeFromFolderClass(String className) {
        // Format: com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter$Folder
        // or com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter
        
        // Map of class name patterns to FQN prefixes
        java.util.Map<String, String> typeMap = new java.util.HashMap<>();
        typeMap.put("Document", "Document");
        typeMap.put("Catalog", "Catalog");
        typeMap.put("Enum", "Enum");
        typeMap.put("Report", "Report");
        typeMap.put("DataProcessor", "DataProcessor");
        typeMap.put("Constant", "Constant");
        typeMap.put("InformationRegister", "InformationRegister");
        typeMap.put("AccumulationRegister", "AccumulationRegister");
        typeMap.put("AccountingRegister", "AccountingRegister");
        typeMap.put("CalculationRegister", "CalculationRegister");
        typeMap.put("ChartOfCharacteristicTypes", "ChartOfCharacteristicTypes");
        typeMap.put("ChartOfAccounts", "ChartOfAccounts");
        typeMap.put("ChartOfCalculationTypes", "ChartOfCalculationTypes");
        typeMap.put("BusinessProcess", "BusinessProcess");
        typeMap.put("Task", "Task");
        typeMap.put("ExternalDataSource", "ExternalDataSource");
        typeMap.put("DocumentJournal", "DocumentJournal");
        typeMap.put("Subsystem", "Subsystem");
        typeMap.put("CommonModule", "CommonModule");
        typeMap.put("CommonForm", "CommonForm");
        typeMap.put("StyleItem", "StyleItem");
        
        for (java.util.Map.Entry<String, String> entry : typeMap.entrySet()) {
            if (className.contains(entry.getKey() + "NavigatorAdapter")) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Checks if any matching FQN starts with the given metadata type.
     */
    private boolean hasMatchingFqnsForType(String metadataType) {
        String prefix = metadataType + ".";
        for (String fqn : matchingFqns) {
            if (fqn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}