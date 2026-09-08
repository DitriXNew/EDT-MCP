# Return value section contains correct types

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-return-section-type` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that return value section of documenting comment contains correct types. Type should be specified and should be a known data type.

## ❌ Error Examples

### Example 1 - Missing return type

```bsl
// Returns current user.
//
// Returns:
//  ← ERROR: Return type is mandatory
//
Function GetCurrentUser()
```

### Example 2 - Unknown type

```bsl
// Gets settings.
//
// Returns:
//  MyCustomType - settings object  ← ERROR: Unknown type
//
Function GetSettings()
```

## ✅ Correct Solutions

### Example 1 - With return type

```bsl
// Returns current user.
//
// Returns:
//  CatalogRef.Users - current user reference
//
Function GetCurrentUser()
```

### Example 2 - Known types

```bsl
// Gets settings.
//
// Returns:
//  Structure - settings object
//
Function GetSettings()
```

### Example 3 - Multiple return types

```bsl
// Gets value from cache.
//
// Returns:
//  Arbitrary, Undefined - cached value or Undefined if not found
//
Function GetCachedValue(Key)
```

## 🔧 How to Fix

1. Add type after `// Returns:` section
2. Use known 1C:Enterprise types
3. For multiple types, separate with comma
4. Add description after type

### Return section format:
```
// Returns:
//  Type - description
//  Type, Type2 - description for multiple types
```

### Common valid types:
- Primitive: String, Number, Boolean, Date, Undefined
- References: CatalogRef.*, DocumentRef.*, etc.
- Collections: Array, Structure, Map, ValueTable
- Special: Arbitrary, Type, TypeDescription

## 🔍 Technical Details

- **Java class**: `FunctionReturnSectionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error messages**: 
  - "Return type is mandatory"
  - "Return type unknown"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
