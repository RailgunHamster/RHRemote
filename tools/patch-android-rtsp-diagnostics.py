"""Add bounded metadata to existing RTSP rejection logs; never log ciphertext or keys."""
import argparse
from pathlib import Path


def patch(root):
    path = root / "app/src/main/jni/moonlight-core/moonlight-common-c/src/RtspConnection.c"
    text = path.read_text(encoding="utf-8")
    changes = [
        (
            "static bool unsealRtspMessage(char* rawMessage, int rawMessageLen, PRTSP_MESSAGE response) {",
            "static bool unsealRtspMessage(char* rawMessage, int rawMessageLen, PRTSP_MESSAGE response, const char* command) {",
        ),
        (
            "ret = unsealRtspMessage(responseBuffer, offset, response);",
            "ret = unsealRtspMessage(responseBuffer, offset, response, request->message.request.command);",
        ),
        (
            'Limelog("RTSP encrypted header too small\\n");',
            'Limelog("RTSP encrypted header too small: method=%s expected_min=%u received=%d interrupted=%d\\n", command, (unsigned int)sizeof(ENC_RTSP_HEADER) + 1, rawMessageLen, ConnectionInterrupted ? 1 : 0);',
        ),
        (
            'Limelog("Rejecting partial encrypted RTSP message\\n");',
            'Limelog("Rejecting partial encrypted RTSP message: method=%s expected=%u received=%d interrupted=%d\\n", command, (unsigned int)(len + sizeof(ENC_RTSP_HEADER)), rawMessageLen, ConnectionInterrupted ? 1 : 0);',
        ),
        (
            'Limelog("Rejecting encrypted RTSP message with excess data\\n");',
            'Limelog("Rejecting encrypted RTSP message with excess data: method=%s expected=%u received=%d interrupted=%d\\n", command, (unsigned int)(len + sizeof(ENC_RTSP_HEADER)), rawMessageLen, ConnectionInterrupted ? 1 : 0);',
        ),
    ]
    for before, after in changes:
        if after in text:
            continue
        if text.count(before) != 1:
            raise RuntimeError(f"RTSP diagnostics anchor missing or ambiguous: {before[:80]}")
        text = text.replace(before, after, 1)
    # All checks complete before writing. Earlier ordered patches retain upstream originals.
    path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    patch(parser.parse_args().source)
