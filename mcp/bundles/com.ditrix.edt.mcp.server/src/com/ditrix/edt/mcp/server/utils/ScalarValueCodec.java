/**
 * Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later.
 */
package com.ditrix.edt.mcp.server.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The scalar ValueSpec grammar shared by DCS and metadata member values. */
public final class ScalarValueCodec
{
    private ScalarValueCodec() { }

    public static boolean supports(String kind)
    {
        return java.util.Set.of("string", "number", "boolean", "date", "null", "undefined").contains(kind);
    }

    /** Builds a fresh detached scalar; callers attach it only inside their write transaction. */
    public static Value parse(JsonObject object, String path)
    {
        String unknown = JsonUtils.unknownMemberError(object, path, "kind", "value");
        if (unknown != null) throw new IllegalArgumentException(unknown);
        String kind = string(object.get("kind"), path + ".kind");
        JsonElement raw = object.get("value");
        switch (kind)
        {
            case "string":
                StringValue string = McoreFactory.eINSTANCE.createStringValue();
                string.setValue(string(raw, path + ".value"));
                return string;
            case "number":
                if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
                    throw bad(path, String.valueOf(raw), "Pass a JSON number in 'value'.");
                return numberValue(raw, path);
            case "boolean":
                if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean())
                    throw bad(path, String.valueOf(raw), "Pass true or false in 'value'.");
                BooleanValue bool = McoreFactory.eINSTANCE.createBooleanValue();
                bool.setValue(raw.getAsBoolean());
                return bool;
            case "date":
                String literal = string(raw, path + ".value");
                try
                {
                    DateValue date = McoreFactory.eINSTANCE.createDateValue();
                    date.setValue(com._1c.g5.v8.dt.mcore.util.Date.fromString(literal));
                    return date;
                }
                catch (IllegalArgumentException e)
                {
                    throw bad(path, literal, "Pass a platform date literal, e.g. 2026-01-01T00:00:00.");
                }
            case "null":
            case "undefined":
                if (raw != null && !raw.isJsonNull())
                    throw bad(path, kind, "It must omit 'value' or set it to null.");
                return "null".equals(kind) ? McoreFactory.eINSTANCE.createNullValue()
                    : McoreFactory.eINSTANCE.createUndefinedValue();
            default:
                throw bad(path, kind, "Use string, number, boolean, date, null or undefined.");
        }
    }

    public static String string(JsonElement raw, String path)
    {
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString())
            throw bad(path, String.valueOf(raw), "Pass a JSON string.");
        return raw.getAsString();
    }

    public static IllegalArgumentException bad(String path, String value, String advice)
    {
        return new IllegalArgumentException("Value '" + value + "' at '" + path + "' is invalid. " + advice);
    }

    /**
     * All JSON number write paths meet here. Like JsonUtils.parseExactDecimalLong, use exact
     * decimal arithmetic to remove an integral transport Double's fraction; retain real fractions
     * verbatim within the platform's Number limits. Check integer digits, fractional scale and
     * significant precision before expansion or model construction. Number qualifiers require
     * scale <= precision, so MAX_NUMBER_PRECISION also bounds the fractional scale.
     */
    static Value numberValue(JsonElement raw, String path)
    {
        BigDecimal decimal;
        try
        {
            decimal = raw.getAsBigDecimal();
        }
        catch (NumberFormatException e)
        {
            // Newer Gson versions also reject extreme scales during parsing. Keep that rejection
            // on the numeric error path, rather than the generic Date ValueSpec catch.
            throw numberValueFailure(raw, path);
        }
        // Zero has no significant exponent: discard even an extreme scale without expanding it.
        if (decimal.signum() == 0)
        {
            decimal = BigDecimal.ZERO;
        }
        long integerDigits = (long)decimal.precision() - decimal.scale();
        if (integerDigits > MetadataTypeBuilder.MAX_NUMBER_PRECISION
            || decimal.scale() > MetadataTypeBuilder.MAX_NUMBER_PRECISION)
        {
            throw numberValueFailure(raw, path);
        }
        BigDecimal significant = decimal.stripTrailingZeros();
        if (significant.precision() > MetadataTypeBuilder.MAX_NUMBER_PRECISION)
        {
            throw numberValueFailure(raw, path);
        }
        // Inspect integrality without growing a power of ten for a tiny fractional exponent.
        if (decimal.scale() <= 0 || significant.scale() <= 0)
        {
            decimal = decimal.setScale(0, RoundingMode.UNNECESSARY);
        }
        NumberValue number = McoreFactory.eINSTANCE.createNumberValue();
        number.setValue(decimal);
        return number;
    }

    private static IllegalArgumentException numberValueFailure(JsonElement raw, String path)
    {
        return new IllegalArgumentException("Number ValueSpec value '" + raw + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "' cannot be stored as a 1C Number. Pass a finite JSON number with at most " //$NON-NLS-1$
            + MetadataTypeBuilder.MAX_NUMBER_PRECISION
            + " integer digits, " + MetadataTypeBuilder.MAX_NUMBER_PRECISION //$NON-NLS-1$
            + " fractional digits and " + MetadataTypeBuilder.MAX_NUMBER_PRECISION //$NON-NLS-1$
            + " significant digits."); //$NON-NLS-1$
    }

}
