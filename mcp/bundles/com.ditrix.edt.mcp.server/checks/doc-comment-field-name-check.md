# Field name in documenting comment

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-field-name` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that field name in documenting comment is a valid name (follows naming rules) and is unique within the structure.

## ❌ Error Examples

### Example 1 - Invalid field name

```bsl
// Returns:
//  Structure:
//      * 123Name - String - name  ← ERROR: Invalid name (starts with digit)
//      * user-name - String - user name  ← ERROR: Invalid name (contains hyphen)
//
Function GetData()
```

### Example 2 - Duplicate field names

```bsl
// Returns:
//  Structure:
//      * Name - String - first name
//      * Age - Number - age
//      * Name - String - last name  ← ERROR: Duplicate field name "Name"
//
Function GetUserData()
```

## ✅ Correct Solutions

### Example 1 - Valid field names

```bsl
// Returns:
//  Structure:
//      * ItemName - String - name
//      * UserName - String - user name
//
Function GetData()
```

### Example 2 - Unique field names

```bsl
// Returns:
//  Structure:
//      * FirstName - String - first name
//      * Age - Number - age
//      * LastName - String - last name
//
Function GetUserData()
```

## 🔧 How to Fix

1. Use valid identifier names for fields
2. Names must start with a letter
3. Names can contain letters, digits, and underscores
4. Ensure field names are unique within structure
5. Use CamelCase for field names

### Valid field name rules:
- Start with letter (a-z, A-Z, or Cyrillic)
- Contain only letters, digits, underscores
- Must be unique within type definition
- Follow naming conventions (CamelCase)

## 🔍 Technical Details

- **Java class**: `FieldDefinitionNameCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error messages**: 
  - "Field name is incorrect name"
  - "Field name is not unique"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
- [Standard #456 - Naming conventions](https://its.1c.ru/db/v8std#content:456:hdoc)
