/**
 * @file src/rhremote_pending_launch.h
 * @brief Bound request ownership and atomically arbitrate cancellation versus RTSP handoff.
 */
#pragma once

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

namespace rhremote {
  /** @brief Results contain no request credential or certificate data. */
  enum class launch_result {
    accepted,
    invalid,
    forbidden,
    cancelled,
    duplicate,
    capacity,
    busy,
    expired,
    handed_off,
    finished,
    closed
  };

  template<class Session>
  class pending_launches;

  /** @brief Opaque lifetime retained while an HTTP request is being prepared. */
  class launch_ticket {
    template<class Session>
    friend class pending_launches;
    /** @brief Internal state; only the owning registry may change it. */
    enum class phase {
      preparing,
      published,
      claimed,
      cancelled,
      completed,
      expired
    };
    std::string credential_;  ///< Fresh 256-bit client credential, never logged or used as a path.
    std::string owner_;  ///< Exact authenticated certificate identity supplied by the HTTP layer.
    phase phase_ = phase::preparing;  ///< Current request ownership phase.
    std::chrono::steady_clock::time_point expires_;  ///< Bounded preparation/history lifetime.
  };

  /** @brief Admission outcome and opaque request handle. */
  struct launch_admission {
    launch_result result;  ///< Admission outcome.
    std::shared_ptr<launch_ticket> ticket;  ///< Present only for newly admitted preparation.
  };

  /**
   * @brief Own one pending RTSP slot and bounded per-request preparation/cancellation history.
   * @tparam Session Shared session type with an integral id and canonical client_cert string.
   * @note Clients must generate a fresh credential for every actual launch/resume. The registry
   *       does not use client wall clocks, mutable global TLS identities, or an asynchronous timer.
   */
  template<class Session>
  class pending_launches {
  public:
    using clock = std::chrono::steady_clock;  ///< Monotonic lifecycle clock.
    using ticket = std::shared_ptr<launch_ticket>;  ///< Opaque HTTP preparation handle.
    using session = std::shared_ptr<Session>;  ///< Session retained through RTSP setup.

    /** @brief Set explicit cache bounds. @param capacity Maximum retained request records. @param retention Preparation/history lifetime. */
    explicit pending_launches(std::size_t capacity = 256, clock::duration retention = std::chrono::seconds {120}):
        capacity_(capacity),
        retention_(retention) {
    }

    /** @brief Validate a fresh request credential without accepting filenames or URL syntax. @param value Credential to validate. @return Whether it is 64 lowercase hexadecimal characters. */
    static bool valid(std::string_view value) {
      return value.size() == 64 && std::all_of(value.begin(), value.end(), [](char c) {
               return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
             });
    }

    /** @brief Admit HTTP preparation, honoring a cancellation received before the request. @param credential Fresh client secret. @param owner Authenticated certificate identity. @param now Current monotonic time. @return Admission and opaque handle. */
    launch_admission begin(std::string_view credential, std::string_view owner, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      if (closed_) {
        return {launch_result::closed, {}};
      }
      if (!valid(credential) || owner.empty() || owner.size() > 65536) {
        return {launch_result::invalid, {}};
      }
      auto found = records_.find(std::string(credential));
      if (found != records_.end()) {
        if (found->second->owner_ != owner) {
          return {launch_result::forbidden, {}};
        }
        return {found->second->phase_ == phase::cancelled ? launch_result::cancelled : launch_result::duplicate, {}};
      }
      if (records_.size() >= capacity_) {
        return {launch_result::capacity, {}};
      }
      auto request = make_ticket(credential, owner, now);
      records_.emplace(request->credential_, request);
      return {launch_result::accepted, request};
    }

    /** @brief Check preparation at real side-effect boundaries; a null handle is a legacy request. @param request Request handle. @param now Current monotonic time. @return Whether preparation may continue. */
    bool preparing(const ticket &request, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      return !closed_ && (!request || (registered(request) && request->phase_ == phase::preparing));
    }

    /** @brief Finish a preparation that did not publish; never erase a newer request or a cancellation. @param request Original preparation handle. @param now Current monotonic time. */
    void finish_preparing(const ticket &request, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      if (request && registered(request) && request->phase_ == phase::preparing) {
        request->phase_ = phase::completed;
        request->expires_ = now + retention_;
      }
    }

    /** @brief Publish only the still-owned request into the single pending slot. @param value Prepared session. @param request Original handle, or null for legacy clients. @param timeout RTSP setup timeout. @param now Current monotonic time. @return Whether the session was published. */
    bool publish(session value, const ticket &request, clock::duration timeout, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      if (closed_ || !value || !value->id || slot_ || timeout <= clock::duration::zero() || (request && (!registered(request) || request->phase_ != phase::preparing || value->client_cert != request->owner_))) {
        return false;
      }
      if (request) {
        request->phase_ = phase::published;
      }
      slot_ = slot {std::move(value), request, now + timeout, false};
      return true;
    }

    /** @brief Obtain the current unexpired slot without waiting or transferring ownership. @param now Current monotonic time. @return Session, or empty. */
    session pending(clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      return slot_ ? slot_->value : session {};
    }

    /** @brief Atomically win ANNOUNCE admission versus cancellation/expiry; only one stream may be allocated. @param value Actual session held by the RTSP socket. @param now Current monotonic time. @return Whether this call won the handoff. */
    bool claim(const Session &value, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      if (!slot_ || slot_->value.get() != &value || slot_->claimed) {
        return false;
      }
      slot_->claimed = true;
      if (slot_->request) {
        slot_->request->phase_ = phase::claimed;
      }
      return true;
    }

    /** @brief Clear only the matching upstream session ID after control handoff or setup failure. @param id Server-generated session ID. @param now Current monotonic time. @return Whether the matching slot was cleared. */
    bool clear(std::uint32_t id, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      if (!slot_ || slot_->value->id != id) {
        return false;
      }
      retire_slot(phase::completed, now);
      return true;
    }

    /** @brief Cancel this authenticated request, including cancellation before HTTP preparation starts. @param credential Per-request secret. @param owner Actual paired TLS identity. @param now Current monotonic time. @return Idempotent cancellation/handoff outcome. */
    launch_result cancel(std::string_view credential, std::string_view owner, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      prune(now);
      if (closed_) {
        return launch_result::closed;
      }
      if (!valid(credential) || owner.empty() || owner.size() > 65536) {
        return launch_result::invalid;
      }
      auto found = records_.find(std::string(credential));
      if (found == records_.end()) {
        if (records_.size() >= capacity_) {
          return launch_result::capacity;
        }
        auto request = make_ticket(credential, owner, now);
        request->phase_ = phase::cancelled;
        records_.emplace(std::string(credential), request);
        return launch_result::cancelled;
      }
      auto request = found->second;
      if (request->owner_ != owner) {
        return launch_result::forbidden;
      }
      if (request->phase_ == phase::claimed) {
        return launch_result::handed_off;
      }
      if (request->phase_ == phase::completed) {
        return launch_result::finished;
      }
      if (request->phase_ == phase::expired) {
        return launch_result::expired;
      }
      request->phase_ = phase::cancelled;
      request->expires_ = now + retention_;
      if (slot_ && slot_->request == request) {
        slot_.reset();
      }
      return launch_result::cancelled;
    }

    /** @brief Invalidate this revoked owner's pending requests; active stream teardown is the caller's existing responsibility. @param owner Canonical revoked certificate. @param now Current monotonic time. */
    void revoke(std::string_view owner, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      for (auto &[key, request] : records_) {
        if (request->owner_ == owner) {
          request->phase_ = phase::cancelled;
          request->expires_ = now + retention_;
        }
      }
      if (slot_ && slot_->value->client_cert == owner) {
        slot_.reset();
      }
    }

    /** @brief Explicit administrative/session shutdown, never used for per-request cancellation. @param permanent Whether the server is shutting down. @param now Current monotonic time. */
    void reset(bool permanent = false, clock::time_point now = clock::now()) {
      std::lock_guard lock {mutex_};
      for (auto &[key, request] : records_) {
        request->phase_ = phase::cancelled;
        request->expires_ = now + retention_;
      }
      slot_.reset();
      closed_ = closed_ || permanent;
    }

  private:
    using phase = launch_ticket::phase;  ///< State protected by this registry's mutex.

    /** @brief Single RTSP slot retained until the existing control-connect clear. */
    struct slot {
      session value;  ///< Exact session accepted by RTSP sockets.
      ticket request;  ///< Empty for legacy clients.
      clock::time_point deadline;  ///< Monotonic expiration, not an asynchronous timer callback.
      bool claimed;  ///< ANNOUNCE has already won stream allocation.
    };

    /** @brief Build a bounded request record. @param credential Client secret. @param owner Paired TLS identity. @param now Current time. @return New request record. */
    ticket make_ticket(std::string_view credential, std::string_view owner, clock::time_point now) const {
      auto value = std::make_shared<launch_ticket>();
      value->credential_ = credential;
      value->owner_ = owner;
      value->expires_ = now + retention_;
      return value;
    }

    /** @brief Reject an old handle even if its expired credential has since been reused. @param request Original handle. @return Whether it is still the same registered request. */
    bool registered(const ticket &request) const {
      const auto found = records_.find(request->credential_);
      return found != records_.end() && found->second == request;
    }

    /** @brief Release only the currently locked slot. @param outcome Terminal state. @param now Current time. */
    void retire_slot(phase outcome, clock::time_point now) {
      if (slot_->request) {
        slot_->request->phase_ = outcome;
        slot_->request->expires_ = now + retention_;
      }
      slot_.reset();
    }

    /** @brief Expire using the same mutex as publication/cancellation; no stale timer can remove a new slot. @param now Current time. */
    void prune(clock::time_point now) {
      if (slot_ && now >= slot_->deadline) {
        retire_slot(phase::expired, now);
      }
      for (auto it = records_.begin(); it != records_.end();) {
        if (now >= it->second->expires_ && (!slot_ || slot_->request != it->second)) {
          it->second->phase_ = phase::expired;
          it = records_.erase(it);
        } else {
          ++it;
        }
      }
    }

    std::mutex mutex_;  ///< Serializes ownership decisions only, never HTTP, encoder or socket work.
    std::unordered_map<std::string, ticket> records_;  ///< Bounded fresh credentials and cancellation history.
    std::optional<slot> slot_;  ///< At most one pending RTSP handshake.
    std::size_t capacity_;  ///< Maximum history size, including live preparation.
    clock::duration retention_;  ///< Preparation/history retention.
    bool closed_ = false;  ///< Permanent shutdown admission guard.
  };
}  // namespace rhremote
