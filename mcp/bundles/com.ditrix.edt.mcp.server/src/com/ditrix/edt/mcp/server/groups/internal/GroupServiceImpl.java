/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import com.ditrix.edt.mcp.server.groups.repository.YamlGroupRepository;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * OSGi DS implementation of the group service.
 *
 * <p>Thread Safety: This class is thread-safe. It uses a per-project lock
 * to protect storage mutations and snapshot reads and CopyOnWriteArrayList for listeners.</p>
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
    private final YamlGroupRepository repository = new YamlGroupRepository();

    /**
     * Listeners for group changes.
     * CopyOnWriteArrayList provides thread-safe iteration.
     */
    private final List<IGroupChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * File system watcher for external changes. AtomicReference (not a bare volatile field) so the
     * mutable resource is published thread-safely between the start/stop path and the watcher thread.
     */
    private final AtomicReference<WatchService> watchService = new AtomicReference<>();

    /**
     * Thread for watching file system changes. AtomicReference for the same thread-safe-publication
     * reason as {@link #watchService}.
     */
    private final AtomicReference<Thread> watchThread = new AtomicReference<>();

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
        repository.clear();

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
                watchService.set(FileSystems.getDefault().newWatchService());
                Thread thread = new Thread(this::runFileWatcher, "GroupService-FileWatcher");
                thread.setDaemon(true);
                watchThread.set(thread);
                thread.start();
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
            Thread thread = watchThread.get();
            if (thread != null) {
                thread.interrupt();
                try {
                    // Wait for thread to finish (with timeout)
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                watchThread.set(null);
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
            WatchService service = watchService.get();
            if (service != null) {
                try {
                    service.close();
                } catch (IOException e) {
                    Activator.logError("Error closing watch service", e);
                }
                watchService.set(null);
            }
        }
    }

    /**
     * Runs the file watcher loop in a background thread.
     */
    private void runFileWatcher() {
        while (!shutdown.get() && !Thread.currentThread().isInterrupted()) { // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
            WatchService service = watchService.get();
            if (service == null) {
                break;
            }

            try {
                WatchKey key = service.take();
                processWatchKey(key);
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
     * Processes the pending events of a single watch key: refreshes affected project groups and
     * resets the key (dropping it from the registry when it is no longer valid). Extracted from
     * {@link #runFileWatcher()} to keep that loop's complexity in check; carries no checked
     * exception of its own.
     */
    private void processWatchKey(WatchKey key) {
        java.nio.file.Path settingsPath;

        synchronized (watcherLock) {
            settingsPath = watchKeyToPath.get(key);
        }

        if (settingsPath != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                processWatchEvent(event, settingsPath);
            }
        }

        boolean valid = key.reset();
        if (!valid) {
            synchronized (watcherLock) {
                watchKeyToPath.remove(key);
            }
        }
    }

    /**
     * Handles a single watch event: when it refers to the groups file, refreshes the owning
     * project's groups. Overflow events are ignored. Extracted from {@link #processWatchKey(WatchKey)}
     * to keep its nesting shallow.
     *
     * @param event the watch event to handle
     * @param settingsPath the watched {@code .settings} directory the event originated from
     */
    private void processWatchEvent(WatchEvent<?> event, java.nio.file.Path settingsPath) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
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

    /**
     * Refreshes group file from disk and updates Navigator.
     */
    private void refreshProjectGroups(String projectName) {
        if (shutdown.get()) {
            return;
        }

        IProject project = ProjectContext.of(projectName).project();
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
        WatchService service = watchService.get();
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
        ensureProjectWatched(project);
        return repository.snapshot(project);
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
        ensureProjectWatched(project);
        Group result = repository.update(project, storage -> {
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
                return group;
            }
            return null;
        }, Objects::nonNull);
        if (result != null) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean renameGroup(IProject project, String oldFullPath, String newName) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.renameGroup(oldFullPath, newName), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean updateGroup(IProject project, String oldFullPath, String newName, String description) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.updateGroup(oldFullPath, newName, description), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean deleteGroup(IProject project, String fullPath) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.removeGroup(fullPath), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean addObjectToGroup(IProject project, String objectFqn, String groupFullPath) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.moveObjectToGroup(objectFqn, groupFullPath), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean removeObjectFromGroup(IProject project, String objectFqn) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.removeObjectFromAllGroups(objectFqn), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
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
        repository.invalidate(project);
        fireGroupsChanged(project);
    }

    @Override
    public boolean renameObject(IProject project, String oldFqn, String newFqn) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.renameObject(oldFqn, newFqn), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
    }

    @Override
    public boolean removeObject(IProject project, String objectFqn) {
        ensureProjectWatched(project);
        boolean result = repository.update(project, storage -> storage.removeObjectFromAllGroups(objectFqn), Boolean.TRUE::equals);
        if (result) {
            fireGroupsChanged(project);
        }
        return result;
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
                        repository.invalidate(project);
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
