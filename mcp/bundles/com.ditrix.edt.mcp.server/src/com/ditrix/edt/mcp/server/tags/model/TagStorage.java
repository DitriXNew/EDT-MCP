/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root model for tag storage.
 * Contains available tags and tag assignments to metadata objects.
 */
public class TagStorage {
    
    /**
     * List of all defined tags in the project.
     */
    private List<Tag> tags;
    
    /**
     * Map from metadata object FQN to set of tag names.
     * Key: FQN (e.g., "Catalog.Products", "Document.SalesOrder")
     * Value: Set of tag names assigned to this object
     */
    private Map<String, Set<String>> assignments;
    
    /**
     * Default constructor for YAML deserialization.
     */
    public TagStorage() {
        this.tags = new ArrayList<>();
        this.assignments = new HashMap<>();
    }
    
    public List<Tag> getTags() {
        return tags;
    }
    
    public void setTags(List<Tag> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }
    
    public Map<String, Set<String>> getAssignments() {
        return assignments;
    }
    
    public void setAssignments(Map<String, Set<String>> assignments) {
        this.assignments = assignments != null ? assignments : new HashMap<>();
    }
    
    // === Convenience methods ===
    
    /**
     * Gets a tag by name.
     * 
     * @param name the tag name
     * @return the tag or null if not found
     */
    public Tag getTagByName(String name) {
        return tags.stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Adds a new tag if it doesn't exist.
     * 
     * @param tag the tag to add
     * @return true if added, false if already exists
     */
    public boolean addTag(Tag tag) {
        if (getTagByName(tag.getName()) == null) {
            tags.add(tag);
            return true;
        }
        return false;
    }
    
    /**
     * Removes a tag and all its assignments.
     * 
     * @param tagName the tag name to remove
     * @return true if removed, false if not found
     */
    public boolean removeTag(String tagName) {
        Tag tag = getTagByName(tagName);
        if (tag != null) {
            tags.remove(tag);
            // Remove from all assignments
            assignments.values().forEach(set -> set.remove(tagName));
            return true;
        }
        return false;
    }
    
    /**
     * Assigns a tag to a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name to assign
     * @return true if assigned, false if tag doesn't exist
     */
    public boolean assignTag(String objectFqn, String tagName) {
        if (getTagByName(tagName) == null) {
            return false;
        }
        assignments.computeIfAbsent(objectFqn, k -> new HashSet<>()).add(tagName);
        return true;
    }
    
    /**
     * Removes a tag assignment from a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name to remove
     * @return true if removed
     */
    public boolean unassignTag(String objectFqn, String tagName) {
        Set<String> objectTags = assignments.get(objectFqn);
        if (objectTags != null) {
            boolean removed = objectTags.remove(tagName);
            if (objectTags.isEmpty()) {
                assignments.remove(objectFqn);
            }
            return removed;
        }
        return false;
    }
    
    /**
     * Gets all tags assigned to a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @return set of assigned tags (never null)
     */
    public Set<Tag> getObjectTags(String objectFqn) {
        Set<String> tagNames = assignments.get(objectFqn);
        if (tagNames == null || tagNames.isEmpty()) {
            return Set.of();
        }
        Set<Tag> result = new HashSet<>();
        for (String name : tagNames) {
            Tag tag = getTagByName(name);
            if (tag != null) {
                result.add(tag);
            }
        }
        return result;
    }
    
    /**
     * Gets all metadata objects that have a specific tag.
     * 
     * @param tagName the tag name
     * @return set of FQNs
     */
    public Set<String> getObjectsByTag(String tagName) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : assignments.entrySet()) {
            if (entry.getValue().contains(tagName)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}
