# Right set: Start Thick Client

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-start-thick-client` |
| **Category** | Role Rights |
| **Severity** | Major |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `StartThickClient` right in roles. Thick client has more capabilities than thin or web client, including file system access. Should be restricted for improved security.

## ❌ Error Examples

```xml
<!-- Potentially incorrect - ThickClient for all users -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - External users with thick client -->
<!-- Role: ExternalUser -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - ThickClient for administrators -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Regular users use thin client -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThinClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Specific role for thick client users -->
<!-- Role: LocalUser -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Determine which roles need thick client
2. Disable the right for roles working through web or thin client
3. Use thin client as the primary operating mode
4. Document reasons for thick client usage

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightStartThickClient`
- **Plugin**: `com.e1c.v8codestyle.right`

