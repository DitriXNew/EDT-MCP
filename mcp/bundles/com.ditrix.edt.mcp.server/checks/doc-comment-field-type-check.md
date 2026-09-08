# Field has no type definition

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-field-type` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that field in documenting comment has type definition. Each structure field should have data type specification.

## ❌ Error Examples

### Example 1 - Field without type

```bsl
// Returns:
//  Structure:
//      * Name  ← ERROR: Field has no type definition
//      * Age   ← ERROR: Field has no type definition
//
Function GetUserData()
```

### Example 2 - Missing type after hyphen

```bsl
// Returns:
//  Structure:
//      * Name -  ← ERROR: Type definition missing after hyphen
//      * Age - Number - user age
//
Function GetUserData()
```

## ✅ Correct Solutions

### Example 1 - Fields with types

```bsl
// Returns:
//  Structure:
//      * Name - String - user name
//      * Age - Number - user age
//
Function GetUserData()
```

### Example 2 - Complete field definitions

```bsl
// Returns:
//  Structure:
//      * Name - String
//      * Age - Number
//      * Active - Boolean
//
Function GetUserData()
```

## 🔧 How to Fix

1. Add type after field name using hyphen separator
2. Format: `* FieldName - Type - Description`
3. Type can be primitive or reference type
4. Description is optional but recommended

### Field format:
```
* FieldName - Type - Description
* FieldName - Type
```

## 🔍 Technical Details

- **Java class**: `FieldDefinitionTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Field has no type definition"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
