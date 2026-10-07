/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.StyleAppearanceItem;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.mcore.StyleFont;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Builds and renders the value of a plain contained mcore {@link Color} or {@link Font} property - a
 * form item's {@code textColor}, {@code titleFont}, ... (issue #660) - in the EDT designer's grammar:
 *
 * <ul>
 * <li><b>Color</b> {@code {color:{red,green,blue}}} (a {@link ColorDef}); {@code {color:'auto'}}
 * (clears the property - the designer's Clear); a NAMED color {@code {color:'<Source>.<Name>'}} or
 * {@code {color:{<source>:'<Name>'}}} (a {@link ColorRef}), the source being {@code Style} (a
 * configuration style item, else a platform style color), {@code Palette} (a configuration palette
 * color, else a platform one), {@code Web} or {@code Windows}.</li>
 * <li><b>Font</b> an absolute {@code {font:{faceName?,height?,bold?,italic?,underline?,strikeout?,scale?}}}
 * (a {@link FontDef}); {@code {font:'auto'}} (clears); a REFERENCE {@code {font:'<Source>.<Name>'}} or
 * {@code {font:{<source>:'<Name>', <overrides>?}}} (a {@link FontRef}), the source being {@code Style}
 * (a configuration style item, else a platform style font) or {@code System}; only the overrides
 * given are set, the rest stays inherited.</li>
 * </ul>
 *
 * <p>Platform names come from {@link PlatformCatalogue} (English names only - the platform registers
 * no others). A configuration item is found through the shared {@link StyleValueBuilder} lookup; the
 * built reference then carries NO target: the caller {@link #bind binds} it to the item re-fetched
 * inside its write transaction, so nothing read elsewhere is attached to the model. The RGB and font
 * member shapes are parsed by {@link StyleValueBuilder}, whose StyleItem / DCS behaviour is
 * unchanged.</p>
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

    /** A built value, a clear, or an actionable error. */
    public static final class Result
    {
        /** The actionable error, or {@code null} on success. */
        public final String error;

        /** {@code true} when the value is 'auto': the property is to be cleared. */
        public final boolean clear;

        /**
         * The detached Color / Font to set, or {@code null} for a clear or an error. When
         * {@link #configurationItem} is set it is a ColorRef / FontRef with NO target yet.
         */
        public final EObject value;

        /** The configuration StyleItem / PaletteColor {@link #value} is to be {@link #bind bound} to. */
        public final MdObject configurationItem;

        private Result(String error, boolean clear, EObject value, MdObject configurationItem)
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

        static Result ok(EObject value, MdObject configurationItem)
        {
            return new Result(null, false, value, configurationItem);
        }
    }

    /** What a named value resolves against, and how it is told apart: colour or font. */
    private static final class Kind
    {
        static final Kind COLOR_KIND = new Kind("color", McorePackage.Literals.COLOR, Color.class); //$NON-NLS-1$

        static final Kind FONT_KIND = new Kind("font", McorePackage.Literals.FONT, Font.class); //$NON-NLS-1$

        final String label;

        final EClass type;

        final Class<?> valueClass;

        private Kind(String label, EClass type, Class<?> valueClass)
        {
            this.label = label;
            this.type = type;
            this.valueClass = valueClass;
        }
    }

    private AppearanceValueBuilder()
    {
        // utility class
    }

    /**
     * The platform colour catalogue for a platform version.
     *
     * @param version the platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform supplies none
     */
    public static IEObjectProvider colorCatalogue(Version version)
    {
        return PlatformCatalogue.providerFor(McorePackage.Literals.COLOR, version);
    }

    /**
     * The platform font catalogue for a platform version.
     *
     * @param version the platform version, may be {@code null}
     * @return the catalogue, or {@code null} when the platform supplies none
     */
    public static IEObjectProvider fontCatalogue(Version version)
    {
        return PlatformCatalogue.providerFor(McorePackage.Literals.FONT, version);
    }

    // ---- colour ---------------------------------------------------------------------------------

    /**
     * Builds a colour value.
     *
     * @param raw the property value as supplied ({@code {color: ...}})
     * @param configuration the configuration a style/palette name may reference, or {@code null} when
     *     the owner admits platform colors only
     * @param catalogue the platform colour catalogue, may be {@code null}
     * @return the built value, a clear, or an actionable error
     */
    public static Result buildColor(JsonElement raw, Configuration configuration,
        IEObjectProvider catalogue)
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
            return named(Kind.COLOR_KIND, source, text.substring(dot + 1).trim(),
                McoreFactory.eINSTANCE.createColorRef(), configuration, catalogue);
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
            return named(Kind.COLOR_KIND, COLOR_SOURCES.get(sourceKey), name.trim(),
                McoreFactory.eINSTANCE.createColorRef(), configuration, catalogue);
        }
        for (String key : object.keySet())
        {
            if (!"red".equals(key) && !"green".equals(key) && !"blue".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return Result.error("Unknown color member '" + key + "'. " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // The RGB shape is the StyleItem one: its parser and its refusals are shared.
        JsonObject wrapped = new JsonObject();
        wrapped.add(COLOR, object);
        StyleValueBuilder.Result built = StyleValueBuilder.build(wrapped);
        if (built.error != null)
        {
            return Result.error(built.error);
        }
        Color def = built.value instanceof ColorValue ? ((ColorValue)built.value).getValue() : null;
        return def instanceof ColorDef ? Result.ok(def, null)
            : Result.error("'color' could not be built from " + object + ". " + COLOR_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- font -----------------------------------------------------------------------------------

    /**
     * Builds a font value.
     *
     * @param raw the property value as supplied ({@code {font: ...}})
     * @param configuration the configuration a style name may reference, or {@code null} when the
     *     owner admits platform fonts only
     * @param catalogue the platform font catalogue, may be {@code null}
     * @return the built value, a clear, or an actionable error
     */
    public static Result buildFont(JsonElement raw, Configuration configuration,
        IEObjectProvider catalogue)
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
            return named(Kind.FONT_KIND, source, text.substring(dot + 1).trim(),
                McoreFactory.eINSTANCE.createFontRef(), configuration, catalogue);
        }
        if (!font.isJsonObject())
        {
            return Result.error("'font' must be a string or an object, got " + describe(font) + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + FONT_FORMS);
        }
        JsonObject members = font.getAsJsonObject().deepCopy();
        String sourceKey = null;
        for (String key : FONT_SOURCES.keySet())
        {
            if (members.has(key))
            {
                if (sourceKey != null)
                {
                    return Result.error("A font reference names ONE source, got both '" + sourceKey //$NON-NLS-1$
                        + "' and '" + key + "'. " + FONT_FORMS); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sourceKey = key;
            }
        }
        JsonElement sourceName = sourceKey == null ? null : members.remove(sourceKey);
        StyleValueBuilder.FontMembers parsed = StyleValueBuilder.parseFontMembers(members, true);
        if (parsed.error != null)
        {
            return Result.error(parsed.error + " " + FONT_FORMS); //$NON-NLS-1$
        }
        if (sourceKey == null)
        {
            if (parsed.isEmpty())
            {
                return Result.error("An absolute font needs at least one member. " + FONT_FORMS); //$NON-NLS-1$
            }
            FontDef def = McoreFactory.eINSTANCE.createFontDef();
            StyleValueBuilder.applyFontMembers(def, parsed);
            return Result.ok(def, null);
        }
        String name = strictString(sourceName);
        if (name == null || name.trim().isEmpty())
        {
            return Result.error("A font reference needs a non-empty name: {" + sourceKey //$NON-NLS-1$
                + ":'<Name>'}. " + FONT_FORMS); //$NON-NLS-1$
        }
        FontRef ref = McoreFactory.eINSTANCE.createFontRef();
        StyleValueBuilder.applyFontMembers(ref, parsed);
        return named(Kind.FONT_KIND, FONT_SOURCES.get(sourceKey), name.trim(), ref, configuration,
            catalogue);
    }

    // ---- named values ---------------------------------------------------------------------------

    /**
     * Resolves a named colour / font onto {@code ref}: a configuration item first for a source that
     * has one (as the designer's merged list does), else the platform catalogue.
     */
    private static Result named(Kind kind, String source, String name, EObject ref, // NOSONAR one named lookup's inputs
        Configuration configuration, IEObjectProvider catalogue)
    {
        if (name.isEmpty())
        {
            return Result.error("A named " + kind.label + " needs a name after '" + source + ".'. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + forms(kind));
        }
        Configuration searched = hasConfigurationSource(kind, source) ? configuration : null;
        MdObject item = searched == null ? null : configurationItem(searched, source, name);
        if (item != null)
        {
            if (!kind.valueClass.isInstance(StyleValueBuilder.appearanceOf(item)))
            {
                return Result.error(source + " item '" + item.getName() + "' holds no " + kind.label //$NON-NLS-1$ //$NON-NLS-2$
                    + " (it holds another kind of value, or none yet). Set a " + kind.label //$NON-NLS-1$
                    + " value on it, or name another " + source + " " + kind.label + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            // The target is bound inside the write transaction - see bind().
            return Result.ok(ref, item);
        }
        PlatformCatalogue.Lookup found = PlatformCatalogue.find(catalogue, source + "." + name, kind.type); //$NON-NLS-1$
        if (found.status == PlatformCatalogue.Status.FOUND)
        {
            setTarget(ref, found.proxy);
            return Result.ok(ref, null);
        }
        return Result.error("Unknown " + source + " " + kind.label + " '" + name + "'. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + candidatesTail(kind, source, name, searched, catalogue, found.status));
    }

    /** Whether a source has configuration items: Style (colours and fonts), Palette (colours). */
    private static boolean hasConfigurationSource(Kind kind, String source)
    {
        return STYLE.equals(source) || PALETTE.equals(source) && kind == Kind.COLOR_KIND;
    }

    private static MdObject configurationItem(Configuration configuration, String source, String name)
    {
        return STYLE.equals(source) ? StyleValueBuilder.findStyleItem(configuration, name)
            : StyleValueBuilder.findPaletteColor(configuration, name);
    }

    /**
     * The refusal tail: what is known about the name, then the valid names - built by the SAME
     * precedence and kind rule as resolution, so every listed name resolves when retried.
     */
    private static String candidatesTail(Kind kind, String source, String name, // NOSONAR one refusal's inputs
        Configuration configuration, IEObjectProvider catalogue, PlatformCatalogue.Status status)
    {
        StringBuilder sb = new StringBuilder();
        if (status == PlatformCatalogue.Status.UNAVAILABLE)
        {
            sb.append("The platform catalogue is unavailable for this project's platform version, " //$NON-NLS-1$
                + "so platform names cannot be checked. "); //$NON-NLS-1$
        }
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> shadowed = new LinkedHashSet<>();
        if (configuration != null)
        {
            List<? extends MdObject> items = STYLE.equals(source) ? configuration.getStyleItems()
                : configuration.getPaletteColors();
            for (MdObject item : items)
            {
                // A configuration item shadows the platform name it shares, whatever it holds.
                shadowed.add(item.getName().toLowerCase());
                if (kind.valueClass.isInstance(StyleValueBuilder.appearanceOf(item)))
                {
                    candidates.add(source + "." + item.getName()); //$NON-NLS-1$
                }
            }
        }
        String prefix = source + "."; //$NON-NLS-1$
        List<String> platform = status == PlatformCatalogue.Status.UNAVAILABLE ? null
            : PlatformCatalogue.names(catalogue, n -> n.startsWith(prefix)
                && !shadowed.contains(n.substring(prefix.length()).toLowerCase()));
        if (platform != null)
        {
            candidates.addAll(platform);
        }
        if (candidates.isEmpty())
        {
            return sb.append(forms(kind)).toString();
        }
        // Similar names first (the shared not-found suggestion rule), then the rest, bounded.
        List<String> ordered = new ArrayList<>();
        for (String candidate : candidates)
        {
            if (MetadataTypeUtils.isSimilarName(candidate.substring(prefix.length()), name))
            {
                ordered.add(candidate);
            }
        }
        for (String candidate : candidates)
        {
            if (!ordered.contains(candidate))
            {
                ordered.add(candidate);
            }
        }
        sb.append("Valid names: ").append(String.join(", ", //$NON-NLS-1$ //$NON-NLS-2$
            ordered.subList(0, Math.min(MAX_LISTED, ordered.size()))));
        if (ordered.size() > MAX_LISTED)
        {
            sb.append(", ... (").append(ordered.size() - MAX_LISTED).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.append('.').toString();
    }

    private static String forms(Kind kind)
    {
        return kind == Kind.COLOR_KIND ? COLOR_FORMS : FONT_FORMS;
    }

    private static void setTarget(EObject ref, EObject target)
    {
        if (ref instanceof ColorRef)
        {
            ((ColorRef)ref).setColor((Color)target);
        }
        else
        {
            ((FontRef)ref).setFont((Font)target);
        }
    }

    // ---- transaction binding --------------------------------------------------------------------

    /**
     * Points a built ColorRef / FontRef at the appearance item of {@code itemInTx} - the
     * configuration item re-fetched inside the write transaction.
     *
     * @param value the built ColorRef / FontRef
     * @param itemInTx the StyleItem / PaletteColor re-fetched inside the write transaction
     * @throws IllegalStateException a {@link Refusals#state refusal} when the item no longer holds a
     *     value of the reference's kind
     */
    public static void bind(EObject value, EObject itemInTx)
    {
        StyleAppearanceItem appearance = itemInTx instanceof MdObject
            ? StyleValueBuilder.appearanceOf((MdObject)itemInTx) : null;
        boolean fits = value instanceof ColorRef ? appearance instanceof Color
            : value instanceof FontRef && appearance instanceof Font;
        if (!fits)
        {
            String name = itemInTx instanceof MdObject ? ((MdObject)itemInTx).getName() : null;
            throw Refusals.state("The referenced item '" + name + "' no longer holds a " //$NON-NLS-1$ //$NON-NLS-2$
                + (value instanceof ColorRef ? COLOR : FONT)
                + ". Read its value with get_metadata_details and retry."); //$NON-NLS-1$
        }
        setTarget(value, appearance);
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * Renders a contained colour in the form {@link #buildColor} accepts back: the referenced color's
     * {@code <Source>.<Name>} for a reference, otherwise the shared
     * {@link StyleValueBuilder#renderColor} ({@code RGB(r, g, b)}, {@code Auto}).
     *
     * @param value the feature value
     * @return the rendering, or {@code null} when nothing identifies the value
     */
    public static String renderColor(Object value)
    {
        if (value instanceof ColorRef)
        {
            return referenceName((EObject)value, McorePackage.Literals.COLOR_REF__COLOR);
        }
        return value instanceof Color ? StyleValueBuilder.renderColor((Color)value) : null;
    }

    /**
     * Renders a contained font: an absolute font as its members ({@code face='Arial', height=12,
     * bold}), a font reference as {@code <Source>.<Name>} followed by its SET overrides in
     * parentheses, an automatic font as {@code Auto} with its overrides.
     *
     * @param value the feature value
     * @return the rendering, or {@code null} when nothing identifies the value
     */
    public static String renderFont(Object value)
    {
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
        if (!(value instanceof Font))
        {
            return null;
        }
        EObject font = (EObject)value;
        String head = value instanceof FontRef
            ? referenceName(font, McorePackage.Literals.FONT_REF__FONT) : "Auto"; //$NON-NLS-1$
        if (head == null)
        {
            // A reference that names no target is not identified by its overrides.
            return null;
        }
        String overrides = renderSetOverrides(font);
        return overrides.isEmpty() ? head : head + " (" + overrides + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String renderSetOverrides(EObject font)
    {
        StringBuilder sb = new StringBuilder();
        for (String name : StyleValueBuilder.FONT_MEMBERS)
        {
            EStructuralFeature feature = font.eClass().getEStructuralFeature(name);
            if (feature == null || !font.eIsSet(feature))
            {
                continue;
            }
            Object v = font.eGet(feature);
            String part;
            if ("faceName".equals(name)) //$NON-NLS-1$
            {
                part = "face='" + v + "'"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (v instanceof Boolean)
            {
                part = Boolean.TRUE.equals(v) ? name : name + "=false"; //$NON-NLS-1$
            }
            else if (v instanceof Float)
            {
                part = name + "=" + StyleValueBuilder.formatHeight((Float)v); //$NON-NLS-1$
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
     *
     * @return the name, an explicit "Unresolved reference" text for an unnamed proxy, or {@code null}
     *     when the reference holds no target at all
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

    private static String describe(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        return isString(element) ? "'" + element.getAsString() + "'" : element.toString(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
