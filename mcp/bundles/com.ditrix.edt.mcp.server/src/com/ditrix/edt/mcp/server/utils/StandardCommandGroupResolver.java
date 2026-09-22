/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Resolves a platform STANDARD command group - the toolbar / navigation-panel groups a command's
 * {@code group} can point at ({@code ActionsPanelTools}, {@code NavigationPanelSeeAlso}, ...) - from
 * the bare identifier a caller supplies, through the versioned {@link IEObjectProvider} catalogue.
 * The catalogue indexes every group under BOTH of its identifiers, the English {@code name} and the
 * Russian {@code nameRu}, so both spellings address the same group; the localized UI string is not
 * an identifier and is not accepted.
 *
 * <p>The resolved value is the catalogue's own UNRESOLVED proxy, never a
 * {@code McoreFactory}-built object. That is not a style choice: a detached factory object has no BM
 * namespace and no resource, so the write transaction's reference-value factory cannot build a
 * persistable reference to it and the commit dies with "Failed to persist reference value
 * ...Impl@hash". A proxy is exactly what EDT's own command inferrer assigns, and the exporter spells
 * it back out as the group's English name.</p>
 *
 * <p>No exception escapes this helper: an unreachable catalogue, an unknown identifier and a
 * catalogue that throws are all returned as an actionable {@link Result#error}.</p>
 */
public final class StandardCommandGroupResolver
{
    /** A resolved standard command group, or an actionable error. Exactly one field is non-null. */
    public static final class Result
    {
        /** The platform's unresolved proxy for the group, or {@code null} on failure. */
        public final EObject group;

        /** The actionable resolution error, or {@code null} on success. */
        public final String error;

        private Result(EObject group, String error)
        {
            this.group = group;
            this.error = error;
        }

        static Result ok(EObject group)
        {
            return new Result(group, null);
        }

        static Result error(String error)
        {
            return new Result(null, error);
        }
    }

    private StandardCommandGroupResolver()
    {
        // utility class
    }

    /**
     * The platform's command-group catalogue for a project's platform version.
     *
     * @param version the project's platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform cannot supply one
     */
    public static IEObjectProvider providerFor(Version version)
    {
        if (version == null)
        {
            return null;
        }
        try
        {
            return IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.COMMAND_GROUP,
                version);
        }
        catch (RuntimeException e)
        {
            // A missing catalogue is reported through the caller's refusal, which carries both
            // accepted forms; it is never an exception out of a property-preparation path.
            return null;
        }
    }

    /**
     * Resolves one bare standard-group identifier.
     *
     * @param provider the catalogue, may be {@code null} when the platform supplied none
     * @param token the identifier as supplied by the caller, in either language, any case
     * @return the platform proxy for that group, or an actionable error
     */
    public static Result resolve(IEObjectProvider provider, String token)
    {
        if (provider == null || token == null || token.isEmpty())
        {
            return Result.error(unknown(token, provider));
        }
        try
        {
            // The catalogue index is an EXACT, case-sensitive map, so the exact spelling is the
            // fast path and anything else is answered by a pass over the descriptions.
            EObject exact = provider.getProxy(token);
            if (isStandardGroupProxy(exact))
            {
                return Result.ok(exact);
            }
            for (IEObjectDescription description : descriptions(provider))
            {
                if (token.equalsIgnoreCase(nameOf(description)))
                {
                    EObject group = description.getEObjectOrProxy();
                    if (isStandardGroupProxy(group))
                    {
                        return Result.ok(group);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            return Result.error(unknown(token, null));
        }
        return Result.error(unknown(token, provider));
    }

    /**
     * The catalogue rendered for a refusal: one entry per GROUP (not per index key), English name
     * first with the Russian identifier beside it, in the platform's own declaration order.
     *
     * @param provider the catalogue, may be {@code null}
     * @return the rendered names, empty when the catalogue is unreachable
     */
    public static List<String> standardNames(IEObjectProvider provider)
    {
        if (provider == null)
        {
            return Collections.emptyList();
        }
        Map<URI, List<String>> byGroup = new LinkedHashMap<>();
        try
        {
            for (IEObjectDescription description : descriptions(provider))
            {
                String name = nameOf(description);
                URI target = description.getEObjectURI();
                if (name == null || name.isEmpty() || target == null)
                {
                    continue;
                }
                // Both identifiers of one group share its EObjectURI, and the catalogue registers
                // the English name before the Russian one, so encounter order is EN, RU.
                List<String> identifiers = byGroup.computeIfAbsent(target, key -> new ArrayList<>());
                if (!identifiers.contains(name))
                {
                    identifiers.add(name);
                }
            }
        }
        catch (RuntimeException e)
        {
            return Collections.emptyList();
        }
        List<String> rendered = new ArrayList<>();
        for (List<String> identifiers : byGroup.values())
        {
            rendered.add(identifiers.size() > 1
                ? identifiers.get(0) + " (" + identifiers.get(1) + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : identifiers.get(0));
        }
        return Collections.unmodifiableList(rendered);
    }

    /**
     * The one "how a command group is addressed" sentence, naming BOTH accepted forms and, when the
     * catalogue is reachable, every standard group in both identifiers.
     *
     * @param provider the catalogue, may be {@code null}
     * @return the hint sentence
     */
    public static String addressingHint(IEObjectProvider provider)
    {
        String forms = "Use a 'CommandGroup.<Name>' FQN for a configuration command group (a " //$NON-NLS-1$
            + "top-level metadata object; create it with create_metadata), or the bare name of a " //$NON-NLS-1$
            + "platform built-in STANDARD command group, in English or Russian"; //$NON-NLS-1$
        List<String> names = standardNames(provider);
        return names.isEmpty() ? forms + "." //$NON-NLS-1$
            : forms + ": " + String.join(", ", names) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String unknown(String token, IEObjectProvider provider)
    {
        return "'" + (token == null ? "" : token) + "' is not a platform STANDARD command group. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + addressingHint(provider);
    }

    private static Iterable<IEObjectDescription> descriptions(IEObjectProvider provider)
    {
        Iterable<IEObjectDescription> all = provider.getEObjectDescriptions(null);
        return all == null ? Collections.emptyList() : all;
    }

    private static String nameOf(IEObjectDescription description)
    {
        return description == null || description.getName() == null ? null
            : description.getName().toString();
    }

    /**
     * Whether a catalogue answer is a value this server may queue: a command group, and an
     * UNRESOLVED proxy. A non-proxy would only fail later, at commit, as an opaque BM assertion.
     */
    private static boolean isStandardGroupProxy(EObject value)
    {
        return value != null && value.eIsProxy() && value.eClass() != null
            && McorePackage.Literals.COMMAND_GROUP.isSuperTypeOf(value.eClass());
    }
}
