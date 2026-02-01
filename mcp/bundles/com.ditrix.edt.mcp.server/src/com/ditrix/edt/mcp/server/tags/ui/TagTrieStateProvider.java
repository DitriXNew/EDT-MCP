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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.common.EObjectTrie;
import com._1c.g5.v8.dt.common.IEObjectTrie;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

import org.eclipse.xtext.naming.QualifiedName;

/**
 * A custom INavigatorContentProviderStateProvider that builds and provides
 * an EObjectTrie based on tag search results.
 * 
 * This provider is used when the search pattern starts with #.
 * It builds a Trie containing all metadata objects that have matching tags.
 */
public class TagTrieStateProvider implements INavigatorContentProviderStateProvider {
    
    private static TagTrieStateProvider instance;
    
    private final Map<IProject, IEObjectTrie> tagTries = new HashMap<>();
    private boolean isActive = false;
    private String currentPattern = "";
    
    private TagTrieStateProvider() {
    }
    
    /**
     * Gets the singleton instance.
     */
    public static synchronized TagTrieStateProvider getInstance() {
        if (instance == null) {
            instance = new TagTrieStateProvider();
        }
        return instance;
    }
    
    @Override
    public IEObjectTrie getEObjectTrie(IProject project) {
        return tagTries.get(project);
    }
    
    @Override
    public boolean isActive() {
        return isActive;
    }
    
    @Override
    public void setActive(boolean active) {
        this.isActive = active;
        if (!active) {
            tagTries.clear();
            currentPattern = "";
        }
    }
    
    /**
     * Builds tag search Tries for all projects and activates this provider.
     * 
     * @param tagPattern the tag pattern to search for (without #)
     */
    public void buildAndActivate(String tagPattern) {
        if (tagPattern.equals(currentPattern) && isActive) {
            return;
        }
        
        currentPattern = tagPattern;
        tagTries.clear();
        
        TagService tagService = TagService.getInstance();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        
        if (bmModelManager == null) {
            Activator.logError("BmModelManager not available", null);
            return;
        }
        
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) {
                continue;
            }
            
            // Find all FQNs matching the tag pattern
            Set<String> matchingFqns = findMatchingFqns(project, tagPattern, tagService);
            
            if (matchingFqns.isEmpty()) {
                continue;
            }
            
            // Build Trie with matching objects
            EObjectTrie trie = buildTrie(project, matchingFqns, bmModelManager);
            if (trie != null) {
                tagTries.put(project, trie);
            }
        }
        
        isActive = true;
    }
    
    /**
     * Finds all FQNs matching the tag pattern.
     */
    private Set<String> findMatchingFqns(IProject project, String tagPattern, TagService tagService) {
        Set<String> matchingFqns = new HashSet<>();
        TagStorage storage = tagService.getTagStorage(project);
        
        for (Tag tag : storage.getTags()) {
            if (tag.getName().toLowerCase().contains(tagPattern.toLowerCase())) {
                matchingFqns.addAll(storage.getObjectsByTag(tag.getName()));
            }
        }
        
        return matchingFqns;
    }
    
    /**
     * Builds an EObjectTrie containing the matching objects.
     * The Trie structure must match what content providers expect:
     * - TypeName (e.g., "Document", "Catalog") - folder nodes without EObject
     *   - ObjectName (e.g., "SalesOrder") - object nodes WITH EObject
     */
    private EObjectTrie buildTrie(IProject project, Set<String> matchingFqns, IBmModelManager bmModelManager) {
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null) {
            return null;
        }
        
        try {
            return bmModel.executeReadonlyTask(new AbstractBmTask<EObjectTrie>("Build tag search Trie") {
                @Override
                public EObjectTrie execute(IBmTransaction transaction, IProgressMonitor monitor) {
                    EObjectTrie trie = new EObjectTrie();
                    
                    for (String fqn : matchingFqns) {
                        try {
                            // FQN format: TypeName.ObjectName or TypeName.ObjectName.SubTypeName.SubName
                            // e.g., "Document.SalesOrder", "CommonModule.Common", "Catalog.Products.Attribute.Name"
                            
                            String[] parts = fqn.split("\\.");
                            if (parts.length < 2) {
                                continue;
                            }
                            
                            // Get top-level object FQN (TypeName.ObjectName)
                            String topFqn = parts[0] + "." + parts[1];
                            QualifiedName topQualifiedName = QualifiedName.create(parts[0], parts[1]);
                            
                            // Add path to Trie - this creates both the type folder and object node
                            trie.addPath(topQualifiedName);
                            
                            // Resolve top object and set it in the Trie
                            EObject topObject = null;
                            
                            // Special handling for Subsystems - they are not top-level BM objects
                            // They are contained in Configuration.getSubsystems()
                            // Using hardcoded "Subsystem" as the EClass name
                            if ("Subsystem".equals(parts[0])) {
                                topObject = resolveSubsystem(project, parts[1]);
                                if (topObject != null) {
                                    trie.setEObject(topQualifiedName, topObject);
                                }
                            } else {
                                // Standard top-level object resolution
                                IBmObject bmObject = transaction.getTopObjectByFqn(topFqn);
                                if (bmObject != null) {
                                    topObject = (EObject) bmObject;
                                    trie.setEObject(topQualifiedName, topObject);
                                }
                            }
                            
                            // If FQN has more parts (nested objects), add those paths too
                            if (parts.length > 2) {
                                // Add the full path for nested objects
                                QualifiedName fullQualifiedName = QualifiedName.create(parts);
                                trie.addPath(fullQualifiedName);
                                
                                // Try to resolve nested object
                                if (topObject != null) {
                                    EObject nestedObject = resolveNestedObject((EObject) topObject, parts, 2);
                                    if (nestedObject != null) {
                                        trie.setEObject(fullQualifiedName, nestedObject);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Activator.logError("Error adding FQN to Trie: " + fqn, e);
                        }
                    }
                    
                    // Add Configuration to make the tree traversable
                    try {
                        com._1c.g5.v8.dt.core.platform.IV8ProjectManager v8ProjectManager = 
                            Activator.getDefault().getV8ProjectManager();
                        if (v8ProjectManager != null) {
                            com._1c.g5.v8.dt.core.platform.IV8Project v8Project = 
                                v8ProjectManager.getProject(project);
                            if (v8Project instanceof com._1c.g5.v8.dt.core.platform.IConfigurationAware) {
                                com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration = 
                                    ((com._1c.g5.v8.dt.core.platform.IConfigurationAware) v8Project).getConfiguration();
                                if (configuration != null) {
                                    QualifiedName configQName = QualifiedName.create("Configuration", configuration.getName());
                                    trie.addPath(configQName);
                                    trie.setEObject(configQName, (EObject) configuration);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Activator.logError("Error adding Configuration to Trie", e);
                    }
                    
                    return trie;
                }
            });
        } catch (Exception e) {
            Activator.logError("Error building Trie", e);
            return null;
        }
    }
    
    /**
     * Resolves a Subsystem by name from the Configuration.
     * Subsystems are not top-level BM objects, they are contained in Configuration.getSubsystems().
     */
    private EObject resolveSubsystem(IProject project, String subsystemName) {
        try {
            com._1c.g5.v8.dt.core.platform.IV8ProjectManager v8ProjectManager = 
                Activator.getDefault().getV8ProjectManager();
            if (v8ProjectManager != null) {
                com._1c.g5.v8.dt.core.platform.IV8Project v8Project = 
                    v8ProjectManager.getProject(project);
                if (v8Project instanceof com._1c.g5.v8.dt.core.platform.IConfigurationAware) {
                    com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration = 
                        ((com._1c.g5.v8.dt.core.platform.IConfigurationAware) v8Project).getConfiguration();
                    if (configuration != null) {
                        // Search in Configuration.getSubsystems() for the matching subsystem
                        for (com._1c.g5.v8.dt.metadata.mdclass.Subsystem subsystem : configuration.getSubsystems()) {
                            if (subsystemName.equals(subsystem.getName())) {
                                return subsystem;
                            }
                            // Also search in child subsystems (recursive)
                            EObject found = findChildSubsystem(subsystem, subsystemName);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("Error resolving Subsystem: " + subsystemName, e);
        }
        return null;
    }
    
    /**
     * Recursively searches for a child subsystem by name.
     */
    private EObject findChildSubsystem(com._1c.g5.v8.dt.metadata.mdclass.Subsystem parent, String subsystemName) {
        for (com._1c.g5.v8.dt.metadata.mdclass.Subsystem child : parent.getSubsystems()) {
            if (subsystemName.equals(child.getName())) {
                return child;
            }
            // Recursive search
            EObject found = findChildSubsystem(child, subsystemName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
    
    /**
     * Resolves a nested object from the parent using FQN parts.
     * @param parent the parent EObject
     * @param parts FQN parts array
     * @param startIndex index to start resolving from (skipping TypeName.ObjectName)
     */
    private EObject resolveNestedObject(EObject parent, String[] parts, int startIndex) {
        EObject current = parent;
        
        for (int i = startIndex; i < parts.length; i += 2) { // Skip by 2 (SubTypeName.SubName)
            if (i + 1 >= parts.length) {
                break;
            }
            
            String subTypeName = parts[i];
            String subName = parts[i + 1];
            
            // Try to find the child by navigating containment references
            EObject child = findChildByTypeAndName(current, subTypeName, subName);
            if (child == null) {
                return null;
            }
            current = child;
        }
        
        return current;
    }
    
    /**
     * Finds a child EObject by type and name.
     */
    private EObject findChildByTypeAndName(EObject parent, String typeName, String name) {
        // Try to find in all containment references
        for (org.eclipse.emf.ecore.EReference ref : parent.eClass().getEAllContainments()) {
            Object value = parent.eGet(ref);
            if (value instanceof java.util.Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof EObject child) {
                        if (matchesTypeAndName(child, typeName, name)) {
                            return child;
                        }
                    }
                }
            } else if (value instanceof EObject child) {
                if (matchesTypeAndName(child, typeName, name)) {
                    return child;
                }
            }
        }
        return null;
    }
    
    /**
     * Checks if an EObject matches the given type and name.
     */
    private boolean matchesTypeAndName(EObject obj, String typeName, String name) {
        String objTypeName = obj.eClass().getName();
        if (!objTypeName.equals(typeName) && !objTypeName.endsWith(typeName)) {
            return false;
        }
        
        // Try to get name
        try {
            for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object objName = m.invoke(obj);
                    return name.equals(objName);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return false;
    }
    
    /**
     * Deactivates this provider and clears all Tries.
     */
    public void deactivate() {
        isActive = false;
        tagTries.clear();
        currentPattern = "";
    }
}
