/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.withSettings;

import java.math.BigDecimal;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.UndefinedValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameter;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameterLink;
import com._1c.g5.v8.dt.metadata.common.CommonFactory;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingFlag;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.Field;
import com._1c.g5.v8.dt.metadata.mdclass.FullTextSearchUsing;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.SequenceDimension;
import com._1c.g5.v8.dt.metadata.mdclass.SessionParameter;
import com._1c.g5.v8.dt.metadata.mdclass.UseQuickChoice;
import com._1c.g5.v8.dt.metadata.mdtype.DefinedTypeTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdUserDefinedType;
import com.ditrix.edt.mcp.server.utils.TypeChangeDefaults;
import com.ditrix.edt.mcp.server.utils.TypeChangeDefaults.Reset;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Issue #700: a {@code type} change through modify_metadata resets the properties that depend on the
 * type exactly as EDT's designer does. The designer's type editor runs EDT's own
 * {@code com._1c.g5.v8.dt.md.api.ChangeTypeDescriptionTask}; the write transaction runs the same task,
 * so the rules below are EDT's, read from its bytecode (md 27.1.0 for EDT 2026.1 and md 28.0.0 for
 * 2026.2 carry the same rules):
 * <ul>
 * <li>a Constant or a BasicFeature (an attribute, dimension, resource, tabular-section attribute,
 * accounting flag, common attribute, ...): the choice form is cleared and quick choice becomes
 * {@code Auto}; the choice parameters and parameter links are cleared - always on a Constant, and on a
 * BasicFeature unless a type in the new type can use them (a reference); the fill value becomes
 * {@code Undefined}; min / max become {@code Undefined} unless the new type includes {@code Number}; a
 * single {@code UUID} also stops filling from the filling value and full-text search; a single
 * {@code ValueStorage} or unlimited {@code String} stops indexing;</li>
 * <li>a DefinedType: the fill value of every editable metadata object typed with it becomes
 * {@code Undefined};</li>
 * <li>any other owner (a chart of characteristic types, an external data source field, ...): only the
 * type is set;</li>
 * <li>an object an extension adopted from its base configuration: nothing - its type lives in the
 * extension's type extension, which {@code AdoptedPropertyRouter} writes or refuses before the transaction,
 * so a type write that still reaches the native type fails closed (issues #709 / #710).</li>
 * </ul>
 * Each property the task resets is found BY NAME on the owner's class, so a class without one is simply
 * skipped. The designer runs the task only for a real change (its type editor commits nothing when the
 * type is unchanged), so writing the type a member already has resets nothing; and a batch that writes
 * the type twice is reset for the type it ends with.
 *
 * <p>Driven through the production apply step ({@link ModifyMetadataTool.PreparedChange#applyTo}) on
 * bare metadata objects. The transaction is a mock: the task asks it only for a DefinedType's
 * referrers. EDT's editing-support service is the one {@link VendorSupportGuard} hands out, replaced
 * here by a mock that allows every edit.</p>
 */
public class ModifyMetadataToolTypeChangeTest
{
    private IModelEditingSupport editingSupport;

    @Before
    public void installEditingSupport()
    {
        editingSupport = mock(IModelEditingSupport.class);
        doReturn(Boolean.TRUE).when(editingSupport).canEdit(any(), any());
        VendorSupportGuard.setServiceForTests(() -> editingSupport);
    }

    @After
    public void restoreEditingSupport()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== the type-dependent resets of a Constant / BasicFeature ====================

    @Test
    public void testARetypeResetsTheFillValueToUndefined()
    {
        CatalogAttribute attribute = designerString10Attribute();
        TypeDescription number = number(10, 2);

        retype(attribute, number);

        assertSame("the new type is set", number, attribute.getType()); //$NON-NLS-1$
        assertUndefined("the designer's String fill value must not survive a retype to Number", //$NON-NLS-1$
            attribute.getFillValue());
    }

    @Test
    public void testARetypeAwayFromNumberResetsMinAndMaxToUndefined()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setType(number(10, 2));
        attribute.setMinValue(numberValue(1));
        attribute.setMaxValue(numberValue(100));

        retype(attribute, string(20, false));

        assertUndefined("a String has no numeric minimum", attribute.getMinValue()); //$NON-NLS-1$
        assertUndefined("a String has no numeric maximum", attribute.getMaxValue()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeThatKeepsANumberKeepsMinAndMax()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setType(number(10, 2));
        NumberValue min = numberValue(1);
        NumberValue max = numberValue(100);
        attribute.setMinValue(min);
        attribute.setMaxValue(max);
        attribute.setFillValue(numberValue(5));

        // A composite type that still includes Number: EDT keeps the bounds.
        TypeDescription composite = types("String", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
        composite.setNumberQualifiers(numberQualifiers(15, 2));
        retype(attribute, composite);

        assertSame("min survives while the type still includes Number", min, attribute.getMinValue()); //$NON-NLS-1$
        assertSame("max survives while the type still includes Number", max, attribute.getMaxValue()); //$NON-NLS-1$
        assertUndefined("the fill value is reset on every retype, Number or not", attribute.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToUuidStopsFillingFromTheFillingValueAndFullTextSearch()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setFillFromFillingValue(true);
        attribute.setFullTextSearch(FullTextSearchUsing.USE);

        retype(attribute, types("UUID")); //$NON-NLS-1$

        assertFalse("a UUID attribute is not filled from the filling value", attribute.isFillFromFillingValue()); //$NON-NLS-1$
        assertEquals("a UUID attribute takes no part in full-text search", FullTextSearchUsing.DONT_USE, //$NON-NLS-1$
            attribute.getFullTextSearch());
    }

    @Test
    public void testARetypeToAnyOtherTypeKeepsFillingFromTheFillingValueAndFullTextSearch()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setFillFromFillingValue(true);
        attribute.setFullTextSearch(FullTextSearchUsing.USE);

        // UUID among others is no UUID: EDT's rule asks for exactly one type, named UUID.
        retype(attribute, types("UUID", "String")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(attribute.isFillFromFillingValue());
        assertEquals(FullTextSearchUsing.USE, attribute.getFullTextSearch());
        assertUndefined("the fill value is still reset", attribute.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToValueStorageStopsIndexing()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setIndexing(Indexing.INDEX);

        retype(attribute, types("ValueStorage")); //$NON-NLS-1$

        assertEquals("a value storage cannot be indexed", Indexing.DONT_INDEX, attribute.getIndexing()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToAnUnlimitedStringStopsIndexing()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setIndexing(Indexing.INDEX_WITH_ADDITIONAL_ORDER);

        // An unlimited String as EDT's designer writes it: string qualifiers of length 0, variable.
        retype(attribute, string(0, false));

        assertEquals("an unlimited string cannot be indexed", Indexing.DONT_INDEX, attribute.getIndexing()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToABoundedFixedOrQualifierlessStringKeepsIndexing()
    {
        CatalogAttribute bounded = designerString10Attribute();
        bounded.setIndexing(Indexing.INDEX);
        retype(bounded, string(20, false));
        assertEquals("a bounded string stays indexed", Indexing.INDEX, bounded.getIndexing()); //$NON-NLS-1$

        CatalogAttribute fixed = designerString10Attribute();
        fixed.setIndexing(Indexing.INDEX);
        retype(fixed, string(0, true));
        assertEquals("EDT's rule reads a FIXED zero length as not unlimited", Indexing.INDEX, //$NON-NLS-1$
            fixed.getIndexing());

        // EDT's designer always writes string qualifiers; a String without them is not what its rule
        // calls unlimited. The guide therefore names `length: 0` for an unlimited string.
        CatalogAttribute qualifierless = designerString10Attribute();
        qualifierless.setIndexing(Indexing.INDEX);
        retype(qualifierless, types("String")); //$NON-NLS-1$
        assertEquals("EDT's rule needs the string qualifiers to call a String unlimited", Indexing.INDEX, //$NON-NLS-1$
            qualifierless.getIndexing());
    }

    @Test
    public void testARetypeClearsTheChoiceFormAndSetsQuickChoiceToAuto()
    {
        CatalogAttribute attribute = designerString10Attribute();
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        attribute.setChoiceForm(form);
        attribute.setQuickChoice(UseQuickChoice.DONT_USE);

        retype(attribute, types("CatalogRef.Products")); //$NON-NLS-1$

        assertEquals("the choice form belonged to the old type", null, attribute.getChoiceForm()); //$NON-NLS-1$
        assertEquals(UseQuickChoice.AUTO, attribute.getQuickChoice());
    }

    @Test
    public void testARetypeToATypeWithoutReferencesClearsTheChoiceParametersAndLinks()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setType(types("CatalogRef.Products")); //$NON-NLS-1$
        attribute.getChoiceParameters().add(choiceParameter());
        attribute.getChoiceParameterLinks().add(choiceParameterLink());

        retype(attribute, number(10, 0));

        assertTrue("a Number cannot use choice parameters", attribute.getChoiceParameters().isEmpty()); //$NON-NLS-1$
        assertTrue("nor choice parameter links", attribute.getChoiceParameterLinks().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToAReferenceKeepsTheChoiceParametersAndLinks()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setType(types("CatalogRef.Products")); //$NON-NLS-1$
        ChoiceParameter parameter = choiceParameter();
        ChoiceParameterLink link = choiceParameterLink();
        attribute.getChoiceParameters().add(parameter);
        attribute.getChoiceParameterLinks().add(link);

        // A reference among the new types can use them, so EDT keeps them.
        retype(attribute, types("Number", "DocumentRef.Invoice")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(parameter), attribute.getChoiceParameters());
        assertEquals(List.of(link), attribute.getChoiceParameterLinks());
        assertUndefined("the fill value is still reset", attribute.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testAConstantIsResetAsTheDesignerResetsIt()
    {
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setType(number(10, 2));
        constant.setMinValue(numberValue(1));
        constant.setMaxValue(numberValue(100));
        CommonForm form = MdClassFactory.eINSTANCE.createCommonForm();
        constant.setChoiceForm(form);
        constant.setQuickChoice(UseQuickChoice.USE);
        constant.getChoiceParameters().add(choiceParameter());
        TypeDescription string = string(30, false);

        retype(constant, MdClassPackage.Literals.CONSTANT__TYPE, string, mock(IBmTransaction.class));

        assertSame(string, constant.getType());
        assertUndefined("a String constant has no numeric minimum", constant.getMinValue()); //$NON-NLS-1$
        assertUndefined("a String constant has no numeric maximum", constant.getMaxValue()); //$NON-NLS-1$
        assertEquals(null, constant.getChoiceForm());
        assertEquals(UseQuickChoice.AUTO, constant.getQuickChoice());
        assertTrue("a String cannot use choice parameters", constant.getChoiceParameters().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAConstantLosesItsChoiceParametersEvenForAReference()
    {
        // EDT's task asks the type providers whether the new type can still use choice parameters, and
        // they answer that for a BasicFeature only: retyped to the same reference, an attribute keeps
        // them and a constant loses them.
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setType(types("CatalogRef.Products")); //$NON-NLS-1$
        ChoiceParameter attributeParameter = choiceParameter();
        attribute.getChoiceParameters().add(attributeParameter);
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setType(types("CatalogRef.Products")); //$NON-NLS-1$
        constant.getChoiceParameters().add(choiceParameter());
        constant.getChoiceParameterLinks().add(choiceParameterLink());

        retype(attribute, types("DocumentRef.Invoice")); //$NON-NLS-1$
        retype(constant, MdClassPackage.Literals.CONSTANT__TYPE, types("DocumentRef.Invoice"), //$NON-NLS-1$
            mock(IBmTransaction.class));

        assertEquals("an attribute retyped to a reference keeps its choice parameters", //$NON-NLS-1$
            List.of(attributeParameter), attribute.getChoiceParameters());
        assertTrue("a constant keeps no choice parameters, whatever its new type", //$NON-NLS-1$
            constant.getChoiceParameters().isEmpty());
        assertTrue("nor choice parameter links", constant.getChoiceParameterLinks().isEmpty()); //$NON-NLS-1$
    }

    // ==================== no reset ====================

    @Test
    public void testWritingTheTypeTheMemberAlreadyHasResetsNothing()
    {
        CatalogAttribute attribute = designerString10Attribute();
        Value fill = attribute.getFillValue();
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        attribute.setChoiceForm(form);
        attribute.setQuickChoice(UseQuickChoice.DONT_USE);
        attribute.setIndexing(Indexing.INDEX);
        ChoiceParameter parameter = choiceParameter();
        attribute.getChoiceParameters().add(parameter);
        TypeDescription same = string(10, false);

        retype(attribute, same);

        assertSame("the type is still written", same, attribute.getType()); //$NON-NLS-1$
        assertSame("the same type keeps the fill value", fill, attribute.getFillValue()); //$NON-NLS-1$
        assertSame(form, attribute.getChoiceForm());
        assertEquals(UseQuickChoice.DONT_USE, attribute.getQuickChoice());
        assertEquals(Indexing.INDEX, attribute.getIndexing());
        assertEquals(List.of(parameter), attribute.getChoiceParameters());
    }

    @Test
    public void testAnOwnerOutsideEdtsTypeChangeIsOnlyRetyped()
    {
        // A chart of characteristic types carries a fullTextSearch (and a quickChoice flag), but it is no
        // Constant, BasicFeature or DefinedType: EDT's type change sets its type and nothing else.
        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setType(string(10, false));
        chart.setQuickChoice(true);
        chart.setFullTextSearch(FullTextSearchUsing.USE);
        TypeDescription uuid = types("UUID"); //$NON-NLS-1$

        retype(chart, MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES__TYPE, uuid,
            mock(IBmTransaction.class));

        assertSame(uuid, chart.getType());
        assertTrue(chart.isQuickChoice());
        assertEquals("a chart's full-text search outlives a UUID type", FullTextSearchUsing.USE, //$NON-NLS-1$
            chart.getFullTextSearch());

        // An external data source's field has every value property an attribute has, and is no
        // BasicFeature either.
        Field field = MdClassFactory.eINSTANCE.createField();
        field.setType(string(10, false));
        StringValue fill = stringValue("abc"); //$NON-NLS-1$
        field.setFillValue(fill);
        field.setFillFromFillingValue(true);
        NumberValue min = numberValue(1);
        field.setMinValue(min);
        field.setQuickChoice(UseQuickChoice.DONT_USE);
        TypeDescription fieldType = types("UUID"); //$NON-NLS-1$

        retype(field, MdClassPackage.Literals.FIELD__TYPE, fieldType, mock(IBmTransaction.class));

        assertSame(fieldType, field.getType());
        assertSame(fill, field.getFillValue());
        assertTrue(field.isFillFromFillingValue());
        assertSame(min, field.getMinValue());
        assertEquals(UseQuickChoice.DONT_USE, field.getQuickChoice());
    }

    @Test
    public void testAnAdoptedMemberIsNeverRetypedNatively()
    {
        // EDT's designer changes the type of an object an extension adopted only in the extension's type
        // extension, and EDT's .mdo writer drops a native type written on an adopted object - such a write
        // answered success over a file that never changed (issues #709 / #710). modify_metadata routes or
        // refuses every type write on an adopted object before the transaction (AdoptedPropertyRouter); one
        // that still reaches the type write fails closed - rolled back with the transaction - and writes
        // nothing, the base-configuration resets included.
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        TypeDescription before = attribute.getType();
        Value fill = attribute.getFillValue();
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        attribute.setChoiceForm(form);
        attribute.setQuickChoice(UseQuickChoice.DONT_USE);
        attribute.setFillFromFillingValue(true);
        IBmTransaction tx = mock(IBmTransaction.class);

        assertFailsClosed(attribute, MdClassPackage.Literals.BASIC_FEATURE__TYPE, types("UUID"), tx); //$NON-NLS-1$

        assertSame("the type is not written", before, attribute.getType()); //$NON-NLS-1$
        assertSame(fill, attribute.getFillValue());
        assertSame(form, attribute.getChoiceForm());
        assertEquals(UseQuickChoice.DONT_USE, attribute.getQuickChoice());
        assertTrue(attribute.isFillFromFillingValue());
        assertEquals(FullTextSearchUsing.USE, attribute.getFullTextSearch());
        verifyZeroInteractions(tx);
    }

    // ==================== DefinedType ====================

    @Test
    public void testADefinedTypeRetypeResetsTheFillValueOfItsEditableReferrers()
    {
        // EDT's rule for a DefinedType reaches OTHER objects: every metadata object whose type holds the
        // defined type, found through the BM references of the type set it produces, gets an Undefined
        // fill value - when EDT allows editing it. The objects are fetched from the TRANSACTION, so the
        // pin also proves the task runs with the write transaction it was handed.
        DefinedType definedType = mock(DefinedType.class, withSettings().extraInterfaces(IBmObject.class));
        DefinedTypeTypes produced = mock(DefinedTypeTypes.class);
        MdUserDefinedType container = mock(MdUserDefinedType.class);
        TypeSet typeSet = mock(TypeSet.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(produced).when(definedType).getProducedTypes();
        doReturn(container).when(produced).getContainerType();
        doReturn(typeSet).when(container).getTypeSet();
        IBmCrossReference toEditable = crossReference(11L);
        IBmCrossReference toLocked = crossReference(12L);
        doReturn(List.of(toEditable, toLocked)).when((IBmObject)typeSet).bmGetReferences();

        CatalogAttribute editable = designerString10Attribute();
        CatalogAttribute locked = designerString10Attribute();
        Value lockedFill = locked.getFillValue();
        IBmTransaction tx = mock(IBmTransaction.class);
        doReturn(editable.getType()).when(tx).getObjectById(11L);
        doReturn(locked.getType()).when(tx).getObjectById(12L);
        doReturn(Boolean.FALSE).when(editingSupport).canEdit(locked, EditingMode.DIRECT);
        TypeDescription number = number(15, 2);

        retype(definedType, MdClassPackage.Literals.DEFINED_TYPE__TYPE, number, tx);

        assertUndefined("an editable attribute typed with the defined type loses its stale fill value", //$NON-NLS-1$
            editable.getFillValue());
        assertSame("an attribute EDT does not allow editing is left alone", lockedFill, locked.getFillValue()); //$NON-NLS-1$
        verify(definedType).eSet(MdClassPackage.Literals.DEFINED_TYPE__TYPE, number);
    }

    @Test
    public void testAnAdoptedDefinedTypeIsNeverRetypedNatively()
    {
        // The referrer set-up a native defined type resets: an adopted one fails closed before its type is
        // written, so neither the defined type nor an object typed with it changes, and the transaction is
        // never asked for one. Its type lives in the extension's type extension (issue #709).
        DefinedType definedType = mock(DefinedType.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(ObjectBelonging.ADOPTED).when(definedType).getObjectBelonging();
        DefinedTypeTypes produced = mock(DefinedTypeTypes.class);
        MdUserDefinedType container = mock(MdUserDefinedType.class);
        TypeSet typeSet = mock(TypeSet.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(produced).when(definedType).getProducedTypes();
        doReturn(container).when(produced).getContainerType();
        doReturn(typeSet).when(container).getTypeSet();
        doReturn(List.of(crossReference(11L))).when((IBmObject)typeSet).bmGetReferences();
        CatalogAttribute referrer = designerString10Attribute();
        Value fill = referrer.getFillValue();
        IBmTransaction tx = mock(IBmTransaction.class);
        doReturn(referrer.getType()).when(tx).getObjectById(11L);
        TypeDescription number = number(15, 2);

        assertFailsClosed(definedType, MdClassPackage.Literals.DEFINED_TYPE__TYPE, number, tx);

        assertSame("an object typed with an adopted defined type keeps its fill value", fill, //$NON-NLS-1$
            referrer.getFillValue());
        verify(definedType, never()).eSet(any(), any());
        verify(tx, never()).getObjectById(anyLong());
    }

    // ==================== a missing editing-support service ====================

    @Test
    public void testATypeChangeWithoutTheEditingSupportServiceFailsBeforeWriting()
    {
        // EDT's task needs the editing-support service. The vendor-support guard has already refused a
        // write it could not check against it, so a missing service here is a race the call fails on
        // loudly - rolled back with the transaction - rather than writing a type without its resets.
        VendorSupportGuard.setServiceForTests(() -> null);
        CatalogAttribute attribute = designerString10Attribute();
        TypeDescription before = attribute.getType();
        Value fill = attribute.getFillValue();

        try
        {
            retype(attribute, number(10, 2));
            fail("a type change that cannot run EDT's resets must not be written"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("IModelEditingSupport")); //$NON-NLS-1$
        }
        assertSame("nothing was written", before, attribute.getType()); //$NON-NLS-1$
        assertSame(fill, attribute.getFillValue());
    }


    // ==================== what a write reports, the order of a batch, the message ====================

    @Test
    public void testApplyToAnswersWhatATypeWriteResetBesidesTheType()
    {
        assertEquals("a retype of an attribute resets its own type-dependent properties", Reset.OWNER, //$NON-NLS-1$
            applyType(designerString10Attribute(), MdClassPackage.Literals.BASIC_FEATURE__TYPE, number(10, 2)));
        assertEquals("the type it already has resets nothing", Reset.NONE, //$NON-NLS-1$
            applyType(designerString10Attribute(), MdClassPackage.Literals.BASIC_FEATURE__TYPE, string(10, false)));
        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setType(string(10, false));
        assertEquals("EDT resets nothing on a chart's retype", Reset.NONE, //$NON-NLS-1$
            applyType(chart, MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES__TYPE, types("UUID"))); //$NON-NLS-1$
        assertEquals("a property that is not a type resets nothing", Reset.NONE, //$NON-NLS-1$
            ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.BASIC_FEATURE__QUICK_CHOICE,
                UseQuickChoice.USE).applyTo(designerString10Attribute(), mock(IBmTransaction.class)));

        DefinedType definedType = mock(DefinedType.class, withSettings().extraInterfaces(IBmObject.class));
        assertEquals("a defined type's retype resets the fill value of the objects typed with it", //$NON-NLS-1$
            Reset.REFERRERS, applyType(definedType, MdClassPackage.Literals.DEFINED_TYPE__TYPE, number(10, 2)));
    }

    @Test
    public void testATypeWriteIsAppliedBeforeTheOtherPropertiesOfItsCall()
    {
        // The batch lists the explicit properties FIRST. Applied in that order, the retype's resets would
        // undo them (quick choice back to Auto, indexing off for a value storage); the type goes first.
        CatalogAttribute attribute = designerString10Attribute();
        TypeDescription valueStorage = types("ValueStorage"); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> batch = List.of(
            ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.BASIC_FEATURE__QUICK_CHOICE,
                UseQuickChoice.DONT_USE),
            ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.DB_OBJECT_ATTRIBUTE__INDEXING,
                Indexing.INDEX),
            ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.BASIC_FEATURE__TYPE,
                valueStorage));

        Reset reset = ModifyMetadataTool.applyChanges(attribute, batch, mock(IBmTransaction.class));

        assertEquals(Reset.OWNER, reset);
        assertSame(valueStorage, attribute.getType());
        assertEquals("the call's own quick choice wins over the reset", UseQuickChoice.DONT_USE, //$NON-NLS-1$
            attribute.getQuickChoice());
        assertEquals("the call's own indexing wins over the reset", Indexing.INDEX, attribute.getIndexing()); //$NON-NLS-1$
        assertUndefined("what the call does not set is reset", attribute.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testABatchWithoutATypeWriteReportsNoReset()
    {
        CatalogAttribute attribute = designerString10Attribute();
        Value fill = attribute.getFillValue();

        Reset reset = ModifyMetadataTool.applyChanges(attribute, List.of(
            ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.DB_OBJECT_ATTRIBUTE__INDEXING,
                Indexing.INDEX)), mock(IBmTransaction.class));

        assertEquals(Reset.NONE, reset);
        assertEquals(Indexing.INDEX, attribute.getIndexing());
        assertSame(fill, attribute.getFillValue());
    }

    @Test
    public void testARepeatedTypeIsResetForTheTypeTheBatchEndsWith()
    {
        // The last write of a repeated property decides, and a repeated type is no exception: the UUID
        // the batch passes through must not leave its resets on the String it ends with.
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setFillFromFillingValue(true);
        TypeDescription string20 = string(20, false);

        Reset reset = ModifyMetadataTool.applyChanges(attribute,
            List.of(typeWrite(types("UUID")), typeWrite(string20)), mock(IBmTransaction.class)); //$NON-NLS-1$

        assertEquals("String(10) to String(20) is still a type change", Reset.OWNER, reset); //$NON-NLS-1$
        assertSame(string20, attribute.getType());
        assertUndefined("the change the batch ends with resets the fill value", attribute.getFillValue()); //$NON-NLS-1$
        assertTrue("a String keeps filling from the filling value", attribute.isFillFromFillingValue()); //$NON-NLS-1$
        assertEquals("a String keeps its full-text search", FullTextSearchUsing.USE, //$NON-NLS-1$
            attribute.getFullTextSearch());
    }

    @Test
    public void testARepeatedTypeThatEndsOnTheStoredTypeResetsNothing()
    {
        CatalogAttribute attribute = designerString10Attribute();
        Value fill = attribute.getFillValue();
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        attribute.setChoiceForm(form);
        attribute.setQuickChoice(UseQuickChoice.DONT_USE);
        TypeDescription same = string(10, false);

        Reset reset = ModifyMetadataTool.applyChanges(attribute,
            List.of(typeWrite(number(10, 2)), typeWrite(same)), mock(IBmTransaction.class));

        assertEquals("a batch that ends on the stored type changes no type", Reset.NONE, reset); //$NON-NLS-1$
        assertSame(same, attribute.getType());
        assertSame(fill, attribute.getFillValue());
        assertSame(form, attribute.getChoiceForm());
        assertEquals(UseQuickChoice.DONT_USE, attribute.getQuickChoice());
    }

    @Test
    public void testOfTwoTypeWritesTheLastOneIsApplied()
    {
        CatalogAttribute attribute = designerString10Attribute();
        attribute.setIndexing(Indexing.INDEX);
        TypeDescription valueStorage = types("ValueStorage"); //$NON-NLS-1$

        Reset reset = ModifyMetadataTool.applyChanges(attribute,
            List.of(typeWrite(number(10, 2)), typeWrite(valueStorage)), mock(IBmTransaction.class));

        assertEquals(Reset.OWNER, reset);
        assertSame("the last type write decides", valueStorage, attribute.getType()); //$NON-NLS-1$
        assertEquals("the value storage's own reset ran", Indexing.DONT_INDEX, attribute.getIndexing()); //$NON-NLS-1$

        // The same new type twice is one change, reported once.
        CatalogAttribute twice = designerString10Attribute();
        TypeDescription second = number(10, 2);
        assertEquals(Reset.OWNER, ModifyMetadataTool.applyChanges(twice,
            List.of(typeWrite(number(10, 2)), typeWrite(second)), mock(IBmTransaction.class)));
        assertSame(second, twice.getType());
        assertUndefined("the fill value is reset", twice.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testTheOtherPropertiesOfABatchKeepTheirOrder()
    {
        // Only the type moves to the front: the rest of the batch keeps the caller's order, so a property
        // repeated around the type is decided by its last write, as everywhere else.
        CatalogAttribute attribute = designerString10Attribute();

        Reset reset = ModifyMetadataTool.applyChanges(attribute, List.of(quickChoice(UseQuickChoice.USE),
            typeWrite(number(10, 2)), quickChoice(UseQuickChoice.DONT_USE)), mock(IBmTransaction.class));

        assertEquals(Reset.OWNER, reset);
        assertEquals("the batch's last quick choice wins", UseQuickChoice.DONT_USE, attribute.getQuickChoice()); //$NON-NLS-1$
        assertUndefined("the type change still resets the fill value", attribute.getFillValue()); //$NON-NLS-1$
    }

    @Test
    public void testTheModifiedMessageNamesWhatTheTypeChangeReset()
    {
        List<String> applied = List.of("type"); //$NON-NLS-1$
        String fqn = "Catalog.Products.Attribute.Weight"; //$NON-NLS-1$

        assertEquals("Modified Catalog.Products.Attribute.Weight (type)", //$NON-NLS-1$
            ModifyMetadataTool.modifiedMessage(fqn, applied, List.of(), Reset.NONE));
        assertEquals("Modified Catalog.Products.Attribute.Weight (type); the type change also reset the " //$NON-NLS-1$
            + "properties that depend on the type, as EDT's designer does", //$NON-NLS-1$
            ModifyMetadataTool.modifiedMessage(fqn, applied, List.of(), Reset.OWNER));
        assertEquals("Modified DefinedType.Amount (type); the type change also reset the fill value of every " //$NON-NLS-1$
            + "editable object typed with it, as EDT's designer does", //$NON-NLS-1$
            ModifyMetadataTool.modifiedMessage("DefinedType.Amount", applied, List.of(), Reset.REFERRERS)); //$NON-NLS-1$
    }

    @Test
    public void testTheOwnersEdtsTypeChangeResets()
    {
        // The instanceof chain of EDT's task: a Constant or any BasicFeature resets its own properties, a
        // DefinedType its referrers' fill values, every other type-carrying object nothing.
        MdClassFactory md = MdClassFactory.eINSTANCE;
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(md.createCatalogAttribute()));
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(md.createConstant()));
        CommonAttribute commonAttribute = md.createCommonAttribute();
        assertEquals("a common attribute is a BasicFeature", Reset.OWNER, //$NON-NLS-1$
            TypeChangeDefaults.resetFor(commonAttribute));
        InformationRegisterDimension dimension = md.createInformationRegisterDimension();
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(dimension));
        AccountingFlag flag = md.createAccountingFlag();
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(flag));
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(md.createTabularSectionAttribute()));
        assertEquals(Reset.REFERRERS, TypeChangeDefaults.resetFor(md.createDefinedType()));
        SequenceDimension sequenceDimension = md.createSequenceDimension();
        assertEquals("a sequence dimension is no BasicFeature", Reset.NONE, //$NON-NLS-1$
            TypeChangeDefaults.resetFor(sequenceDimension));
        SessionParameter sessionParameter = md.createSessionParameter();
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(sessionParameter));
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(md.createChartOfCharacteristicTypes()));
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(md.createField()));
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(md.createEventSubscription()));
    }

    @Test
    public void testAnAdoptedOwnerGetsNoResetAndNoNativeType()
    {
        // The belonging decides, not the class: EDT's designer retypes an adopted object through the
        // extension's type extension, never through its type-change task - and never through its native
        // type either, so the type write itself fails closed on every adopted owner (issue #709).
        MdClassFactory md = MdClassFactory.eINSTANCE;
        CatalogAttribute nativeAttribute = md.createCatalogAttribute();
        nativeAttribute.setObjectBelonging(ObjectBelonging.NATIVE);
        assertEquals(Reset.OWNER, TypeChangeDefaults.resetFor(nativeAttribute));
        CatalogAttribute adoptedAttribute = md.createCatalogAttribute();
        adoptedAttribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(adoptedAttribute));
        Constant adoptedConstant = md.createConstant();
        adoptedConstant.setObjectBelonging(ObjectBelonging.ADOPTED);
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(adoptedConstant));
        DefinedType adoptedDefinedType = md.createDefinedType();
        adoptedDefinedType.setObjectBelonging(ObjectBelonging.ADOPTED);
        assertEquals(Reset.NONE, TypeChangeDefaults.resetFor(adoptedDefinedType));

        TypeDescription number = number(10, 2);
        assertFailsClosed(adoptedAttribute, MdClassPackage.Literals.BASIC_FEATURE__TYPE, number,
            mock(IBmTransaction.class));
        assertFailsClosed(adoptedConstant, MdClassPackage.Literals.CONSTANT__TYPE, number,
            mock(IBmTransaction.class));
        assertFailsClosed(adoptedDefinedType, MdClassPackage.Literals.DEFINED_TYPE__TYPE, number,
            mock(IBmTransaction.class));
        assertEquals("nothing reached the adopted attribute", null, adoptedAttribute.getType()); //$NON-NLS-1$
        assertEquals("nothing reached the adopted constant", null, adoptedConstant.getType()); //$NON-NLS-1$
        assertEquals("nothing reached the adopted defined type", null, adoptedDefinedType.getType()); //$NON-NLS-1$
        assertSame("a native owner of the same class is still retyped", number, //$NON-NLS-1$
            setTypeOf(nativeAttribute, number));
    }

    // ==================== the guide ====================

    @Test
    public void testTheGuideStatesTheTypeChangeResets()
    {
        // Short anchors, not the paragraph: the mechanism, the constant's exception and the spelling of
        // an unlimited String each have to stay stated, in whatever words around them.
        String guide = new ModifyMetadataTool().getGuide();
        for (String anchor : GUIDE_ANCHORS)
        {
            assertTrue("the modify_metadata guide no longer says: " + anchor, guide.contains(anchor)); //$NON-NLS-1$
        }
    }

    /** What the {@code type} section of the guide states about the resets (issue #700). */
    static final List<String> GUIDE_ANCHORS = List.of("runs EDT's own type-change task", //$NON-NLS-1$
        "always on a constant", "`length: 0`"); //$NON-NLS-1$ //$NON-NLS-2$

    // ==================== helpers ====================

    private static void retype(CatalogAttribute attribute, TypeDescription newType)
    {
        retype(attribute, MdClassPackage.Literals.BASIC_FEATURE__TYPE, newType, mock(IBmTransaction.class));
    }

    /** The production apply step of a prepared {@code type} change. */
    private static void retype(EObject owner, EStructuralFeature feature, TypeDescription newType,
        IBmTransaction tx)
    {
        ModifyMetadataTool.PreparedChange.typeDescription(feature, newType).applyTo(owner, tx);
    }

    /** A prepared {@code type} write of a BasicFeature, as modify_metadata prepares it. */
    private static ModifyMetadataTool.PreparedChange typeWrite(TypeDescription newType)
    {
        return ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.BASIC_FEATURE__TYPE,
            newType);
    }

    private static ModifyMetadataTool.PreparedChange quickChoice(UseQuickChoice value)
    {
        return ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.BASIC_FEATURE__QUICK_CHOICE,
            value);
    }

    /**
     * A type write that must fail closed: {@link TypeChangeDefaults#setType} on an adopted owner throws
     * an {@link IllegalStateException} that points at the router and says nothing was changed.
     */
    private static void assertFailsClosed(EObject owner, EStructuralFeature feature, TypeDescription newType,
        IBmTransaction tx)
    {
        try
        {
            ModifyMetadataTool.PreparedChange.typeDescription(feature, newType).applyTo(owner, tx);
            fail("a native type write on an adopted " + owner.getClass().getSimpleName() //$NON-NLS-1$
                + " must fail closed"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("AdoptedPropertyRouter")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was changed")); //$NON-NLS-1$
        }
    }

    /** The type of a native owner after a type write through the production apply step. */
    private static TypeDescription setTypeOf(CatalogAttribute owner, TypeDescription newType)
    {
        ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.BASIC_FEATURE__TYPE, newType)
            .applyTo(owner, mock(IBmTransaction.class));
        return owner.getType();
    }

    /** {@link #retype} answering what the write reset besides the type. */
    private static Reset applyType(EObject owner, EStructuralFeature feature, TypeDescription newType)
    {
        return ModifyMetadataTool.PreparedChange.typeDescription(feature, newType).applyTo(owner,
            mock(IBmTransaction.class));
    }

    /**
     * A catalog attribute as EDT's designer creates it (CatalogInitializer, md 28): String(10), an empty
     * String fill value, Undefined min / max and full-text search on.
     */
    private static CatalogAttribute designerString10Attribute()
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Member"); //$NON-NLS-1$
        attribute.setType(string(10, false));
        attribute.setFillValue(stringValue("")); //$NON-NLS-1$
        attribute.setMinValue(McoreFactory.eINSTANCE.createUndefinedValue());
        attribute.setMaxValue(McoreFactory.eINSTANCE.createUndefinedValue());
        attribute.setFullTextSearch(FullTextSearchUsing.USE);
        return attribute;
    }

    /** A type of named, resolved type items; the task reads a resolved item's name, as for a proxy. */
    private static TypeDescription types(String... names)
    {
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (String name : names)
        {
            Type type = McoreFactory.eINSTANCE.createType();
            type.setName(name);
            description.getTypes().add(type);
        }
        return description;
    }

    private static TypeDescription string(int length, boolean fixed)
    {
        TypeDescription description = types("String"); //$NON-NLS-1$
        StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
        qualifiers.setLength(length);
        qualifiers.setFixed(fixed);
        description.setStringQualifiers(qualifiers);
        return description;
    }

    private static TypeDescription number(int precision, int scale)
    {
        TypeDescription description = types("Number"); //$NON-NLS-1$
        description.setNumberQualifiers(numberQualifiers(precision, scale));
        return description;
    }

    private static NumberQualifiers numberQualifiers(int precision, int scale)
    {
        NumberQualifiers qualifiers = McoreFactory.eINSTANCE.createNumberQualifiers();
        qualifiers.setPrecision(precision);
        qualifiers.setScale(scale);
        return qualifiers;
    }

    private static NumberValue numberValue(int value)
    {
        NumberValue number = McoreFactory.eINSTANCE.createNumberValue();
        number.setValue(BigDecimal.valueOf(value));
        return number;
    }

    private static StringValue stringValue(String value)
    {
        StringValue string = McoreFactory.eINSTANCE.createStringValue();
        string.setValue(value);
        return string;
    }

    private static ChoiceParameter choiceParameter()
    {
        ChoiceParameter parameter = CommonFactory.eINSTANCE.createChoiceParameter();
        parameter.setName("Filter.Owner"); //$NON-NLS-1$
        return parameter;
    }

    private static ChoiceParameterLink choiceParameterLink()
    {
        ChoiceParameterLink link = CommonFactory.eINSTANCE.createChoiceParameterLink();
        link.setName("Filter.Parent"); //$NON-NLS-1$
        return link;
    }

    private static IBmCrossReference crossReference(long objectId)
    {
        IBmCrossReference reference = mock(IBmCrossReference.class);
        doReturn(Long.valueOf(objectId)).when(reference).getObjectId();
        return reference;
    }

    private static void assertUndefined(String message, Value value)
    {
        assertNotNull(message + ": no value at all", value); //$NON-NLS-1$
        assertTrue(message + ": " + value, value instanceof UndefinedValue); //$NON-NLS-1$
    }
}
