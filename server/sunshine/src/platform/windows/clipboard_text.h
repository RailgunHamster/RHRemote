/**
 * @file src/platform/windows/clipboard_text.h
 * @brief Read a bounded text snapshot from the live Windows desktop.
 */
#pragma once

#include <algorithm>
#include <array>
#include <string>
#include <string_view>
#include <utility>

#include <windows.h>

#include "../../utility.h"

namespace platf::clipboard {
  constexpr size_t max_text_units = 16384;  ///< Limit in UTF-16 code units, excluding the terminator.

  /** @brief Result of one bounded remote clipboard read. */
  struct snapshot_t {
    std::string text;  ///< UTF-8 text, populated on success.
    std::string_view error;  ///< Fixed diagnostic with no clipboard data.
  };

  /** @brief Check that Windows is showing the interactive desktop. @return False on lock and secure desktops. */
  inline bool desktop_available() {
    const auto desktop = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
    if (!desktop) {
      return false;
    }
    auto close = util::fail_guard([&] { CloseDesktop(desktop); });
    std::array<wchar_t, 16> name {};
    DWORD required = 0;
    return GetUserObjectInformationW(desktop, UOI_NAME, name.data(), sizeof(name), &required) &&
           std::wstring_view(name.data(), 7) == L"Default" && name[7] == L'\0';
  }

  /**
   * @brief Decode an owned clipboard allocation without reading beyond its bounds.
   * @param data UTF-16 data while its clipboard memory handle is locked.
   * @param units Number of allocated code units.
   * @return A UTF-8 snapshot or a diagnostic for empty, oversized or invalid text.
   */
  inline snapshot_t decode(const wchar_t *data, size_t units) {
    if (!data || units == 0) {
      return {{}, "No text on the remote clipboard"};
    }
    const auto bound = std::min(units, max_text_units + 1);
    const auto end = std::find(data, data + bound, L'\0');
    if (end == data) {
      return {{}, "No text on the remote clipboard"};
    }
    if (end == data + bound) {
      return {{}, "Remote clipboard text is too long or invalid"};
    }
    const auto length = static_cast<int>(end - data);
    const auto size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, data, length, nullptr, 0, nullptr, nullptr);
    if (size <= 0) {
      return {{}, "Invalid remote clipboard text"};
    }
    std::string text(size, '\0');
    if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, data, length, text.data(), size, nullptr, nullptr) != size) {
      return {{}, "Invalid remote clipboard text"};
    }
    return {std::move(text), {}};
  }

  /** @brief Read the interactive window station's current Unicode text once. @return The text or a diagnostic. */
  inline snapshot_t read_text() {
    if (!desktop_available()) {
      return {{}, "Unlock the remote desktop to copy its clipboard text"};
    }
    if (!OpenClipboard(nullptr)) {
      return {{}, "Remote clipboard is in use; try again"};
    }
    auto close = util::fail_guard([] { CloseClipboard(); });
    const auto memory = GetClipboardData(CF_UNICODETEXT);
    if (!memory) {
      return {{}, "No text on the remote clipboard"};
    }
    const auto *data = static_cast<const wchar_t *>(GlobalLock(memory));
    if (!data) {
      return {{}, "Remote clipboard text is unavailable"};
    }
    auto unlock = util::fail_guard([&] { GlobalUnlock(memory); });
    return decode(data, GlobalSize(memory) / sizeof(wchar_t));
  }
}  // namespace platf::clipboard
