/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the refusals of {@code modify_metadata} on the configuration ROOT (issue #741): only
 * {@code properties} is accepted there, only the nine settable properties, and the batch is judged as a
 * whole before anything is written - one closed property refuses every entry, as the generic path does -
 * and the dispatch hands the root to the write only after all of that was judged.
 */
public class ModifyMetadataConfigurationRootTest
{
    /** The nine settable properties, spelled independently of production, in the guide's order. */
    private static final List<String> NINE = Arrays.asList("synonym", "comment", "version", "vendor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "copyright", "briefInformation", "detailedInformation", "vendorInformationAddress", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "configurationInformationAddress"); //$NON-NLS-1$

    private static List<JsonObject> props(String... json)
    {
        List<JsonObject> list = new ArrayList<>();
        for (String entry : json)
        {
            list.add(JsonParser.parseString(entry).getAsJsonObject());
        }
        return list;
    }

    @Test
    public void testTheSettablePropertiesAreAcceptedInAnyCase()
    {
        assertNull(ModifyMetadataTool.configurationRootPropertiesError(props(
            "{name:'version', value:'1.0.17.14'}", "{name:'Synonym', value:'S', language:'en'}", //$NON-NLS-1$ //$NON-NLS-2$
            "{name:'COPYRIGHT', value:'C'}", "{name:'vendorInformationAddress', value:'https://v'}"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABehaviourSettingIsRefusedWithTheSettableList()
    {
        String refusal = ModifyMetadataTool.configurationRootPropertiesError(props(
            "{name:'compatibilityMode', value:'8.3.27'}")); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'compatibilityMode'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("configuration root")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("version")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("briefInformation")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'Configuration'")); //$NON-NLS-1$
    }

    @Test
    public void testTheNameIsRefusedWithoutARenamePointer()
    {
        String refusal = ModifyMetadataTool.configurationRootPropertiesError(props("{name:'name', value:'X'}")); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("'name' is not settable on the configuration root")); //$NON-NLS-1$
        assertFalse("the root has no cascading rename: " + refusal, refusal.contains("rename_metadata_object")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnExtensionsOwnSettingsAreRefusedByName()
    {
        for (String name : new String[] { "keepMappingToExtendedConfigurationObjectsByIDs", "namePrefix", //$NON-NLS-1$ //$NON-NLS-2$
            "configurationExtensionPurpose", "configurationExtensionCompatibilityMode" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String refusal = ModifyMetadataTool.configurationRootPropertiesError(
                props("{name:'" + name + "', value:'x'}")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(name, refusal);
            assertTrue(refusal, refusal.startsWith("'" + name + "' is not settable")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testOneClosedEntryRefusesTheWholeBatch()
    {
        String refusal = ModifyMetadataTool.configurationRootPropertiesError(props(
            "{name:'version', value:'1.0'}", "{name:'scriptVariant', value:'English'}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a settable first entry must not let the closed second one through", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'scriptVariant' is not settable")); //$NON-NLS-1$
    }

    @Test
    public void testAnEntryWithoutANameIsRefused()
    {
        for (String entry : new String[] { "{value:'x'}", "{name:'', value:'x'}", "{name:null, value:'x'}" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String refusal = ModifyMetadataTool.configurationRootPropertiesError(props(entry));
            assertNotNull(entry, refusal);
            assertTrue(refusal, refusal.contains("non-empty 'name'")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOnlyPropertiesAreAcceptedOnTheRoot()
    {
        assertNull(ModifyMetadataTool.configurationRootPayloadError(false, false, false));
        String role = ModifyMetadataTool.configurationRootPayloadError(true, false, false);
        assertNotNull(role);
        assertTrue(role, role.contains("'rights'")); //$NON-NLS-1$
        assertTrue(role, role.contains("accepts only 'properties'")); //$NON-NLS-1$
        String content = ModifyMetadataTool.configurationRootPayloadError(false, true, false);
        assertNotNull(content);
        assertTrue(content, content.contains("'content'")); //$NON-NLS-1$
        String template = ModifyMetadataTool.configurationRootPayloadError(false, false, true);
        assertNotNull(template);
        assertTrue(template, template.contains("'template'")); //$NON-NLS-1$
        for (String refusal : new String[] { role, content, template })
        {
            assertTrue(refusal, refusal.startsWith("'Configuration' (the configuration root) accepts only " //$NON-NLS-1$
                + "'properties'")); //$NON-NLS-1$
        }
    }

    /**
     * The order the dispatch judges a root call in: the address first (another configuration's Name, an
     * external-objects project), then the payload, then the properties - every refusal before anything is
     * written, and the first one that applies is the answer.
     */
    @Test
    public void testTheRefusalJudgesTheAddressThenThePayloadThenTheProperties()
    {
        List<JsonObject> closed = props("{name:'scriptVariant', value:'English'}"); //$NON-NLS-1$
        List<JsonObject> settable = props("{name:'version', value:'1.0'}"); //$NON-NLS-1$

        assertEquals("the address error", //$NON-NLS-1$
            ModifyMetadataTool.configurationRootRefusal("the address error", true, true, true, closed)); //$NON-NLS-1$
        String payload = ModifyMetadataTool.configurationRootRefusal(null, false, true, false, closed);
        assertNotNull(payload);
        assertTrue(payload, payload.contains("'content'")); //$NON-NLS-1$
        String property = ModifyMetadataTool.configurationRootRefusal(null, false, false, false, closed);
        assertNotNull(property);
        assertTrue(property, property.startsWith("'scriptVariant' is not settable")); //$NON-NLS-1$
        assertNull(ModifyMetadataTool.configurationRootRefusal(null, false, false, false, settable));
        assertNull("a call with no properties at all is refused before the dispatch (properties is required)", //$NON-NLS-1$
            ModifyMetadataTool.configurationRootRefusal(null, false, false, false, Collections.emptyList()));
    }

    /**
     * The guide is where an agent learns the address and the surface. Its enumerating sentence lists
     * exactly the nine - built here from a list spelled independently of production, so a tenth property
     * in the guide, or one missing, changes the sentence - and says they are a SUBSET of what EDT's own
     * editor offers on an extension root (the editor's others stay closed). It names what stays closed,
     * and claims no more for get_configuration_properties than that tool reads.
     */
    @Test
    public void testTheGuideDocumentsTheRootAndExactlyItsSettableProperties()
    {
        String guide = new ModifyMetadataTool().getGuide();
        assertTrue(guide, guide.contains("## Configuration root")); //$NON-NLS-1$
        String listed = "`" + String.join("`, `", NINE.subList(0, NINE.size() - 1)) + "` and `" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + NINE.get(NINE.size() - 1) + "`"; //$NON-NLS-1$
        assertTrue(guide, guide.contains("Only `properties` is accepted there, and only these nine: " + listed //$NON-NLS-1$
            + " - nine of the properties EDT's own editor lets you edit on an extension root (the others stay " //$NON-NLS-1$
            + "closed, see below), and an extension root keeps all nine on disk.")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("`get_configuration_properties` also reads the root's name, behaviour " //$NON-NLS-1$
            + "settings and languages.")); //$NON-NLS-1$
        assertFalse("get_configuration_properties does not read every other property of the root: " + guide, //$NON-NLS-1$
            guide.contains("reads the rest")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("`keepMappingToExtendedConfigurationObjectsByIDs`")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("fqn:'Configuration', properties:[{name:'version'")); //$NON-NLS-1$
    }

    /** What the dispatch handed to the write; it must hand the root over only once nothing was refused. */
    private static final class RecordingWrite implements Function<MetadataNodeResolver.MetadataNode, String>
    {
        final List<MetadataNodeResolver.MetadataNode> written = new ArrayList<>();

        @Override
        public String apply(MetadataNodeResolver.MetadataNode node)
        {
            written.add(node);
            return "WRITTEN"; //$NON-NLS-1$
        }
    }

    /** The error text of a refusal the dispatch returned as ready JSON. */
    private static String errorOf(String json)
    {
        assertNotNull("a refusal was expected", json); //$NON-NLS-1$
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(json, result.get("success").getAsBoolean()); //$NON-NLS-1$
        return result.get("error").getAsString(); //$NON-NLS-1$
    }

    /**
     * The wiring the allow-list depends on. The dispatch hands the root to the generic property path,
     * which can set ANY assignable feature of the root - its object collections included - so it may do
     * so only after the address, the payload and every property were judged: a closed property (even
     * behind a settable one), another payload, another configuration's Name and an external-objects
     * project never reach the write. A settable batch does, with the root as a top-level node.
     */
    @Test
    public void testNothingReachesTheWriteBeforeEveryRefusalWasJudged()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        RecordingWrite write = new RecordingWrite();
        List<JsonObject> settable = props("{name:'version', value:'1.0'}"); //$NON-NLS-1$

        String closed = errorOf(ModifyMetadataTool.dispatchConfigurationRoot(scope, "Configuration", false, //$NON-NLS-1$
            false, false, props("{name:'version', value:'1.0'}", "{name:'catalogs', value:[]}"), write)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(closed, closed.startsWith("'catalogs' is not settable on the configuration root")); //$NON-NLS-1$
        String payload = errorOf(ModifyMetadataTool.dispatchConfigurationRoot(scope, "Configuration", false, //$NON-NLS-1$
            true, false, settable, write));
        assertTrue(payload, payload.contains("accepts only 'properties'")); //$NON-NLS-1$
        String otherName = errorOf(ModifyMetadataTool.dispatchConfigurationRoot(scope, "Configuration.Other", //$NON-NLS-1$
            false, false, false, settable, write));
        assertTrue(otherName, otherName.contains("'Cfg'")); //$NON-NLS-1$
        String external = errorOf(ModifyMetadataTool.dispatchConfigurationRoot(
            MetadataScopeTestFixtures.externalObjectsWithBase(config), "Configuration", false, false, false, //$NON-NLS-1$
            settable, write));
        assertTrue(external, external.contains("external-objects")); //$NON-NLS-1$
        assertTrue("nothing refused may reach the write: " + write.written, write.written.isEmpty()); //$NON-NLS-1$

        assertNull("not a root address: the caller goes on down the normal path", //$NON-NLS-1$
            ModifyMetadataTool.dispatchConfigurationRoot(scope, "Catalog.Goods", false, false, false, settable, //$NON-NLS-1$
                write));
        assertTrue(write.written.toString(), write.written.isEmpty());

        assertEquals("WRITTEN", ModifyMetadataTool.dispatchConfigurationRoot(scope, "Configuration.Cfg", false, //$NON-NLS-1$ //$NON-NLS-2$
            false, false, props("{name:'version', value:'1.0'}", "{name:'Synonym', value:'S', language:'en'}"), //$NON-NLS-1$ //$NON-NLS-2$
            write));
        assertEquals(1, write.written.size());
        assertSame(config, write.written.get(0).object);
        assertTrue("the root is written as a top-level node", write.written.get(0).topLevel); //$NON-NLS-1$
    }

    /** The "Node not found" refusal names the root's own address among the address forms. */
    @Test
    public void testNodeNotFoundNamesTheRootAddress()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        String message = ModifyMetadataTool.nodeNotFoundMessage(MetadataScope.ofConfiguration(config),
            "Catalog.Missing", "Catalog.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.startsWith("Node not found: Catalog.Missing. Use 'Type.Name' for a top " //$NON-NLS-1$
            + "object, 'Type.Name.Kind.Name' for a member, or 'Configuration' for the configuration root. Use " //$NON-NLS-1$
            + "get_metadata_objects to find an FQN.")); //$NON-NLS-1$
    }
}
