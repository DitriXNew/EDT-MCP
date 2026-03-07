# Right set: Start External Connection

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-start-external-connection` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `StartExternalConnection` right in roles. This right allows connecting to the infobase through external COM connection. Used for integration but should be limited to service accounts.

## ❌ Error Examples

```xml
<!-- Incorrect - ExternalConnection for regular user -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - All users can connect externally -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - ExternalConnection for integration service only -->
<!-- Role: IntegrationService -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Regular users cannot connect externally -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>false</Value>
  </Right>
</Rights>

<!-- Correct - Administrator for maintenance -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the right for all user roles
2. Keep the right for service accounts
3. Use special roles for integration connections
4. Maintain a log of external connections

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightStartExternalConnection`
- **Plugin**: `com.e1c.v8codestyle.right`

