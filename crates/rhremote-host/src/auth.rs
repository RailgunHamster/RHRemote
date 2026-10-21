use rustls::{
    DigitallySignedStruct, DistinguishedName, Error, SignatureScheme,
    client::danger::HandshakeSignatureValid,
    pki_types::{CertificateDer, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
};
use std::{io::Cursor, path::PathBuf, sync::Arc};

/// Trust exactly the enabled certificates already paired in Sunshine. No account password or new shared token.
#[derive(Debug)]
pub struct SunshineClients {
    pub state: PathBuf,
    provider: Arc<rustls::crypto::CryptoProvider>,
}
impl SunshineClients {
    pub fn new(state: PathBuf) -> Self {
        Self {
            state,
            provider: Arc::new(rustls::crypto::ring::default_provider()),
        }
    }
    pub fn allowed(&self, peer: &[u8]) -> bool {
        std::fs::read(&self.state)
            .ok()
            .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            .is_some_and(|state| {
                state["root"]["named_devices"]
                    .as_array()
                    .is_some_and(|clients| {
                        clients.iter().any(|client| {
                            let enabled = client
                                .get("enabled")
                                .is_none_or(|v| v == true || v == "true");
                            enabled
                                && client["cert"].as_str().is_some_and(|pem| {
                                    rustls_pemfile::certs(&mut Cursor::new(pem.as_bytes()))
                                        .filter_map(Result::ok)
                                        .any(|cert| cert.as_ref() == peer)
                                })
                        })
                    })
            })
    }
}
impl ClientCertVerifier for SunshineClients {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        if intermediates.is_empty() && self.allowed(end_entity.as_ref()) {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(Error::General(
                "Client is not an enabled Sunshine peer".into(),
            ))
        }
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn disabled_and_removed_sunshine_peers_are_rejected_without_restart() {
        let path = std::env::temp_dir().join(format!(
            "rhremote-host-auth-{}-{}.json",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let verifier = SunshineClients::new(path.clone());
        let client = serde_json::json!({"cert":"-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----","enabled":"true"});
        let mut state = serde_json::json!({"root":{"named_devices":[client]}});
        std::fs::write(&path, state.to_string()).unwrap();
        assert!(verifier.allowed(&[1, 2, 3]));
        assert!(!verifier.allowed(&[4, 5, 6]));
        state["root"]["named_devices"][0]["enabled"] = serde_json::json!(false);
        std::fs::write(&path, state.to_string()).unwrap();
        assert!(!verifier.allowed(&[1, 2, 3]));
        std::fs::remove_file(path).unwrap();
        assert!(!verifier.allowed(&[1, 2, 3]));
    }
}
