/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ICategoryTypeInfoProvider;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ITypeInfoCategory;
import com._1c.g5.v8.dt.platform.core.typeinfo.category.ITypeInfoCategoryServiceProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A DefinedType's own {@code type} takes what EDT's DefinedType type editor offers (issue #727). The
 * {@link MetadataTypeBuilder.TypeTarget#DEFINED_TYPE} target builds each item as a FORM attribute
 * would, then asks EDT's type category {@code UserDefinedTypeInfoCategory} - the one EDT builds that
 * editor from - whether the item belongs to it.
 *
 * <p>The category is faked through {@link DefinedTypeCategory#serviceSource}. It answers by the
 * type's NAME, the way EDT's own provider does, and records what it was asked and with which platform
 * version - so each test pins what EDT is asked as well as what the builder does with the answer. The
 * category name is a literal here: a lookup under any other name finds nothing.</p>
 */
public class MetadataTypeBuilderDefinedTypeTest
{
    /** The category EDT's DefinedType type editor is built from (md 27.1.0 and 28.0.0). */
    private static final String CATEGORY = "UserDefinedTypeInfoCategory"; //$NON-NLS-1$

    /** The project's platform version, as the tool passes it. */
    private static final Version PROJECT_VERSION = Version.V8_3_24;

    /** The closing advice of every refusal for a category that cannot answer. */
    private static final String UNAVAILABLE_ADVICE =
        ". Nothing was written; retry, and restart EDT if it persists."; //$NON-NLS-1$

    /** The names the fake category was asked about, in order. */
    private final List<String> asked = new ArrayList<>();

    /** The versions the fake category was asked with, in order. */
    private final List<Version> askedWith = new ArrayList<>();

    private Supplier<ITypeInfoCategoryServiceProvider> production;

    @Before
    public void keepTheProductionSource()
    {
        production = DefinedTypeCategory.serviceSource;
    }

    @After
    public void restoreTheProductionSource()
    {
        DefinedTypeCategory.serviceSource = production;
    }

    // ---- what EDT offers is built ----------------------------------------------------------------

    @Test
    public void testADocumentObjectAndACatalogObjectWithARefAreBuiltWhenEdtOffersThem()
    {
        // The reporter's case: an object type is a legal member of a defined type, both with a bare
        // ref and with a qualified one.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type invoice = seed(config, "Document", "Invoice", "objectType", "DocumentObject.Invoice"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Type goods = seed(config, "Catalog", "Goods", "objectType", "CatalogObject.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        useCategoryOffering("DocumentObject.Invoice", "CatalogObject.Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = buildDefinedType(config,
            item("DocumentObject", "Invoice"), item("CatalogObject", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull(result.error, result.error);
        assertEquals(2, result.typeDescription.getTypes().size());
        assertSame("the owner's model-owned produced Type is shared", //$NON-NLS-1$
            invoice, result.typeDescription.getTypes().get(0));
        assertSame(goods, result.typeDescription.getTypes().get(1));
        assertEquals("EDT's category judged every built item, in order", //$NON-NLS-1$
            List.of("DocumentObject.Invoice", "CatalogObject.Goods"), asked); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheRussianSpellingOfADocumentObjectBuildsTheSameType()
    {
        // The Russian spelling of DocumentObject, with Invoice qualified by the Russian spelling of
        // Document: the TYPE tokens are bilingual, the object Name is the programmatic one in both cases.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type invoice = seed(config, "Document", "Invoice", "objectType", "DocumentObject.Invoice"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        useCategoryOffering("DocumentObject.Invoice"); //$NON-NLS-1$
        String document = "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442"; //$NON-NLS-1$
        String object = "\u041E\u0431\u044A\u0435\u043A\u0442"; //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(config,
            item(document + object, document + ".Invoice")); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertEquals(1, result.typeDescription.getTypes().size());
        assertSame(invoice, result.typeDescription.getTypes().get(0));
        assertEquals(List.of("DocumentObject.Invoice"), asked); //$NON-NLS-1$
    }

    @Test
    public void testValueTableValueTreeAndFixedArrayAreBuiltWhenEdtOffersThem()
    {
        // The three ways a platform value is built: a curated collection kind, the same in Russian
        // (here the Russian spelling of ValueTree), and a type only the platform's own index knows.
        Type table = named("ValueTable"); //$NON-NLS-1$
        Type tree = named("ValueTree"); //$NON-NLS-1$
        Type fixedArray = named("FixedArray"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(
            Map.of("ValueTable", table, "ValueTree", tree, "FixedArray", fixedArray)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        useCategoryOffering("ValueTable", "ValueTree", "FixedArray"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MetadataTypeBuilder.Result result = buildDefinedType(provider,
            MdClassFactory.eINSTANCE.createConfiguration(), kind("ValueTable"), //$NON-NLS-1$
            kind("\u0414\u0435\u0440\u0435\u0432\u043E\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0439"), //$NON-NLS-1$
            kind("FixedArray")); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertEquals(3, result.typeDescription.getTypes().size());
        assertSame(table, result.typeDescription.getTypes().get(0));
        assertSame(tree, result.typeDescription.getTypes().get(1));
        assertSame(fixedArray, result.typeDescription.getTypes().get(2));
        assertEquals(List.of("ValueTable", "ValueTree", "FixedArray"), asked); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAPrimitiveAndAReferenceAreJudgedByEdtsCategoryToo()
    {
        // Most real defined types hold primitives and references. They are built as for any other
        // target and still go through EDT's category: a primitive as the platform type it names (with
        // its qualifiers), a reference as the object's produced Ref type.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Type string = named("String"); //$NON-NLS-1$
        useCategoryOffering("String", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject text = kind("String"); //$NON-NLS-1$
        text.addProperty("length", 10); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(providerKnowing(Map.of("String", string)), config, //$NON-NLS-1$
            text, item("Ref", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error, result.error);
        assertEquals(2, result.typeDescription.getTypes().size());
        assertSame(string, result.typeDescription.getTypes().get(0));
        assertSame("the catalog's model-owned Ref type is shared", //$NON-NLS-1$
            goodsRef, result.typeDescription.getTypes().get(1));
        assertNotNull("the String keeps its qualifiers", result.typeDescription.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, result.typeDescription.getStringQualifiers().getLength());
        assertEquals("EDT's category judged the primitive and the reference too, in order", //$NON-NLS-1$
            List.of("String", "CatalogRef.Goods"), asked); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDynamicListIsLeftToEdtsCategoryOnADefinedType()
    {
        // A FORM attribute refuses a bare DynamicList because a list needs its query, which queryText
        // owns. A defined type holds no query, and EDT's DefinedType editor lists DynamicList - so
        // there the category decides, like for every other type.
        Type dynamicList = named("DynamicList"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("DynamicList", dynamicList)); //$NON-NLS-1$
        useCategoryOffering("DynamicList"); //$NON-NLS-1$
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();

        MetadataTypeBuilder.Result result = buildDefinedType(provider, config, kind("DynamicList")); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertSame(dynamicList, result.typeDescription.getTypes().get(0));
        assertEquals(List.of("DynamicList"), asked); //$NON-NLS-1$

        TypeDescription form = McoreFactory.eINSTANCE.createTypeDescription();
        String formError = MetadataTypeBuilder.addType(form, kind("DynamicList"), "DynamicList", provider, //$NON-NLS-1$ //$NON-NLS-2$
            config, false, MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);
        assertNotNull("a form attribute still refuses a list without its query", formError); //$NON-NLS-1$
        assertTrue(formError, formError.contains("queryText")); //$NON-NLS-1$
    }

    // ---- what EDT does not offer is refused, in the defined type's own words ---------------------

    @Test
    public void testACatalogListIsRefusedInTheDefinedTypesOwnWords()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "Catalog", "Products", "listType", "CatalogList.Products"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        useCategoryOffering("CatalogObject.Products", "CatalogRef.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = buildDefinedType(config, item("CatalogList", "Products")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("nothing is built", result.typeDescription); //$NON-NLS-1$
        assertFalse("a type EDT does not offer is the caller's refusal, not a platform failure", //$NON-NLS-1$
            result.platformFailure);
        assertEquals("Type kind 'CatalogList' (type.types[0], the type 'CatalogList.Products') cannot be " //$NON-NLS-1$
            + "part of a defined type: EDT's DefinedType type editor does not offer it (EDT type category " //$NON-NLS-1$
            + "'UserDefinedTypeInfoCategory'). Remove it from the defined type's type list.", result.error); //$NON-NLS-1$
        assertEquals(List.of("CatalogList.Products"), asked); //$NON-NLS-1$
    }

    @Test
    public void testTheFirstTypeEdtDoesNotOfferStopsTheBuildAndIsNamedByItsIndex()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "Document", "Invoice", "objectType", "DocumentObject.Invoice"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        seed(config, "InformationRegister", "Prices", "recordManagerType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "InformationRegisterRecordManager.Prices"); //$NON-NLS-1$
        seed(config, "Document", "Order", "objectType", "DocumentObject.Order"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        useCategoryOffering("DocumentObject.Invoice", "DocumentObject.Order"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = buildDefinedType(config, item("DocumentObject", "Invoice"), //$NON-NLS-1$ //$NON-NLS-2$
            item("InformationRegisterRecordManager", "Prices"), item("DocumentObject", "Order")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull(result.typeDescription);
        assertFalse(result.platformFailure);
        assertEquals("Type kind 'InformationRegisterRecordManager' (type.types[1], the type " //$NON-NLS-1$
            + "'InformationRegisterRecordManager.Prices') cannot be part of a defined type: EDT's DefinedType " //$NON-NLS-1$
            + "type editor does not offer it (EDT type category 'UserDefinedTypeInfoCategory'). Remove it " //$NON-NLS-1$
            + "from the defined type's type list.", result.error); //$NON-NLS-1$
        assertEquals("the build stops at the first refusal", //$NON-NLS-1$
            List.of("DocumentObject.Invoice", "InformationRegisterRecordManager.Prices"), asked); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnotherDefinedTypeIsRefusedBecauseEdtDoesNotOfferIt()
    {
        // It is EDT's category that says no here, not a rule of this builder: the category is asked.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        MetadataTypeBuilderTest.seedDefinedType(config, "Money", true); //$NON-NLS-1$
        useCategoryOffering("DocumentObject.Invoice"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(config, item("DefinedType", "Money")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.platformFailure);
        assertEquals("Type kind 'DefinedType' (type.types[0], the type 'DefinedType.Money') cannot be part of " //$NON-NLS-1$
            + "a defined type: EDT's DefinedType type editor does not offer it (EDT type category " //$NON-NLS-1$
            + "'UserDefinedTypeInfoCategory'). Remove it from the defined type's type list.", result.error); //$NON-NLS-1$
        assertEquals(List.of("DefinedType.Money"), asked); //$NON-NLS-1$
    }

    @Test
    public void testAnAbstractProducedTypeIsRefusedAndNamedOnce()
    {
        // Without a ref, ExchangePlanObject is the ABSTRACT produced type: a platform type whose name is
        // the kind itself. EDT's DefinedType category takes no such one-segment produced type, and the
        // refusal names the type once instead of repeating it as "the type 'ExchangePlanObject'".
        Type abstractObject = named("ExchangePlanObject"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("ExchangePlanObject", abstractObject)); //$NON-NLS-1$
        useCategoryOffering("ExchangePlanObject.Nodes"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(provider, MdClassFactory.eINSTANCE.createConfiguration(),
            kind("ExchangePlanObject")); //$NON-NLS-1$

        assertNull(result.typeDescription);
        assertFalse(result.platformFailure);
        assertEquals("Type kind 'ExchangePlanObject' (type.types[0]) cannot be part of a defined type: EDT's " //$NON-NLS-1$
            + "DefinedType type editor does not offer it (EDT type category 'UserDefinedTypeInfoCategory'). " //$NON-NLS-1$
            + "Remove it from the defined type's type list.", result.error); //$NON-NLS-1$
        assertEquals(List.of("ExchangePlanObject"), asked); //$NON-NLS-1$
    }

    @Test
    public void testANestedProducedTypeIsRefusedWithoutAnAbstractFormToRetry()
    {
        // The other targets advise the abstract {kind:'RecalculationRecordSet'} here. EDT's DefinedType
        // category takes neither form of a recalculation's types, so on a defined type that advice would
        // only lead to a second refusal: this one ends the matter instead.
        useCategoryOffering("DocumentObject.Invoice"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(MdClassFactory.eINSTANCE.createConfiguration(),
            item("RecalculationRecordSet", "CalculationRegister.Payroll.Recalculation.Rc")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.typeDescription);
        assertFalse(result.platformFailure);
        assertEquals("Type kind 'RecalculationRecordSet' is a produced type of a NESTED object (Recalculation " //$NON-NLS-1$
            + "lives inside its owning register), which cannot be addressed by ref. A defined type does not " //$NON-NLS-1$
            + "take its abstract form either: EDT's DefinedType type editor does not offer it. Remove it from " //$NON-NLS-1$
            + "the defined type's type list.", result.error); //$NON-NLS-1$
        assertFalse("no retry is advised: " + result.error, result.error.contains("{kind:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing was built for EDT to judge: " + asked, asked.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testTheUnknownKindAdviceNamesOnlyWhatADefinedTypeTakes()
    {
        // The other targets' advice lists another DefinedType and the abstract {kind:'ExchangePlanObject'},
        // and sends the caller to a FORM attribute for the rest - retries EDT's DefinedType category
        // refuses. A typo and the unpublished recalculation kind (with and without a ref) all get the
        // defined type's own list instead.
        useCategoryOffering("String"); //$NON-NLS-1$
        JsonObject[] unknown = {kind("Strng"), kind("RecalculationObject"), //$NON-NLS-1$ //$NON-NLS-2$
            item("RecalculationObject", "X")}; //$NON-NLS-1$ //$NON-NLS-2$

        for (JsonObject spec : unknown)
        {
            String kind = spec.get("kind").getAsString(); //$NON-NLS-1$
            MetadataTypeBuilder.Result result = buildDefinedType(providerKnowing(Map.of()),
                MdClassFactory.eINSTANCE.createConfiguration(), spec);

            assertNull(spec.toString(), result.typeDescription);
            assertFalse(spec.toString(), result.platformFailure);
            assertEquals(spec.toString(), "Unknown type kind '" + kind + "'. A defined type takes what EDT's " //$NON-NLS-1$ //$NON-NLS-2$
                + "DefinedType type editor offers: String / Number / Boolean / Date / ValueStorage / UUID, a " //$NON-NLS-1$
                + "reference ({kind:'Ref', ref:'Type.Name'}), a produced type with its ref such as " //$NON-NLS-1$
                + "{kind:'DocumentObject', ref:'Invoice'}, or a platform value such as ValueTable / ValueTree / " //$NON-NLS-1$
                + "FixedArray / ValueList (English or Russian) - this one names no type this platform version " //$NON-NLS-1$
                + "knows.", result.error); //$NON-NLS-1$
        }
        assertTrue("an unknown kind builds nothing for EDT to judge: " + asked, asked.isEmpty()); //$NON-NLS-1$
    }

    // ---- a category that cannot answer refuses the whole type: never a pass, never another rule ----

    @Test
    public void testAMissingCategoryServiceRefusesTheWholeType()
    {
        RuntimeException unavailable = new IllegalStateException("the service is unavailable"); //$NON-NLS-1$
        DefinedTypeCategory.serviceSource = () -> {
            throw unavailable;
        };

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result, "EDT's type-category service is not available"); //$NON-NLS-1$
        assertSame("the platform's own exception rides along for the log", unavailable, result.cause); //$NON-NLS-1$
    }

    @Test
    public void testAServiceThatAnswersNothingRefusesTheWholeType()
    {
        DefinedTypeCategory.serviceSource = () -> null;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result, "EDT's type-category service is not available"); //$NON-NLS-1$
    }

    @Test
    public void testACategoryEdtDoesNotRegisterRefusesTheWholeType()
    {
        // EDT's service THROWS for a name it does not know ("Unknown category name - <name>",
        // TypeInfoCategoryServiceProvider.getCategoryByName on 2026.1.2 and 2026.2.x).
        ITypeInfoCategoryServiceProvider service = Mockito.mock(ITypeInfoCategoryServiceProvider.class);
        IllegalArgumentException unknown = new IllegalArgumentException("Unknown category name - " + CATEGORY); //$NON-NLS-1$
        Mockito.doThrow(unknown).when(service).getCategoryByName(Mockito.anyString());
        DefinedTypeCategory.serviceSource = () -> service;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result, "EDT does not register the category"); //$NON-NLS-1$
        assertSame(unknown, result.cause);
    }

    @Test
    public void testACategoryAnsweredAsNullRefusesTheWholeType()
    {
        ITypeInfoCategoryServiceProvider service = Mockito.mock(ITypeInfoCategoryServiceProvider.class);
        DefinedTypeCategory.serviceSource = () -> service;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result, "EDT does not register the category"); //$NON-NLS-1$
    }

    @Test
    public void testACategoryWithoutAProviderRefusesTheWholeTypeRatherThanEveryItem()
    {
        // With no provider, EDT's category answers "no" for every item - md.ui contributes its only
        // provider. That "no" would blame the caller for the platform, so the category is never asked.
        ITypeInfoCategory category = Mockito.mock(ITypeInfoCategory.class);
        Mockito.doReturn(Collections.emptyList()).when(category).getProviders();
        ITypeInfoCategoryServiceProvider service = serviceReturning(category);
        DefinedTypeCategory.serviceSource = () -> service;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result,
            "the category has no provider (EDT's metadata editor bundle contributes it)"); //$NON-NLS-1$
        Mockito.verify(category, Mockito.never())
            .hasTypeItemInCategory(Mockito.any(TypeItem.class), Mockito.any(Version.class));
        Mockito.verify(category, Mockito.never()).hasTypeItemInCategory(Mockito.any(TypeItem.class));
    }

    @Test
    public void testACategoryWhoseProvidersCannotBeReadRefusesTheWholeType()
    {
        ITypeInfoCategory category = Mockito.mock(ITypeInfoCategory.class);
        RuntimeException failure = new IllegalStateException("the provider list failed"); //$NON-NLS-1$
        Mockito.doThrow(failure).when(category).getProviders();
        ITypeInfoCategoryServiceProvider service = serviceReturning(category);
        DefinedTypeCategory.serviceSource = () -> service;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertCategoryUnavailable(result, "the category's providers could not be read"); //$NON-NLS-1$
        assertSame("the platform's own exception rides along for the log", failure, result.cause); //$NON-NLS-1$
        Mockito.verify(category, Mockito.never())
            .hasTypeItemInCategory(Mockito.any(TypeItem.class), Mockito.any(Version.class));
    }

    @Test
    public void testACategoryThatThrowsRefusesTheWholeTypeAndKeepsItsException()
    {
        ITypeInfoCategory category = categoryWithAProvider();
        RuntimeException failure = new IllegalStateException("the provider failed"); //$NON-NLS-1$
        Mockito.doThrow(failure).when(category)
            .hasTypeItemInCategory(Mockito.any(TypeItem.class), Mockito.any(Version.class));
        ITypeInfoCategoryServiceProvider service = serviceReturning(category);
        DefinedTypeCategory.serviceSource = () -> service;

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.typeDescription);
        assertTrue(result.error, result.platformFailure);
        assertEquals("Type kind 'DocumentObject' (type.types[0]) could not be checked against EDT's DefinedType " //$NON-NLS-1$
            + "type category 'UserDefinedTypeInfoCategory': the category failed while answering" //$NON-NLS-1$
            + UNAVAILABLE_ADVICE, result.error);
        assertSame(failure, result.cause);
    }

    @Test
    public void testATypeWhoseNameCannotBeReadIsNeverJudged()
    {
        // EDT's provider reads an item by its name and answers "no" when it cannot - the very answer
        // that refuses. So an unreadable name is caught first and reported as what it is.
        Type proxy = McoreFactory.eINSTANCE.createType();
        ((InternalEObject)proxy).eSetProxyURI(
            URI.createURI("http://ditrix.com/test/no-such-type#//NoSuchType")); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("ValueTable", proxy)); //$NON-NLS-1$
        useCategoryOffering("ValueTable"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(provider, MdClassFactory.eINSTANCE.createConfiguration(),
            kind("ValueTable")); //$NON-NLS-1$

        assertNull(result.typeDescription);
        assertTrue(result.error, result.platformFailure);
        assertEquals("Type kind 'ValueTable' (type.types[0]) could not be checked against EDT's DefinedType " //$NON-NLS-1$
            + "type category 'UserDefinedTypeInfoCategory': the name of the type built for it could not be " //$NON-NLS-1$
            + "read" + UNAVAILABLE_ADVICE, result.error); //$NON-NLS-1$
        assertTrue("the category was never asked: " + asked, asked.isEmpty()); //$NON-NLS-1$
    }

    // ---- EDT is asked with the project's platform version, once per build ------------------------

    @Test
    public void testEdtIsAskedWithTheProjectsPlatformVersion()
    {
        useCategoryOffering("DocumentObject.Invoice"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = buildDefinedType(documentInvoice(), item("DocumentObject", "Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error, result.error);
        assertEquals(1, askedWith.size());
        assertSame(PROJECT_VERSION, askedWith.get(0));
    }

    @Test
    public void testTheVersionKeyedBuildAsksWithTheVersionItWasGiven()
    {
        // build() looks its type provider up by the version; a concrete produced type does not use the
        // provider, so what is under test is only the version's way to EDT's category.
        assertNotNull("the registry answers a provider for this version", //$NON-NLS-1$
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, Version.LATEST));
        Configuration config = documentInvoice();
        useCategoryOffering("DocumentObject.Invoice"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.build(spec(item("DocumentObject", "Invoice")), //$NON-NLS-1$ //$NON-NLS-2$
            config, MetadataScope.ofConfiguration(config), Version.LATEST, false,
            MetadataTypeBuilder.TypeTarget.DEFINED_TYPE);

        assertNull(result.error, result.error);
        assertEquals(1, askedWith.size());
        assertSame(Version.LATEST, askedWith.get(0));
    }

    @Test
    public void testAnUnknownVersionRefusesTheWholeTypeBeforeEdtIsAsked()
    {
        // The provider-taking public overload carries no version; a defined type is not checked without one.
        AtomicInteger lookups = new AtomicInteger();
        DefinedTypeCategory.serviceSource = () -> {
            lookups.incrementAndGet();
            return null;
        };
        Configuration config = documentInvoice();

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(
            spec(item("DocumentObject", "Invoice")), Mockito.mock(IEObjectProvider.class), config, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataScope.ofConfiguration(config), false, MetadataTypeBuilder.TypeTarget.DEFINED_TYPE);

        assertCategoryUnavailable(result, "the project's platform version is not known"); //$NON-NLS-1$
        assertEquals(0, lookups.get());
    }

    @Test
    public void testTheCategoryIsLookedUpOncePerBuildUnderTheEditorsName()
    {
        Configuration config = documentInvoice();
        seed(config, "Document", "Order", "objectType", "DocumentObject.Order"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ITypeInfoCategoryServiceProvider service =
            useCategoryOffering("DocumentObject.Invoice", "DocumentObject.Order"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = buildDefinedType(config, item("DocumentObject", "Invoice"), //$NON-NLS-1$ //$NON-NLS-2$
            item("DocumentObject", "Order")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error, result.error);
        Mockito.verify(service, Mockito.times(1)).getCategoryByName(Mockito.anyString());
        Mockito.verify(service).getCategoryByName(CATEGORY);
        assertEquals(2, asked.size());
    }

    // ---- the other targets are untouched ---------------------------------------------------------

    @Test
    public void testNoOtherTargetAsksEdtsCategory()
    {
        AtomicInteger lookups = new AtomicInteger();
        DefinedTypeCategory.serviceSource = () -> {
            lookups.incrementAndGet();
            return null;
        };
        Configuration config = documentInvoice();

        for (MetadataTypeBuilder.TypeTarget target : MetadataTypeBuilder.TypeTarget.values())
        {
            if (target != MetadataTypeBuilder.TypeTarget.DEFINED_TYPE)
            {
                MetadataTypeBuilder.buildWithProvider(spec(item("DocumentObject", "Invoice")), //$NON-NLS-1$ //$NON-NLS-2$
                    Mockito.mock(IEObjectProvider.class), config, MetadataScope.ofConfiguration(config),
                    PROJECT_VERSION, false, target);
            }
        }

        assertEquals(0, lookups.get());
    }

    @Test
    public void testAStoredFeatureStillRefusesADocumentObjectInItsOwnWords()
    {
        Configuration config = documentInvoice();

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(
            spec(item("DocumentObject", "Invoice")), Mockito.mock(IEObjectProvider.class), config, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataScope.ofConfiguration(config), PROJECT_VERSION, false, MetadataTypeBuilder.TypeTarget.METADATA);

        assertFalse(result.platformFailure);
        assertEquals("Type kind 'DocumentObject' is a runtime object type: it belongs on an event " //$NON-NLS-1$
            + "subscription's 'source' or on a FORM attribute (fqn " //$NON-NLS-1$
            + "'Type.Object.Form.FormName.Attribute.Name'). A stored metadata feature takes a " //$NON-NLS-1$
            + "reference ({kind:'Ref', ref:'Type.Name'}) or a primitive " //$NON-NLS-1$
            + "(String / Number / Boolean / Date) instead.", result.error); //$NON-NLS-1$
    }

    @Test
    public void testNoRefusalStillCallsAFormAttributeTheOnlyPlaceAPlatformValueIsBuilt()
    {
        // A defined type now takes the platform values EDT's editor offers, so the refusals of the other
        // targets may no longer say a FORM attribute is the only place one is built.
        IEObjectProvider provider = providerKnowing(Map.of("SpreadsheetDocument", named("SpreadsheetDocument"), //$NON-NLS-1$ //$NON-NLS-2$
            "Chart", named("Chart"))); //$NON-NLS-1$ //$NON-NLS-2$
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();

        String stored = MetadataTypeBuilder.addType(McoreFactory.eINSTANCE.createTypeDescription(),
            kind("SpreadsheetDocument"), "SpreadsheetDocument", provider, config, false, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.TypeTarget.METADATA);
        String dcsValue = MetadataTypeBuilder.addType(McoreFactory.eINSTANCE.createTypeDescription(),
            kind("Chart"), "Chart", provider, config, false, MetadataTypeBuilder.TypeTarget.DCS_PARAMETER); //$NON-NLS-1$ //$NON-NLS-2$
        String dcsCollection = MetadataTypeBuilder.addType(McoreFactory.eINSTANCE.createTypeDescription(),
            kind("ValueTable"), "ValueTable", provider, config, false, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertTrue(stored, stored.contains("stored metadata feature")); //$NON-NLS-1$
        assertTrue(stored, stored.contains("Form.FormName.Attribute")); //$NON-NLS-1$
        assertFalse(stored, stored.contains("builds only for a FORM attribute")); //$NON-NLS-1$
        assertTrue(dcsValue, dcsValue.contains("data-composition parameter")); //$NON-NLS-1$
        assertFalse(dcsValue, dcsValue.contains("only target")); //$NON-NLS-1$
        assertTrue(dcsCollection, dcsCollection.contains("IN-MEMORY collection")); //$NON-NLS-1$
        assertFalse(dcsCollection, dcsCollection.contains("only target")); //$NON-NLS-1$
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Type named(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    /** Seeds {@code englishMetadataType.name} with one produced type, named the way EDT names it. */
    private static Type seed(Configuration config, String englishMetadataType, String name, String producedFeature,
        String typeName)
    {
        Type type = named(typeName);
        MetadataTypeBuilderTest.seedProducedType(config, englishMetadataType, name, producedFeature, type);
        return type;
    }

    /** A configuration with {@code Document.Invoice} and its object type {@code DocumentObject.Invoice}. */
    private static Configuration documentInvoice()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "Document", "Invoice", "objectType", "DocumentObject.Invoice"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return config;
    }

    private static JsonObject item(String kind, String ref)
    {
        JsonObject item = kind(kind);
        item.addProperty("ref", ref); //$NON-NLS-1$
        return item;
    }

    private static JsonObject kind(String kind)
    {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind); //$NON-NLS-1$
        return item;
    }

    private static JsonElement spec(JsonObject... items)
    {
        JsonArray types = new JsonArray();
        for (JsonObject item : items)
        {
            types.add(item);
        }
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        return spec;
    }

    private static MetadataTypeBuilder.Result buildDefinedType(Configuration config, JsonObject... items)
    {
        return buildDefinedType(Mockito.mock(IEObjectProvider.class), config, items);
    }

    private static MetadataTypeBuilder.Result buildDefinedType(IEObjectProvider provider, Configuration config,
        JsonObject... items)
    {
        return MetadataTypeBuilder.buildWithProvider(spec(items), provider, config,
            MetadataScope.ofConfiguration(config), PROJECT_VERSION, false,
            MetadataTypeBuilder.TypeTarget.DEFINED_TYPE);
    }

    /** A platform type provider that knows exactly these names and THROWS for any other, as EDT's does. */
    private static IEObjectProvider providerKnowing(Map<String, Type> known)
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("Can't create proxy for unknown name")) //$NON-NLS-1$
            .when(provider).createProxy(Mockito.anyString());
        known.forEach((name, type) -> Mockito.doReturn(type).when(provider).createProxy(name));
        return provider;
    }

    /**
     * Installs EDT's category service, faked with a {@code UserDefinedTypeInfoCategory} that offers
     * exactly {@code names} and records every question.
     *
     * @return the faked service
     */
    private ITypeInfoCategoryServiceProvider useCategoryOffering(String... names)
    {
        Set<String> offered = Set.of(names);
        ITypeInfoCategory category = categoryWithAProvider();
        Mockito.doAnswer(invocation -> {
            TypeItem item = invocation.getArgument(0);
            asked.add(item.getName());
            askedWith.add(invocation.getArgument(1));
            return Boolean.valueOf(offered.contains(item.getName()));
        }).when(category).hasTypeItemInCategory(Mockito.any(TypeItem.class), Mockito.any(Version.class));
        ITypeInfoCategoryServiceProvider service = serviceReturning(category);
        DefinedTypeCategory.serviceSource = () -> service;
        return service;
    }

    private static ITypeInfoCategory categoryWithAProvider()
    {
        ITypeInfoCategory category = Mockito.mock(ITypeInfoCategory.class);
        Mockito.doReturn(List.of(Mockito.mock(ICategoryTypeInfoProvider.class))).when(category).getProviders();
        return category;
    }

    /** EDT's service answering {@code category} under its one name and THROWING for any other, as EDT's does. */
    private static ITypeInfoCategoryServiceProvider serviceReturning(ITypeInfoCategory category)
    {
        ITypeInfoCategoryServiceProvider service = Mockito.mock(ITypeInfoCategoryServiceProvider.class);
        Mockito.doThrow(new IllegalArgumentException("Unknown category name")) //$NON-NLS-1$
            .when(service).getCategoryByName(Mockito.anyString());
        Mockito.doReturn(category).when(service).getCategoryByName(CATEGORY);
        return service;
    }

    /** A refusal of the whole type because EDT's category could not be consulted at all. */
    private static void assertCategoryUnavailable(MetadataTypeBuilder.Result result, String reason)
    {
        assertNull("nothing is built", result.typeDescription); //$NON-NLS-1$
        assertTrue("a category that cannot answer is the platform's failure: " + result.error, //$NON-NLS-1$
            result.platformFailure);
        assertEquals("A defined type's type list is checked against EDT's DefinedType type category " //$NON-NLS-1$
            + "'UserDefinedTypeInfoCategory', which is unavailable: " + reason + UNAVAILABLE_ADVICE, //$NON-NLS-1$
            result.error);
    }
}
