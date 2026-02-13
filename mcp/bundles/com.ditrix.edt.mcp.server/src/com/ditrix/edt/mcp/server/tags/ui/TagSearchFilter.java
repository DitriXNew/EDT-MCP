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
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;
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
    
    /** Whether to show only untagged objects */
    private boolean showUntaggedOnly = false;
    
    /** Selected tags from dialog per project */
    private Map<IProject, Set<Tag>> selectedTagsByProject = new HashMap<>();
    
    /**
     * Common metadata type names that appear in the "Common" folder.
     * These are the types that don't have their own top-level folder in navigator.
     * IMPORTANT: Must be declared BEFORE COMMON_METADATA_TYPES to avoid null during static init.
     */
    private static final Set<String> COMMON_TYPE_NAMES = Set.of(
        "Subsystem", "CommonModule", "SessionParameter", "Role", "CommonAttribute",
        "ExchangePlan", "FilterCriterion", "EventSubscription", "ScheduledJob", "Bot",
        "FunctionalOption", "FunctionalOptionsParameter", "DefinedType", "SettingsStorage",
        "CommonForm", "CommonCommand", "CommandGroup", "CommonTemplate", "CommonPicture",
        "XDTOPackage", "WebService", "HTTPService", "WSReference", "WebSocketClient",
        "IntegrationService", "Style", "StyleItem", "Language"
    );
    
    /**
     * Set of metadata types that appear under "Common" folder in the navigator.
     * Derived dynamically from MdClassPackage using reflection.
     */
    private static final Set<String> COMMON_METADATA_TYPES = initCommonMetadataTypes();
    
    /**
     * Initializes the set of common metadata types from MdClassPackage.
     * Uses reflection to get all EClass types and filters by known common type names.
     * This ensures we use the actual EMF type names.
     */
    private static Set<String> initCommonMetadataTypes() {
        Set<String> result = new java.util.HashSet<>();
        org.eclipse.emf.ecore.EPackage pkg = 
            com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.eINSTANCE;
        
        for (org.eclipse.emf.ecore.EClassifier classifier : pkg.getEClassifiers()) {
            if (classifier instanceof org.eclipse.emf.ecore.EClass eClass) {
                String name = eClass.getName();
                if (COMMON_TYPE_NAMES.contains(name)) {
                    result.add(name);
                }
            }
        }
        
        // Fallback to hardcoded names if reflection failed
        if (result.isEmpty()) {
            return COMMON_TYPE_NAMES;
        }
        
        return result;
    }
    
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
     * Sets the filter to show only untagged objects.
     * 
     * @param showUntaggedOnly true to show only untagged objects
     */
    public void setShowUntaggedOnly(boolean showUntaggedOnly) {
        this.showUntaggedOnly = showUntaggedOnly;
        if (showUntaggedOnly) {
            this.dialogMode = true; // Enable dialog mode
            // Clear tag selection since we're showing untagged only
            recalculateUntaggedFqns();
            Activator.logInfo("TagSearchFilter: untagged-only mode enabled with " + 
                matchingFqns.size() + " untagged objects");
        }
    }
    
    /**
     * Recalculates matching FQNs to show only untagged objects.
     */
    private void recalculateUntaggedFqns() {
        matchingFqns.clear();
        matchingFqnsByProject.clear();
        
        TagService tagService = TagService.getInstance();
        
        // Get all projects
        for (IProject project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                .getRoot().getProjects()) {
            if (!project.isOpen()) {
                continue;
            }
            
            TagStorage storage = tagService.getTagStorage(project);
            
            // Get all assigned FQNs
            Set<String> assignedFqns = new HashSet<>();
            for (Tag tag : storage.getTags()) {
                assignedFqns.addAll(storage.getObjectsByTag(tag.getName()));
            }
            
            // Store as "untagged FQNs" - we need to invert the logic in select()
            // For untagged mode, we'll mark which FQNs ARE tagged, and reject them
            if (!assignedFqns.isEmpty()) {
                matchingFqnsByProject.put(project, assignedFqns);
                matchingFqns.addAll(assignedFqns);
            }
        }
    }
    
    /**
     * Clears dialog mode.
     */
    public void clearSelectedTagsMode() {
        this.dialogMode = false;
        this.showUntaggedOnly = false;
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
        
        // Groups should be visible if any of their children match the filter
        if (element instanceof GroupNavigatorAdapter groupAdapter) {
            return hasMatchingChildrenInGroup(groupAdapter);
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
                // Using hardcoded "Subsystem" as the EClass name
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
                // Check if any matching FQN belongs to a "common" metadata type
                return hasMatchingFqnsForAnyType(COMMON_METADATA_TYPES);
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
     * Checks if a group has any children that match the current filter.
     * A group should be visible if any of its FQNs match the selected tags.
     */
    private boolean hasMatchingChildrenInGroup(GroupNavigatorAdapter groupAdapter) {
        Group group = groupAdapter.getGroup();
        IProject project = groupAdapter.getProject();
        
        Set<String> projectFqns = matchingFqnsByProject.get(project);
        
        // In untagged mode with no tagged objects, show all groups
        if (showUntaggedOnly && (projectFqns == null || projectFqns.isEmpty())) {
            return !group.getChildren().isEmpty();
        }
        
        if (projectFqns == null || projectFqns.isEmpty()) {
            return false;
        }
        
        // Check if any child FQN of this group matches
        for (String childFqn : group.getChildren()) {
            if (showUntaggedOnly) {
                // In untagged mode, matchingFqns contains TAGGED objects
                // So we show the group if any child is NOT in matchingFqns
                // Also check that no child descendants are tagged
                if (!matchesFqnOrChildInSet(childFqn, projectFqns)) {
                    return true;
                }
            } else {
                // Normal mode - show if child or any of its descendants is in matchingFqns
                if (matchesFqnOrChildInSet(childFqn, projectFqns)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if an FQN or any of its descendants is in the given set.
     * For example, if set contains "Catalog.X.Attribute.Y" and fqn is "Catalog.X",
     * this returns true.
     */
    private boolean matchesFqnOrChildInSet(String fqn, Set<String> fqnSet) {
        // Direct match
        if (fqnSet.contains(fqn)) {
            return true;
        }
        
        // Check if any matching FQN is a child of this FQN
        String prefix = fqn + ".";
        for (String matchingFqn : fqnSet) {
            if (matchingFqn.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
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
     * Uses ALL_METADATA_TYPES list built dynamically from MdClassPackage.
     */
    private String extractMetadataTypeFromFolderClass(String className) {
        // Format: com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter$Folder
        // or com._1c.g5.v8.dt.md.ui.navigator.adapters.XXXNavigatorAdapter
        
        // Get all metadata type names from MdClassPackage via reflection
        // Check in order from longest to shortest to handle cases like
        // "InformationRegisterNavigatorAdapter" vs "RegisterNavigatorAdapter"
        return ALL_METADATA_TYPES.stream()
            .filter(typeName -> className.contains(typeName + "NavigatorAdapter"))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * All metadata types from MdClassPackage.
     * Sorted by length descending to match longer names first.
     */
    private static final java.util.List<String> ALL_METADATA_TYPES = initAllMetadataTypes();
    
    /**
     * Initializes list of all metadata types from MdClassPackage using reflection.
     * Gets all EClass types from the package and sorts by length descending.
     */
    private static java.util.List<String> initAllMetadataTypes() {
        java.util.List<String> types = new java.util.ArrayList<>();
        org.eclipse.emf.ecore.EPackage pkg = 
            com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.eINSTANCE;
        
        for (org.eclipse.emf.ecore.EClassifier classifier : pkg.getEClassifiers()) {
            if (classifier instanceof org.eclipse.emf.ecore.EClass eClass) {
                // Only include concrete metadata types (not abstract)
                if (!eClass.isAbstract() && !eClass.isInterface()) {
                    types.add(eClass.getName());
                }
            }
        }
        
        // Sort by length descending so longer names match first
        types.sort((a, b) -> b.length() - a.length());
        return types;
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
     * Checks if any matching FQN starts with any of the given metadata types.
     * Uses currentFilterProject context.
     * 
     * @param metadataTypes set of metadata type names to check
     * @return true if any matching FQN belongs to one of the specified types
     */
    private boolean hasMatchingFqnsForAnyType(Set<String> metadataTypes) {
        Set<String> projectFqns = getCurrentMatchingFqns();
        for (String fqn : projectFqns) {
            int dotIndex = fqn.indexOf('.');
            if (dotIndex > 0) {
                String type = fqn.substring(0, dotIndex);
                if (metadataTypes.contains(type)) {
                    return true;
                }
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
     * In untagged-only mode, the logic is inverted: matchingFqns contains TAGGED objects,
     * so we return true only if the FQN is NOT in the set.
     * 
     * @param fqn the FQN to check
     * @param project the project (can be null for global check)
     * @return true if matches
     */
    private boolean matchesFqnOrParentInProject(String fqn, IProject project) {
        Set<String> projectFqns = getMatchingFqnsForProject(project);
        
        if (showUntaggedOnly) {
            // In untagged-only mode, matchingFqns contains TAGGED objects
            // We want to show objects that are NOT tagged
            // Exact match means this object IS tagged - reject it
            if (projectFqns.contains(fqn)) {
                return false;
            }
            // Check if this FQN is a parent of a tagged object - show it (as folder)
            String prefix = fqn + ".";
            for (String taggedFqn : projectFqns) {
                if (taggedFqn.startsWith(prefix)) {
                    // This is a parent of a tagged object, we need to check children
                    // Return true to show the folder (will be filtered by children)
                    return true;
                }
            }
            // Not tagged and not a parent of tagged - this is an untagged object!
            return true;
        }
        
        // Normal mode: matchingFqns contains objects that match selected tags
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