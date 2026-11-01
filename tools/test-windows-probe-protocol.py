"""Run the actual native list CLI against owned loopback HTTP/mTLS peers only."""
import argparse, datetime, hashlib, http.server, ipaddress, json, os, pathlib
import secrets, socket, socketserver, ssl, subprocess, threading, time, uuid
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

HOST = '127.77.0.2'
CASES = {
    'http-no-pin': 'unpaired', 'https-paired': 'paired', 'https-unpaired': 'unpaired',
    'https-xml-401': 'auth_rejected', 'https-http-401': 'auth_rejected',
    'certificate-changed': 'certificate_mismatch', 'tls-not-tls': 'tls_failure',
    'uuid-mismatch': 'identity_mismatch', 'tls-timeout': 'unavailable',
    'slow-metadata': 'paired', 'cancel-metadata': 'paired',
    'http-401-no-pin': 'unavailable', 'certificate-changed-401': 'certificate_mismatch',
}
def sha(path): return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest().upper()
def save(path, value): pathlib.Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
def generated_identity(folder, name, client=False):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'RHRemote test '+name)])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5))
        .not_valid_after(now+datetime.timedelta(days=2)).add_extension(x509.BasicConstraints(ca=True,path_length=None),critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(HOST))]),critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH if client else ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
        .add_extension(x509.KeyUsage(digital_signature=True,content_commitment=False,key_encipherment=True,data_encipherment=False,key_agreement=False,key_cert_sign=True,crl_sign=True,encipher_only=False,decipher_only=False),critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),critical=False).sign(key,hashes.SHA256()))
    cert_path=folder/(name+'.pem'); key_path=folder/(name+'-key.pem')
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    return cert_path,key_path,hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest().upper()

class State:
    def __init__(self,case,target,client_sha):
        self.case=case; self.target=target; self.client_sha=client_sha; self.lock=threading.Lock()
        self.requests=[]; self.handshakes=[]; self.unexpected=[]; self.metadata_entered=threading.Event()
        self.metadata_closed=threading.Event(); self.stopping=threading.Event(); self.started=time.perf_counter_ns()
    def record(self,collection,value):
        value['atNs']=time.perf_counter_ns()-self.started
        with self.lock: getattr(self,collection).append(value)

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args): pass
    def do_POST(self):
        self.server.state.record('unexpected',{'method':'POST','path':self.path});self.send_error(405)
    def do_GET(self):
        state=self.server.state; path=self.path.split('?',1)[0]; role=self.server.role
        if path not in ('/serverinfo','/applist','/v1/network'):
            state.record('unexpected',{'method':'GET','path':path});self.send_error(404);return
        client=hashlib.sha256(self.connection.getpeercert(binary_form=True)).hexdigest().upper() if role!='http' else None
        if role!='http' and client!=state.client_sha: state.record('unexpected',{'error':'wrong generated client certificate'})
        state.record('requests',{'role':role,'path':path,'clientCertificateVerified':role!='http' and client==state.client_sha})
        if path=='/v1/network':
            state.metadata_entered.set()
            if state.case in ('slow-metadata','cancel-metadata'):
                # Detect the actual peer close while withholding the whole response.
                until=time.monotonic()+6; self.connection.settimeout(.1)
                while time.monotonic()<until and not state.stopping.is_set():
                    try:
                        if not self.connection.recv(1): state.metadata_closed.set();return
                    except socket.timeout: pass
                    except (OSError,ssl.SSLError): state.metadata_closed.set();return
            data=b'{"ok":true,"result":{"hostname":"Native protocol fixture","tailscale":{"installed":false}}}'
            return self.reply(200,data,'application/json')
        if path=='/applist': return self.reply(200,b'<root status_code="200"><App><ID>1</ID><AppTitle>Desktop</AppTitle></App></root>')
        if role=='https' and state.case=='tls-timeout':
            state.stopping.wait(6);return
        if (role=='https' and state.case in ('https-http-401','https-xml-401','certificate-changed-401')
                or role=='http' and state.case=='http-401-no-pin'):
            # An ordinary unauthorized response need not disclose a UUID.
            return self.reply(200 if state.case=='https-xml-401' else 401,b'<root status_code="401" status_message="Unauthorized"/>')
        target=str(uuid.UUID(int=1)) if role=='https' and state.case=='uuid-mismatch' else state.target
        paired=role=='https' and state.case!='https-unpaired'
        data=(f'<root status_code="200"><hostname>Native protocol fixture</hostname><uniqueid>{target}</uniqueid>'
            f'<HttpsPort>47984</HttpsPort><PairStatus>{int(paired)}</PairStatus><state>SUNSHINE_SERVER_FREE</state>'
            '<currentgame>0</currentgame><appversion>7.1.431.0</appversion><GfeVersion>3.23.0.74</GfeVersion>'
            f'<LocalIP>{HOST}</LocalIP><ExternalIP>{HOST}</ExternalIP><mac>00:00:00:00:00:00</mac></root>').encode()
        self.reply(200,data)
    def reply(self,status,data,content_type='application/xml'):
        try:
            self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(data)))
            self.send_header('Connection','close');self.end_headers();self.wfile.write(data);self.wfile.flush()
        except (OSError,ssl.SSLError): pass

class Server(http.server.ThreadingHTTPServer):
    allow_reuse_address=False; daemon_threads=True
    def server_bind(self):
        self.socket.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
        # The owned IP is fixed; HTTPServer's reverse-DNS lookup is unnecessary
        # and must not turn fixture setup time into apparent native startup time.
        socketserver.TCPServer.server_bind(self)
        self.server_name=HOST;self.server_port=self.server_address[1]
    def __init__(self,port,role,state,context=None):
        self.role=role; self.state=state; self.context=context
        super().__init__((HOST,port),Handler)
    def get_request(self):
        connection,address=super().get_request();connection.settimeout(3)
        if self.context:
            try:
                connection=self.context.wrap_socket(connection,server_side=True)
                self.state.record('handshakes',{'role':self.role,'established':True})
            except (OSError,ssl.SSLError) as failure:
                connection.close();self.state.record('handshakes',{'role':self.role,'established':False,'errorType':type(failure).__name__});raise
        return connection,address

def owned_servers(state,cert,key,client_cert,plain_tls=False):
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(str(cert),str(key));context.load_verify_locations(cafile=str(client_cert));context.verify_mode=ssl.CERT_REQUIRED
    for _ in range(24):
        offset=4096+secrets.randbelow(12000);servers=[]
        try:
            servers.append(Server(47989+offset,'http',state))
            servers.append(Server(47984+offset,'https',state,None if plain_tls else context))
            servers.append(Server(47995+offset,'metadata',state,context))
            threads=[threading.Thread(target=s.serve_forever,kwargs={'poll_interval':.05},daemon=True) for s in servers]
            for thread in threads: thread.start()
            return offset,servers,threads
        except OSError:
            for server in servers: server.server_close()
    raise RuntimeError('Could not bind all owned loopback fixture ports; no client started')

def run_case(plan,root,name,identities,qt_bin):
    folder=root/name;folder.mkdir();data=folder/'native-data';(data/'RHRemote').mkdir(parents=True);(data/'portable.dat').write_bytes(b'')
    target=str(uuid.uuid4());client_cert,client_key,client_sha=identities['client'];server_cert,server_key,_=identities['server'];changed_cert,changed_key,_=identities['changed']
    pin='-' if name in ('http-no-pin','http-401-no-pin') else str(server_cert)
    env={k:v for k,v in os.environ.items() if not k.startswith('RHREMOTE_')}
    env['PATH']=str(qt_bin)+os.pathsep+env.get('PATH','')
    seed=subprocess.run([str(root/'seed-probe-identity.exe'),str(data/'RHRemote/RHRemote.ini'),target,str(client_cert),str(client_key),pin],env=env,capture_output=True,timeout=10)
    (folder/'identity-seed.log').write_bytes(seed.stdout+seed.stderr)
    if seed.returncode: raise RuntimeError('Fresh test identity could not be prepared')
    state=State(name,target,client_sha)
    cert,key=(changed_cert,changed_key) if name in ('certificate-changed','certificate-changed-401') else (server_cert,server_key)
    offset,servers,threads=owned_servers(state,cert,key,client_cert,name=='tls-not-tls')
    probe_id=secrets.token_hex(32);report_path=folder/'report.json';metadata_path=folder/'network.json'
    env.update({'RHREMOTE_PROBE_OPERATION':'1','RHREMOTE_PROBE_ID':probe_id,'RHREMOTE_PROBE_REPORT':str(report_path),'RHREMOTE_TARGET_UUID':target,
        'RHREMOTE_CONNECT_ADDRESS':HOST,'RHREMOTE_NETWORK_METADATA':str(metadata_path),'RHREMOTE_OWNER_PID':str(os.getpid()),'RHREMOTE_DATA_DIR':str(data),
        'RHREMOTE_TUNNEL_PORT_OFFSET':str(offset),'QT_QPA_PLATFORM':'offscreen','QT_QPA_PLATFORM_PLUGIN_PATH':plan['platformPluginDirectory'],'QT_QUICK_BACKEND':'software'})
    process=None;report=None;history=[];began=time.perf_counter_ns();first_report_ns=None;published_before_metadata=False;killed=False;reason='normal-exit'
    try:
        with (folder/'stdout.log').open('wb') as out,(folder/'stderr.log').open('wb') as err:
            process=subprocess.Popen([plan['engine'],'list',HOST],cwd=data,env=env,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
            deadline=time.monotonic()+plan['maximumProcessSeconds']
            while True:
                try:
                    candidate=json.loads(report_path.read_text(encoding='utf-8-sig'))
                    if candidate['probeId']!=probe_id or candidate['targetUuid']!=target: raise RuntimeError('Native report context mismatch')
                    if candidate!=report:
                        report=candidate;at=time.perf_counter_ns()-began;history.append({'atNs':at,'report':report})
                        if first_report_ns is None:first_report_ns=at
                    if report['outcome']=='paired' and not metadata_path.exists() and process.poll() is None:published_before_metadata=True
                except (FileNotFoundError,json.JSONDecodeError):pass
                if process.poll() is not None:break
                elapsed=(time.perf_counter_ns()-began)/1e9
                cancel=name=='cancel-metadata' and state.metadata_entered.is_set() and report and report['outcome']=='paired'
                # Model the existing native worker's five-second process budget.
                if cancel or elapsed>=5 or time.monotonic()>=deadline:
                    killed=True;reason='explicit-fixture-cancel' if cancel else 'native-five-second-budget';process.kill();process.wait(timeout=3);break
                time.sleep(.005)
            process.wait(timeout=3)
        if report_path.exists():report=json.loads(report_path.read_text(encoding='utf-8-sig'))
        if name=='cancel-metadata':state.metadata_closed.wait(2)
    finally:
        if process is not None and process.poll() is None:process.kill();process.wait(timeout=3)
        state.stopping.set()
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=2)
    requests=state.requests;secure=[r for r in requests if r['role']!='http']
    record={'case':name,'expectedOutcome':CASES[name],'report':report,'reportPath':str(report_path.relative_to(root)),'probeId':probe_id,'targetUuid':target,
        'testClientCertificateSha256':client_sha,'testPinnedServerCertificateSha256':identities['server'][2],
        'processId':process.pid,'processExited':process.poll() is not None,'exitCode':process.returncode,'terminatedByFixture':killed,'terminationReason':reason,
        'processElapsedNs':time.perf_counter_ns()-began,'firstReportNs':first_report_ns,'publishedBeforeMetadata':published_before_metadata,'metadataPeerClosed':state.metadata_closed.is_set(),
        'offset':offset,'requests':requests,'handshakes':state.handshakes,'unexpected':state.unexpected,'reportHistory':history,'engineSha256':plan['engineSha256']}
    record['passed']=bool(report and report['outcome']==CASES[name] and not state.unexpected and all(r['clientCertificateVerified'] for r in secure))
    if name in ('https-paired','https-unpaired','https-xml-401','https-http-401','slow-metadata','cancel-metadata'):record['passed'] &= any(r['role']=='https' for r in secure)
    if name=='slow-metadata':record['passed'] &= published_before_metadata
    if name=='cancel-metadata':record['passed'] &= published_before_metadata and state.metadata_closed.is_set() and killed
    # A seeded INI contains only this run's generated private identity. Retain
    # sanitized reports and public certificates, never the reusable test key.
    (data/'RHRemote/RHRemote.ini').unlink()
    record['generatedIdentityIniRemoved']=True
    save(folder/'verification.json',record);return record

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--plan',type=pathlib.Path,required=True);parser.add_argument('--qt-bin',type=pathlib.Path,required=True);args=parser.parse_args()
    plan=json.loads(args.plan.read_text(encoding='utf-8-sig'));root=pathlib.Path(plan['output']);assert plan['host']==HOST and plan['qtPlatform']=='offscreen'
    assert sha(plan['engine'])==plan['engineSha256'];assert (pathlib.Path(plan['platformPluginDirectory'])/'qoffscreen.dll').exists()
    cases=list(CASES) if plan['cases']=='all' else plan['cases'].split(',');assert cases and all(name in CASES for name in cases)
    private=root/'generated-identities';private.mkdir();identities={name:generated_identity(private,name,name=='client') for name in ('client','server','changed')}
    results=[]
    try:
        for name in cases:
            row=run_case(plan,root,name,identities,args.qt_bin);results.append(row)
            print(json.dumps({'case':name,'passed':row['passed'],'expected':row['expectedOutcome'],'actual':row['report'].get('outcome') if row['report'] else None,'source':row['report'].get('source') if row['report'] else None,'requests':len(row['requests']),'exited':row['processExited']},ensure_ascii=False),flush=True)
    finally:
        for _,key,_ in identities.values():key.unlink(missing_ok=True)
    save(root/'result.json',{'scope':'Actual native CLI with newly generated identities and owned loopback HTTP/mTLS servers; no real peer, PIN, stream, system window or input','passed':all(r['passed'] for r in results),'engineSha256':plan['engineSha256'],'cases':results})
    return 0 if all(row['passed'] for row in results) else 1
if __name__=='__main__':raise SystemExit(main())
