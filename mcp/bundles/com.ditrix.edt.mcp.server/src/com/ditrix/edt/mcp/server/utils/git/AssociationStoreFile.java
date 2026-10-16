/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;

/**
 * Reads, without EDT internals, the file in which EDT keeps the infobase bindings of one project in one
 * association context - the file EDT deletes WHOLE when it removes a binding from a context it does not
 * read now (#656). EDT's read ({@code IInfobaseAssociationManager.getAssociation}) cannot prove what that
 * deletion takes: it drops every UUID no registered infobase has, and it shows nothing but infobases.
 * <p>
 * The layout, from the bytecode of {@code com._1c.g5.v8.dt.platform.services.core} (identical on 2026.1.2,
 * 2026.2.0 and 2026.2.1):
 * <ul>
 * <li>store base: {@code project.getWorkingLocation("com._1c.g5.v8.dt.platform.services.core").toFile().toPath()}
 * ({@code ProjectSettingsStoreLocationProvider}); no working location means EDT keeps no file and deletes
 * none;</li>
 * <li>context path: the context string as a relative path, {@code .default} for {@code empty()}
 * ({@code InfobaseAssociationContextPathConverter.toPath});</li>
 * <li>file: {@code <base>/<context path>/AssociationData.properties}, a {@link Properties} file
 * ({@code InfobaseProjectSettingsStore});</li>
 * <li>keys: {@code Infobases} - the bound UUIDs joined by {@code ;} - and {@code DefaultInfobase} - one UUID
 * ({@code InfobaseAssociationManager}); EDT keeps any other key it finds when it rewrites the file.</li>
 * </ul>
 * A source-branch reference is NOT in this file: EDT keeps references in {@code <base>/.references}
 * ({@code PreferenceFileAssociationReferenceStore}), and lists them through
 * {@code IInfobaseAssociationManager.getAssociationContexts}.
 * <p>
 * The layout is not EDT API, so a caller treats "cannot locate" and "cannot read" as a refusal, never as
 * "nothing there". The context passed in is the STORED one: a context EDT keeps as a reference to another
 * resolves to the referenced file, which the caller has to rule out first.
 */
public final class AssociationStoreFile
{
    /** EDT's bundle whose project working location holds the association store. */
    public static final String STORE_BUNDLE = "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** The file name of one context's bindings. */
    public static final String FILE_NAME = "AssociationData.properties"; //$NON-NLS-1$

    /** The key of the bound UUIDs. */
    public static final String KEY_INFOBASES = "Infobases"; //$NON-NLS-1$

    /** The key of the default infobase's UUID. */
    public static final String KEY_DEFAULT_INFOBASE = "DefaultInfobase"; //$NON-NLS-1$

    /** The path EDT gives the unnamed context. */
    private static final String DEFAULT_CONTEXT_PATH = ".default"; //$NON-NLS-1$

    private static final String ENTRY_DELIMITER = ";"; //$NON-NLS-1$

    /** A shown value is cut to this many characters: a hand-edited file may hold anything. */
    private static final int MAX_SHOWN_VALUE = 80;

    private AssociationStoreFile()
    {
    }

    /**
     * Where EDT keeps {@code project}'s bindings of {@code context}.
     *
     * @return the file path (it may not exist), or {@code null} when the project has no working location
     * @throws RuntimeException when the context string is no valid path (EDT fails on it too)
     */
    public static Path locate(IProject project, InfobaseAssociationContext context)
    {
        IPath working = project.getWorkingLocation(STORE_BUNDLE);
        if (working == null)
        {
            return null;
        }
        String contextPath = context.getContext().orElse(DEFAULT_CONTEXT_PATH);
        return working.toFile().toPath().resolve(Paths.get(contextPath)).resolve(FILE_NAME);
    }

    /**
     * What the file holds besides the given infobases: one readable entry per UUID not in {@code known},
     * per value EDT could not parse as a UUID, and per key EDT does not write. A blank value holds nothing.
     *
     * @param file the file; when it does not exist, it holds nothing
     * @param known the UUIDs that are not "other" (the binding being removed, and bindings named already)
     * @return the other entries, in a stable order; empty when there is none
     * @throws IOException when the file exists but cannot be read
     */
    public static List<String> entriesBesides(Path file, Collection<UUID> known) throws IOException
    {
        List<String> others = new ArrayList<>();
        if (!Files.exists(file))
        {
            return others;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file))
        {
            properties.load(in);
        }
        for (String key : new TreeSet<>(properties.stringPropertyNames()))
        {
            String value = properties.getProperty(key);
            if (KEY_INFOBASES.equals(key))
            {
                for (String entry : value.split(ENTRY_DELIMITER))
                {
                    addIfOther(others, "infobase entry", entry, known); //$NON-NLS-1$
                }
            }
            else if (KEY_DEFAULT_INFOBASE.equals(key))
            {
                addIfOther(others, "default-infobase entry", value, known); //$NON-NLS-1$
            }
            else
            {
                others.add("entry '" + shown(key) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return others;
    }

    private static void addIfOther(List<String> others, String kind, String value, Collection<UUID> known)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        UUID uuid;
        try
        {
            uuid = UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            others.add("unreadable " + kind + " '" + shown(value) + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        if (!known.contains(uuid))
        {
            others.add(kind + " " + uuid); //$NON-NLS-1$
        }
    }

    private static String shown(String value)
    {
        return value.length() <= MAX_SHOWN_VALUE ? value : value.substring(0, MAX_SHOWN_VALUE) + "..."; //$NON-NLS-1$
    }
}
