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
import java.util.List;
import java.util.Objects;

/**
 * Represents a virtual folder group in the Navigator.
 * Groups can contain metadata objects and are displayed as folders
 * in the Navigator tree.
 */
public class Group {
    
    /**
     * Unique name of the group (displayed in Navigator).
     */
    private String name;
    
    /**
     * Path to the parent location in Navigator tree.
     * Examples:
     * - "CommonModules" - group inside Common Modules folder
     * - "Catalogs/Products" - group inside Products catalog
     * - "Catalogs/Products/Attributes" - group inside catalog attributes
     * - "CommonModules/ServerModules" - nested group
     */
    private String path;
    
    /**
     * Optional description of the group.
     */
    private String description;
    
    /**
     * Sort order within the parent (lower values appear first).
     */
    private int order;
    
    /**
     * List of object FQNs contained in this group.
     * FQNs follow EDT format: "CommonModule.ModuleName", "Catalog.Products.Attribute.Name"
     */
    private List<String> children;
    
    /**
     * Default constructor for YAML deserialization.
     */
    public Group() {
        this.children = new ArrayList<>();
        this.order = 0;
    }
    
    /**
     * Creates a new group with name and path.
     * 
     * @param name the group name
     * @param path the parent path in Navigator
     */
    public Group(String name, String path) {
        this();
        this.name = name;
        this.path = path;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public int getOrder() {
        return order;
    }
    
    public void setOrder(int order) {
        this.order = order;
    }
    
    public List<String> getChildren() {
        return children;
    }
    
    public void setChildren(List<String> children) {
        this.children = children != null ? children : new ArrayList<>();
    }
    
    // === Convenience methods ===
    
    /**
     * Gets the full path of this group (path + name).
     * Used as unique identifier.
     * 
     * @return the full path
     */
    public String getFullPath() {
        if (path == null || path.isEmpty()) {
            return name;
        }
        return path + "/" + name;
    }
    
    /**
     * Adds an object to this group.
     * 
     * @param objectFqn the FQN of the object to add
     * @return true if added, false if already present
     */
    public boolean addChild(String objectFqn) {
        if (!children.contains(objectFqn)) {
            children.add(objectFqn);
            return true;
        }
        return false;
    }
    
    /**
     * Removes an object from this group.
     * 
     * @param objectFqn the FQN of the object to remove
     * @return true if removed
     */
    public boolean removeChild(String objectFqn) {
        return children.remove(objectFqn);
    }
    
    /**
     * Checks if this group contains an object.
     * 
     * @param objectFqn the FQN to check
     * @return true if contained
     */
    public boolean containsChild(String objectFqn) {
        return children.contains(objectFqn);
    }
    
    /**
     * Checks if this group is empty (has no children).
     * 
     * @return true if empty
     */
    public boolean isEmpty() {
        return children.isEmpty();
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(path, name);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Group other = (Group) obj;
        return Objects.equals(path, other.path) && Objects.equals(name, other.name);
    }
    
    @Override
    public String toString() {
        return "Group[" + getFullPath() + ", children=" + children.size() + "]";
    }
}
