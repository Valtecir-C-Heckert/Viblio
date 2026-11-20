"""Updater tests without personal servers, credentials, sudo or real Docker mutations."""
import importlib.util
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('viblio_updater', Path(__file__).parents[1] / 'scripts' / 'atualizar-viblio.py')
u = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = u
spec.loader.exec_module(u)

class UpdaterChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.api = self.root / 'api';self.api.mkdir()
        (self.api / '.env').write_text('private-configuration')
        (self.api / 'app').mkdir();(self.api / 'app' / 'main.py').write_text('old')
        (self.api / 'compose.api.yaml').write_text('old-compose')
        self.staged = self.root / 'stage' / 'package';self.staged.mkdir(parents=True)
        (self.staged / 'app').mkdir();(self.staged / 'app' / 'main.py').write_text('new')
        (self.staged / 'compose.api.yaml').write_text('new-compose')
        (self.staged / 'migrations').mkdir();(self.staged / 'migrations' / '006_test.sql').write_text('SELECT 1;')
        self.config = u.Config(api=self.api, backups=self.root/'backups', nextcloud=self.root/'nextcloud')
    def tearDown(self):self.temp.cleanup()
    def package(self,extra=()):
        path=self.root/'viblio-api-0.2.2.zip'
        with zipfile.ZipFile(path,'w') as z:
            for name in ['app/main.py','Dockerfile','compose.api.yaml','requirements.lock','migrations/005_usernames.sql']:
                z.writestr(name,'content')
            for name,data in extra:z.writestr(name,data)
        return path
    def test_valid_package_is_staged_and_configuration_is_untouched(self):
        files=u.unpack(self.package(),self.root/'extracted')
        self.assertEqual([p.name for p in files],['005_usernames.sql'])
        self.assertEqual((self.api/'.env').read_text(),'private-configuration')
        self.assertEqual((self.root/'extracted/app/main.py').stat().st_mode & 0o777,0o600)
    def test_zip_rejects_traversal_credentials_absolute_and_windows_paths(self):
        for name in ['../escape','/tmp/escape','app/../../escape','app/.env','app\\escape','app//extra','migrations/bad.sql']:
            with self.subTest(name=name),self.assertRaises(u.UpdateError):
                u.unpack(self.package([(name,'bad')]),self.root/'out')
        self.assertFalse((self.root/'escape').exists())
    def test_zip_rejects_symlinks(self):
        path=self.package()
        with zipfile.ZipFile(path,'a') as z:
            info=zipfile.ZipInfo('app/link');info.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(info,'/etc/passwd')
        with self.assertRaises(u.UpdateError):u.unpack(path,self.root/'out')
    def test_zip_rejects_duplicate_and_missing_required_files(self):
        with self.assertWarns(UserWarning):path=self.package([('app/main.py','duplicate')])
        with self.assertRaises(u.UpdateError):u.unpack(path,self.root/'out')
        with zipfile.ZipFile(path,'w') as z:z.writestr('README.md','empty')
        with self.assertRaises(u.UpdateError):u.unpack(path,self.root/'out')
    def test_nextcloud_chooses_numeric_latest_and_accepts_explicit_version(self):
        self.config.nextcloud.mkdir()
        for name in ('viblio-api-0.2.9.zip','viblio-api-0.2.10.zip','other.zip'):
            (self.config.nextcloud/name).write_text('fixture')
        updater=u.Updater(self.config)
        self.assertEqual(updater.discover().name,'viblio-api-0.2.10.zip')
        self.assertEqual(updater.discover('0.2.9').name,'viblio-api-0.2.9.zip')
    def test_duplicate_latest_zip_requires_explicit_path(self):
        self.config.nextcloud.mkdir()
        (self.config.nextcloud/'viblio-api-0.2.2.zip').write_text('fixture')
        nested=self.config.nextcloud/'Documents';nested.mkdir()
        (nested/'viblio-api-0.2.2.zip').write_text('fixture')
        with self.assertRaises(u.UpdateError):u.Updater(self.config).discover()
    def test_checksum_cannot_match_other_filename_or_conflicting_entries(self):
        digest='a'*64
        self.assertEqual(u.checksum(digest+'  viblio-api-0.2.2.zip','viblio-api-0.2.2.zip'),digest)
        for text in [digest+'  wrong.zip',(digest+'  viblio-api-0.2.2.zip\n')*2,'bad  viblio-api-0.2.2.zip']:
            with self.assertRaises(u.UpdateError):u.checksum(text,'viblio-api-0.2.2.zip')
    def test_first_update_refuses_an_older_package_than_existing_guides(self):
        (self.api/'UPDATE_0_2_2.md').write_text('existing installation')
        (self.api/'UPDATE_0_1_8.md').write_text('old guide')
        self.assertEqual(u.legacy_floor(self.api), (0,2,2))
        self.assertLess(u.version('0.2.1'),u.legacy_floor(self.api))
    def test_zip_rejects_unbounded_alternative_compression(self):
        path=self.package()
        with zipfile.ZipFile(path,'a') as z:z.writestr('app/extra',b'data',compress_type=zipfile.ZIP_BZIP2)
        with self.assertRaises(u.UpdateError):u.unpack(path,self.root/'out')
    def test_version_sort_is_numeric(self):
        self.assertGreater(u.version('0.2.10'),u.version('0.2.9'))
        with self.assertRaises(u.UpdateError):u.version('0.2.2;rm')
    def test_ledger_skips_applied_migrations_and_refuses_changed_history(self):
        updater=u.Updater(self.config);path=self.staged/'migrations/006_test.sql'
        with patch.object(updater,'ledger',return_value={path.name:u.sha(path)}):self.assertEqual(updater.pending([path]),[])
        with patch.object(updater,'ledger',return_value={path.name:'a'*64}),self.assertRaises(u.UpdateError):updater.pending([path])
        with patch.object(updater,'ledger',return_value={'005_removed.sql':'a'*64}),self.assertRaises(u.UpdateError):updater.pending([path])
    def test_failed_promotion_can_restore_previous_code_without_touching_env(self):
        updater=u.Updater(self.config);updater.promote(self.staged)
        self.assertEqual((self.api/'app/main.py').read_text(),'new')
        updater.stopped=True;updater.backup=self.root/'backup';updater.backup.mkdir();updater.old_image='old-image'
        with patch.object(updater,'compose') as compose,patch.object(updater,'wait_healthy'):
            updater.rollback()
            self.assertIn('up',compose.call_args.args)
        self.assertEqual((self.api/'app/main.py').read_text(),'old')
        self.assertEqual((self.api/'.env').read_text(),'private-configuration')
        self.assertFalse((self.api/'migrations').exists())
    def test_symbolic_destination_is_not_followed(self):
        (self.api/'app').rename(self.root/'outside')
        (self.api/'app').symlink_to(self.root/'outside',target_is_directory=True)
        updater=u.Updater(self.config)
        with self.assertRaises(u.UpdateError):updater.promote(self.staged)
        self.assertEqual((self.root/'outside/main.py').read_text(),'old')
    def test_failed_build_does_not_stop_api(self):
        updater=u.Updater(self.config)
        def compose(*args,**kwargs):
            if args[:2]==('ps','-q'):return 'existing'
            if args[0]=='build':raise u.UpdateError('build failed')
        with patch.object(updater,'check_space'),patch.object(updater,'identity',return_value='catalog-id'),patch.object(updater,'run',return_value='old-image'),patch.object(updater,'compose',side_effect=compose) as calls:
            with self.assertRaises(u.UpdateError):updater.deploy(self.staged,[],'0.2.2','a'*64)
        self.assertFalse(any(c.args[0]=='stop' for c in calls.call_args_list))
        self.assertEqual((self.api/'app/main.py').read_text(),'old')
    def test_failed_stop_attempts_to_start_old_api(self):
        updater=u.Updater(self.config)
        def compose(*args,**kwargs):
            if args[:2]==('ps','-q'):return 'existing'
            if args[0]=='stop':raise u.UpdateError('stop failed')
        with patch.object(updater,'check_space'),patch.object(updater,'identity',return_value='catalog-id'),patch.object(updater,'run',return_value='old-image'),patch.object(updater,'compose',side_effect=compose) as calls,patch.object(updater,'wait_healthy'):
            with self.assertRaises(u.UpdateError):updater.deploy(self.staged,[],'0.2.2','a'*64)
        self.assertTrue(any(c.args[0]=='start' for c in calls.call_args_list))
    def test_copy_is_bounded_before_filling_tmp(self):
        source=self.root/'source.zip';source.write_bytes(b'12345')
        with patch.object(u,'MAX_ZIP',4),self.assertRaises(u.UpdateError):u.Updater(self.config).copy_package(source,self.root/'copy.zip')
    def test_copy_rejects_symlink_source(self):
        real=self.root/'real.zip';real.write_bytes(b'12345')
        link=self.root/'link.zip';link.symlink_to(real)
        with self.assertRaises(u.UpdateError):u.Updater(self.config).copy_package(link,self.root/'copy.zip')
    def test_streaming_unpack_enforces_real_expanded_limit(self):
        path=self.package([('app/big.txt','x'*4096)])
        # Even if MAX_EXPANDED is tiny relative to real decompressed bytes, streaming check catches it.
        with patch.object(u,'MAX_EXPANDED',2048),self.assertRaises(u.UpdateError):
            u.unpack(path,self.root/'out_big')
    def test_https_only_redirect_handler_blocks_downgrade(self):
        handler=u._HttpsOnlyRedirectHandler()
        with self.assertRaises(u.UpdateError):
            handler.redirect_request(None,None,302,'Found',{},'http://example.org/SHA256SUMS')

if __name__=='__main__':unittest.main()
