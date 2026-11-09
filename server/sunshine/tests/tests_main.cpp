/**
 * @file tests/tests_main.cpp
 * @brief Entry point definition.
 */

// test includes
#include "tests_common.h"
#include "tests_environment.h"
#include "tests_events.h"
#ifdef _WIN32
  #include "src/platform/windows/capture_context.h"
  #include "src/platform/windows/window_capture_worker.h"

  #include <dbghelp.h>

namespace {
  /** @brief Record only this owned test process on an unhandled crash. @param failure Native exception. @return Continue normal OS reporting. */
  LONG WINAPI dump_test_failure(EXCEPTION_POINTERS *failure) {
    const auto library = LoadLibraryW(L"dbghelp.dll");
    if (library) {
      const auto write = reinterpret_cast<decltype(&MiniDumpWriteDump)>(GetProcAddress(library, "MiniDumpWriteDump"));
      const auto name = L"window-worker-crash-" + std::to_wstring(GetCurrentProcessId()) + L".dmp";
      const auto file = CreateFileW(name.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
      if (write && file != INVALID_HANDLE_VALUE) {
        MINIDUMP_EXCEPTION_INFORMATION details {GetCurrentThreadId(), failure, FALSE};
        write(GetCurrentProcess(), GetCurrentProcessId(), file, MiniDumpNormal, &details, nullptr, nullptr);
      }
      if (file != INVALID_HANDLE_VALUE) {
        CloseHandle(file);
      }
      FreeLibrary(library);
    }
    return EXCEPTION_CONTINUE_SEARCH;
  }
}  // namespace

namespace rhremote::window_testing {
  /** @brief Run only an explicitly requested synthetic-window GPU integration check. @param argc Argument count. @param argv Arguments. @return Check exit code, or no value for normal tests. */
  std::optional<int> arguments(int argc, char **argv);
}  // namespace rhremote::window_testing
#endif

int main(int argc, char **argv) {
#ifdef _WIN32
  SetUnhandledExceptionFilter(dump_test_failure);
  platf::capture_context::process_security_t security;
  if (const auto worker = platf::window_capture::worker_arguments(argc, argv)) {
    return *worker;
  }
  if (const auto integration = rhremote::window_testing::arguments(argc, argv)) {
    return *integration;
  }
#endif
  testing::InitGoogleTest(&argc, argv);
  testing::AddGlobalTestEnvironment(new SunshineEnvironment);
  testing::UnitTest::GetInstance()->listeners().Append(new SunshineEventListener);
  return RUN_ALL_TESTS();
}
