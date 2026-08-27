/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonElement;

/**
 * Resolves the picture target used by picture-valued metadata and form features. Standard pictures
 * are resolved to platform proxies through the versioned {@link IEObjectProvider}, using the full
 * bilingual symbolic key registered by the platform loader; configuration pictures are resolved
 * through the shared bilingual {@link MetadataNodeResolver}.
 *
 * <p>The builder never mutates the project model and deliberately does not create the containing
 * {@code PictureRef}. Its {@link Result#picture} is either a safe platform proxy or the resolved live
 * CommonPicture; the caller converts the latter to a BM id and creates the PictureRef inside its write
 * transaction. No exception escapes this helper: invalid input and unavailable platform values are
 * returned as actionable {@link Result#error} messages.</p>
 */
public final class PictureValueBuilder
{
    /** Symbolic-name prefix used by EDT for a platform standard picture. */
    public static final String STANDARD_PREFIX = "StdPicture."; //$NON-NLS-1$

    /** Symbolic-name prefix used by EDT for a platform extended-standard picture. */
    public static final String EXTENDED_PREFIX = "StdExtPicture."; //$NON-NLS-1$

    /** Canonical FQN prefix of a configuration CommonPicture. */
    public static final String COMMON_PREFIX = "CommonPicture."; //$NON-NLS-1$

    /** A resolved picture target, or an actionable error. Exactly one field is non-null. */
    public static final class Result
    {
        /** A platform-picture proxy or the resolved live CommonPicture. */
        public final EObject picture;

        /** The actionable validation/resolution error, or {@code null} on success. */
        public final String error;

        private Result(EObject picture, String error)
        {
            this.picture = picture;
            this.error = error;
        }

        static Result ok(EObject picture)
        {
            return new Result(picture, null);
        }

        static Result error(String error)
        {
            return new Result(null, error);
        }
    }

    private PictureValueBuilder()
    {
        // utility class
    }

    /**
     * Builds a picture value for the supplied project platform version.
     *
     * @param raw the wire value ({@code StdPicture.<Name>}, {@code StdExtPicture.<Name>} or
     * {@code CommonPicture.<Name>})
     * @param scope the metadata resolution root for a CommonPicture
     * @param version the project's platform version
     * @return the resolved picture target or an actionable error
     */
    public static Result build(JsonElement raw, MetadataScope scope, Version version)
    {
        String value = strictString(raw);
        if (value == null)
        {
            return invalidForm(raw);
        }
        if (isPlatformPictureName(value))
        {
            IEObjectProvider provider = null;
            if (version != null)
            {
                try
                {
                    provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.PICTURE, version);
                }
                catch (RuntimeException e)
                {
                    // The common error below carries the original value and the recovery path.
                    provider = null;
                }
            }
            return buildWithProvider(raw, scope, provider);
        }
        return buildWithProvider(raw, scope, (IEObjectProvider)null);
    }

    /**
     * Provider-injected variant used by the unit tests and by the public version-aware entry point.
     * Package-visible so tests can exercise the provider's throw-on-unknown contract headlessly.
     */
    static Result buildWithProvider(JsonElement raw, MetadataScope scope, IEObjectProvider pictureProvider)
    {
        String value = strictString(raw);
        if (value == null)
        {
            return invalidForm(raw);
        }
        try
        {
            if (isPlatformPictureName(value))
            {
                String prefix = value.startsWith(EXTENDED_PREFIX) ? EXTENDED_PREFIX : STANDARD_PREFIX;
                if (value.length() == prefix.length() || pictureProvider == null)
                {
                    return unresolvedStandard(value);
                }
                // StdPicturesLoader registers provider entries by the FULL bilingual symbolic key,
                // including StdPicture./StdExtPicture.; a plain picture name never resolves.
                EObject platformPicture = pictureProvider.createProxy(value);
                if (!isPicture(platformPicture))
                {
                    return unresolvedStandard(value);
                }
                return Result.ok(platformPicture);
            }

            String normalized = MetadataTypeUtils.normalizeFqn(value);
            if (normalized != null && normalized.startsWith(COMMON_PREFIX))
            {
                MetadataNodeResolver.MetadataNode node =
                    MetadataNodeResolver.resolveExisting(scope, normalized);
                if (node == null || !(node.object instanceof CommonPicture)
                    || !isPicture(node.object))
                {
                    return Result.error("Picture value '" + value + "' was not found. Use " //$NON-NLS-1$ //$NON-NLS-2$
                        + "list_common_pictures to choose a valid 'CommonPicture.<Name>' value."); //$NON-NLS-1$
                }
                return Result.ok(node.object);
            }
            return invalidForm(raw);
        }
        catch (RuntimeException e)
        {
            return isPlatformPictureName(value) ? unresolvedStandard(value)
                : Result.error("Picture value '" + value + "' could not be built. Use " //$NON-NLS-1$ //$NON-NLS-2$
                    + "list_common_pictures to choose a valid 'CommonPicture.<Name>' value, or use " //$NON-NLS-1$
                    + "the exact 'StdPicture.<Name>' or 'StdExtPicture.<Name>' spelling for a " //$NON-NLS-1$
                    + "platform picture."); //$NON-NLS-1$
        }
    }

    private static boolean isPlatformPictureName(String value)
    {
        return value != null && (value.startsWith(STANDARD_PREFIX)
            || value.startsWith(EXTENDED_PREFIX));
    }

    private static boolean isPicture(EObject value)
    {
        return value != null && value.eClass() != null
            && McorePackage.Literals.PICTURE.isSuperTypeOf(value.eClass());
    }

    private static String strictString(JsonElement raw)
    {
        return raw != null && !raw.isJsonNull() && raw.isJsonPrimitive()
            && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null;
    }

    private static Result unresolvedStandard(String value)
    {
        return Result.error(
            "Picture value '" + value + "' could not be resolved for this platform " //$NON-NLS-1$ //$NON-NLS-2$
            + "version. Use list_common_pictures for configuration pictures, or use the exact " //$NON-NLS-1$
            + "'StdPicture.<Name>' or 'StdExtPicture.<Name>' spelling for a platform picture."); //$NON-NLS-1$
    }

    private static Result invalidForm(JsonElement raw)
    {
        String value = raw == null || raw.isJsonNull() ? "null" : raw.toString(); //$NON-NLS-1$
        return Result.error(
            "Picture value " + value + " is invalid. Use 'StdPicture.<Name>' or " //$NON-NLS-1$ //$NON-NLS-2$
            + "'StdExtPicture.<Name>' for a platform picture, or 'CommonPicture.<Name>' (the type " //$NON-NLS-1$
            + "token may also be Russian) for " //$NON-NLS-1$
            + "a configuration picture; use list_common_pictures to discover configuration pictures."); //$NON-NLS-1$
    }
}
