/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.md.api.ChangeTypeDescriptionTask;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/**
 * A metadata type change made the way EDT's designer makes it: the properties that depend on the type
 * are reset by EDT's own {@link ChangeTypeDescriptionTask}, run inside the caller's write transaction
 * (issue #700).
 *
 * <p>The task is not re-implemented here on purpose. One of its rules - whether the new type can still
 * use the choice parameters - asks EDT's type-provider registry, which a copy could only approximate;
 * the class is in an exported package of the md bundle, and its rules are the same in md 27.1.0
 * (EDT 2026.1) and md 28.0.0 (EDT 2026.2). What it does, read from that bytecode:</p>
 * <ul>
 * <li>a {@link Constant} or a {@link BasicFeature} (an attribute, dimension, resource, tabular-section
 * attribute, accounting flag, common attribute, ...): the choice form is cleared and quick choice
 * becomes {@code Auto}; the choice parameters and parameter links are cleared unless the type
 * providers say a type in the new type can use them - they answer that for a BasicFeature only (a
 * reference can), so a Constant always loses them; the fill value becomes {@code Undefined}; min / max
 * become {@code Undefined} unless the new type includes {@code Number}; a lone {@code UUID} also stops
 * filling from the filling value and full-text search; a lone {@code ValueStorage}, or a lone
 * {@code String} whose string qualifiers say length 0 and not fixed, stops indexing - EDT's designer
 * always writes string qualifiers, so a String without them is not unlimited to this rule. Each
 * property is looked up BY NAME on the owner's class, so one the class lacks is skipped;</li>
 * <li>a {@link DefinedType}: the fill value of every metadata object whose type holds the defined type
 * (found through the BM references of the type set it produces) becomes {@code Undefined}, where EDT's
 * editing support allows the edit;</li>
 * <li>any other owner: only the type is set.</li>
 * </ul>
 * The task ends by setting the new type itself, so it replaces the plain set rather than following it.
 *
 * <p>The designer runs the task only for a real change: its type editor commits nothing when the edited
 * type equals the stored one. Writing the type an object already has is therefore a plain set here, with
 * {@link MetadataTypeBuilder#describesSameType} - the platform's own comparison - deciding "the same".</p>
 *
 * <p>Nor does the designer run it on an object an extension ADOPTED from its base configuration: the
 * property page of an adopted object edits the type through the extension's type extension (the
 * md.ui.extension type-extension model, in 2026.1 and 2026.2 alike), so the base-configuration resets
 * never apply to it. A type write to an adopted object stays the plain set it was before #700; what
 * that native write should do at all is an open question of its own (issues #709 / #710).</p>
 */
public final class TypeChangeDefaults
{
    /** What a type write did beyond setting the type. */
    public enum Reset
    {
        /**
         * Only the type was set: the type is unchanged, EDT's type change resets nothing on this owner,
         * or the owner is adopted.
         */
        NONE,
        /** EDT's resets ran on the owner's own type-dependent properties (a Constant or a BasicFeature). */
        OWNER,
        /** EDT's resets ran on the fill values of the objects typed with the owner (a DefinedType). */
        REFERRERS
    }

    private TypeChangeDefaults()
    {
        // Utility class
    }

    /**
     * Which of EDT's type-change resets a retype of {@code owner} gets - the {@code instanceof} chain of
     * {@link ChangeTypeDescriptionTask#execute}, for an owner EDT's designer retypes through that task (an
     * adopted object it never does). The task applies that chain itself, so this answer cannot widen
     * the resets: it decides whether the task runs at all and what the call reports.
     *
     * @param owner the object whose type is written
     * @return {@link Reset#OWNER}, {@link Reset#REFERRERS} or {@link Reset#NONE}
     */
    public static Reset resetFor(EObject owner)
    {
        if (owner instanceof MdObject && ((MdObject)owner).getObjectBelonging() == ObjectBelonging.ADOPTED)
        {
            return Reset.NONE;
        }
        if (owner instanceof Constant || owner instanceof BasicFeature)
        {
            return Reset.OWNER;
        }
        return owner instanceof DefinedType ? Reset.REFERRERS : Reset.NONE;
    }

    /**
     * Writes {@code newType} to {@code owner}'s {@code feature} as EDT's designer does. When EDT's type
     * change resets something on this owner and the type really changes, EDT's task runs in {@code tx}:
     * it resets the type-dependent properties and then sets the type. Otherwise the type is set as is.
     *
     * @param owner the object whose type is written, inside {@code tx}
     * @param feature its type-description feature
     * @param newType the detached type description to write
     * @param tx the write transaction the change runs in
     * @return what was reset besides the type
     * @throws IllegalStateException when the resets are due but EDT's editing-support service is not
     *             available; thrown inside the write boundary, it rolls the whole change back, as does
     *             anything the task throws (its status is {@code OK} by construction: the task returns
     *             {@code Status.OK_STATUS} unconditionally in both versions)
     */
    public static Reset setType(EObject owner, EStructuralFeature feature, TypeDescription newType,
        IBmTransaction tx)
    {
        Reset reset = resetFor(owner);
        if (reset == Reset.NONE || MetadataTypeBuilder.describesSameType(owner.eGet(feature), newType))
        {
            owner.eSet(feature, newType);
            return Reset.NONE;
        }
        IModelEditingSupport editingSupport = VendorSupportGuard.service();
        if (editingSupport == null)
        {
            throw new IllegalStateException("Cannot change the type: EDT's editing-support service " //$NON-NLS-1$
                + "(IModelEditingSupport) is not available, so the designer's type-change resets cannot " //$NON-NLS-1$
                + "run. Nothing was changed. Retry once list_projects reports the project ready."); //$NON-NLS-1$
        }
        // Through the task's transaction entry, as EDT's model API runs it: the task keeps the
        // transaction it is handed, and a DefinedType's referrers are fetched from it.
        new ChangeTypeDescriptionTask(owner, newType, feature, editingSupport).execute(tx,
            new NullProgressMonitor());
        return reset;
    }
}
