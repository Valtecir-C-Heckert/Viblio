import copy,concurrent.futures,gzip,json,uuid
import pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.personal_library import merge_snapshot,compact
from test_api import DSN,TOKEN,AUTH
@pytest.fixture
def api(tmp_path):
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as client:yield client
def headers(user):return {'Authorization':'Bearer '+user['token']}
_ADMIN_SESSION=None
def admin(api):
    global _ADMIN_SESSION
    if _ADMIN_SESSION is not None and api.get('/v1/me',headers=headers(_ADMIN_SESSION)).status_code==200:
        return _ADMIN_SESSION
    body=dict(email='admin@example.test',password='test-password-123456')
    r=api.post('/v1/auth/bootstrap',headers=AUTH,json=body)
    if r.status_code==409:r=api.post('/v1/auth/login',json=body)
    assert r.status_code==200,r.text
    _ADMIN_SESSION=r.json()
    return _ADMIN_SESSION
def invited(api,owner):
    code=api.post('/v1/admin/invitations',headers=headers(owner),json={}).json()['code']
    r=api.post('/v1/auth/register',json=dict(email=uuid.uuid4().hex+'@example.test',password='guest-password-123456',invitation=code))
    assert r.status_code==200,r.text
    return r.json()
def snapshot():return {'library':{'schemaVersion':6,'currentBookId':'book','books':[dict(id='book',kind='book',title='Livro',author='Autor',totalPages=100,currentPage=0,coverData='',rating=None,archived=False)],'collections':[],'sessions':[],'readingDates':[]},'appearance':{'SHELF_MANUAL_ORDER:book':'["book"]'}}
def sync(api,user,data,base=0,**kwargs):
    identifier=kwargs.pop("request_id",str(uuid.uuid4()))
    raw=gzip.compress(json.dumps(dict(request_id=identifier,base_revision=base,snapshot=data,**kwargs)).encode())
    return api.post('/v1/me/library/sync',headers=headers(user),content=raw)
def result(r):
    assert r.status_code==200,r.text
    return json.loads(gzip.decompress(r.content))
def test_bootstrap_and_single_use_invitation(api):
    owner=admin(api)
    assert api.post('/v1/auth/bootstrap',headers=AUTH,json=dict(email='other@example.test',password='password-123456')).status_code==409
    assert api.post('/v1/auth/register',json=dict(email='a@example.test',password='password-123456')).status_code==422
    invite=api.post('/v1/admin/invitations',headers=headers(owner),json={}).json()
    body=dict(email=uuid.uuid4().hex+'@example.test',password='password-123456',invitation=invite['code'])
    with concurrent.futures.ThreadPoolExecutor(2) as pool:codes=list(pool.map(lambda _:api.post('/v1/auth/register',json=body).status_code,range(2)))
    assert sorted(codes)==[200,400]
    assert all('token_hash' not in r for r in api.get('/v1/admin/invitations',headers=headers(owner)).json()['items'])
def test_roles_recovery_and_revoked_sessions(api):
    owner=admin(api);user=invited(api,owner)
    assert not api.get('/v1/me',headers=headers(user)).json()['admin']
    assert api.get('/v1/admin/users',headers=headers(user)).status_code==403
    assert api.get('/v1/books?q=sabio',headers=headers(user)).status_code==200
    assert api.post('/v1/books',headers=headers(user)).status_code==401
    code=api.post('/v1/admin/users/'+user['user']['id']+'/recovery',headers=headers(owner)).json()['code']
    body=dict(code=code,password='new-password-123456')
    fresh=api.post('/v1/auth/reset-password',json=body);assert fresh.status_code==200
    assert api.get('/v1/me',headers=headers(user)).status_code==401
    assert api.post('/v1/auth/reset-password',json=body).status_code==400
    assert api.post('/v1/auth/logout',headers=headers(fresh.json())).status_code==200
    assert api.get('/v1/me',headers=headers(fresh.json())).status_code==401

def test_private_libraries_merge_archive_and_backup(api):
    owner=admin(api);a=invited(api,owner);b=invited(api,owner)
    data=snapshot();identifier=str(uuid.uuid4())
    assert result(sync(api,a,data,request_id=identifier))['revision']==1
    assert result(sync(api,a,data,request_id=identifier))['revision']==1
    reused=copy.deepcopy(data);reused['library']['books'][0]['title']='Alterado'
    assert sync(api,a,reused,request_id=identifier).status_code==409
    assert api.get('/v1/me/library',headers=headers(b)).status_code==404
    assert api.get('/v1/me/library',headers=AUTH).status_code==401
    server=copy.deepcopy(data);server['library']['books'][0]['rating']=4.5
    result(sync(api,a,server,1));phone=copy.deepcopy(data);phone['library']['books'][0]['currentPage']=25
    merged=result(sync(api,a,phone,1));book=merged['snapshot']['library']['books'][0];assert book['rating']==4.5 and book['currentPage']==25
    book['archived']=True;result(sync(api,a,merged['snapshot'],3))
    older=api.get('/v1/me/library?revision=1',headers=headers(a));assert json.loads(gzip.decompress(older.content))['library']['books'][0]['rating'] is None
    assert api.get('/v1/me/library/status',headers=headers(b)).json()['revision']==0

def test_same_field_conflict_is_explicit_and_revision_bound(api):
    a=invited(api,admin(api));original=snapshot();result(sync(api,a,original))
    server=copy.deepcopy(original);server['library']['books'][0]['currentPage']=30;result(sync(api,a,server,1))
    phone=copy.deepcopy(original);phone['library']['books'][0]['currentPage']=50
    conflict=sync(api,a,phone,1);assert conflict.status_code==409
    detail=conflict.json()['detail'];path=detail['conflicts'][0]['path'];assert path.endswith('/currentPage')
    assert sync(api,a,phone,1,resolutions={path:'local'},against_revision=1).status_code==409
    assert result(sync(api,a,phone,1,resolutions={path:'local'},against_revision=2))['snapshot']['library']['books'][0]['currentPage']==50

def test_validation_throttle_and_revoked_invitation(api):
    owner=admin(api);a=invited(api,owner);bad=snapshot();bad['library']['books'][0]['currentPage']=101
    assert sync(api,a,bad).status_code==422
    bad=snapshot();bad['appearance']['SERVER_TOKEN']='secret';assert sync(api,a,bad).status_code==422
    bad=snapshot();bad['appearance']['BOOK_SECRET']='secret';assert sync(api,a,bad).status_code==422
    invite=api.post('/v1/admin/invitations',headers=headers(owner),json={}).json();api.delete('/v1/admin/invitations/'+invite['id'],headers=headers(owner))
    assert api.post('/v1/auth/register',json=dict(email='revoked@example.test',password='password-123456',invitation=invite['code'])).status_code==400
    email=uuid.uuid4().hex+'@example.test'
    statuses=[api.post('/v1/auth/login',json=dict(email=email,password='wrong-password-123456')).status_code for _ in range(13)]
    assert statuses[:12]==[401]*12 and statuses[-1]==429

def test_retention_compact_bases_and_automatic_stale_rejection(api):
    a=invited(api,admin(api));original=snapshot();result(sync(api,a,original));local=copy.deepcopy(original);local['library']['books'][0]['author']='Outro'
    for base in range(1,13):
        data=copy.deepcopy(original);data['library']['books'][0]['currentPage']=base
        result(sync(api,a,data,base))
    assert sync(api,a,local,1,strict_base=True).status_code==409
    final=result(sync(api,a,local,1));assert final['snapshot']['library']['books'][0]['currentPage']==12
    status=api.get('/v1/me/library/status',headers=headers(a)).json();assert len(status['versions'])==10
    assert api.get('/v1/me/library?revision=1',headers=headers(a)).status_code==404

def test_archived_flag_merges_with_progress_from_offline_device():
    original=snapshot();local=copy.deepcopy(original);server=copy.deepcopy(original)
    server['library']['books'][0]['archived']=True;local['library']['books'][0]['currentPage']=12
    merged,conflicts=merge_snapshot(original,local,server)
    assert not conflicts and merged['library']['books'][0]['archived'] and merged['library']['books'][0]['currentPage']==12

def test_compact_cover_bases_merge_without_image_loss():
    original=snapshot();original['library']['books'][0]['coverData']='data:image/jpeg;base64,old'
    local=copy.deepcopy(original);local['library']['books'][0]['title']='Nome pessoal'
    server=copy.deepcopy(original);server['library']['books'][0]['coverData']='data:image/jpeg;base64,new'
    merged,conflicts=merge_snapshot(compact(original),local,server)
    assert not conflicts and merged['library']['books'][0]['coverData'].endswith(',new') and merged['library']['books'][0]['title']=='Nome pessoal'

def test_duplicate_reading_day_is_a_set():
    original=snapshot();local=copy.deepcopy(original);server=copy.deepcopy(original)
    local['library']['readingDates']=[dict(id='a',bookId='book',day='2026-10-17')];server['library']['readingDates']=[dict(id='b',bookId='book',day='2026-10-17')]
    merged,conflicts=merge_snapshot(original,local,server)
    assert not conflicts and len(merged['library']['readingDates'])==1

def test_existing_account_chooses_unique_username_and_logs_in_by_either(api):
    owner=admin(api);a=invited(api,owner);b=invited(api,owner)
    info=api.get("/v1/auth/info").json();assert info["account_api"]==1 and info["username_login"]
    before=a['user']['id']
    saved=api.patch('/v1/me',headers=headers(a),json={'username':'Reader.One'})
    assert saved.status_code==200 and saved.json()['username']=='reader.one' and saved.json()['id']==before
    assert api.patch('/v1/me',headers=headers(b),json={'username':'READER.ONE'}).status_code==409
    for identity in ('READER.ONE',a['user']['email']):
        entered=api.post('/v1/auth/login',json={'identifier':identity,'password':'guest-password-123456'})
        assert entered.status_code==200 and entered.json()['user']['id']==before
    assert api.post('/v1/auth/login',json={'identifier':'reader.one','password':'wrong-password-123456'}).status_code==401
    assert api.get('/v1/me',headers=headers(b)).json()['username'] is None
    assert api.patch('/v1/me',headers=headers(a),json={'username':'bad@name'}).status_code==422

def test_invited_signup_username_validation_and_case_collision(api):
    owner=admin(api)
    code=api.post('/v1/admin/invitations',headers=headers(owner),json={}).json()['code']
    body=dict(email='named-'+uuid.uuid4().hex+'@example.test',username='named_'+uuid.uuid4().hex[:12],password='guest-password-123456',invitation=code)
    created=api.post('/v1/auth/register',json=body)
    assert created.status_code==200 and created.json()['user']['username']==body['username']
    code2=api.post('/v1/admin/invitations',headers=headers(owner),json={}).json()['code']
    body.update(email='collision-'+uuid.uuid4().hex+'@example.test',username=body['username'].upper(),invitation=code2)
    assert api.post('/v1/auth/register',json=body).status_code==409
    body['username']='valid_'+uuid.uuid4().hex[:12]
    assert api.post('/v1/auth/register',json=body).status_code==200 # Collision did not consume the invite.

def inventory_snapshot(numbers=(1,),expected=3):
    data=snapshot();data['library']['currentBookId']=None
    data['library']['collections']=[dict(id='series',kind='manga',title='Mangá',expectedVolumes=expected,missingVolumes=999,totalVolumes=999,obtainedVolumes=999,readVolumes=999)]
    data['library']['books']=[dict(id=f'v{i}-{n}',kind='manga',title='Volume',totalPages=None,pagesKnown=False,currentPage=0,sequenceNumber=n,collectionId='series',coverData='',archived=False) for i,n in enumerate(numbers)]
    return data

def test_collection_counts_are_derived_and_unknown_is_preserved(api):
    user=invited(api,admin(api));data=inventory_snapshot((1,1,3,9))
    merged=result(sync(api,user,data))['snapshot'];title=merged['library']['collections'][0]
    assert title['expectedVolumes']==3 and title['missingVolumes']==1 and title['obtainedVolumes']==4
    unknown=inventory_snapshot((1,),None)
    unknown=result(sync(api,user,unknown,1))['snapshot']['library']['collections'][0]
    assert unknown['expectedVolumes'] is None and unknown['missingVolumes'] is None

def test_collection_inventory_combines_offline_volume_additions_without_counter_conflicts(api):
    user=invited(api,admin(api));data=inventory_snapshot((1,));result(sync(api,user,data))
    remote=copy.deepcopy(data);remote['library']['books'].append(dict(remote['library']['books'][0],id='remote-two',sequenceNumber=2))
    result(sync(api,user,remote,1))
    local=copy.deepcopy(data);local['library']['books'].append(dict(local['library']['books'][0],id='local-three',sequenceNumber=3))
    merged=result(sync(api,user,local,1))['snapshot']
    assert len(merged['library']['books'])==3 and merged['library']['collections'][0]['missingVolumes']==0
    assert merged['library']['collections'][0]['expectedVolumes']==3

def test_collection_inventory_rejects_invalid_total_and_preserves_real_total_conflicts(api):
    user=invited(api,admin(api))
    malformed=inventory_snapshot();malformed['library']['books'][0]['readFinished']='true'
    assert sync(api,user,malformed).status_code==422
    for invalid in (-1,0,True,10001,'three'):
        assert sync(api,user,inventory_snapshot(expected=invalid)).status_code==422
    original=inventory_snapshot();result(sync(api,user,original))
    remote=copy.deepcopy(original);remote['library']['collections'][0]['expectedVolumes']=4;result(sync(api,user,remote,1))
    local=copy.deepcopy(original);local['library']['collections'][0]['expectedVolumes']=5
    conflict=sync(api,user,local,1)
    assert conflict.status_code==409
    assert any(r['path'].endswith('/expectedVolumes') for r in conflict.json()['detail']['conflicts'])


def test_private_goal_preferences_sync_and_conflict(api):
    from test_friends import setup
    a,b,_=setup(api)
    key='SHELF_READING_GOAL:'+str(uuid.uuid4());other='SHELF_READING_GOAL:'+str(uuid.uuid4())
    goal=json.dumps({'v':1,'name':'Private purchase cycle','target':3,'cycles':[]})
    original=snapshot();original['appearance'][key]=goal
    assert result(sync(api,a,original))['snapshot']['appearance'][key]==goal
    assert api.get('/v1/me/library',headers=headers(b)).status_code==404
    remote=copy.deepcopy(original);remote['appearance'][key]=goal.replace('"target": 3','"target": 5');result(sync(api,a,remote,1))
    local=copy.deepcopy(original);local['appearance'][other]=goal
    merged=result(sync(api,a,local,1))
    assert merged['snapshot']['appearance'][key]==remote['appearance'][key] and merged['snapshot']['appearance'][other]==goal
    stale=copy.deepcopy(original);stale['appearance'][key]=goal.replace('"target": 3','"target": 7')
    conflict=sync(api,a,stale,1);assert conflict.status_code==409
    assert any(c['path'].endswith(key) for c in conflict.json()['detail']['conflicts'])
    current=json.loads(gzip.decompress(api.get('/v1/me/library',headers=headers(a)).content))
    assert current['appearance'][key]==remote['appearance'][key]


def test_legacy_zoom_removal_unblocks_page_without_resolving_book_conflicts(api):
    from test_friends import setup, accept
    owner,reader,_=setup(api);accept(api,owner,reader)
    original=snapshot();original['appearance']['SHELF_SPINE_ZOOM']='1.0'
    result(sync(api,owner,original))
    server=copy.deepcopy(original);server['appearance']['SHELF_SPINE_ZOOM']='1.15'
    result(sync(api,owner,server,1))
    local=copy.deepcopy(original);local['appearance'].pop('SHELF_SPINE_ZOOM');local['library']['books'][0]['currentPage']=61
    conflict=sync(api,owner,local,1);assert conflict.status_code==409
    detail=conflict.json()['detail'];path='/appearance/SHELF_SPINE_ZOOM'
    assert [c['path'] for c in detail['conflicts']]==[path]
    combined=result(sync(api,owner,local,1,resolutions={path:'local'},against_revision=detail['revision']))
    assert combined['snapshot']['library']['books'][0]['currentPage']==61
    assert 'SHELF_SPINE_ZOOM' not in combined['snapshot']['appearance']
    shared=api.get('/v1/friends',headers=headers(reader)).json()['items'][0]
    assert shared['current']['page']==61


def test_zoom_cleanup_cannot_silently_choose_a_conflicting_page(api):
    from test_friends import setup
    owner,_,_=setup(api)
    original=snapshot();original['appearance']['SHELF_SPINE_ZOOM']='1.0';result(sync(api,owner,original))
    server=copy.deepcopy(original);server['appearance']['SHELF_SPINE_ZOOM']='1.15';server['library']['books'][0]['currentPage']=40;result(sync(api,owner,server,1))
    local=copy.deepcopy(original);local['appearance'].pop('SHELF_SPINE_ZOOM');local['library']['books'][0]['currentPage']=61
    response=sync(api,owner,local,1,resolutions={'/appearance/SHELF_SPINE_ZOOM':'local'},against_revision=2)
    assert response.status_code==409
    assert any(c['path']=='/library/books/book/currentPage' for c in response.json()['detail']['conflicts'])
    current=json.loads(gzip.decompress(api.get('/v1/me/library',headers=headers(owner)).content))
    assert current['library']['books'][0]['currentPage']==40

def _jpeg_data(size=(20,40)):
    import base64,io
    from PIL import Image
    buffer=io.BytesIO();Image.new('RGB',size,(120,40,30)).save(buffer,'JPEG')
    return 'data:image/jpeg;base64,'+base64.b64encode(buffer.getvalue()).decode()

def test_spine_photo_syncs_and_invalid_spine_is_rejected(api):
    a=invited(api,admin(api));data=snapshot();spine=_jpeg_data()
    data['library']['books'][0]['spineData']=spine
    synced=result(sync(api,a,data));assert synced['snapshot']['library']['books'][0]['spineData']==spine
    stored=api.get('/v1/me/library',headers=headers(a));assert json.loads(gzip.decompress(stored.content))['library']['books'][0]['spineData']==spine
    for invalid in ('data:image/png;base64,AAAA','data:image/jpeg;base64,not-base64!',12,_jpeg_data((2049,10))):
        bad=snapshot();bad['library']['books'][0]['spineData']=invalid
        assert sync(api,a,bad).status_code==422
    titled=inventory_snapshot();titled['library']['collections'][0]['spineData']=spine
    assert sync(api,a,titled).status_code==422

def test_compact_spine_bases_merge_without_image_loss():
    original=snapshot();original['library']['books'][0]['spineData']='data:image/jpeg;base64,old'
    local=copy.deepcopy(original);local['library']['books'][0]['currentPage']=40
    server=copy.deepcopy(original);server['library']['books'][0]['spineData']='data:image/jpeg;base64,new'
    base=compact(original);assert base['library']['books'][0]['spineData'].keys()=={'_cover_hash'}
    merged,conflicts=merge_snapshot(base,local,server)
    assert not conflicts and merged['library']['books'][0]['spineData'].endswith(',new') and merged['library']['books'][0]['currentPage']==40

def _jpeg_bytes(size=(40,60),color=(90,40,120)):
    import io
    from PIL import Image
    buf=io.BytesIO();Image.new('RGB',size,color).save(buf,'JPEG',quality=90)
    return buf.getvalue()

def test_media_endpoints_sync_isolation_and_backwards_compatibility(api):
    import base64,hashlib
    owner=admin(api);a=invited(api,owner);b=invited(api,owner)
    assert api.get('/v1/me/library/status',headers=headers(a)).json()['media'] is True
    cover_bytes=_jpeg_bytes((80,120),(180,40,40));cover_sha=hashlib.sha256(cover_bytes).hexdigest()
    spine_bytes=_jpeg_bytes((24,140),(30,80,160));spine_sha=hashlib.sha256(spine_bytes).hexdigest()

    # Check missing -> both missing
    chk=api.post('/v1/me/media/check',headers=headers(a),json={'hashes':[cover_sha,spine_sha]})
    assert chk.status_code==200 and chk.json()['missing']==[cover_sha,spine_sha]

    # Syncing before uploading media -> 409 missing_media
    data=snapshot()
    data['library']['books'][0]['coverData']='media:'+cover_sha
    data['library']['books'][0]['spineData']='media:'+spine_sha
    media_headers={**headers(a),'X-Viblio-Media':'1'}
    raw=gzip.compress(json.dumps(dict(request_id=str(uuid.uuid4()),base_revision=0,snapshot=data)).encode())
    r_missing=api.post('/v1/me/library/sync',headers=media_headers,content=raw)
    assert r_missing.status_code==409 and r_missing.json()['detail']['code']=='missing_media'

    # Reject mismatched hash, non-JPEG and oversized image
    assert api.put('/v1/me/media/'+cover_sha,headers=headers(a),content=spine_bytes).status_code==422
    bad_png=b'\x89PNG\r\n\x1a\nxxxx';bad_sha=hashlib.sha256(bad_png).hexdigest()
    assert api.put('/v1/me/media/'+bad_sha,headers=headers(a),content=bad_png).status_code==422
    huge=_jpeg_bytes((2049,20));huge_sha=hashlib.sha256(huge).hexdigest()
    assert api.put('/v1/me/media/'+huge_sha,headers=headers(a),content=huge).status_code==422

    # Upload cover & spine cleanly, verify check returns empty and isolation against user b
    assert api.put('/v1/me/media/'+cover_sha,headers=headers(a),content=cover_bytes).status_code==200
    assert api.put('/v1/me/media/'+spine_sha,headers=headers(a),content=spine_bytes).status_code==200
    assert api.post('/v1/me/media/check',headers=headers(a),json={'hashes':[cover_sha,spine_sha]}).json()['missing']==[]
    dl=api.get('/v1/me/media/'+cover_sha,headers=headers(a))
    assert dl.status_code==200 and dl.content==cover_bytes and 'immutable' in dl.headers.get('cache-control','')
    assert api.get('/v1/me/media/'+cover_sha,headers=headers(b)).status_code==404

    # Sync with X-Viblio-Media: 1 keeps compact media:<sha256> refs
    raw2=gzip.compress(json.dumps(dict(request_id=str(uuid.uuid4()),base_revision=0,snapshot=data)).encode())
    synced=result(api.post('/v1/me/library/sync',headers=media_headers,content=raw2))
    assert synced['revision']==1
    assert synced['snapshot']['library']['books'][0]['coverData']=='media:'+cover_sha
    assert synced['snapshot']['library']['books'][0]['spineData']=='media:'+spine_sha

    # Compact download for 0.8.0+ clients vs hydrated Base64 download for legacy <= 0.7.4 clients
    compact_dl=json.loads(gzip.decompress(api.get('/v1/me/library',headers=media_headers).content))
    assert compact_dl['library']['books'][0]['coverData']=='media:'+cover_sha
    legacy_dl=json.loads(gzip.decompress(api.get('/v1/me/library',headers=headers(a)).content))
    expected_cover_uri='data:image/jpeg;base64,'+base64.b64encode(cover_bytes).decode('ascii')
    assert legacy_dl['library']['books'][0]['coverData']==expected_cover_uri

def test_legacy_cover_hash_merges_with_media_ref_without_false_conflict(tmp_path):
    import base64,hashlib
    from app.personal_library import store_media_bytes
    mdir=tmp_path/'media';mdir.mkdir()
    jpeg=_jpeg_bytes((40,60),(10,120,40))
    sha=store_media_bytes(mdir,jpeg)
    uri='data:image/jpeg;base64,'+base64.b64encode(jpeg).decode('ascii')
    original=snapshot();original['library']['books'][0]['coverData']=uri
    base=compact(original)
    local=copy.deepcopy(original);local['library']['books'][0]['coverData']='media:'+sha;local['library']['books'][0]['currentPage']=33
    server=copy.deepcopy(original);server['library']['books'][0]['coverData']='media:'+sha;server['library']['books'][0]['rating']=4.5
    merged,conflicts=merge_snapshot(base,local,server,media_dir=mdir)
    assert not conflicts
    assert merged['library']['books'][0]['coverData']=='media:'+sha
    assert merged['library']['books'][0]['currentPage']==33 and merged['library']['books'][0]['rating']==4.5
