use std::time::Duration;

const BODY: &str = r#"<?xml version="1.0" encoding="utf-8"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>RHRemote</D:owner></D:lockinfo>"#;

pub(super) fn supported(agent: &ureq::Agent, address: &str, auth: &str) -> Result<bool, String> {
    let response = agent
        .request("OPTIONS", address)
        .set("Authorization", auth)
        .timeout(Duration::from_secs(5))
        .call();
    match response {
        Ok(response) if (200..300).contains(&response.status()) => Ok(response
            .all("DAV")
            .iter()
            .flat_map(|value| value.split(','))
            .any(|value| value.trim() == "2")),
        Ok(response) => Err(super::http_error(ureq::Error::Status(
            response.status(),
            response,
        ))),
        // Method discovery is optional for servers that use HTTP conditional writes.
        Err(ureq::Error::Status(403 | 404 | 405 | 501, _)) => Ok(false),
        Err(error) => Err(super::http_error(error)),
    }
}

pub(super) struct Lease {
    agent: ureq::Agent,
    address: String,
    auth: String,
    token: String,
    pub(super) created_resource: bool,
    released: bool,
}

impl Lease {
    pub(super) fn acquire(agent: &ureq::Agent, address: &str, auth: &str) -> Result<Self, String> {
        let response = agent
            .request("LOCK", address)
            .set("Authorization", auth)
            .set("Content-Type", "application/xml; charset=utf-8")
            .set("Depth", "0")
            .set("Timeout", "Second-60")
            .send_string(BODY)
            .map_err(super::http_error)?;
        if !matches!(response.status(), 200 | 201) {
            return Err(super::http_error(ureq::Error::Status(
                response.status(),
                response,
            )));
        }
        let token = response.header("Lock-Token").unwrap_or("").trim();
        if token.len() < 3
            || token.len() > 2048
            || !token.starts_with('<')
            || !token.ends_with('>')
            || token[1..token.len() - 1]
                .bytes()
                .any(|b| b <= 32 || b >= 127 || matches!(b, b'<' | b'>'))
        {
            return Err("WebDAV 未返回有效的同步保护凭据，请稍后重试".into());
        }
        Ok(Self {
            agent: agent.clone(),
            address: address.into(),
            auth: auth.into(),
            token: token.into(),
            created_resource: response.status() == 201,
            released: false,
        })
    }

    pub(super) fn condition(&self) -> String {
        format!("({})", self.token)
    }

    pub(super) fn release(&mut self) -> Result<(), String> {
        if self.released {
            return Ok(());
        }
        // A failed release is reported once; the finite lease also survives a crash.
        self.released = true;
        let response = self
            .agent
            .request("UNLOCK", &self.address)
            .set("Authorization", &self.auth)
            .set("Lock-Token", &self.token)
            .timeout(Duration::from_secs(3))
            .call()
            .map_err(|_| "WebDAV 同步保护未能释放，请稍后手动同步核对".to_owned())?;
        if !(200..300).contains(&response.status()) {
            return Err("WebDAV 同步保护未能释放，请稍后手动同步核对".into());
        }
        Ok(())
    }
}

impl Drop for Lease {
    fn drop(&mut self) {
        let _ = self.release();
    }
}
