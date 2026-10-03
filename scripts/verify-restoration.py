"""Standalone ADB restart check; touches only the com.carmusic.app.smoke fixture package."""
import io
import json
import pathlib
import re
import subprocess
import sys
import time
import wave
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "com.carmusic.app.smoke"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else "DEVICE_SERIAL"

def adb(*args, data=None):
    return subprocess.run(["adb", "-s", SERIAL, *args], input=data, capture_output=True, check=True, timeout=40).stdout.decode("utf-8", errors="replace")

def snapshot():
    adb("shell", "uiautomator", "dump", "/sdcard/carmusic-smoke-restore.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/carmusic-smoke-restore.xml"))

def text_values(tree):
    return [node.get("text", "") for node in tree.iter("node") if node.get("package") == PACKAGE]

def click(tree, label):
    node = next(node for node in tree.iter("node") if node.get("text") == label and node.get("package") == PACKAGE)
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError("Invalid fixture button bounds")
    adb("shell", "input", "tap", str((values[0] + values[2]) // 2), str((values[1] + values[3]) // 2))

formal_path = adb("shell", "pm", "path", "com.carmusic.app").strip()
if not formal_path:
    raise AssertionError("Formal package must remain installed")
apk = ROOT / "app/build/outputs/apk/smoke/app-smoke.apk"
adb("install", "-r", str(apk))
try:
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(44100)
        audio.writeframes(bytes(44100 * 2 * 30))
    adb("shell", "run-as", PACKAGE, "sh", "-c", "'cat > files/restore.wav'", data=buffer.getvalue())
    first = dict(id="restore-one", source="local", name="Restore One", artist="Fixture", album="Restart Test", duration=30, url=f"file:///data/user/0/{PACKAGE}/files/restore.wav")
    second = dict(first, id="restore-two", name="Restore Two")
    prefs = ET.Element("map")
    for key, value in {"queue": json.dumps([first, second]), "current": "local:restore-one", "mode": "SEQUENCE", "theme": "night"}.items():
        ET.SubElement(prefs, "string", name=key).text = value
    ET.SubElement(prefs, "long", name="position", value="19000")
    ET.SubElement(prefs, "boolean", name="autoplay", value="false")
    adb("shell", "run-as", PACKAGE, "sh", "-c", "'cat > shared_prefs/carmusic.xml'", data=ET.tostring(prefs, encoding="utf-8", xml_declaration=True))
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", f"{PACKAGE}/com.carmusic.app.MainActivity")
    time.sleep(3)
    tree = snapshot()
    values = text_values(tree)
    assert "Restore One" in values, "Current song not restored"
    assert "00:19 / 00:30" in values, "Saved position not restored"
    assert "继续播放" in values, "Autoplay must remain off"
    click(tree, "继续播放")
    time.sleep(1)
    tree = snapshot()
    values = text_values(tree)
    assert "暂停" in values, "Resume did not play fixture audio"
    seconds = [int(value[2:]) for value in values if re.fullmatch(r"0:\d\d", value) and value != "0:30"]
    assert any(19 <= value <= 26 for value in seconds), f"Audio resumed at wrong position: {seconds}"
    click(tree, "暂停")
    time.sleep(1)
    saved = ET.fromstring(adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/carmusic.xml"))
    saved_pos = int(saved.find("long[@name='position']").get("value"))
    assert 19000 <= saved_pos < 30000
    assert len(json.loads(saved.find("string[@name='queue']").text)) == 2
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", f"{PACKAGE}/com.carmusic.app.MainActivity")
    time.sleep(3)
    values = text_values(snapshot())
    assert "Restore One" in values and "继续播放" in values
    assert f"00:{saved_pos // 1000:02d} / 00:30" in values
    print("PASS: queue/current/progress restored; autoplay off; audio resumes at saved position; pause survives second process restart.")
finally:
    adb("shell", "am", "force-stop", PACKAGE)
    adb("uninstall", PACKAGE)
    assert adb("shell", "pm", "path", "com.carmusic.app").strip() == formal_path, "Formal package changed"
