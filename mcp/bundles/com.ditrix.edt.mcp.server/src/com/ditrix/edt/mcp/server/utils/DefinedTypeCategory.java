/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.function.Supplier;

import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ICategoryTypeInfoProvider;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ITypeInfoCategory;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ITypeInfoCategoryServiceProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

/**
 * EDT's own answer to "does the DefinedType type editor offer this type?" - the vocabulary of a
 * DefinedType's {@code type} (issue #727).
 *
 * <p>EDT builds that editor from a platform type-info category: for a DefinedType's {@code type},
 * {@code MdTypeProvider} hands it the category {@value #CATEGORY_NAME}, whose one provider
 * ({@code UserDefinedCategoryTypeInfoProvider}) is contributed by the md.ui bundle - the same on
 * 2026.1 (md 27.1.0) and 2026.2 (md 28.0.0). This class asks THAT category, so the vocabulary is
 * EDT's rather than a list kept here that would drift from it.</p>
 *
 * <p>It fails closed, and keeps "cannot answer" apart from "no". The provider reads an item by its
 * type name and answers "no" when it cannot read one, and a category with no provider answers "no"
 * to every item - either would refuse a legal type and blame the caller for the platform. So every
 * way of not answering - no version, no service, no such category, no provider, an unreadable name,
 * a throw - is {@link Unavailable}, and only an item the category read and turned down is
 * {@linkplain Verdict#offered() not offered}.</p>
 */
final class DefinedTypeCategory
{
    /** The category EDT's DefinedType type editor is built from ({@code MdTypeProvider.definedTypeTypes}). */
    static final String CATEGORY_NAME = "UserDefinedTypeInfoCategory"; //$NON-NLS-1$

    /** Where EDT's category service comes from: the OSGi registry in production, a fake in a test. */
    static Supplier<ITypeInfoCategoryServiceProvider> serviceSource =
        () -> ServiceAccess.get(ITypeInfoCategoryServiceProvider.class);

    private static final String NO_SERVICE = "EDT's type-category service is not available"; //$NON-NLS-1$

    private static final String NO_CATEGORY = "EDT does not register the category"; //$NON-NLS-1$

    private static final String UNREADABLE_NAME = "the name of the type built for it could not be read"; //$NON-NLS-1$

    private final ITypeInfoCategory category;

    private final Version version;

    private DefinedTypeCategory(ITypeInfoCategory category, Version version)
    {
        this.category = category;
        this.version = version;
    }

    /**
     * Opens EDT's DefinedType category for one build.
     *
     * @param version the project's platform version, which every question is asked for
     * @return the category, ready to judge type items
     * @throws Unavailable when the category cannot be consulted; the message says why
     */
    static DefinedTypeCategory open(Version version) throws Unavailable
    {
        if (version == null)
        {
            throw new Unavailable("the project's platform version is not known", null); //$NON-NLS-1$
        }
        ITypeInfoCategoryServiceProvider service;
        try
        {
            service = serviceSource.get();
        }
        catch (RuntimeException e) // ServiceAccess THROWS when the service is not registered
        {
            throw new Unavailable(NO_SERVICE, e);
        }
        if (service == null)
        {
            throw new Unavailable(NO_SERVICE, null);
        }
        ITypeInfoCategory found;
        try
        {
            // EDT's service THROWS IllegalArgumentException for a name it does not know.
            found = service.getCategoryByName(CATEGORY_NAME);
        }
        catch (RuntimeException e)
        {
            throw new Unavailable(NO_CATEGORY, e);
        }
        if (found == null)
        {
            throw new Unavailable(NO_CATEGORY, null);
        }
        List<ICategoryTypeInfoProvider> providers;
        try
        {
            providers = found.getProviders();
        }
        catch (RuntimeException e)
        {
            throw new Unavailable("the category's providers could not be read", e); //$NON-NLS-1$
        }
        if (providers == null || providers.isEmpty())
        {
            // The provider IS the vocabulary: without one the category answers "no" to every item.
            throw new Unavailable(
                "the category has no provider (EDT's metadata editor bundle contributes it)", null); //$NON-NLS-1$
        }
        return new DefinedTypeCategory(found, version);
    }

    /**
     * Asks the category whether EDT's DefinedType type editor offers {@code item}, for the project's
     * platform version.
     *
     * @param item one built type item
     * @return the verdict, with the type name the item was judged by
     * @throws Unavailable when the item's name cannot be read or the category fails to answer
     */
    Verdict judge(TypeItem item) throws Unavailable
    {
        String typeName = typeNameOf(item);
        try
        {
            return new Verdict(typeName, category.hasTypeItemInCategory(item, version));
        }
        catch (RuntimeException e)
        {
            throw new Unavailable("the category failed while answering", e); //$NON-NLS-1$
        }
    }

    /**
     * The name EDT's provider judges {@code item} by ({@code McoreUtil.getTypeName}, which reads a
     * platform-type proxy through EDT's type-name resolver).
     */
    private static String typeNameOf(TypeItem item) throws Unavailable
    {
        String name;
        try
        {
            name = McoreUtil.getTypeName(item);
        }
        catch (RuntimeException e)
        {
            throw new Unavailable(UNREADABLE_NAME, e);
        }
        if (name == null || name.isEmpty())
        {
            throw new Unavailable(UNREADABLE_NAME, null);
        }
        return name;
    }

    /**
     * The category's answer for one type item.
     *
     * @param typeName the type name the category judged the item by
     * @param offered whether EDT's DefinedType type editor offers the item
     */
    record Verdict(String typeName, boolean offered)
    {
    }

    /**
     * EDT's category could not be consulted. The message is the reason, worded to follow
     * "unavailable:"; the cause, when there is one, is the platform's own failure, kept for the log.
     */
    static final class Unavailable extends Exception
    {
        private static final long serialVersionUID = 1L;

        Unavailable(String reason, Throwable cause)
        {
            super(reason, cause);
        }
    }
}
