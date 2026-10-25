/**
 * @file src/nvhttp.cpp
 * @brief Definitions for the nvhttp (GameStream) server.
 */
// macros
#define BOOST_BIND_GLOBAL_PLACEHOLDERS

// standard includes
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <format>
#include <mutex>
#include <string>
#include <utility>

// lib includes
#include <boost/algorithm/string/predicate.hpp>
#include <boost/asio/ssl/context.hpp>
#include <boost/asio/ssl/context_base.hpp>
#include <boost/property_tree/json_parser.hpp>
#include <boost/property_tree/ptree.hpp>
#include <boost/property_tree/xml_parser.hpp>
#include <Simple-Web-Server/server_http.hpp>

// local includes
#include "config.h"
#include "display_device.h"
#include "file_handler.h"
#include "globals.h"
#include "httpcommon.h"
#include "logging.h"
#include "network.h"
#include "nvhttp.h"
#include "platform/common.h"
#include "process.h"
#include "rhremote_frame_trace.h"
#include "rhremote_initial_display.h"
#include "rhremote_launch_http.h"
#include "rtsp.h"
#include "system_tray.h"
#include "utility.h"
#include "uuid.h"
#include "video.h"

using namespace std::literals;

namespace nvhttp {

  static constexpr std::string_view EMPTY_PROPERTY_TREE_ERROR_MSG = "Property tree is empty. Probably, control flow got interrupted by an unexpected C++ exception. This is a bug in Sunshine. Moonlight-qt will report Malformed XML (missing root element)."sv;

  namespace fs = std::filesystem;
  namespace pt = boost::property_tree;

  crypto::cert_chain_t cert_chain;  ///< Enabled paired-client certificates accepted by Sunshine's GameStream HTTPS server.

  /** @brief Serialize HTTP launch preparation and cancellation. @return Mutex protecting admission through RTSP publication. */
  std::mutex &launch_mutex() {
    static std::mutex mutex;
    return mutex;
  }

  /**
   * @brief Get the mutex protecting paired-client authorization state.
   *
   * @return Mutex serializing paired-client state and certificate authorization changes.
   */
  std::mutex &client_auth_mutex() {
    static std::mutex mutex;
    return mutex;
  }

  /**
   * @brief HTTPS server backend that adds Sunshine's client-certificate verification.
   */
  class SunshineHTTPSServer: public SimpleWeb::ServerBase<SunshineHTTPS> {
  public:
    /**
     * @brief Initialize the HTTPS server with Sunshine's certificate and key files.
     *
     * @param certification_file Path to the server certificate file.
     * @param private_key_file Path to the matching private key file.
     */
    SunshineHTTPSServer(const std::string &certification_file, const std::string &private_key_file):
        ServerBase<SunshineHTTPS>::ServerBase(443),
        context(boost::asio::ssl::context::tls_server) {
      // Disabling TLS 1.0 and 1.1 (see RFC 8996)
      context.set_options(boost::asio::ssl::context::no_tlsv1);
      context.set_options(boost::asio::ssl::context::no_tlsv1_1);
      context.use_certificate_chain_file(certification_file);
      context.use_private_key_file(private_key_file, boost::asio::ssl::context::pem);
    }

    std::function<std::optional<rhremote::launch_peer>(SSL *)> verify;  ///< Actual verified identity from this TLS socket.
    std::function<void(std::shared_ptr<Response>, std::shared_ptr<Request>)> on_verify_failed;  ///< Handler used to return the pairing challenge when client verification fails.
    rhremote::verified_launch_requests<Request> verified_requests;  ///< Bounded weak identity bindings for actual accepted requests.

  protected:
    boost::asio::ssl::context context;  ///< TLS server context configured with Sunshine's certificate and protocol policy.

    /**
     * @brief Enable client-certificate verification after the listening socket is bound.
     */
    void after_bind() override {
      if (verify) {
        context.set_verify_mode(boost::asio::ssl::verify_peer | boost::asio::ssl::verify_fail_if_no_peer_cert | boost::asio::ssl::verify_client_once);
        context.set_verify_callback([](int verified, boost::asio::ssl::verify_context &ctx) {
          // To respond with an error message, a connection must be established
          return 1;
        });
      }
    }

    // This is Server<HTTPS>::accept() with SSL validation support added
    /**
     * @brief Accept a pending connection and arm the server for the next client.
     */
    void accept() override {
      auto connection = create_connection(*io_service, context);

      acceptor->async_accept(connection->socket->lowest_layer(), [this, connection](const SimpleWeb::error_code &ec) {
        auto lock = connection->handler_runner->continue_lock();
        if (!lock) {
          return;
        }

        if (ec != SimpleWeb::error::operation_aborted) {
          this->accept();
        }

        auto session = std::make_shared<Session>(config.max_request_streambuf_size, connection);

        if (!ec) {
          boost::asio::ip::tcp::no_delay option(true);
          SimpleWeb::error_code ec;
          session->connection->socket->lowest_layer().set_option(option, ec);

          session->connection->set_timeout(config.timeout_request);
          session->connection->socket->async_handshake(boost::asio::ssl::stream_base::server, [this, session](const SimpleWeb::error_code &ec) {
            session->connection->cancel_timeout();
            auto lock = session->connection->handler_runner->continue_lock();
            if (!lock) {
              return;
            }
            if (!ec) {
              auto peer = verify ? verify(session->connection->socket->native_handle()) : std::optional<rhremote::launch_peer> {};
              if (verify && !peer) {
                this->write(session, on_verify_failed);
              } else if (peer && !verified_requests.bind(session->request, std::move(*peer))) {
                std::function<void(std::shared_ptr<Response>, std::shared_ptr<Request>)> unavailable = [](auto response, auto) {
                  response->write("<root status_code=\"503\" status_message=\"Too many authenticated requests\"/>");
                  response->close_connection_after_response = true;
                };
                this->write(session, unavailable);
              } else {
                this->read(session);
              }
            } else if (this->on_error) {
              this->on_error(session->request, ec);
            }
          });
        } else if (this->on_error) {
          this->on_error(session->request, ec);
        }
      });
    }
  };

  /**
   * @brief HTTPS server type used for GameStream endpoints requiring TLS.
   */
  using https_server_t = SunshineHTTPSServer;
  /**
   * @brief Plain HTTP server type used for GameStream endpoints without TLS.
   */
  using http_server_t = SimpleWeb::Server<SimpleWeb::HTTP>;

  /**
   * @brief Internal HTTPS credential paths for the configuration server.
   */
  struct conf_intern_t {
    std::string servercert;  ///< Server certificate PEM string.
    std::string pkey;  ///< Private key PEM string or path.
  } conf_intern;  ///< TLS credential paths loaded from Sunshine's runtime configuration.

  /**
   * @brief Certificate entry associated with a client name and UUID.
   */
  struct named_cert_t {
    std::string name;  ///< Human-readable name for this item.
    std::string uuid;  ///< Persistent Moonlight client UUID associated with the certificate.
    std::string cert;  ///< Certificate PEM string or path.
    bool enabled = true;  ///< Whether this persisted client entry may connect.
  };

  /**
   * @brief Persisted pairing data for one Moonlight client.
   */
  struct client_t {
    std::vector<named_cert_t> named_devices;  ///< Persisted Moonlight clients allowed to pair or reconnect.
  };

  // uniqueID, session
  std::unordered_map<std::string, pair_session_t> map_id_sess;  ///< Pairing sessions keyed by temporary unique ID.

  /**
   * @brief Get the mutex protecting pairing-session state.
   *
   * @return Mutex serializing pairing-session storage and lifecycle transitions.
   */
  std::mutex &map_id_sess_mutex() {
    static std::mutex mutex;
    return mutex;
  }

  client_t client_root;  ///< In-memory representation of the paired-client database.
  std::atomic<uint32_t> session_id_counter;  ///< Monotonic counter used to allocate GameStream session IDs.

  /**
   * @brief Case-insensitive map used for HTTP headers and query parameters.
   */
  using args_t = SimpleWeb::CaseInsensitiveMultimap;
  /**
   * @brief Shared HTTPS response object passed to GameStream handlers.
   */
  using resp_https_t = std::shared_ptr<typename SimpleWeb::ServerBase<SunshineHTTPS>::Response>;
  /**
   * @brief Shared HTTPS request object received by GameStream handlers.
   */
  using req_https_t = std::shared_ptr<typename SimpleWeb::ServerBase<SunshineHTTPS>::Request>;
  /**
   * @brief Shared HTTP response object passed to redirect and discovery handlers.
   */
  using resp_http_t = std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Response>;
  /**
   * @brief Shared HTTP request object received by redirect and discovery handlers.
   */
  using req_http_t = std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Request>;

  /**
   * @brief Certificate operations supported by the pairing API.
   */
  enum class op_e {
    ADD,  ///< Add certificate
    REMOVE  ///< Remove certificate
  };

  /**
   * @brief Read a named query argument from the HTTP request map.
   *
   * @param args Parsed query-string argument map.
   * @param name Query parameter name to read.
   * @param default_value Value returned when the parameter is absent.
   * @return Query parameter value, default value, or an empty string.
   */
  std::string get_arg(const args_t &args, const char *name, const char *default_value = nullptr) {
    auto it = args.find(name);
    if (it == std::end(args)) {
      if (default_value != nullptr) {
        return std::string(default_value);
      }

      throw std::out_of_range(name);
    }
    return it->second;
  }

  /**
   * @brief Persist the current state to its backing store.
   */
  void save_state() {
    pt::ptree root;

    if (fs::exists(config::nvhttp.file_state)) {
      try {
        pt::read_json(config::nvhttp.file_state, root);
      } catch (std::exception &e) {
        BOOST_LOG(error) << "Couldn't read "sv << config::nvhttp.file_state << ": "sv << e.what();
        return;
      }
    }

    root.erase("root"s);

    root.put("root.uniqueid", http::unique_id);
    client_t &client = client_root;
    pt::ptree node;

    pt::ptree named_cert_nodes;
    for (auto &named_cert : client.named_devices) {
      pt::ptree named_cert_node;
      named_cert_node.put("name"s, named_cert.name);
      named_cert_node.put("cert"s, named_cert.cert);
      named_cert_node.put("uuid"s, named_cert.uuid);
      named_cert_node.put("enabled"s, named_cert.enabled);
      named_cert_nodes.push_back(std::make_pair(""s, named_cert_node));
    }
    root.add_child("root.named_devices"s, named_cert_nodes);

    try {
      pt::write_json(config::nvhttp.file_state, root);
    } catch (std::exception &e) {
      BOOST_LOG(error) << "Couldn't write "sv << config::nvhttp.file_state << ": "sv << e.what();
      return;
    }
  }

  /**
   * @brief Convert a PEM certificate to Sunshine's canonical OpenSSL serialization.
   *
   * @param cert_pem PEM-encoded certificate to canonicalize.
   * @return Canonical PEM text, or an empty string when the certificate is invalid.
   */
  std::string canonical_certificate_pem(const std::string_view cert_pem) {
    auto certificate = crypto::x509(cert_pem);
    return certificate ? crypto::pem(certificate) : std::string {};
  }

  /**
   * @brief Rebuild the GameStream trust stores from enabled paired-client records.
   *
   * @note The caller must hold `client_auth_mutex()`.
   */
  void rebuild_client_cert_chain() {
    cert_chain.clear();
    for (const auto &named_cert : client_root.named_devices) {
      if (!named_cert.enabled) {
        continue;
      }

      auto certificate = crypto::x509(named_cert.cert);
      if (!certificate) {
        BOOST_LOG(warning) << "Ignoring invalid paired-client certificate"sv;
        continue;
      }

      cert_chain.add(std::move(certificate));
    }
  }

  /**
   * @brief Check whether a certificate exactly matches an enabled paired-client record.
   *
   * @param certificate Parsed client certificate to compare by canonical X.509 identity.
   * @return `true` only when exactly one matching paired-client record is enabled.
   * @note The caller must hold `client_auth_mutex()`.
   */
  bool is_client_enabled(const X509 *certificate) {
    bool matched = false;
    for (const auto &named_cert : client_root.named_devices) {
      if (auto stored_certificate = crypto::x509(named_cert.cert); !stored_certificate || X509_cmp(stored_certificate.get(), certificate) != 0) {
        continue;
      }

      if (matched || !named_cert.enabled) {
        return false;
      }
      matched = true;
    }
    return matched;
  }

  /**
   * @brief Verify a client certificate against the exact enabled paired identity.
   *
   * @param certificate Parsed client certificate presented during the TLS handshake.
   * @return `nullptr` when authorized, otherwise a non-sensitive error string.
   * @note The caller must hold `client_auth_mutex()`.
   */
  const char *verify_client_certificate(X509 *certificate) {
    if (const auto verification_error = cert_chain.verify(certificate); verification_error) {
      return verification_error;
    }
    return is_client_enabled(certificate) ? nullptr : "Client certificate identity is not enabled";
  }

  /**
   * @brief Load state from its backing store.
   */
  void load_state() {
    if (!fs::exists(config::nvhttp.file_state)) {
      BOOST_LOG(info) << "File "sv << config::nvhttp.file_state << " doesn't exist"sv;
      http::unique_id = uuid_util::uuid_t::generate().string();
      return;
    }

    pt::ptree tree;
    try {
      pt::read_json(config::nvhttp.file_state, tree);
    } catch (std::exception &e) {
      BOOST_LOG(error) << "Couldn't read "sv << config::nvhttp.file_state << ": "sv << e.what();

      return;
    }

    auto unique_id_p = tree.get_optional<std::string>("root.uniqueid");
    if (!unique_id_p) {
      // This file doesn't contain moonlight credentials
      http::unique_id = uuid_util::uuid_t::generate().string();
      return;
    }
    http::unique_id = std::move(*unique_id_p);

    auto root = tree.get_child("root");
    client_t client;

    // Import from old format
    if (root.get_child_optional("devices")) {
      auto device_nodes = root.get_child("devices");
      for (auto &[_, device_node] : device_nodes) {
        auto uniqID = device_node.get<std::string>("uniqueid");

        if (device_node.count("certs")) {
          for (auto &[_, el] : device_node.get_child("certs")) {
            named_cert_t named_cert;
            named_cert.name = ""s;
            named_cert.cert = el.get_value<std::string>();
            named_cert.uuid = uuid_util::uuid_t::generate().string();
            client.named_devices.emplace_back(named_cert);
          }
        }
      }
    }

    if (root.count("named_devices")) {
      for (auto &[_, el] : root.get_child("named_devices")) {
        named_cert_t named_cert;
        named_cert.name = el.get_child("name").get_value<std::string>();
        named_cert.cert = el.get_child("cert").get_value<std::string>();
        named_cert.uuid = el.get_child("uuid").get_value<std::string>();
        named_cert.enabled = el.get<bool>("enabled", true);
        client.named_devices.emplace_back(named_cert);
      }
    }

    for (auto &named_cert : client.named_devices) {
      auto canonical_certificate = canonical_certificate_pem(named_cert.cert);
      if (!canonical_certificate.empty()) {
        named_cert.cert = std::move(canonical_certificate);
      }
    }

    std::lock_guard lock {client_auth_mutex()};
    client_root = std::move(client);
    rebuild_client_cert_chain();
  }

  /**
   * @brief Add authorized client data.
   *
   * @param name Human-readable name to assign.
   * @param cert Certificate data or object used by the operation.
   * @return Persistent UUID for the added client, or an empty string when the certificate is invalid.
   */
  std::string add_authorized_client(const std::string &name, std::string &&cert) {
    auto canonical_certificate = canonical_certificate_pem(cert);
    if (canonical_certificate.empty()) {
      return {};
    }

    named_cert_t named_cert;
    named_cert.name = name;
    named_cert.cert = std::move(canonical_certificate);
    named_cert.uuid = uuid_util::uuid_t::generate().string();

    std::lock_guard lock {client_auth_mutex()};
    client_root.named_devices.emplace_back(std::move(named_cert));
    rebuild_client_cert_chain();

    if (!config::sunshine.flags[config::flag::FRESH_STATE]) {
      save_state();
    }
    return client_root.named_devices.back().uuid;
  }

  /**
   * @brief Create launch session.
   *
   * @param host_audio Host audio.
   * @param args Arguments forwarded to the callable or parser.
   * @param peer Actual paired TLS identity captured for this request.
   * @return Constructed launch session object.
   */
  std::shared_ptr<rtsp_stream::launch_session_t> make_launch_session(bool host_audio, const args_t &args, const rhremote::launch_peer &peer) {
    auto launch_session = std::make_shared<rtsp_stream::launch_session_t>();

    launch_session->id = ++session_id_counter;
    launch_session->frame_trace_client = get_arg(args, "rhremoteTraceClient", "");
    launch_session->frame_trace_requested = rhremote::frame_trace_requested(get_arg(args, "rhremoteFrameTrace", "0"), launch_session->frame_trace_client);

    auto rikey = util::from_hex_vec(get_arg(args, "rikey"), true);
    std::copy(rikey.cbegin(), rikey.cend(), std::back_inserter(launch_session->gcm_key));

    launch_session->host_audio = host_audio;
    std::stringstream mode = std::stringstream(get_arg(args, "mode", "0x0x0"));
    // Split mode by the char "x", to populate width/height/fps
    int x = 0;
    std::string segment;
    while (std::getline(mode, segment, 'x')) {
      if (x == 0) {
        launch_session->width = atoi(segment.c_str());
      }
      if (x == 1) {
        launch_session->height = atoi(segment.c_str());
      }
      if (x == 2) {
        launch_session->fps = atoi(segment.c_str());
      }
      x++;
    }
    launch_session->unique_id = (get_arg(args, "uniqueid", "unknown"));
    launch_session->appid = (int) util::from_view(get_arg(args, "appid", "unknown"));
    launch_session->enable_sops = util::from_view(get_arg(args, "sops", "0"));
    launch_session->surround_info = (int) util::from_view(get_arg(args, "surroundAudioInfo", "196610"));
    launch_session->surround_params = (get_arg(args, "surroundParams", ""));
    launch_session->continuous_audio = util::from_view(get_arg(args, "continuousAudio", "0"));
    launch_session->gcmap = (int) util::from_view(get_arg(args, "gcmap", "0"));
    launch_session->enable_hdr = util::from_view(get_arg(args, "hdrMode", "0"));

    // Encrypted RTSP is enabled with client reported corever >= 1
    auto corever = util::from_view(get_arg(args, "corever", "0"));
    if (corever >= 1) {
      launch_session->rtsp_cipher = crypto::cipher::gcm_t {
        launch_session->gcm_key,
        false
      };
      launch_session->rtsp_iv_counter = 0;
    }
    launch_session->rtsp_url_scheme = launch_session->rtsp_cipher ? "rtspenc://"s : "rtsp://"s;
    launch_session->client_cert = peer.certificate;
    launch_session->client_name = peer.name;

    // Generate the unique identifiers for this connection that we will send later during RTSP handshake
    unsigned char raw_payload[8];
    RAND_bytes(raw_payload, sizeof(raw_payload));
    launch_session->av_ping_payload = util::hex_vec(raw_payload);
    RAND_bytes((unsigned char *) &launch_session->control_connect_data, sizeof(launch_session->control_connect_data));

    launch_session->iv.resize(16);
    uint32_t prepend_iv = util::endian::big<uint32_t>((int) util::from_view(get_arg(args, "rikeyid")));
    auto prepend_iv_p = (uint8_t *) &prepend_iv;
    std::copy(prepend_iv_p, prepend_iv_p + sizeof(prepend_iv), std::begin(launch_session->iv));
    return launch_session;
  }

  /** @brief Validate an optional initial source before global display or application changes. @param args Paired HTTPS query. @param session Launch receiving the target. @param tree Error response on rejection. @return Whether launch preparation may continue. */
  bool prepare_initial_display(const args_t &args, rtsp_stream::launch_session_t &session, pt::ptree &tree) {
    const auto name = get_arg(args, "rhrdisplay", "");
#ifdef _WIN32
    constexpr bool supported = true;
#else
    constexpr bool supported = false;
#endif
    // ANNOUNCE inserts an active session before PLAY releases the pending slot.
    // Read pending first so that transition cannot appear idle between two reads.
    const bool pending = rtsp_stream::launch_pending();
    const auto admission = rhremote::admit_initial_display(name, rtsp_stream::session_count(), pending, supported);
    if (admission == rhremote::initial_display_admission::busy || admission == rhremote::initial_display_admission::invalid) {
      tree.put("root.<xmlattr>.status_code", admission == rhremote::initial_display_admission::busy ? 409 : 400);
      tree.put("root.<xmlattr>.status_message", admission == rhremote::initial_display_admission::busy ? "Another launch or stream is using this host; disconnect it before choosing an initial display" : "Invalid or unsupported initial display source");
      tree.put("root.resume", 0);
      tree.put("root.gamesession", 0);
      return false;
    }
    session.initial_display = name;
    return true;
  }

  /**
   * @brief Write a completed response to the client waiting for PIN approval.
   *
   * @param sess Pairing session that owns the waiting response.
   * @param tree XML response body to write.
   * @return `true` when a live response was available.
   */
  bool write_pairing_response(pair_session_t &sess, const pt::ptree &tree) {
    std::ostringstream data;
    pt::write_xml(data, tree);

    auto &response = sess.async_insert_pin.response;
    if (response.has_left() && response.left()) {
      response.left()->close_connection_after_response = true;
      response.left()->write(data.str());
    } else if (response.has_right() && response.right()) {
      response.right()->close_connection_after_response = true;
      response.right()->write(data.str());
    } else {
      return false;
    }

    response = std::monostate {};
    return true;
  }

  /**
   * @brief Expire stale pairing sessions while the session mutex is held.
   *
   * @param now Monotonic time used to evaluate session deadlines.
   */
  void expire_pair_sessions_unlocked(const std::chrono::steady_clock::time_point now) {
    for (auto it = map_id_sess.begin(); it != map_id_sess.end();) {
      if (it->second.async_insert_pin.expires_at > now) {
        ++it;
        continue;
      }

      pt::ptree tree;
      tree.put("root.paired", 0);
      tree.put("root.<xmlattr>.status_code", 408);
      tree.put("root.<xmlattr>.status_message", "Pairing session expired");
      write_pairing_response(it->second, tree);
      it = map_id_sess.erase(it);
    }
  }

  pair_session_insert_e insert_pair_session(pair_session_t sess, std::string &pairing_id) {
    std::scoped_lock lock {map_id_sess_mutex()};
    const auto now = std::chrono::steady_clock::now();
    expire_pair_sessions_unlocked(now);
    pairing_id.clear();

    if (map_id_sess.contains(sess.client.uniqueID)) {
      return pair_session_insert_e::ALREADY_EXISTS;
    }
    if (map_id_sess.size() >= MAX_PENDING_PAIRING_SESSIONS) {
      return pair_session_insert_e::FULL;
    }

    do {
      pairing_id = crypto::rand_alphabet(PAIRING_ID_SIZE, "0123456789abcdef"sv);
    } while (std::ranges::any_of(map_id_sess, [&](const auto &entry) {
      return entry.second.async_insert_pin.id == pairing_id;
    }));

    sess.async_insert_pin.id = pairing_id;
    sess.async_insert_pin.expires_at = now + PAIRING_SESSION_TIMEOUT;
    map_id_sess.emplace(sess.client.uniqueID, std::move(sess));
    return pair_session_insert_e::ADDED;
  }

  bool is_valid_pairing_id(const std::string_view pairing_id) {
    return pairing_id.size() == PAIRING_ID_SIZE && std::ranges::all_of(pairing_id, [](const char character) {
             return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f') || (character >= 'A' && character <= 'F');
           });
  }

  bool is_valid_pairing_pin(const std::string_view pin) {
    return pin.size() == 4 && std::ranges::all_of(pin, [](const char character) {
             return character >= '0' && character <= '9';
           });
  }

  bool is_valid_pairing_name(const std::string_view name) {
    return !name.empty() && name.size() <= MAX_PAIRING_CLIENT_NAME_SIZE;
  }

  void expire_pair_sessions(const std::chrono::steady_clock::time_point now) {
    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(now);
  }

  std::vector<pending_pairing_t> get_pending_pairings() {
    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());

    std::vector<const pair_session_t *> pending_sessions;
    pending_sessions.reserve(map_id_sess.size());
    for (const auto &[_, sess] : map_id_sess) {
      if (sess.last_phase == PAIR_PHASE::NONE) {
        pending_sessions.push_back(&sess);
      }
    }
    std::ranges::sort(pending_sessions, {}, [](const pair_session_t *sess) {
      return sess->async_insert_pin.expires_at;
    });

    std::vector<pending_pairing_t> result;
    result.reserve(pending_sessions.size());
    for (const auto *sess : pending_sessions) {
      result.push_back({
        .id = sess->async_insert_pin.id,
        .name = sess->async_insert_pin.device_name,
        .address = sess->async_insert_pin.address,
      });
    }
    return result;
  }

  bool cancel_pairing(const std::string_view pairing_id) {
    if (!is_valid_pairing_id(pairing_id)) {
      return false;
    }

    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());

    const auto sess_it = std::ranges::find_if(map_id_sess, [&](const auto &entry) {
      return entry.second.last_phase == PAIR_PHASE::NONE && entry.second.async_insert_pin.id == pairing_id;
    });
    if (sess_it == map_id_sess.end()) {
      return false;
    }

    pt::ptree tree;
    tree.put("root.paired", 0);
    tree.put("root.<xmlattr>.status_code", 400);
    tree.put("root.<xmlattr>.status_message", "Pairing request cancelled by operator");
    write_pairing_response(sess_it->second, tree);
    map_id_sess.erase(sess_it);
    return true;
  }

  bool cancel_pairing_client(const std::string_view unique_id, const std::string_view salt, const std::string_view signature) {
    const auto hex_text = [](const std::string_view value) {
      return std::ranges::all_of(value, [](const char character) {
        return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f') || (character >= 'A' && character <= 'F');
      });
    };
    if (unique_id.empty() || unique_id.size() > 128 || !hex_text(unique_id) || salt.size() != 32 || !hex_text(salt) || signature.size() < 128 || signature.size() > 4096 || signature.size() % 2 != 0 || !hex_text(signature)) {
      return false;
    }

    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());
    const auto sess_it = map_id_sess.find(std::string {unique_id});
    if (sess_it == map_id_sess.end() || sess_it->second.async_insert_pin.salt != salt) {
      return false;
    }
    const auto cert = crypto::x509(sess_it->second.client.cert);
    const auto message = "RHREMOTE-PAIR-CANCEL-1\n"s + std::string {unique_id} + "\n" + std::string {salt};
    const auto proof = util::from_hex_vec(std::string {signature}, true);
    if (!cert || !crypto::verify256(cert, message, proof)) {
      return false;
    }

    pt::ptree tree;
    tree.put("root.paired", 0);
    tree.put("root.<xmlattr>.status_code", 400);
    tree.put("root.<xmlattr>.status_message", "Pairing request cancelled by client");
    write_pairing_response(sess_it->second, tree);
    map_id_sess.erase(sess_it);
    return true;
  }

  void remove_session(const pair_session_t &sess) {
    std::scoped_lock lock {map_id_sess_mutex()};
    map_id_sess.erase(sess.client.uniqueID);
  }

  /**
   * @brief Return the GameStream pairing failure response.
   *
   * @param sess Pairing session that owns the request state.
   * @param tree XML property tree used for the response body.
   * @param status_msg Status msg.
   */
  void fail_pair(pair_session_t &sess, pt::ptree &tree, const std::string status_msg) {
    tree.put("root.paired", 0);
    tree.put("root.<xmlattr>.status_code", 400);
    tree.put("root.<xmlattr>.status_message", status_msg);
    sess.failed = true;
  }

  /**
   * @brief Return the server certificate text for pairing responses.
   *
   * @param sess Pairing session that owns the request state.
   * @param tree XML property tree used for the response body.
   * @param pin PIN supplied by the client during pairing.
   */
  void getservercert(pair_session_t &sess, pt::ptree &tree, const std::string &pin) {
    if (sess.last_phase != PAIR_PHASE::NONE) {
      fail_pair(sess, tree, "Out of order call to getservercert");
      return;
    }
    sess.last_phase = PAIR_PHASE::GETSERVERCERT;

    if (sess.async_insert_pin.salt.size() < 32) {
      fail_pair(sess, tree, "Salt too short");
      return;
    }

    std::string_view salt_view {sess.async_insert_pin.salt.data(), 32};

    auto salt = util::from_hex<std::array<uint8_t, 16>>(salt_view, true);

    auto key = crypto::gen_aes_key(salt, pin);
    sess.cipher_key = std::make_unique<crypto::aes_t>(key);

    tree.put("root.paired", 1);
    tree.put("root.plaincert", util::hex_vec(conf_intern.servercert, true));
    tree.put("root.<xmlattr>.status_code", 200);
  }

  /**
   * @brief Handle the client-challenge phase of GameStream pairing.
   *
   * @param sess Pairing session that owns the request state.
   * @param tree XML property tree used for the response body.
   * @param challenge Client challenge bytes from the pairing request.
   */
  void clientchallenge(pair_session_t &sess, pt::ptree &tree, const std::string &challenge) {
    if (sess.last_phase != PAIR_PHASE::GETSERVERCERT) {
      fail_pair(sess, tree, "Out of order call to clientchallenge");
      return;
    }
    sess.last_phase = PAIR_PHASE::CLIENTCHALLENGE;

    if (!sess.cipher_key) {
      fail_pair(sess, tree, "Cipher key not set");
      return;
    }
    crypto::cipher::ecb_t cipher(*sess.cipher_key, false);

    std::vector<uint8_t> decrypted;
    cipher.decrypt(challenge, decrypted);

    auto x509 = crypto::x509(conf_intern.servercert);
    auto sign = crypto::signature(x509);
    auto serversecret = crypto::rand(16);

    decrypted.insert(std::end(decrypted), std::begin(sign), std::end(sign));
    decrypted.insert(std::end(decrypted), std::begin(serversecret), std::end(serversecret));

    auto hash = crypto::hash({(char *) decrypted.data(), decrypted.size()});
    auto serverchallenge = crypto::rand(16);

    std::string plaintext;
    plaintext.reserve(hash.size() + serverchallenge.size());

    plaintext.insert(std::end(plaintext), std::begin(hash), std::end(hash));
    plaintext.insert(std::end(plaintext), std::begin(serverchallenge), std::end(serverchallenge));

    std::vector<uint8_t> encrypted;
    cipher.encrypt(plaintext, encrypted);

    sess.serversecret = std::move(serversecret);
    sess.serverchallenge = std::move(serverchallenge);

    tree.put("root.paired", 1);
    tree.put("root.challengeresponse", util::hex_vec(encrypted, true));
    tree.put("root.<xmlattr>.status_code", 200);
  }

  /**
   * @brief Handle the server-challenge response phase of GameStream pairing.
   *
   * @param sess Pairing session that owns the request state.
   * @param tree XML property tree used for the response body.
   * @param encrypted_response Encrypted response.
   */
  void serverchallengeresp(pair_session_t &sess, pt::ptree &tree, const std::string &encrypted_response) {
    if (sess.last_phase != PAIR_PHASE::CLIENTCHALLENGE) {
      fail_pair(sess, tree, "Out of order call to serverchallengeresp");
      return;
    }
    sess.last_phase = PAIR_PHASE::SERVERCHALLENGERESP;

    if (!sess.cipher_key || sess.serversecret.empty()) {
      fail_pair(sess, tree, "Cipher key or serversecret not set");
      return;
    }

    std::vector<uint8_t> decrypted;
    crypto::cipher::ecb_t cipher(*sess.cipher_key, false);

    cipher.decrypt(encrypted_response, decrypted);

    sess.clienthash = std::move(decrypted);

    auto serversecret = sess.serversecret;
    auto sign = crypto::sign256(crypto::pkey(conf_intern.pkey), serversecret);

    serversecret.insert(std::end(serversecret), std::begin(sign), std::end(sign));

    tree.put("root.pairingsecret", util::hex_vec(serversecret, true));
    tree.put("root.paired", 1);
    tree.put("root.<xmlattr>.status_code", 200);
  }

  /**
   * @brief Handle the client pairing-secret phase of GameStream pairing.
   *
   * @param sess Pairing session that owns the request state.
   * @param tree XML property tree used for the response body.
   * @param client_pairing_secret Client pairing secret.
   */
  void clientpairingsecret(pair_session_t &sess, pt::ptree &tree, const std::string &client_pairing_secret) {
    if (sess.last_phase != PAIR_PHASE::SERVERCHALLENGERESP) {
      fail_pair(sess, tree, "Out of order call to clientpairingsecret");
      return;
    }
    sess.last_phase = PAIR_PHASE::CLIENTPAIRINGSECRET;

    auto &client = sess.client;

    if (client_pairing_secret.size() <= 16) {
      fail_pair(sess, tree, "Client pairing secret too short");
      return;
    }

    std::string_view secret {client_pairing_secret.data(), 16};
    std::string_view sign {client_pairing_secret.data() + secret.size(), client_pairing_secret.size() - secret.size()};

    auto x509 = crypto::x509(client.cert);
    if (!x509) {
      fail_pair(sess, tree, "Invalid client certificate");
      return;
    }
    auto x509_sign = crypto::signature(x509);

    std::string data;
    data.reserve(sess.serverchallenge.size() + x509_sign.size() + secret.size());

    data.insert(std::end(data), std::begin(sess.serverchallenge), std::end(sess.serverchallenge));
    data.insert(std::end(data), std::begin(x509_sign), std::end(x509_sign));
    data.insert(std::end(data), std::begin(secret), std::end(secret));

    auto hash = crypto::hash(data);

    // if hash not correct, probably MITM
    bool same_hash = hash.size() == sess.clienthash.size() && std::equal(hash.begin(), hash.end(), sess.clienthash.begin());
    auto verify = crypto::verify256(crypto::x509(client.cert), secret, sign);
    if (same_hash && verify) {
      // The client is now successfully paired and will be authorized to connect
      tree.put("root.paired", add_authorized_client(client.name, std::move(client.cert)).empty() ? 0 : 1);
    } else {
      tree.put("root.paired", 0);
    }

    tree.put("root.<xmlattr>.status_code", 200);
  }

  template<class T>
  struct tunnel;

  /**
   * @brief HTTPS tunnel session used for encrypted client requests.
   */
  template<>
  struct tunnel<SunshineHTTPS> {
    static auto constexpr to_string = "HTTPS"sv;  ///< To string.
  };

  /**
   * @brief Plain HTTP server wrapper used for non-TLS endpoints.
   */
  template<>
  struct tunnel<SimpleWeb::HTTP> {
    static auto constexpr to_string = "NONE"sv;  ///< To string.
  };

  /**
   * @brief Write req details to the log.
   *
   * @param request HTTP request data from the client.
   */
  template<class T>
  void print_req(std::shared_ptr<typename SimpleWeb::ServerBase<T>::Request> request) {
    BOOST_LOG(debug) << "TUNNEL :: "sv << tunnel<T>::to_string;

    BOOST_LOG(debug) << "METHOD :: "sv << request->method;
    BOOST_LOG(debug) << "DESTINATION :: "sv << request->path;

    for (auto &[name, val] : request->header) {
      BOOST_LOG(debug) << name << " -- " << val;
    }

    BOOST_LOG(debug) << " [--] "sv;

    for (auto &[name, val] : request->parse_query_string()) {
      BOOST_LOG(debug) << name << " -- " << (boost::iequals(name, "rhremoteLaunchToken") ? "[redacted]" : val);
    }

    BOOST_LOG(debug) << " [--] "sv;
  }

  /**
   * @brief Return a GameStream HTTP not-found response.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  template<class T>
  void not_found(std::shared_ptr<typename SimpleWeb::ServerBase<T>::Response> response, std::shared_ptr<typename SimpleWeb::ServerBase<T>::Request> request) {
    print_req<T>(request);

    pt::ptree tree;
    tree.put("root.<xmlattr>.status_code", 404);

    std::ostringstream data;

    pt::write_xml(data, tree);
    response->write(data.str());

    *response
      << "HTTP/1.1 404 NOT FOUND\r\n"
      << data.str();

    response->close_connection_after_response = true;
  }

  /**
   * @brief Start a GameStream pairing session for a `getservercert` request.
   *
   * @tparam T HTTP server transport type.
   * @param response HTTP response retained while Web UI PIN approval is pending.
   * @param request HTTP request containing the client pairing parameters.
   * @param args Parsed request query parameters.
   * @param tree XML property tree used for immediate responses.
   * @param unique_id Client-provided pairing-session identifier.
   * @return `true` when the response remains pending for Web UI PIN approval.
   */
  template<class T>
  bool start_pairing_session(
    const std::shared_ptr<typename SimpleWeb::ServerBase<T>::Response> &response,
    const std::shared_ptr<typename SimpleWeb::ServerBase<T>::Request> &request,
    const args_t &args,
    pt::ptree &tree,
    const std::string &unique_id
  ) {
    pair_session_t sess;
    sess.client.uniqueID = unique_id;
    sess.client.cert = util::from_hex_vec(get_arg(args, "clientcert"), true);
    sess.async_insert_pin.salt = get_arg(args, "salt");
    sess.async_insert_pin.device_name = get_arg(args, "devicename");
    sess.async_insert_pin.address = net::addr_to_normalized_string(request->remote_endpoint().address());

    BOOST_LOG(debug) << sess.client.cert;
    const bool pin_stdin = config::sunshine.flags[config::flag::PIN_STDIN];
    if (!pin_stdin) {
      sess.async_insert_pin.response = response;
    }

    std::string pairing_id;
    switch (insert_pair_session(std::move(sess), pairing_id)) {
      using enum pair_session_insert_e;

      case ALREADY_EXISTS:
        tree.put("root.paired", 0);
        tree.put("root.<xmlattr>.status_code", 409);
        tree.put("root.<xmlattr>.status_message", "A pairing session with this uniqueid already exists");
        return false;
      case FULL:
        tree.put("root.paired", 0);
        tree.put("root.<xmlattr>.status_code", 503);
        tree.put("root.<xmlattr>.status_message", "Too many pending pairing sessions");
        return false;
      case ADDED:
        break;
    }

    if (!pin_stdin) {
#if defined SUNSHINE_TRAY && SUNSHINE_TRAY >= 1
      system_tray::update_tray_require_pin();
#endif
      return true;
    }

    std::string pin;
    std::cout << "Please insert pin: "sv;
    std::getline(std::cin, pin);

    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());
    const auto sess_it = map_id_sess.find(unique_id);
    if (sess_it == map_id_sess.end() || sess_it->second.async_insert_pin.id != pairing_id) {
      tree.put("root.paired", 0);
      tree.put("root.<xmlattr>.status_code", 408);
      tree.put("root.<xmlattr>.status_message", "Pairing session expired");
      return false;
    }

    getservercert(sess_it->second, tree, pin);
    if (sess_it->second.failed) {
      map_id_sess.erase(sess_it);
    }
    return false;
  }

  /**
   * @brief Dispatch the top-level GameStream pairing request by phase.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  template<class T>
  void pair(std::shared_ptr<typename SimpleWeb::ServerBase<T>::Response> response, std::shared_ptr<typename SimpleWeb::ServerBase<T>::Request> request) {
    print_req<T>(request);

    pt::ptree tree;

    auto fg = util::fail_guard([&]() {
      std::ostringstream data;

      pt::write_xml(data, tree);
      response->write(data.str());
      response->close_connection_after_response = true;
    });

    auto args = request->parse_query_string();
    if (args.find("uniqueid"s) == std::end(args)) {
      tree.put("root.<xmlattr>.status_code", 400);
      tree.put("root.<xmlattr>.status_message", "Missing uniqueid parameter");

      return;
    }

    auto uniqID {get_arg(args, "uniqueid")};

    const auto phrase_it = args.find("phrase");
    if (phrase_it != std::end(args) && phrase_it->second == "rhcancel"sv) {
      const bool cancelled = cancel_pairing_client(uniqID, get_arg(args, "rhcancel_salt", ""), get_arg(args, "rhcancel_sig", ""));
      tree.put("root.paired", 0);
      tree.put("root.cancelled", cancelled ? 1 : 0);
      tree.put("root.<xmlattr>.status_code", cancelled ? 200 : 403);
      return;
    }
    if (phrase_it != std::end(args) && phrase_it->second == "getservercert"sv) {
      if (start_pairing_session<T>(response, request, args, tree, uniqID)) {
        fg.disable();
      }
      return;
    }
    if (phrase_it != std::end(args) && phrase_it->second == "pairchallenge"sv) {
      tree.put("root.paired", 1);
      tree.put("root.<xmlattr>.status_code", 200);
      return;
    }

    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());
    auto sess_it = map_id_sess.find(uniqID);
    if (sess_it == std::end(map_id_sess)) {
      tree.put("root.<xmlattr>.status_code", 400);
      tree.put("root.<xmlattr>.status_message", "Invalid uniqueid");

      return;
    }

    bool pairing_complete = false;
    args_t::const_iterator it;
    if (it = args.find("clientchallenge"); it != std::end(args)) {
      auto challenge = util::from_hex_vec(it->second, true);
      clientchallenge(sess_it->second, tree, challenge);
    } else if (it = args.find("serverchallengeresp"); it != std::end(args)) {
      auto encrypted_response = util::from_hex_vec(it->second, true);
      serverchallengeresp(sess_it->second, tree, encrypted_response);
    } else if (it = args.find("clientpairingsecret"); it != std::end(args)) {
      auto pairingsecret = util::from_hex_vec(it->second, true);
      clientpairingsecret(sess_it->second, tree, pairingsecret);
      pairing_complete = true;
    } else {
      fail_pair(sess_it->second, tree, "Invalid pairing request");
    }

    if (pairing_complete || sess_it->second.failed) {
      map_id_sess.erase(sess_it);
    }
  }

  bool pin(const std::string_view pairing_id, std::string pin, std::string name) {
    if (!is_valid_pairing_id(pairing_id) || !is_valid_pairing_pin(pin) || !is_valid_pairing_name(name)) {
      return false;
    }

    std::scoped_lock lock {map_id_sess_mutex()};
    expire_pair_sessions_unlocked(std::chrono::steady_clock::now());
    const auto sess_it = std::ranges::find_if(map_id_sess, [&](const auto &entry) {
      return entry.second.last_phase == PAIR_PHASE::NONE && entry.second.async_insert_pin.id == pairing_id;
    });
    if (sess_it == map_id_sess.end()) {
      return false;
    }

    auto &sess = sess_it->second;
    pt::ptree tree;
    getservercert(sess, tree, pin);
    if (!sess.failed) {
      sess.client.name = std::move(name);
    }

    const bool response_written = write_pairing_response(sess, tree);
    const bool success = response_written && !sess.failed;
    if (!response_written || sess.failed) {
      map_id_sess.erase(sess_it);
    }
    return success;
  }

  /**
   * @brief Get codec mode flags.
   *
   * @return Moonlight codec capability bitmask for the currently probed encoders.
   */
  uint32_t get_codec_mode_flags() {
    uint32_t codec_mode_flags = SCM_H264;
    if (video::last_encoder_probe_supported_yuv444_for_codec[0]) {
      codec_mode_flags |= SCM_H264_HIGH8_444;
    }
    if (video::active_hevc_mode >= 2) {
      codec_mode_flags |= SCM_HEVC;
      if (video::last_encoder_probe_supported_yuv444_for_codec[1]) {
        codec_mode_flags |= SCM_HEVC_REXT8_444;
      }
    }
    if (video::active_hevc_mode == 3 || video::active_hevc_mode == 5) {
      codec_mode_flags |= SCM_HEVC_MAIN10;
    }
    if ((video::active_hevc_mode == 4 || video::active_hevc_mode == 5) && video::last_encoder_probe_supported_yuv444_for_codec[1]) {
      codec_mode_flags |= SCM_HEVC_REXT10_444;
    }

    if (video::active_av1_mode >= 2) {
      codec_mode_flags |= SCM_AV1_MAIN8;
      if (video::last_encoder_probe_supported_yuv444_for_codec[2]) {
        codec_mode_flags |= SCM_AV1_HIGH8_444;
      }
    }
    if (video::active_av1_mode == 3 || video::active_av1_mode == 5) {
      codec_mode_flags |= SCM_AV1_MAIN10;
    }
    if ((video::active_av1_mode == 4 || video::active_av1_mode == 5) && video::last_encoder_probe_supported_yuv444_for_codec[2]) {
      codec_mode_flags |= SCM_AV1_HIGH10_444;
    }
    return codec_mode_flags;
  }

  /**
   * @brief Build the GameStream server-info response.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  template<class T>
  void serverinfo(std::shared_ptr<typename SimpleWeb::ServerBase<T>::Response> response, std::shared_ptr<typename SimpleWeb::ServerBase<T>::Request> request) {
    print_req<T>(request);

    int pair_status = 0;
    if constexpr (std::is_same_v<SunshineHTTPS, T>) {
      auto args = request->parse_query_string();
      auto clientID = args.find("uniqueid"s);

      if (clientID != std::end(args)) {
        pair_status = 1;
      }
    }

    auto local_endpoint = request->local_endpoint();

    pt::ptree tree;

    tree.put("root.<xmlattr>.status_code", 200);
    tree.put("root.hostname", config::nvhttp.sunshine_name);

    tree.put("root.appversion", VERSION);
    tree.put("root.RHPairingCancel", 1);
    tree.put("root.RHLaunchCancel", 1);
    tree.put("root.GfeVersion", GFE_VERSION);
    tree.put("root.uniqueid", http::unique_id);
    tree.put("root.HttpsPort", net::map_port(PORT_HTTPS));
    tree.put("root.ExternalPort", net::map_port(PORT_HTTP));
    tree.put("root.MaxLumaPixelsHEVC", video::active_hevc_mode > 1 ? "1869449984" : "0");

    // Only include the MAC address for requests sent from paired clients over HTTPS.
    // For HTTP requests, use a placeholder MAC address that Moonlight knows to ignore.
    if constexpr (std::is_same_v<SunshineHTTPS, T>) {
      tree.put("root.mac", platf::get_mac_address(net::addr_to_normalized_string(local_endpoint.address())));
    } else {
      tree.put("root.mac", "00:00:00:00:00:00");
    }

    // Moonlight clients track LAN IPv6 addresses separately from LocalIP which is expected to
    // always be an IPv4 address. If we return that same IPv6 address here, it will clobber the
    // stored LAN IPv4 address. To avoid this, we need to return an IPv4 address in this field
    // when we get a request over IPv6.
    //
    // HACK: We should return the IPv4 address of local interface here, but we don't currently
    // have that implemented. For now, we will emulate the behavior of GFE+GS-IPv6-Forwarder,
    // which returns 127.0.0.1 as LocalIP for IPv6 connections. Moonlight clients with IPv6
    // support know to ignore this bogus address.
    if (local_endpoint.address().is_v6() && !local_endpoint.address().to_v6().is_v4_mapped()) {
      tree.put("root.LocalIP", "127.0.0.1");
    } else {
      tree.put("root.LocalIP", net::addr_to_normalized_string(local_endpoint.address()));
    }

    const uint32_t codec_mode_flags = get_codec_mode_flags();
    tree.put("root.ServerCodecModeSupport", codec_mode_flags);

    if (!config::nvhttp.external_ip.empty()) {
      tree.put("root.ExternalIP", config::nvhttp.external_ip);
    }

    auto current_appid = proc::proc.running();
    tree.put("root.PairStatus", pair_status);
    tree.put("root.currentgame", current_appid);
    tree.put("root.state", current_appid > 0 ? "SUNSHINE_SERVER_BUSY" : "SUNSHINE_SERVER_FREE");

    std::ostringstream data;

    pt::write_xml(data, tree);
    response->write(data.str());
    response->close_connection_after_response = true;
  }

  nlohmann::json get_all_clients() {
    nlohmann::json named_cert_nodes = nlohmann::json::array();
    std::lock_guard lock {client_auth_mutex()};
    for (const auto &named_cert : client_root.named_devices) {
      nlohmann::json named_cert_node;
      named_cert_node["name"] = named_cert.name;
      named_cert_node["uuid"] = named_cert.uuid;
      named_cert_node["enabled"] = named_cert.enabled;
      named_cert_nodes.push_back(named_cert_node);
    }

    return named_cert_nodes;
  }

  /**
   * @brief Build the GameStream application list response.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  void applist(resp_https_t response, req_https_t request) {
    print_req<SunshineHTTPS>(request);

    pt::ptree tree;

    auto g = util::fail_guard([&]() {
      std::ostringstream data;

      pt::write_xml(data, tree);
      response->write(data.str());
      response->close_connection_after_response = true;
    });

    auto &apps = tree.add_child("root", pt::ptree {});

    apps.put("<xmlattr>.status_code", 200);

    for (auto &proc : proc::proc.get_apps()) {
      pt::ptree app;

      app.put("IsHdrSupported"s, video::active_hevc_mode >= 3 ? 1 : 0);
      app.put("AppTitle"s, proc.name);
      app.put("ID", proc.id);

      apps.push_back(std::make_pair("App", std::move(app)));
    }
  }

  /** @brief Serialize a protocol result without including any request secrets. @param response Owned HTTPS response. @param tree Protocol result. */
  template<class Response>
  void write_launch_result(const std::shared_ptr<Response> &response, const pt::ptree &tree) {
    std::ostringstream data;
    pt::write_xml(data, tree);
    response->write(data.str());
    response->close_connection_after_response = true;
  }

  /** @brief Make a launch protocol error. @param code Protocol status. @param message Non-sensitive reason. @return XML tree. */
  pt::ptree launch_error(int code, std::string_view message) {
    pt::ptree tree;
    tree.put("root.<xmlattr>.status_code", code);
    tree.put("root.<xmlattr>.status_message", message);
    tree.put("root.resume", 0);
    tree.put("root.gamesession", 0);
    return tree;
  }

  /** @brief Keep process-state readers on the same serial worker as launch/resume. @tparam Response HTTP response type. @tparam Request HTTP request type. @tparam Handler Existing protocol handler. @param worker Owned bounded queue. @param response Owned response. @param request Accepted request. @param handler Existing server-info or app handler. */
  template<class Response, class Request, class Handler>
  void defer_process_read(rhremote::launch_worker &worker, std::shared_ptr<Response> response, std::shared_ptr<Request> request, Handler handler) {
    const auto task = [response, request, handler] {
      handler(response, request);
    };
    // Only the authenticated application-list path needs these diagnostics.
    const bool accepted = request->path == "/applist" ? worker.submit_observed(task, [](const auto timing) {
      const auto us = [](const auto point) {
        return std::chrono::duration_cast<std::chrono::microseconds>(point.time_since_epoch()).count();
      };
      BOOST_LOG(info) << "RHREMOTE_HTTP_READ name=applist queued_us=" << us(timing.queued)
                      << " start_us=" << us(timing.started) << " end_us=" << us(timing.finished);
    }) :
                                                        worker.submit(task);
    if (!accepted) {
      write_launch_result(response, launch_error(503, "Host state queue is full or closed"));
    }
  }

  /** @brief Recheck the snapshotted peer under the authorization mutex. @param peer Actual TLS peer. @note Caller must hold client_auth_mutex(). @return Whether that exact certificate is still enabled. */
  bool launch_peer_authorized_locked(const rhremote::launch_peer &peer) {
    auto cert = crypto::x509(peer.certificate);
    return cert && !verify_client_certificate(cert.get());
  }

  /** @brief Stop cancelled preparations at real side-effect boundaries. @param ownership Original request handle. @param tree Rejection result. @return Whether preparation may continue. */
  bool launch_checkpoint(const std::shared_ptr<rhremote::launch_ticket> &ownership, pt::ptree &tree) {
    if (rtsp_stream::launch_request_preparing(ownership)) {
      return true;
    }
    tree = launch_error(410, "Launch request was cancelled or expired");
    return false;
  }

  /** @brief Publish only an unrevoked peer and still-owned preparation. @param session Prepared session. @param peer Actual TLS peer. @param tree Rejection result. @return Whether RTSP publication succeeded. */
  bool publish_owned_launch(const std::shared_ptr<rtsp_stream::launch_session_t> &session, const rhremote::launch_peer &peer, pt::ptree &tree) {
    std::lock_guard lock {client_auth_mutex()};
    if (!launch_peer_authorized_locked(peer)) {
      tree = launch_error(401, "Client authorization was revoked");
      return false;
    }
    if (!rtsp_stream::launch_session_raise(session)) {
      tree = launch_error(409, "Launch request no longer owns the pending slot");
      return false;
    }
    return true;
  }

  /**
   * @brief Launch the requested application for a GameStream session.
   *
   * @param host_audio Host audio.
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   * @param peer Actual TLS peer captured before deferring the request.
   * @param ownership Original request handle, or empty for legacy clients.
   */
  void launch(bool &host_audio, resp_https_t response, req_https_t request, const rhremote::launch_peer &peer, const std::shared_ptr<rhremote::launch_ticket> &ownership) {
    print_req<SunshineHTTPS>(request);
    std::scoped_lock launch_lock {launch_mutex()};

    pt::ptree tree;
    bool revert_display_configuration {false};
    auto g = util::fail_guard([&]() {
      rtsp_stream::launch_request_finish(ownership);
      std::ostringstream data;

      if (tree.empty()) {
        BOOST_LOG(error) << EMPTY_PROPERTY_TREE_ERROR_MSG;
        tree = launch_error(500, "Launch preparation failed");
      }

      pt::write_xml(data, tree);
      response->write(data.str());
      response->close_connection_after_response = true;

      if (revert_display_configuration) {
        display_device::revert_configuration();
      }
    });

    if (!launch_checkpoint(ownership, tree)) {
      return;
    }

    auto args = request->parse_query_string();
    if (
      args.find("rikey"s) == std::end(args) ||
      args.find("rikeyid"s) == std::end(args) ||
      args.find("localAudioPlayMode"s) == std::end(args) ||
      args.find("appid"s) == std::end(args)
    ) {
      tree.put("root.resume", 0);
      tree.put("root.<xmlattr>.status_code", 400);
      tree.put("root.<xmlattr>.status_message", "Missing a required launch parameter");

      return;
    }

    auto appid = util::from_view(get_arg(args, "appid"));

    auto current_appid = proc::proc.running();
    if (current_appid > 0) {
      tree.put("root.resume", 0);
      tree.put("root.<xmlattr>.status_code", 400);
      tree.put("root.<xmlattr>.status_message", "An app is already running on this host");

      return;
    }

    const bool requested_host_audio = util::from_view(get_arg(args, "localAudioPlayMode"));
    auto launch_session = make_launch_session(requested_host_audio, args, peer);
    launch_session->ownership = ownership;

    if (!prepare_initial_display(args, *launch_session, tree)) {
      return;
    }
    if (!launch_checkpoint(ownership, tree)) {
      return;
    }
    host_audio = requested_host_audio;

    if (rtsp_stream::session_count() == 0) {
      // The display should be restored in case something fails as there are no other sessions.
      revert_display_configuration = true;

      // We want to prepare display only if there are no active sessions at
      // the moment. This should be done before probing encoders as it could
      // change the active displays.
      display_device::configure_display(config::video, *launch_session);
      if (!launch_checkpoint(ownership, tree)) {
        return;
      }

      // Probe encoders again before streaming to ensure our chosen
      // encoder matches the active GPU (which could have changed
      // due to hotplugging, driver crash, primary monitor change,
      // or any number of other factors).
      if (video::probe_encoders()) {
        tree.put("root.<xmlattr>.status_code", 503);
        tree.put("root.<xmlattr>.status_message", "Failed to initialize video capture/encoding. Is a display connected and turned on?");
        tree.put("root.gamesession", 0);

        return;
      }
    }

    auto encryption_mode = net::encryption_mode_for_address(request->remote_endpoint().address());
    if (!launch_session->rtsp_cipher && encryption_mode == config::ENCRYPTION_MODE_MANDATORY) {
      BOOST_LOG(error) << "Rejecting client that cannot comply with mandatory encryption requirement"sv;

      tree.put("root.<xmlattr>.status_code", 403);
      tree.put("root.<xmlattr>.status_message", "Encryption is mandatory for this host but unsupported by the client");
      tree.put("root.gamesession", 0);

      return;
    }

    if (appid > 0) {
      if (!launch_checkpoint(ownership, tree)) {
        return;
      }
      auto err = proc::proc.execute((int) appid, launch_session);
      if (err) {
        tree.put("root.<xmlattr>.status_code", err);
        tree.put("root.<xmlattr>.status_message", "Failed to start the specified application");
        tree.put("root.gamesession", 0);

        return;
      }
    }

    tree.put("root.<xmlattr>.status_code", 200);
    tree.put(
      "root.sessionUrl0",
      std::format(
        "{}{}:{}",
        launch_session->rtsp_url_scheme,
        net::addr_to_url_escaped_string(request->local_endpoint().address()),
        static_cast<int>(net::map_port(rtsp_stream::RTSP_SETUP_PORT))
      )
    );
    tree.put("root.gamesession", 1);

    tree.put("root.RHFrameTrace", launch_session->frame_trace_requested ? 1 : 0);
    if (!launch_session->initial_display.empty()) {
      tree.put("root.RHInitialDisplay", launch_session->initial_display);
    }

    if (!publish_owned_launch(launch_session, peer, tree)) {
      return;
    }

    // Stream was started successfully, we will revert the config when the app or session terminates
    revert_display_configuration = false;
  }

  /**
   * @brief Resume an existing GameStream session.
   *
   * @param host_audio Host audio.
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   * @param peer Actual TLS peer captured before deferring the request.
   * @param ownership Original request handle, or empty for legacy clients.
   */
  void resume(bool &host_audio, resp_https_t response, req_https_t request, const rhremote::launch_peer &peer, const std::shared_ptr<rhremote::launch_ticket> &ownership) {
    print_req<SunshineHTTPS>(request);
    std::scoped_lock launch_lock {launch_mutex()};

    pt::ptree tree;
    bool revert_display_configuration {false};
    auto g = util::fail_guard([&]() {
      rtsp_stream::launch_request_finish(ownership);
      std::ostringstream data;

      if (tree.empty()) {
        BOOST_LOG(error) << EMPTY_PROPERTY_TREE_ERROR_MSG;
        tree = launch_error(500, "Resume preparation failed");
      }

      pt::write_xml(data, tree);
      response->write(data.str());
      response->close_connection_after_response = true;
      if (revert_display_configuration) {
        display_device::revert_configuration();
      }
    });

    if (!launch_checkpoint(ownership, tree)) {
      return;
    }

    auto current_appid = proc::proc.running();
    if (current_appid == 0) {
      tree.put("root.resume", 0);
      tree.put("root.<xmlattr>.status_code", 503);
      tree.put("root.<xmlattr>.status_message", "No running app to resume");

      return;
    }

    auto args = request->parse_query_string();
    if (
      args.find("rikey"s) == std::end(args) ||
      args.find("rikeyid"s) == std::end(args)
    ) {
      tree.put("root.resume", 0);
      tree.put("root.<xmlattr>.status_code", 400);
      tree.put("root.<xmlattr>.status_message", "Missing a required resume parameter");

      return;
    }

    // Newer Moonlight clients send localAudioPlayMode on /resume too,
    // so we should use it if it's present in the args and there are
    // no active sessions we could be interfering with.
    const bool no_active_sessions {rtsp_stream::session_count() == 0};
    const bool requested_host_audio = no_active_sessions && args.find("localAudioPlayMode"s) != std::end(args) ? util::from_view(get_arg(args, "localAudioPlayMode")) : host_audio;
    const auto launch_session = make_launch_session(requested_host_audio, args, peer);
    launch_session->ownership = ownership;

    if (!prepare_initial_display(args, *launch_session, tree)) {
      return;
    }
    if (!launch_checkpoint(ownership, tree)) {
      return;
    }
    if (no_active_sessions) {
      revert_display_configuration = true;
      host_audio = requested_host_audio;
      // We want to prepare display only if there are no active sessions at
      // the moment. This should be done before probing encoders as it could
      // change the active displays.
      display_device::configure_display(config::video, *launch_session);
      if (!launch_checkpoint(ownership, tree)) {
        return;
      }

      // Probe encoders again before streaming to ensure our chosen
      // encoder matches the active GPU (which could have changed
      // due to hotplugging, driver crash, primary monitor change,
      // or any number of other factors).
      if (video::probe_encoders()) {
        tree.put("root.resume", 0);
        tree.put("root.<xmlattr>.status_code", 503);
        tree.put("root.<xmlattr>.status_message", "Failed to initialize video capture/encoding. Is a display connected and turned on?");

        return;
      }
    }

    auto encryption_mode = net::encryption_mode_for_address(request->remote_endpoint().address());
    if (!launch_session->rtsp_cipher && encryption_mode == config::ENCRYPTION_MODE_MANDATORY) {
      BOOST_LOG(error) << "Rejecting client that cannot comply with mandatory encryption requirement"sv;

      tree.put("root.<xmlattr>.status_code", 403);
      tree.put("root.<xmlattr>.status_message", "Encryption is mandatory for this host but unsupported by the client");
      tree.put("root.gamesession", 0);

      return;
    }

    tree.put("root.<xmlattr>.status_code", 200);
    tree.put(
      "root.sessionUrl0",
      std::format(
        "{}{}:{}",
        launch_session->rtsp_url_scheme,
        net::addr_to_url_escaped_string(request->local_endpoint().address()),
        static_cast<int>(net::map_port(rtsp_stream::RTSP_SETUP_PORT))
      )
    );
    tree.put("root.resume", 1);

    tree.put("root.RHFrameTrace", launch_session->frame_trace_requested ? 1 : 0);
    if (!launch_session->initial_display.empty()) {
      tree.put("root.RHInitialDisplay", launch_session->initial_display);
    }

    if (publish_owned_launch(launch_session, peer, tree)) {
      revert_display_configuration = false;
    }
  }

  /**
   * @brief Check whether cel.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  void cancel(resp_https_t response, req_https_t request) {
    print_req<SunshineHTTPS>(request);
    std::scoped_lock launch_lock {launch_mutex()};

    pt::ptree tree;
    auto g = util::fail_guard([&]() {
      std::ostringstream data;

      pt::write_xml(data, tree);
      response->write(data.str());
      response->close_connection_after_response = true;
    });

    tree.put("root.cancel", 1);
    tree.put("root.<xmlattr>.status_code", 200);

    rtsp_stream::terminate_sessions();

    if (proc::proc.running() > 0) {
      proc::proc.terminate();
    }

    // The config needs to be reverted regardless of whether "proc::proc.terminate()" was called or not.
    display_device::revert_configuration();
  }

  /** @brief Authenticate and admit one real launch/resume before it enters the worker queue. @param args Actual query. @param peer Verified TLS identity. @param error Rejection result. @return Admission, with an empty handle for a legacy request. */
  rhremote::launch_admission admit_launch(const args_t &args, const rhremote::launch_peer &peer, pt::ptree &error) {
    std::lock_guard lock {client_auth_mutex()};
    if (!launch_peer_authorized_locked(peer)) {
      error = launch_error(401, "Client authorization was revoked");
      return {rhremote::launch_result::forbidden, {}};
    }
    const auto token = args.find("rhremoteLaunchToken");
    if (token == args.end()) {
      if (rtsp_stream::launch_request_preparing({})) {
        return {rhremote::launch_result::accepted, {}};
      }
      error = launch_error(503, "Server is shutting down");
      return {rhremote::launch_result::closed, {}};
    }
    if (args.count("rhremoteLaunchToken") != 1 || !rhremote::pending_launches<rtsp_stream::launch_session_t>::valid(token->second)) {
      error = launch_error(400, "Invalid launch ownership credential");
      return {rhremote::launch_result::invalid, {}};
    }
    auto admission = rtsp_stream::launch_request_begin(token->second, peer.certificate);
    if (admission.result != rhremote::launch_result::accepted) {
      const auto code = admission.result == rhremote::launch_result::forbidden ? 403 :
                        admission.result == rhremote::launch_result::cancelled ? 410 :
                        admission.result == rhremote::launch_result::duplicate ? 409 :
                                                                                 503;
      error = launch_error(code, "Launch ownership admission rejected");
    }
    return admission;
  }

  /** @brief Cancel one pending launch without touching active streams, applications or display configuration. @param args Actual HTTPS query. @param peer Actual paired TLS identity. @return Non-sensitive protocol result. */
  pt::ptree pending_launch_cancel(const args_t &args, const rhremote::launch_peer &peer) {
    std::lock_guard lock {client_auth_mutex()};
    if (!launch_peer_authorized_locked(peer)) {
      return launch_error(401, "Client authorization was revoked");
    }
    const auto token = args.find("rhremoteLaunchToken");
    if (args.count("rhremoteLaunchToken") != 1) {
      return launch_error(400, "One launch ownership credential is required");
    }
    const auto result = rtsp_stream::launch_request_cancel(token->second, peer.certificate);
    std::string state;
    switch (result) {
      case rhremote::launch_result::cancelled:
        state = "cancelled";
        break;
      case rhremote::launch_result::handed_off:
        state = "handed_off";
        break;
      case rhremote::launch_result::finished:
        state = "finished";
        break;
      case rhremote::launch_result::expired:
        state = "expired";
        break;
      default:
        return launch_error(result == rhremote::launch_result::forbidden ? 403 : result == rhremote::launch_result::invalid ? 400 :
                                                                                                                              503,
                            "Pending launch cancellation rejected");
    }
    pt::ptree tree;
    tree.put("root.<xmlattr>.status_code", 200);
    tree.put("root.cancel", 1);
    tree.put("root.RHLaunchState", state);
    return tree;
  }

  /** @brief Defer serialized legacy cancellation or launch preparation, keeping HTTPS free for request cancellation. @param server Server holding actual TLS bindings. @param worker Owned bounded worker. @param host_audio Shared launch setting, accessed only on the serial worker. @param response Owned response. @param request Actual accepted request. */
  void defer_launch(https_server_t &server, rhremote::launch_worker &worker, bool &host_audio, resp_https_t response, req_https_t request) {
    auto peer = server.verified_requests.peer(request);
    if (!peer) {
      write_launch_result(response, launch_error(401, "Authenticated TLS request identity is unavailable"));
      return;
    }
    const bool global_cancel = request->path == "/cancel";
    pt::ptree error;
    auto admission = global_cancel ? rhremote::launch_admission {rhremote::launch_result::accepted, {}} : admit_launch(request->parse_query_string(), *peer, error);
    if (admission.result != rhremote::launch_result::accepted) {
      write_launch_result(response, error);
      return;
    }
    const auto ownership = admission.ticket;
    if (!worker.submit([&host_audio, response, request, peer = std::move(*peer), ownership, global_cancel] {
          bool authorized;
          {
            std::lock_guard lock {client_auth_mutex()};
            authorized = launch_peer_authorized_locked(peer);
          }
          if (!authorized) {
            rtsp_stream::launch_request_finish(ownership);
            write_launch_result(response, launch_error(401, "Client authorization was revoked"));
          } else if (global_cancel) {
            cancel(response, request);
          } else if (request->path == "/resume") {
            resume(host_audio, response, request, peer, ownership);
          } else {
            launch(host_audio, response, request, peer, ownership);
          }
        })) {
      rtsp_stream::launch_request_finish(ownership);
      write_launch_result(response, launch_error(503, "Launch preparation queue is full or closed"));
    }
  }

  /**
   * @brief Return an application asset requested by the client.
   *
   * @param response HTTP response object to populate.
   * @param request HTTP request data from the client.
   */
  void appasset(resp_https_t response, req_https_t request) {
    print_req<SunshineHTTPS>(request);

    auto args = request->parse_query_string();
    auto app_image = proc::proc.get_app_image((int) util::from_view(get_arg(args, "appid")));

    std::ifstream in(app_image, std::ios::binary);
    SimpleWeb::CaseInsensitiveMultimap headers;
    headers.emplace("Content-Type", "image/png");
    response->write(SimpleWeb::StatusCode::success_ok, in, headers);
    response->close_connection_after_response = true;
  }

  void setup(const std::string &pkey, const std::string &cert) {
    conf_intern.pkey = pkey;
    conf_intern.servercert = cert;
  }

  /**
   * @brief Check whether a paired client certificate is allowed to connect and retrieve its friendly name.
   *
   * @param cert_pem PEM-encoded client certificate to look up.
   * @return Pair of (bool enabled, string name) where "enabled" is True when the client certificate belongs to an
             enabled device and "name" is the friendly client name set during pairing.
   */
  std::pair<bool, std::string> get_client_status(const std::string_view cert_pem);

  void start() {
    platf::set_thread_name("nvhttp");
    auto shutdown_event = mail::man->event<bool>(mail::shutdown);

    auto port_http = net::map_port(PORT_HTTP);
    auto port_https = net::map_port(PORT_HTTPS);
    auto address_family = net::af_from_enum_string(config::sunshine.address_family);

    bool clean_slate = config::sunshine.flags[config::flag::FRESH_STATE];

    if (!clean_slate) {
      load_state();
    }

    auto pkey = file_handler::read_file(config::nvhttp.pkey.c_str());
    auto cert = file_handler::read_file(config::nvhttp.cert.c_str());
    setup(pkey, cert);

    // resume doesn't always get the parameter "localAudioPlayMode"
    // launch will store it in host_audio
    bool host_audio {};

    https_server_t https_server {config::nvhttp.cert, config::nvhttp.pkey};
    http_server_t http_server;
    rhremote::launch_worker worker(2, [] {
      BOOST_LOG(error) << "Deferred launch preparation raised an exception"sv;
    });

    // Verify certificates after establishing connection
    https_server.verify = [](SSL *ssl) -> std::optional<rhremote::launch_peer> {
      crypto::x509_t x509 {
#if OPENSSL_VERSION_MAJOR >= 3
        SSL_get1_peer_certificate(ssl)
#else
        SSL_get_peer_certificate(ssl)
#endif
      };
      if (!x509) {
        BOOST_LOG(info) << "unknown -- denied"sv;
        return {};
      }

      int verified = 0;

      auto fg = util::fail_guard([&]() {
        char subject_name[256];

        X509_NAME_oneline(X509_get_subject_name(x509.get()), subject_name, sizeof(subject_name));

        BOOST_LOG(debug) << subject_name << " -- "sv << (verified ? "verified"sv : "denied"sv);
      });

      std::lock_guard lock {client_auth_mutex()};
      auto err_str = verify_client_certificate(x509.get());
      if (err_str) {
        BOOST_LOG(warning) << "SSL Verification error :: "sv << err_str;

        return {};
      }

      // Check if this client is enabled
      auto pem = crypto::pem(x509);
      auto [enabled, client_name] = get_client_status(pem);
      if (!enabled) {
        BOOST_LOG(info) << "Client is disabled -- denied"sv;
        return {};
      }

      verified = 1;
      return rhremote::launch_peer {std::move(pem), std::move(client_name)};
    };

    https_server.on_verify_failed = [](resp_https_t resp, req_https_t req) {
      pt::ptree tree;
      auto g = util::fail_guard([&]() {
        std::ostringstream data;

        pt::write_xml(data, tree);
        resp->write(data.str());
        resp->close_connection_after_response = true;
      });

      tree.put("root.<xmlattr>.status_code"s, 401);
      tree.put("root.<xmlattr>.query"s, req->path);
      tree.put("root.<xmlattr>.status_message"s, "The client is not authorized. Certificate verification failed."s);
    };

    https_server.default_resource["GET"] = not_found<SunshineHTTPS>;
    https_server.resource["^/serverinfo$"]["GET"] = [&worker](auto resp, auto req) {
      defer_process_read(worker, resp, req, serverinfo<SunshineHTTPS>);
    };
    https_server.resource["^/pair$"]["GET"] = [](auto resp, auto req) {
      pair<SunshineHTTPS>(resp, req);
    };
    https_server.resource["^/applist$"]["GET"] = [&worker](auto resp, auto req) {
      defer_process_read(worker, resp, req, applist);
    };
    https_server.resource["^/appasset$"]["GET"] = [&worker](auto resp, auto req) {
      defer_process_read(worker, resp, req, appasset);
    };
    https_server.resource["^/launch$"]["GET"] = [&host_audio, &https_server, &worker](auto resp, auto req) {
      defer_launch(https_server, worker, host_audio, resp, req);
    };
    https_server.resource["^/resume$"]["GET"] = [&host_audio, &https_server, &worker](auto resp, auto req) {
      defer_launch(https_server, worker, host_audio, resp, req);
    };
    https_server.resource["^/cancel$"]["GET"] = [&host_audio, &https_server, &worker](auto resp, auto req) {
      defer_launch(https_server, worker, host_audio, resp, req);
    };
    https_server.resource["^/rhremote/launch/cancel$"]["GET"] = [&https_server](auto resp, auto req) {
      print_req<SunshineHTTPS>(req);
      const auto peer = https_server.verified_requests.peer(req);
      write_launch_result(resp, peer ? pending_launch_cancel(req->parse_query_string(), *peer) : launch_error(401, "Authenticated TLS request identity is unavailable"));
    };

    https_server.config.reuse_address = true;
    https_server.config.address = net::get_bind_address(address_family);
    https_server.config.port = port_https;

    http_server.default_resource["GET"] = not_found<SimpleWeb::HTTP>;
    http_server.resource["^/serverinfo$"]["GET"] = [&worker](auto resp, auto req) {
      defer_process_read(worker, resp, req, serverinfo<SimpleWeb::HTTP>);
    };
    http_server.resource["^/pair$"]["GET"] = [](auto resp, auto req) {
      pair<SimpleWeb::HTTP>(resp, req);
    };

    http_server.config.reuse_address = true;
    http_server.config.address = net::get_bind_address(address_family);
    http_server.config.port = port_http;

    auto accept_and_run = [&](auto *http_server) {
      try {
        std::string name = "nvhttp::" + std::to_string(http_server->config.port);
        platf::set_thread_name(name);
        http_server->start();
      } catch (boost::system::system_error &err) {
        // It's possible the exception gets thrown after calling http_server->stop() from a different thread
        if (shutdown_event->peek()) {
          return;
        }

        BOOST_LOG(fatal) << "Couldn't start http server on ports ["sv << port_https << ", "sv << port_https << "]: "sv << err.what();
        shutdown_event->raise(true);
        return;
      }
    };
    std::jthread ssl {accept_and_run, &https_server};
    std::jthread tcp {accept_and_run, &http_server};

    // Wait for any event
    shutdown_event->view();

    rtsp_stream::launch_requests_close();
    worker.close();
    https_server.stop();
    http_server.stop();

    ssl.join();
    tcp.join();
  }

  void erase_all_clients() {
    std::lock_guard lock {client_auth_mutex()};
    for (const auto &client : client_root.named_devices) {
      rtsp_stream::launch_request_revoke(client.cert);
    }
    client_root = {};
    cert_chain.clear();
    save_state();
  }

  bool unpair_client(const std::string_view uuid) {
    std::lock_guard lock {client_auth_mutex()};
    bool removed = false;
    for (auto it = client_root.named_devices.begin(); it != client_root.named_devices.end();) {
      if ((*it).uuid == uuid) {
        rtsp_stream::launch_request_revoke(it->cert);
        it = client_root.named_devices.erase(it);
        removed = true;
      } else {
        ++it;
      }
    }

    rebuild_client_cert_chain();
    save_state();
    return removed;
  }

  bool set_client_enabled(const std::string_view uuid, bool enabled) {
    std::lock_guard lock {client_auth_mutex()};
    for (auto &named_cert : client_root.named_devices) {
      if (named_cert.uuid == uuid) {
        named_cert.enabled = enabled;
        if (!enabled) {
          rtsp_stream::launch_request_revoke(named_cert.cert);
        }
        rebuild_client_cert_chain();
        save_state();
        return true;
      }
    }
    return false;
  }

  /**
   * @brief Get cert by UUID.
   */
  std::string get_cert_by_uuid(const std::string_view uuid) {
    std::lock_guard lock {client_auth_mutex()};
    for (const auto &named_cert : client_root.named_devices) {
      if (named_cert.uuid == uuid) {
        return named_cert.cert;
      }
    }
    return {};
  }

  /**
   * @brief Check whether a paired client certificate is allowed to connect and return its friendly name.
   */
  std::pair<bool, std::string> get_client_status(const std::string_view cert_pem) {
    const client_t &client = client_root;
    for (const auto &named_cert : client.named_devices) {
      if (named_cert.cert == cert_pem) {
        return {named_cert.enabled, named_cert.name};
      }
    }
    return {true, {}};
  }

#ifdef SUNSHINE_TESTS
  namespace test_support {
    rhremote::launch_admission admit_launch(const SimpleWeb::CaseInsensitiveMultimap &args, std::string_view certificate, pt::ptree &error) {
      return nvhttp::admit_launch(args, {std::string(certificate), "fixture"}, error);
    }

    pt::ptree pending_launch_cancel(const SimpleWeb::CaseInsensitiveMultimap &args, std::string_view certificate) {
      return nvhttp::pending_launch_cancel(args, {std::string(certificate), "fixture"});
    }

    void serverinfo_http(
      std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Response> response,
      std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Request> request
    ) {
      serverinfo<SimpleWeb::HTTP>(std::move(response), std::move(request));
    }

    void pair_http(
      std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Response> response,
      std::shared_ptr<typename SimpleWeb::ServerBase<SimpleWeb::HTTP>::Request> request
    ) {
      pair<SimpleWeb::HTTP>(std::move(response), std::move(request));
    }

    void reset_client_state() {
      std::lock_guard lock {client_auth_mutex()};
      client_root = {};
      cert_chain.clear();
    }

    std::string add_client(const std::string &name, std::string cert, bool enabled) {
      auto uuid = add_authorized_client(name, std::move(cert));
      if (!uuid.empty() && !enabled) {
        set_client_enabled(uuid, false);
      }
      return uuid;
    }

    bool authorize_client_certificate(const std::string_view cert) {
      auto certificate = crypto::x509(cert);
      if (!certificate) {
        return false;
      }

      std::lock_guard lock {client_auth_mutex()};
      return verify_client_certificate(certificate.get()) == nullptr;
    }

    void reload_client_state() {
      load_state();
    }
  }  // namespace test_support
#endif
}  // namespace nvhttp
