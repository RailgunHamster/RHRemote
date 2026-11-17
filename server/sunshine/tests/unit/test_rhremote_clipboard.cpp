/** @file tests/unit/test_rhremote_clipboard.cpp @brief Bounded clipboard text conversion tests. */
#ifdef _WIN32
  #include <gtest/gtest.h>

  #include "src/platform/windows/clipboard_text.h"

TEST(RhRemoteClipboard, CopiesUnicodeAndLineEndingsWithoutReadingPadding) {
  constexpr wchar_t source[] = L"\u4F60\u597D \U0001F642\r\nline\0ignored";
  const auto snapshot = platf::clipboard::decode(source, std::size(source));
  EXPECT_TRUE(snapshot.error.empty());
  EXPECT_EQ(snapshot.text, "\xE4\xBD\xA0\xE5\xA5\xBD \xF0\x9F\x99\x82\r\nline");
}

TEST(RhRemoteClipboard, RejectsEmptyBrokenAndOverlongText) {
  constexpr wchar_t unterminated[] = {L'A'};
  constexpr wchar_t malformed[] = {static_cast<wchar_t>(0xD800), 0};
  EXPECT_FALSE(platf::clipboard::decode(nullptr, 0).error.empty());
  EXPECT_FALSE(platf::clipboard::decode(L"", 1).error.empty());
  EXPECT_FALSE(platf::clipboard::decode(unterminated, 1).error.empty());
  EXPECT_FALSE(platf::clipboard::decode(malformed, std::size(malformed)).error.empty());

  std::wstring at_limit(platf::clipboard::max_text_units, L'A');
  EXPECT_EQ(platf::clipboard::decode(at_limit.c_str(), at_limit.size() + 1).text.size(), at_limit.size());
  at_limit.push_back(L'B');
  EXPECT_FALSE(platf::clipboard::decode(at_limit.c_str(), at_limit.size() + 1).error.empty());
}
#endif
