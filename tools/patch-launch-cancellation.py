"""Wire fresh native launch ownership and drain cancellation before transport teardown."""
import argparse
from pathlib import Path


def replace(text, before, after):
    if after in text:
        return text
    if text.count(before) != 1:
        raise RuntimeError("Launch ownership anchor missing or ambiguous: " + before[:85])
    return text.replace(before, after, 1)


def patch(root):
    path = root / "app/backend/nvhttp.h"
    text = path.read_text(encoding="utf-8")
    text = replace(text, "    QUrl m_BaseUrlHttp;", "    bool rhLaunchCancelSupported() const { return m_RhLaunchCancelSupported; }\n\n    QUrl m_BaseUrlHttp;")
    text = replace(text, "    QSslCertificate m_ServerCert;", "    QSslCertificate m_ServerCert;\n    bool m_RhLaunchCancelSupported = false;")
    path.write_text(text, encoding="utf-8", newline="\n")

    path = root / "app/backend/nvhttp.cpp"
    text = path.read_text(encoding="utf-8")
    text = replace(text, '    NvHTTP(computer->activeAddress, computer->activeHttpsPort, computer->serverCert)\n{\n\n}',
                   '    NvHTTP(computer->activeAddress, computer->activeHttpsPort, computer->serverCert)\n{\n    m_RhLaunchCancelSupported = computer->rhLaunchCancelSupported;\n}')
    text = replace(text, 'NvHTTP::getServerInfo(NvLogLevel logLevel, bool fastFail)\n{\n    QString serverInfo;',
                   'NvHTTP::getServerInfo(NvLogLevel logLevel, bool fastFail)\n{\n    m_RhLaunchCancelSupported = false;\n    QString serverInfo;')
    text = replace(text, '            // Throws if the request failed\n            verifyResponseStatus(serverInfo);',
                   '            // Throws if the request failed\n            verifyResponseStatus(serverInfo);\n            m_RhLaunchCancelSupported = getXmlString(serverInfo, "RHLaunchCancel") == "1";')
    before = '    RhStatistics::Scope traceLaunch("host_launch_request");'
    after = '''    RhStatistics::Scope traceLaunch("host_launch_request");
    const auto cancelAddress = address();
    const auto cancelPort = httpsPort();
    const auto cancelCert = serverCert();
    const auto rhLaunch = std::make_shared<RhLaunchAttempt>(m_RhLaunchCancelSupported,
        [cancelAddress,cancelPort,cancelCert](const QString& credential){
            NvHTTP cleanup(cancelAddress,cancelPort,cancelCert);
            const auto reply = cleanup.openConnectionToString(cleanup.m_BaseUrlHttps,
                "rhremote/launch/cancel", "rhremoteLaunchToken="+credential, 3000, NvHTTP::NVLL_NONE);
            NvHTTP::verifyResponseStatus(reply);
            return RhLaunchAttempt::reply(NvHTTP::getXmlString(reply,"RHLaunchState"));
        });
    if(!RhRuntime::installLaunch(rhLaunch)){rhLaunch->cancel();throw GfeHttpResponseException(410,"Launch admission closed");}
    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load()){
        rhLaunch->cancel();throw GfeHttpResponseException(410,"Launch cancelled");
    }'''
    text = replace(text, before, after)
    text = replace(text, 'rhFrameTraceQuery+RhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters(),',
                   'rhLaunch->query()+rhFrameTraceQuery+RhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters(),')
    text = replace(text, '    connect(QCoreApplication::instance(), &QCoreApplication::aboutToQuit, &loop, &QEventLoop::quit);',
                   '    if(command!="rhremote/launch/cancel")\n        connect(QCoreApplication::instance(), &QCoreApplication::aboutToQuit, &loop, &QEventLoop::quit);')
    text = replace(text, 'qInfo() << "Executing request:" << url.toString();',
                   'qInfo() << "Executing request:" << RhLaunchAttempt::redact(url.toString());')
    text = replace(text, 'qWarning() << "Aborting timed out request for" << url.toString();',
                   'qWarning() << "Aborting timed out request for" << RhLaunchAttempt::redact(url.toString());')
    text = replace(text, 'QtNetworkReplyException exception(reply->error(), reply->errorString());',
                   'QtNetworkReplyException exception(reply->error(), RhLaunchAttempt::redact(reply->errorString()));')
    text = replace(text, '    QNetworkReply* reply = m_Nam.get(request);', '''    QNetworkReply* reply = m_Nam.get(request);
    const auto rhPinMismatch = std::make_shared<bool>(false);
    if(baseUrl.scheme()=="https"&&!m_ServerCert.isNull()){
        const auto pin=m_ServerCert;
        QObject::connect(reply,&QNetworkReply::encrypted,reply,[reply,pin,rhPinMismatch]{
            if(reply->sslConfiguration().peerCertificate()!=pin){*rhPinMismatch=true;reply->abort();}
        });
    }''')
    text = replace(text, '    // Handle error\n    if (reply->error() != QNetworkReply::NoError)', '''    // A CA-valid peer must not replace this device's paired certificate.
    if(*rhPinMismatch){delete reply;throw GfeHttpResponseException(401,"Server certificate mismatch");}
    // Handle error
    if (reply->error() != QNetworkReply::NoError)''')
    path.write_text(text, encoding="utf-8", newline="\n")

    path = root / "app/backend/nvcomputer.h"
    text = path.read_text(encoding="utf-8")
    text = replace(text, '    // Ephemeral traits\n', '    // Ephemeral traits\n    bool rhLaunchCancelSupported = false;\n')
    path.write_text(text, encoding="utf-8", newline="\n")
    path = root / "app/backend/nvcomputer.cpp"
    text = path.read_text(encoding="utf-8")
    text = replace(text, '    this->serverCert = http.serverCert();',
                   '    this->serverCert = http.serverCert();\n    this->rhLaunchCancelSupported = http.rhLaunchCancelSupported();')
    text = replace(text, '    ASSIGN_IF_CHANGED(activeHttpsPort);',
                   '    ASSIGN_IF_CHANGED(activeHttpsPort);\n    ASSIGN_IF_CHANGED(rhLaunchCancelSupported);')
    path.write_text(text, encoding="utf-8", newline="\n")

    path = root / "app/streaming/session.cpp"
    text = path.read_text(encoding="utf-8")
    text = replace(text, '        LiStopConnection();',
                   '        LiStopConnection();\n        RhRuntime::get().protocolStarted=false;\n        RhRuntime::drainLaunch();')
    text = replace(text, '    RhStatistics::event("connection_begin");',
                   '    RhRuntime::get().protocolStarted=true;\n    RhStatistics::event("connection_begin");')
    text = replace(text, 'DispatchDeferredCleanup:\n',
                   'DispatchDeferredCleanup:\n    RhRuntime::cancelLaunch();\n')
    text = replace(text, 'r.firstFrame=false;r.localDisconnect=false;r.retryEligible=false;r.routeRetryEligible=false;',
                   'r.firstFrame=false;r.retryEligible=false;r.routeRetryEligible=false; // Never overwrite an in-flight cancellation.')
    path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    patch(parser.parse_args().source)
