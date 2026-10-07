/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * The one way this server reads a versioned platform catalogue ({@link IEObjectProvider}): standard
 * command groups, pictures, colors, fonts. The value a caller stores is the catalogue's own
 * UNRESOLVED proxy - what EDT's own generators store - never a factory-built object, which BM cannot
 * persist a reference to.
 *
 * <p>No exception escapes: an unreachable or throwing catalogue is reported as
 * {@link Status#UNAVAILABLE}, which a refusal must not present as "no such name".</p>
 */
public final class PlatformCatalogue
{
    /** The outcome of a {@link #find}. */
    public enum Status
    {
        /** The catalogue holds the name. */
        FOUND,
        /** The catalogue was searched and does not hold the name. */
        NOT_FOUND,
        /** There is no catalogue, or it failed: nothing is known about the name. */
        UNAVAILABLE
    }

    /** A lookup outcome; {@link #proxy} and {@link #name} are set only when {@link #status} is FOUND. */
    public static final class Lookup
    {
        /** The outcome. */
        public final Status status;

        /** The catalogue's unresolved proxy. */
        public final EObject proxy;

        /** The name exactly as the catalogue registers it. */
        public final String name;

        private Lookup(Status status, EObject proxy, String name)
        {
            this.status = status;
            this.proxy = proxy;
            this.name = name;
        }
    }

    private static final Lookup NOT_FOUND = new Lookup(Status.NOT_FOUND, null, null);

    private static final Lookup UNAVAILABLE = new Lookup(Status.UNAVAILABLE, null, null);

    private PlatformCatalogue()
    {
        // utility class
    }

    /**
     * The platform catalogue of {@code type} for a platform version.
     *
     * @param type the catalogued EClass, e.g. {@code McorePackage.Literals.COLOR}
     * @param version the project's platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform cannot supply one
     */
    public static IEObjectProvider providerFor(EClass type, Version version)
    {
        if (type == null || version == null)
        {
            return null;
        }
        try
        {
            return IEObjectProvider.Registry.INSTANCE.get(type, version);
        }
        catch (RuntimeException e)
        {
            // A missing catalogue is reported by the caller's refusal, never as an exception.
            return null;
        }
    }

    /**
     * Finds a name in a catalogue: the exact (case-sensitive) index first, then one case-insensitive
     * pass over the descriptions. Only an unresolved proxy of {@code type} counts as found.
     *
     * @param provider the catalogue, may be {@code null}
     * @param name the name as supplied
     * @param type the EClass the found proxy must be a subtype of
     * @return the lookup outcome, never {@code null}
     */
    public static Lookup find(IEObjectProvider provider, String name, EClass type)
    {
        if (provider == null)
        {
            return UNAVAILABLE;
        }
        if (name == null || name.isEmpty())
        {
            return NOT_FOUND;
        }
        try
        {
            EObject exact = provider.getProxy(name);
            if (isProxyOf(exact, type))
            {
                return new Lookup(Status.FOUND, exact, name);
            }
            for (IEObjectDescription description : descriptions(provider))
            {
                String registered = nameOf(description);
                if (name.equalsIgnoreCase(registered))
                {
                    EObject proxy = description.getEObjectOrProxy();
                    if (isProxyOf(proxy, type))
                    {
                        return new Lookup(Status.FOUND, proxy, registered);
                    }
                }
            }
            return NOT_FOUND;
        }
        catch (RuntimeException e)
        {
            return UNAVAILABLE;
        }
    }

    /**
     * The registered names a predicate admits, distinct, in the catalogue's order.
     *
     * @param provider the catalogue, may be {@code null}
     * @param filter which names to keep
     * @return the names, or {@code null} when the catalogue is unavailable
     */
    public static List<String> names(IEObjectProvider provider, Predicate<String> filter)
    {
        if (provider == null)
        {
            return null; // NOSONAR null = unavailable, distinct from an empty catalogue
        }
        Set<String> names = new LinkedHashSet<>();
        try
        {
            for (IEObjectDescription description : descriptions(provider))
            {
                String name = nameOf(description);
                if (name != null && !name.isEmpty() && filter.test(name))
                {
                    names.add(name);
                }
            }
        }
        catch (RuntimeException e)
        {
            return null; // NOSONAR null = unavailable, distinct from an empty catalogue
        }
        return new ArrayList<>(names);
    }

    /**
     * Every description of a catalogue, never {@code null}. May throw, as the catalogue may.
     *
     * @param provider the catalogue
     * @return the descriptions
     */
    public static Iterable<IEObjectDescription> descriptions(IEObjectProvider provider)
    {
        Iterable<IEObjectDescription> all = provider.getEObjectDescriptions(null);
        return all == null ? Collections.emptyList() : all;
    }

    /**
     * The registered name of a description, e.g. {@code Web.AliceBlue}.
     *
     * @param description the description, may be {@code null}
     * @return the name, or {@code null}
     */
    public static String nameOf(IEObjectDescription description)
    {
        return description == null || description.getName() == null ? null
            : description.getName().toString();
    }

    private static boolean isProxyOf(EObject value, EClass type)
    {
        return value != null && value.eIsProxy() && value.eClass() != null
            && type.isSuperTypeOf(value.eClass());
    }
}
