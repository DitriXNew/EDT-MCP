# set_infobase_credentials

Follow [SETUP](SETUP.md). Use a test user that exists in the infobase; never record its password.

```text
set_infobase_credentials({"projectName":"TestConfiguration","applicationId":"<test-application-id>","user":"<test-user>","password":"<test-password>"})
set_infobase_credentials({"launchConfigurationName":"<test-client-name>","user":"<test-user>","password":"<test-password>"})
```

After each call, re-read applications and RuntimeClient configurations: the explicit default
and application selections must survive. The first form stores agent credentials only;
the second must also report `clientConfigured=true` and update client credentials while
retaining its application binding. Verify recovery independently of credential success;
unresolved bindings require an error naming the settings still to restore.

Restore test credentials through the same tool and restore the test client's credential
settings in EDT. Credential storage persists beyond the call and source-fixture resets.
