/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups;

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;

/**
 * Service interface for managing virtual folder groups in the Navigator.
 * Groups allow organizing metadata objects into custom folders.
 * 
 * <p>This interface is designed for OSGi Declarative Services registration.</p>
 */
public interface IGroupService {
    
    /**
     * Gets the group storage for a project.
     * 
     * @param project the project
     * @return the group storage, never null
     */
    GroupStorage getGroupStorage(IProject project);
    
    /**
     * Gets all groups at a specific path.
     * 
     * @param project the project
     * @param path the parent path (e.g., "CommonModules")
     * @return list of groups sorted by order then name
     */
    List<Group> getGroupsAtPath(IProject project, String path);
    
    /**
     * Gets all groups in a project.
     * 
     * @param project the project
     * @return list of all groups
     */
    List<Group> getAllGroups(IProject project);
    
    /**
     * Creates a new group.
     * 
     * @param project the project
     * @param name the group name
     * @param path the parent path in Navigator
     * @param description optional description
     * @return the created group, or null if already exists
     */
    Group createGroup(IProject project, String name, String path, String description);
    
    /**
     * Renames a group.
     * 
     * @param project the project
     * @param oldFullPath the current full path
     * @param newName the new name
     * @return true if renamed
     */
    boolean renameGroup(IProject project, String oldFullPath, String newName);
    
    /**
     * Updates a group's name and description.
     * 
     * @param project the project
     * @param oldFullPath the current full path of the group
     * @param newName the new name (can be same as old)
     * @param description the new description (can be null)
     * @return true if updated
     */
    boolean updateGroup(IProject project, String oldFullPath, String newName, String description);
    
    /**
     * Deletes a group.
     * Objects in the group will return to their original location.
     * 
     * @param project the project
     * @param fullPath the full path of the group
     * @return true if deleted
     */
    boolean deleteGroup(IProject project, String fullPath);
    
    /**
     * Adds an object to a group.
     * Removes from previous group if any.
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @param groupFullPath the target group full path
     * @return true if added
     */
    boolean addObjectToGroup(IProject project, String objectFqn, String groupFullPath);
    
    /**
     * Removes an object from its group (returns to original location).
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @return true if removed from any group
     */
    boolean removeObjectFromGroup(IProject project, String objectFqn);
    
    /**
     * Finds which group contains an object.
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @return the group or null if not grouped
     */
    Group findGroupForObject(IProject project, String objectFqn);
    
    /**
     * Gets all grouped objects at a path.
     * Used to filter objects from original location.
     * 
     * @param project the project
     * @param path the path
     * @return set of grouped FQNs
     */
    Set<String> getGroupedObjectsAtPath(IProject project, String path);
    
    /**
     * Checks if a path has any groups.
     * 
     * @param project the project
     * @param path the path
     * @return true if groups exist
     */
    boolean hasGroupsAtPath(IProject project, String path);
    
    /**
     * Refreshes the cache for a project.
     * 
     * @param project the project
     */
    void refresh(IProject project);
    
    /**
     * Renames an object in group assignments (for refactoring support).
     * 
     * @param project the project
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return true if renamed
     */
    boolean renameObject(IProject project, String oldFqn, String newFqn);
    
    /**
     * Removes an object from all groups (for refactoring support).
     * 
     * @param project the project
     * @param objectFqn the FQN of the deleted object
     * @return true if removed
     */
    boolean removeObject(IProject project, String objectFqn);
    
    /**
     * Adds a listener for group changes.
     * 
     * @param listener the listener to add
     */
    void addGroupChangeListener(IGroupChangeListener listener);
    
    /**
     * Removes a group change listener.
     * 
     * @param listener the listener to remove
     */
    void removeGroupChangeListener(IGroupChangeListener listener);
}
