#pragma once

#include <QString>

#ifdef Q_OS_WIN32
#include <cstring>
#include <windows.h>

namespace RhClipboard {
inline bool writeText(quintptr streamWindow, const QString& text) {
    constexpr qsizetype maxTextUnits = 16384;
    const auto window = reinterpret_cast<HWND>(streamWindow);
    DWORD owner = 0;
    if (!window || !GetWindowThreadProcessId(window, &owner) || owner != GetCurrentProcessId() ||
        text.isEmpty() || text.size() > maxTextUnits || text.contains(QChar::Null)) {
        return false;
    }

    const auto size = (text.size() + 1) * sizeof(wchar_t);
    const auto memory = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!memory) return false;
    auto* data = GlobalLock(memory);
    if (!data) {
        GlobalFree(memory);
        return false;
    }
    std::memcpy(data, text.utf16(), size);
    GlobalUnlock(memory);

    if (!OpenClipboard(window)) {
        GlobalFree(memory);
        return false;
    }
    const bool written = EmptyClipboard() && SetClipboardData(CF_UNICODETEXT, memory) != nullptr;
    CloseClipboard();
    if (!written) GlobalFree(memory);
    return written;
}
}
#endif
