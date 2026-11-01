use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckOutcome {
    Paired,
    Unpaired,
    AuthRejected,
    CertificateMismatch,
    TlsFailure,
    IdentityMismatch,
    InvalidResponse,
    Unreachable,
    Timeout,
    Unavailable,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CheckSource {
    Http,
    Https,
    None,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckPhase {
    RouteSelection,
    ServerInfo,
    NativeProcess,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeviceCheck {
    pub phase: CheckPhase,
    pub outcome: CheckOutcome,
    pub source: CheckSource,
    pub has_certificate: bool,
    pub elapsed_us: u64,
}
impl DeviceCheck {
    pub fn repair_required(&self) -> bool {
        matches!(
            self.outcome,
            CheckOutcome::AuthRejected | CheckOutcome::CertificateMismatch
        ) || (self.outcome == CheckOutcome::Unpaired && self.has_certificate)
    }
    pub fn blocks_connection(&self) -> bool {
        matches!(
            self.outcome,
            CheckOutcome::IdentityMismatch | CheckOutcome::InvalidResponse
        )
    }
    pub fn label(&self) -> &'static str {
        match self.outcome {
            CheckOutcome::Paired => "已配对",
            CheckOutcome::Unpaired if self.has_certificate => "需要重新配对",
            CheckOutcome::Unpaired => "未配对",
            CheckOutcome::AuthRejected => "认证失败",
            CheckOutcome::CertificateMismatch => "证书不符",
            CheckOutcome::TlsFailure => "安全连接失败",
            CheckOutcome::IdentityMismatch => "设备标识不符",
            CheckOutcome::InvalidResponse => "设备信息无效",
            CheckOutcome::Unreachable => "暂时无法连接",
            CheckOutcome::Timeout => "检查超时",
            CheckOutcome::Unavailable => "状态未确认",
        }
    }
    pub fn hint(&self) -> &'static str {
        match self.outcome {
            CheckOutcome::AuthRejected => "主机未接受本机保存的配对，请重新配对。",
            CheckOutcome::CertificateMismatch => {
                "主机证书与本机保存的证书不符，请确认目标设备后重新配对。"
            }
            CheckOutcome::TlsFailure => {
                "安全连接未完成，已保存的配对仍保留，请检查主机证书和连接设置。"
            }
            CheckOutcome::IdentityMismatch => "返回的设备标识与已保存记录不符，请检查连接地址。",
            CheckOutcome::InvalidResponse => "主机返回的设备信息无效，请检查连接地址。",
            CheckOutcome::Unreachable => "当前启用的线路无法到达这台设备，请检查网络或连接设置。",
            CheckOutcome::Timeout if self.has_certificate => {
                "在线检查超时，已保存的配对仍保留，可重试连接。"
            }
            CheckOutcome::Timeout => "在线检查超时，请重试或检查网络。",
            CheckOutcome::Unavailable if self.has_certificate => {
                "本机已保存配对，但在线检查未完成，可重试连接。"
            }
            CheckOutcome::Unavailable => "本机未能完成设备检查，请重试或查看网络诊断。",
            CheckOutcome::Unpaired if self.has_certificate => {
                "主机报告尚未配对，本机保存的身份文件仍保留。"
            }
            _ => "",
        }
    }
}

impl crate::State {
    /// Route or identity changes invalidate both current and retained evidence.
    pub(crate) fn clear_device_checks(&mut self, uuid: &str) {
        self.device_checks.remove(uuid);
        self.device_trust_checks.remove(uuid);
    }

    /// A transient network result cannot establish that a rejected identity is
    /// trusted again. Keep its current cause without overwriting that fact.
    pub(crate) fn update_device_check(&mut self, uuid: &str, verified: bool, check: Option<DeviceCheck>) {
        if verified {
            self.device_trust_checks.remove(uuid);
        }
        if let Some(check) = check {
            if check.repair_required() || check.blocks_connection() {
                self.device_trust_checks.insert(uuid.into(), check.clone());
            }
            self.device_checks.insert(uuid.into(), check);
        } else if verified {
            self.device_checks.remove(uuid);
        }
    }
}
