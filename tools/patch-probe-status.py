"""Observe existing Windows serverinfo work without changing protocol behavior."""
import argparse
import re
from pathlib import Path


def patch(root):
    header = root / "app/backend/nvhttp.h"
    h = header.read_text(encoding="utf-8")
    if "m_RhProbeTlsFailure" not in h:
        anchor = "    bool m_RhLaunchCancelSupported = false;"
        if h.count(anchor) != 1:
            raise RuntimeError("Probe TLS observation header anchor missing")
        h = h.replace(anchor, anchor + "\n    bool m_RhProbeTlsFailure = false;\n    bool m_RhProbeCertificateMismatch = false;", 1)
    if "m_RhProbePinnedAuthRejected" not in h:
        anchor = "    bool m_RhProbeCertificateMismatch = false;"
        if h.count(anchor) != 1:
            raise RuntimeError("Pinned HTTPS rejection observation header anchor missing")
        h = h.replace(anchor, anchor + "\n    bool m_RhProbePinnedAuthRejected = false;", 1)
    header.write_text(h, encoding="utf-8", newline="\n")
    path = root / "app/backend/nvhttp.cpp"
    text = path.read_text(encoding="utf-8")
    if "RhProbe::ServerInfo rhProbe" in text:
        if "m_RhProbePinnedAuthRejected = false;" not in text:
            raise RuntimeError("Re-run prepare-windows before updating an older probe observer")
        return
    start = text.index("NvHTTP::getServerInfo(NvLogLevel logLevel, bool fastFail)")
    end = text.index("\n}\n", start) + 3
    segment = text[start:end]

    def one(before, after):
        nonlocal segment
        if segment.count(before) != 1:
            raise RuntimeError("Probe status anchor missing or ambiguous: " + before[:90])
        segment = segment.replace(before, after, 1)

    one("    m_RhLaunchCancelSupported = false;", "    RhProbe::ServerInfo rhProbe(m_Address.address(), !m_ServerCert.isNull());\n    m_RhProbeTlsFailure = false; m_RhProbeCertificateMismatch = false; m_RhProbePinnedAuthRejected = false;\n    m_RhLaunchCancelSupported = false;")
    one("            serverInfo = openConnectionToString(m_BaseUrlHttps,", "            rhProbe.https();\n            serverInfo = openConnectionToString(m_BaseUrlHttps,")
    one("                // Certificate validation error, fallback to HTTP", "                if(m_RhProbeTlsFailure)rhProbe.failedTls(m_RhProbeCertificateMismatch);else rhProbe.rejected();\n                // Certificate validation error, fallback to HTTP")
    segment, count = re.subn(r"(?m)^([ \t]*)serverInfo = openConnectionToString\(m_BaseUrlHttp,", r"\1rhProbe.http();\n\1serverInfo = openConnectionToString(m_BaseUrlHttp,", segment)
    if count != 2:
        raise RuntimeError("Expected both existing HTTP paths")
    one("            return getServerInfo(logLevel, fastFail);", "            rhProbe.delegated();\n            return getServerInfo(logLevel, fastFail);")
    # Observe Qt's HTTP-level 401 without changing its original exception or
    # adding a fallback request. XML-level 401 retains the upstream HTTP fallback.
    one("    }\n    else\n    {\n        // Only use HTTP", "        catch (const QtNetworkReplyException&)\n        {\n            if(m_RhProbePinnedAuthRejected)rhProbe.pinnedHttpsAuthRejected();\n            throw;\n        }\n    }\n    else\n    {\n        // Only use HTTP")
    one("    return serverInfo;", '    rhProbe.complete(getXmlString(serverInfo, "uniqueid"), getXmlString(serverInfo, "PairStatus") == "1");\n    return serverInfo;')
    text = '#include "gui/rhprobe.h"\n' + text[:start] + segment + text[end:]
    anchor = '    const auto rhPinMismatch = std::make_shared<bool>(false);'
    if text.count(anchor) != 1:
        raise RuntimeError("Verified pin observation anchor missing")
    text = text.replace(anchor, anchor + '\n    const auto rhPinVerified = std::make_shared<bool>(false);', 1)
    anchor = '[reply,pin,rhPinMismatch]{\n            if(reply->sslConfiguration().peerCertificate()!=pin){*rhPinMismatch=true;reply->abort();}'
    if text.count(anchor) != 1:
        raise RuntimeError("Encrypted peer pin observation anchor missing")
    text = text.replace(anchor, '[reply,pin,rhPinMismatch,rhPinVerified]{\n            if(reply->sslConfiguration().peerCertificate()!=pin){*rhPinMismatch=true;reply->abort();}\n            else {*rhPinVerified=true;}', 1)
    anchor = '    // Handle error\n    if (reply->error() != QNetworkReply::NoError)'
    if text.count(anchor) != 1:
        raise RuntimeError("HTTP status observation anchor missing")
    text = text.replace(anchor, '    if(command=="serverinfo"&&baseUrl.scheme()=="https"&&*rhPinVerified&&!m_RhProbeTlsFailure&&\n       reply->error()==QNetworkReply::AuthenticationRequiredError&&\n       reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt()==401)\n        m_RhProbePinnedAuthRejected=true;\n' + anchor, 1)
    pin = 'if(*rhPinMismatch){delete reply;throw GfeHttpResponseException(401,"Server certificate mismatch");}'
    if text.count(pin) != 1:
        raise RuntimeError("Probe pin rejection observation anchor missing")
    text = text.replace(pin, 'if(*rhPinMismatch){m_RhProbeTlsFailure=true;m_RhProbeCertificateMismatch=true;delete reply;throw GfeHttpResponseException(401,"Server certificate mismatch");}', 1)
    ssl = '        if (reply->error() == QNetworkReply::SslHandshakeFailedError) {'
    if text.count(ssl) != 1:
        raise RuntimeError("Probe TLS failure observation anchor missing")
    text = text.replace(ssl, ssl + '\n            m_RhProbeTlsFailure=true;\n            const auto peer=reply->sslConfiguration().peerCertificate();\n            m_RhProbeCertificateMismatch=!peer.isNull()&&peer!=m_ServerCert;', 1)
    path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    patch(parser.parse_args().source)
