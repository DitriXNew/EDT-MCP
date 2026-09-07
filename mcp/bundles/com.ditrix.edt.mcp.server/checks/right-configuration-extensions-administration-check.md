# Right set: Configuration Extensions Administration

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-configuration-extensions-administration` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `ConfigurationExtensionsAdministration` right in roles. This right allows installing, removing, and managing configuration extensions. Extensions can contain arbitrary code, so this right must be strictly limited.

## ❌ Error Examples

```xml
<!-- Incorrect - Extension administration for non-admin -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Multiple roles can manage extensions -->
<!-- Role: Developer -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Only Administrator manages extensions -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Other roles cannot manage extensions -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the right for all roles except Administrator
2. Extensions can execute arbitrary code
3. Installing extensions is a critical operation
4. Control extensions through the security system

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightConfigurationExtensionsAdministration`
- **Plugin**: `com.e1c.v8codestyle.right`

