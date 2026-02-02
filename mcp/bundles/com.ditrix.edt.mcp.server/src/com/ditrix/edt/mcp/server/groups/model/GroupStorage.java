/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root model for group storage.
 * Contains all virtual folder groups for a project.
 * 
 * YAML structure example:
 * <pre>
 * groups:
 *   - name: "Server Modules"
 *     path: "CommonModules"
 *     description: "Server-side common modules"
 *     order: 1
 *     children:
 *       - "CommonModule.ServerModule1"
 *       - "CommonModule.ServerModule2"
 *   - name: "Client Modules"
 *     path: "CommonModules"
 *     order: 2
 *     children:
 *       - "CommonModule.ClientModule1"
 * </pre>
 */
public class GroupStorage {
    
    /**
     * List of all groups in the project.
     */
    private List<Group> groups;
    
    /**
     * Default constructor for YAML deserialization.
     */
    public GroupStorage() {
        this.groups = new ArrayList<>();
    }
    
    public List<Group> getGroups() {
        return groups;
    }
    
    public void setGroups(List<Group> groups) {
        this.groups = groups != null ? groups : new ArrayList<>();
    }
    
    // === Convenience methods ===
    
    /**
     * Gets a group by its full path (path + name).
     * 
     * @param fullPath the full path (e.g., "CommonModules/ServerModules")
     * @return the group or null if not found
     */
    public Group getGroupByFullPath(String fullPath) {
        return groups.stream()
            .filter(g -> g.getFullPath().equals(fullPath))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Gets all groups at a specific path (direct children only).
     * 
     * @param path the parent path (e.g., "CommonModules")
     * @return list of groups at this path, sorted by order then name
     */
    public List<Group> getGroupsAtPath(String path) {
        return groups.stream()
            .filter(g -> {
                String groupPath = g.getPath();
                if (path == null || path.isEmpty()) {
                    return groupPath == null || groupPath.isEmpty();
                }
                return path.equals(groupPath);
            })
            .sorted((a, b) -> {
                int orderCmp = Integer.compare(a.getOrder(), b.getOrder());
                if (orderCmp != 0) return orderCmp;
                return a.getName().compareToIgnoreCase(b.getName());
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Adds a new group.
     * 
     * @param group the group to add
     * @return true if added, false if group with same path already exists
     */
    public boolean addGroup(Group group) {
        if (getGroupByFullPath(group.getFullPath()) == null) {
            groups.add(group);
            return true;
        }
        return false;
    }
    
    /**
     * Removes a group by its full path.
     * 
     * @param fullPath the full path of the group
     * @return true if removed
     */
    public boolean removeGroup(String fullPath) {
        Group group = getGroupByFullPath(fullPath);
        if (group != null) {
            groups.remove(group);
            return true;
        }
        return false;
    }
    
    /**
     * Renames a group.
     * 
     * @param oldFullPath the old full path
     * @param newName the new name
     * @return true if renamed
     */
    public boolean renameGroup(String oldFullPath, String newName) {
        Group group = getGroupByFullPath(oldFullPath);
        if (group != null) {
            // Check if new name would conflict
            String newFullPath = group.getPath() + "/" + newName;
            if (getGroupByFullPath(newFullPath) == null) {
                // Update child groups that have this group as parent
                String oldPrefix = group.getFullPath();
                group.setName(newName);
                String newPrefix = group.getFullPath();
                
                for (Group g : groups) {
                    if (g.getPath() != null && g.getPath().startsWith(oldPrefix)) {
                        g.setPath(newPrefix + g.getPath().substring(oldPrefix.length()));
                    }
                }
                return true;
            }
        }
        return false;
    }
    
    /**
     * Updates a group's name and description.
     * 
     * @param oldFullPath the current full path of the group
     * @param newName the new name (can be same as old)
     * @param description the new description (can be null)
     * @return true if updated
     */
    public boolean updateGroup(String oldFullPath, String newName, String description) {
        Group group = getGroupByFullPath(oldFullPath);
        if (group == null) {
            return false;
        }
        
        // If name is changing, check for conflicts
        if (!group.getName().equals(newName)) {
            String path = group.getPath();
            String newFullPath = (path == null || path.isEmpty()) ? newName : path + "/" + newName;
            if (getGroupByFullPath(newFullPath) != null) {
                return false; // Conflict with existing group
            }
            
            // Update child groups that have this group as parent
            String oldPrefix = group.getFullPath();
            group.setName(newName);
            String newPrefix = group.getFullPath();
            
            for (Group g : groups) {
                if (g.getPath() != null && g.getPath().startsWith(oldPrefix)) {
                    g.setPath(newPrefix + g.getPath().substring(oldPrefix.length()));
                }
            }
        }
        
        // Update description
        group.setDescription(description);
        return true;
    }
    
    /**
     * Finds which group contains a specific object.
     * 
     * @param objectFqn the FQN of the object
     * @return the group containing the object, or null if not grouped
     */
    public Group findGroupForObject(String objectFqn) {
        return groups.stream()
            .filter(g -> g.containsChild(objectFqn))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Moves an object to a group.
     * Removes from previous group if any.
     * 
     * @param objectFqn the FQN of the object
     * @param targetGroupFullPath the target group full path
     * @return true if moved
     */
    public boolean moveObjectToGroup(String objectFqn, String targetGroupFullPath) {
        // Remove from current group if any
        Group currentGroup = findGroupForObject(objectFqn);
        if (currentGroup != null) {
            currentGroup.removeChild(objectFqn);
        }
        
        // Add to target group
        Group targetGroup = getGroupByFullPath(targetGroupFullPath);
        if (targetGroup != null) {
            return targetGroup.addChild(objectFqn);
        }
        return false;
    }
    
    /**
     * Removes an object from all groups.
     * 
     * @param objectFqn the FQN of the object
     * @return true if removed from any group
     */
    public boolean removeObjectFromAllGroups(String objectFqn) {
        boolean removed = false;
        for (Group group : groups) {
            if (group.removeChild(objectFqn)) {
                removed = true;
            }
        }
        return removed;
    }
    
    /**
     * Gets all grouped object FQNs at a specific path.
     * Used to filter objects from original location.
     * 
     * @param path the path (e.g., "CommonModules")
     * @return set of all FQNs that are grouped at or under this path
     */
    public Set<String> getGroupedObjectsAtPath(String path) {
        Set<String> result = new HashSet<>();
        for (Group group : groups) {
            String groupPath = group.getPath();
            if (groupPath != null && groupPath.startsWith(path)) {
                result.addAll(group.getChildren());
            }
            if (path.equals(groupPath)) {
                result.addAll(group.getChildren());
            }
        }
        return result;
    }
    
    /**
     * Renames an object FQN in all groups (for refactoring support).
     * 
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return true if renamed in any group
     */
    public boolean renameObject(String oldFqn, String newFqn) {
        boolean renamed = false;
        for (Group group : groups) {
            List<String> children = group.getChildren();
            int index = children.indexOf(oldFqn);
            if (index >= 0) {
                children.set(index, newFqn);
                renamed = true;
            }
        }
        return renamed;
    }
    
    /**
     * Checks if any groups exist for a specific path.
     * 
     * @param path the path to check
     * @return true if groups exist at this path
     */
    public boolean hasGroupsAtPath(String path) {
        return groups.stream().anyMatch(g -> {
            String groupPath = g.getPath();
            if (path == null || path.isEmpty()) {
                return groupPath == null || groupPath.isEmpty();
            }
            return path.equals(groupPath);
        });
    }
    
    /**
     * Gets the total number of groups.
     * 
     * @return group count
     */
    public int getGroupCount() {
        return groups.size();
    }
    
    /**
     * Checks if storage is empty.
     * 
     * @return true if no groups defined
     */
    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
