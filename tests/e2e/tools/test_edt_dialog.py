"""EDT modal discovery, refusal contracts and an attended native-dialog round trip."""
from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_no_diff,
    e2e_test, requires_live_infobase, LIVE_LAUNCH_CONFIG, wait_until_no_running_launch,
)


@e2e_test(tool="edt_dialog", kind="read")
def test_edt_dialog_lists_typed_shells():
    result = call("edt_dialog", {"action": "list"})
    assert_ok(result)
    assert isinstance(result.structured["dialogs"], list)
    for dialog in result.structured["dialogs"]:
        assert isinstance(dialog["dialogId"], str) and dialog["dialogId"]
        assert isinstance(dialog["title"], str)
        assert isinstance(dialog["message"], str)
        assert isinstance(dialog["buttons"], list)
        assert isinstance(dialog["answerable"], bool)
        if not dialog["answerable"]:
            assert dialog["reason"] == "ask the user to answer it in EDT"
    assert_no_diff()


@e2e_test(tool="edt_dialog", kind="read")
def test_edt_dialog_rejects_invalid_action_and_missing_press_arguments():
    for arguments, names, suggests in [
        ({"action": "invented"}, ["invented"], ["list", "press"]),
        ({"action": "press"}, ["dialogId", "button"], ["list"]),
        ({"action": "list", "button": "Cancel"}, ["button"], ["press"]),
        ({"action": "press", "dialogId": "closed-e2e-shell", "button": "Cancel"},
         ["closed-e2e-shell"], ["list again"]),
    ]:
        error = assert_error(call("edt_dialog", arguments))
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()


@e2e_test(tool="edt_dialog", kind="write-metadata")
def test_edt_dialog_live_launch_false_reports_and_cancels_application_update():
    requires_live_infobase("Prepare an out-of-date infobase so launch(false) opens Application update.")
    configurations = call("list_configurations", {})
    assert_ok(configurations)
    config = next(c for c in configurations.structured["configurations"]
                  if c["name"] == LIVE_LAUNCH_CONFIG)
    forms = ({"launchConfigurationName": LIVE_LAUNCH_CONFIG},
             {"projectName": config["project"], "applicationId": config["applicationId"]})
    for target in forms:
        wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG)
        launched = call("launch", dict(target, updateBeforeLaunch=False))
        assert_ok(launched)
        observed = launched.structured["edtDialogs"]
        assert any(d["title"] in ("Application update", "Обновление приложения") for d in observed)
        listed = call("edt_dialog", {"action": "list"})
        assert_ok(listed)
        dialog = next(d for d in listed.structured["dialogs"]
                      if d["title"] in ("Application update", "Обновление приложения"))
        assert dialog["answerable"] is True
        wrong = assert_error(call("edt_dialog", {"action": "press", "dialogId": dialog["dialogId"],
                                                "button": "not-a-dialog-button"}))
        assert_error_quality(wrong, names=["not-a-dialog-button"], suggests=["Available labels", "list"])
        cancel = next(label for label in dialog["buttons"] if label in ("Cancel", "Отмена", "Отменить"))
        pressed = call("edt_dialog", {"action": "press", "dialogId": dialog["dialogId"], "button": cancel})
        assert_ok(pressed)
        after = call("edt_dialog", {"action": "list"})
        assert_ok(after)
        assert dialog["dialogId"] not in [d["dialogId"] for d in after.structured["dialogs"]]
        stale = assert_error(call("edt_dialog", {"action": "press", "dialogId": dialog["dialogId"],
                                                "button": cancel}))
        assert_error_quality(stale, names=[dialog["dialogId"]], suggests=["list again"])
        assert_no_diff()
    wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG)
