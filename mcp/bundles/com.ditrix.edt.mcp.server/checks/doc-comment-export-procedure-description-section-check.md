# Missing description section in export procedure comment

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-export-procedure-description-section` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 453 |

## 🎯 What This Check Does

Checks that documenting comment for export procedure (function) contains "Description" section - text description of method purpose.

## ❌ Error Examples

### Example 1 - No description section

```bsl
// Parameters:
//  Data - Structure - data to save  ← ERROR: No description before parameters
//
Procedure SaveData(Data) Export
```

### Example 2 - Empty description

```bsl
//
// Parameters:  ← ERROR: Empty description section
//  User - String - user name
//
Procedure SetCurrentUser(User) Export
```

## ✅ Correct Solutions

### Example 1 - With description

```bsl
// Saves data to the database.
// Validates data before saving and raises exception if validation fails.
//
// Parameters:
//  Data - Structure - data to save
//
Procedure SaveData(Data) Export
```

### Example 2 - Complete documentation comment

```bsl
// Sets current user for the session.
// This procedure should be called at the beginning of each session
// to initialize user context.
//
// Parameters:
//  User - String - user name
//
Procedure SetCurrentUser(User) Export
```

### Example 3 - Simple one-line description

```bsl
// Clears temporary files from cache.
//
Procedure ClearCache() Export
```

## 🔧 How to Fix

1. Add text description before `// Parameters:` section
2. Description should explain what the method does
3. Use complete sentences ending with periods
4. For complex logic, use multi-line descriptions

### Documentation comment structure:
```
// Description text (required)
// Can be multi-line for complex methods.
//
// Parameters:
//  ...
//
// Returns:
//  ...
```

## 🔍 Technical Details

- **Java class**: `ExportProcedureCommentDescriptionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Applies to**: Export procedures and functions

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
