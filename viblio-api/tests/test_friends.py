import base64
import concurrent.futures
import copy
import io
import uuid
import pytest
import psycopg
from test_api import DSN
from app.accounts import digest
from PIL import Image
from test_accounts import admin, invited, headers, snapshot, sync, result
from test_accounts import api


def person(api,owner):
    user=invited(api,owner)
    name='reader_'+uuid.uuid4().hex[:12]
    response=api.patch('/v1/me',headers=headers(user),json={'username':name})
    assert response.status_code==200,response.text
    user['user']=response.json()
    return user

def setup(api):
    identifier=uuid.uuid4();token=uuid.uuid4().hex+uuid.uuid4().hex
    with psycopg.connect(DSN) as db:
        db.execute("INSERT INTO public.viblio_users(id,email,password_hash,is_admin,username) VALUES (%s,%s,'unused-test-hash',true,%s)",(identifier,str(identifier)+'@example.test','owner_'+identifier.hex[:12]))
        db.execute('INSERT INTO public.viblio_sessions(token_hash,user_id) VALUES (%s,%s)',(digest(token),identifier))
    owner={'token':token}
    return person(api,owner),person(api,owner),person(api,owner)

def add(api,a,b):
    return api.post('/v1/friends/requests',headers=headers(a),json={'username':b['user']['username']})

def accept(api,a,b):
    assert add(api,a,b).status_code==200
    response=api.post('/v1/friends/'+a['user']['id']+'/accept',headers=headers(b))
    assert response.status_code==200,response.text

def art():
    data=io.BytesIO();Image.new('RGB',(32,48),'red').save(data,'JPEG')
    return 'data:image/jpeg;base64,'+base64.b64encode(data.getvalue()).decode()

def data():
    s=snapshot();s['library']['books'][0].update(title='Private title',author='Author',currentPage=7,coverData=art(),rating=4.5,notes='NEVER SHARE',background='wine')
    s['appearance']['genre_color:private']='NEVER SHARE'
    return s

def test_consent_identity_and_read_only_projection(api):
    a,b,c=setup(api);result(sync(api,b,data()))
    path='/v1/friends/'+b['user']['id']+'/library'
    assert api.get(path,headers=headers(a)).status_code==404
    assert add(api,a,b).status_code==200
    assert api.get(path,headers=headers(a)).status_code==404
    assert api.post('/v1/friends/'+b['user']['id']+'/accept',headers=headers(a)).status_code==404
    pending=api.get('/v1/friends',headers=headers(b)).json()['requests']
    assert any(r['id']==a['user']['id'] and r['incoming'] for r in pending)
    accept(api,a,b)
    shared=api.get(path,headers=headers(a));assert shared.status_code==200,shared.text
    assert len(shared.json()['items'])==1
    book=shared.json()['items'][0]
    assert book['title']=='Private title' and book['page']==7 and book['cover'].startswith('/v1/friends/')
    for forbidden in ('rating','notes','sessions','appearance','email','coverData','snapshot','NEVER SHARE'):
        assert forbidden not in shared.text
    assert api.get(path,headers=headers(c)).status_code==404
    assert api.get(path).status_code==401
    assert api.post(path,headers=headers(a),json={'title':'Hacked'}).status_code==405
    assert api.get(path,headers=headers(a)).headers['cache-control']=='no-store'

def test_current_cover_is_authenticated_and_revocation_covers_cached_images(api):
    a,b,c=setup(api);result(sync(api,b,data()));accept(api,a,b)
    response=api.get('/v1/friends',headers=headers(a));assert response.status_code==200,response.text
    friend=next(f for f in response.json()['items'] if f['id']==b['user']['id'])
    assert friend['current']['title']=='Private title' and friend['current']['page']==7
    assert friend['current']['pages']==100
    for forbidden in ('email','notes','rating','sessions','coverData','NEVER SHARE'):assert forbidden not in response.text
    cover=friend['current']['cover'];image=api.get(cover,headers=headers(a))
    assert image.status_code==200,image.text
    assert image.headers['content-type']=='image/jpeg' and image.headers['cache-control']=='no-store'
    with Image.open(io.BytesIO(image.content)) as decoded:assert decoded.size==(32,48)
    assert api.get(cover).status_code==401
    assert api.get(cover,headers=headers(c)).status_code==404
    assert api.delete('/v1/friends/'+b['user']['id'],headers=headers(c)).status_code==200
    assert api.get(cover,headers=headers(a)).status_code==200
    assert api.delete('/v1/friends/'+b['user']['id'],headers=headers(a)).status_code==200
    assert api.get(cover,headers=headers(a)).status_code==404
    assert api.get('/v1/friends/'+b['user']['id']+'/library',headers=headers(a)).status_code==404

def test_archive_and_revision_do_not_allow_old_covers(api):
    a,b,_=setup(api);s=data();result(sync(api,b,s));accept(api,a,b)
    first=api.get('/v1/friends',headers=headers(a)).json()['items'][0]['current']['cover']
    assert api.get(first,headers=headers(a)).status_code==200
    s['library']['books'][0]['archived']=True;result(sync(api,b,s,base=1))
    assert api.get(first,headers=headers(a)).status_code==404
    shared=api.get('/v1/friends/'+b['user']['id']+'/library',headers=headers(a)).json()
    assert shared['items']==[]
    assert api.get('/v1/friends',headers=headers(a)).json()['items'][0]['current'] is None

def test_collections_filters_and_pagination(api):
    a,b,_=setup(api);s=snapshot();s['library']['currentBookId']=None
    books=s['library']['books'];books.clear()
    for n in range(45):books.append(dict(id='b'+str(n),title='Book '+str(n).zfill(2),kind='book',totalPages=100,currentPage=0,coverData=''))
    s['library']['currentBookId']='b0'
    s['library']['collections']=[dict(id='title',title='Manga collection',kind='manga',coverData=art(),author='Mangaka')]
    books.append(dict(id='volume',title='Manga',kind='manga',collectionId='title',sequenceNumber=1,totalPages=100,currentPage=10,coverData=''))
    result(sync(api,b,s));accept(api,a,b)
    path='/v1/friends/'+b['user']['id']+'/library'
    one=api.get(path,headers=headers(a)).json();two=api.get(path+'?cursor=40&revision=1',headers=headers(a)).json()
    assert len(one['items'])==40 and len(two['items'])==6
    assert all(item['cover']=='' for item in one['items'])
    assert api.get('/v1/friends',headers=headers(a)).json()['items'][0]['current']['cover']==''
    assert not ({x['id'] for x in one['items']} & {x['id'] for x in two['items']})
    title=api.get(path+'?kind=manga',headers=headers(a)).json()['items'][0]
    assert title['id']=='title' and title['volumes']==1 and title['cover']
    assert 'book_id=title' in title['cover']
    assert api.get(title['cover'],headers=headers(a)).status_code==200
    volume=api.get(path+'?collection_id=title',headers=headers(a)).json()['items'][0]
    assert volume['id']=='volume'
    assert api.get(path+'?q=Book%2001',headers=headers(a)).json()['items'][0]['id']=='b1'
    assert api.get(path+'?revision=2',headers=headers(a)).status_code==409
    assert api.get(path+'?cursor=-1',headers=headers(a)).status_code==422
    s['library']['collections'][0]['archived']=True;s['library']['currentBookId']='volume'
    result(sync(api,b,s,base=1))
    assert api.get(path+'?kind=manga',headers=headers(a)).json()['items']==[]
    assert api.get('/v1/friends',headers=headers(a)).json()['items'][0]['current'] is None
    assert api.get(title['cover'].replace('revision=1','revision=2'),headers=headers(a)).status_code==404

def test_duplicates_reverse_requests_rejection_and_input_limits(api):
    a,b,_=setup(api)
    assert add(api,a,b).status_code==200 and add(api,a,b).status_code==200
    assert add(api,b,a).status_code==409
    assert api.delete('/v1/friends/'+a['user']['id'],headers=headers(b)).status_code==200
    assert api.get('/v1/friends',headers=headers(a)).json()['requests']==[]
    assert add(api,a,a).status_code==422
    for username in ('x'*33,'../admin','x@example.test'):
        assert api.post('/v1/friends/requests',headers=headers(a),json={'username':username}).status_code==422

def test_simultaneous_opposite_requests_have_single_pair(api):
    a,b,_=setup(api)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda users:add(api,*users).status_code,[(a,b),(b,a)]))
    assert sorted(results)==[200,409]
    assert len(api.get('/v1/friends',headers=headers(a)).json()['requests'])==1

def test_friend_writes_are_rate_limited(api):
    a,_,_=setup(api)
    codes=[api.post('/v1/friends/requests',headers=headers(a),json={'username':'nonexistentuser'}).status_code for _ in range(21)]
    assert codes[:20]==[404]*20 and codes[-1]==429


def test_reading_goals_and_purchase_receipts_are_private(api):
    a,b,_=setup(api);s=data()
    s['appearance']['SHELF_READING_GOAL:'+str(uuid.uuid4())]='{"name":"PRIVATE GOAL RECEIPT","cycles":["PRIVATE PURCHASE"]}'
    result(sync(api,b,s));accept(api,a,b)
    for path in ('/v1/friends','/v1/friends/'+b['user']['id']+'/library'):
        response=api.get(path,headers=headers(a));assert response.status_code==200
        assert 'PRIVATE GOAL' not in response.text and 'PRIVATE PURCHASE' not in response.text and 'SHELF_READING_GOAL' not in response.text
