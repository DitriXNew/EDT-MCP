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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * ViewerFilter that filters navigator elements by tag.
 * Supports dialog mode: When selected tags are set from FilterByTagDialog
 */
public class TagSearchFilter extends ViewerFilter {
    
    /** Filter ID as registered in plugin.xml */
    public static final String FILTER_ID = "com.ditrix.edt.mcp.server.tags.TagSearchFilter";
    
    /** Matching FQNs per project - each project has its own set of matching FQNs */
    private Map<IProject, Set<String>> matchingFqnsByProject = new HashMap<>();
    
    /** Combined set for quick lookup */
    private Set<String> matchingFqns = new HashSet<>();
    
    /** Current project context for filtering - set at the start of select() */
    private IProject currentFilterProject;
    
    /** Whether we are in dialog-selected tags mode */
    private boolean dialogMode = false;
    
    /** Selected tags from dialog per project */
    private Map<IProject, Set<Tag>> selectedTagsByProject = new HashMap<>();
    
    /**
     * Default constructor for extension factory.
     */
    public TagSearchFilter() {
    }
    
    /**
     * Sets the filter to dialog mode with the specified selected tags.
     * This mode is activated when user selects tags from FilterByTagDialog.
     * 
     * @param selectedTags map of project to set of selected tags
     */
    public void setSelectedTagsMode(Map<IProject, Set<Tag>> selectedTags) {
        this.dialogMode = true;
        this.selectedTagsByProject = new HashMap<>(selectedTags);
        
        // Recalculate matching FQNs based on selected tags
        recalculateMatchingFqnsFromSelectedTags();
        
        Activator.logInfo("TagSearchFilter: dialog mode enabled with " + 
            matchingFqns.size() + " matching objects");
    }
    
    /**
     * Clears dialog mode.
     */
    public void clearSelectedTagsMode() {
        this.dialogMode = false;
        this.selectedTagsByProject.clear();
        this.matchingFqns.clear();
        this.matchingFqnsByProject.clear();
        
        Activator.logInfo("TagSearchFilter: dialog mode disabled");
    }
    
    /**
     * Recalculates matching FQNs based on selected tags from dialog.
     */
    private void recalculateMatchingFqnsFromSelectedTags() {
        matchingFqns.clear();
        matchingFqnsByProject.clear();
        
        TagService tagService = TagService.getInstance();
        
        for (Map.Entry<IProject, Set<Tag>> entry : selectedTagsByProject.entrySet()) {
            IProject project = entry.getKey();
            Set<Tag> tags = entry.getValue();
            
            Set<String> projectFqns = new HashSet<>();
            TagStorage storage = tagService.getTagStorage(project);
            
            for (Tag tag : tags) {
                Set<String> objectFqns = storage.getObjectsByTag(tag.getName());
                projectFqns.addAll(objectFqns);
                matchingFqns.addAll(objectFqns);
            }
            
            if (!projectFqns.isEmpty()) {
                matchingFqnsByProject.put(project, projectFqns);
            }
        }
    }
    
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        // Only filter in dialog mode (tags selected from dialog)
        if (!dialogMode) {
            return true; // Not in filter mode, show everything
        }
        
        // Use the shared filtering logic
        return selectByMatchingFqns(viewer, parentElement, element);
    }
    
    /**
     * Shared filtering logic - checks if element matches the current matching FQNs.
     */
    private boolean selectByMatchingFqns(Viewer viewer, Object parentElement, Object element) {
        // If no matching FQNs, show everything
        if (matchingFqns.isEmpty() && matchingFqnsByProject.isEmpty()) {
            return true;
        }
        
        // Try to determine current project context from element or parent
        currentFilterProject = TagUtils.extractProjectFromElement(element);
        if (currentFilterProject == null) {
            currentFilterProject = TagUtils.extractProjectFromElement(parentElement);
        }
        
        // Projects always visible if they have matching children
        if (element instanceof IProject project) {
            return hasMatchingChildrenInProject(project);
        }
        
        // Check if element matches
        if (element instanceof EObject eObject) {
            // Get the project this EObject belongs to for project-specific matching
            IProject project = TagUtils.extractProject(eObject);
            if (project != null) {
                currentFilterProject = project;
            }
            
            // Special handling for Configuration - always visible if there are matching FQNs for this project
            String typeName = eObject.eClass().getName();
            if ("Configuration".equals(typeName)) {
                Set<String> projectFqns = getMatchingFqnsForProject(currentFilterProject);
                return !projectFqns.isEmpty();
            }
            
            String fqn = TagUtils.extractFqn(eObject);
            
            // Debug: log what we're checking
            if (fqn != null && fqn.contains("AddDataProc")) {
                Activator.logInfo("DEBUG: Checking " + fqn + " in project " + 
                    (currentFilterProject != null ? currentFilterProject.getName() : "null") +
                    ", projectFqns size: " + getMatchingFqnsForProject(currentFilterProject).size());
            }
            
            if (fqn != null) {
                // Check if this FQN or any parent matches IN THIS PROJECT
                boolean result = matchesFqnOrParentInProject(fqn, currentFilterProject);
                
                // Special handling for Subsystems: parent subsystem should be visible if ANY child matches
                if (!result && "Subsystem".equals(typeName)) {
                    result = hasMatchingChildSubsystemInProject(eObject, currentFilterProject);
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
            EObject unwrapped = TagUtils.unwrapToEObject(element);
            if (unwrapped != null) {
                String fqn = TagUtils.extractFqn(unwrapped);
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
     * Checks if project has any matching FQNs (used in dialog mode).
     */
    private boolean hasMatchingChildrenInProject(IProject project) {
        Set<String> projectFqns = matchingFqnsByProject.get(project);
        return projectFqns != null && !projectFqns.isEmpty();
    }
    
    /**
     * Checks if FQN matches directly or is a parent of a matching FQN.
     * Uses currentFilterProject context.
     */
    private boolean matchesFqnOrParent(String fqn) {
        Set<String> projectFqns = getCurrentMatchingFqns();
        
        // Direct match
        if (projectFqns.contains(fqn)) {
            return true;
        }
        
        // Check if this is a parent of any matching FQN
        String prefix = fqn + ".";
        for (String matchingFqn : projectFqns) {
            if (matchingFqn.startsWith(prefix)) {
                return true;
            }
        }
        
        // Check if this FQN is part of a matching FQN path
        // (to show intermediate nodes in the tree)
        for (String matchingFqn : projectFqns) {
            if (matchingFqn.startsWith(fqn)) {
                // This element is on the path to a matching element
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a subsystem has any child subsystems that match tags within a specific project.
     * 
     * @param subsystemEObject the parent subsystem EObject
     * @param project the project (can be null for global check)
     * @return true if any child subsystem or descendant matches
     */
    private boolean hasMatchingChildSubsystemInProject(EObject subsystemEObject, IProject project) {
        try {
            // Get child subsystems using reflection
            java.lang.reflect.Method getSubsystems = subsystemEObject.getClass().getMethod("getSubsystems");
            Object result = getSubsystems.invoke(subsystemEObject);
            
            if (result instanceof java.util.Collection<?> children) {
                for (Object child : children) {
                    if (child instanceof EObject childEObject) {
                        String childFqn = TagUtils.extractFqn(childEObject);
                        if (childFqn != null && matchesFqnOrParentInProject(childFqn, project)) {
                            return true;
                        }
                        // Recursively check children
                        if (hasMatchingChildSubsystemInProject(childEObject, project)) {
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
            String parentFqn = TagUtils.extractFqn(parent);
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
            
            Set<String> projectFqns = getCurrentMatchingFqns();
            
            // Map folder model object names to FQN type prefixes
            // For example, "Attribute" in Document -> "DocumentAttribute"
            // "EnumValue" in Enum -> "EnumValue"
            String fqnTypePrefix = mapModelObjectNameToFqnType(parentFqn, modelObjectName);
            
            if (fqnTypePrefix == null) {
                // No mapping, fall back to checking if any matching FQN contains this segment
                for (String fqn : projectFqns) {
                    if (fqn.contains("." + modelObjectName + ".") && fqn.startsWith(parentFqn + ".")) {
                        return true;
                    }
                }
                return false;
            }
            
            // Check if any matching FQN is a child of parent.FqnType.*
            String searchPrefix = parentFqn + "." + fqnTypePrefix + ".";
            for (String fqn : projectFqns) {
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
     * Uses currentFilterProject context.
     */
    private boolean hasMatchingFqnsForType(String metadataType) {
        Set<String> projectFqns = getCurrentMatchingFqns();
        String prefix = metadataType + ".";
        for (String fqn : projectFqns) {
            if (fqn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets matching FQNs for a specific project.
     * 
     * @param project the project (can be null)
     * @return the set of matching FQNs for this project, or empty set if none
     */
    private Set<String> getMatchingFqnsForProject(IProject project) {
        IProject effectiveProject = project != null ? project : currentFilterProject;
        if (effectiveProject == null) {
            // If no project specified and no current filter project, return combined set
            return matchingFqns;
        }
        return matchingFqnsByProject.getOrDefault(effectiveProject, java.util.Collections.emptySet());
    }
    
    /**
     * Gets matching FQNs for the current filter project context.
     * Uses currentFilterProject if set, otherwise returns combined set.
     * 
     * @return the set of matching FQNs for current context
     */
    private Set<String> getCurrentMatchingFqns() {
        return getMatchingFqnsForProject(currentFilterProject);
    }
    
    /**
     * Checks if FQN matches (exact or as parent) within a specific project.
     * 
     * @param fqn the FQN to check
     * @param project the project (can be null for global check)
     * @return true if matches
     */
    private boolean matchesFqnOrParentInProject(String fqn, IProject project) {
        Set<String> projectFqns = getMatchingFqnsForProject(project);
        
        // Exact match
        if (projectFqns.contains(fqn)) {
            return true;
        }
        
        // Check if this FQN is a parent of a matching FQN
        String prefix = fqn + ".";
        for (String matchingFqn : projectFqns) {
            if (matchingFqn.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
}