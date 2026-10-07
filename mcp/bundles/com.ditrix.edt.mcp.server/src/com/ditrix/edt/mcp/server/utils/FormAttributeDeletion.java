/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com._1c.g5.v8.dt.form.service.item.FormItemVisitor;
import com._1c.g5.v8.dt.form.service.item.IFormItemCommand;
import com._1c.g5.v8.dt.form.util.DatapathUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * EDT's own delete of a form attribute or attribute column - the one the form designer runs
 * ({@code FormAttributeManagementService.deleteAttribute} with {@code removeItems=true}) - and the
 * read-only prediction of what it removes, made with the platform's own collector so the preview and
 * the delete cannot disagree. Obtained from {@code EdtServices.getFormAttributeDeletion()}.
 */
public final class FormAttributeDeletion
{
    /** What EDT's delete of one attribute will do to the form's items, read without mutating. */
    public static final class Plan
    {
        /** The data items EDT removes, in visit order: its collector's result set. */
        public final List<EObject> bound = new ArrayList<>();

        /** Items that stay but lose their data path (it starts with the attribute's, yet is not removed). */
        public final List<EObject> cleared = new ArrayList<>();

        /** EDT keeps the bound items: an extension form's attribute adopted from the base form. */
        public boolean itemsKept;

        /** The attribute's data path does not resolve, so EDT removes and clears no item. */
        public boolean unresolved;
    }

    private final FormAttributeManagementService service;
    private final IDataSourceInfoAssociationService associations;
    private final IFormExtensionService extensions;
    private final Constructor<?> collector;

    /**
     * @param service EDT's form-attribute service
     * @param associations the form data-source association service (path resolution)
     * @param extensions the form extension service
     * @param collector the constructor of EDT's {@code DeleteDataItemByPathDathPrefixCommand}
     */
    public FormAttributeDeletion(FormAttributeManagementService service,
        IDataSourceInfoAssociationService associations, IFormExtensionService extensions,
        Constructor<?> collector)
    {
        this.service = service;
        this.associations = associations;
        this.extensions = extensions;
        this.collector = collector;
    }

    /**
     * Runs EDT's delete inside the caller's write transaction.
     *
     * @param tx the open BM write transaction
     * @param attribute the tx-bound {@code AbstractFormAttribute}
     */
    public void delete(IBmTransaction tx, EObject attribute)
    {
        service.deleteAttribute(tx, (AbstractFormAttribute)attribute, true);
    }

    /**
     * Predicts the delete without mutating: takes the branch {@code deleteAttribute} takes, runs EDT's
     * own collector over the form for the removed items, and lists as cleared the remaining items whose
     * data path starts with the attribute's in either script variant - the rule of the platform's
     * cleaner ({@code FormItemDataPathCleanerCommand}, run in deleting mode). The cleaner's secondary
     * paths (footer, choice links, group titles) are not items and are not listed.
     *
     * @param formModel the tx-bound content {@code Form}
     * @param attributeObject the tx-bound attribute or column
     * @return the plan
     */
    public Plan plan(EObject formModel, EObject attributeObject)
    {
        Form form = (Form)formModel;
        AbstractFormAttribute attribute = (AbstractFormAttribute)attributeObject;
        Plan plan = new Plan();
        if (extensions.isExtensionAdopted(attribute) && !extensions.isPureExtensionObject(attribute, form))
        {
            plan.itemsKept = true;
            return plan;
        }
        PropertyInfo info = associations.findPropertyInfo(form, attribute);
        if (info == null)
        {
            plan.unresolved = true;
            return plan;
        }
        AbstractDataPath english = info.getDataPath(ScriptVariant.ENGLISH);
        AbstractDataPath russian = info.getDataPath(ScriptVariant.RUSSIAN);
        Set<DataItem> removed = new LinkedHashSet<>();
        new FormItemVisitor(newCollector(removed, english, form)).visit(form);
        plan.bound.addAll(removed);
        new FormItemVisitor(item ->
        {
            if (item instanceof DataItem && !insideAny(item, removed))
            {
                AbstractDataPath path = ((DataItem)item).getDataPath();
                if (DatapathUtil.startsWith(path, english) || DatapathUtil.startsWith(path, russian))
                {
                    plan.cleared.add(item);
                }
            }
        }).visit(form);
        return plan;
    }

    private IFormItemCommand newCollector(Set<DataItem> result, AbstractDataPath prefix, Form form)
    {
        try
        {
            return (IFormItemCommand)collector.newInstance(result, prefix, associations, form);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            throw new IllegalStateException("EDT's form-item collector could not be created", e); //$NON-NLS-1$
        }
    }

    /** Whether {@code item} is, or lies inside, one of {@code roots}. */
    private static boolean insideAny(EObject item, Set<? extends EObject> roots)
    {
        for (EObject root : roots)
        {
            if (root == item || EcoreUtil.isAncestor(root, item))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The dotted data path of a data item, or {@code ""} when it is not one or carries none.
     *
     * @param item any form element
     * @return the path
     */
    public static String pathOf(EObject item)
    {
        if (!(item instanceof DataItem) || ((DataItem)item).getDataPath() == null)
        {
            return ""; //$NON-NLS-1$
        }
        return String.join(".", ((DataItem)item).getDataPath().getSegments()); //$NON-NLS-1$
    }
}
