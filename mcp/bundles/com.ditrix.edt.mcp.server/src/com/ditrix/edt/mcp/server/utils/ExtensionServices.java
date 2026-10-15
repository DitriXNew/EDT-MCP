/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.function.Supplier;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.md.extension.IMdPropertyTypeProvider;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.wiring.ServiceAccess;

/**
 * EDT's configuration-extension services, and the one question every caller asks first: is this object
 * an extension's ADOPTED copy of a base-configuration object?
 *
 * <p>The services are the OSGi services EDT's md.extension bundle registers (MdExtensionPlugin, the
 * same on EDT 2026.1 and 2026.2): {@link IMdAdoptedPropertyAccess} (the writer of an adopted property
 * and of its extension state), {@link IMdPropertyTypeProvider} (which extension states a property
 * offers for a platform version) and {@link IModelObjectAdopter} (the adopter, which also finds the
 * base object of an adopted copy). {@link ServiceAccess#get} throws while a service is not registered;
 * every accessor answers {@code null} then, so a caller refuses - nothing here turns a missing service
 * into a guess.</p>
 */
public final class ExtensionServices
{
    private ExtensionServices()
    {
        // Utility class
    }

    /**
     * Whether {@code object} is a metadata object an extension adopted from its base configuration.
     *
     * @param object any object, may be {@code null}
     * @return {@code true} for an {@link MdObject} whose belonging is {@link ObjectBelonging#ADOPTED}
     */
    public static boolean isAdopted(EObject object)
    {
        return object instanceof MdObject && ((MdObject)object).getObjectBelonging() == ObjectBelonging.ADOPTED;
    }

    /** @return EDT's adopted-property access, or {@code null} when it is not available */
    public static IMdAdoptedPropertyAccess adoptedPropertyAccess()
    {
        return lookup(() -> ServiceAccess.get(IMdAdoptedPropertyAccess.class));
    }

    /** @return EDT's property-type provider of extensions, or {@code null} when it is not available */
    public static IMdPropertyTypeProvider propertyTypeProvider()
    {
        return lookup(() -> ServiceAccess.get(IMdPropertyTypeProvider.class));
    }

    /** @return EDT's model-object adopter, or {@code null} when it is not available */
    public static IModelObjectAdopter modelObjectAdopter()
    {
        return lookup(() -> ServiceAccess.get(IModelObjectAdopter.class));
    }

    private static <T> T lookup(Supplier<T> service)
    {
        try
        {
            return service.get();
        }
        catch (RuntimeException e) // NOSONAR a missing service is an answer (the caller refuses), not a crash
        {
            return null;
        }
    }
}
