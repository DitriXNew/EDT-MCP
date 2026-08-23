/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.google.gson.JsonObject;

/** Lossless XML serialization and detached-schema import through EDT's native DCS serializer. */
public final class DcsXmlCodec
{
    /** Default raw XML characters requested per page; the serialized envelope is measured separately. */
    public static final int DEFAULT_CHUNK_CHARS = 40_000;

    /**
     * The protocol may append a bounded userSignal after this envelope is measured. Its maximum
     * serialized growth is 48 fixed characters plus 512 message characters times the six-character
     * worst-case JSON escape, or 3,120 characters; reserve that exact amount so the augmented text
     * fallback remains within {@link OutputSizeGuard#MAX_CONTENT_CHARS}.
     */
    private static final int MAX_XML_PAGE_ENVELOPE_CHARS = OutputSizeGuard.MAX_CONTENT_CHARS
        - McpProtocolHandler.MAX_USER_SIGNAL_JSON_AUGMENTATION_CHARS;

    private static final String LINE_SEPARATOR = "\n"; //$NON-NLS-1$

    private final DcsV8Serializer serializer;
    private final IDtProject dtProject;

    DcsXmlCodec(DcsV8Serializer serializer, IDtProject dtProject)
    {
        this.serializer = serializer;
        this.dtProject = dtProject;
    }

    /** Resolves the project services required by {@link DcsV8Serializer}. */
    public static ResolveResult resolve(IProject project)
    {
        if (project == null)
        {
            return ResolveResult.failure("The EDT project is unavailable for DCS XML processing. " //$NON-NLS-1$
                + "Re-open the project and retry."); //$NON-NLS-1$
        }
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IDtProject dtProject = dtProjectManager == null ? null : dtProjectManager.getDtProject(project);
        IV8Project v8Project = v8ProjectManager == null ? null : v8ProjectManager.getProject(project);
        IResourceLookup resourceLookup;
        try
        {
            resourceLookup = ServiceAccess.get(IResourceLookup.class);
        }
        catch (RuntimeException e)
        {
            return ResolveResult.failure("EDT's IResourceLookup service is unavailable for DCS XML " //$NON-NLS-1$
                + "processing: " + exceptionMessage(e) + ". Re-open the project and retry."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (dtProject == null || v8Project == null || v8Project.getVersion() == null
            || resourceLookup == null)
        {
            return ResolveResult.failure("EDT services needed for DCS XML processing are unavailable " //$NON-NLS-1$
                + "(DT project, platform version, or IResourceLookup). Wait for the project to finish " //$NON-NLS-1$
                + "loading, then retry."); //$NON-NLS-1$
        }
        return ResolveResult.success(new DcsXmlCodec(
            new DcsV8Serializer(dtProject, v8Project.getVersion(), resourceLookup), dtProject));
    }

    /** Serializes one attached schema while the caller keeps it inside a BM read boundary. */
    public XmlResult serialize(DataCompositionSchema schema)
    {
        if (schema == null)
        {
            return XmlResult.failure("The DCS schema is empty and cannot be serialized as XML. " //$NON-NLS-1$
                + "Create schema content first, then retry format='xml'."); //$NON-NLS-1$
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            serializer.serializeXML(schema, output, LINE_SEPARATOR, dtProject);
            return XmlResult.success(output.toString(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            return XmlResult.failure("EDT could not serialize the DataCompositionSchema as XML: " //$NON-NLS-1$
                + exceptionMessage(e) + ". Re-open or clean the project, then retry format='xml'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Builds one JSON envelope for a lossless XML transfer. The candidate chunk is serialized first;
     * if JSON escaping makes the envelope exceed {@link OutputSizeGuard#MAX_CONTENT_CHARS}, a binary
     * search shrinks the chunk to the largest UTF-16-safe boundary that fits. Consequently the
     * content guard is a no-op even when a client receives the JSON tool result through its text
     * fallback channel.
     *
     * @param xml complete serialized DCS XML
     * @param hash current 20-character DCS structural hash
     * @param offset requested zero-based UTF-16 offset
     * @param limit requested maximum chunk length in UTF-16 characters
     * @return serialized JSON envelope whose length cannot exceed the content budget
     */
    public static String serializePageEnvelope(String xml, String hash, int offset, int limit)
    {
        return serializePageEnvelope(xml, hash, offset, limit, MAX_XML_PAGE_ENVELOPE_CHARS);
    }

    static String serializePageEnvelope(String xml, String hash, int offset, int limit, int maxSerializedChars)
    {
        if (xml == null)
        {
            throw new IllegalArgumentException("Complete DCS XML is required"); //$NON-NLS-1$
        }
        if (hash == null || !hash.matches("[0-9a-f]{20}")) //$NON-NLS-1$
        {
            throw new IllegalArgumentException("A 20-character lowercase DCS hash is required"); //$NON-NLS-1$
        }
        if (maxSerializedChars < 1)
        {
            throw new IllegalArgumentException("The serialized-envelope budget must be positive"); //$NON-NLS-1$
        }

        int total = xml.length();
        int start = Math.min(Math.max(0, offset), total);
        start = safeStart(xml, start);
        long requestedEnd = (long)start + Math.max(1, limit);
        int end = safeEndAtOrBefore(xml, start, (int)Math.min(total, requestedEnd));
        if (end == start && start < total)
        {
            end = nextBoundary(xml, start);
        }

        String envelope = buildEnvelope(xml, hash, total, start, end);
        if (envelope.length() <= maxSerializedChars)
        {
            return envelope;
        }

        int minimumEnd = start < total ? nextBoundary(xml, start) : start;
        String minimum = buildEnvelope(xml, hash, total, start, minimumEnd);
        if (minimum.length() > maxSerializedChars)
        {
            throw new IllegalArgumentException("The XML page envelope cannot fit the configured output budget"); //$NON-NLS-1$
        }

        int bestEnd = minimumEnd;
        String best = minimum;
        int low = minimumEnd + 1;
        int high = end - 1;
        while (low <= high)
        {
            int midpoint = low + (high - low) / 2;
            int candidateEnd = safeEndAtOrBefore(xml, start, midpoint);
            if (candidateEnd < minimumEnd)
            {
                low = midpoint + 1;
                continue;
            }
            String candidate = buildEnvelope(xml, hash, total, start, candidateEnd);
            if (candidate.length() <= maxSerializedChars)
            {
                bestEnd = candidateEnd;
                best = candidate;
                low = midpoint + 1;
            }
            else
            {
                high = midpoint - 1;
            }
        }

        // Rebuild from the selected boundary so the returned nextOffset and XML slice are visibly
        // coupled even if the search visited the same surrogate-safe boundary more than once.
        String fitted = buildEnvelope(xml, hash, total, start, bestEnd);
        if (fitted.length() > maxSerializedChars)
        {
            throw new IllegalStateException("Measured XML page envelope exceeds the output budget"); //$NON-NLS-1$
        }
        return fitted.equals(best) ? best : fitted;
    }

    private static String buildEnvelope(String xml, String hash, int total, int start, int end)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", true); //$NON-NLS-1$
        result.addProperty("totalChars", total); //$NON-NLS-1$
        result.addProperty("offset", start); //$NON-NLS-1$
        boolean hasMore = end < total;
        result.addProperty("hasMore", hasMore); //$NON-NLS-1$
        if (hasMore)
        {
            result.addProperty("nextOffset", end); //$NON-NLS-1$
        }
        result.addProperty("hash", hash); //$NON-NLS-1$
        result.addProperty("xml", xml.substring(start, end)); //$NON-NLS-1$
        return GsonProvider.toJson(result);
    }

    private static int safeStart(String value, int offset)
    {
        if (offset > 0 && offset < value.length()
            && Character.isHighSurrogate(value.charAt(offset - 1))
            && Character.isLowSurrogate(value.charAt(offset)))
        {
            return offset - 1;
        }
        return offset;
    }

    private static int safeEndAtOrBefore(String value, int start, int end)
    {
        int bounded = Math.max(start, Math.min(end, value.length()));
        if (bounded > start && bounded < value.length()
            && Character.isHighSurrogate(value.charAt(bounded - 1))
            && Character.isLowSurrogate(value.charAt(bounded)))
        {
            return bounded - 1;
        }
        return bounded;
    }

    private static int nextBoundary(String value, int start)
    {
        if (start < value.length() - 1 && Character.isHighSurrogate(value.charAt(start))
            && Character.isLowSurrogate(value.charAt(start + 1)))
        {
            return start + 2;
        }
        return Math.min(value.length(), start + 1);
    }

    /** Deserializes XML to the detached schema returned by EDT's native serializer. */
    public SchemaResult deserialize(String xml)
    {
        if (xml == null || xml.trim().isEmpty())
        {
            return SchemaResult.failure("body.xml is empty. Pass the complete DataCompositionSchema " //$NON-NLS-1$
                + "XML returned by dcs format='xml'."); //$NON-NLS-1$
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        {
            DataCompositionSchema schema = serializer.deserializeXML(input);
            if (schema == null)
            {
                return SchemaResult.failure("body.xml does not contain a DataCompositionSchema root. " //$NON-NLS-1$
                    + "Pass the complete XML returned by dcs format='xml'."); //$NON-NLS-1$
            }
            return SchemaResult.success(schema);
        }
        catch (Exception e)
        {
            return SchemaResult.failure("body.xml is malformed or is not valid EDT DCS XML: " //$NON-NLS-1$
                + exceptionMessage(e) + ". Pass the complete XML returned by dcs format='xml' " //$NON-NLS-1$ //$NON-NLS-2$
                + "without editing or truncating it."); //$NON-NLS-1$
        }
    }

    /**
     * Replaces every serializable root feature while retaining the target's attached BM top-object identity.
     * The imported schema is detached; attaching it over the existing external-property top object would risk
     * losing that object's FQN. A deep EMF copy preserves every containment and internal cross-reference, and
     * moving the copied root features into the existing schema lets BM record the wholesale change normally.
     */
    public static void replaceContent(DataCompositionSchema target, DataCompositionSchema imported)
    {
        if (target == null || imported == null)
        {
            throw new IllegalArgumentException("Both target and imported DCS schemas are required"); //$NON-NLS-1$
        }
        EcoreUtil.Copier copier = new EcoreUtil.Copier(true, true);
        EObject copied = copier.copy(imported);
        copier.copyReferences();
        for (EStructuralFeature feature : target.eClass().getEAllStructuralFeatures())
        {
            if (!feature.isChangeable() || feature.isDerived() || feature.isTransient())
            {
                continue;
            }
            if (feature.isMany())
            {
                replaceMany(target, copied, feature);
            }
            else if (copied.eIsSet(feature))
            {
                target.eSet(feature, copied.eGet(feature));
            }
            else
            {
                target.eUnset(feature);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceMany(EObject target, EObject copied, EStructuralFeature feature)
    {
        EList<Object> targetValues = (EList<Object>)target.eGet(feature);
        List<Object> replacements = new ArrayList<>((List<Object>)copied.eGet(feature));
        targetValues.clear();
        targetValues.addAll(replacements);
    }

    private static String exceptionMessage(Throwable error)
    {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** Resolved codec or an actionable service-resolution error. */
    public static final class ResolveResult
    {
        private final DcsXmlCodec codec;
        private final String error;

        private ResolveResult(DcsXmlCodec codec, String error)
        {
            this.codec = codec;
            this.error = error;
        }

        private static ResolveResult success(DcsXmlCodec codec)
        {
            return new ResolveResult(codec, null);
        }

        private static ResolveResult failure(String error)
        {
            return new ResolveResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public DcsXmlCodec codec()
        {
            return codec;
        }

        public String error()
        {
            return error;
        }
    }

    /** Serialized XML or an actionable serializer error. */
    public static final class XmlResult
    {
        private final String xml;
        private final String error;

        private XmlResult(String xml, String error)
        {
            this.xml = xml;
            this.error = error;
        }

        private static XmlResult success(String xml)
        {
            return new XmlResult(xml, null);
        }

        private static XmlResult failure(String error)
        {
            return new XmlResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String xml()
        {
            return xml;
        }

        public String error()
        {
            return error;
        }
    }

    /** Detached schema or an actionable deserialization error. */
    public static final class SchemaResult
    {
        private final DataCompositionSchema schema;
        private final String error;

        private SchemaResult(DataCompositionSchema schema, String error)
        {
            this.schema = schema;
            this.error = error;
        }

        private static SchemaResult success(DataCompositionSchema schema)
        {
            return new SchemaResult(schema, null);
        }

        private static SchemaResult failure(String error)
        {
            return new SchemaResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public DataCompositionSchema schema()
        {
            return schema;
        }

        public String error()
        {
            return error;
        }
    }
}
