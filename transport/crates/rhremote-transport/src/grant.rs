//! Signed public relay enrollment. No account, pairing key or bearer secret is sent.
use anyhow::{Result, ensure};
use iroh::{EndpointId, SecretKey, Signature};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Grant {
    pub host_id: String,
    pub endpoint_id: String,
    pub issued_at: u64,
    pub expires_at: u64,
    pub signature: String,
}
impl Grant {
    fn message(&self) -> String {
        format!(
            "RHREMOTE-RELAY-GRANT-1\n{}\n{}\n{}\n{}",
            self.host_id, self.endpoint_id, self.issued_at, self.expires_at
        )
    }
    pub fn sign(
        host: &SecretKey,
        endpoint_id: &str,
        issued_at: u64,
        expires_at: u64,
    ) -> Result<Self> {
        let _: EndpointId = endpoint_id.parse()?;
        let mut grant = Self {
            host_id: host.public().to_string(),
            endpoint_id: endpoint_id.into(),
            issued_at,
            expires_at,
            signature: String::new(),
        };
        grant.signature = host
            .sign(grant.message().as_bytes())
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        Ok(grant)
    }
    pub fn verify(&self, now: u64) -> Result<()> {
        let host: EndpointId = self.host_id.parse()?;
        let _: EndpointId = self.endpoint_id.parse()?;
        ensure!(
            self.issued_at <= now + 60 && now.saturating_sub(self.issued_at) <= 300,
            "Grant timestamp outside allowed window"
        );
        ensure!(
            self.expires_at <= self.issued_at.saturating_add(172800),
            "Grant lifetime exceeds two days"
        );
        ensure!(
            self.signature.len() == 128 && self.signature.is_ascii(),
            "Invalid signature encoding"
        );
        let mut signature = [0u8; 64];
        for (index, byte) in signature.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&self.signature[index * 2..index * 2 + 2], 16)?;
        }
        host.verify(
            self.message().as_bytes(),
            &Signature::from_bytes(&signature),
        )?;
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identity_expiry_and_signature_are_bound() {
        let root = SecretKey::generate();
        let peer = SecretKey::generate().public().to_string();
        let grant = Grant::sign(&root, &peer, 1000, 4600).unwrap();
        assert!(grant.verify(1001).is_ok());
        let mut forged = grant.clone();
        forged.endpoint_id = SecretKey::generate().public().to_string();
        assert!(forged.verify(1001).is_err());
        assert!(grant.verify(1401).is_err());
        let too_long = Grant::sign(&root, &peer, 1000, 173801).unwrap();
        assert!(too_long.verify(1001).is_err());
    }
}
