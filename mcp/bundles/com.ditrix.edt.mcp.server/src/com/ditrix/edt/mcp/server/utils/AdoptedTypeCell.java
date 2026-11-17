/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * The details cell of a type-valued property (a type, an event subscription's source, a command's
 * parameter type) of an object a configuration extension ADOPTED, told the way the extension actually
 * stores it (issue #955).
 *
 * <p>For an adopted object, the native value of such a property in the extension's model is not, by itself,
 * what the extension says about it:</p>
 * <ul>
 * <li>from runtime 8.3.18 a typed object's adopter leaves the native type EMPTY and keeps the composition in
 * the extension block's type extension ({@code typeExtension} / {@code commandParameterTypeExtension}), each
 * type with its own state - so that list is what the cell shows;</li>
 * <li>a property whose same-named state in the block is unset or None is not stored by the extension at all
 * (EDT's {@code .mdo} writer drops it, {@link AdoptedPropertyRouter#wouldDrop}): the base configuration's
 * value applies, and whatever the model holds there (empty after an adoption) is not shown as if it did;</li>
 * <li>any other state (Extended, Checked, Notify) is named next to the value, which EDT then writes.</li>
 * </ul>
 * <p>An object the extension owns, and every object of a base configuration, keep the plain value.</p>
 */
public final class AdoptedTypeCell
{
    /** The cell of a property the extension does not store. */
    static final String NOT_STORED = "- (not stored in the extension: state None, the base configuration's " //$NON-NLS-1$
        + "value applies)"; //$NON-NLS-1$

    /** What the states of a type extension mean, appended once to its list. */
    static final String TYPE_EXTENSION_NOTE = " (the extension's type extension: the base configuration's " //$NON-NLS-1$
        + "types apply, an Extended type is added by the extension)"; //$NON-NLS-1$

    private AdoptedTypeCell()
    {
        // Utility class
    }

    /**
     * The cell of {@code feature} of {@code owner}.
     *
     * @param owner the object, may be {@code null}
     * @param feature the type-valued feature, may be {@code null}
     * @param nativeText the rendered native value of the feature
     * @return {@code nativeText} unless {@code owner} is an adopted object whose extension block says more
     */
    public static String render(EObject owner, EStructuralFeature feature, String nativeText)
    {
        return render(owner, feature, nativeText, null);
    }

    /** Renders the stored type and, for adopted basic features, its comparison with the base. */
    public static String render(EObject owner, EStructuralFeature feature, String nativeText, IProject project)
    {
        if (!(owner instanceof MdObject) || feature == null
            || ((MdObject)owner).getObjectBelonging() != ObjectBelonging.ADOPTED)
        {
            return nativeText;
        }
        ObjectExtension block = ((MdObject)owner).getExtension();
        if (project != null && feature == AdoptedPropertyRouter.BASIC_FEATURE_TYPE)
        {
            ControlledMetadataProperties.Row compared = ControlledMetadataProperties.compareType(project, (MdObject)owner);
            return compared.controlled() + " · Base: " + compared.base() + " [" + compared.status() + "]"
                + (compared.refreshable() ? " · modify_metadata type value: fromBase" : "");
        }
        if (block == null)
        {
            return nativeText;
        }
        String typeExtension = typeExtensionText(block, feature);
        if (typeExtension != null)
        {
            return typeExtension;
        }
        if (AdoptedPropertyRouter.wouldDrop(owner, feature))
        {
            return NOT_STORED;
        }
        MdPropertyState state = AdoptedPropertyRouter.stateOf(owner, feature);
        return state == null ? nativeText : nativeText + " (extension state " + state.getLiteral() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The block's type extension of {@code feature} (the feature named {@code <feature>Extension}) as
     * {@code Type (State), ...}, or {@code null} when the block has none or it lists no type.
     */
    static TypeDescriptionExtension typeExtension(ObjectExtension block, EStructuralFeature feature)
    {
        if (block == null)
        {
            return null;
        }
        EStructuralFeature extensionFeature = block.eClass().getEStructuralFeature(feature.getName() + "Extension"); //$NON-NLS-1$
        if (extensionFeature == null || extensionFeature.isMany())
        {
            return null;
        }
        return block.eGet(extensionFeature) instanceof TypeDescriptionExtension value ? value : null;
    }

    static String typeExtensionText(ObjectExtension block, EStructuralFeature feature)
    {
        TypeDescriptionExtension value = typeExtension(block, feature);
        if (value == null || value.getTypes().isEmpty())
        {
            return null;
        }
        List<String> items = new ArrayList<>();
        AdoptedPropertyRouter.Qualifiers qualifiers = AdoptedPropertyRouter.Qualifiers.of(value);
        for (TypeExtension type : value.getTypes())
        {
            String name = AdoptedPropertyRouter.typeName(type.getType());
            MdPropertyState state = type.getState() == null ? MdPropertyState.NONE : type.getState();
            items.add((name == null ? "[unreadable type]"
                : MetadataPropertyIntrospector.formatQualifiedType(name, qualifiers)) + " (" + state.getLiteral() + ")");
        }
        return String.join(", ", items) + TYPE_EXTENSION_NOTE; //$NON-NLS-1$
    }
}
