/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups;

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
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;

/**
 * Service for managing virtual folder groups in the Navigator.
 * Groups allow organizing metadata objects into custom folders.
 * 
 * <p>Thread Safety: This class is thread-safe. It uses a ReadWriteLock
 * to protect the storage cache and CopyOnWriteArrayList for listeners.</p>
 */
public class GroupService implements IResourceChangeListener {
    
    private static final IPath GROUPS_PATH = new Path(GroupConstants.SETTINGS_FOLDER)
        .append(GroupConstants.GROUPS_FILE);
    
    private static GroupService instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    /**
     * Cache of group storage per project.
     * Protected by cacheLock.
     */
    private final Map<String, GroupStorage> projectStorageCache = new HashMap<>();
    
    /**
     * Lock for thread-safe cache access.
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    /**
     * Listeners for group changes.
     * CopyOnWriteArrayList provides thread-safe iteration.
     */
    private final List<IGroupChangeListener> listeners = new CopyOnWriteArrayList<>();
    
    private GroupService() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, 
            IResourceChangeEvent.POST_CHANGE);
    }
    
    /**
     * Gets the singleton instance.
     * 
     * @return the group service instance
     */
    public static GroupService getInstance() {
        GroupService localInstance = instance;
        if (localInstance == null) {
            synchronized (INSTANCE_LOCK) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new GroupService();
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
     * Adds a listener for group changes.
     * 
     * @param listener the listener to add
     */
    public void addGroupChangeListener(IGroupChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    /**
     * Removes a group change listener.
     * 
     * @param listener the listener to remove
     */
    public void removeGroupChangeListener(IGroupChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Gets the group storage for a project.
     * Thread-safe with proper locking.
     * 
     * @param project the project
     * @return the group storage, never null
     */
    public GroupStorage getGroupStorage(IProject project) {
        String projectName = project.getName();
        
        // Try read lock first (fast path)
        cacheLock.readLock().lock();
        try {
            GroupStorage storage = projectStorageCache.get(projectName);
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
            GroupStorage storage = projectStorageCache.get(projectName);
            if (storage == null) {
                storage = loadGroupStorage(project);
                projectStorageCache.put(projectName, storage);
            }
            return storage;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets all groups at a specific path.
     * 
     * @param project the project
     * @param path the parent path (e.g., "CommonModules")
     * @return list of groups sorted by order then name
     */
    public List<Group> getGroupsAtPath(IProject project, String path) {
        return getGroupStorage(project).getGroupsAtPath(path);
    }
    
    /**
     * Gets all groups in a project.
     * 
     * @param project the project
     * @return list of all groups
     */
    public List<Group> getAllGroups(IProject project) {
        return getGroupStorage(project).getGroups();
    }
    
    /**
     * Creates a new group.
     * 
     * @param project the project
     * @param name the group name
     * @param path the parent path in Navigator
     * @param description optional description
     * @return the created group, or null if already exists
     */
    public Group createGroup(IProject project, String name, String path, String description) {
        GroupStorage storage = getGroupStorage(project);
        Group group = new Group(name, path);
        group.setDescription(description);
        
        // Set order to be last among siblings
        List<Group> siblings = storage.getGroupsAtPath(path);
        if (!siblings.isEmpty()) {
            int maxOrder = siblings.stream()
                .mapToInt(Group::getOrder)
                .max()
                .orElse(0);
            group.setOrder(maxOrder + 1);
        }
        
        if (storage.addGroup(group)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return group;
        }
        return null;
    }
    
    /**
     * Renames a group.
     * 
     * @param project the project
     * @param oldFullPath the current full path
     * @param newName the new name
     * @return true if renamed
     */
    public boolean renameGroup(IProject project, String oldFullPath, String newName) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.renameGroup(oldFullPath, newName)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Deletes a group.
     * Objects in the group will return to their original location.
     * 
     * @param project the project
     * @param fullPath the full path of the group
     * @return true if deleted
     */
    public boolean deleteGroup(IProject project, String fullPath) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeGroup(fullPath)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Adds an object to a group.
     * Removes from previous group if any.
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @param groupFullPath the target group full path
     * @return true if added
     */
    public boolean addObjectToGroup(IProject project, String objectFqn, String groupFullPath) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.moveObjectToGroup(objectFqn, groupFullPath)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Removes an object from its group (returns to original location).
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @return true if removed from any group
     */
    public boolean removeObjectFromGroup(IProject project, String objectFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeObjectFromAllGroups(objectFqn)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Finds which group contains an object.
     * 
     * @param project the project
     * @param objectFqn the FQN of the object
     * @return the group or null if not grouped
     */
    public Group findGroupForObject(IProject project, String objectFqn) {
        return getGroupStorage(project).findGroupForObject(objectFqn);
    }
    
    /**
     * Gets all grouped objects at a path.
     * Used to filter objects from original location.
     * 
     * @param project the project
     * @param path the path
     * @return set of grouped FQNs
     */
    public Set<String> getGroupedObjectsAtPath(IProject project, String path) {
        return getGroupStorage(project).getGroupedObjectsAtPath(path);
    }
    
    /**
     * Checks if a path has any groups.
     * 
     * @param project the project
     * @param path the path
     * @return true if groups exist
     */
    public boolean hasGroupsAtPath(IProject project, String path) {
        return getGroupStorage(project).hasGroupsAtPath(path);
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
        fireGroupsChanged(project);
    }
    
    /**
     * Renames an object in group assignments (for refactoring support).
     * 
     * @param project the project
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return true if renamed
     */
    public boolean renameObject(IProject project, String oldFqn, String newFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.renameObject(oldFqn, newFqn)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    /**
     * Removes an object from all groups (for refactoring support).
     * 
     * @param project the project
     * @param objectFqn the FQN of the deleted object
     * @return true if removed
     */
    public boolean removeObject(IProject project, String objectFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeObjectFromAllGroups(objectFqn)) {
            saveGroupStorage(project, storage);
            fireGroupsChanged(project);
            return true;
        }
        return false;
    }
    
    // === Private methods ===
    
    private GroupStorage loadGroupStorage(IProject project) {
        IFile groupsFile = project.getFile(GROUPS_PATH);
        if (!groupsFile.exists()) {
            return new GroupStorage();
        }
        
        try (InputStream is = groupsFile.getContents();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setTagInspector(tag -> true); // Allow all tags
            Constructor constructor = new Constructor(GroupStorage.class, loaderOptions);
            Yaml yaml = new Yaml(constructor);
            GroupStorage storage = yaml.load(reader);
            return storage != null ? storage : new GroupStorage();
            
        } catch (CoreException | IOException e) {
            Activator.logError("Failed to load groups from " + groupsFile.getFullPath(), e);
            return new GroupStorage();
        }
    }
    
    private void saveGroupStorage(IProject project, GroupStorage storage) {
        try {
            // Ensure .settings folder exists
            IFolder settingsFolder = project.getFolder(GroupConstants.SETTINGS_FOLDER);
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
            // Don't output class tags
            representer.addClassTag(GroupStorage.class, org.yaml.snakeyaml.nodes.Tag.MAP);
            representer.addClassTag(Group.class, org.yaml.snakeyaml.nodes.Tag.MAP);
            
            Yaml yaml = new Yaml(representer, options);
            StringWriter writer = new StringWriter();
            yaml.dump(storage, writer);
            
            byte[] content = writer.toString().getBytes(StandardCharsets.UTF_8);
            
            IFile groupsFile = project.getFile(GROUPS_PATH);
            if (groupsFile.exists()) {
                groupsFile.setContents(
                    new java.io.ByteArrayInputStream(content), 
                    true, true, null);
            } else {
                groupsFile.create(
                    new java.io.ByteArrayInputStream(content), 
                    true, null);
            }
            
        } catch (CoreException e) {
            Activator.logError("Failed to save groups for project " + project.getName(), e);
        }
    }
    
    private void fireGroupsChanged(IProject project) {
        for (IGroupChangeListener listener : listeners) {
            try {
                listener.onGroupsChanged(project);
            } catch (Exception e) {
                Activator.logError("Error notifying group change listener", e);
            }
        }
    }
    
    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        // Invalidate cache when groups file changes externally
        if (event.getDelta() == null) {
            return;
        }
        
        try {
            event.getDelta().accept(delta -> {
                if (delta.getResource() instanceof IFile file) {
                    if (GroupConstants.GROUPS_FILE.equals(file.getName()) 
                            && GroupConstants.SETTINGS_FOLDER.equals(file.getParent().getName())) {
                        IProject project = file.getProject();
                        // Invalidate cache with write lock
                        cacheLock.writeLock().lock();
                        try {
                            projectStorageCache.remove(project.getName());
                        } finally {
                            cacheLock.writeLock().unlock();
                        }
                        fireGroupsChanged(project);
                    }
                }
                return true;
            });
        } catch (CoreException e) {
            Activator.logError("Error processing resource change", e);
        }
    }
}
