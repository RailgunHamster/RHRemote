/**
 * @file tests/unit/test_nvhttp_client_auth.cpp
 * @brief Test exact paired-client certificate authorization and persistence.
 */

#include "../certificate_test_utils.h"
#include "../tests_common.h"

// standard includes
#include <atomic>
#include <filesystem>
#include <thread>
#include <vector>

// local includes
#include <src/config.h>
#include <src/nvhttp.h>
#include <src/rtsp.h>

namespace fs = std::filesystem;

/**
 * @brief Isolate paired-client authorization tests from the user's Sunshine state.
 */
class ClientAuthorizationTest: public BaseTest {
protected:
  /**
   * @brief Redirect persisted client state to the test build directory.
   */
  void SetUp() override {
    BaseTest::SetUp();
    original_state_file = config::nvhttp.file_state;
    original_fresh_state = config::sunshine.flags[config::flag::FRESH_STATE];
    state_file = fs::path {SUNSHINE_TEST_BIN_DIR} / "client_authorization_state.json";

    config::nvhttp.file_state = state_file.string();
    config::sunshine.flags[config::flag::FRESH_STATE] = false;
    nvhttp::test_support::reset_client_state();

    std::error_code remove_error;
    fs::remove(state_file, remove_error);
  }

  /**
   * @brief Remove test state and restore the caller's configuration.
   */
  void TearDown() override {
    rtsp_stream::launch_session_clear(900001);
    rtsp_stream::launch_session_clear(900002);
    nvhttp::test_support::reset_client_state();
    std::error_code remove_error;
    fs::remove(state_file, remove_error);

    config::nvhttp.file_state = original_state_file;
    config::sunshine.flags[config::flag::FRESH_STATE] = original_fresh_state;
    BaseTest::TearDown();
  }

private:
  fs::path state_file;  ///< Task-specific persisted state fixture.
  std::string original_state_file;  ///< State-file setting restored after each test.
  bool original_fresh_state;  ///< Fresh-state flag restored after each test.
};

TEST_F(ClientAuthorizationTest, CanonicalIdentityFailsClosedAndTracksEnableState) {
  const auto paired_credentials = test_utils::certificates::generate_ca_credentials();
  const auto crlf_certificate = test_utils::certificates::to_crlf_pem(paired_credentials.x509);
  const auto unknown_credentials = crypto::gen_creds("Sunshine Unknown Client", 2048);
  const auto uuid = nvhttp::test_support::add_client("paired", crlf_certificate, true);

  ASSERT_FALSE(uuid.empty());
  EXPECT_EQ(nvhttp::get_cert_by_uuid(uuid), paired_credentials.x509);
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(paired_credentials.x509));
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(crlf_certificate));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(unknown_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate("not a certificate"));

  ASSERT_TRUE(nvhttp::set_client_enabled(uuid, false));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(paired_credentials.x509));
  ASSERT_TRUE(nvhttp::set_client_enabled(uuid, true));
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(paired_credentials.x509));
}

TEST_F(ClientAuthorizationTest, DerivedLeafDoesNotInheritPairedAuthorization) {
  const auto paired_credentials = test_utils::certificates::generate_ca_credentials();
  const auto derived_credentials = test_utils::certificates::generate_derived_leaf(paired_credentials);
  const auto uuid = nvhttp::test_support::add_client("paired issuer", paired_credentials.x509, true);

  ASSERT_FALSE(uuid.empty());
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(paired_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(derived_credentials.x509));

  ASSERT_TRUE(nvhttp::set_client_enabled(uuid, false));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(paired_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(derived_credentials.x509));
}

TEST_F(ClientAuthorizationTest, MultipleClientsPersistAndUnpairIndependently) {
  const auto enabled_credentials = test_utils::certificates::generate_ca_credentials("Sunshine Enabled Client");
  const auto disabled_credentials = crypto::gen_creds("Sunshine Disabled Client", 2048);
  const auto expired_credentials = test_utils::certificates::expire_credentials(
    test_utils::certificates::generate_ca_credentials("Sunshine Expired Client")
  );

  const auto enabled_uuid = nvhttp::test_support::add_client("enabled", enabled_credentials.x509, true);
  const auto disabled_uuid = nvhttp::test_support::add_client("disabled", disabled_credentials.x509, false);
  const auto expired_uuid = nvhttp::test_support::add_client("expired", expired_credentials.x509, true);
  ASSERT_FALSE(enabled_uuid.empty());
  ASSERT_FALSE(disabled_uuid.empty());
  ASSERT_FALSE(expired_uuid.empty());
  EXPECT_EQ(nvhttp::get_all_clients().size(), 3);

  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(enabled_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(disabled_credentials.x509));
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(expired_credentials.x509));

  nvhttp::test_support::reset_client_state();
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(enabled_credentials.x509));
  nvhttp::test_support::reload_client_state();

  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(enabled_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(disabled_credentials.x509));
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(expired_credentials.x509));

  ASSERT_TRUE(nvhttp::set_client_enabled(disabled_uuid, true));
  EXPECT_TRUE(nvhttp::test_support::authorize_client_certificate(disabled_credentials.x509));
  ASSERT_TRUE(nvhttp::unpair_client(enabled_uuid));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(enabled_credentials.x509));

  nvhttp::erase_all_clients();
  nvhttp::test_support::reset_client_state();
  nvhttp::test_support::reload_client_state();
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(disabled_credentials.x509));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(expired_credentials.x509));
}

TEST_F(ClientAuthorizationTest, DuplicateCertificateIdentityFailsClosed) {
  const auto credentials = test_utils::certificates::generate_ca_credentials();
  ASSERT_FALSE(nvhttp::test_support::add_client("first", credentials.x509, true).empty());
  ASSERT_FALSE(nvhttp::test_support::add_client("second", credentials.x509, true).empty());

  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(credentials.x509));
}

TEST_F(ClientAuthorizationTest, ConcurrentStateChangesRemainConsistent) {
  const auto credentials = test_utils::certificates::generate_ca_credentials();
  const auto uuid = nvhttp::test_support::add_client("concurrent", credentials.x509, true);
  ASSERT_FALSE(uuid.empty());

  std::atomic_bool operations_succeeded {true};
  std::vector<std::jthread> workers;
  for (std::size_t worker = 0; worker < 4; ++worker) {
    workers.emplace_back([&operations_succeeded, uuid, certificate = credentials.x509, worker]() {
      for (std::size_t iteration = 0; iteration < 8; ++iteration) {
        if (!nvhttp::set_client_enabled(uuid, (worker + iteration) % 2 == 0)) {
          operations_succeeded = false;
        }
        static_cast<void>(nvhttp::test_support::authorize_client_certificate(certificate));
      }
    });
  }
  workers.clear();

  EXPECT_TRUE(operations_succeeded);
  ASSERT_TRUE(nvhttp::set_client_enabled(uuid, false));
  EXPECT_FALSE(nvhttp::test_support::authorize_client_certificate(credentials.x509));
}

TEST_F(ClientAuthorizationTest, PendingLaunchCancellationIsBoundToActualPeerAndRequest) {
  const auto a = crypto::gen_creds("Pending Client A", 2048);
  const auto b = crypto::gen_creds("Pending Client B", 2048);
  ASSERT_FALSE(nvhttp::test_support::add_client("A", a.x509, true).empty());
  ASSERT_FALSE(nvhttp::test_support::add_client("B", b.x509, true).empty());
  const SimpleWeb::CaseInsensitiveMultimap args {{"rhremoteLaunchToken", crypto::rand_alphabet(64, "0123456789abcdef")}};
  boost::property_tree::ptree error;
  const auto admitted = nvhttp::test_support::admit_launch(args, a.x509, error);
  ASSERT_EQ(admitted.result, rhremote::launch_result::accepted);
  auto session = std::make_shared<rtsp_stream::launch_session_t>();
  session->id = 900001;
  session->client_cert = a.x509;
  session->ownership = admitted.ticket;
  ASSERT_TRUE(rtsp_stream::launch_session_raise(session));
  const auto other = nvhttp::test_support::pending_launch_cancel(args, b.x509);
  EXPECT_EQ(other.get<int>("root.<xmlattr>.status_code"), 403);
  EXPECT_TRUE(rtsp_stream::launch_pending());
  const auto cancelled = nvhttp::test_support::pending_launch_cancel(args, a.x509);
  EXPECT_EQ(cancelled.get<int>("root.<xmlattr>.status_code"), 200);
  EXPECT_EQ(cancelled.get<std::string>("root.RHLaunchState"), "cancelled");
  EXPECT_FALSE(rtsp_stream::launch_pending());
  EXPECT_FALSE(rtsp_stream::launch_session_raise(session));

  const SimpleWeb::CaseInsensitiveMultimap next {{"rhremoteLaunchToken", crypto::rand_alphabet(64, "0123456789abcdef")}};
  const auto replacement = nvhttp::test_support::admit_launch(next, a.x509, error);
  ASSERT_EQ(replacement.result, rhremote::launch_result::accepted);
  session = std::make_shared<rtsp_stream::launch_session_t>();
  session->id = 900002;
  session->client_cert = a.x509;
  session->ownership = replacement.ticket;
  ASSERT_TRUE(rtsp_stream::launch_session_raise(session));
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(args, a.x509).get<int>("root.<xmlattr>.status_code"), 200);
  EXPECT_TRUE(rtsp_stream::launch_pending());
  rtsp_stream::launch_session_clear(session->id);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(next, a.x509).get<std::string>("root.RHLaunchState"), "finished");
}

TEST_F(ClientAuthorizationTest, PendingLaunchRejectsInvalidRevokedAndPreCancelledRequests) {
  const auto a = crypto::gen_creds("Pending Client", 2048);
  const auto uuid = nvhttp::test_support::add_client("A", a.x509, true);
  ASSERT_FALSE(uuid.empty());
  boost::property_tree::ptree error;
  const auto legacy = nvhttp::test_support::admit_launch({}, a.x509, error);
  EXPECT_EQ(legacy.result, rhremote::launch_result::accepted);
  EXPECT_FALSE(legacy.ticket);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel({}, a.x509).get<int>("root.<xmlattr>.status_code"), 400);
  const SimpleWeb::CaseInsensitiveMultimap invalid {{"rhremoteLaunchToken", "invalid"}};
  EXPECT_EQ(nvhttp::test_support::admit_launch(invalid, a.x509, error).result, rhremote::launch_result::invalid);
  EXPECT_EQ(error.get<int>("root.<xmlattr>.status_code"), 400);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(invalid, a.x509).get<int>("root.<xmlattr>.status_code"), 400);
  const SimpleWeb::CaseInsensitiveMultimap args {{"rhremoteLaunchToken", crypto::rand_alphabet(64, "0123456789abcdef")}};
  auto duplicate = args;
  duplicate.emplace("RHREMOTELAUNCHTOKEN", args.begin()->second);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(duplicate, a.x509).get<int>("root.<xmlattr>.status_code"), 400);
  EXPECT_EQ(nvhttp::test_support::admit_launch(duplicate, a.x509, error).result, rhremote::launch_result::invalid);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(args, "untrusted certificate").get<int>("root.<xmlattr>.status_code"), 401);
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(args, a.x509).get<std::string>("root.RHLaunchState"), "cancelled");
  EXPECT_EQ(nvhttp::test_support::admit_launch(args, a.x509, error).result, rhremote::launch_result::cancelled);
  EXPECT_EQ(error.get<int>("root.<xmlattr>.status_code"), 410);

  const SimpleWeb::CaseInsensitiveMultimap next {{"rhremoteLaunchToken", crypto::rand_alphabet(64, "0123456789abcdef")}};
  const auto preparation = nvhttp::test_support::admit_launch(next, a.x509, error);
  ASSERT_EQ(preparation.result, rhremote::launch_result::accepted);
  ASSERT_TRUE(nvhttp::set_client_enabled(uuid, false));
  EXPECT_FALSE(rtsp_stream::launch_request_preparing(preparation.ticket));
  EXPECT_EQ(nvhttp::test_support::pending_launch_cancel(next, a.x509).get<int>("root.<xmlattr>.status_code"), 401);
  EXPECT_EQ(nvhttp::test_support::admit_launch(next, a.x509, error).result, rhremote::launch_result::forbidden);
}
