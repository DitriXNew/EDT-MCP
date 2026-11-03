/**
 * Copyright (c) 2026 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.function.BiFunction;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithCategories;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService;
import com.google.gson.JsonElement;

/** Checks form value types against the same provider and predicate as EDT's type editor. */
public final class FormValueTypePolicy
{
    public static final String INVALID_TYPE = "invalid-form-value-type"; //$NON-NLS-1$
    public static final String UNAVAILABLE = "unavailable-form-value-types"; //$NON-NLS-1$

    static BiFunction<EObject, EReference, TypeDescriptionInfoWithCategories> typeInfoSource =
        (owner, feature) -> TypeProviderService.INSTANCE.getTypeDescriptionInfo(owner, feature);

    private FormValueTypePolicy()
    {
    }

    public record Problem(String code, String message)
    {
    }

    /** Reads only; the member and its type must be accessed within a BM transaction. */
    public static Problem check(EObject member, TypeDescription valueType)
    {
        if (!(member instanceof FormParameter || member instanceof AbstractFormAttribute)
            || valueType == null || valueType.getTypes().isEmpty())
        {
            return null;
        }
        String role = member instanceof FormParameter ? "parameter" : "attribute"; //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            EReference feature = (EReference)member.eClass().getEStructuralFeature("valueType"); //$NON-NLS-1$
            TypeDescriptionInfoWithCategories info = typeInfoSource.apply(member, feature);
            if (info == null)
            {
                return unavailable(role);
            }
            for (TypeItem type : valueType.getTypes())
            {
                String name = McoreUtil.getTypeName(type);
                if (name == null || name.isBlank())
                {
                    return unavailable(role);
                }
                if (!info.contains(type))
                {
                    return new Problem(INVALID_TYPE, "Type '" + name + "' is not offered by EDT's form " //$NON-NLS-1$ //$NON-NLS-2$
                        + role + " type picker. Remove it from valueType. " + arbitraryAdvice(role)); //$NON-NLS-1$
                }
            }
            return null;
        }
        catch (RuntimeException e)
        {
            return unavailable(role);
        }
    }

    private static Problem unavailable(String role)
    {
        return new Problem(UNAVAILABLE, "Cannot check valueType against EDT's form " + role //$NON-NLS-1$
            + " type picker. Wait for project indexing to finish and retry; restart EDT if it persists."); //$NON-NLS-1$
    }

    private static String arbitraryAdvice(String role)
    {
        return "For an array or structure, declare the " + role //$NON-NLS-1$
            + " without a type (arbitrary, <valueType/>)."; //$NON-NLS-1$
    }

    /** Creation does not accept valueType; name the requested value and the supported next step. */
    public static String creationRefusal(JsonElement requested, boolean parameter)
    {
        String role = parameter ? "parameter" : "attribute"; //$NON-NLS-1$ //$NON-NLS-2$
        return "Cannot set valueType " + requested + " when creating a form " + role //$NON-NLS-1$ //$NON-NLS-2$
            + ". Create it without a type; use modify_metadata to select a type offered by EDT's form " //$NON-NLS-1$
            + role + " type picker. " + arbitraryAdvice(role); //$NON-NLS-1$
    }
}
