/**
 * @file src/rhremote_launch_http.h
 * @brief Bind HTTP requests to their verified TLS peer and bound deferred launch preparation.
 */
#pragma once

#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>

namespace rhremote {
  /** @brief Authenticated identity copied from the actual TLS handshake, never from HTTP fields. */
  struct launch_peer {
    std::string certificate;  ///< Canonical public certificate of the paired peer.
    std::string name;  ///< Paired device name captured with the same authorization check.
  };

  /** @brief Bounded weak request bindings; request address reuse cannot inherit another TLS identity. @tparam Request Actual HTTP request type. */
  template<class Request>
  class verified_launch_requests {
  public:
    /** @brief Set the number of simultaneous request bindings. @param capacity Maximum live requests. */
    explicit verified_launch_requests(std::size_t capacity = 256):
        capacity_(capacity) {}

    /** @brief Bind only after successful verification of this request's TLS socket. @param request Actual accepted request. @param peer Verified TLS peer. @return Whether the binding was retained. */
    bool bind(const std::shared_ptr<Request> &request, launch_peer peer) {
      std::lock_guard lock {mutex_};
      prune();
      if (!request || peer.certificate.empty() || peer.certificate.size() > 65536 || peer.name.size() > 4096 || bindings_.size() >= capacity_ || bindings_.contains(request.get())) {
        return false;
      }
      bindings_.emplace(request.get(), binding {request, std::move(peer)});
      return true;
    }

    /** @brief Snapshot the peer for this exact request before deferring it. @param request Actual routed request. @return Verified identity, or empty. */
    std::optional<launch_peer> peer(const std::shared_ptr<Request> &request) {
      std::lock_guard lock {mutex_};
      prune();
      if (!request) {
        return {};
      }
      const auto found = bindings_.find(request.get());
      if (found == bindings_.end() || found->second.request.lock() != request) {
        return {};
      }
      return found->second.peer;
    }

  private:
    /** @brief Pair weak lifetime with immutable authenticated identity. */
    struct binding {
      std::weak_ptr<Request> request;  ///< Never prolong a completed HTTP request.
      launch_peer peer;  ///< Actual handshake identity, independent of mutable headers.
    };

    /** @brief Reclaim completed requests while holding the registry mutex. */
    void prune() {
      for (auto it = bindings_.begin(); it != bindings_.end();) {
        if (it->second.request.expired()) {
          it = bindings_.erase(it);
        } else {
          ++it;
        }
      }
    }

    std::mutex mutex_;  ///< Protect binding and lifetime checks.
    std::unordered_map<const Request *, binding> bindings_;  ///< Bounded bindings, containing no private keys.
    std::size_t capacity_;  ///< Live-request limit.
  };

  /** @brief One owned worker keeps slow display preparation off the HTTPS cancellation thread. */
  class launch_worker {
  public:
    /** @brief Start the worker. @param capacity Maximum queued tasks, excluding the one executing. @param failure Notification for unexpected task exceptions. */
    explicit launch_worker(std::size_t capacity = 2, std::function<void()> failure = {}):
        capacity_(capacity),
        failure_(std::move(failure)),
        thread_([this] {
          run();
        }) {}

    /** @brief Drain owned tasks and join before the HTTP server and response deleters are destroyed. */
    ~launch_worker() {
      close();
    }

    /** @brief Submit bounded work without running it on the calling HTTPS thread. @param task Owned task with captured response/request lifetimes. @return Whether this worker accepted it. */
    bool submit(std::function<void()> task) {
      std::lock_guard lock {mutex_};
      if (closed_ || !task || tasks_.size() >= capacity_) {
        return false;
      }
      tasks_.push_back(std::move(task));
      ready_.notify_one();
      return true;
    }

    /** @brief Reject new work, finish queued ownership cleanup, then join. Called only by the owning server thread. */
    void close() {
      {
        std::lock_guard lock {mutex_};
        closed_ = true;
      }
      ready_.notify_one();
      if (thread_.joinable()) {
        thread_.join();
      }
    }

  private:
    /** @brief Run admitted tasks serially, outside the queue mutex. */
    void run() {
      for (;;) {
        std::function<void()> task;
        {
          std::unique_lock lock {mutex_};
          ready_.wait(lock, [this] {
            return closed_ || !tasks_.empty();
          });
          if (tasks_.empty()) {
            return;
          }
          task = std::move(tasks_.front());
          tasks_.pop_front();
        }
        try {
          task();
        } catch (...) {
          if (failure_) {
            try {
              failure_();
            } catch (...) {
              // An error reporter must not terminate the queue or strand owned responses.
            }
          }
        }
      }
    }

    std::mutex mutex_;  ///< Protect admission and queue ownership only.
    std::condition_variable ready_;  ///< Wake for work or shutdown.
    std::deque<std::function<void()>> tasks_;  ///< Strictly bounded queued tasks.
    std::size_t capacity_;  ///< Queue limit.
    std::function<void()> failure_;  ///< Non-sensitive exception notification.
    bool closed_ = false;  ///< Permanent admission closure.
    std::thread thread_;  ///< Last member, started only after all worker state is initialized.
  };
}  // namespace rhremote
