/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Ratchet for the bilingual (RU/EN) settings/tags UI: every English {@code .properties}
 * bundle and its {@code _ru} sibling must carry the SAME key set — no missing translation
 * and no orphan key — across all four externalization channels the feature uses:
 *
 * <ul>
 *   <li>the preferences NLS bundle ({@code preferences/messages.properties} — the settings form);</li>
 *   <li>the tags-UI NLS bundle ({@code tags/ui/messages.properties} — the tag dialogs);</li>
 *   <li>the History-view NLS bundle ({@code ui/messages.properties} — the MCP History view);</li>
 *   <li>the {@code plugin.xml} {@code %key} localization ({@code plugin.properties} — registry names).</li>
 * </ul>
 *
 * <p>The test loads the REAL packaged resources from the bundle (never an inline copy): if a
 * {@code _ru} file is absent or was not packaged (e.g. left out of {@code build.properties}
 * {@code bin.includes}), the resource resolves to {@code null} and the test fails — so it also
 * guards the packaging of the new files. Each pair is read with {@link Properties#load(InputStream)},
 * which decodes ISO-8859-1 and unescapes {@code \\uXXXX} — exactly the loader the plugin's Eclipse
 * NLS ({@code org.eclipse.osgi.util.NLS}) and the {@code plugin.xml} {@code %}-localization use at
 * runtime — so the parsed key set mirrors what actually ships.</p>
 *
 * <p>The final case additionally proves {@code preferences.Messages} initializes cleanly: every
 * declared public static String field resolves to a real message (non-null and not the Eclipse NLS
 * "missing message" placeholder).</p>
 */
public class MessagesParityTest
{
    /** Bundle-root-relative paths of the paired English / Russian {@code .properties}. */
    private static final String PREFS_EN = "com/ditrix/edt/mcp/server/preferences/messages.properties"; //$NON-NLS-1$
    private static final String PREFS_RU = "com/ditrix/edt/mcp/server/preferences/messages_ru.properties"; //$NON-NLS-1$
    private static final String TAGS_EN = "com/ditrix/edt/mcp/server/tags/ui/messages.properties"; //$NON-NLS-1$
    private static final String TAGS_RU = "com/ditrix/edt/mcp/server/tags/ui/messages_ru.properties"; //$NON-NLS-1$
    private static final String HISTORY_EN = "com/ditrix/edt/mcp/server/ui/messages.properties"; //$NON-NLS-1$
    private static final String HISTORY_RU = "com/ditrix/edt/mcp/server/ui/messages_ru.properties"; //$NON-NLS-1$
    private static final String PLUGIN_EN = "plugin.properties"; //$NON-NLS-1$
    private static final String PLUGIN_RU = "plugin_ru.properties"; //$NON-NLS-1$

    /** The Eclipse NLS placeholder assigned to a Messages field that has no key in the .properties. */
    private static final String NLS_MISSING_PREFIX = "NLS missing message:"; //$NON-NLS-1$

    /** The settings form's NLS bundle: English keys == Russian keys. */
    @Test
    public void testPreferencesMessagesKeyParity() throws IOException
    {
        assertKeyParity(PREFS_EN, PREFS_RU);
    }

    /** The tag dialogs' NLS bundle: English keys == Russian keys. */
    @Test
    public void testTagsUiMessagesKeyParity() throws IOException
    {
        assertKeyParity(TAGS_EN, TAGS_RU);
    }

    /** The MCP History view's NLS bundle: English keys == Russian keys. */
    @Test
    public void testHistoryViewMessagesKeyParity() throws IOException
    {
        assertKeyParity(HISTORY_EN, HISTORY_RU);
    }

    /** The plugin.xml %-localization: English keys == Russian keys. */
    @Test
    public void testPluginPropertiesKeyParity() throws IOException
    {
        assertKeyParity(PLUGIN_EN, PLUGIN_RU);
    }

    /**
     * Every {@code public static String} field of {@code preferences.Messages} resolves after class
     * init: non-null and not the Eclipse NLS "missing message" placeholder (which would mean the
     * field is declared but absent from {@code messages.properties}).
     */
    /** The NLS Messages classes whose fields must all resolve (both bundles this feature ships). */
    private static final String[] MESSAGES_CLASSES = {
        "com.ditrix.edt.mcp.server.preferences.Messages", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.tags.ui.Messages", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.ui.Messages" //$NON-NLS-1$
    };

    @Test
    public void testMessagesFieldsResolve() throws ReflectiveOperationException
    {
        for (String className : MESSAGES_CLASSES)
        {
            assertMessagesClassResolves(className);
        }
    }

    /**
     * Every {@code public static String} field of the given NLS Messages class resolves after class init:
     * non-null and not the Eclipse NLS "missing message" placeholder. A placeholder means the field is
     * declared but has no key in the bundle's {@code messages.properties} — a runtime
     * {@code MissingResourceException} the key-parity tests cannot catch, since a key absent from BOTH the
     * EN and RU files still passes parity. The larger, heavily-edited {@code tags.ui.Messages} bundle gets
     * the same guard as {@code preferences.Messages}.
     */
    private static void assertMessagesClassResolves(String className) throws ReflectiveOperationException
    {
        // initialize=true runs the NLS static block, so a field with no key resolves to the placeholder.
        Class<?> messages = Class.forName(className);

        List<String> problems = new ArrayList<>();
        int fieldCount = 0;
        for (Field field : messages.getDeclaredFields())
        {
            int mods = field.getModifiers();
            if (!(Modifier.isPublic(mods) && Modifier.isStatic(mods) && field.getType() == String.class))
            {
                continue;
            }
            fieldCount++;
            Object value = field.get(null);
            if (value == null)
            {
                problems.add(field.getName() + " = null"); //$NON-NLS-1$
            }
            else if (((String)value).startsWith(NLS_MISSING_PREFIX))
            {
                problems.add(field.getName() + " -> " + value); //$NON-NLS-1$
            }
        }
        assertTrue(className + " must declare at least one NLS string field", fieldCount > 0); //$NON-NLS-1$
        assertTrue(className + " fields that did not resolve to a message: " + problems, //$NON-NLS-1$
            problems.isEmpty());
    }

    /**
     * The encoding invariant this change's design calls its top risk: every {@code _ru} file must be
     * pure ASCII {@code \\uXXXX}-escaped (no byte {@code >= 0x80}) and BOM-free. Both the plugin's
     * Eclipse NLS and the {@code plugin.xml} {@code %}-localization decode {@code .properties} as
     * ISO-8859-1 in this EDT, so raw UTF-8 Cyrillic (or an editor re-saving the file as UTF-8 / adding
     * a BOM) parses into valid keys — passing the parity check above — yet MOJIBAKES at runtime. This
     * ratchets the actual on-disk byte contract so such a regression fails the build instead of
     * shipping garbled Russian text.
     */
    @Test
    public void testRussianFilesAreAsciiEscapedAndBomFree() throws IOException
    {
        for (String ruPath : new String[] {PLUGIN_RU, PREFS_RU, TAGS_RU, HISTORY_RU})
        {
            URL url = resolve(ruPath);
            assertNotNull("packaged Russian resource not found (missing file, or not in " //$NON-NLS-1$
                + "build.properties bin.includes / not copied from src): " + ruPath, url); //$NON-NLS-1$

            byte[] bytes;
            try (InputStream in = url.openStream())
            {
                bytes = readAll(in);
            }

            assertFalse("Russian .properties starts with a UTF-8 BOM (0xEF 0xBB 0xBF) - the plugin " //$NON-NLS-1$
                + "reads it as ISO-8859-1, so the BOM mojibakes at runtime: " + ruPath, //$NON-NLS-1$
                bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF);

            List<String> highBytes = new ArrayList<>();
            for (int i = 0; i < bytes.length && highBytes.size() < 8; i++)
            {
                if ((bytes[i] & 0x80) != 0)
                {
                    highBytes.add(String.format("offset %d = 0x%02X", i, bytes[i] & 0xFF)); //$NON-NLS-1$
                }
            }
            assertTrue("Russian .properties contains a non-ASCII byte (>= 0x80): it must be pure " //$NON-NLS-1$
                + "ASCII with \\uXXXX escapes, because the plugin decodes it as ISO-8859-1 and raw " //$NON-NLS-1$
                + "UTF-8 Cyrillic mojibakes at runtime: " + ruPath + " -> " + highBytes, //$NON-NLS-1$ //$NON-NLS-2$
                highBytes.isEmpty());
        }
    }

    /** Loads both files and asserts their key sets are identical (no missing, no orphan). */
    private static void assertKeyParity(String enPath, String ruPath) throws IOException
    {
        Properties en = loadProperties(enPath);
        Properties ru = loadProperties(ruPath);

        Set<String> enKeys = new TreeSet<>(en.stringPropertyNames());
        Set<String> ruKeys = new TreeSet<>(ru.stringPropertyNames());

        assertFalse("no keys loaded from " + enPath + " - the parity check would be vacuous", //$NON-NLS-1$ //$NON-NLS-2$
            enKeys.isEmpty());

        Set<String> missing = new TreeSet<>(enKeys);
        missing.removeAll(ruKeys);
        Set<String> orphan = new TreeSet<>(ruKeys);
        orphan.removeAll(enKeys);

        assertTrue("keys missing from " + ruPath + " (present in " + enPath + "): " + missing //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "; orphan keys in " + ruPath + " (absent from " + enPath + "): " + orphan, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            missing.isEmpty() && orphan.isEmpty());
    }

    /**
     * Reads a packaged bundle resource as {@link Properties}, tolerating a leading UTF-8 BOM (the
     * existing {@code tags/ui/messages.properties} carries one on its comment line) and using the
     * plugin's real runtime decoding (ISO-8859-1 + {@code \\uXXXX} unescape).
     */
    private static Properties loadProperties(String bundlePath) throws IOException
    {
        URL url = resolve(bundlePath);
        assertNotNull("packaged resource not found (missing file, or not in build.properties " //$NON-NLS-1$
            + "bin.includes / not copied from src): " + bundlePath, url); //$NON-NLS-1$

        byte[] bytes;
        try (InputStream in = url.openStream())
        {
            bytes = readAll(in);
        }
        // Skip a UTF-8 BOM: it precedes a comment line, so it is not a real key, but decoded as
        // ISO-8859-1 it would otherwise surface as a spurious key and break parity.
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF)
        {
            offset = 3;
        }
        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes, offset, bytes.length - offset))
        {
            props.load(in);
        }
        return props;
    }

    /**
     * Resolves a bundle-root-relative resource: the host bundle entry first (the OSGi runtime, where
     * {@code bin.includes} root files like {@code plugin.properties} live), then the class loader as
     * a plain-classpath fallback. Mirrors the resolution GuideLoader uses for bundled resources.
     */
    private static URL resolve(String bundlePath)
    {
        Bundle bundle = FrameworkUtil.getBundle(MessagesParityTest.class);
        if (bundle != null)
        {
            URL url = bundle.getEntry(bundlePath);
            if (url != null)
            {
                return url;
            }
        }
        return MessagesParityTest.class.getResource("/" + bundlePath); //$NON-NLS-1$
    }

    private static byte[] readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1)
        {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
