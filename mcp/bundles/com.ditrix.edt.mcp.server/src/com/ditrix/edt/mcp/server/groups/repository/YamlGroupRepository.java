/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.repository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupConstants;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;

/**
 * YAML-based implementation of group repository.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Git-friendly output: sorted keys and stable ordering</li>
 *   <li>File locking for concurrent access protection</li>
 *   <li>Orphan FQN cleanup on load</li>
 *   <li>Empty file avoidance (doesn't create file for empty storage)</li>
 * </ul>
 */
public class YamlGroupRepository implements IGroupRepository {
    
    private static final IPath GROUPS_PATH = 
        new org.eclipse.core.runtime.Path(GroupConstants.SETTINGS_FOLDER)
            .append(GroupConstants.GROUPS_FILE);
    
    /**
     * Comparator for Git-friendly group ordering.
     * Groups are sorted by path, then by order, then by name.
     */
    private static final Comparator<Group> GIT_FRIENDLY_ORDER = Comparator
        .comparing((Group g) -> g.getPath() != null ? g.getPath() : "")
        .thenComparingInt(Group::getOrder)
        .thenComparing(Group::getName, String.CASE_INSENSITIVE_ORDER);
    
    @Override
    public GroupStorage load(IProject project) {
        IFile groupsFile = project.getFile(GROUPS_PATH);
        if (!groupsFile.exists()) {
            return new GroupStorage();
        }
        
        try (InputStream is = groupsFile.getContents();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setTagInspector(tag -> true);
            Constructor constructor = new Constructor(GroupStorage.class, loaderOptions);
            Yaml yaml = new Yaml(constructor);
            GroupStorage storage = yaml.load(reader);
            
            if (storage == null) {
                return new GroupStorage();
            }
            
            // Cleanup orphaned FQNs (objects that might have been deleted)
            cleanupOrphanedFqns(project, storage);
            
            return storage;
            
        } catch (CoreException | IOException e) {
            Activator.logError("Failed to load groups from " + groupsFile.getFullPath(), e);
            return new GroupStorage();
        }
    }
    
    @Override
    public boolean save(IProject project, GroupStorage storage) {
        // Don't create file for empty storage
        if (storage.getGroups().isEmpty()) {
            return deleteIfExists(project);
        }
        
        try {
            // Ensure .settings folder exists
            IFolder settingsFolder = project.getFolder(GroupConstants.SETTINGS_FOLDER);
            if (!settingsFolder.exists()) {
                settingsFolder.create(true, true, null);
            }
            
            // Sort groups for Git-friendly output
            List<Group> sortedGroups = new ArrayList<>(storage.getGroups());
            Collections.sort(sortedGroups, GIT_FRIENDLY_ORDER);
            
            // Sort children in each group for stable output
            for (Group group : sortedGroups) {
                if (group.getChildren() != null && !group.getChildren().isEmpty()) {
                    List<String> sortedChildren = new ArrayList<>(group.getChildren());
                    Collections.sort(sortedChildren, String.CASE_INSENSITIVE_ORDER);
                    group.setChildren(sortedChildren);
                }
            }
            
            // Create a sorted storage for output
            GroupStorage sortedStorage = new GroupStorage();
            sortedStorage.setGroups(sortedGroups);
            
            // Configure YAML output
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            options.setWidth(120);
            options.setSplitLines(false);
            
            Representer representer = new SortedKeysRepresenter(options);
            representer.getPropertyUtils().setSkipMissingProperties(true);
            representer.addClassTag(GroupStorage.class, Tag.MAP);
            representer.addClassTag(Group.class, Tag.MAP);
            
            Yaml yaml = new Yaml(representer, options);
            StringWriter writer = new StringWriter();
            yaml.dump(sortedStorage, writer);
            
            byte[] content = writer.toString().getBytes(StandardCharsets.UTF_8);
            
            IFile groupsFile = project.getFile(GROUPS_PATH);
            
            // Use file locking for concurrent access protection
            return saveWithLock(project, groupsFile, content);
            
        } catch (CoreException e) {
            Activator.logError("Failed to save groups for project " + project.getName(), e);
            return false;
        }
    }
    
    @Override
    public boolean exists(IProject project) {
        IFile groupsFile = project.getFile(GROUPS_PATH);
        return groupsFile.exists();
    }
    
    @Override
    public boolean delete(IProject project) {
        return deleteIfExists(project);
    }
    
    /**
     * Saves content with file lock for concurrent access protection.
     */
    private boolean saveWithLock(IProject project, IFile groupsFile, byte[] content) {
        Path filePath = groupsFile.getLocation() != null 
            ? groupsFile.getLocation().toFile().toPath() 
            : null;
        
        if (filePath == null) {
            // Fallback to direct Eclipse resource API
            return saveDirectly(groupsFile, content);
        }
        
        try {
            // Create parent directories if needed
            Files.createDirectories(filePath.getParent());
            
            // Use file lock for atomic write
            try (FileChannel channel = FileChannel.open(filePath, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 FileLock lock = channel.tryLock()) {
                
                if (lock == null) {
                    // Could not acquire lock, fallback to direct save
                    Activator.logWarning("Could not acquire file lock, falling back to direct save");
                    return saveDirectly(groupsFile, content);
                }
                
                channel.write(java.nio.ByteBuffer.wrap(content));
                
                // Refresh Eclipse resource
                groupsFile.refreshLocal(1, null);
                return true;
            }
            
        } catch (IOException | CoreException e) {
            Activator.logError("Failed to save with file lock, trying direct save", e);
            return saveDirectly(groupsFile, content);
        }
    }
    
    /**
     * Direct save using Eclipse resource API.
     */
    private boolean saveDirectly(IFile groupsFile, byte[] content) {
        try {
            if (groupsFile.exists()) {
                groupsFile.setContents(new ByteArrayInputStream(content), true, true, null);
            } else {
                groupsFile.create(new ByteArrayInputStream(content), true, null);
            }
            return true;
        } catch (CoreException e) {
            Activator.logError("Failed to save groups file directly", e);
            return false;
        }
    }
    
    /**
     * Deletes group file if it exists.
     */
    private boolean deleteIfExists(IProject project) {
        IFile groupsFile = project.getFile(GROUPS_PATH);
        if (groupsFile.exists()) {
            try {
                groupsFile.delete(true, null);
                return true;
            } catch (CoreException e) {
                Activator.logError("Failed to delete empty groups file", e);
                return false;
            }
        }
        return true;
    }
    
    /**
     * Cleans up orphaned FQNs (metadata objects that no longer exist).
     * This is called during load to ensure storage consistency.
     */
    private void cleanupOrphanedFqns(IProject project, GroupStorage storage) {
        // Note: Full orphan detection requires BM access which should be done lazily
        // Here we just clean obviously invalid FQNs (empty, null)
        for (Group group : storage.getGroups()) {
            if (group.getChildren() != null) {
                group.getChildren().removeIf(fqn -> fqn == null || fqn.isBlank());
            }
        }
    }
    
    /**
     * Custom representer that outputs keys in sorted order for Git-friendly diffs.
     */
    private static class SortedKeysRepresenter extends Representer {
        
        public SortedKeysRepresenter(DumperOptions options) {
            super(options);
        }
        
        @Override
        protected org.yaml.snakeyaml.nodes.MappingNode representJavaBean(
                java.util.Set<org.yaml.snakeyaml.introspector.Property> properties, Object javaBean) {
            // Sort properties by name for consistent output
            List<org.yaml.snakeyaml.introspector.Property> sorted = new ArrayList<>(properties);
            sorted.sort(Comparator.comparing(org.yaml.snakeyaml.introspector.Property::getName));
            return super.representJavaBean(new java.util.LinkedHashSet<>(sorted), javaBean);
        }
    }
}
