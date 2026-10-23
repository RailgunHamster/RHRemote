"""Check that generating the 443 plan cannot silently change unrelated routes."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('relay443', Path(__file__).with_name('prepare-relay-443-plan.py'))
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


class Relay443PlanTest(unittest.TestCase):
    def original(self):
        return ('http {\n    map $http_upgrade $connection_upgrade { default upgrade; }\n'
                '    server {\n        listen 443 ssl;\n        server_name  192.0.2.1;\n'
                + plan.OLD_CERT + plan.ANCHOR
                + '        ssl_ciphers ECDHE-RSA-AES256-GCM-SHA384;\n'
                + '        location / { proxy_pass http://siyuan:6806; }\n'
                + '        location ~* ^/(ws|api/ws)(/.*)?$ { proxy_pass http://siyuan:6806; }\n    }\n}\n')

    def generate(self, value):
        return plan.prepare(value, '172.18.0.1', '192.0.2.1', 'a' * 64)

    def test_only_reserved_routes_and_certificate_reference_change(self):
        original = self.original()
        files = self.generate(original)
        addition = plan.routes('172.18.0.1')
        restored = files['https-443.nginx.conf'].replace(addition, '').replace(plan.CERT_INCLUDE, plan.OLD_CERT)
        upgraded = 'ssl_ciphers ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384;'
        self.assertEqual(restored.replace(upgraded, 'ssl_ciphers ECDHE-RSA-AES256-GCM-SHA384;'), original)
        self.assertIn(plan.OLD_CERT, files['routes-only.nginx.conf'])
        for path in ('/relay', '/derp', '/ping', '/generate_204'):
            self.assertIn(f'location = {path} ', addition)
        self.assertIn('location ^~ /rhremote/', addition)
        self.assertNotIn('47990', addition)
        self.assertIn('proxy_set_header Upgrade $http_upgrade;', addition)
        self.assertIn('proxy_pass http://172.18.0.1:33448/;', addition)

    def test_duplicate_changed_or_already_managed_layout_requires_review(self):
        original = self.original()
        for changed in (original.replace(plan.ANCHOR, ''), original + plan.ANCHOR,
                        original.replace('listen 443 ssl;', 'listen 444 ssl;'),
                        original + plan.BEGIN, original.replace('siyuan.key', 'new.key')):
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError):
                    self.generate(changed)

    def test_gateway_and_certificate_input_cannot_inject_directives(self):
        for gateway in ('172.18.0.1; include x;', '127.0.0.1', '101.43.203.147', '172.18.0.1:47990'):
            with self.subTest(gateway=gateway):
                with self.assertRaises(ValueError):
                    plan.prepare(self.original(), gateway, '192.0.2.1', 'a' * 64)
        with self.assertRaises(ValueError):
            plan.prepare(self.original(), '172.18.0.1', '192.0.2.1', '../key')

    def test_existing_ecdsa_suites_are_preserved_without_duplicates(self):
        original = self.original().replace('ssl_ciphers ECDHE-RSA-', 'ssl_ciphers ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-')
        value = self.generate(original)['https-443.nginx.conf']
        self.assertEqual(value.count('ECDHE-ECDSA-AES256-GCM-SHA384'), 1)
        with self.assertRaises(ValueError):
            self.generate(self.original().replace('ssl_ciphers ECDHE-RSA-AES256-GCM-SHA384;', ''))


if __name__ == '__main__':
    unittest.main()
