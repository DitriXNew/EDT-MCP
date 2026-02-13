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
     * Map from metadata object FQN to list of tag names.
     * Key: FQN (e.g., "Catalog.Products", "Document.SalesOrder")
     * Value: List of tag names assigned to this object
     * 
     * Using List instead of Set to avoid !!set markers in YAML output.
     */
    private Map<String, List<String>> assignments;
    
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
    
    public Map<String, List<String>> getAssignments() {
        return assignments;
    }
    
    public void setAssignments(Map<String, List<String>> assignments) {
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
            assignments.values().forEach(list -> list.remove(tagName));
            return true;
        }
        return false;
    }
    
    /**
     * Assigns a tag to a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name to assign
     * @return true if assigned, false if tag doesn't exist or already assigned
     */
    public boolean assignTag(String objectFqn, String tagName) {
        if (getTagByName(tagName) == null) {
            return false;
        }
        List<String> objectTags = assignments.computeIfAbsent(objectFqn, k -> new ArrayList<>());
        if (!objectTags.contains(tagName)) {
            objectTags.add(tagName);
            return true;
        }
        return false;
    }
    
    /**
     * Removes a tag assignment from a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name to remove
     * @return true if removed
     */
    public boolean unassignTag(String objectFqn, String tagName) {
        List<String> objectTags = assignments.get(objectFqn);
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
        List<String> tagNames = assignments.get(objectFqn);
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
     * Gets the names of all tags assigned to a metadata object.
     * 
     * @param objectFqn the FQN of the metadata object
     * @return set of tag names (never null)
     */
    public Set<String> getTagNames(String objectFqn) {
        List<String> tagNames = assignments.get(objectFqn);
        if (tagNames == null || tagNames.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(tagNames);
    }
    
    /**
     * Renames an object in the assignments (updates FQN key).
     * 
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return true if renamed, false if old FQN not found
     */
    public boolean renameObject(String oldFqn, String newFqn) {
        List<String> tagNames = assignments.remove(oldFqn);
        if (tagNames != null && !tagNames.isEmpty()) {
            assignments.put(newFqn, tagNames);
            return true;
        }
        return false;
    }
    
    /**
     * Removes an object from all tag assignments.
     * 
     * @param objectFqn the FQN of the object to remove
     * @return true if removed, false if not found
     */
    public boolean removeObject(String objectFqn) {
        List<String> removed = assignments.remove(objectFqn);
        return removed != null && !removed.isEmpty();
    }
    
    /**
     * Gets all metadata objects that have a specific tag.
     * 
     * @param tagName the tag name
     * @return set of FQNs
     */
    public Set<String> getObjectsByTag(String tagName) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : assignments.entrySet()) {
            if (entry.getValue().contains(tagName)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    /**
     * Moves a tag up in the list (decreases its index).
     * This affects the order for keyboard shortcuts Ctrl+Alt+1-0.
     * 
     * @param tagName the tag name to move
     * @return true if moved, false if already at top or not found
     */
    public boolean moveTagUp(String tagName) {
        int index = getTagIndex(tagName);
        if (index > 0) {
            Tag tag = tags.remove(index);
            tags.add(index - 1, tag);
            return true;
        }
        return false;
    }
    
    /**
     * Moves a tag down in the list (increases its index).
     * This affects the order for keyboard shortcuts Ctrl+Alt+1-0.
     * 
     * @param tagName the tag name to move
     * @return true if moved, false if already at bottom or not found
     */
    public boolean moveTagDown(String tagName) {
        int index = getTagIndex(tagName);
        if (index >= 0 && index < tags.size() - 1) {
            Tag tag = tags.remove(index);
            tags.add(index + 1, tag);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the index of a tag in the list.
     * 
     * @param tagName the tag name
     * @return the index (0-based), or -1 if not found
     */
    public int getTagIndex(String tagName) {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).getName().equals(tagName)) {
                return i;
            }
        }
        return -1;
    }
}
