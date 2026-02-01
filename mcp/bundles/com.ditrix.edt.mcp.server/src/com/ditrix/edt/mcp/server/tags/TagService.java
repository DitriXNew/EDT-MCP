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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 * 
 * <p>Thread Safety: This class is thread-safe. It uses a ReadWriteLock
 * to protect the storage cache and CopyOnWriteArrayList for listeners.</p>
 */
public class TagService implements IResourceChangeListener {
    
    private static final IPath TAGS_PATH = new Path(TagConstants.SETTINGS_FOLDER)
        .append(TagConstants.TAGS_FILE);
    
    private static TagService instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    /**
     * Cache of tag storage per project.
     * Protected by cacheLock.
     */
    private final Map<String, TagStorage> projectStorageCache = new HashMap<>();
    
    /**
     * Lock for thread-safe cache access.
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    /**
     * Listeners for tag changes.
     * CopyOnWriteArrayList provides thread-safe iteration.
     */
    private final List<ITagChangeListener> listeners = new CopyOnWriteArrayList<>();
    
    private TagService() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, 
            IResourceChangeEvent.POST_CHANGE);
    }
    
    /**
     * Gets the singleton instance.
     * 
     * @return the tag service instance
     */
    public static TagService getInstance() {
        TagService localInstance = instance;
        if (localInstance == null) {
            synchronized (INSTANCE_LOCK) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new TagService();
                }
            }
        }
        return localInstance;
    }
    
    /**
     * Disposes the service and releases resources.
     */
    public static void dispose() {
        synchronized (INSTANCE_LOCK) {
            if (instance != null) {
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(instance);
                instance.cacheLock.writeLock().lock();
                try {
                    instance.projectStorageCache.clear();
                } finally {
                    instance.cacheLock.writeLock().unlock();
                }
                instance.listeners.clear();
                instance = null;
            }
        }
    }
    
    /**
     * Adds a listener for tag changes.
     * 
     * @param listener the listener to add
     */
    public void addTagChangeListener(ITagChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
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
     * Thread-safe with proper locking.
     * 
     * @param project the project
     * @return the tag storage, never null
     */
    public TagStorage getTagStorage(IProject project) {
        String projectName = project.getName();
        
        // Try read lock first (fast path)
        cacheLock.readLock().lock();
        try {
            TagStorage storage = projectStorageCache.get(projectName);
            if (storage != null) {
                return storage;
            }
        } finally {
            cacheLock.readLock().unlock();
        }
        
        // Need to load - acquire write lock
        cacheLock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            TagStorage storage = projectStorageCache.get(projectName);
            if (storage == null) {
                storage = loadTagStorage(project);
                projectStorageCache.put(projectName, storage);
            }
            return storage;
        } finally {
            cacheLock.writeLock().unlock();
        }
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
        cacheLock.writeLock().lock();
        try {
            projectStorageCache.remove(project.getName());
        } finally {
            cacheLock.writeLock().unlock();
        }
        fireTagsChanged(project);
    }
    
    /**
     * Renames an object in tag assignments (updates FQN).
     * Called when a metadata object is renamed via refactoring.
     * 
     * @param project the project
     * @param oldFqn the old FQN of the object
     * @param newFqn the new FQN of the object
     * @return true if renamed successfully
     */
    public boolean renameObject(IProject project, String oldFqn, String newFqn) {
        TagStorage storage = getTagStorage(project);
        if (storage.renameObject(oldFqn, newFqn)) {
            saveTagStorage(project, storage);
            fireAssignmentsChanged(project, newFqn);
            return true;
        }
        return false;
    }
    
    /**
     * Removes an object from all tag assignments.
     * Called when a metadata object is deleted via refactoring.
     * 
     * @param project the project
     * @param objectFqn the FQN of the deleted object
     * @return true if removed successfully
     */
    public boolean removeObject(IProject project, String objectFqn) {
        TagStorage storage = getTagStorage(project);
        if (storage.removeObject(objectFqn)) {
            saveTagStorage(project, storage);
            fireAssignmentsChanged(project, objectFqn);
            return true;
        }
        return false;
    }
    
    /**
     * Moves a tag up in the list (decreases its index).
     * This affects the order for keyboard shortcuts Ctrl+Alt+1-0.
     * 
     * @param project the project
     * @param tagName the tag name to move
     * @return true if moved, false if already at top or not found
     */
    public boolean moveTagUp(IProject project, String tagName) {
        TagStorage storage = getTagStorage(project);
        if (storage.moveTagUp(tagName)) {
            saveTagStorage(project, storage);
            fireTagsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Moves a tag down in the list (increases its index).
     * This affects the order for keyboard shortcuts Ctrl+Alt+1-0.
     * 
     * @param project the project
     * @param tagName the tag name to move
     * @return true if moved, false if already at bottom or not found
     */
    public boolean moveTagDown(IProject project, String tagName) {
        TagStorage storage = getTagStorage(project);
        if (storage.moveTagDown(tagName)) {
            saveTagStorage(project, storage);
            fireTagsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the index of a tag (1-based, for display).
     * This corresponds to keyboard shortcuts Ctrl+Alt+1-0.
     * 
     * @param project the project
     * @param tagName the tag name
     * @return the index (1-based), or 0 if not found or beyond position 10
     */
    public int getTagHotkeyIndex(IProject project, String tagName) {
        TagStorage storage = getTagStorage(project);
        int index = storage.getTagIndex(tagName);
        if (index >= 0 && index < 10) {
            // Convert 0-based to 1-based, but index 9 becomes 0 (for Ctrl+Alt+0)
            return index == 9 ? 0 : index + 1;
        }
        return -1; // No hotkey for this tag
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
            IFolder settingsFolder = project.getFolder(TagConstants.SETTINGS_FOLDER);
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
        for (ITagChangeListener listener : listeners) {
            try {
                listener.onTagsChanged(project);
            } catch (Exception e) {
                Activator.logError("Error notifying tag change listener", e);
            }
        }
    }
    
    private void fireAssignmentsChanged(IProject project, String objectFqn) {
        for (ITagChangeListener listener : listeners) {
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
                    if (TagConstants.TAGS_FILE.equals(file.getName()) 
                            && TagConstants.SETTINGS_FOLDER.equals(file.getParent().getName())) {
                        IProject project = file.getProject();
                        // Invalidate cache with write lock
                        cacheLock.writeLock().lock();
                        try {
                            projectStorageCache.remove(project.getName());
                        } finally {
                            cacheLock.writeLock().unlock();
                        }
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
