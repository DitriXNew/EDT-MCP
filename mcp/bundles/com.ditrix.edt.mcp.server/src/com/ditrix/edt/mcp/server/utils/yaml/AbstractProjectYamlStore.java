/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

import com.ditrix.edt.mcp.server.Activator;

/** Project-scoped YAML persistence with detached reads and serialized mutations. */
public abstract class AbstractProjectYamlStore<S> {
    private final Class<S> storageType;
    private final IPath path;
    private final Map<IProject, Entry<S>> projects = new ConcurrentHashMap<>();

    protected AbstractProjectYamlStore(Class<S> storageType, IPath path) {
        this.storageType = storageType;
        this.path = path;
    }

    protected abstract S emptyStorage();
    protected abstract S copy(S storage);
    public abstract String dump(S storage);

    protected boolean omitEmpty(S storage) {
        return false;
    }

    /** The writers emit plain mappings; no Java class tags are part of the format. */
    public final S readYaml(Reader reader) {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> false);
        Constructor constructor = new Constructor(storageType, options);
        S storage = new Yaml(constructor).load(reader);
        return storage != null ? storage : emptyStorage();
    }

    public final S snapshot(IProject project) {
        Entry<S> entry = entry(project);
        entry.lock.lock();
        try {
            S current = cached(project, entry);
            return current != null ? copy(current) : emptyStorage();
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * The callback and disk write share the project lock; results never alias the cache. A change that
     * could not be saved - or made over a file that could not be read - is not published and answers
     * {@code false} (a Boolean result) or {@code null}.
     */
    public final <R> R update(IProject project, Function<S, R> operation, Predicate<R> changed) {
        Entry<S> entry = entry(project);
        entry.lock.lock();
        try {
            S current = cached(project, entry);
            if (current == null) {
                R result = operation.apply(emptyStorage());
                return changed.test(result) ? refused(result) : result;
            }
            S working = copy(current);
            R result = operation.apply(working);
            if (changed.test(result) && !saveUnlocked(project, working)) {
                entry.storage = null;
                return refused(result);
            }
            entry.storage = copy(working);
            return result;
        } finally {
            entry.lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    static <R> R refused(R result) {
        return result instanceof Boolean ? (R) Boolean.FALSE : null;
    }

    /**
     * Lock-free: resource listeners call this while the workspace holds a scheduling rule, and a writer
     * holding the project lock may be waiting for that rule in {@code refreshLocal}.
     */
    public final void invalidate(IProject project) {
        entry(project).stale.set(true);
    }

    public final void clear() {
        projects.keySet().forEach(this::invalidate);
    }

    public final S load(IProject project) {
        Entry<S> entry = entry(project);
        entry.lock.lock();
        try {
            S loaded = loadUnlocked(project);
            return loaded != null ? loaded : emptyStorage();
        } finally {
            entry.lock.unlock();
        }
    }

    public final boolean save(IProject project, S storage) {
        Entry<S> entry = entry(project);
        entry.lock.lock();
        try {
            S detached = copy(storage);
            boolean saved = saveUnlocked(project, detached);
            entry.storage = saved ? detached : null;
            return saved;
        } finally {
            entry.lock.unlock();
        }
    }

    public final boolean exists(IProject project) {
        return project.getFile(path).exists();
    }

    public final boolean delete(IProject project) {
        Entry<S> entry = entry(project);
        entry.lock.lock();
        try {
            boolean deleted = deleteFile(project.getFile(path));
            if (deleted) {
                entry.storage = emptyStorage();
            }
            return deleted;
        } finally {
            entry.lock.unlock();
        }
    }

    private Entry<S> entry(IProject project) {
        return projects.computeIfAbsent(project, key -> new Entry<>());
    }

    /** {@code null} when the file could not be read; nothing is cached then, so the next call retries. */
    private S cached(IProject project, Entry<S> entry) {
        if (entry.stale.getAndSet(false)) {
            entry.storage = null;
        }
        if (entry.storage == null) {
            entry.storage = loadUnlocked(project);
        }
        return entry.storage;
    }

    /**
     * An unreadable file answers {@code null}, so a mutation cannot overwrite it with an empty store;
     * unparseable content (including refused global tags) reads as empty.
     */
    private S loadUnlocked(IProject project) {
        IFile file = project.getFile(path);
        if (!file.exists()) {
            return emptyStorage();
        }
        try (Reader reader = new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)) {
            return readYaml(reader);
        } catch (YAMLException e) {
            Activator.logError("Failed to parse associations from " + file.getFullPath(), e);
            return emptyStorage();
        } catch (CoreException | IOException e) {
            Activator.logError("Failed to read associations from " + file.getFullPath(), e);
            return null;
        }
    }

    private boolean saveUnlocked(IProject project, S storage) {
        IFile file = project.getFile(path);
        if (omitEmpty(storage)) {
            return deleteFile(file);
        }
        try {
            IFolder folder = project.getFolder(path.removeLastSegments(1));
            if (!folder.exists()) {
                folder.create(true, true, null);
            }
            return saveWithLock(file, dump(storage).getBytes(StandardCharsets.UTF_8));
        } catch (CoreException e) {
            Activator.logError("Failed to save associations for project " + project.getName(), e);
            return false;
        }
    }

    private boolean saveWithLock(IFile file, byte[] content) {
        if (file.getLocation() == null) {
            return saveDirectly(file, content);
        }
        java.nio.file.Path filePath = file.getLocation().toFile().toPath();
        try {
            Files.createDirectories(filePath.getParent());
            try (FileChannel channel = FileChannel.open(filePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    Activator.logWarning("Could not acquire association file lock: " + filePath);
                    return false;
                }
                channel.truncate(0);
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            file.refreshLocal(1, null);
            return true;
        } catch (OverlappingFileLockException e) {
            Activator.logError("Association file is already locked: " + filePath, e);
            return false;
        } catch (IOException | CoreException e) {
            Activator.logError("Failed to save with file lock, trying direct save", e);
            return saveDirectly(file, content);
        }
    }

    private boolean saveDirectly(IFile file, byte[] content) {
        try {
            if (file.exists()) {
                file.setContents(new ByteArrayInputStream(content), true, true, null);
            } else {
                file.create(new ByteArrayInputStream(content), true, null);
            }
            return true;
        } catch (CoreException e) {
            Activator.logError("Failed to save association file", e);
            return false;
        }
    }

    private boolean deleteFile(IFile file) {
        try {
            if (file.exists()) {
                file.delete(true, null);
            }
            return true;
        } catch (CoreException e) {
            Activator.logError("Failed to delete empty association file", e);
            return false;
        }
    }

    private static final class Entry<S> {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean stale = new AtomicBoolean();
        private S storage;
    }
}
