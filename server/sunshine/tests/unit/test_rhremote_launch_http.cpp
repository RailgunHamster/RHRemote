/**
 * @file tests/unit/test_rhremote_launch_http.cpp
 * @brief Test the production weak TLS binding and bounded deferred launch worker.
 */
#include "src/rhremote_launch_http.h"

#include <atomic>
#include <cstdlib>
#include <future>
#include <iostream>
#include <source_location>
#include <stdexcept>
#include <vector>
#ifndef RHREMOTE_LAUNCH_HTTP_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  /** @brief Preserve assertions in release builds. @param value Expected invariant. @param location Failing caller location. */
  void require(bool value, std::source_location location = std::source_location::current()) {
    if (!value) {
      std::cerr << location.file_name() << ':' << location.line() << '\n';
      std::abort();
    }
  }

  /** @brief Mutable request fields deliberately cannot provide the authenticated TLS identity. */
  struct request_fixture {
    std::string header;
  };  ///< Only fixture data; bindings and worker are production code.

  /** @brief Bind two simultaneous handshakes without mutable-global identity confusion. */
  void bind_exact_request() {
    rhremote::verified_launch_requests<request_fixture> bindings;
    auto a = std::make_shared<request_fixture>();
    auto b = std::make_shared<request_fixture>();
    auto spoof = std::make_shared<request_fixture>();
    require(bindings.bind(a, {"certificate-A", "A"}));
    require(bindings.bind(b, {"certificate-B", "B"}));
    a->header = "certificate-B";
    spoof->header = "certificate-A";
    require(bindings.peer(a)->certificate == "certificate-A");
    require(bindings.peer(b)->certificate == "certificate-B");
    require(!bindings.peer(spoof));
    require(!bindings.peer({}));
    require(!bindings.bind(a, {"certificate-B", "B"}));
    require(bindings.peer(a)->name == "A");
  }

  /** @brief Weak bindings free capacity without retaining requests or admitting invalid peers. */
  void bound_lifetimes() {
    rhremote::verified_launch_requests<request_fixture> bindings(1);
    auto a = std::make_shared<request_fixture>();
    auto b = std::make_shared<request_fixture>();
    require(!bindings.bind({}, {"cert", "name"}));
    require(!bindings.bind(a, {"", "name"}));
    require(!bindings.bind(a, {std::string(65537, 'x'), "name"}));
    require(!bindings.bind(a, {"cert", std::string(4097, 'x')}));
    require(bindings.bind(a, {"certificate-A", "A"}));
    require(!bindings.bind(b, {"certificate-B", "B"}));
    auto old = std::weak_ptr(a);
    a.reset();
    require(old.expired());
    require(bindings.bind(b, {"certificate-B", "B"}));
    require(bindings.peer(b)->certificate == "certificate-B");
    rhremote::verified_launch_requests<request_fixture> disabled(0);
    require(!disabled.bind(b, {"cert", "name"}));
  }

  /** @brief Saturation rejects work promptly while admitted tasks remain ordered and drain at shutdown. */
  void bounded_serial_worker() {
    rhremote::launch_worker worker(2);
    std::promise<void> entered, release;
    auto gate = release.get_future().share();
    std::vector<int> order;
    const auto caller = std::this_thread::get_id();
    require(worker.submit([&] {
      require(std::this_thread::get_id() != caller);
      entered.set_value();
      gate.wait();
      order.push_back(1);
    }));
    entered.get_future().wait();
    require(worker.submit([&] {
      order.push_back(2);
    }));
    require(worker.submit([&] {
      order.push_back(3);
    }));
    require(!worker.submit([&] {
      order.push_back(99);
    }));
    require(!worker.submit({}));
    release.set_value();
    worker.close();
    require(order == std::vector<int>({1, 2, 3}));
    require(!worker.submit([] {
    }));
    worker.close();
  }

  /** @brief An unexpected exception reports failure and still drains following ownership cleanup. */
  void worker_failure_and_cleanup() {
    std::atomic_int failures {0};
    std::atomic_bool cleaned {false};
    {
      rhremote::launch_worker worker(4, [&] {
        ++failures;
        throw std::runtime_error("reporter");
      });
      require(worker.submit([] {
        throw std::runtime_error("fixture");
      }));
      require(worker.submit([&] {
        cleaned = true;
      }));
    }
    require(failures == 1 && cleaned);
    rhremote::launch_worker disabled(0);
    require(!disabled.submit([] {
    }));
  }

  /** @brief Distinguish waiting behind another handler from time spent inside the observed handler. */
  void observed_queue_and_handler() {
    rhremote::launch_worker worker(2);
    std::promise<void> blocked, release_queue, entered, release_handler;
    auto queue_gate = release_queue.get_future().share();
    auto handler_gate = release_handler.get_future().share();
    std::promise<rhremote::launch_worker::work_timing> measured;
    require(worker.submit([&] {
      blocked.set_value();
      queue_gate.wait();
    }));
    blocked.get_future().wait();
    require(worker.submit_observed([&] {
      entered.set_value();
      handler_gate.wait();
    },
                                   [&](auto timing) {
                                     measured.set_value(timing);
                                   }));
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    release_queue.set_value();
    entered.get_future().wait();
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    release_handler.set_value();
    worker.close();
    const auto timing = measured.get_future().get();
    require(timing.started - timing.queued >= std::chrono::milliseconds(4));
    require(timing.finished - timing.started >= std::chrono::milliseconds(4));
  }

  /** @brief Handler failures are reported once; a throwing observer cannot fail a successful handler or stop the queue. */
  void observed_failure_isolated() {
    std::atomic_int failures {0}, reports {0}, following {0};
    rhremote::launch_worker worker(4, [&] {
      ++failures;
    });
    const auto observer = [&](auto timing) {
      require(timing.queued <= timing.started && timing.started <= timing.finished);
      ++reports;
      throw std::runtime_error("observer");
    };
    require(worker.submit_observed([] {
      throw std::runtime_error("handler");
    },
                                   observer));
    require(worker.submit_observed([] {
    },
                                   observer));
    require(worker.submit([&] {
      ++following;
    }));
    worker.close();
    require(failures == 1 && reports == 2 && following == 1);
  }

  /** @brief Rejected or invalid work has no handler or observer side effects. */
  void observed_rejection() {
    rhremote::launch_worker worker(0);
    int effects = 0;
    require(!worker.submit_observed([&] {
      ++effects;
    },
                                    [&](auto) {
                                      ++effects;
                                    }));
    require(!worker.submit_observed({}, [&](auto) {
      ++effects;
    }));
    require(!worker.submit_observed([&] {
      ++effects;
    },
                                    {}));
    worker.close();
    require(!worker.submit_observed([&] {
      ++effects;
    },
                                    [&](auto) {
                                      ++effects;
                                    }));
    require(effects == 0);
  }
}  // namespace

#ifdef RHREMOTE_LAUNCH_HTTP_STANDALONE
int main() {
  bind_exact_request();
  bound_lifetimes();
  bounded_serial_worker();
  worker_failure_and_cleanup();
  observed_queue_and_handler();
  observed_failure_isolated();
  observed_rejection();
}
#else
TEST(RhRemoteLaunchHttp, BindExactRequest) {
  bind_exact_request();
}

TEST(RhRemoteLaunchHttp, BoundLifetimes) {
  bound_lifetimes();
}

TEST(RhRemoteLaunchHttp, BoundedSerialWorker) {
  bounded_serial_worker();
}

TEST(RhRemoteLaunchHttp, WorkerFailureAndCleanup) {
  worker_failure_and_cleanup();
}

TEST(RhRemoteLaunchHttp, ObservedQueueAndHandler) {
  observed_queue_and_handler();
}

TEST(RhRemoteLaunchHttp, ObservedFailureIsolated) {
  observed_failure_isolated();
}

TEST(RhRemoteLaunchHttp, ObservedRejection) {
  observed_rejection();
}
#endif
