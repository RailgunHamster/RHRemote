param([string]$SourceDir='D:\RHRemoteTools\moonlight-android-v12.2-source',
 [ValidatePattern('^\d+\.\d+\.\d+(-preview\.[1-9]\d*)?$')][string]$Version='0.2.4',
 [ValidateRange(1,2100000000)][int]$VersionCode=206)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$expected='b48494cb96bff23d8886c4775cc4f39a1075495d'
if((& git -C $SourceDir rev-parse HEAD).Trim() -ne $expected){throw 'Unexpected Android upstream revision.'}
$backup=Join-Path $SourceDir '.rhremote-upstream-v12.2'
function Edit-Source([string]$Relative,[scriptblock]$Edit){
    $target=Join-Path $SourceDir $Relative;$original=Join-Path $backup $Relative
    if(-not(Test-Path -LiteralPath $original)){New-Item -ItemType Directory -Force -Path (Split-Path $original -Parent)|Out-Null;Copy-Item -LiteralPath $target -Destination $original}
    $text=Get-Content -LiteralPath $original -Raw
    $updated=& $Edit $text
    [IO.File]::WriteAllText($target,$updated,(New-Object Text.UTF8Encoding $false))
}
function Replace-Once([string]$Text,[string]$Before,[string]$After){if(-not $Text.Contains($Before)){throw ('Upstream patch anchor missing: '+$Before.Substring(0,[Math]::Min(70,$Before.Length)))};return $Text.Replace($Before,$After)}
Edit-Source 'app/src/main/jni/moonlight-core/moonlight-common-c/src/VideoStream.c' {param($s)
    return (Replace-Once $s '#define FIRST_FRAME_TIMEOUT_SEC 10' '#define FIRST_FRAME_TIMEOUT_SEC 30')
}
Edit-Source 'app/build.gradle' {
    param($s)
    $s=$s.Replace('applicationId "com.limelight"','applicationId "com.rhremote.client"').Replace('applicationId "com.limelight.root"','applicationId "com.rhremote.client.root"')
    $s=$s.Replace('applicationIdSuffix ".debug"','').Replace('applicationIdSuffix ".unofficial"','')
    $s=$s.Replace('versionName "12.2"',('versionName "'+$Version+'"')).Replace('versionCode = 315',('versionCode = '+$VersionCode))
    $s=$s.Replace('"Moonlight (Debug)"','"RHRemote-Moonlight"').Replace('"Moonlight (Root Debug)"','"RHRemote-Moonlight"').Replace('"Moonlight"','"RHRemote-Moonlight"').Replace('"Moonlight (Root)"','"RHRemote-Moonlight"')
    # Build only the actual OPPO architecture; don't spend time building unused ABIs.
    $s=$s.Replace('minSdk 21',"minSdk 26`n        ndk { abiFilters 'arm64-v8a' }")
    $s=$s.Replace('targetSdk 36',"targetSdk 36`n        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'")
    $s=$s.Replace('    compileOptions {',@'
    testOptions {
        unitTests.includeAndroidResources = true
        unitTests.all {
            systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
            ['https.proxyHost','https.proxyPort','http.proxyHost','http.proxyPort','http.nonProxyHosts'].each { key ->
                if(System.getProperty(key) != null)systemProperty key, System.getProperty(key)
            }
        }
    }
    compileOptions {
'@)
    $s=$s.Replace("dependencies {","dependencies {`n    implementation 'androidx.core:core:1.16.0'`n    testImplementation 'junit:junit:4.13.2'`n    testImplementation 'org.robolectric:robolectric:4.17'`n    androidTestImplementation 'androidx.test:runner:1.6.2'`n    androidTestImplementation 'androidx.test.ext:junit:1.2.1'")
    return $s
}
Edit-Source 'app/src/main/AndroidManifest.xml' {param($s)
    $s=$s.Replace('<uses-permission android:name="android.permission.INTERNET" />','<uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />')
    $s=$s.Replace('    </application>','        <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.updates" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/rh_update_paths" /></provider>
    </application>')
    $s=$s.Replace('android:isGame="true"','android:isGame="false"').Replace('android:appCategory="game"','android:appCategory="productivity"')
    $s=$s.Replace('android:icon="@mipmap/ic_launcher"','android:icon="@mipmap/rh_launcher" android:roundIcon="@mipmap/rh_launcher"').Replace('android:theme="@style/AppTheme"','android:theme="@style/RhTheme"')
    return $s
}
Edit-Source 'app/src/main/java/com/limelight/computers/ComputerManagerService.java' {param($s)
    $s=Replace-Once $s '    private ComputerDatabaseManager dbManager;' "    private ComputerDatabaseManager dbManager;`n    private final Object rhPairingStoreLock = new Object();"
    $persistStart=$s.IndexOf('        // If it''s online, update our persistent state')
    if($persistStart -lt 0){throw 'Android discovery persistence anchor missing'}
    $persistEnd=$s.IndexOf('        // Don''t call the listener if this is a failed lookup of a new PC',$persistStart)
    if($persistEnd -le $persistStart){throw 'Android discovery persistence end anchor missing'}
    $persistence=@'
        if (details.state == ComputerDetails.State.ONLINE) {
            // Optional discovery runs outside the credential write lock.
            if (newPc && details.remoteAddress == null && dbManager.getComputerByUUID(details.uuid) == null) {
                try {
                    if (InetAddress.getByName(details.activeAddress.address).isSiteLocalAddress()) populateExternalAddress(details);
                } catch (UnknownHostException ignored) {}
            }
            synchronized (rhPairingStoreLock) {
                if (!com.limelight.rhremote.RhPairingStore.savePoll(dbManager, details, newPc)) {
                    releaseLocalDatabaseReference();
                    return false;
                }
            }
        }

'@
    $s=$s.Remove($persistStart,$persistEnd-$persistStart).Insert($persistStart,$persistence+"`n")
    $s=$s.Replace("`r`n","`n")
    $tupleAnchor="    private void addTuple(ComputerDetails details) {`n        synchronized (pollingTuples) {"
    $s=Replace-Once $s $tupleAnchor (@'
    private void addTuple(ComputerDetails details) {
        synchronized (pollingTuples) {
            synchronized (rhPairingStoreLock) {
                com.limelight.rhremote.RhPairingStore.refreshLocalPin(dbManager, details);
            }
'@).Replace("`r`n","`n")
    $s=Replace-Once $s '        public void invalidateStateForComputer(String uuid) {' @'
        public ComputerDetails saveRhPairing(ComputerDetails verified, java.security.cert.X509Certificate certificate) throws IOException {
            if (verified == null || verified.uuid == null) throw new IOException("Missing pairing identity");
            if (!getLocalDatabaseReference()) throw new IOException("Connection manager unavailable");
            try {
                ComputerDetails saved;
                synchronized (pollingTuples) {
                    PollingTuple existing = null;
                    for (PollingTuple tuple : pollingTuples) if (tuple.computer.uuid.equals(verified.uuid)) { existing = tuple; break; }
                    Object gate = existing == null ? pollingTuples : existing.networkLock;
                    synchronized (gate) {
                      synchronized (rhPairingStoreLock) {
                        try {
                            saved = com.limelight.rhremote.RhPairingStore.save(getApplicationContext(), dbManager, verified, certificate);
                        } catch (RuntimeException storageFailure) {
                            throw new IOException("Local pairing record could not be saved", storageFailure);
                        }
                        addTuple(saved);
                        ComputerDetails live = getComputer(saved.uuid);
                        // update() intentionally ignores null tuples; explicitly clear
                        // transient proxy addresses removed by the persistence helper.
                        live.manualAddress = saved.manualAddress; live.localAddress = saved.localAddress;
                        live.remoteAddress = saved.remoteAddress; live.ipv6Address = saved.ipv6Address;
                        live.activeAddress = saved.activeAddress;
                      }
                    }
                }
                ComputerManagerListener current = listener;
                if (current != null) current.notifyComputerUpdated(new ComputerDetails(saved));
                return new ComputerDetails(saved);
            } finally { releaseLocalDatabaseReference(); }
        }
        public void invalidateStateForComputer(String uuid) {
'@
    $start=$s.IndexOf('    private void populateExternalAddress(ComputerDetails details) {')
    if($start -lt 0){throw 'Android discovery network anchor missing'}
    $end=$s.IndexOf('    private MdnsDiscoveryListener createDiscoveryListener()', $start)
    if($start -lt 0 -or $end -le $start){throw 'Android discovery network anchor missing'}
    $replacement=@'
    private void populateExternalAddress(ComputerDetails details) {
        // Never rebind the whole process to discover a public address. Other
        // requests may concurrently need the VPN, and their sockets retain the
        // binding even after the STUN lookup restores the default network.
        if (details.remoteAddress != null || NetHelper.isActiveNetworkVpn(this)) return;
        String address = NvConnection.findExternalAddressForMdns("stun.moonlight-stream.org", 3478);
        // A VPN may have become active during the lookup. In that case its
        // public endpoint is not evidence of the discovered computer's address.
        if (address != null && !NetHelper.isActiveNetworkVpn(this)) {
            details.remoteAddress = new ComputerDetails.AddressTuple(address, details.guessExternalPort());
        }
    }

'@
    $s=$s.Remove($start,$end-$start).Insert($start,$replacement+"`n")
    $s=$s.Replace('import java.util.concurrent.locks.Lock;','').Replace('import java.util.concurrent.locks.ReentrantLock;','')
    $s=$s.Replace('    private final Lock defaultNetworkLock = new ReentrantLock();','')
    $s=Replace-Once $s '        startParallelPollThread(ipv6Info, uniqueAddresses);' @'
        startParallelPollThread(ipv6Info, uniqueAddresses);
        // Saved Tailscale/public addresses participate in the same identity-checked
        // polling as upstream addresses. Background discovery never opens a tunnel.
        java.util.ArrayList<ParallelPollTuple> rhExtra = new java.util.ArrayList<>();
        for (ComputerDetails.AddressTuple address : com.limelight.rhremote.RhNetwork.pollingAddresses(this, details)) {
            ParallelPollTuple tuple = new ParallelPollTuple(address, details);
            rhExtra.add(tuple);
            startParallelPollThread(tuple, uniqueAddresses);
        }
'@
    $anchor="        } finally {`n            // Stop any further polling if we've found a working address or we've been"
    $s=$s.Replace("`r`n","`n")
    $s=Replace-Once $s $anchor (@'
            for (ParallelPollTuple tuple : rhExtra) {
                synchronized (tuple) {
                    while (!tuple.complete) tuple.wait();
                    if (tuple.returnedDetails != null) {
                        tuple.returnedDetails.activeAddress = tuple.address;
                        return tuple.returnedDetails;
                    }
                }
            }
        } finally {
            // Stop any further polling if we've found a working address or we've been
'@).Replace("`r`n","`n")
    $s=Replace-Once $s '            ipv6Info.interrupt();' "            ipv6Info.interrupt();`n            for (ParallelPollTuple tuple : rhExtra) tuple.interrupt();"
    $s=Replace-Once $s '            details.update(polledDetails);' @'
            details.update(polledDetails);
            if (details.activeAddress != null) com.limelight.rhremote.RhNetwork.learn(getApplicationContext(), new ComputerDetails(details), details.activeAddress.address);
'@
    return $s
}
Edit-Source 'app/src/main/java/com/limelight/PcView.java' {param($s)
    $s=Replace-Once $s '                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();' @'
                            try {
                                computer.update(managerBinder.saveRhPairing(computer, pm.getPairedCert()));
                            } catch (IOException persistenceFailure) {
                                success = false;
                                throw persistenceFailure;
                            }
'@
    $s=Replace-Once $s 'private RelativeLayout noPcFoundLayout;' "private RelativeLayout noPcFoundLayout;`n    private com.limelight.rhremote.RhHome rhHome;"
    $s=Replace-Once $s '    private void initializeViews() {' @'
    private void initializeViews() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rhHome = new com.limelight.rhremote.RhHome(this, new com.limelight.rhremote.RhHome.Actions() {
            public void connect(ComputerDetails d) {
                if (d.pairState != PairState.PAIRED) doPair(d); else launchRhDesktop(d);
            }
            public void add(String address) { addRhComputer(address); }
            public void advanced() { startActivity(new Intent(PcView.this, StreamSettings.class)); }
            public void pair(ComputerDetails d,String pin) { startRhPair(d,pin); }
            public void cancelPair() { RhPairOperation operation=rhPairOperation;if(operation!=null){operation.cancelled=true;NvHTTP current=operation.http;if(current!=null)current.cancelRhPairing();} }
            public void refresh() { if(managerBinder!=null) for(int i=0;i<pcGridAdapter.getCount();i++)managerBinder.invalidateStateForComputer(((ComputerObject)pcGridAdapter.getItem(i)).details.uuid);startComputerUpdates(); }
        });
        noPcFoundLayout = rhHome.emptyView();
        for(int i=0;i<pcGridAdapter.getCount();i++) rhHome.update(((ComputerObject)pcGridAdapter.getItem(i)).details);
    }
    private void initializeLegacyViews() {
'@
    $s=Replace-Once $s '        pcGridAdapter.notifyDataSetChanged();' "        pcGridAdapter.notifyDataSetChanged();`n        if(rhHome != null) for(int rhIndex=0;rhIndex<pcGridAdapter.getCount();rhIndex++) rhHome.update(((ComputerObject)pcGridAdapter.getItem(rhIndex)).details);"
    $s=Replace-Once $s '    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {' @'
    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if(!showHiddenGames) { launchRhDesktop(computer); return; }
'@
    $s=Replace-Once $s '    public static class ComputerObject {' @'
    @Override public void onBackPressed() {
        if(rhHome != null && rhHome.goBack()) return;
        super.onBackPressed();
    }
    private void addRhComputer(String address) {
        if(managerBinder == null) { rhHome.message("连接管理器正在准备，请稍后重试。"); return; }
        rhHome.message("正在添加 " + address);
        new Thread(() -> {
            try {
                java.net.URI uri = new java.net.URI("http://" + address);
                if(uri.getHost()==null || uri.getRawUserInfo()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty())) throw new IOException("请输入电脑 IP 或主机名，可选端口。");
                ComputerDetails d = new ComputerDetails();
                d.manualAddress = new ComputerDetails.AddressTuple(uri.getHost(), uri.getPort()>0?uri.getPort():NvHTTP.DEFAULT_HTTP_PORT);
                boolean added = managerBinder.addComputerBlocking(d);
                runOnUiThread(() -> rhHome.message(added?"已添加电脑":"电脑不可达，请确认 Sunshine 已运行。"));
            } catch(Exception e) { runOnUiThread(() -> rhHome.message("添加失败："+e.getMessage())); }
        },"RHRemote Add").start();
    }
    private static final class RhPairOperation {volatile NvHTTP http;volatile boolean cancelled;}
    private volatile RhPairOperation rhPairOperation;
    private void startRhPair(ComputerDetails computer,String pin) {
        if(rhPairOperation!=null&&!rhPairOperation.cancelled)return;
        final RhPairOperation operation=new RhPairOperation();rhPairOperation=operation;
        new Thread(() -> {
            boolean paired=false;String result="配对失败，请重试";ComputerDetails savedPair=null;
            try {
                if(managerBinder==null)throw new IOException("manager unavailable");
                stopComputerUpdates(true);
                NvHTTP http=new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),computer.httpsPort,managerBinder.getUniqueId(),computer.serverCert,PlatformBinding.getCryptoProvider(this));
                operation.http=http;
                if(operation.cancelled){http.cancelRhPairing();throw new IOException("cancelled");}
                PairingManager pairing=http.getPairingManager();
                PairState state=pairing.pair(http.getServerInfo(true),pin);
                if(state==PairState.PAIRED && !operation.cancelled){savedPair=managerBinder.saveRhPairing(computer,pairing.getPairedCert());paired=true;result="配对完成";}
                else if(state==PairState.ALREADY_IN_PROGRESS)result="主机已有配对请求，请稍后重试";
                else if(state==PairState.PIN_WRONG)result="配对码不正确";
            }catch(Exception e){if(e.getMessage()!=null)result="配对失败："+e.getMessage();}finally{operation.http=null;}
            final boolean success=paired;final String message=operation.cancelled?"已取消配对":result;final ComputerDetails stored=savedPair;
            runOnUiThread(()->{if(rhPairOperation!=operation)return;rhPairOperation=null;if(rhHome!=null){if(success)rhHome.update(stored);rhHome.pairFinished(success,message);}startComputerUpdates();});
        },"RHRemote Pair").start();
    }
    private void launchRhDesktop(ComputerDetails computer) {
        if(managerBinder==null) return;
        rhHome.message("正在连接 " + computer.name);
        new Thread(() -> {
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),computer.httpsPort,managerBinder.getUniqueId(),computer.serverCert,PlatformBinding.getCryptoProvider(this));
                java.util.List<NvApp> apps = http.getAppList();
                NvApp desktop = null;
                for(NvApp app:apps) if("Desktop".equalsIgnoreCase(app.getAppName())) { desktop=app; break; }
                if(desktop==null) throw new IOException("Sunshine 没有 Desktop 入口，请在主机确认应用列表。");
                if(computer.runningGameId!=0 && computer.runningGameId!=desktop.getAppId()) throw new IOException("主机正在运行其他串流应用，请先结束该会话。");
                final NvApp selected=desktop;
                runOnUiThread(() -> { if(!isFinishing()) ServerHelper.doStart(this,selected,computer,managerBinder); });
            } catch(Exception e) { com.limelight.rhremote.RhStatistics.event("launch_failed");com.limelight.rhremote.RhStatistics.finish();runOnUiThread(() -> rhHome.message("连接失败："+e.getMessage())); }
        },"RHRemote Desktop").start();
    }
    public static class ComputerObject {
'@
    return $s
}
Edit-Source 'app/src/main/java/com/limelight/nvstream/http/NvHTTP.java' {param($s)
    $s=Replace-Once $s '    private X509Certificate serverCert;' "    private X509Certificate serverCert;`n    private final boolean rhStrictPin;"
    $s=Replace-Once $s '    public NvHTTP(ComputerDetails.AddressTuple address, int httpsPort, String uniqueId, X509Certificate serverCert, LimelightCryptoProvider cryptoProvider) throws IOException {' @'
    public NvHTTP(ComputerDetails.AddressTuple address, int httpsPort, String uniqueId, X509Certificate serverCert, LimelightCryptoProvider cryptoProvider) throws IOException {
        this(address, httpsPort, uniqueId, serverCert, cryptoProvider, false);
    }
    public NvHTTP(ComputerDetails.AddressTuple address, int httpsPort, String uniqueId, X509Certificate serverCert, LimelightCryptoProvider cryptoProvider, boolean strictPin) throws IOException {
        this.rhStrictPin = strictPin;
'@
    $s=Replace-Once $s '            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {' @'
            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                if (rhStrictPin && NvHTTP.this.serverCert != null) {
                    if (certs.length != 1 || !certs[0].equals(NvHTTP.this.serverCert)) throw new CertificateException("Paired certificate mismatch");
                    return;
                }
'@
    $s=Replace-Once $s '                // Fall back to default HostnameVerifier for validating CA-issued certs' @'
                if (rhStrictPin && NvHTTP.this.serverCert != null) return false;
                // Fall back to default HostnameVerifier for validating CA-issued certs
'@
    $s=Replace-Once $s '    public ComputerDetails getComputerDetails(boolean likelyOnline) throws IOException, XmlPullParserException {' @'
    // Connection admission must not turn a certificate failure into an unpaired
    // HTTP response. The upstream discovery/re-pair fallback remains separate.
    public ComputerDetails getRhConnectionDetails() throws IOException, XmlPullParserException {
        if (serverCert == null) return getComputerDetails(false);
        String response = openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "serverinfo");
        getServerVersion(response);
        return getComputerDetails(response);
    }
    public ComputerDetails getComputerDetails(boolean likelyOnline) throws IOException, XmlPullParserException {
'@
    $s=Replace-Once $s '    public void unpair() throws IOException {' @'
    public void cancelRhRequests() {
        httpClientLongConnectTimeout.dispatcher().cancelAll();
        httpClientLongConnectNoReadTimeout.dispatcher().cancelAll();
        httpClientShortConnectTimeout.dispatcher().cancelAll();
    }
    public void unpair() throws IOException {
'@
    return $s
}
Edit-Source 'app/src/main/java/com/limelight/nvstream/http/PairingManager.java' {param($s)return $s}
Edit-Source 'app/src/main/java/com/limelight/Game.java' {param($s)
    $s=Replace-Once $s '    private final TouchContext[] touchContextMap' '    private com.limelight.rhremote.RhTouchController rhTouch;
    private com.limelight.rhremote.RhStreamControls rhControls;
    public com.limelight.rhremote.RhAudioRenderer rhAudioRenderer;
    private com.limelight.rhremote.RhProfile rhProfile;
    private final TouchContext[] touchContextMap'
    $s=Replace-Once $s '        prefConfig = PreferenceConfiguration.readPreferences(this);' @'
        prefConfig = PreferenceConfiguration.readPreferences(this);
        rhProfile = com.limelight.rhremote.RhProfile.load(this,getIntent().getStringExtra(EXTRA_PC_UUID));
        rhProfile.apply(prefConfig);
'@
    $s=Replace-Once $s '        // Initialize touch contexts' @'
        rhTouch = new com.limelight.rhremote.RhTouchController(conn,streamView,rhProfile);
        rhControls = com.limelight.rhremote.RhStreamControls.install(this,rhTouch,prefConfig,rhProfile,conn.getRhDisplay());
        // Initialize touch contexts
'@
    $s=Replace-Once $s '                if (virtualController != null &&' @'
                if(rhTouch != null) return rhTouch.handle(view,event);
                if (virtualController != null &&
'@
    $s=Replace-Once $s '    private void stopConnection() {' "    private void stopConnection() {`n        if(rhControls != null) rhControls.dispose();`n        if(rhTouch != null) rhTouch.cancel();"
    $s=Replace-Once $s '                connected = true;' "                connected = true;`n                if(rhControls != null) rhControls.markConnected();"
    $s=Replace-Once $s '.setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)' '.setRemoteConfiguration(rhProfile.remoteConfiguration(host))'
    $s=Replace-Once $s 'rhProfile.apply(prefConfig);' 'rhProfile.applyNetwork(getIntent().getStringExtra(EXTRA_HOST),prefConfig);'
    $s=Replace-Once $s '.setMaxPacketSize(1392)'  '.setMaxPacketSize(rhProfile.effectivePacketSize(host))'
    $s=Replace-Once $s '        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);' @'
        conn.setRhDisplay(new com.limelight.rhremote.RhDisplay(this,host,getIntent().getByteArrayExtra(EXTRA_SERVER_CERT),rhProfile));
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
'@
    $s=Replace-Once $s 'new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx)' '(rhAudioRenderer = new com.limelight.rhremote.RhAudioRenderer(Game.this,prefConfig.enableAudioFx,rhProfile))'
    return $s
}
Edit-Source 'app/src/main/java/com/limelight/nvstream/NvConnection.java' {param($s)
    $s=Replace-Once $s '    private String uniqueId;' @'
    private String uniqueId;
    private com.limelight.rhremote.RhDisplay rhDisplay;
    public void setRhDisplay(com.limelight.rhremote.RhDisplay display){rhDisplay=display;}
    public com.limelight.rhremote.RhDisplay getRhDisplay(){return rhDisplay;}
'@
    return (Replace-Once $s "        // If there's a game running, resume it" "        if(rhDisplay!=null)rhDisplay.prepare();`n        // If there's a game running, resume it")
}
$overlay=Join-Path $repo 'client/android/overlay'
New-Item -ItemType Directory -Force -Path (Join-Path $SourceDir 'app/src/main/assets')|Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'client/crates/rhremote-core/src/actions.json') -Destination (Join-Path $SourceDir 'app/src/main/assets/rhremote-actions.json') -Force
Copy-Item -LiteralPath (Join-Path $repo 'client/shared/shortcuts.json') -Destination (Join-Path $SourceDir 'app/src/main/assets/rhremote-shortcuts.json') -Force
Copy-Item -LiteralPath (Join-Path $repo 'client/crates/rhremote-core/src/settings.json') -Destination (Join-Path $SourceDir 'app/src/main/assets/rhremote-settings.json') -Force
Get-ChildItem -LiteralPath $overlay -Recurse -File|ForEach-Object {
    $relative=$_.FullName.Substring($overlay.Length+1);$target=Join-Path $SourceDir $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent)|Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
$sdk='C:/Users/40608/AppData/Local/Android/Sdk'
[IO.File]::WriteAllText((Join-Path $SourceDir 'local.properties'),"sdk.dir=$sdk`n",(New-Object Text.UTF8Encoding $false))
& python (Join-Path $PSScriptRoot 'patch-statistics.py') android $SourceDir
if($LASTEXITCODE -ne 0){throw 'Android statistics instrumentation failed.'}
& python (Join-Path $PSScriptRoot 'patch-tunnel.py') android $SourceDir
if($LASTEXITCODE -ne 0){throw 'Android tunnel adaptation failed.'}
& python (Join-Path $PSScriptRoot 'patch-android-recovery.py') $SourceDir
if($LASTEXITCODE -ne 0){throw 'Android recovery adaptation failed.'}
& python (Join-Path $PSScriptRoot 'patch-pairing.py') android $SourceDir
if($LASTEXITCODE -ne 0){throw 'Android pairing adaptation failed.'}
& python (Join-Path $PSScriptRoot 'patch-initial-display.py') android $SourceDir
if($LASTEXITCODE -ne 0){throw 'Android initial display adaptation failed.'}
Write-Output 'Android RHRemote source prepared. Upstream originals retained locally; no host or device settings changed.'
