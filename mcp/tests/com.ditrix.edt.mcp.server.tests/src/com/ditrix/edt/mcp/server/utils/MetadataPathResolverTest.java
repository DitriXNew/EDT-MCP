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
