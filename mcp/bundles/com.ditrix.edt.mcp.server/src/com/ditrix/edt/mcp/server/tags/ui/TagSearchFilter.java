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
    
    private Viewer viewer;
    private String lastPattern = "";
    private Set<String> matchingFqns = new HashSet<>();
    
    /**
     * Default constructor for extension factory.
     */
    public TagSearchFilter() {
    }
    
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        this.viewer = viewer;

        String searchPattern = getActiveSearchPattern();

        // Only handle patterns starting with #
        if (searchPattern == null || !searchPattern.startsWith("#")) {
            return true; // Let other filters handle
        }
        
        String tagPattern = searchPattern.substring(1).toLowerCase().trim();
        if (tagPattern.isEmpty()) {
            return true;
        }
        
        // Recalculate matching FQNs if pattern changed
        if (!searchPattern.equals(lastPattern)) {
            lastPattern = searchPattern;
            recalculateMatchingFqns(tagPattern);
            Activator.logInfo("Tag search: " + matchingFqns.size() + " objects found for #" + tagPattern);
        }
        
        // Projects always visible if they have matching children
        if (element instanceof IProject project) {
            return hasMatchingChildren(project, tagPattern);
        }
        
        // Check if element matches
        if (element instanceof EObject eObject) {
            // Special handling for Configuration - always visible if there are matching FQNs
            String typeName = eObject.eClass().getName();
            if ("Configuration".equals(typeName)) {
                return !matchingFqns.isEmpty();
            }
            
            String fqn = extractFqn(eObject);
            if (fqn != null) {
                // Check if this FQN or any parent matches
                boolean result = matchesFqnOrParent(fqn);
                
                // Special handling for Subsystems: parent subsystem should be visible if ANY child matches
                if (!result && "Subsystem".equals(typeName)) {
                    result = hasMatchingChildSubsystem(eObject);
                }
                
                return result;
            } else {
                // If we can't extract FQN, assume visible (might be a special element)
                return true;
            }
        } else {
            // Check if this is a Navigator folder (e.g., DocumentNavigatorAdapter$Folder)
            String className = element.getClass().getName();
            
            // Handle CommonNavigatorAdapter - it's the "Common" folder containing subsystems, common modules, etc.
            if (className.endsWith("CommonNavigatorAdapter")) {
                // Check if any of the "common" types have matching FQNs
                boolean hasCommonMatches = hasMatchingFqnsForType("Subsystem") ||
                    hasMatchingFqnsForType("CommonModule") ||
                    hasMatchingFqnsForType("SessionParameter") ||
                    hasMatchingFqnsForType("Role") ||
                    hasMatchingFqnsForType("CommonAttribute") ||
                    hasMatchingFqnsForType("ExchangePlan") ||
                    hasMatchingFqnsForType("FilterCriterion") ||
                    hasMatchingFqnsForType("EventSubscription") ||
                    hasMatchingFqnsForType("ScheduledJob") ||
                    hasMatchingFqnsForType("Bot") ||
                    hasMatchingFqnsForType("FunctionalOption") ||
                    hasMatchingFqnsForType("FunctionalOptionsParameter") ||
                    hasMatchingFqnsForType("DefinedType") ||
                    hasMatchingFqnsForType("SettingsStorage") ||
                    hasMatchingFqnsForType("CommonForm") ||
                    hasMatchingFqnsForType("CommonCommand") ||
                    hasMatchingFqnsForType("CommandGroup") ||
                    hasMatchingFqnsForType("CommonTemplate") ||
                    hasMatchingFqnsForType("CommonPicture") ||
                    hasMatchingFqnsForType("XDTOPackage") ||
                    hasMatchingFqnsForType("WebService") ||
                    hasMatchingFqnsForType("HTTPService") ||
                    hasMatchingFqnsForType("WSReference") ||
                    hasMatchingFqnsForType("WebSocketClient") ||
                    hasMatchingFqnsForType("IntegrationService") ||
                    hasMatchingFqnsForType("PaletteColor") ||
                    hasMatchingFqnsForType("StyleItem");
                return hasCommonMatches;
            }
            
            // Handle navigator folder containers - fix operator precedence
            if (className.contains("NavigatorAdapter$Folder") || 
                    (className.contains("NavigatorAdapter") && !className.endsWith("CommonNavigatorAdapter"))) {
                
                // First, try to handle as a nested object folder (Attributes, TabularSections, etc.)
                // These folders have a parent EObject and a model object name
                Boolean nestedResult = checkNestedObjectFolder(element);
                if (nestedResult != null) {
                    return nestedResult;
                }
                
                // Fall back to type-based check for top-level folders
                String metadataType = extractMetadataTypeFromFolderClass(className);
                if (metadataType != null) {
                    return hasMatchingFqnsForType(metadataType);
                }
            }
            
            // Try to unwrap CommonNavigatorAdapter or similar
            EObject unwrapped = unwrapToEObject(element);
            if (unwrapped != null) {
                String fqn = extractFqn(unwrapped);
                if (fqn != null) {
                    return matchesFqnOrParent(fqn);
                }
            }
            
            // IMPORTANT: Unknown elements should NOT be visible by default during tag search!
            // Only show elements we explicitly matched
            return false;
        }
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
     * Checks if a subsystem has any child subsystems that match tags.
     * This handles nested subsystems where the parent FQN is just "Subsystem.ParentName"
     * but child subsystems have separate FQNs like "Subsystem.ChildName".
     * 
     * @param subsystemEObject the parent subsystem EObject
     * @return true if any child subsystem or descendant matches
     */
    private boolean hasMatchingChildSubsystem(EObject subsystemEObject) {
        try {
            // Get child subsystems using reflection
            java.lang.reflect.Method getSubsystems = subsystemEObject.getClass().getMethod("getSubsystems");
            Object result = getSubsystems.invoke(subsystemEObject);
            
            if (result instanceof java.util.Collection<?> children) {
                for (Object child : children) {
                    if (child instanceof EObject childEObject) {
                        String childFqn = extractFqn(childEObject);
                        if (childFqn != null && matchesFqnOrParent(childFqn)) {
                            return true;
                        }
                        // Recursively check children
                        if (hasMatchingChildSubsystem(childEObject)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Not a subsystem or no getSubsystems method - ignore
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
     * Checks if a nested object folder (Attributes, TabularSections, EnumValues, etc.)
     * contains any matching child elements.
     * 
     * These folders have:
     * - getModel() or getModel(boolean) -> returns parent EObject (Document, Enum, etc.)
     * - getModelObjectName() -> returns "Attribute", "EnumValue", "TabularSection", etc.
     * 
     * @param element the folder element
     * @return true if folder should be visible, false if not, null if not a nested folder
     */
    private Boolean checkNestedObjectFolder(Object element) {
        try {
            // First get the parent EObject
            EObject parent = null;
            
            // Try getModel() or getModel(false)
            for (String methodName : new String[]{"getModel"}) {
                try {
                    // Try getModel(boolean)
                    var method = element.getClass().getMethod(methodName, boolean.class);
                    Object result = method.invoke(element, false);
                    if (result instanceof EObject eObj) {
                        parent = eObj;
                        break;
                    }
                } catch (NoSuchMethodException e) {
                    // Try without parameter
                    try {
                        var method = element.getClass().getMethod(methodName);
                        Object result = method.invoke(element);
                        if (result instanceof EObject eObj) {
                            parent = eObj;
                            break;
                        }
                    } catch (NoSuchMethodException e2) {
                        // Ignore
                    }
                }
            }
            
            if (parent == null) {
                return null; // Not a nested folder
            }
            
            // Get the parent's FQN
            String parentFqn = extractFqn(parent);
            if (parentFqn == null) {
                return null;
            }
            
            // Get the model object name (Attribute, EnumValue, TabularSection, etc.)
            String modelObjectName = null;
            try {
                var method = element.getClass().getMethod("getModelObjectName");
                Object result = method.invoke(element);
                if (result != null) {
                    modelObjectName = result.toString();
                }
            } catch (NoSuchMethodException e) {
                // Ignore
            }
            
            if (modelObjectName == null) {
                return null;
            }
            
            // Map folder model object names to FQN type prefixes
            // For example, "Attribute" in Document -> "DocumentAttribute"
            // "EnumValue" in Enum -> "EnumValue"
            String fqnTypePrefix = mapModelObjectNameToFqnType(parentFqn, modelObjectName);
            
            if (fqnTypePrefix == null) {
                // No mapping, fall back to checking if any matching FQN contains this segment
                String searchPrefix = parentFqn + "." + modelObjectName + ".";
                for (String fqn : matchingFqns) {
                    if (fqn.contains("." + modelObjectName + ".") && fqn.startsWith(parentFqn + ".")) {
                        return true;
                    }
                }
                return false;
            }
            
            // Check if any matching FQN is a child of parent.FqnType.*
            String searchPrefix = parentFqn + "." + fqnTypePrefix + ".";
            for (String fqn : matchingFqns) {
                if (fqn.startsWith(searchPrefix)) {
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            Activator.logError("Error checking nested folder", e);
            return null;
        }
    }
    
    /**
     * Maps a model object name (from getModelObjectName) to the FQN type prefix.
     * For example:
     * - "Attribute" in Document -> "DocumentAttribute"
     * - "EnumValue" in Enum -> "EnumValue"
     * - "Dimension" in InformationRegister -> "InformationRegisterDimension"
     */
    private String mapModelObjectNameToFqnType(String parentFqn, String modelObjectName) {
        // Get the parent type (first segment)
        String[] parts = parentFqn.split("\\.");
        if (parts.length < 1) {
            return null;
        }
        String parentType = parts[0];
        
        // Map common model object names to FQN types
        // The pattern is usually ParentType + ModelObjectName or just ModelObjectName
        
        // Attributes
        if ("Attribute".equals(modelObjectName)) {
            // Documents, Catalogs, etc. use Type + Attribute
            return parentType + "Attribute";
        }
        
        // Enum values
        if ("EnumValue".equals(modelObjectName)) {
            return "EnumValue";
        }
        
        // Tabular sections
        if ("TabularSection".equals(modelObjectName)) {
            return parentType + "TabularSection";
        }
        
        // Register dimensions
        if ("Dimension".equals(modelObjectName)) {
            return parentType + "Dimension";
        }
        
        // Register resources
        if ("Resource".equals(modelObjectName)) {
            return parentType + "Resource";
        }
        
        // Forms, Commands, Templates are typically just the name
        if ("Form".equals(modelObjectName) || "Command".equals(modelObjectName) || "Template".equals(modelObjectName)) {
            return parentType + modelObjectName;
        }
        
        // Default: try parentType + modelObjectName
        return parentType + modelObjectName;
    }

    /**
     * Extracts metadata type from Navigator folder class name.
     * E.g., "DocumentNavigatorAdapter$Folder" -> "Document"
     */
    private String extractMetadataTypeFromFolderClass(String className) {
        // Format: com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter$Folder
        // or com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter
        
        // Map of class name patterns to FQN prefixes
        java.util.Map<String, String> typeMap = new java.util.LinkedHashMap<>();
        // Most specific first
        typeMap.put("InformationRegister", "InformationRegister");
        typeMap.put("AccumulationRegister", "AccumulationRegister");
        typeMap.put("AccountingRegister", "AccountingRegister");
        typeMap.put("CalculationRegister", "CalculationRegister");
        typeMap.put("ChartOfCharacteristicTypes", "ChartOfCharacteristicTypes");
        typeMap.put("ChartOfAccounts", "ChartOfAccounts");
        typeMap.put("ChartOfCalculationTypes", "ChartOfCalculationTypes");
        typeMap.put("ExternalDataSource", "ExternalDataSource");
        typeMap.put("ExternalDataProcessor", "ExternalDataProcessor");
        typeMap.put("ExternalReport", "ExternalReport");
        typeMap.put("DocumentJournal", "DocumentJournal");
        typeMap.put("DocumentNumerator", "DocumentNumerator");
        typeMap.put("Document", "Document");
        typeMap.put("Catalog", "Catalog");
        typeMap.put("Enum", "Enum");
        typeMap.put("Report", "Report");
        typeMap.put("DataProcessor", "DataProcessor");
        typeMap.put("Constant", "Constant");
        typeMap.put("BusinessProcess", "BusinessProcess");
        typeMap.put("Task", "Task");
        typeMap.put("Subsystem", "Subsystem");
        typeMap.put("CommonModule", "CommonModule");
        typeMap.put("CommonForm", "CommonForm");
        typeMap.put("CommonCommand", "CommonCommand");
        typeMap.put("CommonTemplate", "CommonTemplate");
        typeMap.put("CommonPicture", "CommonPicture");
        typeMap.put("CommonAttribute", "CommonAttribute");
        typeMap.put("CommandGroup", "CommandGroup");
        typeMap.put("StyleItem", "StyleItem");
        typeMap.put("Style", "Style");
        typeMap.put("PaletteColor", "PaletteColor");
        typeMap.put("SessionParameter", "SessionParameter");
        typeMap.put("Role", "Role");
        typeMap.put("ExchangePlan", "ExchangePlan");
        typeMap.put("FilterCriterion", "FilterCriterion");
        typeMap.put("EventSubscription", "EventSubscription");
        typeMap.put("ScheduledJob", "ScheduledJob");
        typeMap.put("Bot", "Bot");
        typeMap.put("FunctionalOptionsParameter", "FunctionalOptionsParameter");
        typeMap.put("FunctionalOption", "FunctionalOption");
        typeMap.put("DefinedType", "DefinedType");
        typeMap.put("SettingsStorage", "SettingsStorage");
        typeMap.put("XDTOPackage", "XDTOPackage");
        typeMap.put("WebService", "WebService");
        typeMap.put("HTTPService", "HTTPService");
        typeMap.put("WSReference", "WSReference");
        typeMap.put("WebSocketClient", "WebSocketClient");
        typeMap.put("IntegrationService", "IntegrationService");
        typeMap.put("Sequence", "Sequence");
        typeMap.put("Recalculation", "Recalculation");
        typeMap.put("Language", "Language");
        
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