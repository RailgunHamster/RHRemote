"""Bounded loopback WebDAV fixture. No production credentials or external writes."""
import argparse, base64, hashlib, http.server, json, pathlib, time

p = argparse.ArgumentParser()
p.add_argument('--directory', type=pathlib.Path, required=True)
p.add_argument('--port', type=int, default=48760)
args = p.parse_args()
root = args.directory.resolve()
document = root / 'document.json'
authorization = 'Basic ' + base64.b64encode(('fixture:' + (root / 'password.txt').read_text().strip()).encode()).decode()

def etag():
    return '"' + hashlib.sha256(document.read_bytes()).hexdigest() + '"' if document.exists() else None

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, status, content=b''):
        self.send_response(status)
        self.send_header('Content-Length', str(len(content)))
        self.send_header('Content-Type', 'application/json')
        self.send_header('Connection', 'close')
        if document.exists():
            self.send_header('ETag', etag())
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        if self.path == '/health':
            return self.respond(200, b'{"ok":true}')
        if self.headers.get('Authorization') != authorization:
            return self.respond(401)
        if self.path != '/rhremote-config-v1.json':
            return self.respond(404)
        self.respond(200, document.read_bytes()) if document.exists() else self.respond(404)

    def do_PUT(self):
        if self.headers.get('Authorization') != authorization:
            return self.respond(401)
        if self.path != '/rhremote-config-v1.json':
            return self.respond(404)
        if (document.exists() and self.headers.get('If-Match') != etag()) or (not document.exists() and self.headers.get('If-None-Match') != '*'):
            return self.respond(412)
        length = int(self.headers.get('Content-Length', '-1'))
        if not 0 <= length <= 2 * 1024 * 1024:
            return self.respond(413)
        content = self.rfile.read(length)
        json.loads(content)
        pending = root / 'document.pending.json'
        pending.write_bytes(content)
        pending.replace(document)
        self.respond(204)

server = http.server.HTTPServer(('127.0.0.1', args.port), Handler)
server.timeout = 0.5
deadline = time.monotonic() + 900
while time.monotonic() < deadline and not (root / 'stop').exists():
    server.handle_request()
server.server_close()
