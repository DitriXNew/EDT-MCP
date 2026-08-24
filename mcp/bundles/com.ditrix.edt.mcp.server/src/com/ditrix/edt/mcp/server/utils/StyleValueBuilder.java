/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.dt.mcore.AutoColor;
import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.PaletteColor;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Builds the mcore {@link Value} (a {@link ColorValue} or a {@link FontValue}) of a
 * {@link com._1c.g5.v8.dt.metadata.mdclass.StyleItem StyleItem} from the structured JSON a client
 * passes to {@code modify_metadata}'s {@code value} property or a typed DCS parameter, and the
 * matching {@link StyleElementType} (so a style item's {@code type} stays consistent with its value).
 *
 * <p>Accepted shapes (exactly one of {@code color} / {@code font}):</p>
 * <ul>
 * <li><b>Color</b> {@code {color:{red:0-255, green:0-255, blue:0-255}}} - an explicit RGB color;
 * {@code {color:"auto"}} - the platform automatic color; or
 * {@code {color:{style:"Name"}}} / {@code {color:{palette:"Name"}}} - a named project color.</li>
 * <li><b>Font</b> {@code {font:{faceName?, height?, bold?, italic?, underline?, strikeout?}}} -
 * at least one of the listed members must be present.</li>
 * </ul>
 *
 * <p>The build never mutates the project model: it instantiates the value wrapper via
 * {@link McoreFactory}; for a named color it reads and references the resolved appearance item.
 * The caller attaches the resulting {@link Result#value} inside its own write transaction.</p>
 */
public final class StyleValueBuilder
{
    /** Minimum allowed RGB component value. */
    private static final int RGB_MIN = 0;
    /** Maximum allowed RGB component value. */
    private static final int RGB_MAX = 255;
    /** Error-message fragment between the expected range/value and the actual one. */
    private static final String GOT_SEPARATOR = ", got "; //$NON-NLS-1$

    /** Resolves the mcore appearance object owned by a named project style/palette item. */
    public interface NamedColorResolver
    {
        /** @return the style item's {@link StyleColor}, or {@code null} when it is absent/not a color. */
        StyleColor resolveStyle(String name);

        /** @return the palette item's {@link PaletteColor}, or {@code null} when it is absent/not a color. */
        PaletteColor resolvePalette(String name);
    }

    /** A successfully built style value (the mcore {@link Value} + the matching element {@link StyleElementType}),
     * or an actionable {@link #error} message. Exactly one of {@code error} / {@code value} is set. */
    public static final class Result
    {
        /** The actionable error message when the input was invalid; {@code null} on success. */
        public final String error;
        /** The built mcore value (a {@link ColorValue} or {@link FontValue}); {@code null} on error. */
        public final Value value;
        /** The style element type matching the value ({@code COLOR} / {@code FONT}); {@code null} on error. */
        public final StyleElementType type;
        /** A short human-readable summary of the applied value (for the result echo); {@code null} on error. */
        public final String summary;

        private Result(String error, Value value, StyleElementType type, String summary)
        {
            this.error = error;
            this.value = value;
            this.type = type;
            this.summary = summary;
        }

        static Result error(String message)
        {
            return new Result(message, null, null, null);
        }

        static Result ok(Value value, StyleElementType type, String summary)
        {
            return new Result(null, value, type, summary);
        }
    }

    private StyleValueBuilder()
    {
        // utility class
    }

    /**
     * Builds the style value from the structured {@code value} JSON.
     *
     * @param raw the {@code value} JSON element ({@code {color:...}} or {@code {font:...}})
     * @return the built {@link Result} (its {@link Result#error} is non-null on invalid input)
     */
    public static Result build(JsonElement raw)
    {
        return build(raw, null);
    }

    /**
     * Builds a style value, resolving named colors against the caller's project model.
     *
     * @param raw structured style value JSON
     * @param namedColors project color resolver; required only for style/palette forms
     * @return built value or an actionable refusal
     */
    public static Result build(JsonElement raw, NamedColorResolver namedColors)
    {
        if (raw == null || !raw.isJsonObject())
        {
            return Result.error("A StyleItem 'value' must be a structured object: " //$NON-NLS-1$
                + "{color:{red,green,blue}} or {color:'auto'} for a color, or " //$NON-NLS-1$
                + "{font:{faceName?,height?,bold?,italic?,underline?,strikeout?}} for a font."); //$NON-NLS-1$
        }
        JsonObject obj = raw.getAsJsonObject();
        boolean hasColor = obj.has("color"); //$NON-NLS-1$
        boolean hasFont = obj.has("font"); //$NON-NLS-1$
        if (hasColor && hasFont)
        {
            return Result.error("A StyleItem 'value' must set EITHER 'color' OR 'font', not both."); //$NON-NLS-1$
        }
        if (hasColor)
        {
            return buildColor(obj.get("color"), namedColors); //$NON-NLS-1$
        }
        if (hasFont)
        {
            return buildFont(obj.get("font")); //$NON-NLS-1$
        }
        return Result.error("A StyleItem 'value' needs a 'color' or a 'font' member, e.g. " //$NON-NLS-1$
            + "{color:{red:255,green:0,blue:0}} or {font:{faceName:'Arial',height:12,bold:true}}."); //$NON-NLS-1$
    }

    private static Result buildColor(JsonElement colorEl, NamedColorResolver namedColors)
    {
        // The automatic color is expressed as the string "auto" (or {auto:true}); anything else is an
        // explicit RGB object.
        if (isAuto(colorEl))
        {
            ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
            colorValue.setValue(McoreFactory.eINSTANCE.createAutoColor());
            return Result.ok(colorValue, StyleElementType.COLOR, "Color=Auto"); //$NON-NLS-1$
        }
        if (colorEl == null || !colorEl.isJsonObject())
        {
            return Result.error("A 'color' value must be {red:0-255, green:0-255, blue:0-255} or " //$NON-NLS-1$
                + "the string 'auto'."); //$NON-NLS-1$
        }
        JsonObject color = colorEl.getAsJsonObject();
        boolean hasStyle = color.has("style"); //$NON-NLS-1$
        boolean hasPalette = color.has("palette"); //$NON-NLS-1$
        if (hasStyle || hasPalette)
        {
            if (hasStyle && hasPalette || color.size() != 1)
            {
                return Result.error("A named 'color' must be exactly {style:'<name>'} or " //$NON-NLS-1$
                    + "{palette:'<name>'}, with no RGB or other members."); //$NON-NLS-1$
            }
            String member = hasStyle ? "style" : "palette"; //$NON-NLS-1$ //$NON-NLS-2$
            String name = strictStringMember(color, member);
            if (name == null || name.trim().isEmpty())
            {
                return Result.error("A named 'color' needs a non-empty string name: " //$NON-NLS-1$
                    + "{color:{" + member + ":'<name>'}}."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            name = name.trim();
            Color resolved = hasStyle && namedColors != null ? namedColors.resolveStyle(name)
                : !hasStyle && namedColors != null ? namedColors.resolvePalette(name) : null;
            if (resolved == null)
            {
                String kind = hasStyle ? "style color" : "palette color"; //$NON-NLS-1$ //$NON-NLS-2$
                String location = hasStyle
                    ? "Configuration > Style items (Common/StyleItems)" //$NON-NLS-1$
                    : "Configuration > Palette colors (Common/PaletteColors)"; //$NON-NLS-1$
                return Result.error("Named " + kind + " '" + name + "' cannot be resolved. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "Such items are defined in the project's " + location + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            ColorRef colorRef = McoreFactory.eINSTANCE.createColorRef();
            colorRef.setColor(resolved);
            ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
            colorValue.setValue(colorRef);
            return Result.ok(colorValue, StyleElementType.COLOR,
                "Color=" + (hasStyle ? "Style." : "Palette.") + name); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Integer red = intMember(color, "red"); //$NON-NLS-1$
        Integer green = intMember(color, "green"); //$NON-NLS-1$
        Integer blue = intMember(color, "blue"); //$NON-NLS-1$
        if (red == null || green == null || blue == null)
        {
            return Result.error("An explicit 'color' needs integer red, green and blue (0-255). " //$NON-NLS-1$
                + "Use {color:'auto'} for the automatic color."); //$NON-NLS-1$
        }
        String rangeError = validateRgb(red, green, blue);
        if (rangeError != null)
        {
            return Result.error(rangeError);
        }
        ColorDef colorDef = McoreFactory.eINSTANCE.createColorDef();
        colorDef.setRed(red);
        colorDef.setGreen(green);
        colorDef.setBlue(blue);
        ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
        colorValue.setValue(colorDef);
        return Result.ok(colorValue, StyleElementType.COLOR,
            "Color RGB(" + red + ", " + green + ", " + blue + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static Result buildFont(JsonElement fontEl)
    {
        if (fontEl == null || !fontEl.isJsonObject())
        {
            return Result.error("A 'font' value must be an object with at least one of faceName, " //$NON-NLS-1$
                + "height, bold, italic, underline, strikeout."); //$NON-NLS-1$
        }
        JsonObject font = fontEl.getAsJsonObject();
        String faceName = stringMember(font, "faceName"); //$NON-NLS-1$
        Integer height = intMember(font, "height"); //$NON-NLS-1$
        boolean hasFace = faceName != null && !faceName.isEmpty();
        boolean hasHeight = height != null;
        Boolean bold = boolMember(font, "bold"); //$NON-NLS-1$
        Boolean italic = boolMember(font, "italic"); //$NON-NLS-1$
        Boolean underline = boolMember(font, "underline"); //$NON-NLS-1$
        Boolean strikeout = boolMember(font, "strikeout"); //$NON-NLS-1$
        boolean hasFlag = bold != null || italic != null || underline != null || strikeout != null;

        if (!hasFace && !hasHeight && !hasFlag)
        {
            return Result.error("A 'font' value needs at least one of faceName, height, bold, " //$NON-NLS-1$
                + "italic, underline, strikeout."); //$NON-NLS-1$
        }
        if (hasHeight && height <= 0)
        {
            return Result.error("Font height must be a positive integer, got " + height + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        FontDef fontDef = McoreFactory.eINSTANCE.createFontDef();
        if (hasFace)
        {
            fontDef.setFaceName(faceName);
        }
        if (hasHeight)
        {
            fontDef.setHeight((float)height.intValue());
        }
        fontDef.setBold(Boolean.TRUE.equals(bold));
        fontDef.setItalic(Boolean.TRUE.equals(italic));
        fontDef.setUnderline(Boolean.TRUE.equals(underline));
        fontDef.setStrikeout(Boolean.TRUE.equals(strikeout));
        FontValue fontValue = McoreFactory.eINSTANCE.createFontValue();
        fontValue.setValue(fontDef);
        return Result.ok(fontValue, StyleElementType.FONT,
            summarizeFont(hasFace ? faceName : null, hasHeight ? height : null,
                Boolean.TRUE.equals(bold), Boolean.TRUE.equals(italic),
                Boolean.TRUE.equals(underline), Boolean.TRUE.equals(strikeout)));
    }

    // ---- rendering (shared by the get_metadata_details formatter) -------------------------------

    /**
     * Renders a {@link Color} (an {@link AutoColor} or an explicit {@link ColorDef}) to a readable
     * string. {@code AutoColor} extends {@code ColorDef}, so the {@code AutoColor} check MUST come
     * first (otherwise an automatic color would render as {@code RGB(0,0,0)}).
     *
     * @param color the color (may be {@code null})
     * @return {@code "Auto"}, {@code "RGB(r, g, b)"}, the class name for an unknown color, or {@code null}
     */
    public static String renderColor(Color color)
    {
        if (color == null)
        {
            return null;
        }
        if (color instanceof AutoColor)
        {
            return "Auto"; //$NON-NLS-1$
        }
        if (color instanceof ColorDef)
        {
            ColorDef def = (ColorDef)color;
            return "RGB(" + def.getRed() + ", " + def.getGreen() + ", " + def.getBlue() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if (color instanceof ColorRef)
        {
            Color referenced = ((ColorRef)color).getColor();
            if (referenced instanceof NamedElement)
            {
                String prefix = referenced instanceof StyleColor ? "Style." //$NON-NLS-1$
                    : referenced instanceof PaletteColor ? "Palette." : ""; //$NON-NLS-1$ //$NON-NLS-2$
                return prefix + ((NamedElement)referenced).getName();
            }
        }
        return color.eClass().getName();
    }

    /**
     * Creates the resolver used by both DCS typed values and {@code modify_metadata}. The platform
     * stores the serializable named color on the metadata item's {@code appearanceItem}; a style
     * item is usable here only when that object is an mcore {@link StyleColor}, and a palette item
     * only when it is an mcore {@link PaletteColor}.
     */
    public static NamedColorResolver forConfiguration(Configuration configuration)
    {
        return new NamedColorResolver()
        {
            @Override
            public StyleColor resolveStyle(String name)
            {
                if (configuration == null || name == null)
                {
                    return null;
                }
                for (StyleItem item : configuration.getStyleItems())
                {
                    if (item != null && name.equalsIgnoreCase(item.getName())
                        && item.getAppearanceItem() instanceof StyleColor)
                    {
                        return (StyleColor)item.getAppearanceItem();
                    }
                }
                return null;
            }

            @Override
            public PaletteColor resolvePalette(String name)
            {
                if (configuration == null || name == null)
                {
                    return null;
                }
                for (com._1c.g5.v8.dt.metadata.mdclass.PaletteColor item
                    : configuration.getPaletteColors())
                {
                    if (item != null && name.equalsIgnoreCase(item.getName())
                        && item.getAppearanceItem() instanceof PaletteColor)
                    {
                        return (PaletteColor)item.getAppearanceItem();
                    }
                }
                return null;
            }
        };
    }

    /**
     * Renders a {@link Font} (an explicit {@link FontDef}) to a readable string showing the face
     * name, height and the bold/italic/underline/strikeout flags.
     *
     * @param font the font (may be {@code null})
     * @return the readable description, the class name for an unknown font, or {@code null}
     */
    public static String renderFont(Font font)
    {
        if (font == null)
        {
            return null;
        }
        if (!(font instanceof FontDef))
        {
            return font.eClass().getName();
        }
        FontDef def = (FontDef)font;
        StringBuilder sb = new StringBuilder();
        String faceName = def.getFaceName();
        if (faceName != null && !faceName.isEmpty())
        {
            sb.append("face='").append(faceName).append('\''); //$NON-NLS-1$
        }
        if (def.getHeight() > 0)
        {
            appendSeparator(sb);
            sb.append("height=").append(formatHeight(def.getHeight())); //$NON-NLS-1$
        }
        appendFlag(sb, "bold", def.isBold()); //$NON-NLS-1$
        appendFlag(sb, "italic", def.isItalic()); //$NON-NLS-1$
        appendFlag(sb, "underline", def.isUnderline()); //$NON-NLS-1$
        appendFlag(sb, "strikeout", def.isStrikeout()); //$NON-NLS-1$
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---- internals ------------------------------------------------------------------------------

    private static boolean isAuto(JsonElement colorEl)
    {
        if (colorEl != null && colorEl.isJsonPrimitive())
        {
            JsonPrimitive p = colorEl.getAsJsonPrimitive();
            return p.isString() && "auto".equalsIgnoreCase(p.getAsString().trim()); //$NON-NLS-1$
        }
        if (colorEl != null && colorEl.isJsonObject())
        {
            Boolean auto = boolMember(colorEl.getAsJsonObject(), "auto"); //$NON-NLS-1$
            return Boolean.TRUE.equals(auto);
        }
        return false;
    }

    private static String validateRgb(int red, int green, int blue)
    {
        if (outOfRange(red))
        {
            return "red must be in range " + RGB_MIN + "-" + RGB_MAX + GOT_SEPARATOR + red + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (outOfRange(green))
        {
            return "green must be in range " + RGB_MIN + "-" + RGB_MAX + GOT_SEPARATOR + green + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (outOfRange(blue))
        {
            return "blue must be in range " + RGB_MIN + "-" + RGB_MAX + GOT_SEPARATOR + blue + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    private static boolean outOfRange(int component)
    {
        return component < RGB_MIN || component > RGB_MAX;
    }

    private static Integer intMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement el = obj.get(name);
        if (el == null || !el.isJsonPrimitive())
        {
            return null;
        }
        try
        {
            double d = el.getAsDouble();
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return null;
            }
            return Integer.valueOf((int)d);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String stringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement el = obj.get(name);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static String strictStringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement el = obj.get(name);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
            ? el.getAsString() : null;
    }

    private static Boolean boolMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
        }
        JsonElement el = obj.get(name);
        if (el == null || !el.isJsonPrimitive())
        {
            return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean())
        {
            return p.getAsBoolean();
        }
        String s = p.getAsString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
    }

    private static String summarizeFont(String faceName, Integer height, boolean bold, boolean italic,
        boolean underline, boolean strikeout)
    {
        StringBuilder sb = new StringBuilder("Font"); //$NON-NLS-1$
        if (faceName != null)
        {
            sb.append(" face='").append(faceName).append('\''); //$NON-NLS-1$
        }
        if (height != null)
        {
            sb.append(" height=").append(height); //$NON-NLS-1$
        }
        if (bold)
        {
            sb.append(" bold"); //$NON-NLS-1$
        }
        if (italic)
        {
            sb.append(" italic"); //$NON-NLS-1$
        }
        if (underline)
        {
            sb.append(" underline"); //$NON-NLS-1$
        }
        if (strikeout)
        {
            sb.append(" strikeout"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String formatHeight(float height)
    {
        if (height == Math.rint(height))
        {
            return String.valueOf((int)height);
        }
        return String.valueOf(height);
    }

    private static void appendSeparator(StringBuilder sb)
    {
        if (sb.length() > 0)
        {
            sb.append(", "); //$NON-NLS-1$
        }
    }

    private static void appendFlag(StringBuilder sb, String name, boolean set)
    {
        if (!set)
        {
            return;
        }
        appendSeparator(sb);
        sb.append(name);
    }
}
