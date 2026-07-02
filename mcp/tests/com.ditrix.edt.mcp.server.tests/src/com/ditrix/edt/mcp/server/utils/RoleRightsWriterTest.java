/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.rights.model.RightValue;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Tests the pure, model-independent and UI-independent logic of {@link RoleRightsWriter}: the
 * tri-state {@link RightValue} parsing (set / unset / provided / boolean), the bilingual right / field
 * name matching, the template-op normalization and the whole-payload validation. The model-touching
 * apply path (BM tasks, RLS field resolution against a live DB view, the RoleDescription bootstrap) is
 * covered by the e2e suite against a live role.
 *
 * <p>Russian tokens are built from code points so the assertion verifies the real Cyrillic mapping,
 * not a round-trip of the same literal.</p>
 */
public class RoleRightsWriterTest
{
    // "Чтение" (Read) and "Изменение" (Update) as code points - pure ASCII source.
    private static final String RU_READ = fromCp(0x0427, 0x0442, 0x0435, 0x043d, 0x0438, 0x0435);
    private static final String RU_UPDATE =
        fromCp(0x0418, 0x0437, 0x043c, 0x0435, 0x043d, 0x0435, 0x043d, 0x0438, 0x0435);

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    private static JsonElement str(String value)
    {
        return new JsonPrimitive(value);
    }

    // ---- parseRightValue --------------------------------------------------------------------

    @Test
    public void testParseRightValueDefaultsToSet()
    {
        assertSame(RightValue.SET, RoleRightsWriter.parseRightValue(null));
        assertSame(RightValue.SET, RoleRightsWriter.parseRightValue(str("set"))); //$NON-NLS-1$
        assertSame(RightValue.SET, RoleRightsWriter.parseRightValue(str("SET"))); //$NON-NLS-1$
    }

    @Test
    public void testParseRightValueTokens()
    {
        assertSame(RightValue.UNSET, RoleRightsWriter.parseRightValue(str("unset"))); //$NON-NLS-1$
        assertSame(RightValue.UNSET, RoleRightsWriter.parseRightValue(str(" Unset "))); //$NON-NLS-1$
        assertSame(RightValue.PROVIDED, RoleRightsWriter.parseRightValue(str("provided"))); //$NON-NLS-1$
    }

    @Test
    public void testParseRightValueBoolean()
    {
        assertSame(RightValue.SET, RoleRightsWriter.parseRightValue(new JsonPrimitive(true)));
        assertSame(RightValue.UNSET, RoleRightsWriter.parseRightValue(new JsonPrimitive(false)));
    }

    @Test
    public void testIsValidRightValue()
    {
        assertTrue(RoleRightsWriter.isValidRightValue(null));
        assertTrue(RoleRightsWriter.isValidRightValue(str("set"))); //$NON-NLS-1$
        assertTrue(RoleRightsWriter.isValidRightValue(str("unset"))); //$NON-NLS-1$
        assertTrue(RoleRightsWriter.isValidRightValue(str("provided"))); //$NON-NLS-1$
        assertTrue(RoleRightsWriter.isValidRightValue(new JsonPrimitive(true)));
        assertFalse(RoleRightsWriter.isValidRightValue(str("maybe"))); //$NON-NLS-1$
        assertFalse(RoleRightsWriter.isValidRightValue(str(""))); //$NON-NLS-1$
    }

    // ---- namesMatch (bilingual) -------------------------------------------------------------

    @Test
    public void testNamesMatchEnglish()
    {
        assertTrue(RoleRightsWriter.namesMatch("Read", "Read", RU_READ)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsWriter.namesMatch("read", "Read", RU_READ)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNamesMatchRussian()
    {
        assertTrue(RoleRightsWriter.namesMatch(RU_READ, "Read", RU_READ)); //$NON-NLS-1$
        assertTrue(RoleRightsWriter.namesMatch(RU_UPDATE, "Update", RU_UPDATE)); //$NON-NLS-1$
    }

    @Test
    public void testNamesMatchNegativeAndNullSafe()
    {
        assertFalse(RoleRightsWriter.namesMatch("Delete", "Read", RU_READ)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RoleRightsWriter.namesMatch(null, "Read", RU_READ)); //$NON-NLS-1$
        assertFalse(RoleRightsWriter.namesMatch("Read", null, null)); //$NON-NLS-1$
        // A right with only an English name (Russian null) still matches by English.
        assertTrue(RoleRightsWriter.namesMatch("View", "View", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- resolveRlsFields (empty = whole-object) --------------------------------------------

    @Test
    public void testResolveRlsFieldsEmptyIsWholeObject()
    {
        assertSame(Collections.emptyList(), RoleRightsWriter.resolveRlsFields(null, null));
        assertSame(Collections.emptyList(),
            RoleRightsWriter.resolveRlsFields(null, Collections.emptyList()));
    }

    // ---- templateOp -------------------------------------------------------------------------

    @Test
    public void testTemplateOpDefaultAdd()
    {
        assertEquals("add", RoleRightsWriter.templateOp(new JsonObject())); //$NON-NLS-1$
        JsonObject blank = new JsonObject();
        blank.addProperty("op", "  "); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add", RoleRightsWriter.templateOp(blank)); //$NON-NLS-1$
    }

    @Test
    public void testTemplateOpNormalizesCase()
    {
        JsonObject edit = new JsonObject();
        edit.addProperty("op", "EDIT"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("edit", RoleRightsWriter.templateOp(edit)); //$NON-NLS-1$
        JsonObject del = new JsonObject();
        del.addProperty("op", " Delete "); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("delete", RoleRightsWriter.templateOp(del)); //$NON-NLS-1$
    }

    // ---- validateRightsEntry ----------------------------------------------------------------

    @Test
    public void testValidateRightsEntryRequiresObjectAndRight()
    {
        JsonObject noObject = new JsonObject();
        noObject.addProperty("right", "Read"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateRightsEntry(noObject), "object"); //$NON-NLS-1$

        JsonObject noRight = new JsonObject();
        noRight.addProperty("object", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateRightsEntry(noRight), "right"); //$NON-NLS-1$
    }

    @Test
    public void testValidateRightsEntryRejectsBadValue()
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("object", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("right", "Read"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("value", "maybe"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateRightsEntry(entry), "value"); //$NON-NLS-1$
    }

    @Test
    public void testValidateRightsEntryAcceptsValid()
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("object", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("right", RU_READ); //$NON-NLS-1$
        entry.addProperty("value", "unset"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(RoleRightsWriter.validateRightsEntry(entry));

        // value omitted -> defaults to 'set', still valid.
        JsonObject noValue = new JsonObject();
        noValue.addProperty("object", "Document.Order"); //$NON-NLS-1$ //$NON-NLS-2$
        noValue.addProperty("right", "Update"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(RoleRightsWriter.validateRightsEntry(noValue));
    }

    // ---- validateTemplateEntry --------------------------------------------------------------

    @Test
    public void testValidateTemplateEntryRequiresName()
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("op", "add"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("condition", "WHERE TRUE"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateTemplateEntry(entry), "name"); //$NON-NLS-1$
    }

    @Test
    public void testValidateTemplateEntryAddEditNeedCondition()
    {
        JsonObject add = new JsonObject();
        add.addProperty("op", "add"); //$NON-NLS-1$ //$NON-NLS-2$
        add.addProperty("name", "OwnOnly"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateTemplateEntry(add), "condition"); //$NON-NLS-1$

        JsonObject edit = new JsonObject();
        edit.addProperty("op", "edit"); //$NON-NLS-1$ //$NON-NLS-2$
        edit.addProperty("name", "OwnOnly"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateTemplateEntry(edit), "condition"); //$NON-NLS-1$
    }

    @Test
    public void testValidateTemplateEntryDeleteNeedsNoCondition()
    {
        JsonObject del = new JsonObject();
        del.addProperty("op", "delete"); //$NON-NLS-1$ //$NON-NLS-2$
        del.addProperty("name", "OwnOnly"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(RoleRightsWriter.validateTemplateEntry(del));
    }

    @Test
    public void testValidateTemplateEntryRejectsUnknownOp()
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("op", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("name", "OwnOnly"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.addProperty("condition", "WHERE TRUE"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateTemplateEntry(entry), "op"); //$NON-NLS-1$
    }

    // ---- validateRoleProperties -------------------------------------------------------------

    @Test
    public void testValidateRolePropertiesNullOk()
    {
        assertNull(RoleRightsWriter.validateRoleProperties(null));
        assertNull(RoleRightsWriter.validateRoleProperties(new JsonObject()));
    }

    @Test
    public void testValidateRolePropertiesAcceptsBooleans()
    {
        JsonObject props = new JsonObject();
        props.addProperty("setForNewObjects", true); //$NON-NLS-1$
        props.addProperty("setForAttributesByDefault", false); //$NON-NLS-1$
        props.addProperty("independentRightsOfChildObjects", true); //$NON-NLS-1$
        assertNull(RoleRightsWriter.validateRoleProperties(props));
    }

    @Test
    public void testValidateRolePropertiesRejectsNonBoolean()
    {
        JsonObject props = new JsonObject();
        props.addProperty("setForNewObjects", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorMentions(RoleRightsWriter.validateRoleProperties(props), "setForNewObjects"); //$NON-NLS-1$
    }

    // ---- validatePayload (aggregate) --------------------------------------------------------

    @Test
    public void testValidatePayloadSurfacesFirstError()
    {
        JsonObject badRight = new JsonObject();
        badRight.addProperty("object", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        // no 'right' -> should fail on the rights entry first.
        String err = RoleRightsWriter.validatePayload(
            List.of(badRight), Collections.emptyList(), null);
        assertErrorMentions(err, "right"); //$NON-NLS-1$
    }

    @Test
    public void testValidatePayloadAllValid()
    {
        JsonObject right = new JsonObject();
        right.addProperty("object", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        right.addProperty("right", "Read"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject template = new JsonObject();
        template.addProperty("name", "OwnOnly"); //$NON-NLS-1$ //$NON-NLS-2$
        template.addProperty("condition", "WHERE Owner = &CurrentUser"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject props = new JsonObject();
        props.addProperty("independentRightsOfChildObjects", true); //$NON-NLS-1$
        assertNull(RoleRightsWriter.validatePayload(List.of(right), List.of(template), props));
    }

    // ---- schema parity: every key the writer/tool reads is declared ------------------------

    @Test
    public void testInputSchemaDeclaresRolePayloadKeys()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        JsonObject props = JsonParser.parseString(schema).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue("schema must declare 'rights'", props.has("rights")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'templates'", props.has("templates")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'roleProperties'", props.has("roleProperties")); //$NON-NLS-1$ //$NON-NLS-2$
        // The role keys are OPTIONAL: they must not be in the required list.
        JsonElement required = JsonParser.parseString(schema).getAsJsonObject().get("required"); //$NON-NLS-1$
        if (required != null && required.isJsonArray())
        {
            for (JsonElement el : required.getAsJsonArray())
            {
                String name = el.getAsString();
                assertFalse("role payload keys must be optional: " + name, //$NON-NLS-1$
                    "rights".equals(name) || "templates".equals(name) //$NON-NLS-1$ //$NON-NLS-2$
                        || "roleProperties".equals(name)); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testInputSchemaDescribesRolePayloadKeys()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        JsonObject props = JsonParser.parseString(schema).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue("'rights' needs a description", //$NON-NLS-1$
            props.getAsJsonObject("rights").has("description")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("'templates' needs a description", //$NON-NLS-1$
            props.getAsJsonObject("templates").has("description")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("'roleProperties' needs a description", //$NON-NLS-1$
            props.getAsJsonObject("roleProperties").has("description")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertErrorMentions(String errorJson, String needle)
    {
        assertTrue("expected an error", errorJson != null); //$NON-NLS-1$
        assertTrue("error should mention '" + needle + "': " + errorJson, //$NON-NLS-1$ //$NON-NLS-2$
            errorJson.contains(needle));
    }
}
