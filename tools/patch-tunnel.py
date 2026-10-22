"""Reproducible native socket adaptation; keeps Sunshine on the client usable."""
import argparse
from pathlib import Path

MARKER = '// RHREMOTE_TUNNEL_PATCH'

def edit(root, relative, change):
    path = root / relative
    text = path.read_text(encoding='utf-8')
    backup = root / '.rhremote-transport-originals' / relative
    if MARKER in text:
        if not backup.exists():
            raise RuntimeError(f'tunnel original missing: {relative}')
        text = backup.read_text(encoding='utf-8')
    else:
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_text(text, encoding='utf-8', newline='\n')
    text = change(text)
    path.write_text(MARKER + '\n' + text, encoding='utf-8', newline='\n')

def replace(text, before, after):
    if before not in text:
        raise RuntimeError(f'tunnel patch anchor missing: {before[:100]}')
    return text.replace(before, after)

def sockets(text):
    helper = r'''
#include <stdlib.h>
// Only the application's loopback proxy gets remapped. Local receiver binds,
// direct connections and other processes retain their original ports.
static inline unsigned short rhTunnelPort(const void* raw, unsigned short port) {
    const struct sockaddr_in* address = (const struct sockaddr_in*)raw;
    const char* value;
    int offset;
    if (address->sin_family != AF_INET || ntohl(address->sin_addr.s_addr) != 0x7f4d0002U)
        return port;
    switch (port) {
    case 47984: case 47989: case 47995: case 47998: case 47999:
    case 48000: case 48002: case 48010: break;
    default: return port;
    }
    value = getenv("RHREMOTE_TUNNEL_PORT_OFFSET");
    offset = value ? atoi(value) : 0;
    return offset > 0 && offset <= 17525 ? (unsigned short)(port + offset) : port;
}
'''
    text = replace(text, '#ifdef AF_INET6\ntypedef struct sockaddr_in6 LC_SOCKADDR;', helper + '\n#ifdef AF_INET6\ntypedef struct sockaddr_in6 LC_SOCKADDR;')
    text = replace(text, 'htons(port))', 'htons(rhTunnelPort(addr, port)))')
    return text

def windows(root):
    edit(root, 'moonlight-common-c/moonlight-common-c/src/PlatformSockets.h', sockets)
    def http(text):
        text = replace(text, 'void NvHTTP::setAddress(NvAddress address)\n{', 'void NvHTTP::setAddress(NvAddress address)\n{\n    if(address.address()=="127.77.0.2"&&address.port()==47989)address.setPort(47989+qEnvironmentVariableIntValue("RHREMOTE_TUNNEL_PORT_OFFSET"));')
        text = replace(text, 'void NvHTTP::setHttpsPort(uint16_t port)\n{', 'void NvHTTP::setHttpsPort(uint16_t port)\n{\n    if(m_BaseUrlHttp.host()=="127.77.0.2"&&port==47984)port+=qEnvironmentVariableIntValue("RHREMOTE_TUNNEL_PORT_OFFSET");')
        return text
    edit(root, 'app/backend/nvhttp.cpp', http)

def android(root):
    edit(root, 'app/src/main/jni/moonlight-core/moonlight-common-c/src/PlatformSockets.h', sockets)
    def http(text):
        text = replace(text, '.port(httpsPort).build()', '.port(com.limelight.rhremote.RhTunnel.port(baseUrlHttp.host(),httpsPort)).build()')
        return text
    edit(root, 'app/src/main/java/com/limelight/nvstream/http/NvHTTP.java', http)

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('platform',choices=['windows','android']);parser.add_argument('root',type=Path)
    args=parser.parse_args();globals()[args.platform](args.root)
