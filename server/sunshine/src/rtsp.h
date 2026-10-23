/**
 * @file src/rtsp.h
 * @brief Declarations for RTSP streaming.
 */
#pragma once

// standard includes
#include <atomic>

// local includes
#include "crypto.h"
#include "rhremote_pending_launch.h"
#include "thread_safe.h"

namespace rtsp_stream {
  constexpr auto RTSP_SETUP_PORT = 21;  ///< GameStream base-port offset used for the RTSP setup listener.

  /**
   * @brief RTSP launch session state shared with stream setup.
   */
  struct launch_session_t {
    uint32_t id;  ///< RTSP launch-session identifier assigned before stream startup.

    crypto::aes_t gcm_key;  ///< AES-GCM key negotiated for encrypted RTSP messages.
    crypto::aes_t iv;  ///< Initial RTSP AES-GCM IV supplied by the client.

    std::string av_ping_payload;  ///< AV ping payload.
    uint32_t control_connect_data;  ///< Client-provided token used when connecting the control channel.

    bool host_audio;  ///< Whether host audio should be played locally.
    std::string unique_id;  ///< Moonlight client unique identifier for this launch request.
    int width;  ///< Frame or display width in pixels.
    int height;  ///< Frame or display height in pixels.
    int fps;  ///< Requested video frame rate.
    int gcmap;  ///< Game controller mapping requested by the client.
    int appid;  ///< Application ID requested for launch or resume.
    int surround_info;  ///< Encoded GameStream surround-sound capability flags.
    std::string surround_params;  ///< Client-provided surround-sound layout parameters.
    bool continuous_audio;  ///< Whether audio packets continue during silence.
    bool enable_hdr;  ///< Whether HDR streaming is requested.
    bool enable_sops;  ///< Whether sequence output protection is requested.
    std::string client_name;  ///< Friendly client name from initial pairing.

    std::optional<crypto::cipher::gcm_t> rtsp_cipher;  ///< AES-GCM cipher used once encrypted RTSP is negotiated.
    std::string rtsp_url_scheme;  ///< URL scheme selected by the RTSP SETUP flow.
    uint32_t rtsp_iv_counter;  ///< Counter value mixed into encrypted RTSP IVs.
    std::string client_cert;  ///< PEM certificate for the paired Moonlight client.
    std::string initial_display;  ///< Optional per-launch GDI source, resolved before first capture.
    bool frame_trace_requested {};  ///< Paired client explicitly requested metadata-only frame tracing.
    std::string frame_trace_client;  ///< Validated client statistics label, never used as a host path.
    std::shared_ptr<rhremote::launch_ticket> ownership;  ///< Authenticated request lifetime; empty for legacy clients.
  };

  /**
   * @brief Queue a launch session until the RTSP client connects.
   *
   * @param launch_session Session state prepared by the GameStream launch handler.
   * @return Whether this still-owned request acquired the pending slot.
   */
  bool launch_session_raise(std::shared_ptr<launch_session_t> launch_session);

  /** @brief Admit authenticated HTTP preparation. @param credential Fresh per-request secret. @param owner Canonical paired TLS certificate. @return Admission and opaque preparation handle. */
  rhremote::launch_admission launch_request_begin(std::string_view credential, std::string_view owner);

  /** @brief Check admission before slow preparation or publication. @param request Original handle, empty for legacy. @return Whether preparation may continue. */
  bool launch_request_preparing(const std::shared_ptr<rhremote::launch_ticket> &request);

  /** @brief Finish preparation without removing a published or cancelled request. @param request Original handle. */
  void launch_request_finish(const std::shared_ptr<rhremote::launch_ticket> &request);

  /** @brief Cancel only this authenticated pending request. @param credential Fresh per-request secret. @param owner Canonical paired TLS certificate. @return Cancellation or irreversible handoff outcome. */
  rhremote::launch_result launch_request_cancel(std::string_view credential, std::string_view owner);

  /** @brief Permanently close request admission during server shutdown, before draining HTTP workers. */
  void launch_requests_close();

  /** @brief Revoke pending preparation without joining active stream threads under the authorization lock. @param owner Canonical certificate being disabled or removed. */
  void launch_request_revoke(std::string_view owner);

  /**
   * @brief Clear state for the specified launch session.
   * @param launch_session_id The ID of the session to clear.
   */
  void launch_session_clear(uint32_t launch_session_id);

  /**
   * @brief Get the number of active sessions.
   * @return Count of active sessions.
   */
  int session_count();

  /** @brief Check whether another HTTP launch still owns the RTSP handshake slot. @return Whether a launch is pending. */
  bool launch_pending();

  /**
   * @brief Terminates all running streaming sessions.
   */
  void terminate_sessions();
  /**
   * @brief Terminate active sessions associated with a client certificate.
   *
   * @param cert Certificate data or object used by the operation.
   */
  void terminate_sessions_by_cert(std::string_view cert);

  /**
   * @brief Runs the RTSP server loop.
   */
  void start();
}  // namespace rtsp_stream
