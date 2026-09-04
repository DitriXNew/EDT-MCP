/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.BmBasicTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.event.IEventBroker;
import com._1c.g5.v8.dt.core.model.IModelObjectCollectionRuntimeOrderSorter;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Tests the pure, model-independent and UI-independent logic of {@link RoleRightsWriter}: the
 * tri-state {@link RightValue} parsing (set / unset / provided / boolean), the bilingual right / field
 * name matching, the template-op normalization and the whole-payload validation. The model-touching
 * apply path (BM tasks, RLS field resolution against a live DB view) is covered by the e2e suite
 * against a live role.
 *
 * <p>The {@link RoleRightsWriter#attachRoleDescription RoleDescription bootstrap} (issue #452) is
 * covered HERE as well as by e2e: it takes the transaction and the FQN generator as arguments instead
 * of resolving them, so a mocked pair reaches every branch - the attach, the load-bearing ORDER, the
 * three ways the FQN can fail to arrive, the reuse guard and the FQN collision - none of which an e2e
 * run can steer into on demand.</p>
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

    // ---- the RoleDescription bootstrap: attach it, carry its FQN out (issue #452) --------------

    /**
     * A Java object identity - the {@code ...Impl@<hash>} shape that must never reach the caller.
     * No length floor: the identity hash is {@code Integer.toHexString(hashCode())} and is
     * routinely shorter than four digits, so a probe with a floor would call a leaked
     * {@code @abc} clean.
     */
    private static final Pattern OBJECT_IDENTITY = Pattern.compile("@[0-9a-fA-F]+"); //$NON-NLS-1$

    /** The real #452 commit failure, verbatim apart from the hash. */
    private static final String PERSIST_FAILURE = "Failed to persist reference value " //$NON-NLS-1$
        + "com._1c.g5.v8.dt.rights.model.impl.RoleDescriptionImpl@3f2a1b"; //$NON-NLS-1$

    /**
     * A {@link Role} stand-in whose {@code rights} reference behaves like a real property: the stub
     * remembers what {@code setRights} was handed and returns it from {@code getRights}. That is what
     * makes the ORDER pin possible - the generator can ask the role what it points at WHILE its FQN is
     * being produced - without a live BM model.
     *
     * <p>It is an {@link IBmObject} as well, because a real role is one: the writer re-fetches it as
     * {@code (Role)tx.getObjectById(bmId)}, and a stand-in that is only a {@code Role} cannot be
     * handed back by that transaction at all.</p>
     */
    private static Role mockRole(String name)
    {
        Role role = mock(Role.class, withSettings().extraInterfaces(IBmObject.class));
        AbstractRoleDescription[] held = new AbstractRoleDescription[1];
        when(role.getName()).thenReturn(name);
        when(role.getRights()).thenAnswer(inv -> held[0]);
        doAnswer(inv ->
        {
            held[0] = inv.getArgument(0);
            return null;
        }).when(role).setRights(any());
        return role;
    }

    /**
     * A description BM has ATTACHED (never transient), which is (or is not) a top object. The
     * transient case is deliberately NOT reachable through this helper: a real detached description
     * answers {@code bmIsTop() == true}, so a mock stubbed {@code false} would pin a value that state
     * never produces - see
     * {@link #testAttachRoleDescriptionReplacesATransientDescriptionBmNeverAttached()}.
     */
    private static RoleDescription mockDescription(boolean top)
    {
        RoleDescription description =
            mock(RoleDescription.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)description).bmIsTransient()).thenReturn(false);
        when(((IBmObject)description).bmIsTop()).thenReturn(top);
        return description;
    }

    @Test
    public void testAttachRoleDescriptionRegistersTheFreshDescriptionUnderTheGeneratedFqn()
    {
        // The whole of issue #452: a role created through create_metadata has no rights model, and a
        // description that is merely REFERENCED is not persistable - the next commit dies with
        // "Failed to persist reference value ...RoleDescriptionImpl@<hash>". The bootstrap must
        // register it as a BM top object in the SAME transaction that sets the reference.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(role, MdClassPackage.Literals.ROLE__RIGHTS))
            .thenReturn("Role.Reader.Rights"); //$NON-NLS-1$

        String fqn = RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertEquals("Role.Reader.Rights", fqn); //$NON-NLS-1$
        assertTrue("the role must end up with a concrete RoleDescription", //$NON-NLS-1$
            role.getRights() instanceof RoleDescription);
        ArgumentCaptor<IBmObject> attached = ArgumentCaptor.forClass(IBmObject.class);
        verify(tx).attachTopObject(attached.capture(), eq("Role.Reader.Rights")); //$NON-NLS-1$
        assertSame("the attached object must be the one the role now references", //$NON-NLS-1$
            role.getRights(), attached.getValue());
    }

    @Test
    public void testAttachRoleDescriptionPointsTheRoleAtTheDescriptionBeforeGeneratingItsFqn()
    {
        // The order is load-bearing, not cosmetic: with the FQN generated BEFORE the reference is set,
        // attachTopObject appears to succeed - no exception, the reference reads back fine - while
        // never durably registering the object under that FQN (the regression XdtoWriter documents).
        // Nothing about the RESULT distinguishes the two orders, so the pin has to observe what the
        // role pointed at at the moment the generator was asked.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        Object[] seenWhenGenerating = new Object[1];
        when(gen.generateExternalPropertyFqn(any(), any())).thenAnswer(inv ->
        {
            seenWhenGenerating[0] = role.getRights();
            return "Role.Reader.Rights"; //$NON-NLS-1$
        });

        RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertTrue("the role must ALREADY point at the fresh description when its FQN is generated, " //$NON-NLS-1$
            + "but it pointed at: " + seenWhenGenerating[0], //$NON-NLS-1$
            seenWhenGenerating[0] instanceof RoleDescription);
    }

    @Test
    public void testAttachRoleDescriptionUndoesTheReferenceWhenTheFqnIsNull()
    {
        // A role whose reference was set but whose attach did not happen must never survive the
        // method: leaving it in place is exactly the unpersistable state #452 reports, only now with
        // the tool claiming nothing went wrong.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn(null);

        assertRefusesAndUndoes(tx, role, gen, "Reader"); //$NON-NLS-1$
    }

    @Test
    public void testAttachRoleDescriptionUndoesTheReferenceWhenTheFqnIsEmpty()
    {
        // An empty FQN is as unusable as a missing one - attaching under "" would register the
        // description where nothing can find it again.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn(""); //$NON-NLS-1$

        assertRefusesAndUndoes(tx, role, gen, "Reader"); //$NON-NLS-1$
    }

    @Test
    public void testAttachRoleDescriptionUndoesTheReferenceWhenTheGeneratorThrows()
    {
        // The md delegate reports an unresolvable owner by THROWING, so a refusal that only inspected
        // the returned value would let the half-built role through.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any()))
            .thenThrow(new IllegalStateException(PERSIST_FAILURE));

        assertRefusesAndUndoes(tx, role, gen, "Reader"); //$NON-NLS-1$
    }

    @Test
    public void testAttachRoleDescriptionRefusalCarriesNoEmfObjectIdentity()
    {
        // The second half of #452: the platform names the failure THROUGH an EMF impl's toString(),
        // and "RoleDescriptionImpl@3f2a1b" tells the caller nothing the simple type name does not.
        // The identity must be scrubbed on its way out while the diagnosis survives.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any()))
            .thenThrow(new IllegalStateException(PERSIST_FAILURE));

        try
        {
            RoleRightsWriter.attachRoleDescription(tx, role, gen);
            fail("a role whose rights FQN cannot be generated must not be reported as written"); //$NON-NLS-1$
        }
        catch (RoleRightsWriter.RoleWriteException e)
        {
            String error = e.getErrorJson();
            assertFalse("the refusal must carry no object identity: " + error, //$NON-NLS-1$
                OBJECT_IDENTITY.matcher(error).find());
            assertTrue("the diagnosis must survive the scrubbing: " + error, //$NON-NLS-1$
                error.contains("RoleDescriptionImpl")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAttachRoleDescriptionReusesAnAlreadyRegisteredDescription()
    {
        // A role that already carries a REGISTERED description must keep it: replacing it would
        // discard the matrix the role already grants. Nothing is created and nothing is attached, but
        // the FQN is still produced, because the caller has to force-export Rights.rights either way.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription existing = mockDescription(true);
        role.setRights(existing);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(role, MdClassPackage.Literals.ROLE__RIGHTS))
            .thenReturn("Role.Reader.Rights"); //$NON-NLS-1$

        String fqn = RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertEquals("Role.Reader.Rights", fqn); //$NON-NLS-1$
        assertSame("the registered description must be kept, not replaced", existing, //$NON-NLS-1$
            role.getRights());
        verify(tx, never()).attachTopObject(any(), any());
    }

    @Test
    public void testAttachRoleDescriptionDegradesToNullWhenTheGeneratorThrowsForARegisteredDescription()
    {
        // Asymmetry with the FRESH branch, and it is the whole point: on the reuse branch NOTHING has
        // been mutated, so a generator failure may cost only the Rights.rights export (rightsFqn ==
        // null -> persisted:false), never the write. The md delegate reports an unresolvable owner by
        // THROWING, and an unconverted throw leaves ensureRoleDescription for apply()'s generic
        // RuntimeException catch, refusing the entire call BEFORE any right is applied - on the path
        // every role that already has a rights model takes.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription existing = mockDescription(true);
        role.setRights(existing);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any()))
            .thenThrow(new IllegalStateException(PERSIST_FAILURE));

        String fqn = RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertNull("an already-registered description whose FQN cannot be produced must degrade to " //$NON-NLS-1$
            + "null, not refuse the write", fqn); //$NON-NLS-1$
        assertSame("the registered description must be kept untouched", existing, role.getRights()); //$NON-NLS-1$
        verify(tx, never()).attachTopObject(any(), any());
    }

    @Test
    public void testAttachRoleDescriptionDegradesToNullWhenTheGeneratorIsEmptyForARegisteredDescription()
    {
        // The same best-effort outcome for the other way the generator can fail to answer: an empty
        // FQN is no FQN, and it must reach the caller as the same null the throw now does.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription existing = mockDescription(true);
        role.setRights(existing);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn(""); //$NON-NLS-1$

        assertNull(RoleRightsWriter.attachRoleDescription(tx, role, gen));
        assertSame("the registered description must be kept untouched", existing, role.getRights()); //$NON-NLS-1$
        verify(tx, never()).attachTopObject(any(), any());
    }

    @Test
    public void testAttachRoleDescriptionReplacesATransientDescriptionBmNeverAttached()
    {
        // The half of the guard a mock cannot pin, and the one #452 actually walks into. Role.rights
        // is a 'refers' (NON-containment) reference, so setRights never gives the description an
        // eContainer - and BmObject.bmIsTop() is 'bmIsTransient() ? eContainer() == null :
        // isFullTopObjectId(id)'. A description that was created and assigned but never attached
        // therefore answers bmIsTop() == TRUE. A guard that trusts bmIsTop() alone keeps it, returns
        // an FQN as if all was well, and the next commit dies with "Failed to persist reference
        // value ...RoleDescriptionImpl@<hash>" all over again. Only a REAL description shows this:
        // stubbing bmIsTop() false on a mock asserts a value this state never produces.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription neverAttached = RightsFactory.eINSTANCE.createRoleDescription();
        assertTrue("precondition: a factory-fresh description has not been attached to BM", //$NON-NLS-1$
            ((IBmObject)neverAttached).bmIsTransient());
        assertTrue("precondition: and it answers bmIsTop() == true all the same, which is why the " //$NON-NLS-1$
            + "guard cannot be bmIsTop() alone", ((IBmObject)neverAttached).bmIsTop()); //$NON-NLS-1$
        role.setRights(neverAttached);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn("Role.Reader.Rights"); //$NON-NLS-1$

        String fqn = RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertEquals("Role.Reader.Rights", fqn); //$NON-NLS-1$
        assertNotSame("a description BM never attached must be replaced by an attached one", //$NON-NLS-1$
            neverAttached, role.getRights());
        verify(tx).attachTopObject(any(), eq("Role.Reader.Rights")); //$NON-NLS-1$
    }

    @Test
    public void testAttachRoleDescriptionReplacesAConcreteDescriptionThatIsNotATopObject()
    {
        // The other half: an ATTACHED description that is not a top object of its own is no more
        // usable as the role's rights model than a transient one, and 'instanceof RoleDescription'
        // alone would keep it.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription unregistered = mockDescription(false);
        role.setRights(unregistered);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn("Role.Reader.Rights"); //$NON-NLS-1$

        String fqn = RoleRightsWriter.attachRoleDescription(tx, role, gen);

        assertEquals("Role.Reader.Rights", fqn); //$NON-NLS-1$
        assertNotSame("an unregistered description must be replaced by an attached one", //$NON-NLS-1$
            unregistered, role.getRights());
        verify(tx).attachTopObject(any(), eq("Role.Reader.Rights")); //$NON-NLS-1$
    }

    @Test
    public void testAttachRoleDescriptionRefusesWhenTheFqnIsAlreadyRegistered()
    {
        // The disk importer registers this same FQN, and stale '.Rights' entries outlive their roles.
        // Adopting the incumbent would silently rewire what the role grants to an object we did not
        // create; detaching it would destroy a matrix we did not author. Refuse, and say what to do.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn("Role.Reader.Rights"); //$NON-NLS-1$
        when(tx.getTopObjectByFqn("Role.Reader.Rights")).thenReturn(mock(IBmObject.class)); //$NON-NLS-1$

        try
        {
            RoleRightsWriter.attachRoleDescription(tx, role, gen);
            fail("a colliding rights FQN must be refused, not attached over"); //$NON-NLS-1$
        }
        catch (RoleRightsWriter.RoleWriteException e)
        {
            assertErrorMentions(e.getErrorJson(), "Role.Reader.Rights"); //$NON-NLS-1$
            assertErrorMentions(e.getErrorJson(), "clean_project"); //$NON-NLS-1$
            // ...but the refusal may claim only what it OBSERVED: that the name is taken. Declaring
            // the registration stale sends the caller to run clean_project - a rebuild - on a premise
            // this call never checked, and a LIVE registration the role's reference has not resolved
            // to yet reaches this same branch, where the rebuild re-registers the same FQN and cannot
            // help. Same defect class as #310 / #412: output reporting facts nobody established.
            assertFalse("the refusal must not assert a cause it never established: " //$NON-NLS-1$
                + e.getErrorJson(), e.getErrorJson().contains("is a stale registration")); //$NON-NLS-1$
            // The OWNER of the incumbent is unestablished in exactly the same way. The only reading
            // taken is getTopObjectByFqn(fqn) != null - the name is taken - which says nothing about
            // WHOSE it is; the likeliest occupant is this role's OWN '.Rights' entry that the
            // transient 'Role.rights' reference has not resolved to, and naming a foreign owner
            // there is a plain falsehood. Pinned separately because a claim removed from one clause
            // comes back in another.
            assertFalse("the refusal must not claim a foreign owner it never observed: " //$NON-NLS-1$
                + e.getErrorJson(), e.getErrorJson().contains("another object")); //$NON-NLS-1$
        }

        assertNull("the role must not keep a reference to a description that was never attached", //$NON-NLS-1$
            role.getRights());
        verify(tx, never()).attachTopObject(any(), any());
    }

    @Test
    public void testAttachRoleDescriptionRestoresAPreviousReferenceWhenTheFqnIsNull()
    {
        // The undo is a RESTORE, not a clear. Every refusal test above starts from a role that held
        // nothing, so there setRights(previous) and setRights(null) are indistinguishable - and a
        // clear would DISCARD the reference the role arrived with on the very path that promises to
        // leave the model untouched.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription unregistered = mockDescription(false);
        role.setRights(unregistered);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn(null);

        assertRefusesAndRestores(tx, role, gen, unregistered);
    }

    @Test
    public void testAttachRoleDescriptionRestoresAPreviousReferenceWhenTheGeneratorThrows()
    {
        // Same contract on the throwing branch: it has its OWN restore, so a mutation there survives
        // any pin placed only on the null-FQN branch.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription unregistered = mockDescription(false);
        role.setRights(unregistered);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any()))
            .thenThrow(new IllegalStateException(PERSIST_FAILURE));

        assertRefusesAndRestores(tx, role, gen, unregistered);
    }

    @Test
    public void testAttachRoleDescriptionRestoresAPreviousReferenceWhenTheFqnIsAlreadyRegistered()
    {
        // The collision refusal is the third restore site, and the one where clearing would be worst:
        // refusing on a stale registration is supposed to change NOTHING, yet a cleared reference
        // would leave the role granting less than it did before the rejected call.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleDescription unregistered = mockDescription(false);
        role.setRights(unregistered);
        IBmTransaction tx = mock(IBmTransaction.class);
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn("Role.Reader.Rights"); //$NON-NLS-1$
        when(tx.getTopObjectByFqn("Role.Reader.Rights")).thenReturn(mock(IBmObject.class)); //$NON-NLS-1$

        assertRefusesAndRestores(tx, role, gen, unregistered);
    }

    // ---- what a refusal reports, driven through the REAL apply --------------------------------
    //
    // These go through RoleRightsWriter.applyResolved with a mocked BM model rather than building a
    // Result by hand. A hand-built DTO pins the constructor and nothing else: deleting the FQN or the
    // write flag from the catch blocks - the only place either value is decided - left such a test
    // green, so it proved that a record can hold two fields, not that the apply fills them in.

    /** The role's bm id in these tests; the mocked transaction answers for exactly this one. */
    private static final long ROLE_BM_ID = 42L;

    @Test
    public void testASuccessfulApplyReportsBothTheFqnAndTheWrite()
    {
        // The baseline the refusal tests are read against: one template applied, the rights model
        // named, the write declared.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleRightsWriter.Result result = applyThroughMockedModel(role, mockDescription(true),
            generator("Role.Reader.Rights"), List.of(templateEntry("add", "T1", "C1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull("nothing in this payload can be refused", result.error); //$NON-NLS-1$
        assertEquals(1, result.templates);
        assertEquals("Role.Reader.Rights", result.rightsFqn); //$NON-NLS-1$
        assertTrue("an applied template is a write and must be declared as one", //$NON-NLS-1$
            result.rightsModelWritten);
    }

    @Test
    public void testARefusalAfterTheBootstrapDeclaresTheWriteTheBootstrapMade()
    {
        // Template resolution genuinely needs RoleDescription, so it still runs after the bootstrap.
        // A missing template therefore refuses AFTER a fresh Rights.rights resource exists in the
        // model. Reporting no write leaves the caller with a resource it neither drains to disk nor
        // declares having written (WriteScope is recorded by the export submission, issue #408) - the
        // same defect shape as #310 / #412, output describing something other than the work that
        // happened.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleRightsWriter.Result result = applyThroughMockedModel(role, null,
            generator("Role.Reader.Rights"), List.of(templateEntry("edit", "Missing", "C1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the result must still be a refusal", result.hasError()); //$NON-NLS-1$
        assertEquals("the refusal must still name the rights model it created", //$NON-NLS-1$
            "Role.Reader.Rights", result.rightsFqn); //$NON-NLS-1$
        assertTrue("the bootstrap attached a fresh rights model: that is a write, and the refusal " //$NON-NLS-1$
            + "owes the caller both its drain and its WriteScope declaration", //$NON-NLS-1$
            result.rightsModelWritten);
    }

    @Test
    public void testAnUnresolvableRightsEntryIsRefusedBeforeTheBootstrapWrites()
    {
        // The whole rights payload must resolve before the bootstrap commits. An unresolvable entry
        // on a fresh role must therefore leave NO Rights.rights model behind - and, above all, must
        // not leave a right granted while the caller sees only a refusal.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        JsonObject missing = rightsEntry("DefinitelyNotAnObjectFqn", "Read", "set"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        RoleRightsWriter.Result result = applyThroughMockedModel(role, null,
            generator("Role.Reader.Rights"), List.of(missing), Collections.emptyList()); //$NON-NLS-1$

        assertTrue("the unresolved object must refuse the call", result.hasError()); //$NON-NLS-1$
        assertErrorMentions(result.error, "DefinitelyNotAnObjectFqn"); //$NON-NLS-1$
        assertFalse("rights resolution failed before the bootstrap, so no rights model was written", //$NON-NLS-1$
            result.rightsModelWritten);
        assertNull("the refused call must not attach a rights model", role.getRights()); //$NON-NLS-1$
        assertEquals(0, result.rights);
    }

    @Test
    public void testARefusalBeforeAnyWriteDeclaresNoWriteEvenThoughItCanNameTheRightsModel()
    {
        // The other half of the separation, and the one a single field cannot express: this call
        // knows the rights model's FQN perfectly well (the role already had a registered one) and
        // still wrote NOTHING, because the very first entry was refused. Gating the export on the FQN
        // made this case export - and declare - a write that never happened.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleRightsWriter.Result result = applyThroughMockedModel(role, mockDescription(true),
            generator("Role.Reader.Rights"), List.of(templateEntry("edit", "Missing", "C1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the result must be a refusal", result.hasError()); //$NON-NLS-1$
        assertEquals("knowing the name is not the same as having written", "Role.Reader.Rights", //$NON-NLS-1$ //$NON-NLS-2$
            result.rightsFqn);
        assertFalse("the bootstrap reused a registered description and the first entry was refused: " //$NON-NLS-1$
            + "nothing committed, so nothing may be declared", result.rightsModelWritten); //$NON-NLS-1$
    }

    @Test
    public void testAFirstEntryThatCommittedIsDeclaredWhenTheSecondIsRefused()
    {
        // The case that was missing entirely. Entries are applied ONE AT A TIME, each through its own
        // BM task, so a batch can commit its way to the middle and then be refused. The role here
        // already has a registered rights model, so the bootstrap writes nothing at all - the ONLY
        // write in this call is the first template, and a flag that were sourced from the bootstrap
        // alone would report false while a committed task sat undrained and undeclared.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        RoleRightsWriter.Result result = applyThroughMockedModel(role, mockDescription(true),
            generator("Role.Reader.Rights"), //$NON-NLS-1$
            List.of(templateEntry("add", "T1", "C1"), templateEntry("edit", "Missing", "C2"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue("the batch must be refused", result.hasError()); //$NON-NLS-1$
        assertTrue("the first template committed before the second was refused: the call wrote", //$NON-NLS-1$
            result.rightsModelWritten);
        assertEquals("the refusal must report the template that really landed", 1, //$NON-NLS-1$
            result.templates);
        JsonObject applied = JsonParser.parseString(result.error).getAsJsonObject()
            .getAsJsonObject("applied"); //$NON-NLS-1$
        assertEquals(0, applied.get("rights").getAsInt()); //$NON-NLS-1$
        assertEquals(1, applied.get("templates").getAsInt()); //$NON-NLS-1$
        assertEquals(0, applied.get("roleProperties").getAsInt()); //$NON-NLS-1$
        assertErrorMentions(result.error, "model was changed"); //$NON-NLS-1$
        assertErrorMentions(result.error, "get_metadata_details"); //$NON-NLS-1$
    }

    @Test
    public void testAGeneratorThatCannotNameARegisteredRightsModelStillDeclaresTheWrite()
    {
        // The exact shape the FQN gate got wrong. On the REUSE branch a generator that throws (the md
        // delegate's way of reporting an unresolvable owner) degrades the FQN to null on purpose -
        // losing the FQN must cost only the Rights.rights leg of the export. But the apply then
        // carries on and its tasks write, so a null FQN here says nothing about whether this call
        // wrote: gated on the FQN, the refusal skipped the drain AND the WriteScope declaration for a
        // call that had committed a template.
        Role role = mockRole("Reader"); //$NON-NLS-1$
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any()))
            .thenThrow(new IllegalStateException(PERSIST_FAILURE));

        RoleRightsWriter.Result result = applyThroughMockedModel(role, mockDescription(true), gen,
            List.of(templateEntry("add", "T1", "C1"), templateEntry("edit", "Missing", "C2"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue("the batch must be refused", result.hasError()); //$NON-NLS-1$
        assertNull("the reuse branch degrades an unproduceable FQN to null, as it must", //$NON-NLS-1$
            result.rightsFqn);
        assertTrue("...and that must not hide the template this call committed", //$NON-NLS-1$
            result.rightsModelWritten);
    }

    /**
     * Drives the REAL apply against a mocked BM model.
     *
     * <p>The writer's own transaction lambdas (the bootstrap, the per-entry resolution) are RUN,
     * against a transaction that answers with {@code role} for {@link #ROLE_BM_ID}. EDT's rights
     * tasks all extend {@link BmBasicTask} and need a live BM engine, so those are stubbed as
     * having COMMITTED - which is precisely the state under test here: what the writer records once
     * a task has come back.</p>
     *
     * @param role the role stand-in
     * @param existing the registered description the role arrives with, or {@code null} to make the
     *     bootstrap attach a fresh one
     * @param fqnGenerator the top-object FQN generator
     * @param templates the {@code templates[]} payload to apply
     * @return the writer's result
     */
    private static RoleRightsWriter.Result applyThroughMockedModel(Role role,
        RoleDescription existing, ITopObjectFqnGenerator fqnGenerator, List<JsonObject> templates)
    {
        return applyThroughMockedModel(role, existing, fqnGenerator, Collections.emptyList(),
            templates);
    }

    /** Five-argument harness overload that also drives a {@code rights[]} payload. */
    private static RoleRightsWriter.Result applyThroughMockedModel(Role role,
        RoleDescription existing, ITopObjectFqnGenerator fqnGenerator, List<JsonObject> rights,
        List<JsonObject> templates)
    {
        if (existing != null)
        {
            when(existing.getTemplates()).thenReturn(new BasicEList<>());
            role.setRights(existing);
        }
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectById(ROLE_BM_ID)).thenReturn((IBmObject)role);
        IBmModel model = mock(IBmModel.class);
        when(model.execute(any())).thenAnswer(inv -> runUnlessPlatformTask(inv.getArgument(0), tx));
        when(model.executeReadonlyTask(any()))
            .thenAnswer(inv -> ((IBmTask<?>)inv.getArgument(0)).execute(tx, new NullProgressMonitor()));

        RoleRightsWriter.Context ctx = new RoleRightsWriter.Context(mock(IProject.class),
            mock(Configuration.class), model, role, ROLE_BM_ID, mock(IRightInfosService.class),
            mock(IEventBroker.class), mock(IModelObjectCollectionRuntimeOrderSorter.class));
        return RoleRightsWriter.applyResolved(ctx, fqnGenerator, rights, templates, null);
    }

    /** Runs the writer's own task; reports an EDT rights task as having committed. */
    private static Object runUnlessPlatformTask(IBmTask<?> task, IBmTransaction tx)
    {
        return task instanceof BmBasicTask ? null : task.execute(tx, new NullProgressMonitor());
    }

    /** A generator that answers with one FQN for the role's rights property. */
    private static ITopObjectFqnGenerator generator(String fqn)
    {
        ITopObjectFqnGenerator gen = mock(ITopObjectFqnGenerator.class);
        when(gen.generateExternalPropertyFqn(any(), any())).thenReturn(fqn);
        return gen;
    }

    /** One {@code templates[]} entry. */
    private static JsonObject templateEntry(String op, String name, String condition)
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("op", op); //$NON-NLS-1$
        entry.addProperty("name", name); //$NON-NLS-1$
        entry.addProperty("condition", condition); //$NON-NLS-1$
        return entry;
    }

    /** One {@code rights[]} entry. */
    private static JsonObject rightsEntry(String object, String right, String value)
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("object", object); //$NON-NLS-1$
        entry.addProperty("right", right); //$NON-NLS-1$
        entry.addProperty("value", value); //$NON-NLS-1$
        return entry;
    }

    @Test
    public void testPartialApplicationReportAddsCountsAndReconciliationMessage()
    {
        String error = ToolResult.error("The last operation failed.").toJson(); //$NON-NLS-1$

        String reported = RoleRightsWriter.reportPartialApplication(error, 2, 1, 3);
        JsonObject object = JsonParser.parseString(reported).getAsJsonObject();
        JsonObject applied = object.getAsJsonObject("applied"); //$NON-NLS-1$
        assertEquals(2, applied.get("rights").getAsInt()); //$NON-NLS-1$
        assertEquals(1, applied.get("templates").getAsInt()); //$NON-NLS-1$
        assertEquals(3, applied.get("roleProperties").getAsInt()); //$NON-NLS-1$
        assertErrorMentions(reported, "model was changed"); //$NON-NLS-1$
        assertErrorMentions(reported, "get_metadata_details"); //$NON-NLS-1$
        assertErrorMentions(reported, "undo or complete"); //$NON-NLS-1$
    }

    @Test
    public void testPartialApplicationReportPassesThroughWhenNothingApplied()
    {
        String error = "{ \"success\": false, \"error\": \"unchanged formatting\" }"; //$NON-NLS-1$

        assertSame("a zero-count refusal must pass through byte-for-byte", error, //$NON-NLS-1$
            RoleRightsWriter.reportPartialApplication(error, 0, 0, 0));
    }

    @Test
    public void testPartialApplicationReportPassesThroughSuccessJson()
    {
        String success = "{ \"success\": true, \"message\": \"unchanged formatting\" }"; //$NON-NLS-1$

        assertSame("a success is not an eligible error shape", success, //$NON-NLS-1$
            RoleRightsWriter.reportPartialApplication(success, 1, 0, 0));
    }

    @Test
    public void testPartialApplicationReportPassesThroughMalformedJson()
    {
        String malformed = "{definitely not json"; //$NON-NLS-1$

        assertSame("malformed input must pass through byte-for-byte", malformed, //$NON-NLS-1$
            RoleRightsWriter.reportPartialApplication(malformed, 1, 0, 0));
    }

    @Test
    public void testApplyFailureCarriesNoEmfObjectIdentity()
    {
        // The catch-all of apply() is the one place the #452 commit failure reaches a caller, and the
        // platform names that failure THROUGH an EMF impl's toString(). apply() resolves EDT services
        // and cannot run headless, so the message is built by a pure helper - built inline it would be
        // pinned by nothing, and deleting the scrubber would put the heap address back on the wire
        // with the whole suite still green.
        String error =
            RoleRightsWriter.applyFailure("Reader", new IllegalStateException(PERSIST_FAILURE)); //$NON-NLS-1$

        assertFalse("the catch-all must carry no object identity: " + error, //$NON-NLS-1$
            OBJECT_IDENTITY.matcher(error).find());
    }

    @Test
    public void testApplyFailureKeepsTheDiagnosisTheIdentityWasAttachedTo()
    {
        // Its own test, not a second assertion on the one above: JUnit stops a method at its first
        // failed assertion, so a pin sharing a method with another is only loaded while that one
        // passes. WHICH kind of object the platform refused is the entire diagnosis, and a pin on the
        // absence of the hash alone would be satisfied by dropping the platform text altogether.
        String error =
            RoleRightsWriter.applyFailure("Reader", new IllegalStateException(PERSIST_FAILURE)); //$NON-NLS-1$

        assertTrue("the diagnosis must survive the scrubbing: " + error, //$NON-NLS-1$
            error.contains("RoleDescriptionImpl")); //$NON-NLS-1$
    }

    @Test
    public void testApplyFailureNamesTheRoleAndTheNextAction()
    {
        // The apply is best-effort and non-atomic, so a failure mid-batch leaves earlier entries
        // applied: the refusal has to say WHICH role it is about and what to do next, or the caller
        // cannot tell what state the role is in.
        String error =
            RoleRightsWriter.applyFailure("Reader", new IllegalStateException(PERSIST_FAILURE)); //$NON-NLS-1$

        assertErrorMentions(error, "Reader"); //$NON-NLS-1$
        assertErrorMentions(error, "get_metadata_details"); //$NON-NLS-1$
        assertErrorMentions(error, "clean_project"); //$NON-NLS-1$
    }

    /**
     * Pins the whole undo contract for a bootstrap that could not produce an FQN: the call is refused
     * naming the role, the role keeps NO reference to the description that was never attached, and
     * {@code attachTopObject} was not reached.
     */
    private static void assertRefusesAndUndoes(IBmTransaction tx, Role role,
        ITopObjectFqnGenerator gen, String roleName)
    {
        try
        {
            RoleRightsWriter.attachRoleDescription(tx, role, gen);
            fail("a role whose rights FQN cannot be generated must not be reported as written"); //$NON-NLS-1$
        }
        catch (RoleRightsWriter.RoleWriteException e)
        {
            assertErrorMentions(e.getErrorJson(), roleName);
        }

        assertNull("the role must not keep a reference to a description that was never attached", //$NON-NLS-1$
            role.getRights());
        verify(tx, never()).attachTopObject(any(), any());
    }

    /**
     * Pins the other half of the undo contract: the refusal must put BACK the reference the role
     * arrived with. The role here already points at an unregistered description, so
     * {@code setRights(previous)} and {@code setRights(null)} are finally distinguishable - and only
     * the first one leaves the model as the refused call found it.
     */
    private static void assertRefusesAndRestores(IBmTransaction tx, Role role,
        ITopObjectFqnGenerator gen, AbstractRoleDescription previous)
    {
        try
        {
            RoleRightsWriter.attachRoleDescription(tx, role, gen);
            fail("a bootstrap that could not register its description " //$NON-NLS-1$
                + "must not be reported as written"); //$NON-NLS-1$
        }
        catch (RoleRightsWriter.RoleWriteException e)
        {
            assertErrorMentions(e.getErrorJson(), "Reader"); //$NON-NLS-1$
        }

        assertSame("the previous reference must be restored, not cleared", previous, //$NON-NLS-1$
            role.getRights());
        verify(tx, never()).attachTopObject(any(), any());
    }

    private static void assertErrorMentions(String errorJson, String needle)
    {
        assertTrue("expected an error", errorJson != null); //$NON-NLS-1$
        assertTrue("error should mention '" + needle + "': " + errorJson, //$NON-NLS-1$ //$NON-NLS-2$
            errorJson.contains(needle));
    }
}
