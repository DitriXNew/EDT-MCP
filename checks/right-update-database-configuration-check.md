# Right set: Update Database Configuration

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-update-database-configuration` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `UpdateDatabaseConfiguration` right in roles. This is a critical right that allows modifying the database structure. Should only be available for the full rights role.

## ❌ Error Examples

```xml
<!-- Incorrect - UpdateDatabaseConfiguration for non-admin -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Developer role in production -->
<!-- Role: Developer -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Only FullRights role -->
<!-- Role: FullRights -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Administrator without database update -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>false</Value>
  </Right>
</Rights>

<!-- Correct - Regular roles never have this right -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the right for all roles except FullRights
2. Configuration update is a technical operation
3. Perform updates only through scheduled procedures
4. Maintain a configuration change log

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightUpdateDatabaseConfiguration`
- **Plugin**: `com.e1c.v8codestyle.right`

