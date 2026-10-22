/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.path.DcsPathException;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;

/** One mutation's lazy, platform-specific catalogue snapshot, including failed loads. */
public final class DcsWriterCatalogues
{
    /** Loads exactly the catalogue used by the corresponding container writer. */
    @FunctionalInterface
    public interface Source
    {
        DcsAvailableParameterCollection load(Enum<?> catalogue, Version version, String language)
            throws DcsPathException;
    }

    private final Version version;
    private final AppearanceCatalogue appearance;
    private final String language;
    private final Source source;
    private final Map<Enum<?>, Lookup> cache = new HashMap<>();

    public DcsWriterCatalogues(Version version, AppearanceCatalogue appearance, String language,
        Source source)
    {
        this.version = version == null ? Version.LATEST : version;
        this.appearance = Objects.requireNonNull(appearance);
        this.language = language == null ? "en" : language; //$NON-NLS-1$
        this.source = Objects.requireNonNull(source);
    }

    public Version version()
    {
        return version;
    }

    /**
     * Whether these catalogues serve a DataCompositionSchema root (a report's main schema or a
     * template), the only root kind the lossless XML channel can replace.
     */
    public boolean isSchemaRoot()
    {
        return appearance == AppearanceCatalogue.SCHEMA;
    }

    Lookup outputCatalogue(ParameterValues holder)
    {
        OutputParameterCatalogue kind = OutputParameterCatalogue.forHolder(holder);
        return kind == null ? new Lookup(null, "unsupported parameter-value holder " //$NON-NLS-1$
            + holder.eClass().getName()) : load(kind);
    }

    Lookup appearanceCatalogue()
    {
        return load(appearance);
    }

    private Lookup load(Enum<?> kind)
    {
        return cache.computeIfAbsent(kind, key ->
        {
            try
            {
                return new Lookup(Objects.requireNonNull(source.load(key, version, language),
                    "catalogue source returned null"), null); //$NON-NLS-1$
            }
            catch (DcsPathException | RuntimeException e)
            {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return new Lookup(null, message == null ? e.getClass().getSimpleName() : message);
            }
        });
    }

    record Lookup(DcsAvailableParameterCollection parameters, String failure)
    {
    }
}
