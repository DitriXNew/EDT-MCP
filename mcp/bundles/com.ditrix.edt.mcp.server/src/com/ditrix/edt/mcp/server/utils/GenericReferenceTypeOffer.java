/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithCategories;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService;

/**
 * EDT's own answer to "does the type editor of this stored feature offer this generic reference type?"
 * (issue #966). A generic reference ({@code DocumentRef}, {@code CatalogRef}, {@code AnyRef}, ...) is a
 * platform {@code TypeSet}; EDT offers it on an attribute or a register dimension but not, for example, on
 * an accumulation register resource. The vocabulary of each feature is EDT's: {@code MdTypeProvider}
 * builds the type editor from the owner's class and feature, and {@link TypeProviderService} hands that
 * description out - the same source {@link FormValueTypePolicy} asks for a form member. Only the generic
 * type sets a spec built are judged here; every other built type keeps the rules it had.
 *
 * <p>It fails closed: no description, an unreadable type name or a throw is the platform's failure, never
 * a pass.</p>
 */
final class GenericReferenceTypeOffer
{
    /** Where EDT's type editor description comes from: the platform in production, a fake in a test. */
    static BiFunction<EObject, EReference, TypeDescriptionInfoWithCategories> typeInfoSource =
        (owner, feature) -> TypeProviderService.INSTANCE.getTypeDescriptionInfo(owner, feature);

    private GenericReferenceTypeOffer()
    {
    }

    /**
     * The verdict on one built type.
     *
     * @param message the refusal
     * @param platformFailure {@code true} when EDT could not answer, {@code false} when it answered no
     */
    record Problem(String message, boolean platformFailure)
    {
    }

    /**
     * Asks EDT's type editor of {@code feature} on {@code owner} whether it offers every generic reference
     * type set in {@code built}.
     *
     * @param owner the object whose feature receives the type
     * @param feature the type feature
     * @param built the type description about to be written
     * @return {@code null} when every generic reference is offered (or there is none), else the refusal
     */
    static Problem check(EObject owner, EStructuralFeature feature, TypeDescription built)
    {
        if (owner == null || built == null)
        {
            return null;
        }
        Map<TypeItem, String> generic = new LinkedHashMap<>();
        for (TypeItem item : built.getTypes())
        {
            String name = genericName(item);
            if (name != null)
            {
                generic.put(item, name);
            }
        }
        if (generic.isEmpty())
        {
            // Nothing this check owns: the service is not even asked, so no other type gains a refusal.
            return null;
        }
        TypeDescriptionInfoWithCategories info;
        try
        {
            info = feature instanceof EReference reference ? typeInfoSource.apply(owner, reference) : null;
        }
        catch (RuntimeException e)
        {
            return unavailable(owner);
        }
        if (info == null)
        {
            return unavailable(owner);
        }
        for (Map.Entry<TypeItem, String> entry : generic.entrySet())
        {
            boolean offered;
            try
            {
                offered = info.contains(entry.getKey());
            }
            catch (RuntimeException e)
            {
                return unavailable(owner);
            }
            if (!offered)
            {
                String name = entry.getValue();
                return new Problem("Type '" + name + "' (the generic reference: any object of that kind) is " //$NON-NLS-1$ //$NON-NLS-2$
                    + "not offered by EDT's type editor for " + label(owner) + ". Use a reference to one " //$NON-NLS-1$ //$NON-NLS-2$
                    + "object ({kind:'" + name + "', ref:'Name'}) or list the objects as a composite type.", //$NON-NLS-1$ //$NON-NLS-2$
                    false);
            }
        }
        return null;
    }

    /**
     * The name of a generic platform type set ({@code DocumentRef}, {@code AnyRef}, ...), or {@code null}
     * for anything else: a plain type, and a type set that names ONE object ({@code DefinedType.Money},
     * {@code Characteristic.Kinds}), which keeps its own rules.
     */
    private static String genericName(TypeItem item)
    {
        if (item == null || item.eClass() == null || !McorePackage.Literals.TYPE_SET.isSuperTypeOf(item.eClass()))
        {
            return null;
        }
        try
        {
            String name = McoreUtil.getTypeName(item);
            return name == null || name.isBlank() || name.indexOf('.') >= 0 ? null : name;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Problem unavailable(EObject owner)
    {
        return new Problem("Cannot check the generic reference type against EDT's type editor for " //$NON-NLS-1$
            + label(owner) + ". Nothing was written; wait for project indexing to finish and retry, and " //$NON-NLS-1$
            + "restart EDT if it persists.", true); //$NON-NLS-1$
    }

    private static String label(EObject owner)
    {
        String kind = owner.eClass() != null ? owner.eClass().getName() : "the object"; //$NON-NLS-1$
        return owner instanceof MdObject object && object.getName() != null
            ? kind + " '" + object.getName() + "'" : kind; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
