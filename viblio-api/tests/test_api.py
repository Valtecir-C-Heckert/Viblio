import concurrent.futures
import io
import json
import os
from pathlib import Path
import uuid
import psycopg
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from app.main import create_app, MAX_BODY

DSN=os.environ['VIBLIO_TEST_DATABASE_URL']
from psycopg.conninfo import conninfo_to_dict
if not conninfo_to_dict(DSN).get('dbname','').startswith('viblio_test'):
    raise RuntimeError('Tests require a separate database named viblio_test…; never use the personal database.')
TOKEN='test-only-'+'a'*40
AUTH={'Authorization':'Bearer '+TOKEN}
def prepare_database():
    with psycopg.connect(DSN,autocommit=True) as db:
        db.execute(Path('tests/schema.sql').read_text())
        db.execute("INSERT INTO valteca(id,titulo,autor,editora,metricas_totais,metrica_tipo,ordem,isbn,capa_url) VALUES (9007199254740993,'O Temor do Sábio','Patrick Rothfuss','Arqueiro',992,'páginas',2,'9788599296493','https://example.org/cover.jpg')")
        db.execute(Path('migrations/001_api.sql').read_text())
        for statement in Path('migrations/002_search.sql').read_text().split(';'):
            if statement.strip(): db.execute(statement)
        db.execute(Path('migrations/001_api.sql').read_text())
        db.execute(Path('migrations/003_cover_variants.sql').read_text())
        db.execute(Path('migrations/003_cover_variants.sql').read_text())
        db.execute(Path('migrations/004_accounts.sql').read_text())
        db.execute(Path('migrations/004_accounts.sql').read_text())
        db.execute(Path('migrations/005_usernames.sql').read_text())
        db.execute(Path('migrations/007_friends.sql').read_text())
        db.execute(Path('migrations/008_activity_feed.sql').read_text())
        db.execute(Path('migrations/009_book_clubs.sql').read_text())
        db.execute(Path('migrations/010_club_home.sql').read_text())
        db.execute(Path('migrations/011_automatic_activity.sql').read_text())
        db.execute(Path('migrations/012_club_redesign.sql').read_text())
        db.execute(Path('migrations/013_wishlist.sql').read_text())
        db.execute(Path('migrations/015_feed_added.sql').read_text())
        db.execute(Path('migrations/017_feed_reactions.sql').read_text())
        for _ in range(2):
            for statement in Path('migrations/006_cover_groups.sql').read_text().split(';'):
                if statement.strip():db.execute(statement)
            for statement in Path('migrations/014_recommendation_indexes.sql').read_text().split(';'):
                if statement.strip():db.execute(statement)
            for statement in Path('migrations/016_search_saga_index.sql').read_text().split(';'):
                if statement.strip():db.execute(statement)
        db.execute(Path('migrations/005_usernames.sql').read_text())
        db.execute(Path('migrations/007_friends.sql').read_text())
        db.execute(Path('migrations/008_activity_feed.sql').read_text())
        db.execute(Path('migrations/009_book_clubs.sql').read_text())
        db.execute(Path('migrations/010_club_home.sql').read_text())
        db.execute(Path('migrations/011_automatic_activity.sql').read_text())
        db.execute(Path('migrations/012_club_redesign.sql').read_text())
        db.execute(Path('migrations/013_wishlist.sql').read_text())
        db.execute(Path('migrations/015_feed_added.sql').read_text())
        db.execute(Path('migrations/017_feed_reactions.sql').read_text())
        assert db.execute("SELECT titulo FROM valteca WHERE id=9007199254740993").fetchone()[0]=='O Temor do Sábio'
@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as api: yield api,tmp_path

def payload(**kwargs): return dict(request_id=str(uuid.uuid4()),title='Novo '+uuid.uuid4().hex,**kwargs)
def write(api,data,image=None):
    files={'metadata':(None,json.dumps(data))}
    if image is not None: files['cover']=('../../evil.png',image,'image/png')
    return api.post('/v1/books',headers=AUTH,files=files)
def png():
    out=io.BytesIO();Image.new('RGB',(32,48),'red').save(out,'PNG');return out.getvalue()

def test_authentication_required(client):
    api,_=client
    assert api.get('/v1/books?q=sabio').status_code==401
    assert api.get('/v1/health',headers={'Authorization':'Bearer wrong'}).status_code==401
    assert api.post('/v1/books',files={'metadata':(None,json.dumps(payload()))}).status_code==401
    assert api.get('/v1/health',headers=AUTH).json()['ok']
def test_accent_case_author_isbn_and_bigint(client):
    api,_=client
    body=api.get('/v1/books',headers=AUTH,params={'q':'SABIO','author':'rothfuss'}).json()
    assert body['items'][0]['id']=='9007199254740993'
    assert body['items'][0]['pages']==992 and body['items'][0]['sequence']==2
    assert body['items'][0]['genre'] is None
    assert api.get('/v1/books',headers=AUTH,params={'isbn':'978-85-99296-49-3'}).json()['items'][0]['title']=='O Temor do Sábio'
    assert api.get('/v1/books',headers=AUTH).json()['items']==[]
    assert api.get('/v1/books?q=ab',headers=AUTH).status_code==422
    assert api.get('/v1/books?limit=100',headers=AUTH).status_code==422
    assert api.get('/v1/books',headers=AUTH,params={'q':"Sábio' OR 1=1 --"}).json()['items']==[]
def test_unknown_metrics_are_not_invented(client):
    api,_=client
    with psycopg.connect(DSN) as db: db.execute("INSERT INTO valteca(titulo,metricas_totais,metrica_tipo,ordem) VALUES ('Sem paginas',30,'volumes',1.5)")
    book=api.get('/v1/books?q=Sem%20paginas',headers=AUTH).json()['items'][0]
    assert book['pages'] is None and book['sequence'] is None
def test_pagination_and_literal_wildcard(client):
    api,_=client;stem='Page'+uuid.uuid4().hex
    for i in range(4): write(api,payload(author='Same',publisher=str(i))|{'title':stem})
    first=api.get('/v1/books',headers=AUTH,params={'q':stem,'limit':2}).json()
    second=api.get('/v1/books',headers=AUTH,params={'q':stem,'limit':2,'cursor':first['next_cursor']}).json()
    assert len(first['items'])==len(second['items'])==2
    assert not set(x['id'] for x in first['items']) & set(x['id'] for x in second['items'])
    assert second['next_cursor'] is None
    assert api.get('/v1/books',headers=AUTH,params={'q':'%%%'}).json()['items']==[]
def test_create_cover_safe_storage_and_retry(client):
    api,storage=client;data=payload(author='Autor',pages=328,subgenres=['Dark','Épica'])
    first=write(api,data,png());assert first.status_code==200,first.text
    row=first.json();assert row['book']['pages']==328
    filename=uuid.UUID(data['request_id']).hex+'.jpg';file=storage/filename
    assert row['book']['cover_url'].endswith('/covers/'+filename)
    assert file.is_file() and Image.open(file).format=='JPEG'
    assert api.get('/covers/'+filename).status_code==200
    assert api.get('/covers/..%2Fcatalog-id').status_code==404
    assert write(api,data,png()).json()['book']['id']==row['book']['id']
    assert write(api,data|{'title':'changed'},png()).status_code==409
    assert write(api,payload()|{'title':data['title'],'author':'Autor','pages':328},png()).json()['book']['id']==row['book']['id']
def test_personal_override_does_not_mutate_existing_catalog(client):
    api,_=client
    response=write(api,payload(catalog_id='9007199254740993',pages=10)|{'title':'Meu nome local'},png())
    assert response.status_code==200 and response.json()['uploaded_url']
    assert response.json()['book']['title']=='O Temor do Sábio'
    assert response.json()['book']['cover_url']=='https://example.org/cover.jpg'
def test_invalid_files_and_metadata_rejected_without_rows(client):
    api,storage=client
    for content in (b'<svg><script/></svg>',b'not an image',b''): assert write(api,payload(),content).status_code==422
    assert write(api,payload()|{'title':'   '}).status_code==422
    assert write(api,payload()|{'pages':100001}).status_code==422
    assert write(api,payload()|{'catalog_id':'9223372036854775808'}).status_code==422
    assert write(api,payload()|{'catalog_id':'-123456789'}).status_code==404
    assert api.post('/v1/books',headers=AUTH|{'Content-Length':str(MAX_BODY+1)},content=b'').status_code==413
    assert list(storage.glob('*.jpg'))==[]
def test_isbn_duplicate_and_different_editions(client):
    api,_=client
    assert write(api,payload(isbn='978-85-99296-49-3')).json()['book']['id']=='9007199254740993'
    a=write(api,payload(isbn='9780000000001')|{'title':'Same edition title','author':'A'}).json()['book']['id']
    b=write(api,payload(isbn='9780000000002')|{'title':'Same edition title','author':'A'}).json()['book']['id']
    assert a!=b
def test_concurrent_creates_resolve_one_book(client):
    api,_=client;title='Concurrent '+uuid.uuid4().hex
    def save(_): return write(api,payload()|{'title':title,'author':'Author'}).json()['book']['id']
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: ids=list(pool.map(save,range(4)))
    assert len(set(ids))==1

def test_same_title_known_pages_and_sequence_are_distinct_editions(client):
    api,_=client;title='Volumes '+uuid.uuid4().hex
    def save(pages,sequence): return write(api,payload(author='Author',pages=pages,sequence=sequence)|{'title':title}).json()['book']['id']
    first=save(100,1);second=save(100,2);third=save(200,2)
    assert len({first,second,third})==3
    assert save(100,1)==first

def test_gallery_keeps_primary_and_deduplicates_alternatives(client):
    api,_=client
    def colored(color):
        out=io.BytesIO();Image.new('RGB',(32,48),color).save(out,'PNG');return out.getvalue()
    first=write(api,payload(pages=100),colored('white')).json()
    book_id=first['book']['id'];primary=first['book']['cover_url']
    for color in ['red','blue','blue']:
        response=write(api,payload(catalog_id=book_id)|{'title':'Local title'},colored(color))
        assert response.status_code==200,response.text
        assert response.json()['book']['cover_url']==primary
        assert response.json()['book']['title']==first['book']['title']
    gallery=api.get(f'/v1/books/{book_id}/covers',headers=AUTH).json()
    assert gallery['book_id']==book_id and len(gallery['items'])==3
    assert {key:gallery['items'][0][key] for key in ('id','url','primary')}=={'id':'primary','url':primary,'primary':True}
    assert len({cover['url'] for cover in gallery['items']})==3
    for item in gallery['items'][1:]: assert api.get('/covers/'+item['url'].split('/')[-1]).status_code==200
    with psycopg.connect(DSN) as db:
        assert db.execute('SELECT count(*) FROM valteca WHERE titulo=%s',(first['book']['title'],)).fetchone()[0]==1

def test_gallery_auth_ranges_empty_and_migration_backfills_old_uploads(client):
    api,_=client;data=payload()
    first=write(api,data).json();book_id=first['book']['id']
    assert api.get(f'/v1/books/{book_id}/covers').status_code==401
    assert api.get('/v1/books/999999999999999999999999999/covers',headers=AUTH).status_code==422
    assert api.get('/v1/books/-123456789/covers',headers=AUTH).status_code==404
    assert api.get(f'/v1/books/{book_id}/covers',headers=AUTH).json()['items']==[]
    upload=payload(catalog_id=book_id)|{'title':'Local cover'}
    result=write(api,upload,png()).json()
    with psycopg.connect(DSN) as db: db.execute('DELETE FROM viblio_book_covers WHERE book_id=%s',(int(book_id),))
    with psycopg.connect(DSN,autocommit=True) as db:
        db.execute(Path('migrations/003_cover_variants.sql').read_text());db.execute(Path('migrations/003_cover_variants.sql').read_text())
    gallery=api.get(f'/v1/books/{book_id}/covers',headers=AUTH).json()['items']
    assert len(gallery)==1 and gallery[0]['url']==result['uploaded_url'] and not gallery[0]['primary']
    assert write(api,upload,png()).json()==result

def test_distinct_numbered_volumes_do_not_share_alternative_covers(client):
    api,_=client;title='Series '+uuid.uuid4().hex
    records=[]
    for number in [1,2]:
        response=write(api,payload(kind='manga',is_volume=True,sequence=number)|{'title':title},png())
        assert response.status_code==200,response.text
        records.append(response.json()['book'])
    assert records[0]['id']!=records[1]['id']
    assert records[0]['category']=='Mangá' and records[1]['sequence']==2
    assert write(api,payload(kind='manga',is_volume=True,sequence=1)|{'title':title},png()).json()['book']['id']==records[0]['id']
    assert write(api,payload(is_volume=True)|{'title':'No volume number'},png()).status_code==422

def test_gallery_groups_same_work_editions_and_deduplicates_urls(client):
    api,_=client;stem='Nineteen '+uuid.uuid4().hex
    with psycopg.connect(DSN) as db:
        ids=[]
        for i in range(6):
            ids.append(db.execute('INSERT INTO valteca(titulo,autor,editora,capa_url,isbn,metricas_totais,metrica_tipo) VALUES (%s,%s,%s,%s,%s,%s,%s) RETURNING id',(stem if i%2==0 else '  '+stem.upper()+'  ','George Orwell',f'Editora {i}',f'https://example.test/edition-{i}.jpg',str(9780000000000+i),200+i,'páginas')).fetchone()[0])
        duplicate=db.execute('INSERT INTO valteca(titulo,autor,capa_url) VALUES (%s,%s,%s) RETURNING id',(stem,' George  Orwell ','https://example.test/edition-1.jpg')).fetchone()[0]
        db.execute('INSERT INTO valteca(titulo,autor,capa_url) VALUES (%s,%s,%s)',(stem,'Outro autor','https://example.test/wrong-author.jpg'))
        db.execute('INSERT INTO valteca(titulo,autor,capa_url,ordem) VALUES (%s,%s,%s,2)',(stem,'George Orwell','https://example.test/wrong-volume.jpg'))
        db.execute('INSERT INTO valteca(titulo,autor,capa_url,tipo) VALUES (%s,%s,%s,%s)',(stem,'George Orwell','https://example.test/wrong-kind.jpg','manga'))
    page=api.get(f'/v1/books/{ids[0]}/covers?limit=2',headers=AUTH).json()
    items=page['items'];assert page['has_more'] and page['next_cursor']==2
    while page['next_cursor'] is not None:
        page=api.get(f'/v1/books/{ids[0]}/covers',headers=AUTH,params={'limit':2,'cursor':page['next_cursor']}).json();items+=page['items']
    assert len(items)==6 and len({x['url'] for x in items})==6
    assert items[0]['primary'] and items[0]['book_id']==str(ids[0])
    assert all(x['publisher'].startswith('Editora') for x in items)
    assert items[1]['isbn'] and items[1]['pages']
    with psycopg.connect(DSN) as db:assert db.execute('SELECT count(*) FROM valteca WHERE id=ANY(%s)',(ids+[duplicate],)).fetchone()[0]==7
    assert api.get(f'/v1/books/{ids[0]}/covers?limit=51',headers=AUTH).status_code==422
    assert api.get(f'/v1/books/{ids[0]}/covers?cursor=-1',headers=AUTH).status_code==422

def test_gallery_work_ids_and_unknown_authors_are_conservative(client):
    api,_=client;stem=uuid.uuid4().hex
    with psycopg.connect(DSN) as db:
        def add(title,author,work,url):return db.execute('INSERT INTO valteca(titulo,autor,work_key,capa_url) VALUES (%s,%s,%s,%s) RETURNING id',(title,author,work,url)).fetchone()[0]
        base=add(stem,'Writer','work-a','https://example.test/a.jpg')
        add(stem,'Writer','work-b','https://example.test/wrong-work.jpg')
        add('Translated '+stem,'Writer','work-a','https://example.test/translation.jpg')
        add(stem,'Writer',None,'https://example.test/no-work-id.jpg')
        unknown=add('Unknown '+stem,None,None,'https://example.test/unknown-a.jpg')
        add('Unknown '+stem,None,None,'https://example.test/unknown-b.jpg')
    rows=api.get(f'/v1/books/{base}/covers',headers=AUTH).json()['items']
    assert {r['url'] for r in rows}=={'https://example.test/a.jpg','https://example.test/translation.jpg','https://example.test/no-work-id.jpg'}
    assert len(api.get(f'/v1/books/{unknown}/covers',headers=AUTH).json()['items'])==1

def test_uploaded_gallery_alias_is_inside_public_tunnel_and_read_only(client):
    api,_=client;first=write(api,payload(),png()).json();book=first['book'];name=book['cover_url'].split('/')[-1]
    assert '/v1/covers/' in book['cover_url']
    assert api.get('/v1/covers/'+name).status_code==200
    assert api.get('/covers/'+name).status_code==200
    before=api.get('/v1/books',headers=AUTH,params={'q':book['title']}).json()['items'][0]
    api.get('/v1/books/'+book['id']+'/covers',headers=AUTH)
    after=api.get('/v1/books',headers=AUTH,params={'q':book['title']}).json()['items'][0]
    assert before==after


def test_search_exposes_known_legacy_upload_under_public_api_without_rewriting_database(client):
    api,_=client;book=write(api,payload(),png()).json()['book'];name=book['cover_url'].split('/')[-1]
    legacy='http://192.0.2.10:8088/covers/'+name
    with psycopg.connect(DSN) as db:db.execute('UPDATE valteca SET capa_url=%s WHERE id=%s',(legacy,int(book['id'])))
    response=api.get('/v1/books',headers=AUTH,params={'q':book['title']}).json()['items'][0]
    assert response['cover_url']=='http://localhost:8088/v1/covers/'+name
    assert api.get('/v1/covers/'+name).status_code==200
    with psycopg.connect(DSN) as db:assert db.execute('SELECT capa_url FROM valteca WHERE id=%s',(int(book['id']),)).fetchone()[0]==legacy


def test_cover_group_indexes_accept_long_imported_metadata(client):
    api,_=client;title=os.urandom(3000).hex();author=os.urandom(3000).hex()
    with psycopg.connect(DSN) as db:
        ids=[db.execute('INSERT INTO valteca(titulo,autor,capa_url) VALUES (%s,%s,%s) RETURNING id',(title,author,'https://example.test/long-'+str(i)+'.jpg')).fetchone()[0] for i in range(2)]
    assert len(api.get(f'/v1/books/{ids[0]}/covers',headers=AUTH).json()['items'])==2
