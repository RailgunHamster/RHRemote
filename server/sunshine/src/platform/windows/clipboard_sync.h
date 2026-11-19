/**
 * @file src/platform/windows/clipboard_sync.h
 * @brief Windows clipboard backend for RHRemote clipboard synchronization.
 */
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "src/rhremote_clipboard.h"

namespace platf::clipboard_sync {
  /** @brief Result of reading one host clipboard format. */
  struct read_result {
    std::shared_ptr<const std::vector<std::uint8_t>> data;  ///< Snapshot on success.
    std::string error;  ///< Fixed diagnostic without clipboard content.
    int status = 200;  ///< HTTP-style status for the caller.
  };

  /**
   * @brief Owns a hidden clipboard window on its own thread.
   *
   * Host clipboard changes are announced through the broker. Client announcements take delayed ownership of the
   * host clipboard; WM_RENDERFORMAT then asks the owning client for the data.
   */
  class host {
  public:
    /**
     * @brief Bind to a broker; the broker must outlive this object.
     * @param broker Shared protocol state.
     * @param desktop_available Whether the interactive desktop may share its clipboard; defaults to the input-desktop check.
     */
    explicit host(rhremote::clipboard::broker &broker, std::function<bool()> desktop_available = {});
    ~host();
    host(const host &) = delete;
    host &operator=(const host &) = delete;

    /** @brief Start the clipboard thread once. @return Whether the window is ready. */
    bool start();

    /** @brief Stop the thread without rendering outstanding delayed formats. */
    void stop();

    /**
     * @brief Take delayed ownership for a client's clipboard.
     * @param peer Verified client certificate.
     * @param formats Validated formats in client-local identifiers.
     * @return Empty on success, otherwise a fixed diagnostic.
     */
    std::string announce(const std::string &peer, const std::vector<rhremote::clipboard::format_t> &formats);

    /**
     * @brief Read one format of the current host clipboard.
     * @param seq Sequence from the announcement the client is answering.
     * @param format Host-local format identifier.
     * @return Data snapshot or diagnostic.
     */
    read_result read(std::uint64_t seq, std::uint32_t format);

    /** @brief Release ownership held for a client whose stream ended. @param peer Client certificate. */
    void peer_gone(const std::string &peer);

    /** @brief Deliver a render that finished after the pasting application gave up. @param request Broker request. @param data Bytes. */
    void late_ready(std::uint64_t request, std::vector<std::uint8_t> data);

    /** @brief Memory one incoming format may use now: half of the available physical memory. @return Byte budget. */
    static std::uint64_t memory_budget();

  private:
    struct impl;
    std::unique_ptr<impl> impl_;
  };
}  // namespace platf::clipboard_sync
