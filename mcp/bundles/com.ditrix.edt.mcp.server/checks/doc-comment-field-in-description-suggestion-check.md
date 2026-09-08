# Field definition in multiline description

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-field-in-description-suggestion` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Detects cases when structure field definition is accidentally specified in description section instead of type section. This is a common mistake when writing documenting comments.

## ❌ Error Examples

### Example 1 - Field definition in description

```bsl
// Returns structure with user settings
// * Name - String - user name  ← ERROR: Field defined in description
// * Age - Number - user age
//
// Returns:
//  Structure
//
Function GetUserSettings()
```

### Example 2 - Mixed content

```bsl
// Gets order data.
// Contains fields:  ← ERROR: Fields should be in Returns section
// * OrderNumber - String
// * Date - Date
//
// Returns:
//  Structure
//
Function GetOrderData()
```

## ✅ Correct Solutions

### Example 1 - Fields in type section

```bsl
// Returns structure with user settings.
//
// Returns:
//  Structure:
//      * Name - String - user name
//      * Age - Number - user age
//
Function GetUserSettings()
```

### Example 2 - Complete structure description

```bsl
// Gets order data.
//
// Returns:
//  Structure:
//      * OrderNumber - String - order number
//      * Date - Date - order date
//
Function GetOrderData()
```

## 🔧 How to Fix

1. Move field definitions from description to type section
2. Use proper indentation with `*` for fields
3. Place fields under the type definition in Returns section

### Correct structure:
```
// Description here (plain text)
//
// Returns:
//  Structure:
//      * FieldName - Type - description
```

## 🔍 Technical Details

- **Java class**: `MultilineDescriptionFieldSuggestionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Probably Field is defined in description"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
