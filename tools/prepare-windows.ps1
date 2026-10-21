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
function Replace-Once([string]$Text,[string]$Before,[string]$After){if(-not $Text.Contains($Before)){throw ('Upstream patch anchor missing: '+$Before.Substring(0,[Math]::Min(70,$Before.Length)))};return $Text.Replace($Before,$After)}
Edit-Source 'app/main.cpp' {param($s)
    $s=$s.Replace('QCoreApplication::setOrganizationName("Moonlight Game Streaming Project");','QCoreApplication::setOrganizationName("RHRemote");')
    $s=$s.Replace('QCoreApplication::setOrganizationDomain("moonlight-stream.com");','QCoreApplication::setOrganizationDomain("rhremote.local");')
    $s=$s.Replace('QCoreApplication::setApplicationName("Moonlight");','QCoreApplication::setApplicationName("RHRemote");')
    $s="#include `"gui/rhprofiles.h`"`n#include `"gui/rhcontrol.h`"`n#include <QIcon>`n"+$s
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
    return $s
}
Edit-Source 'app/gui/computermodel.h' {param($s)
    $s=$s.Replace('        DetailsRole','        DetailsRole,
        UuidRole,
        AddressRole')
    $s=$s.Replace('    explicit ComputerModel(QObject* object = nullptr);',"    explicit ComputerModel(QObject* object = nullptr);`n    Q_INVOKABLE QVariantMap get(int row) const;")
    return "#include <QVariantMap>`n"+$s
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
Edit-Source 'app/qml.qrc' {param($s)return $s.Replace('        <file>gui/main.qml</file>',"        <file>gui/main.qml</file>`n        <file>gui/RhButton.qml</file>")}
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
                        if(file.open(QIODevice::ReadOnly)&&file.size()<=4096){const auto request=QJsonDocument::fromJson(file.readAll()).object();const auto path=request["path"].toString();if(path=="/v1/displays"||path=="/v1/display/prepare")result=RhHost::request(event.computer->activeAddress.address(),event.computer->serverCert,path,request["body"].toObject());}
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
Edit-Source 'app/streaming/session.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n"+$s
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
    $s=Replace-Once $s 'QString(m_Computer->name + " - Moonlight")' 'QString(m_Computer->name + " - RHRemote")'
    $s=Replace-Once $s 'bool Session::startConnectionAsync()' 'bool Session::startConnectionAsync()'
    $s=Replace-Once $s '    QString rtspSessionUrl;' @'
    const auto displayMode=qEnvironmentVariable("RHREMOTE_DISPLAY_MODE");
    if(!displayMode.isEmpty()){
        const auto cached=QJsonDocument::fromJson(qgetenv("RHREMOTE_RESUME_SELECTION")).object();
        const int cachedIndex=cached["selected"].toObject()["captureIndex"].toInt();
        auto reply=cachedIndex>=1&&cachedIndex<=12&&cached["displays"].isArray()
            ? QJsonObject{{"ok",true},{"result",cached}}
            : RhHost::request(m_Computer->activeAddress.address(),m_Computer->serverCert,"/v1/display/prepare",{{"mode",displayMode},{"width",m_Preferences->width},{"height",m_Preferences->height}});
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
    return (Replace-Once $s '        // Accumulate these values into the global stats' @'
        if(qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT")) {
            VIDEO_STATS apiStats={};addVideoStats(m_LastWndVideoStats,apiStats);addVideoStats(m_ActiveWndVideoStats,apiStats);
            char text[2048]={};stringifyVideoStats(apiStats,text,sizeof(text));RhRuntime::performance(QString::fromUtf8(text));
        }
        // Accumulate these values into the global stats
'@)
}
Edit-Source 'app/streaming/audio/audio.cpp' {param($s)
    $s="#include `"gui/rhcontrol.h`"`n"+$s
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
HEADERS += gui/rhprofiles.h gui/rhcontrol.h gui/rhhost.h gui/rhkeys.h
win32 {
    TARGET = RHRemote
    QMAKE_TARGET_COMPANY = RHRemote
    QMAKE_TARGET_DESCRIPTION = RHRemote Desktop Client
    QMAKE_TARGET_PRODUCT = RHRemote
    RC_ICONS = $$PWD/res/rhremote.ico
}
'@}
$overlay=Join-Path $repo 'apps/windows/overlay'
Copy-Item -LiteralPath (Join-Path $repo 'apps/shared/shortcuts.json') -Destination (Join-Path $SourceDir 'app/res/rhremote-shortcuts.json') -Force
Get-ChildItem -LiteralPath $overlay -Recurse -File|ForEach-Object {
    $relative=$_.FullName.Substring($overlay.Length+1);$target=Join-Path $SourceDir $relative
    if(Test-Path -LiteralPath $target){$original=Join-Path $backup $relative;if(-not(Test-Path -LiteralPath $original)){New-Item -ItemType Directory -Force -Path (Split-Path $original -Parent)|Out-Null;Copy-Item -LiteralPath $target -Destination $original}}
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent)|Out-Null;Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
& python (Join-Path $PSScriptRoot 'patch-statistics.py') windows $SourceDir
if($LASTEXITCODE -ne 0){throw 'Windows statistics instrumentation failed.'}
'Windows RHRemote source prepared; prior engine experiments and upstream UI originals retained.'
