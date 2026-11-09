/**
 * @file src/rhremote_server_update.h
 * @brief Read the SYSTEM/admin-owned update hold; never accept download locations from peers.
 */
#pragma once
#include "rhremote_update_gate.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <nlohmann/json.hpp>
#include <stdexcept>

namespace rhremote::server_update {
  /** @brief Reject unbounded sources and unsupported settings. @param value Administrator settings. @return Accepted settings unchanged. */
  inline nlohmann::json settings(const nlohmann::json &value) {
    if (!value.is_object() || value.size() != 4 || value.value("schemaVersion", 0) != 1 || !value.at("enabled").is_boolean()) {
      throw std::runtime_error("Invalid server update settings");
    }
    const auto source = value.at("preferredSource").get<std::string>();
    const auto minutes = value.at("checkIntervalMinutes").get<int>();
    if ((source != "cnb" && source != "github") || (minutes != 5 && minutes != 15 && minutes != 30 && minutes != 60)) {
      throw std::runtime_error("Unsupported update source or interval");
    }
    return value;
  }

  /** @brief Shared launch admission guard. @return Process-local update gate. */
  inline update_gate &gate() {
    static update_gate value;
    return value;
  }

  /** @brief Locate the service's protected update state. @return Fixed local directory or an empty path on other platforms. */
  inline std::filesystem::path root() {
#ifdef _WIN32
    const auto base = _wgetenv(L"ProgramData");
    return base ? std::filesystem::path(base) / L"RHRemoteHost" / L"updates" : std::filesystem::path {};
#else
    return {};
#endif
  }

  /** @brief Read a small protected JSON document. @param path Local state file. @return Parsed state. */
  inline nlohmann::json read(const std::filesystem::path &path) {
    if (std::filesystem::file_size(path) > 65536) {
      throw std::runtime_error("Server update state exceeds its limit");
    }
    std::ifstream input(path);
    return nlohmann::json::parse(input);
  }

  /** @brief Validate expiry without trusting malformed maintenance state. @param value Protected hold. @param now Current Unix seconds. @return Whether admission must remain held. */
  inline bool active_hold(const nlohmann::json &value, long long now) {
    if (!value.is_object() || value.value("schema", "") != "rhremote.update-hold/v1") {
      return true;
    }
    const auto id = value.value("transactionId", "");
    if (id.size() != 32 || !std::all_of(id.begin(), id.end(), [](char v) {
          return (v >= '0' && v <= '9') || (v >= 'a' && v <= 'f');
        })) {
      return true;
    }
    const auto expires = value.at("expiresAt").get<long long>();
    return expires > now;
  }

  /** @brief Check the updater hold; absent state leaves normal streaming unchanged. @return True only while held, conservatively including malformed state. */
  inline bool held() {
    const auto directory = root();
    if (directory.empty()) {
      return false;
    }
    const auto file = directory / "hold.json";
    std::error_code error;
    if (!std::filesystem::exists(file, error)) {
      return static_cast<bool>(error);
    }
    try {
      const auto now = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
      return active_hold(read(file), now);
    } catch (...) {
      return true;
    }
  }
}  // namespace rhremote::server_update
