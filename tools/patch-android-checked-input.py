"""Keep the upstream void input API; add a checked bridge for RHRemote controls."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/limelight/nvstream/jni/MoonBridge.java"
native = root / "app/src/main/jni/moonlight-core/simplejni.c"
name = "rhSendKeyboardInputChecked"
declaration = "    public static native int rhSendKeyboardInputChecked(short keyMap, byte keyDirection, byte modifier, byte flags);"
anchor = "    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);"
text = java.read_text(encoding="utf-8")
if name not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Checked keyboard Java anchor changed")
    text = text.replace(anchor, declaration + "\n\n" + anchor)
elif text.count(declaration) != 1:
    raise SystemExit("Unexpected checked keyboard Java declaration")
java.write_text(text, encoding="utf-8")

implementation = """JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_rhSendKeyboardInputChecked(JNIEnv *env, jclass clazz, jshort keyCode, jbyte keyAction, jbyte modifiers, jbyte flags) {
    return LiSendKeyboardEvent2(keyCode, keyAction, modifiers, flags);
}
"""
text = native.read_text(encoding="utf-8")
if name not in text:
    anchor = "JNIEXPORT void JNICALL\nJava_com_limelight_nvstream_jni_MoonBridge_sendKeyboardInput("
    if text.count(anchor) != 1:
        raise SystemExit("Checked keyboard JNI anchor changed")
    text = text.replace(anchor, implementation + "\n" + anchor)
elif text.count(implementation) != 1:
    raise SystemExit("Unexpected checked keyboard JNI implementation")
native.write_text(text, encoding="utf-8")
