/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.AutoColor;
import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.mcore.StyleFont;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Builds and renders the value of a plain contained mcore {@link Color} or {@link Font} property - a
 * form item's {@code textColor}, {@code titleFont}, ... (issue #660) - in the EDT designer's grammar:
 *
 * <ul>
 * <li><b>Color</b> {@code {color:{red,green,blue}}} (a {@link ColorDef}); {@code {color:'auto'}} (clears
 * the property, which the platform reads as the automatic color - the designer's Clear); a NAMED color
 * {@code {color:'<Source>.<Name>'}} or {@code {color:{<source>:'<Name>'}}} (a {@link ColorRef}), where
 * the source is {@code Style} (a configuration style item, else a platform style color),
 * {@code Palette} (a configuration palette color, else a platform palette color), {@code Web} or
 * {@code Windows} (platform colors).</li>
 * <li><b>Font</b> an absolute {@code {font:{faceName?,height?,bold?,italic?,underline?,strikeout?,scale?}}}
 * (a {@link FontDef}); {@code {font:'auto'}} (clears); a font REFERENCE {@code {font:'<Source>.<Name>'}}
 * or {@code {font:{<source>:'<Name>', <overrides>?}}} (a {@link FontRef}) where the source is
 * {@code Style} (a configuration style item, else a platform style font) or {@code System} (a
 * platform system font), and each override present is stored as SET - an absent one stays inherited
 * from the referenced font, as in the designer.</li>
 * </ul>
 *
 * <p>Platform names come from the versioned {@link IEObjectProvider} catalogue, which registers each
 * colour and font under its English name only ({@code Style.FormBackColor}, {@code Web.AliceBlue},
 * {@code System.DefaultGUIFont}); the value stored is the catalogue's own proxy, the same thing EDT's
 * form generator stores. A configuration item is referenced through its inferred appearance item; the
 * caller re-binds that reference inside its write transaction ({@link #rebind}).</p>
 *
 * <p>The shared {@link StyleValueBuilder#build} is reused only for the shapes that coincide (an RGB
 * colour and an absolute font); its behaviour for a StyleItem value and DCS is unchanged.</p>
 */
public final class AppearanceValueBuilder
{
    /** The wrapper member of a colour value. */
    public static final String COLOR = "color"; //$NON-NLS-1$

    /** The wrapper member of a font value. */
    public static final String FONT = "font"; //$NON-NLS-1$

    /** The accepted colour shapes in one line, for the assignable listing. */
    public static final String COLOR_SHAPES = "{color:{red,green,blue}} / {color:'auto'} / " //$NON-NLS-1$
        + "{color:'Style.<Name>'} (or Palette., Web., Windows.)"; //$NON-NLS-1$

    /** The accepted font shapes in one line, for the assignable listing. */
    public static final String FONT_SHAPES = "{font:{faceName?,height?,bold?,italic?,underline?," //$NON-NLS-1$
        + "strikeout?,scale?}} / {font:'auto'} / {font:{style:'<Name>', <overrides>?}} (or system:)"; //$NON-NLS-1$

    private static final String AUTO = "auto"; //$NON-NLS-1$

    private static final String STYLE = "Style"; //$NON-NLS-1$

    private static final String PALETTE = "Palette"; //$NON-NLS-1$

    private static final String APPEARANCE_ITEM = "appearanceItem"; //$NON-NLS-1$

    private static final String FACE_NAME = "faceName"; //$NON-NLS-1$

    private static final String HEIGHT = "height"; //$NON-NLS-1$

    private static final String SCALE = "scale"; //$NON-NLS-1$

    private static final List<String> FONT_FLAGS =
        List.of("bold", "italic", "underline", "strikeout"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The order the overrides of a font are rendered and validated in. */
    private static final List<String> FONT_OVERRIDES = List.of(FACE_NAME, HEIGHT, "bold", "italic", //$NON-NLS-1$ //$NON-NLS-2$
        "underline", "strikeout", SCALE); //$NON-NLS-1$ //$NON-NLS-2$

    /** Colour sources: the object-form member and the catalogue prefix it stands for. */
    private static final Map<String, String> COLOR_SOURCES = Map.of("style", STYLE, "palette", PALETTE, //$NON-NLS-1$ //$NON-NLS-2$
        "web", "Web", "windows", "Windows"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Font sources: the object-form member and the catalogue prefix it stands for. */
    private static final Map<String, String> FONT_SOURCES = Map.of("style", STYLE, "system", "System"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String COLOR_FORMS = "Use {color:{red:0-255, green:0-255, blue:0-255}}, " //$NON-NLS-1$
        + "{color:'auto'}, or a named color {color:'<Source>.<Name>'} / {color:{<source>:'<Name>'}} " //$NON-NLS-1$
        + "with source Style (a configuration style item or a platform style color), Palette, Web " //$NON-NLS-1$
        + "or Windows - e.g. {color:'Web.AliceBlue'} or {color:{style:'FormBackColor'}}."; //$NON-NLS-1$

    private static final String FONT_FORMS = "Use an absolute font {font:{faceName?, height?, " //$NON-NLS-1$
        + "bold?, italic?, underline?, strikeout?, scale?}}, {font:'auto'}, or a font reference " //$NON-NLS-1$
        + "{font:'<Source>.<Name>'} / {font:{<source>:'<Name>', <overrides>?}} with source Style " //$NON-NLS-1$
        + "(a configuration style item or a platform style font) or System - e.g. " //$NON-NLS-1$
        + "{font:{style:'NormalTextFont', bold:true}} or {font:'System.DefaultGUIFont'}."; //$NON-NLS-1$

    /** How many candidate names a refusal lists before it summarizes the rest. */
    private static final int MAX_LISTED = 25;

    /** The configuration items a named colour or font may reference. */
    public interface ConfigurationItems
    {
        /** @return the configuration's style items (mdclass {@code StyleItem}), never {@code null} */
        List<? extends EObject> styleItems();

        /** @return the configuration's palette colors (mdclass {@code PaletteColor}), never {@code null} */
        List<? extends EObject> paletteColors();
    }

    /** A built value, a clear, or an actionable error. */
    public static final class Result
    {
        /** The actionable error, or {@code null} on success. */
        public final String error;

        /** {@code true} when the value is 'auto': the property is to be cleared. */
        public final boolean clear;

        /** The detached Color / Font to set, or {@code null} for a clear or an error. */
        public final EObject value;

        /**
         * The configuration StyleItem / PaletteColor whose appearance item {@link #value} references,
         * or {@code null}: the caller re-binds the reference inside its write transaction.
         */
        public final EObject configurationItem;

        private Result(String error, boolean clear, EObject value, EObject configurationItem)
        {
            this.error = error;
            this.clear = clear;
            this.value = value;
            this.configurationItem = configurationItem;
        }

        static Result error(String error)
        {
            return new Result(error, false, null, null);
        }

        static Result cleared()
        {
            return new Result(null, true, null, null);
        }

        static Result ok(EObject value, EObject configurationItem)
        {
            return new Result(null, false, value, configurationItem);
        }
    }

    /** A catalogue hit: the platform proxy and the name the platform registers it under. */
    private static final class PlatformHit
    {
        final EObject proxy;

        final String name;

        PlatformHit(EObject proxy, String name)
        {
            this.proxy = proxy;
            this.name = name;
        }
    }

    private AppearanceValueBuilder()
    {
        // utility class
    }

    /**
     * The configuration items of a configuration, as a named colour or font sees them.
     *
     * @param configuration the configuration, may be {@code null} (then nothing resolves)
     * @return the items
     */
    public static ConfigurationItems itemsOf(Configuration configuration)
    {
        return new ConfigurationItems()
        {
            @Override
            public List<? extends EObject> styleItems()
            {
                return configuration == null ? Collections.emptyList() : configuration.getStyleItems();
            }

            @Override
            public List<? extends EObject> paletteColors()
            {
                return configuration == null ? Collections.emptyList()
                    : configuration.getPaletteColors();
            }
        };
    }

    /**
     * The platform colour catalogue for a platform version.
     *
     * @param version the platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform supplies none
     */
    public static IEObjectProvider colorCatalogue(Version version)
    {
        return catalogue(McorePackage.Literals.COLOR, version);
    }

    /**
     * The platform font catalogue for a platform version.
     *
     * @param version the platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform supplies none
     */
    public static IEObjectProvider fontCatalogue(Version version)
    {
        return catalogue(McorePackage.Literals.FONT, version);
    }

    private static IEObjectProvider catalogue(EClass type, Version version)
    {
        if (version == null)
        {
            return null;
        }
        try
        {
            return IEObjectProvider.Registry.INSTANCE.get(type, version);
        }
        catch (RuntimeException e)
        {
            // A missing catalogue surfaces as the refusal of the named value; never an exception.
            return null;
        }
    }

    // ---- colour ---------------------------------------------------------------------------------

    /**
     * Builds a colour value.
     *
     * @param raw the property value as supplied ({@code {color: ...}})
     * @param items the configuration items a style/palette name may reference, or {@code null} when
     *     the owner admits platform colors only
     * @param catalogue the platform colour catalogue, may be {@code null}
     * @return the built value, a clear, or an actionable error
     */
    public static Result buildColor(JsonElement raw, ConfigurationItems items, IEObjectProvider catalogue)
    {
        JsonElement color = unwrap(raw, COLOR);
        if (color == null)
        {
            return Result.error("A color value must be an object with the single member 'color', got " //$NON-NLS-1$
                + describe(raw) + ". " + COLOR_FORMS); //$NON-NLS-1$
        }
        if (isString(color))
        {
            String text = color.getAsString().trim();
            if (AUTO.equalsIgnoreCase(text))
            {
                return Result.cleared();
            }
            int dot = text.indexOf('.');
            String source = dot > 0 ? sourceOf(COLOR_SOURCES, text.substring(0, dot)) : null;
            if (source == null)
            {
                return Result.error("'" + text + "' is not a color. " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return namedColor(source, text.substring(dot + 1).trim(), items, catalogue);
        }
        if (!color.isJsonObject())
        {
            return Result.error("'color' must be a string or an object, got " + describe(color) + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + COLOR_FORMS);
        }
        JsonObject object = color.getAsJsonObject();
        String sourceKey = null;
        for (String key : object.keySet())
        {
            if (COLOR_SOURCES.containsKey(key))
            {
                sourceKey = key;
            }
        }
        if (sourceKey != null)
        {
            String name = object.size() == 1 ? strictString(object.get(sourceKey)) : null;
            if (name == null || name.trim().isEmpty())
            {
                return Result.error("A named color must be exactly {" + sourceKey //$NON-NLS-1$
                    + ":'<Name>'} with a non-empty name and no other member, got " + object + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + COLOR_FORMS);
            }
            return namedColor(COLOR_SOURCES.get(sourceKey), name.trim(), items, catalogue);
        }
        for (String key : object.keySet())
        {
            if (!"red".equals(key) && !"green".equals(key) && !"blue".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return Result.error("Unknown color member '" + key + "'. " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // The RGB shape is the StyleItem one, so its parser and its refusals are shared.
        JsonObject wrapped = new JsonObject();
        wrapped.add(COLOR, object);
        StyleValueBuilder.Result built = StyleValueBuilder.build(wrapped);
        if (built.error != null)
        {
            return Result.error(built.error);
        }
        Color def = built.value instanceof ColorValue ? ((ColorValue)built.value).getValue() : null;
        return def instanceof ColorDef && !(def instanceof AutoColor) ? Result.ok(def, null)
            : Result.error("'color' could not be built from " + object + ". " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Result namedColor(String source, String name, ConfigurationItems items,
        IEObjectProvider catalogue)
    {
        if (name.isEmpty())
        {
            return Result.error("A named color needs a name after '" + source + ".'. " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean styleSource = STYLE.equals(source);
        if ((styleSource || PALETTE.equals(source)) && items != null)
        {
            // The configuration wins over the platform, as in the designer's lists.
            EObject item = byName(styleSource ? items.styleItems() : items.paletteColors(), name);
            if (item != null)
            {
                EObject appearance = appearanceOf(item);
                if (!(appearance instanceof Color))
                {
                    return Result.error(source + " item '" + nameOf(item) + "' holds no color " //$NON-NLS-1$ //$NON-NLS-2$
                        + "(it is a font, or has no value yet). Set a color value on it, or name " //$NON-NLS-1$
                        + "another " + source + " color."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                ColorRef ref = McoreFactory.eINSTANCE.createColorRef();
                ref.setColor((Color)appearance);
                return Result.ok(ref, item);
            }
        }
        PlatformHit hit = platform(catalogue, source + "." + name, McorePackage.Literals.COLOR); //$NON-NLS-1$
        if (hit == null)
        {
            List<String> known = new ArrayList<>();
            if (items != null && (styleSource || PALETTE.equals(source)))
            {
                known.addAll(itemNames(styleSource ? items.styleItems() : items.paletteColors(),
                    Color.class, source));
            }
            known.addAll(platformNames(catalogue, source));
            return Result.error("Unknown " + source + " color '" + name + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + unknownTail(known, name, catalogue,
                    items != null && (styleSource || PALETTE.equals(source))));
        }
        ColorRef ref = McoreFactory.eINSTANCE.createColorRef();
        ref.setColor((Color)hit.proxy);
        return Result.ok(ref, null);
    }

    // ---- font -----------------------------------------------------------------------------------

    /**
     * Builds a font value.
     *
     * @param raw the property value as supplied ({@code {font: ...}})
     * @param items the configuration items a style name may reference, or {@code null} when the owner
     *     admits platform fonts only
     * @param catalogue the platform font catalogue, may be {@code null}
     * @return the built value, a clear, or an actionable error
     */
    public static Result buildFont(JsonElement raw, ConfigurationItems items, IEObjectProvider catalogue)
    {
        JsonElement font = unwrap(raw, FONT);
        if (font == null)
        {
            return Result.error("A font value must be an object with the single member 'font', got " //$NON-NLS-1$
                + describe(raw) + ". " + FONT_FORMS); //$NON-NLS-1$
        }
        if (isString(font))
        {
            String text = font.getAsString().trim();
            if (AUTO.equalsIgnoreCase(text))
            {
                return Result.cleared();
            }
            int dot = text.indexOf('.');
            String source = dot > 0 ? sourceOf(FONT_SOURCES, text.substring(0, dot)) : null;
            if (source == null)
            {
                return Result.error("'" + text + "' is not a font. " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return fontRef(source, text.substring(dot + 1).trim(), new JsonObject(), items, catalogue);
        }
        if (!font.isJsonObject())
        {
            return Result.error("'font' must be a string or an object, got " + describe(font) + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + FONT_FORMS);
        }
        JsonObject object = font.getAsJsonObject();
        String sourceKey = null;
        JsonObject overrides = new JsonObject();
        for (Map.Entry<String, JsonElement> member : object.entrySet())
        {
            String key = member.getKey();
            if (FONT_SOURCES.containsKey(key))
            {
                if (sourceKey != null)
                {
                    return Result.error("A font reference names ONE source, got both '" + sourceKey //$NON-NLS-1$
                        + "' and '" + key + "'. " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sourceKey = key;
                continue;
            }
            if (!FONT_OVERRIDES.contains(key))
            {
                return Result.error("Unknown font member '" + key + "'. " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String invalid = invalidOverride(key, member.getValue());
            if (invalid != null)
            {
                return Result.error(invalid);
            }
            overrides.add(key, member.getValue());
        }
        if (sourceKey != null)
        {
            String name = strictString(object.get(sourceKey));
            if (name == null || name.trim().isEmpty())
            {
                return Result.error("A font reference needs a non-empty name: {" + sourceKey //$NON-NLS-1$
                    + ":'<Name>'}. " + FONT_FORMS); //$NON-NLS-1$
            }
            return fontRef(FONT_SOURCES.get(sourceKey), name.trim(), overrides, items, catalogue);
        }
        return fontDef(overrides);
    }

    private static Result fontDef(JsonObject members)
    {
        if (members.size() == 0)
        {
            return Result.error("An absolute font needs at least one member. " + FONT_FORMS); //$NON-NLS-1$
        }
        JsonObject withoutScale = members.deepCopy();
        withoutScale.remove(SCALE);
        FontDef def;
        if (withoutScale.size() == 0)
        {
            def = McoreFactory.eINSTANCE.createFontDef();
        }
        else
        {
            // The absolute-font shape is the StyleItem one, so its parser is shared.
            JsonObject wrapped = new JsonObject();
            wrapped.add(FONT, withoutScale);
            StyleValueBuilder.Result built = StyleValueBuilder.build(wrapped);
            if (built.error != null)
            {
                return Result.error(built.error);
            }
            Font value = built.value instanceof FontValue ? ((FontValue)built.value).getValue() : null;
            if (!(value instanceof FontDef))
            {
                return Result.error("'font' could not be built from " + members + ". " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
            def = (FontDef)value;
        }
        if (members.has(SCALE))
        {
            def.setScale(members.get(SCALE).getAsInt());
        }
        return Result.ok(def, null);
    }

    private static Result fontRef(String source, String name, JsonObject overrides,
        ConfigurationItems items, IEObjectProvider catalogue)
    {
        if (name.isEmpty())
        {
            return Result.error("A font reference needs a name after '" + source + ".'. " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Font target = null;
        EObject item = null;
        boolean styleSource = STYLE.equals(source);
        if (styleSource && items != null)
        {
            item = byName(items.styleItems(), name);
            if (item != null)
            {
                EObject appearance = appearanceOf(item);
                if (!(appearance instanceof Font))
                {
                    return Result.error("Style item '" + nameOf(item) + "' holds no font (it is a " //$NON-NLS-1$ //$NON-NLS-2$
                        + "color, or has no value yet). Set a font value on it, or name another " //$NON-NLS-1$
                        + "Style font."); //$NON-NLS-1$
                }
                target = (Font)appearance;
            }
        }
        if (target == null)
        {
            PlatformHit hit = platform(catalogue, source + "." + name, McorePackage.Literals.FONT); //$NON-NLS-1$
            if (hit == null)
            {
                List<String> known = new ArrayList<>();
                if (items != null && styleSource)
                {
                    known.addAll(itemNames(items.styleItems(), Font.class, source));
                }
                known.addAll(platformNames(catalogue, source));
                return Result.error("Unknown " + source + " font '" + name + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + unknownTail(known, name, catalogue, items != null && styleSource));
            }
            target = (Font)hit.proxy;
        }
        FontRef ref = McoreFactory.eINSTANCE.createFontRef();
        ref.setFont(target);
        applyOverrides(ref, overrides);
        return Result.ok(ref, item);
    }

    /** Sets exactly the overrides given: an absent one stays UNSET, i.e. inherited. */
    private static void applyOverrides(FontRef ref, JsonObject overrides)
    {
        if (overrides.has(FACE_NAME))
        {
            ref.setFaceName(overrides.get(FACE_NAME).getAsString());
        }
        if (overrides.has(HEIGHT))
        {
            ref.setHeight(overrides.get(HEIGHT).getAsInt());
        }
        if (overrides.has("bold")) //$NON-NLS-1$
        {
            ref.setBold(overrides.get("bold").getAsBoolean()); //$NON-NLS-1$
        }
        if (overrides.has("italic")) //$NON-NLS-1$
        {
            ref.setItalic(overrides.get("italic").getAsBoolean()); //$NON-NLS-1$
        }
        if (overrides.has("underline")) //$NON-NLS-1$
        {
            ref.setUnderline(overrides.get("underline").getAsBoolean()); //$NON-NLS-1$
        }
        if (overrides.has("strikeout")) //$NON-NLS-1$
        {
            ref.setStrikeout(overrides.get("strikeout").getAsBoolean()); //$NON-NLS-1$
        }
        if (overrides.has(SCALE))
        {
            ref.setScale(overrides.get(SCALE).getAsInt());
        }
    }

    /** Strict member typing: a font member that does not hold its own type is refused, not coerced. */
    private static String invalidOverride(String key, JsonElement value)
    {
        if (FACE_NAME.equals(key))
        {
            String face = strictString(value);
            return face == null || face.trim().isEmpty()
                ? "Font 'faceName' must be a non-empty string, got " + describe(value) + "." : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (FONT_FLAGS.contains(key))
        {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? null : "Font '" + key + "' must be true or false, got " + describe(value) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Integer number = positiveInteger(value);
        return number != null ? null
            : "Font '" + key + "' must be a positive integer, got " + describe(value) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- transaction re-binding -----------------------------------------------------------------

    /**
     * Points a built ColorRef / FontRef at the appearance item of {@code itemInTx} - the SAME
     * configuration item re-fetched inside the write transaction - so no object read elsewhere is
     * attached to the model.
     *
     * @param value the built ColorRef / FontRef
     * @param itemInTx the StyleItem / PaletteColor re-fetched inside the write transaction
     * @throws IllegalStateException when the item no longer holds a value of the right kind
     */
    public static void rebind(EObject value, EObject itemInTx)
    {
        EObject appearance = appearanceOf(itemInTx);
        if (value instanceof ColorRef && appearance instanceof Color)
        {
            ((ColorRef)value).setColor((Color)appearance);
            return;
        }
        if (value instanceof FontRef && appearance instanceof Font)
        {
            ((FontRef)value).setFont((Font)appearance);
            return;
        }
        throw new IllegalStateException("The referenced item '" + nameOf(itemInTx) //$NON-NLS-1$
            + "' no longer holds a value of the expected kind"); //$NON-NLS-1$
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * Renders a contained colour in the form {@link #buildColor} accepts back: {@code RGB(r, g, b)},
     * {@code Auto}, or the referenced color's {@code <Source>.<Name>}.
     *
     * @param value the feature value
     * @return the rendering, or {@code null} when there is no colour
     */
    public static String renderColor(Object value)
    {
        if (!(value instanceof Color))
        {
            return null;
        }
        if (value instanceof AutoColor)
        {
            return "Auto"; //$NON-NLS-1$
        }
        if (value instanceof ColorRef)
        {
            return referenceName((EObject)value, McorePackage.Literals.COLOR_REF__COLOR);
        }
        if (value instanceof ColorDef)
        {
            ColorDef def = (ColorDef)value;
            return "RGB(" + def.getRed() + ", " + def.getGreen() + ", " + def.getBlue() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return ((EObject)value).eClass().getName();
    }

    /**
     * Renders a contained font: an absolute font as its members ({@code face='Arial', height=12,
     * bold}), a font reference as {@code <Source>.<Name>} followed by its SET overrides in
     * parentheses, an automatic font as {@code Auto} with its overrides.
     *
     * @param value the feature value
     * @return the rendering, or {@code null} when there is no font
     */
    public static String renderFont(Object value)
    {
        if (!(value instanceof Font))
        {
            return null;
        }
        if (value instanceof FontDef)
        {
            FontDef def = (FontDef)value;
            String members = StyleValueBuilder.renderFont(def);
            String scale = def.getScale() != 100 ? "scale=" + def.getScale() : null; //$NON-NLS-1$
            if (members == null)
            {
                return scale == null ? "Absolute font" : scale; //$NON-NLS-1$
            }
            return scale == null ? members : members + ", " + scale; //$NON-NLS-1$
        }
        EObject font = (EObject)value;
        String head = value instanceof FontRef
            ? referenceName(font, McorePackage.Literals.FONT_REF__FONT) : "Auto"; //$NON-NLS-1$
        String overrides = renderSetOverrides(font);
        return overrides.isEmpty() ? head : head + " (" + overrides + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String renderSetOverrides(EObject font)
    {
        StringBuilder sb = new StringBuilder();
        for (String name : FONT_OVERRIDES)
        {
            EStructuralFeature feature = font.eClass().getEStructuralFeature(name);
            if (feature == null || !font.eIsSet(feature))
            {
                continue;
            }
            Object v = font.eGet(feature);
            String part;
            if (FACE_NAME.equals(name))
            {
                part = "face='" + v + "'"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (v instanceof Boolean)
            {
                part = Boolean.TRUE.equals(v) ? name : name + "=false"; //$NON-NLS-1$
            }
            else if (v instanceof Float)
            {
                float f = (Float)v;
                part = name + "=" + (f == Math.rint(f) ? String.valueOf((int)f) : String.valueOf(f)); //$NON-NLS-1$
            }
            else
            {
                part = name + "=" + v; //$NON-NLS-1$
            }
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * The {@code <Source>.<Name>} of a colour / font reference. An unresolved proxy is named from its
     * URI before anything resolves it: a platform proxy carries the registered name as its fragment,
     * a configuration one the {@code StyleItem.<Name>} / {@code PaletteColor.<Name>} top object.
     */
    private static String referenceName(EObject owner, EReference feature)
    {
        Object raw = owner.eGet(feature, false);
        if (!(raw instanceof EObject))
        {
            return null;
        }
        EObject target = (EObject)raw;
        if (target.eIsProxy() && target instanceof InternalEObject)
        {
            URI uri = ((InternalEObject)target).eProxyURI();
            String fromUri = nameFromProxyUri(uri);
            if (fromUri != null)
            {
                return fromUri;
            }
            return "Unresolved reference" + (uri == null ? "" : ": " + uri); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        EObject container = target.eContainer();
        if (container instanceof MdObject)
        {
            String prefix = container instanceof StyleItem ? STYLE : PALETTE;
            return prefix + "." + ((MdObject)container).getName(); //$NON-NLS-1$
        }
        if (target instanceof NamedElement && ((NamedElement)target).getName() != null)
        {
            String name = ((NamedElement)target).getName();
            // A platform item is registered under its prefixed name already; a detached appearance
            // item of a configuration item carries the bare one.
            if (name.indexOf('.') > 0)
            {
                return name;
            }
            return (target instanceof StyleColor || target instanceof StyleFont ? STYLE : PALETTE)
                + "." + name; //$NON-NLS-1$
        }
        return target.eClass().getName();
    }

    private static String nameFromProxyUri(URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        if (BmUriUtil.isBmUri(uri))
        {
            String fqn = BmUriUtil.extractTopObjectFqn(uri);
            if (fqn != null && fqn.startsWith("StyleItem.")) //$NON-NLS-1$
            {
                return STYLE + fqn.substring("StyleItem".length()); //$NON-NLS-1$
            }
            if (fqn != null && fqn.startsWith("PaletteColor.")) //$NON-NLS-1$
            {
                return PALETTE + fqn.substring("PaletteColor".length()); //$NON-NLS-1$
            }
            return null;
        }
        String fragment = uri.fragment();
        if (fragment == null)
        {
            return null;
        }
        String name = fragment.startsWith("/") ? fragment.substring(1) : fragment; //$NON-NLS-1$
        return name.indexOf('.') > 0 ? name : null;
    }

    // ---- lookup helpers -------------------------------------------------------------------------

    private static PlatformHit platform(IEObjectProvider catalogue, String fullName, EClass type)
    {
        if (catalogue == null)
        {
            return null;
        }
        try
        {
            // The catalogue index is exact and case-sensitive: the exact spelling is the fast path,
            // anything else is answered by one pass over the descriptions.
            EObject exact = catalogue.getProxy(fullName);
            if (isProxyOf(exact, type))
            {
                return new PlatformHit(exact, fullName);
            }
            for (IEObjectDescription description : descriptions(catalogue))
            {
                String name = description.getName() == null ? null : description.getName().toString();
                if (fullName.equalsIgnoreCase(name))
                {
                    EObject proxy = description.getEObjectOrProxy();
                    if (isProxyOf(proxy, type))
                    {
                        return new PlatformHit(proxy, name);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }
        return null;
    }

    private static boolean isProxyOf(EObject value, EClass type)
    {
        return value != null && value.eIsProxy() && value.eClass() != null
            && type.isSuperTypeOf(value.eClass());
    }

    private static List<String> platformNames(IEObjectProvider catalogue, String source)
    {
        Set<String> names = new LinkedHashSet<>();
        if (catalogue == null)
        {
            return new ArrayList<>(names);
        }
        String prefix = source + "."; //$NON-NLS-1$
        try
        {
            for (IEObjectDescription description : descriptions(catalogue))
            {
                String name = description.getName() == null ? null : description.getName().toString();
                if (name != null && name.startsWith(prefix))
                {
                    names.add(name);
                }
            }
        }
        catch (RuntimeException e)
        {
            // An unreadable catalogue lists nothing; the refusal still names the accepted forms.
        }
        return new ArrayList<>(names);
    }

    private static Iterable<IEObjectDescription> descriptions(IEObjectProvider catalogue)
    {
        Iterable<IEObjectDescription> all = catalogue.getEObjectDescriptions(null);
        return all == null ? Collections.emptyList() : all;
    }

    private static List<String> itemNames(List<? extends EObject> items, Class<?> valueType,
        String source)
    {
        List<String> names = new ArrayList<>();
        for (EObject item : items)
        {
            if (valueType.isInstance(appearanceOf(item)))
            {
                names.add(source + "." + nameOf(item)); //$NON-NLS-1$
            }
        }
        return names;
    }

    private static EObject byName(List<? extends EObject> items, String name)
    {
        for (EObject item : items)
        {
            if (item != null && name.equalsIgnoreCase(nameOf(item)))
            {
                return item;
            }
        }
        return null;
    }

    private static String nameOf(EObject item)
    {
        return item instanceof MdObject ? ((MdObject)item).getName() : null;
    }

    private static EObject appearanceOf(EObject item)
    {
        EStructuralFeature feature = item == null ? null
            : item.eClass().getEStructuralFeature(APPEARANCE_ITEM);
        Object value = feature == null ? null : item.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    private static String unknownTail(List<String> known, String token, IEObjectProvider catalogue,
        boolean configurationSearched)
    {
        StringBuilder sb = new StringBuilder(". "); //$NON-NLS-1$
        if (catalogue == null)
        {
            sb.append("The platform catalogue is unavailable for this project's platform version. "); //$NON-NLS-1$
        }
        if (configurationSearched)
        {
            sb.append("Neither the configuration nor the platform registers that name. "); //$NON-NLS-1$
        }
        if (known.isEmpty())
        {
            return sb.append("No names of that source are available.").toString(); //$NON-NLS-1$
        }
        // Names that contain the token first: they are the likely intent; then the rest, bounded.
        String needle = token.toLowerCase(Locale.ROOT);
        List<String> ordered = new ArrayList<>();
        for (String name : known)
        {
            if (name.toLowerCase(Locale.ROOT).contains(needle))
            {
                ordered.add(name);
            }
        }
        for (String name : known)
        {
            if (!ordered.contains(name))
            {
                ordered.add(name);
            }
        }
        sb.append("Valid names: "); //$NON-NLS-1$
        sb.append(String.join(", ", ordered.subList(0, Math.min(MAX_LISTED, ordered.size())))); //$NON-NLS-1$
        if (ordered.size() > MAX_LISTED)
        {
            sb.append(", ... (").append(ordered.size() - MAX_LISTED).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.append('.').toString();
    }

    // ---- JSON helpers ---------------------------------------------------------------------------

    private static JsonElement unwrap(JsonElement raw, String member)
    {
        if (raw == null || !raw.isJsonObject())
        {
            return null;
        }
        JsonObject object = raw.getAsJsonObject();
        return object.size() == 1 && object.has(member) ? object.get(member) : null;
    }

    private static String sourceOf(Map<String, String> sources, String prefix)
    {
        for (String source : sources.values())
        {
            if (source.equalsIgnoreCase(prefix))
            {
                return source;
            }
        }
        return null;
    }

    private static boolean isString(JsonElement element)
    {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static String strictString(JsonElement element)
    {
        return isString(element) ? element.getAsString() : null;
    }

    private static Integer positiveInteger(JsonElement element)
    {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
        {
            return null;
        }
        double d = element.getAsDouble();
        if (d != Math.floor(d) || d < 1 || d > Integer.MAX_VALUE)
        {
            return null;
        }
        return Integer.valueOf((int)d);
    }

    private static String describe(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        if (element instanceof JsonPrimitive && ((JsonPrimitive)element).isString())
        {
            return "'" + element.getAsString() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return element.toString();
    }
}
