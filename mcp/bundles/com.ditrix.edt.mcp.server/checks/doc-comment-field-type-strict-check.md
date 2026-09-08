# Documenting comment field has type description (strict types)

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-field-type-strict` |
| **Severity** | Major |
| **Type** | Error |
| **Category** | Strict Types |

## 🎯 What This Check Does

Checks that field in documenting comment has type description in context of strict code typing system. This is a more strict version of `doc-comment-field-type` check.

## ❌ Error Examples

### Example 1 - Field without type in strict mode

```bsl
// @strict-types

// Returns:
//  Structure:
//      * Name  ← ERROR: Field has no type definition
//
Function GetData()
```

### Example 2 - Incomplete type definition

```bsl
// @strict-types

// Parameters:
//  Settings - Structure:
//      * Timeout -  ← ERROR: Type not specified
//
Procedure Configure(Settings)
```

## ✅ Correct Solutions

### Example 1 - Field with type

```bsl
// @strict-types

// Returns:
//  Structure:
//      * Name - String - user name
//
Function GetData()
```

### Example 2 - Complete type definition

```bsl
// @strict-types

// Parameters:
//  Settings - Structure:
//      * Timeout - Number - timeout in seconds
//
Procedure Configure(Settings)
```

## 🔧 How to Fix

1. Add type definition after field name
2. Use format: `* FieldName - Type - Description`
3. Ensure type is a valid 1C:Enterprise type
4. For strict typing, all fields must have explicit types

### Strict typing requirements:
- All fields must have types
- Use concrete types, avoid Arbitrary
- Required when module has `@strict-types` annotation

## 🔍 Technical Details

- **Java class**: `DocCommentFieldTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
