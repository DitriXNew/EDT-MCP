# Redundant parameters section

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-redundant-parameter-section` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that documenting comment for method without parameters does not have parameters section. If method takes no parameters, `// Parameters:` section is redundant and should be removed.

## ❌ Error Examples

### Example 1 - Parameters section for parameterless method

```bsl
// Returns current date.
//
// Parameters:  ← ERROR: Redundant Parameters section
//
// Returns:
//  Date - current date
//
Function GetCurrentDate()
```

### Example 2 - Empty parameters section

```bsl
// Clears cache.
//
// Parameters:  ← ERROR: Remove empty Parameters section
//
Procedure ClearCache()
```

## ✅ Correct Solutions

### Example 1 - No Parameters section

```bsl
// Returns current date.
//
// Returns:
//  Date - current date
//
Function GetCurrentDate()
```

### Example 2 - Simple procedure

```bsl
// Clears cache.
//
Procedure ClearCache()
```

### Example 3 - Function with return only

```bsl
// Gets default timeout value.
//
// Returns:
//  Number - timeout in seconds
//
Function GetDefaultTimeout()
```

## 🔧 How to Fix

1. Remove `// Parameters:` section if method has no parameters
2. Keep only Description and Returns sections (for functions)
3. For procedures without parameters, only Description is needed

### Correct structure for parameterless methods:
```
// Description
//
// Returns:  (for functions only)
//  Type - description
//
```

## 🔍 Technical Details

- **Java class**: `RedundantParametersSectionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Remove useless parameter section"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
