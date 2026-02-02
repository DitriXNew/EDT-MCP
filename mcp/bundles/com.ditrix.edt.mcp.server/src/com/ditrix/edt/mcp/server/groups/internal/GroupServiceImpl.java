/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.internal;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupConstants;
import com.ditrix.edt.mcp.server.groups.IGroupChangeListener;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;
import com.ditrix.edt.mcp.server.groups.repository.IGroupRepository;
import com.ditrix.edt.mcp.server.groups.repository.YamlGroupRepository;

/**
 * OSGi DS implementation of the group service.
 * 
 * <p>Thread Safety: This class is thread-safe. It uses a ReadWriteLock
 * to protect the storage cache and CopyOnWriteArrayList for listeners.</p>
 * 
 * <p>Improvements over previous implementation:</p>
 * <ul>
 *   <li>Proper resource cleanup on deactivation</li>
 *   <li>AtomicBoolean for shutdown flag</li>
 *   <li>Delegated YAML handling to repository</li>
 *   <li>Thread interrupt handling in file watcher</li>
 * </ul>
 * 
 * <p>This component is configured via OSGI-INF XML descriptor.</p>
 */
public class GroupServiceImpl implements IGroupService, IResourceChangeListener {
    
    /**
     * Repository for loading/saving group data.
     */
    private final IGroupRepository repository = new YamlGroupRepository();
    
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
    
    /**
     * File system watcher for external changes.
     */
    private volatile WatchService watchService;
    
    /**
     * Thread for watching file system changes.
     */
    private volatile Thread watchThread;
    
    /**
     * Map from watch key to project path for file watcher.
     */
    private final Map<WatchKey, java.nio.file.Path> watchKeyToPath = new HashMap<>();
    
    /**
     * Set of projects being watched.
     */
    private final Set<String> watchedProjects = new HashSet<>();
    
    /**
     * Shutdown flag for graceful thread termination.
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    /**
     * Lock for file watcher resources.
     */
    private final Object watcherLock = new Object();
    
    /**
     * Activates the service. Called by OSGi DS framework.
     */
    public void activate() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, 
            IResourceChangeEvent.POST_CHANGE);
        startFileWatcher();
        Activator.logInfo("GroupService activated");
    }
    
    /**
     * Deactivates the service. Called by OSGi DS framework.
     */
    public void deactivate() {
        shutdown.set(true);
        
        // Remove resource listener first
        try {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        } catch (Exception e) {
            // Ignore - workspace might be shutting down
        }
        
        // Stop file watcher
        stopFileWatcher();
        
        // Clear cache
        cacheLock.writeLock().lock();
        try {
            projectStorageCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
        
        // Clear listeners
        listeners.clear();
        
        // Clear watcher data
        synchronized (watcherLock) {
            watchedProjects.clear();
            watchKeyToPath.clear();
        }
        
        Activator.logInfo("GroupService deactivated");
    }
    
    /**
     * Starts the file system watcher thread.
     */
    private void startFileWatcher() {
        synchronized (watcherLock) {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                watchThread = new Thread(this::runFileWatcher, "GroupService-FileWatcher");
                watchThread.setDaemon(true);
                watchThread.start();
            } catch (IOException e) {
                Activator.logError("Failed to start file watcher", e);
            }
        }
    }
    
    /**
     * Stops the file system watcher thread with proper cleanup.
     */
    private void stopFileWatcher() {
        synchronized (watcherLock) {
            // Interrupt thread first
            Thread thread = watchThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    // Wait for thread to finish (with timeout)
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                watchThread = null;
            }
            
            // Cancel all watch keys (use copy to avoid concurrent modification)
            for (WatchKey key : new ArrayList<>(watchKeyToPath.keySet())) {
                try {
                    key.cancel();
                } catch (Exception e) {
                    // Ignore
                }
            }
            watchKeyToPath.clear();
            
            // Close watch service
            WatchService service = watchService;
            if (service != null) {
                try {
                    service.close();
                } catch (IOException e) {
                    Activator.logError("Error closing watch service", e);
                }
                watchService = null;
            }
        }
    }
    
    /**
     * Runs the file watcher loop in a background thread.
     */
    private void runFileWatcher() {
        while (!shutdown.get() && !Thread.currentThread().isInterrupted()) {
            WatchService service = watchService;
            if (service == null) {
                break;
            }
            
            try {
                WatchKey key = service.take();
                java.nio.file.Path settingsPath;
                
                synchronized (watcherLock) {
                    settingsPath = watchKeyToPath.get(key);
                }
                
                if (settingsPath != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        
                        @SuppressWarnings("unchecked")
                        WatchEvent<java.nio.file.Path> pathEvent = (WatchEvent<java.nio.file.Path>) event;
                        java.nio.file.Path fileName = pathEvent.context();
                        
                        if (GroupConstants.GROUPS_FILE.equals(fileName.toString())) {
                            java.nio.file.Path projectPath = settingsPath.getParent();
                            if (projectPath != null) {
                                refreshProjectGroups(projectPath.getFileName().toString());
                            }
                        }
                    }
                }
                
                boolean valid = key.reset();
                if (!valid) {
                    synchronized (watcherLock) {
                        watchKeyToPath.remove(key);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!shutdown.get()) {
                    Activator.logError("Error in file watcher", e);
                }
            }
        }
    }
    
    /**
     * Refreshes group file from disk and updates Navigator.
     */
    private void refreshProjectGroups(String projectName) {
        if (shutdown.get()) {
            return;
        }
        
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project != null && project.isOpen()) {
            try {
                IFile groupsFile = project.getFile(
                    GroupConstants.SETTINGS_FOLDER + "/" + GroupConstants.GROUPS_FILE);
                if (groupsFile.exists()) {
                    groupsFile.refreshLocal(1, new NullProgressMonitor());
                }
            } catch (CoreException e) {
                if (!shutdown.get()) {
                    Activator.logError("Error refreshing groups file", e);
                }
            }
        }
    }
    
    /**
     * Registers a project for file watching.
     */
    private void ensureProjectWatched(IProject project) {
        WatchService service = watchService;
        if (service == null) {
            return;
        }
        
        String projectName = project.getName();
        
        synchronized (watcherLock) {
            if (watchedProjects.contains(projectName)) {
                return;
            }
            
            java.nio.file.Path projectLocation = project.getLocation() != null 
                ? project.getLocation().toFile().toPath() 
                : null;
            
            if (projectLocation == null) {
                return;
            }
            
            java.nio.file.Path settingsPath = projectLocation.resolve(GroupConstants.SETTINGS_FOLDER);
            
            // Create .settings folder if it doesn't exist
            if (!Files.exists(settingsPath)) {
                try {
                    Files.createDirectories(settingsPath);
                } catch (IOException e) {
                    return;
                }
            }
            
            try {
                WatchKey key = settingsPath.register(service, 
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
                watchKeyToPath.put(key, settingsPath);
                watchedProjects.add(projectName);
            } catch (IOException e) {
                Activator.logError("Failed to watch project: " + projectName, e);
            }
        }
    }
    
    @Override
    public void addGroupChangeListener(IGroupChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    @Override
    public void removeGroupChangeListener(IGroupChangeListener listener) {
        listeners.remove(listener);
    }
    
    @Override
    public GroupStorage getGroupStorage(IProject project) {
        String projectName = project.getName();
        
        ensureProjectWatched(project);
        
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
                storage = repository.load(project);
                projectStorageCache.put(projectName, storage);
            }
            return storage;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    @Override
    public List<Group> getGroupsAtPath(IProject project, String path) {
        return getGroupStorage(project).getGroupsAtPath(path);
    }
    
    @Override
    public List<Group> getAllGroups(IProject project) {
        return getGroupStorage(project).getGroups();
    }
    
    @Override
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
            saveAndNotify(project, storage);
            return group;
        }
        return null;
    }
    
    @Override
    public boolean renameGroup(IProject project, String oldFullPath, String newName) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.renameGroup(oldFullPath, newName)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean updateGroup(IProject project, String oldFullPath, String newName, String description) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.updateGroup(oldFullPath, newName, description)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean deleteGroup(IProject project, String fullPath) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeGroup(fullPath)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean addObjectToGroup(IProject project, String objectFqn, String groupFullPath) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.moveObjectToGroup(objectFqn, groupFullPath)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean removeObjectFromGroup(IProject project, String objectFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeObjectFromAllGroups(objectFqn)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public Group findGroupForObject(IProject project, String objectFqn) {
        return getGroupStorage(project).findGroupForObject(objectFqn);
    }
    
    @Override
    public Set<String> getGroupedObjectsAtPath(IProject project, String path) {
        return getGroupStorage(project).getGroupedObjectsAtPath(path);
    }
    
    @Override
    public boolean hasGroupsAtPath(IProject project, String path) {
        return getGroupStorage(project).hasGroupsAtPath(path);
    }
    
    @Override
    public void refresh(IProject project) {
        cacheLock.writeLock().lock();
        try {
            projectStorageCache.remove(project.getName());
        } finally {
            cacheLock.writeLock().unlock();
        }
        fireGroupsChanged(project);
    }
    
    @Override
    public boolean renameObject(IProject project, String oldFqn, String newFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.renameObject(oldFqn, newFqn)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean removeObject(IProject project, String objectFqn) {
        GroupStorage storage = getGroupStorage(project);
        if (storage.removeObjectFromAllGroups(objectFqn)) {
            saveAndNotify(project, storage);
            return true;
        }
        return false;
    }
    
    /**
     * Saves storage and notifies listeners.
     */
    private void saveAndNotify(IProject project, GroupStorage storage) {
        repository.save(project, storage);
        fireGroupsChanged(project);
    }
    
    private void fireGroupsChanged(IProject project) {
        if (shutdown.get()) {
            return;
        }
        
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
        if (shutdown.get() || event.getDelta() == null) {
            return;
        }
        
        try {
            event.getDelta().accept(delta -> {
                if (delta.getResource() instanceof IFile file) {
                    if (GroupConstants.GROUPS_FILE.equals(file.getName()) 
                            && GroupConstants.SETTINGS_FOLDER.equals(file.getParent().getName())) {
                        IProject project = file.getProject();
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
            if (!shutdown.get()) {
                Activator.logError("Error processing resource change", e);
            }
        }
    }
}
