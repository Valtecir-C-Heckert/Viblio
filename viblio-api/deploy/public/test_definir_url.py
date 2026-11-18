import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('public_url',Path(__file__).with_name('definir-url-publica.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class PublicUrlChecks(unittest.TestCase):
    def test_only_url_changes_and_private_backup_is_outside_build(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);api=root/'api';api.mkdir();env=api/'.env'
            original=b'DATABASE_URL=test-only\r\nPUBLIC_BASE_URL=http://192.0.2.10:8088\r\nVIBLIO_API_TOKEN=fixture-not-a-real-secret\r\n'
            env.write_bytes(original)
            backup=module.update(api,root/'backups','https://api.example.test/')
            self.assertEqual(backup.read_bytes(),original)
            self.assertEqual(env.read_bytes(),original.replace(b'http://192.0.2.10:8088',b'https://api.example.test'))
            self.assertEqual(env.stat().st_mode&0o777,0o600)
            self.assertEqual(backup.stat().st_mode&0o777,0o600)
            self.assertIsNone(module.update(api,root/'backups','https://api.example.test'))
            self.assertEqual(len(list((root/'backups').iterdir())),1)
    def test_invalid_origins(self):
        for value in ['http://api.example.test','https://name:password@api.example.test','https://api.example.test/v1','https://api.example.test?token=x','https://api.example.test#x','https://100.64.0.10','https://host.ts.net','https://host.local','https://api.example.test:8088','https://api.${DATABASE_URL}.test','https://@api.example.test','https://-bad.example.test','https://api.example.test;bad']:
            with self.subTest(value=value),self.assertRaises(ValueError):module.validate_url(value)
    def test_ambiguous_or_missing_key_and_backup_inside_api_leave_env_unchanged(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);api=root/'api';api.mkdir();env=api/'.env'
            for original in [b'OTHER=value\n',b'PUBLIC_BASE_URL=a\nPUBLIC_BASE_URL=b\n',b'PUBLIC_BASE_URL=a\nexport PUBLIC_BASE_URL=b\n']:
                env.write_bytes(original)
                with self.assertRaises(ValueError):module.update(api,root/'backups','https://api.example.test')
                self.assertEqual(env.read_bytes(),original)
            env.write_text('PUBLIC_BASE_URL=a\n')
            with self.assertRaises(ValueError):module.update(api,api/'backups','https://api.example.test')
    def test_shared_backup_directory_is_not_chmoded_or_used(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);api=root/'api';api.mkdir();env=api/'.env';env.write_text('PUBLIC_BASE_URL=a\n')
            shared=root/'shared';shared.mkdir();shared.chmod(0o755)
            with self.assertRaises(ValueError):module.update(api,shared,'https://api.example.test')
            self.assertEqual(shared.stat().st_mode&0o777,0o755)
            self.assertEqual(env.read_text(),'PUBLIC_BASE_URL=a\n')
    def test_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);api=root/'api';api.mkdir();other=root/'private';other.write_text('PUBLIC_BASE_URL=a\n');(api/'.env').symlink_to(other)
            with self.assertRaises(ValueError):module.update(api,root/'backup','https://api.example.test')
            self.assertEqual(other.read_text(),'PUBLIC_BASE_URL=a\n')
if __name__=='__main__':unittest.main()
