param([string]$SourceDir='D:\RHRemoteTools\moonlight-qt-v6.1-source')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if((& git -C $SourceDir rev-parse HEAD).Trim() -ne 'f786e94c7b2f943e24e65d7d74deb539b827fc84'){throw 'Unexpected Windows upstream revision.'}
$backup=Join-Path $SourceDir '.rhremote-ui-upstream-v6.1'
function Edit-Source([string]$Relative,[scriptblock]$Edit){
    $target=Join-Path $SourceDir $Relative;$original=Join-Path $backup $Relative
    if(-not(Test-Path -LiteralPath $original)){New-Item -ItemType Directory -Force -Path (Split-Path $original -Parent)|Out-Null;Copy-Item -LiteralPath $target -Destination $original}
    $updated=& $Edit (Get-Content -LiteralPath $original -Raw)
    [IO.File]::WriteAllText($target,$updated,(New-Object Text.UTF8Encoding $false))
}
function Replace-Once([string]$Text,[string]$Before,[string]$After){if(-not $Text.Contains($Before)){$Text=$Text.Replace("`r`n","`n");$Before=$Before.Replace("`r`n","`n");$After=$After.Replace("`r`n","`n")};if(-not $Text.Contains($Before)){throw ('Upstream patch anchor missing: '+$Before.Substring(0,[Math]::Min(70,$Before.Length)))};return $Text.Replace($Before,$After)}
Edit-Source 'app/main.cpp' {param($s)
    $s=$s.Replace('QCoreApplication::setOrganizationName("Moonlight Game Streaming Project");','QCoreApplication::setOrganizationName("RHRemote");')
    $s=$s.Replace('QCoreApplication::setOrganizationDomain("moonlight-stream.com");','QCoreApplication::setOrganizationDomain("rhremote.local");')
    $s=$s.Replace('QCoreApplication::setApplicationName("Moonlight");','QCoreApplication::setApplicationName("RHRemote");')
    $s="#include `"gui/rhprofiles.h`"`n#include `"gui/rhcontrol.h`"`n#include `"gui/rhcapturetools.h`"`n#include <QIcon>`n"+$s
    $s=[regex]::Replace($s,'int main\(int argc, char \*argv\[\]\)\r?\n\{',"int main(int argc, char *argv[])`n{`n    RhCaptureTools::isolate();")
    $s=Replace-Once $s '    QQuickStyle::setStyle("Material");' @'
    qmlRegisterSingletonType<RhProfiles>("RhProfiles", 1, 0, "RhProfiles",
        [](QQmlEngine*, QJSEngine*) -> QObject* { return new RhProfiles(); });
    qmlRegisterType<RhHosts>("RhProfiles", 1, 0, "RhHosts");
    new RhControl(&app);
    QQuickStyle::setStyle("Material");
    QGuiApplication::setWindowIcon(QIcon(":/res/rhremote.svg"));
'@
    $s=Replace-Once $s '            auto launcher = new CliPair::Launcher(pairParser.getHost(), pairParser.getPredefinedPin(), &app);' @'
            auto launcher = new CliPair::Launcher(pairParser.getHost(), pairParser.getPredefinedPin(), &app);
            if(qEnvironmentVariableIsSet("RHREMOTE_PAIR_OPERATION")) {
                QObject::connect(launcher,&CliPair::Launcher::failed,&app,[&app](const QString& error){RhRuntime::status("pair_failed",error);app.exit(1);});
                QObject::connect(launcher,&CliPair::Launcher::success,&app,[&app](){RhRuntime::status("paired",QString());app.quit();});
                launcher->execute(new ComputerManager(StreamingPreferences::get()));
                hasGUI=false;
                break;
            }
'@
    $s=Replace-Once $s '        engine.load(QUrl(QStringLiteral("qrc:/gui/main.qml")));' @'
        const bool rhAdapter=commandLineParserResult==GlobalCommandLineParser::StreamRequested && !qEnvironmentVariableIsEmpty("RHREMOTE_TARGET_UUID");
        engine.load(QUrl(rhAdapter ? QStringLiteral("qrc:/gui/RhStreamAdapter.qml") : QStringLiteral("qrc:/gui/main.qml")));
'@
    return $s
}
Edit-Source 'app/gui/computermodel.h' {param($s)
    $s=$s.Replace('        DetailsRole','        DetailsRole,
        UuidRole,
        AddressRole')
    $s=$s.Replace('    explicit ComputerModel(QObject* object = nullptr);',"    explicit ComputerModel(QObject* object = nullptr);`n    Q_INVOKABLE QVariantMap get(int row) const;")
    return "#include <QVariantMap>`n"+$s
}
Edit-Source 'app/backend/nvcomputer.h' {param($s)
    return (Replace-Once $s '    // Ephemeral traits' "    // RHRemote: authenticated app list fetched during this engine process.`n    bool rhAppListFresh = false;`n    // Ephemeral traits")
}
Edit-Source 'app/cli/commandlineparser.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n"+$s
    return (Replace-Once $s '        showMessage(message + "\n\n" + helpText(), Error);' @'
        if(qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")){RhRuntime::status("error",message);exit(1);}
        showMessage(message + "\n\n" + helpText(), Error);
'@)
}
Edit-Source 'app/backend/computermanager.cpp' {param($s)
    $s=$s.Replace("`r`n","`n")
    $s=Replace-Once $s '                for (auto& address : m_Computer->uniqueAddresses()) {' @'
                auto rhAddresses=m_Computer->uniqueAddresses();
                const auto rhSelected=qEnvironmentVariable("RHREMOTE_CONNECT_ADDRESS");
                if(!rhSelected.isEmpty()&&m_Computer->uuid.compare(qEnvironmentVariable("RHREMOTE_TARGET_UUID"),Qt::CaseInsensitive)==0)rhAddresses={NvAddress(rhSelected,47989)};
                for (auto& address : rhAddresses) {
'@
    $s=Replace-Once $s '        changed = m_Computer->updateAppList(appList);' @'
        changed = m_Computer->updateAppList(appList);
        if(qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")){
            m_Computer->rhAppListFresh=true;
            changed=true; // Notify even when the freshly fetched list equals the disk cache.
        }
'@
    $s=Replace-Once $s '    m_CompatFetcher.start();' '    if(!qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID"))m_CompatFetcher.start();'
    $s=Replace-Once $s '    if (m_Prefs->enableMdns) {' '    if (m_Prefs->enableMdns && !qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")) {'
    return (Replace-Once $s 'void ComputerManager::startPollingComputer(NvComputer* computer)
{' @'
void ComputerManager::startPollingComputer(NvComputer* computer)
{
    const auto target=qEnvironmentVariable("RHREMOTE_TARGET_UUID");
    if(!target.isEmpty() && computer->uuid.compare(target,Qt::CaseInsensitive)!=0)return;
    const auto address=qEnvironmentVariable("RHREMOTE_CONNECT_ADDRESS");
    if(!target.isEmpty()&&!address.isEmpty()) {computer->manualAddress=NvAddress(address,47989);computer->activeAddress=computer->manualAddress;}
'@)
}
Edit-Source 'app/backend/computerseeker.cpp' {param($s)
    $s=Replace-Once $s '    m_ComputerManager->addNewHostManually(m_ComputerName);' @'
    // The shell passes a known UUID and address. Its normal monitor performs
    // fresh certificate/UUID checks and fetches the app list. Avoid a parallel
    // add-host task doing the same HTTP and HTTPS probes again.
    bool knownTarget=false;
    const auto target=qEnvironmentVariable("RHREMOTE_TARGET_UUID");
    if(!target.isEmpty()){
        for(auto computer:m_ComputerManager->getComputers()){
            if(computer->uuid.compare(target,Qt::CaseInsensitive)==0&&matchComputer(computer)){
                knownTarget=true;break;
            }
        }
    }
    if(!knownTarget)m_ComputerManager->addNewHostManually(m_ComputerName);
'@
    return $s
}
Edit-Source 'app/cli/startstream.cpp' {param($s)
    $s=$s.Replace("`r`n","`n")
    $s="#include `"gui/rhstatistics.h`"`n"+$s
    $s=Replace-Once $s '                    emit q->searchingApp();' @'
                    RhStatistics::event("client_host_found");
                    emit q->searchingApp();
                    if(qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")){
                        QTimer::singleShot(0,q,[this]{Event ready(Event::ComputerUpdated);ready.computer=m_Computer;handleEvent(ready);});
                    }
'@
    $s=Replace-Once $s '                           q, &Launcher::onComputerUpdated);' '                           q, &Launcher::onComputerUpdated, qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID") ? Qt::QueuedConnection : Qt::AutoConnection);'
    return (Replace-Once $s '        case Event::ComputerUpdated:
            if (m_State == StateSeekApp) {' @'
        case Event::ComputerUpdated:
            if (m_State == StateSeekApp && event.computer==m_Computer) {
                if(qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")){
                    QReadLocker lock(&m_Computer->lock);if(!m_Computer->rhAppListFresh)break;
                }
                RhStatistics::event("client_apps_fresh");
'@)
}
Edit-Source 'app/gui/computermodel.cpp' {param($s)
    $s=Replace-Once $s '    m_Computers = m_ComputerManager->getComputers();' "    beginResetModel();`n    m_Computers = m_ComputerManager->getComputers();`n    endResetModel();"
    $s=$s.Replace('    case DetailsRole:',"    case UuidRole: return computer->uuid;`n    case AddressRole: return computer->activeAddress.toString();`n    case DetailsRole:")
    $s=$s.Replace('    names[DetailsRole] = "details";',"    names[DetailsRole] = `"details`";`n    names[UuidRole] = `"uuid`";`n    names[AddressRole] = `"address`";")
    $s=Replace-Once $s 'int ComputerModel::rowCount' @'
QVariantMap ComputerModel::get(int row) const {
    QVariantMap result;
    if(row<0 || row>=m_Computers.count())return result;
    const auto names=roleNames();
    for(auto it=names.begin();it!=names.end();++it)result[QString::fromUtf8(it.value())]=data(createIndex(row,0),it.key());
    return result;
}
int ComputerModel::rowCount
'@
    return $s
}
Edit-Source 'app/gui/appmodel.h' {param($s)
    $s=$s.Replace('    explicit AppModel(QObject *parent = nullptr);',"    explicit AppModel(QObject *parent = nullptr);`n    Q_INVOKABLE QVariantMap get(int row) const;")
    return "#include <QVariantMap>`n"+$s
}
Edit-Source 'app/gui/appmodel.cpp' {param($s)
    return (Replace-Once $s 'int AppModel::rowCount' @'
QVariantMap AppModel::get(int row) const {
    QVariantMap result;
    if(row<0 || row>=m_VisibleApps.count())return result;
    const auto names=roleNames();
    for(auto it=names.begin();it!=names.end();++it)result[QString::fromUtf8(it.value())]=data(createIndex(row,0),it.key());
    return result;
}
int AppModel::rowCount
'@)
}
Edit-Source 'app/qml.qrc' {param($s)return $s.Replace('        <file>gui/main.qml</file>',"        <file>gui/main.qml</file>`n        <file>gui/RhButton.qml</file>`n        <file>gui/RhStreamAdapter.qml</file>")}
Edit-Source 'app/resources.qrc' {param($s)return $s.Replace('</qresource>',"    <file>res/rhremote.svg</file>`n    <file>res/rhremote-shortcuts.json</file>`n    </qresource>")}
Edit-Source 'app/gui/CliPair.qml' {param($s)
    $s="import RhProfiles 1.0`n"+$s
    $s=Replace-Once $s '    function onFailed(message) {' @'
    function onFailed(message) {
        if(RhProfiles.pairingControlled()) {
            RhProfiles.connectionStatus("pair_failed",message)
            Qt.quit()
            return
        }
'@
    return $s.Replace('        pairCompleteDialog.open()',"        if(RhProfiles.pairingControlled())RhProfiles.connectionStatus(`"paired`",`"`")`n        console.log(`"RHREMOTE_PAIR_SUCCESS`")`n        Qt.quit()")
}
Edit-Source 'app/gui/CliStartStreamSegue.qml' {param($s)
    $s="import RhProfiles 1.0`n"+$s
    $s=Replace-Once $s '    function onSearchingComputer() {' "    function onSearchingComputer() {`n        RhProfiles.connectionStatus(`"connecting`",`"`")"
    $s=Replace-Once $s '    function onSearchingApp() {' "    function onSearchingApp() {`n        RhProfiles.connectionStatus(`"starting`",`"`")"
    return (Replace-Once $s '    function onLaunchFailed(message) {' @'
    function onLaunchFailed(message) {
        RhProfiles.connectionStatus("error",message)
        if(RhProfiles.apiControlled()){Qt.quit();return;}
'@)
}
Edit-Source 'app/cli/listapps.cpp' {param($s)
    $s="#include `"gui/rhhost.h`"`n#include <QFile>`n#include <QSaveFile>`n"+$s
    return (Replace-Once $s '                if (event.computer->pairState == NvComputer::PS_PAIRED) {' @'
                if (event.computer->pairState == NvComputer::PS_PAIRED) {
                    if(qEnvironmentVariableIsSet("RHREMOTE_PROBE_OPERATION")){QCoreApplication::exit(0);return;}
                    const auto requestPath=qEnvironmentVariable("RHREMOTE_HOST_REQUEST");
                    if(!requestPath.isEmpty()) {
                        QFile file(requestPath);QJsonObject result{{"ok",false},{"error","invalid host request"}};
                        if(file.open(QIODevice::ReadOnly)&&file.size()<=4096){const auto request=QJsonDocument::fromJson(file.readAll()).object();const auto path=request["path"].toString();if(path=="/v1/network/diagnose")result=RhHost::diagnose(event.computer->activeAddress.address(),event.computer->serverCert);else if(path=="/v1/displays"||path=="/v1/display/prepare"||path=="/v1/network")result=RhHost::request(event.computer->activeAddress.address(),event.computer->serverCert,path,request["body"].toObject());}
                        QSaveFile output(qEnvironmentVariable("RHREMOTE_HOST_RESPONSE"));if(output.open(QIODevice::WriteOnly)){output.write(QJsonDocument(result).toJson(QJsonDocument::Compact));output.commit();}
                        QCoreApplication::exit(result["ok"].toBool()?0:1);return;
                    }
'@)
}
# Only extend the bounded initial-frame grace period; steady-state pacing is unchanged.
Edit-Source 'app/gui/StreamSegue.qml' {param($s)
    $s="import RhProfiles 1.0`n"+$s
    $s=(Replace-Once $s '    function displayLaunchError(text)' '    function displayLaunchError(text)').Replace('        streamSegueErrorDialog.text = text', '        RhProfiles.connectionStatus("error",text)
        streamSegueErrorDialog.text = text')
    $s=$s.Replace("    function sessionFinished(portTestResult)`r`n    {","    function sessionFinished(portTestResult)`r`n    {`r`n        if(RhProfiles.apiControlled())return;")
    $s=$s.Replace("    function sessionFinished(portTestResult)`n    {","    function sessionFinished(portTestResult)`n    {`n        if(RhProfiles.apiControlled())return;")
    $s=$s.Replace("    function sessionReadyForDeletion()`r`n    {","    function sessionReadyForDeletion()`r`n    {`r`n        if(RhProfiles.apiControlled()){RhProfiles.connectionStatus(`"cleanup_complete`",`"`");Qt.quit();return;}")
    return $s.Replace("    function sessionReadyForDeletion()`n    {","    function sessionReadyForDeletion()`n    {`n        if(RhProfiles.apiControlled()){RhProfiles.connectionStatus(`"cleanup_complete`",`"`");Qt.quit();return;}")
}
Edit-Source 'moonlight-common-c/moonlight-common-c/src/VideoStream.c' {param($s)
    return (Replace-Once $s '#define FIRST_FRAME_TIMEOUT_SEC 10' '#define FIRST_FRAME_TIMEOUT_SEC 30')
}
Edit-Source 'moonlight-common-c/moonlight-common-c/src/RtspConnection.c' {param($s)
    $s=$s.Replace("`r`n","`n")
    return (Replace-Once $s @'
    if (useEnet) {
        return transactRtspMessageEnet(request, response, expectingPayload, error);
    }
    else {
        return transactRtspMessageTcp(request, response, error);
    }
'@ @'
    LARGE_INTEGER rhStarted,rhEnded,rhFrequency;
    QueryPerformanceFrequency(&rhFrequency);QueryPerformanceCounter(&rhStarted);
    const bool rhResult=useEnet ? transactRtspMessageEnet(request,response,expectingPayload,error) : transactRtspMessageTcp(request,response,error);
    // Timing metadata only: never log targets, headers, keys or payloads here.
    QueryPerformanceCounter(&rhEnded);
    Limelog("RHREMOTE_RTSP %s %llu\n",request->message.request.command,(unsigned long long)((rhEnded.QuadPart-rhStarted.QuadPart)*1000000/rhFrequency.QuadPart));
    return rhResult;
'@)
}
Edit-Source 'app/streaming/session.h' {param($s)
    $s="#include <future>`n#include <tuple>`n#include <QJsonObject>`n#include `"gui/rhprobecache.h`"`n#include `"gui/rhwindowbridge.h`"`n"+$s
    $s=[regex]::Replace($s,'    static\r?\n    DecoderAvailability getDecoderAvailability','    DecoderAvailability getDecoderAvailability')
    return (Replace-Once $s '    void execInternal();' "    std::future<QJsonObject> m_RhDisplayPreparation;`n    using RhProbeKey=std::tuple<SDL_Window*,int,int,int,int,int>;`n    RhProbeCache<RhProbeKey,IVideoDecoder> m_RhProbeCache;`n    RhWindowBridge m_RhWindowBridge;`n    int m_RhAudioProbeConfig=-1;`n    bool m_RhAudioProbeOk=false;`n    void execInternal();")
}
Edit-Source 'app/streaming/session.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n#include `"gui/rhpreparation.h`"`n"+$s
    $s=Replace-Once $s '        m_StreamConfig.streamingRemotely = STREAM_CFG_LOCAL;' @'
        m_StreamConfig.streamingRemotely = STREAM_CFG_LOCAL;
        if(qEnvironmentVariableIsSet("RHREMOTE_TARGET_UUID")) {
            const auto mode=qEnvironmentVariable("RHREMOTE_NETWORK_MODE");
            m_StreamConfig.streamingRemotely=(mode=="tailscale"||mode=="internet")?STREAM_CFG_REMOTE:mode=="lan"?STREAM_CFG_LOCAL:STREAM_CFG_AUTO;
        }
'@
    $s=[regex]::Replace($s,'bool Session::initialize\(\)\r?\n\{',"bool Session::initialize()`n{`n    if(qEnvironmentVariable(`"RHREMOTE_PACKET_AUTO`")==`"1`")m_Preferences->packetSize=0;`n    m_RhDisplayPreparation=RhPreparation::start(m_Computer->activeAddress.address(),m_Computer->serverCert,m_Preferences->width,m_Preferences->height);")
    $s=Replace-Once $s '    if (!chooseDecoder(vds, window, videoFormat, width, height, frameRate, false, false, true, decoder)) {' @'
    RhStatistics::Scope traceProbe("client_decoder_probe");
    const auto key=std::make_tuple(window,int(vds),videoFormat,width,height,frameRate);
    if(auto cached=m_RhProbeCache.get(key))return cached->isHardwareAccelerated()?DecoderAvailability::Hardware:DecoderAvailability::Software;
    m_RhProbeCache.clear();
    if (!chooseDecoder(vds, window, videoFormat, width, height, frameRate, false, false, true, decoder)) {
'@
    $s=[regex]::Replace($s,'    bool hw = decoder->isHardwareAccelerated\(\);\r?\n\r?\n    delete decoder;',"    bool hw = decoder->isHardwareAccelerated();`n    m_RhProbeCache.put(key,std::unique_ptr<IVideoDecoder>(decoder));")
    $s=[regex]::Replace($s,'bool Session::populateDecoderProperties\(SDL_Window\* window\)\r?\n\{\r?\n    IVideoDecoder\* decoder;',@'
bool Session::populateDecoderProperties(SDL_Window* window)
{
    RhStatistics::Scope traceProperties("client_decoder_properties");
    const auto key=std::make_tuple(window,int(m_Preferences->videoDecoderSelection),m_SupportedVideoFormats.first(),m_StreamConfig.width,m_StreamConfig.height,m_StreamConfig.fps);
    IVideoDecoder* decoder=m_RhProbeCache.take(key).release();
    m_RhProbeCache.clear();
'@)
    $s=[regex]::Replace($s,'(?m)^    if \(!chooseDecoder\(m_Preferences->videoDecoderSelection,','    if (!decoder && !chooseDecoder(m_Preferences->videoDecoderSelection,')
    $s=Replace-Once $s '    SDL_DestroyWindow(testWindow);' "    m_RhProbeCache.clear();`n    if(ret)m_RhWindowBridge.retain(testWindow);`n    SDL_DestroyWindow(testWindow);"
    # A real window now owns its own loader reference. Release before every
    # video shutdown as well, including connection and window creation failures.
    $s=Replace-Once $s '    m_InputHandler->setWindow(m_Window);' "    m_RhWindowBridge.release();`n    m_InputHandler->setWindow(m_Window);"
    $s=Replace-Once $s 'SDL_QuitSubSystem(SDL_INIT_VIDEO);' "m_RhWindowBridge.release();`n        SDL_QuitSubSystem(SDL_INIT_VIDEO);"
    # Check controller mappings after the real input handler has initialized SDL,
    # instead of opening and closing the same device subsystem twice.
    $s=Replace-Once $s '    if (!SdlInputHandler::getUnmappedGamepads().isEmpty()) {' '    if (qEnvironmentVariableIsEmpty("RHREMOTE_CONTROL_PORT") && !SdlInputHandler::getUnmappedGamepads().isEmpty()) {'
    $s=Replace-Once $s '    m_InputHandler = new SdlInputHandler(*m_Preferences, m_StreamConfig.width, m_StreamConfig.height);' @'
    m_InputHandler = new SdlInputHandler(*m_Preferences, m_StreamConfig.width, m_StreamConfig.height);
    if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT") && qEnvironmentVariable("RHREMOTE_GAMEPAD_ENABLED")!="0" && !SdlInputHandler::getUnmappedGamepads().isEmpty())
        emitLaunchWarning(tr("An attached gamepad has no mapping and won't be usable. Visit the Moonlight help to resolve this."));
'@
    $s=Replace-Once $s '    emit displayLaunchWarning(text);' @'
    emit displayLaunchWarning(text);
    if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")){
        SDL_LogWarn(SDL_LOG_CATEGORY_APPLICATION,"Launch warning: %s",qPrintable(text));
        RhStatistics::event("launch_warning",-1,{{"message",text}});
        return;
    }
'@
    # Desktop absolute input is intentional. The upstream warning adds 3.5 s.
    $s=Replace-Once $s 'if (m_Preferences->absoluteMouseMode && !m_App.isAppCollectorGame)' 'if (m_Preferences->absoluteMouseMode && !m_App.isAppCollectorGame && m_App.name.compare("Desktop",Qt::CaseInsensitive)!=0)'
    # The RHRemote shell already owns progress/status; no fixed toast-reading delay.
    $s=Replace-Once $s '    SDL_Delay(1500);' '    if(qEnvironmentVariableIsEmpty("RHREMOTE_CONTROL_PORT"))SDL_Delay(1500);'
    $s=Replace-Once $s 'void Session::clStageFailed(int stage, int errorCode)' 'void Session::clStageFailed(int stage, int errorCode)'
    $s=$s.Replace("void Session::clStageFailed(int stage, int errorCode)`r`n{","void Session::clStageFailed(int stage, int errorCode)`r`n{`r`n    RhRuntime::status(`"error`",QString(`"%1 (%2)`").arg(QString::fromLocal8Bit(LiGetStageName(stage))).arg(errorCode));")
    $s=$s.Replace("void Session::clStageFailed(int stage, int errorCode)`n{","void Session::clStageFailed(int stage, int errorCode)`n{`n    RhRuntime::status(`"error`",QString(`"%1 (%2)`").arg(QString::fromLocal8Bit(LiGetStageName(stage))).arg(errorCode));")
    $s=$s.Replace('void Session::clConnectionTerminated(int errorCode)', 'void Session::clConnectionTerminated(int errorCode)')
    $s=$s.Replace("void Session::clConnectionTerminated(int errorCode)`r`n{","void Session::clConnectionTerminated(int errorCode)`r`n{`r`n    RhRuntime::terminated(errorCode);")
    $s=$s.Replace("void Session::clConnectionTerminated(int errorCode)`n{","void Session::clConnectionTerminated(int errorCode)`n{`n    RhRuntime::terminated(errorCode);")
    $s=$s.Replace('LiTestClientConnectivity(CONN_TEST_SERVER, 443, portFlags)','(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT") ? 0 : LiTestClientConnectivity(CONN_TEST_SERVER, 443, portFlags))')
    $s=Replace-Once $s 'QString(m_Computer->name + " - Moonlight")' 'QString(m_Computer->name + " - RHRemote-Moonlight")'
    $s=Replace-Once $s 'bool Session::startConnectionAsync()' 'bool Session::startConnectionAsync()'
    $s=Replace-Once $s '    QString rtspSessionUrl;' @'
    const auto displayMode=qEnvironmentVariable("RHREMOTE_DISPLAY_MODE");
    if(!displayMode.isEmpty()){
        auto reply=m_RhDisplayPreparation.valid()
            ? m_RhDisplayPreparation.get()
            : QJsonObject{{"ok",false},{"error","Display preparation did not start"}};
        {auto& r=RhRuntime::get();QMutexLocker lock(&r.mutex);r.initialDisplay=0;r.firstFrame=false;r.localDisconnect=false;r.retryEligible=false;
            if(reply["ok"].toBool()){auto data=reply["result"].toObject();r.displays=data["displays"].toArray();r.selectedDisplay=data["selected"].toObject();int index=r.selectedDisplay["captureIndex"].toInt();r.initialDisplay=index==data["initialCaptureIndex"].toInt()?0:index;r.displayError.clear();}
            else{r.displayError=reply["error"].toString();}
        }
        if(!reply["ok"].toBool()&&displayMode=="virtual"){const auto error=reply["error"].toString();RhRuntime::status("error",error);emit displayLaunchError(error);return false;}
    }
    QString rtspSessionUrl;
'@
    $s=Replace-Once $s '    emit connectionStarted();' @'
    { auto& runtime=RhRuntime::get();QMutexLocker lock(&runtime.mutex);
      runtime.hostAddress=m_Computer->activeAddress.address();runtime.hostCert=m_Computer->serverCert;
      runtime.profile={{"width",m_Preferences->width},{"height",m_Preferences->height},{"swapButtons",m_Preferences->swapMouseButtons}}; }
    RhRuntime::stream(true);
    {int index;{auto& r=RhRuntime::get();QMutexLocker lock(&r.mutex);index=r.initialDisplay;}if(index>0)RhKeys::display(index);}
    emit connectionStarted();
'@
    $s=$s.Replace('        execInternal();',"        execInternal();`n        RhRuntime::stream(false);")
    $s=Replace-Once $s '        case SDL_QUIT:' '        case SDL_QUIT:
            if(!RhRuntime::get().retryEligible.load())RhRuntime::get().localDisconnect=true;'
    $s=Replace-Once $s '    m_InputHandler->setWindow(m_Window);' @'
#ifdef Q_OS_WIN32
    {SDL_SysWMinfo info{};SDL_VERSION(&info.version);if(SDL_GetWindowWMInfo(m_Window,&info)&&info.subsystem==SDL_SYSWM_WINDOWS)RhRuntime::get().streamWindow=quintptr(info.info.win.window);}
#endif
    m_InputHandler->setWindow(m_Window);
'@
    $s=Replace-Once $s '    SDL_DestroyWindow(m_Window);' '    RhRuntime::get().streamWindow=0;
    SDL_DestroyWindow(m_Window);'
    $s=Replace-Once $s '            case SDL_CODE_FRAME_READY:' @'
            case 0x52480001:
                m_OverlayManager.setOverlayState(Overlay::OverlayDebug,!m_OverlayManager.isOverlayEnabled(Overlay::OverlayDebug));
                break;
            case 0x52480002:
                clConnectionTerminated(ML_ERROR_UNEXPECTED_EARLY_TERMINATION);
                break;
            case SDL_CODE_FRAME_READY:
'@
    return (Replace-Once $s 'QSvgRenderer svgIconRenderer(QString(":/res/moonlight.svg"));' 'QSvgRenderer svgIconRenderer(QString(":/res/rhremote.svg"));')
}
Edit-Source 'app/streaming/video/ffmpeg.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n"+$s
    $s=Replace-Once $s '                // The test worked, so now let''s initialize it for real' @'
                // The backend may retain an empty device only after its trial
                // decode succeeded. Live codec, textures and swapchain are rebuilt.
                m_BackendRenderer->finishTrialDecode();
                // The test worked, so now let's initialize it for real
'@
    return (Replace-Once $s '        // Accumulate these values into the global stats' @'
        if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")) {
            VIDEO_STATS apiStats={};addVideoStats(m_LastWndVideoStats,apiStats);addVideoStats(m_ActiveWndVideoStats,apiStats);
            char text[2048]={};stringifyVideoStats(apiStats,text,sizeof(text));RhRuntime::performance(QString::fromUtf8(text));
        }
        // Accumulate these values into the global stats
'@)
}
Edit-Source 'app/streaming/video/ffmpeg-renderers/renderer.h' {param($s)
    return (Replace-Once $s '    // NOTE: This can be called BEFORE initialize()!' @'
    // Called only after a successful trial decode, immediately before reset.
    // Backends may hand off reusable device state after releasing trial resources.
    virtual void finishTrialDecode() {}

    // NOTE: This can be called BEFORE initialize()!
'@)
}
Edit-Source 'app/streaming/input/gamepad.cpp' {param($s)
    $pattern='(?s)QString SdlInputHandler::getUnmappedGamepads\(\)\r?\n\{.*?\r?\n    return ret;\r?\n\}'
    $match=[regex]::Match($s,$pattern);if(-not$match.Success){throw 'Unmapped controller probe anchor missing'}
    $body=Replace-Once $match.Value '    if (SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {' "    const bool rhControllersInitialized=SDL_WasInit(SDL_INIT_GAMECONTROLLER)!=0;`n    if (!rhControllersInitialized && SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {"
    $body=Replace-Once $body '    SDL_QuitSubSystem(SDL_INIT_GAMECONTROLLER);' '    if (!rhControllersInitialized) SDL_QuitSubSystem(SDL_INIT_GAMECONTROLLER);'
    return $s.Substring(0,$match.Index)+$body+$s.Substring($match.Index+$match.Length)
}
Edit-Source 'app/streaming/input/input.cpp' {param($s)
    $start=$s.IndexOf('    SDL_assert(!SDL_WasInit(SDL_INIT_JOYSTICK));')
    $end=$s.IndexOf('    m_GamepadMask = getAttachedGamepadMask();',$start)
    if($start-lt0-or$end-lt$start){throw 'Input controller initialization block missing'}
    $end+='    m_GamepadMask = getAttachedGamepadMask();'.Length
    return $s.Substring(0,$start)+"    if(qEnvironmentVariable(`"RHREMOTE_GAMEPAD_ENABLED`")!=`"0`"){`n"+$s.Substring($start,$end-$start)+"`n    }else{m_GamepadMask=0;}`n"+$s.Substring($end)
}
Edit-Source 'app/streaming/video/ffmpeg-renderers/d3d11va.h' {param($s)
    $s=Replace-Once $s '    virtual bool needsTestFrame() override;' '    virtual void finishTrialDecode() override { m_RhProbeCacheable=true; }
    virtual bool needsTestFrame() override;'
    return (Replace-Once $s '    int m_DecoderSelectionPass;' "    bool m_RhProbeCacheable=false;`n    int m_DecoderSelectionPass;")
}
Edit-Source 'app/streaming/video/ffmpeg-renderers/d3d11va.cpp' {param($s)
    $s=Replace-Once $s '#include <initguid.h>' "#include <initguid.h>`n#include `"gui/rhd3d11probe.h`""
    $s=Replace-Once $s '    m_DecoderParams = *params;' "    m_DecoderParams = *params;`n    m_RhProbeCacheable=params->testOnly;"
    $s=[regex]::Replace($s,'(?m)^    m_Device\.Reset\(\);',"    if(m_RhProbeCacheable)RhD3D11Probe::put(m_Factory.Get(),m_Device.Get());`n    m_Device.Reset();")
    $s=Replace-Once $s '    hr = CreateDXGIFactory(__uuidof(IDXGIFactory5), (void**)&m_Factory);' @'
    m_Factory=RhD3D11Probe::factory();
    hr=m_Factory?S_OK:CreateDXGIFactory(__uuidof(IDXGIFactory5), (void**)&m_Factory);
'@
    $s=Replace-Once $s '    hr = D3D11CreateDevice(adapter.Get(),' @'
    m_Device=RhD3D11Probe::take(adapterDesc.AdapterLuid,m_Factory.Get());
    if(m_Device){featureLevel=m_Device->GetFeatureLevel();m_Device->GetImmediateContext(&m_DeviceContext);hr=S_OK;}
    else hr = D3D11CreateDevice(adapter.Get(),
'@
    return $s
}
Edit-Source 'app/streaming/audio/audio.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n"+$s
    $s=[regex]::Replace($s,'int Session::getAudioRendererCapabilities\(int audioConfiguration\)\r?\n\{',@'
int Session::getAudioRendererCapabilities(int audioConfiguration)
{
    // A muted RHRemote connection can negotiate the conservative capability
    // set without opening a playback endpoint for this disposable probe.
    if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")&&!RhRuntime::get().audioEnabled.load())return 0;
'@)
    $s=[regex]::Replace($s,'bool Session::testAudio\(int audioConfiguration\)\r?\n\{',@'
bool Session::testAudio(int audioConfiguration)
{
    if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")){
        if(!RhRuntime::get().audioEnabled.load())return true;
        if(m_RhAudioProbeConfig==audioConfiguration)return m_RhAudioProbeOk;
    }
'@)
    $s=Replace-Once $s '    int caps = audioRenderer->getCapabilities();' @'
    if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")){
        m_RhAudioProbeConfig=audioConfiguration;m_RhAudioProbeOk=true;
    }
    int caps = audioRenderer->getCapabilities();
'@
    $s=Replace-Once $s '    s_ActiveSession->m_AudioSampleCount++;' @'
    s_ActiveSession->m_AudioSampleCount++;
    RhRuntime::get().audioPackets++;
    if(!RhRuntime::get().audioEnabled.load()||RhRuntime::get().volume.load()==0)return;
'@
    return (Replace-Once $s '        if (!s_ActiveSession->m_AudioRenderer->submitAudio(desiredBufferSize)) {' @'
        int gain=RhRuntime::get().volume.load();
        if(gain<100&&samplesDecoded>0){
            int count=samplesDecoded*s_ActiveSession->m_ActiveAudioConfig.channelCount;
            if(s_ActiveSession->m_AudioRenderer->getAudioBufferFormat()==IAudioRenderer::AudioFormat::Float32NE){float* samples=(float*)buffer;for(int i=0;i<count;i++)samples[i]*=gain/100.0f;}
            else{short* samples=(short*)buffer;for(int i=0;i<count;i++)samples[i]=short(int(samples[i])*gain/100);}
        }
        if (!s_ActiveSession->m_AudioRenderer->submitAudio(desiredBufferSize)) {
'@)
}
Edit-Source 'app/app.pro' {param($s)return $s+@'

# RHRemote native shell; the existing streaming implementation remains intact.
HEADERS += gui/rhprofiles.h gui/rhcontrol.h gui/rhhost.h gui/rhkeys.h gui/rhpreparation.h gui/rhprobecache.h gui/rhd3d11probe.h gui/rhcapturetools.h gui/rhwindowbridge.h
win32 {
    TARGET = RHRemote
    QMAKE_TARGET_COMPANY = RHRemote
    QMAKE_TARGET_DESCRIPTION = RHRemote-Moonlight Desktop Client
    QMAKE_TARGET_PRODUCT = RHRemote-Moonlight
    RC_ICONS = $$PWD/res/rhremote.ico
}
'@}
$overlay=Join-Path $repo 'client/windows/overlay'
Copy-Item -LiteralPath (Join-Path $repo 'client/shared/shortcuts.json') -Destination (Join-Path $SourceDir 'app/res/rhremote-shortcuts.json') -Force
Get-ChildItem -LiteralPath $overlay -Recurse -File|ForEach-Object {
    $relative=$_.FullName.Substring($overlay.Length+1);$target=Join-Path $SourceDir $relative
    if(Test-Path -LiteralPath $target){$original=Join-Path $backup $relative;if(-not(Test-Path -LiteralPath $original)){New-Item -ItemType Directory -Force -Path (Split-Path $original -Parent)|Out-Null;Copy-Item -LiteralPath $target -Destination $original}}
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent)|Out-Null;Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
& python (Join-Path $PSScriptRoot 'patch-statistics.py') windows $SourceDir
if($LASTEXITCODE -ne 0){throw 'Windows statistics instrumentation failed.'}
'Windows RHRemote source prepared; prior engine experiments and upstream UI originals retained.'
