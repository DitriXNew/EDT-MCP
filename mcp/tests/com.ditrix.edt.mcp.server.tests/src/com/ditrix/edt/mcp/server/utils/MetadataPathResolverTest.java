/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link MetadataPathResolver}.
 * <p>
 * Pure string/FQN resolution (no live model needed): the metadata TYPE token is
 * bilingual (resolved via {@link MetadataTypeUtils}), while the object Name and
 * form Name are carried through verbatim. This is the bilingual contract in
 * miniature -- the Russian Справочник resolves to the same Catalogs directory as the
 * English Catalog, but Товары/ФормаЭлемента are never translated. Cyrillic in string
 * literals is written as Unicode escapes per CLAUDE.md don't #7 (raw Cyrillic
 * only in comments).
 */
public class MetadataPathResolverTest
{
    // ==================== resolveFormFilePath -- happy paths ====================

    @Test
    public void testEnglishCatalogForm()
    {
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTypeTokenWithEnglishNames()
    {
        // Russian TYPE token, English object/form names: only the type token is
        // bilingual, so the directory is Catalogs but the rest is verbatim.
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath(
                "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products.Forms.ItemForm")); // Справочник.Products.Forms.ItemForm
    }

    @Test
    public void testFullyRussianForm()
    {
        // Справочник.Товары.Forms.ФормаЭлемента -> src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form
        // The object Name (Товары) and form Name (ФормаЭлемента) pass through untranslated.
        assertEquals(
            "src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/Forms/\u0424\u043E\u0440\u043C\u0430\u042D\u043B\u0435\u043C\u0435\u043D\u0442\u0430/Form.form", // src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form
            MetadataPathResolver.resolveFormFilePath(
                "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0422\u043E\u0432\u0430\u0440\u044B.Forms.\u0424\u043E\u0440\u043C\u0430\u042D\u043B\u0435\u043C\u0435\u043D\u0442\u0430")); // Справочник.Товары.Forms.ФормаЭлемента
    }

    @Test
    public void testCommonForm()
    {
        assertEquals("src/CommonForms/MyForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("CommonForm.MyForm")); //$NON-NLS-1$
    }

    @Test
    public void testFormsKeywordIsCaseInsensitive()
    {
        // The "forms" segment is matched case-insensitively; the emitted path
        // always normalizes to the canonical "Forms" directory.
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.FORMS.ItemForm")); //$NON-NLS-1$
    }

    // ==================== form-token parity (shared FormElementWriter.isFormToken) ====================

    @Test
    public void testSingularFormTokenResolvesSamePathAsPlural()
    {
        // create_metadata accepts 'Catalog.X.Form.Y' (singular) - the screenshot / layout-snapshot
        // resolver must accept the SAME token set, so a just-created form is addressable by the same
        // formPath. Singular and plural resolve to the identical on-disk path.
        assertEquals(MetadataPathResolver.resolveFormFilePath("Catalog.Products.Forms.ItemForm"), //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRussianFormTokensResolveSamePath()
    {
        // Форма (singular) / Формы (plural) as the form segment resolve to the same path the English
        // tokens do - one shared predicate, no divergence between create and read tooling.
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath(
                "Catalog.Products.\u0424\u043E\u0440\u043C\u0430.ItemForm")); // Catalog.Products.Форма.ItemForm
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath(
                "Catalog.Products.\u0424\u043E\u0440\u043C\u044B.ItemForm")); // Catalog.Products.Формы.ItemForm
    }

    @Test
    public void testFormFolderResolvesForSingularFormToken()
    {
        // The folder variant (used by the orphan-folder cleanup) inherits the same token set.
        assertEquals("src/Catalogs/Products/Forms/ItemForm", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFolderPath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    // ==================== resolveFormFilePath -- null/unresolvable ====================

    @Test
    public void testNullReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath(null));
    }

    @Test
    public void testEmptyReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath("")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownTypeReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath("Bogus.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testOnePartReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath("Catalog")); //$NON-NLS-1$
    }

    @Test
    public void testThreePartsReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath("Catalog.Products.Forms")); //$NON-NLS-1$
    }

    @Test
    public void testNonFormsKeywordReturnsNull()
    {
        // 4 parts but the third segment is not "forms".
        assertNull(MetadataPathResolver.resolveFormFilePath("Catalog.Products.Attributes.Price")); //$NON-NLS-1$
    }

    @Test
    public void testTwoPartNonCommonFormReturnsNull()
    {
        // 2 parts whose type token resolves to a directory other than CommonForms.
        assertNull(MetadataPathResolver.resolveFormFilePath("Catalog.Products")); //$NON-NLS-1$
    }

    // ==================== resolveFormFolderPath (orphan form-folder removal) ====================

    @Test
    public void testFormFolderEnglishCatalog()
    {
        // The folder the form-object delete must physically remove: the Form.form file's parent
        // directory (src/<TypeDir>/<Owner>/Forms/<FormName>), Catalog -> Catalogs.
        assertEquals("src/Catalogs/Products/Forms/ItemForm", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFolderPath("Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testFormFolderDocumentType()
    {
        // A non-Catalog owner type maps to its own type directory (Document -> Documents): the orphan
        // removal must follow the same per-type mapping, not assume Catalogs.
        assertEquals("src/Documents/SalesOrder/Forms/DocumentForm", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFolderPath("Document.SalesOrder.Forms.DocumentForm")); //$NON-NLS-1$
    }

    @Test
    public void testFormFolderInformationRegisterType()
    {
        // InformationRegister -> InformationRegisters: a multi-word type directory must resolve correctly.
        assertEquals("src/InformationRegisters/Prices/Forms/ListForm", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFolderPath("InformationRegister.Prices.Forms.ListForm")); //$NON-NLS-1$
    }

    @Test
    public void testFormFolderIsFilePathWithoutFormDotForm()
    {
        // The folder is exactly the file path minus the trailing "/Form.form": delete the folder, not
        // just the file, so any Module.bsl / sub-files under it go too.
        String file = MetadataPathResolver.resolveFormFilePath("Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$
        String folder = MetadataPathResolver.resolveFormFolderPath("Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$
        assertEquals(folder + "/Form.form", file); //$NON-NLS-1$
    }

    @Test
    public void testFormFolderCommonForm()
    {
        // A CommonForm maps to its own layout (src/CommonForms/<Name>) - never routed through the
        // owned-form branch, but the resolver still maps it safely if ever asked.
        assertEquals("src/CommonForms/MyForm", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFolderPath("CommonForm.MyForm")); //$NON-NLS-1$
    }

    @Test
    public void testFormFolderNullAndUnresolvableReturnNull()
    {
        assertNull(MetadataPathResolver.resolveFormFolderPath(null));
        assertNull(MetadataPathResolver.resolveFormFolderPath("")); //$NON-NLS-1$
        assertNull(MetadataPathResolver.resolveFormFolderPath("Bogus.X.Forms.Y")); //$NON-NLS-1$
        assertNull(MetadataPathResolver.resolveFormFolderPath("Catalog.Products")); //$NON-NLS-1$
    }

    // ==================== resolveTopObjectMdoPath (top-object .mdo files) ====================

    @Test
    public void testTopObjectMdoEnglishCatalog()
    {
        assertEquals("src/Catalogs/Products/Products.mdo", //$NON-NLS-1$
            MetadataPathResolver.resolveTopObjectMdoPath("Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectMdoMultiWordTypeDirectory()
    {
        // InformationRegister -> InformationRegisters: the per-type directory mapping must
        // be followed, not assumed (resync_to_disk's missing-.mdo check rides on this).
        assertEquals("src/InformationRegisters/Prices/Prices.mdo", //$NON-NLS-1$
            MetadataPathResolver.resolveTopObjectMdoPath("InformationRegister.Prices")); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectMdoRussianTypeToken()
    {
        // The TYPE token is bilingual; the object Name passes through verbatim.
        assertEquals(
            "src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/\u0422\u043E\u0432\u0430\u0440\u044B.mdo", // src/Catalogs/Товары/Товары.mdo
            MetadataPathResolver.resolveTopObjectMdoPath(
                "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0422\u043E\u0432\u0430\u0440\u044B")); // Справочник.Товары
    }

    @Test
    public void testTopObjectMdoNullEmptyAndDotlessReturnNull()
    {
        // The Configuration root and other dotless FQNs have no own .mdo under a type
        // directory; null/empty inputs resolve to null, never throw.
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath(null));
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath("")); //$NON-NLS-1$
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath("Configuration")); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectMdoLeadingOrTrailingDotReturnsNull()
    {
        // Malformed FQNs (empty type or empty name) must not produce a half-built path.
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath(".Products")); //$NON-NLS-1$
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath("Catalog.")); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectMdoUnknownTypeReturnsNull()
    {
        // An unrecognized type token (or a type with no src/ directory layout) is not a
        // type-directory object: report null so callers skip it instead of mis-flagging it.
        assertNull(MetadataPathResolver.resolveTopObjectMdoPath("Bogus.Products")); //$NON-NLS-1$
    }

    // ==================== resolveMetadataDir ====================

    @Test
    public void testResolveMetadataDirEnglish()
    {
        assertEquals("Catalogs", MetadataPathResolver.resolveMetadataDir("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveMetadataDirRussian()
    {
        assertEquals("Catalogs", //$NON-NLS-1$
            MetadataPathResolver.resolveMetadataDir("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // Справочник
    }

    @Test
    public void testResolveMetadataDirUnknownReturnsNull()
    {
        assertNull(MetadataPathResolver.resolveMetadataDir("NotAType")); //$NON-NLS-1$
    }
}
