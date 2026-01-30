/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Service for managing metadata object tags.
 * Provides methods to create, assign, and query tags.
 * Tags are stored in each project's .settings folder.
 */
public class TagService implements IResourceChangeListener {
    
    private static final String SETTINGS_FOLDER = ".settings";
    private static final String TAGS_FILE = "metadata-tags.yaml";
    private static final IPath TAGS_PATH = new Path(SETTINGS_FOLDER).append(TAGS_FILE);
    
    private static TagService instance;
    
    /**
     * Cache of tag storage per project.
     */
    private final Map<String, TagStorage> projectStorageCache = new ConcurrentHashMap<>();
    
    /**
     * Listeners for tag changes.
     */
    private final Map<ITagChangeListener, Boolean> listeners = new ConcurrentHashMap<>();
    
    private TagService() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, 
            IResourceChangeEvent.POST_CHANGE);
    }
    
    /**
     * Gets the singleton instance.
     * 
     * @return the tag service instance
     */
    public static synchronized TagService getInstance() {
        if (instance == null) {
            instance = new TagService();
        }
        return instance;
    }
    
    /**
     * Disposes the service and releases resources.
     */
    public static synchronized void dispose() {
        if (instance != null) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(instance);
            instance.projectStorageCache.clear();
            instance.listeners.clear();
            instance = null;
        }
    }
    
    /**
     * Adds a listener for tag changes.
     * 
     * @param listener the listener to add
     */
    public void addTagChangeListener(ITagChangeListener listener) {
        listeners.put(listener, Boolean.TRUE);
    }
    
    /**
     * Removes a tag change listener.
     * 
     * @param listener the listener to remove
     */
    public void removeTagChangeListener(ITagChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Gets the tag storage for a project.
     * 
     * @param project the project
     * @return the tag storage, never null
     */
    public TagStorage getTagStorage(IProject project) {
        String projectName = project.getName();
        return projectStorageCache.computeIfAbsent(projectName, k -> loadTagStorage(project));
    }
    
    /**
     * Gets all defined tags in the project.
     * 
     * @param project the project
     * @return list of tags
     */
    public java.util.List<Tag> getTags(IProject project) {
        return getTagStorage(project).getTags();
    }
    
    /**
     * Creates a new tag in the project.
     * 
     * @param project the project
     * @param name the tag name
     * @param color the tag color (hex format)
     * @param description optional description
     * @return the created tag, or null if already exists
     */
    public Tag createTag(IProject project, String name, String color, String description) {
        TagStorage storage = getTagStorage(project);
        Tag tag = new Tag(name, color, description);
        if (storage.addTag(tag)) {
            saveTagStorage(project, storage);
            fireTagsChanged(project);
            return tag;
        }
        return null;
    }
    
    /**
     * Updates an existing tag.
     * 
     * @param project the project
     * @param oldName the current tag name
     * @param newName the new name (or null to keep)
     * @param color the new color (or null to keep)
     * @param description the new description (or null to keep)
     * @return true if updated
     */
    public boolean updateTag(IProject project, String oldName, String newName, 
            String color, String description) {
        TagStorage storage = getTagStorage(project);
        Tag tag = storage.getTagByName(oldName);
        if (tag == null) {
            return false;
        }
        
        // Check if renaming would conflict
        if (newName != null && !newName.equals(oldName) 
                && storage.getTagByName(newName) != null) {
            return false;
        }
        
        // Update assignments if renaming
        if (newName != null && !newName.equals(oldName)) {
            Set<String> objects = storage.getObjectsByTag(oldName);
            for (String fqn : objects) {
                storage.unassignTag(fqn, oldName);
                storage.assignTag(fqn, newName);
            }
            tag.setName(newName);
        }
        
        if (color != null) {
            tag.setColor(color);
        }
        if (description != null) {
            tag.setDescription(description);
        }
        
        saveTagStorage(project, storage);
        fireTagsChanged(project);
        return true;
    }
    
    /**
     * Deletes a tag and all its assignments.
     * 
     * @param project the project
     * @param tagName the tag name
     * @return true if deleted
     */
    public boolean deleteTag(IProject project, String tagName) {
        TagStorage storage = getTagStorage(project);
        if (storage.removeTag(tagName)) {
            saveTagStorage(project, storage);
            fireTagsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Gets tags assigned to a metadata object.
     * 
     * @param project the project
     * @param objectFqn the FQN of the metadata object
     * @return set of tags
     */
    public Set<Tag> getObjectTags(IProject project, String objectFqn) {
        return getTagStorage(project).getObjectTags(objectFqn);
    }
    
    /**
     * Assigns a tag to a metadata object.
     * 
     * @param project the project
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name
     * @return true if assigned
     */
    public boolean assignTag(IProject project, String objectFqn, String tagName) {
        TagStorage storage = getTagStorage(project);
        if (storage.assignTag(objectFqn, tagName)) {
            saveTagStorage(project, storage);
            fireAssignmentsChanged(project, objectFqn);
            return true;
        }
        return false;
    }
    
    /**
     * Removes a tag assignment from a metadata object.
     * 
     * @param project the project
     * @param objectFqn the FQN of the metadata object
     * @param tagName the tag name
     * @return true if removed
     */
    public boolean unassignTag(IProject project, String objectFqn, String tagName) {
        TagStorage storage = getTagStorage(project);
        if (storage.unassignTag(objectFqn, tagName)) {
            saveTagStorage(project, storage);
            fireAssignmentsChanged(project, objectFqn);
            return true;
        }
        return false;
    }
    
    /**
     * Finds all objects with a specific tag.
     * 
     * @param project the project
     * @param tagName the tag name
     * @return set of FQNs
     */
    public Set<String> findObjectsByTag(IProject project, String tagName) {
        return getTagStorage(project).getObjectsByTag(tagName);
    }
    
    /**
     * Finds all objects that have any of the specified tags.
     * 
     * @param project the project
     * @param tagNames the tag names to search for
     * @return map of FQN to matching tags
     */
    public Map<String, Set<Tag>> findObjectsByTags(IProject project, Set<String> tagNames) {
        TagStorage storage = getTagStorage(project);
        Map<String, Set<Tag>> result = new HashMap<>();
        
        for (String tagName : tagNames) {
            Set<String> objects = storage.getObjectsByTag(tagName);
            Tag tag = storage.getTagByName(tagName);
            if (tag != null) {
                for (String fqn : objects) {
                    result.computeIfAbsent(fqn, k -> new java.util.HashSet<>()).add(tag);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Refreshes the cache for a project.
     * 
     * @param project the project
     */
    public void refresh(IProject project) {
        projectStorageCache.remove(project.getName());
        fireTagsChanged(project);
    }
    
    // === Private methods ===
    
    private TagStorage loadTagStorage(IProject project) {
        IFile tagsFile = project.getFile(TAGS_PATH);
        if (!tagsFile.exists()) {
            return new TagStorage();
        }
        
        try (InputStream is = tagsFile.getContents();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setTagInspector(tag -> true); // Allow all tags
            Constructor constructor = new Constructor(TagStorage.class, loaderOptions);
            Yaml yaml = new Yaml(constructor);
            TagStorage storage = yaml.load(reader);
            return storage != null ? storage : new TagStorage();
            
        } catch (CoreException | IOException e) {
            Activator.logError("Failed to load tags from " + tagsFile.getFullPath(), e);
            return new TagStorage();
        }
    }
    
    private void saveTagStorage(IProject project, TagStorage storage) {
        try {
            // Ensure .settings folder exists
            IFolder settingsFolder = project.getFolder(SETTINGS_FOLDER);
            if (!settingsFolder.exists()) {
                settingsFolder.create(true, true, null);
            }
            
            // Configure YAML output
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            
            Representer representer = new Representer(options);
            representer.getPropertyUtils().setSkipMissingProperties(true);
            // Don't output class tags like !!com.ditrix... or !!set
            representer.addClassTag(TagStorage.class, org.yaml.snakeyaml.nodes.Tag.MAP);
            representer.addClassTag(Tag.class, org.yaml.snakeyaml.nodes.Tag.MAP);
            
            Yaml yaml = new Yaml(representer, options);
            StringWriter writer = new StringWriter();
            yaml.dump(storage, writer);
            
            byte[] content = writer.toString().getBytes(StandardCharsets.UTF_8);
            
            IFile tagsFile = project.getFile(TAGS_PATH);
            if (tagsFile.exists()) {
                tagsFile.setContents(
                    new java.io.ByteArrayInputStream(content), 
                    true, true, null);
            } else {
                tagsFile.create(
                    new java.io.ByteArrayInputStream(content), 
                    true, null);
            }
            
        } catch (CoreException e) {
            Activator.logError("Failed to save tags for project " + project.getName(), e);
        }
    }
    
    private void fireTagsChanged(IProject project) {
        for (ITagChangeListener listener : listeners.keySet()) {
            try {
                listener.onTagsChanged(project);
            } catch (Exception e) {
                Activator.logError("Error notifying tag change listener", e);
            }
        }
    }
    
    private void fireAssignmentsChanged(IProject project, String objectFqn) {
        for (ITagChangeListener listener : listeners.keySet()) {
            try {
                listener.onAssignmentsChanged(project, objectFqn);
            } catch (Exception e) {
                Activator.logError("Error notifying tag change listener", e);
            }
        }
    }
    
    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        // Invalidate cache when tags file changes externally
        if (event.getDelta() == null) {
            return;
        }
        
        try {
            event.getDelta().accept(delta -> {
                if (delta.getResource() instanceof IFile file) {
                    if (TAGS_FILE.equals(file.getName()) 
                            && SETTINGS_FOLDER.equals(file.getParent().getName())) {
                        IProject project = file.getProject();
                        projectStorageCache.remove(project.getName());
                        fireTagsChanged(project);
                    }
                }
                return true;
            });
        } catch (CoreException e) {
            Activator.logError("Error processing resource change", e);
        }
    }
    
    /**
     * Listener interface for tag changes.
     */
    public interface ITagChangeListener {
        
        /**
         * Called when tags are added, removed, or modified.
         * 
         * @param project the project
         */
        void onTagsChanged(IProject project);
        
        /**
         * Called when tag assignments change for an object.
         * 
         * @param project the project
         * @param objectFqn the FQN of the affected object
         */
        void onAssignmentsChanged(IProject project, String objectFqn);
    }
}
