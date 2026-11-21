"""Private snapshots, content-addressed media (/v1/me/media), bounded images and optimistic three-way field merge."""
import base64,copy,gzip,hashlib,io,json,math,os,re,time,uuid
from pathlib import Path
from datetime import date
from fastapi import Depends,HTTPException,Request
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool
from PIL import Image

MAX_PACKED=24*1024*1024
MAX_SNAPSHOT=32*1024*1024
MAX_MEDIA_BYTES=12*1024*1024
MAX_MEDIA_FILES=10000
MAX_MEDIA_CHECK=500
MEDIA_GC_GRACE_SECONDS=24*60*60
SHA256_RE=re.compile(r'^[a-f0-9]{64}$')
MEDIA_RE=re.compile(r'^media:([a-f0-9]{64})$')
MISSING=object()
SECTIONS=('books','collections','sessions','readingDates')
IMAGE_FIELDS=('coverData','spineData')

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def checksum(value):return hashlib.sha256(canonical(value)).hexdigest()
MAX_JSON_DEPTH=12
def _reject_constant(token): raise ValueError(f'Invalid JSON constant: {token}')
def _check_depth(value, depth=0):
    if depth>MAX_JSON_DEPTH: raise ValueError('JSON nesting too deep')
    if isinstance(value, dict):
        for v in value.values():
            if isinstance(v, (dict, list)): _check_depth(v, depth+1)
    elif isinstance(value, list):
        for v in value:
            if isinstance(v, (dict, list)): _check_depth(v, depth+1)
def unpack(raw):
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:data=stream.read(MAX_SNAPSHOT+1)
        if len(data)>MAX_SNAPSHOT:raise HTTPException(413,'O backup completo deve ter até 32 MB descompactado.')
        parsed=json.loads(data, parse_constant=_reject_constant)
        _check_depth(parsed)
        return parsed
    except HTTPException:raise
    except (OSError,ValueError,EOFError,RecursionError):raise HTTPException(422,'Backup comprimido inválido.') from None

DERIVED_COUNTS={'missingVolumes','obtainedVolumes','readVolumes','totalVolumes'}
def collection_inventory(data):
    """Freeze legacy totals; derive ownership after merging all devices, never add counters."""
    data=copy.deepcopy(data)
    library=data.get('library',{})
    books=library.get('books',[])
    by_collection={}
    for book in books:by_collection.setdefault(book.get('collectionId'),[]).append(book)
    for title in library.get('collections',[]):
        related=by_collection.get(title['id'],[])
        owned=[b for b in related if not b.get('archived',False)]
        numbers={b.get('sequenceNumber') for b in owned if type(b.get('sequenceNumber')) is int}
        if 'expectedVolumes' in title:
            total=title['expectedVolumes']
        else:
            total=title.get('totalVolumes')
            if total is None and title.get('missingVolumes') is not None:
                keys={('number',b['sequenceNumber']) if b.get('sequenceNumber') is not None else ('id',b['id']) for b in related}
                missing=title['missingVolumes']
                if type(missing) is not int or not 0<=missing<=10000:raise HTTPException(422,'Quantidade faltante inválida.')
                total=missing+len(keys)
            if total==0:total=None
        if total is not None and (type(total) is not int or not 1<=total<=10000):raise HTTPException(422,'Total de volumes inválido.')
        title['expectedVolumes']=total
        title['totalVolumes']=total
        title['obtainedVolumes']=len(owned)
        title['readVolumes']=sum((b.get('readFinished',False) if not b.get('pagesKnown',True) else b.get('currentPage',0)==b.get('totalPages')) for b in owned)
        title['missingVolumes']=None if total is None else total-len({n for n in numbers if 1<=n<=total})
    return data

def validate_jpeg_bytes(pixels,label='Imagem'):
    if not isinstance(pixels,(bytes,bytearray)) or not 2<len(pixels)<=MAX_MEDIA_BYTES:
        raise HTTPException(422,f'{label} JPEG inválida.')
    try:
        with Image.open(io.BytesIO(pixels)) as image:
            if image.format!='JPEG' or max(image.size)>2048:raise ValueError()
            image.verify()
    except Exception:raise HTTPException(422,f'{label} JPEG inválida.') from None

def _validate_image_field(art,label):
    if not isinstance(art,str):raise HTTPException(422,f'{label} inválida.')
    if not art:return
    if MEDIA_RE.fullmatch(art):return
    try:
        if not art.startswith('data:image/jpeg;base64,') or len(art)>17*1024*1024:raise ValueError()
        pixels=base64.b64decode(art.split(',',1)[1],validate=True)
    except Exception:raise HTTPException(422,f'{label} JPEG inválida.') from None
    validate_jpeg_bytes(pixels,label)

def media_dir_for(root,user_id):
    d=Path(root)/str(user_id)/'media'
    d.mkdir(parents=True,mode=0o700,exist_ok=True)
    return d

def _media_quota_bytes():
    try:mb=int(os.environ.get('VIBLIO_USER_MEDIA_MAX_MB','1536'))
    except ValueError:mb=1536
    return max(64,min(mb,32768))*1024*1024

def store_media_bytes(media_dir,pixels,expected_sha=None,label='Imagem'):
    validate_jpeg_bytes(pixels,label)
    digest=hashlib.sha256(pixels).hexdigest()
    if expected_sha is not None and digest!=expected_sha:
        raise HTTPException(422,'Hash SHA-256 não confere com a imagem enviada.')
    target=media_dir/(digest+'.jpg')
    if target.is_file():return digest
    existing=[p for p in media_dir.iterdir() if p.is_file() and p.name.endswith('.jpg')]
    if len(existing)>=MAX_MEDIA_FILES:
        raise HTTPException(413,'Limite de imagens na conta atingido.')
    total_bytes=sum(p.stat().st_size for p in existing)
    if total_bytes+len(pixels)>_media_quota_bytes():
        raise HTTPException(413,'Cota de armazenamento de imagens da conta atingida.')
    tmp=media_dir/(digest+'.'+uuid.uuid4().hex[:8]+'.tmp')
    try:
        tmp.write_bytes(pixels);tmp.chmod(0o600);tmp.replace(target)
    finally:
        if tmp.exists():
            try:tmp.unlink()
            except OSError:pass
    return digest

def read_image_bytes(root,owner_id,art):
    """Resolve raw JPEG bytes from either 'media:<sha256>' or legacy 'data:image/jpeg;base64,...'."""
    if not isinstance(art,str) or not art:return None
    m=MEDIA_RE.fullmatch(art)
    if m:
        path=Path(root)/str(owner_id)/'media'/(m.group(1)+'.jpg')
        try:
            with path.open('rb') as f:raw=f.read(MAX_MEDIA_BYTES+1)
            return raw if 2<len(raw)<=MAX_MEDIA_BYTES else None
        except OSError:return None
    if art.startswith('data:image/jpeg;base64,') and len(art)<=17*1024*1024:
        try:
            raw=base64.b64decode(art.split(',',1)[1],validate=True)
            return raw if 2<len(raw)<=MAX_MEDIA_BYTES else None
        except ValueError:return None
    return None

def externalize_media(snapshot,media_dir):
    """Extract embedded data-URI JPEGs into media_dir and normalize fields to 'media:<sha256>'."""
    result=copy.deepcopy(snapshot)
    lib=result.get('library',{})
    for section in ('books','collections'):
        fields=IMAGE_FIELDS if section=='books' else ('coverData',)
        for row in lib.get(section,[]):
            for field in fields:
                val=row.get(field)
                if isinstance(val,str) and val.startswith('data:image/jpeg;base64,'):
                    pixels=base64.b64decode(val.split(',',1)[1],validate=True)
                    sha=store_media_bytes(media_dir,pixels,label='Capa' if field=='coverData' else 'Lombada')
                    row[field]='media:'+sha
    return result

def collect_media_hashes(snapshot):
    hashes=set()
    lib=snapshot.get('library',{})
    for section in ('books','collections'):
        for row in lib.get(section,[]):
            for field in IMAGE_FIELDS:
                val=row.get(field)
                if isinstance(val,str):
                    m=MEDIA_RE.fullmatch(val)
                    if m:hashes.add(m.group(1))
    return hashes

def verify_media_present(snapshot,media_dir):
    missing=sorted(h for h in collect_media_hashes(snapshot) if not (media_dir/(h+'.jpg')).is_file())
    if missing:
        raise HTTPException(409,dict(code='missing_media',missing=missing[:200],message='Envie as imagens ausentes antes de concluir a sincronização.'))

def hydrate_media(snapshot,media_dir):
    """Hydrate 'media:<sha256>' back into embedded data-URI JPEGs for legacy clients (< 0.8.0)."""
    result=copy.deepcopy(snapshot)
    lib=result.get('library',{})
    cache={}
    for section in ('books','collections'):
        for row in lib.get(section,[]):
            for field in IMAGE_FIELDS:
                val=row.get(field)
                if isinstance(val,str):
                    m=MEDIA_RE.fullmatch(val)
                    if m:
                        sha=m.group(1)
                        if sha not in cache:
                            p=media_dir/(sha+'.jpg')
                            cache[sha]='data:image/jpeg;base64,'+base64.b64encode(p.read_bytes()).decode('ascii') if p.is_file() else ''
                        row[field]=cache[sha]
    return result

def cleanup_unused_media(user_dir,kept_filenames):
    """Lazily remove orphan media files unreferenced by all kept revisions and older than 24h."""
    media_dir=user_dir/'media'
    if not media_dir.is_dir():return
    referenced=set()
    for fname in kept_filenames:
        try:
            snap=unpack((user_dir/fname).read_bytes())
            referenced.update(collect_media_hashes(snap))
        except Exception:
            # If any kept revision cannot be read, abort GC to avoid ever deleting live media.
            return
    cutoff=time.time()-MEDIA_GC_GRACE_SECONDS
    for item in media_dir.iterdir():
        if not item.is_file() or not item.name.endswith('.jpg'):continue
        sha=item.name[:-4]
        if sha in referenced:continue
        try:
            if item.stat().st_mtime<cutoff:item.unlink(missing_ok=True)
        except OSError:pass

def validate_snapshot(data):
    if not isinstance(data,dict) or set(data)-{'library','appearance'}:raise HTTPException(422,'Estrutura do acervo inválida.')
    library=data.get('library');appearance=data.get('appearance',{})
    if not isinstance(library,dict) or library.get('schemaVersion') not in (5,6) or not isinstance(appearance,dict):raise HTTPException(422,'Versão do acervo não suportada.')
    if len(appearance)>10000:raise HTTPException(422,'Preferências demais.')
    for key,value in appearance.items():
        if not re.match(r'^(SHELF_|BOOK_SHELF_FILTERS(?::|$)|genre_parents_migrated_v2$|STATISTICS_TYPE$|books_per_row$|shelf_columns:|collection_columns:|genre_color:|subgenre_vocabulary:)',key):raise HTTPException(422,'Preferência não permitida.')
        if type(value) in (int,float) and (not math.isfinite(value) or not -(2**31)<=value<2**31):raise HTTPException(422,'Preferência numérica inválida.')
        if not isinstance(value,(str,int,float,bool,list)) or isinstance(value,list) and any(not isinstance(v,str) for v in value):raise HTTPException(422,'Preferência inválida.')
    ids={}
    for section in SECTIONS:
        rows=library.get(section,[]);limit={'books':5000,'collections':5000,'sessions':20000,'readingDates':100000}[section]
        if not isinstance(rows,list) or len(rows)>limit:raise HTTPException(422,'Acervo acima dos limites de importação.')
        identifiers=set()
        for row in rows:
            if not isinstance(row,dict) or not isinstance(row.get('id'),str) or not 1<=len(row['id'])<=200 or row['id'] in identifiers:raise HTTPException(422,'Identificador duplicado ou inválido.')
            identifiers.add(row['id'])
            if section in ('books','collections'):
                if not isinstance(row.get('title'),str) or not row['title'].strip():raise HTTPException(422,'Título ausente.')
                for opt_str in ('author','genre','subgenre','saga','publisher','catalogSource'):
                    val=row.get(opt_str)
                    if val is not None and not isinstance(val,str):raise HTTPException(422,'Campo textual inválido.')
                if row.get('catalogId') is not None and not isinstance(row.get('catalogId'),str):raise HTTPException(422,'Identificador de catálogo inválido.')
                updated_at=row.get('updatedAt')
                if updated_at is not None and (type(updated_at) is not int or not 0<=updated_at<2**63):raise HTTPException(422,'Data de atualização inválida.')
                if row.get('kind','book') not in ('book','manga','comic'):raise HTTPException(422,'Tipo inválido.')
                if row.get('sequenceNumber') is not None and (type(row['sequenceNumber']) is not int or not 1<=row['sequenceNumber']<=10000):raise HTTPException(422,'Volume inválido.')
                if row.get('background','black') not in ('black','graphite','slate','warm','blue','wine','cover'):raise HTTPException(422,'Fundo inválido.')
                tags=row.get('subgenres',[])
                if not isinstance(tags,list) or len(tags)>3 or any(not isinstance(t,str) or not t.strip() for t in tags):raise HTTPException(422,'Subgêneros inválidos.')
                if type(row.get('archived',False)) is not bool:raise HTTPException(422,'Remoção inválida.')
                rating=row.get('rating')
                if rating is not None and (type(rating) not in (int,float) or not math.isfinite(rating) or not 0<=rating<=5 or rating*2!=int(rating*2)):raise HTTPException(422,'Nota inválida.')
                _validate_image_field(row.get('coverData',''),'Capa')
                if section=='books':_validate_image_field(row.get('spineData',''),'Lombada')
                elif 'spineData' in row:raise HTTPException(422,'Lombada permitida apenas em livros.')
        ids[section]=identifiers
    if ids['books'] & ids['collections']:raise HTTPException(422,'IDs de livros e títulos devem ser diferentes.')
    books={r['id']:r for r in library.get('books',[])};collections={r['id']:r for r in library.get('collections',[])}
    for book in books.values():
        known=book.get('pagesKnown',True);total=book.get('totalPages');page=book.get('currentPage',0);parent=book.get('collectionId','')
        if type(book.get('readFinished',False)) is not bool:raise HTTPException(422,'Estado de leitura inválido.')
        if type(known) is not bool or type(page) is not int or page<0:raise HTTPException(422,'Progresso inválido.')
        if known and (type(total) is not int or not 1<=total<=100000 or page>total):raise HTTPException(422,'Páginas inválidas.')
        if not isinstance(parent,str) or parent and (parent not in collections or collections[parent]['kind']!=book.get('kind','book')):raise HTTPException(422,'Volume sem título compatível.')
        if not known and (not parent or page!=0 or total is not None):raise HTTPException(422,'Volume sem páginas inválido.')
    seen=set()
    for section in ('sessions','readingDates'):
        for row in library.get(section,[]):
            if not isinstance(row.get('bookId'),str) or row['bookId'] not in books:raise HTTPException(422,'Histórico sem livro.')
            if section=='sessions':
                for key in ('startPage','endPage','durationMs','startedAt','finishedAt'):
                    if type(row.get(key)) is not int or not 0<=row[key]<2**63:raise HTTPException(422,'Sessão inválida.')
                if not row['startPage']<=row['endPage']<=(books[row['bookId']].get('totalPages') or 1) or row['finishedAt']<row['startedAt']:raise HTTPException(422,'Sessão inválida.')
                day=row.get('readingDay')
            else:
                day=row.get('day')
                if not isinstance(day,str) or (row['bookId'],day) in seen:raise HTTPException(422,'Data inválida ou duplicada.')
                seen.add((row['bookId'],day))
            if day is not None:
                try:date.fromisoformat(day)
                except (ValueError,TypeError):raise HTTPException(422,'Data inválida.') from None
    current=library.get('currentBookId')
    if current is not None and (not isinstance(current,str) or current not in books):raise HTTPException(422,'Livro atual ausente.')
    result=collection_inventory(data);result.setdefault('appearance',{})
    for section in SECTIONS:result['library'][section]=sorted(result['library'].get(section,[]),key=lambda r:r['id'])
    return result

def compact(value):
    if isinstance(value,dict):
        return {
            k:({'_cover_hash':hashlib.sha256(v.encode()).hexdigest()} if k in IMAGE_FIELDS and isinstance(v,str) and not MEDIA_RE.fullmatch(v) else compact(v))
            for k,v in value.items()
        }
    if isinstance(value,list):return [compact(v) for v in value]
    return value

def _legacy_data_uri_hash(media_dir,sha,cache):
    if sha in cache:return cache[sha]
    p=media_dir/(sha+'.jpg')
    if not p.is_file():
        cache[sha]=None;return None
    uri=('data:image/jpeg;base64,'+base64.b64encode(p.read_bytes()).decode('ascii')).encode()
    cache[sha]=hashlib.sha256(uri).hexdigest()
    return cache[sha]

def same(a,b,media_dir=None,_cache=None):
    if a is MISSING or b is MISSING:return a is b
    if isinstance(a,dict) and set(a)=={'_cover_hash'} and isinstance(b,str):
        m=MEDIA_RE.fullmatch(b)
        if m and media_dir is not None:
            return a['_cover_hash']==_legacy_data_uri_hash(media_dir,m.group(1),_cache if _cache is not None else {})
        return a['_cover_hash']==hashlib.sha256(b.encode()).hexdigest()
    if isinstance(b,dict) and set(b)=={'_cover_hash'} and isinstance(a,str):
        m=MEDIA_RE.fullmatch(a)
        if m and media_dir is not None:
            return b['_cover_hash']==_legacy_data_uri_hash(media_dir,m.group(1),_cache if _cache is not None else {})
        return b['_cover_hash']==hashlib.sha256(a.encode()).hexdigest()
    return compact(a)==compact(b)

def merge_snapshot(base,local,server,resolutions=None,media_dir=None):
    base,local,server=[collection_inventory(s) for s in (base,local,server)]
    conflicts=[];resolutions=resolutions or {};legacy_cache={}
    def eq(x,y):return same(x,y,media_dir,legacy_cache)
    def preview(v):
        if v is MISSING:return '(excluído)'
        if isinstance(v,str) and (v.startswith('data:image/') or MEDIA_RE.fullmatch(v)):return '(imagem)'
        if isinstance(v,dict):return str(v.get('title') or v.get('id') or '(registro)')[:180]
        return str(v)[:180]
    def merge(old,mine,theirs,path):
        if eq(mine,theirs):return mine
        if eq(mine,old):return theirs
        if eq(theirs,old):return mine
        if path.endswith('/updatedAt') and all(type(v) in (int,float) for v in (mine,theirs)):return max(mine,theirs)
        if all(isinstance(v,dict) for v in (old,mine,theirs)):
            result={}
            for key in sorted(set(old)|set(mine)|set(theirs)):
                if path.startswith('/library/collections/') and path.count('/')==3 and key in DERIVED_COUNTS:continue
                value=merge(old.get(key,MISSING),mine.get(key,MISSING),theirs.get(key,MISSING),path+'/'+key.replace('~','~0').replace('/','~1'))
                if value is not MISSING:result[key]=value
            return result
        if path in resolutions:return mine if resolutions[path]=='local' else theirs
        conflicts.append(dict(path=path,local=preview(mine),server=preview(theirs)));return theirs
    result={'library':{},'appearance':merge(base.get('appearance',{}),local.get('appearance',{}),server.get('appearance',{}),'/appearance')}
    for key in sorted(set(base['library'])|set(local['library'])|set(server['library'])):
        path='/library/'+key
        if key in SECTIONS:result['library'][key]=list(merge(*[{r['id']:r for r in s['library'].get(key,[])} for s in (base,local,server)],path).values())
        else:
            value=merge(base['library'].get(key,MISSING),local['library'].get(key,MISSING),server['library'].get(key,MISSING),path)
            if value is not MISSING:result['library'][key]=value
    remaining={r['id'] for r in result['library'].get('books',[])}
    for section in ('sessions','readingDates'):result['library'][section]=[r for r in result['library'].get(section,[]) if r.get('bookId') in remaining]
    dates={}
    for row in sorted(result['library'].get('readingDates',[]),key=lambda r:r['id']):dates.setdefault((row['bookId'],row['day']),row)
    result['library']['readingDates']=list(dates.values())
    if result['library'].get('currentBookId') not in remaining:result['library']['currentBookId']=None
    return collection_inventory(result),conflicts

def mount_library(app,connect,root,current):
    root=Path(root)/'private-libraries';root.mkdir(mode=0o700,exist_ok=True)
    def file_for(user,row):return root/str(user['id'])/row['filename']
    def read(user,row):
        try:return unpack(file_for(user,row).read_bytes())
        except FileNotFoundError:raise HTTPException(404,'Esta versão não está mais disponível para restauração.') from None
    def read_normalized(user,row):
        snap=read(user,row)
        return externalize_media(snap,media_dir_for(root,user['id']))
    def present_for_client(snap,user,use_media):
        return snap if use_media else hydrate_media(snap,media_dir_for(root,user['id']))
    def status_for(db,user):
        rows=db.execute('SELECT revision,digest,created_at FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 10',(user['id'],)).fetchall()
        return dict(revision=rows[0]['revision'] if rows else 0,digest=rows[0]['digest'] if rows else '',versions=rows,media=True)

    @app.get('/v1/me/library/status')
    def status(user=Depends(current)):
        with connect() as db:return status_for(db,user)

    @app.post('/v1/me/media/check')
    async def media_check(request:Request,user=Depends(current)):
        raw=await request.body()
        try:body=json.loads(raw,parse_constant=_reject_constant)
        except ValueError:raise HTTPException(422,'Pedido inválido.') from None
        hashes=body.get('hashes') if isinstance(body,dict) else None
        if not isinstance(hashes,list) or len(hashes)>MAX_MEDIA_CHECK or any(not isinstance(h,str) or not SHA256_RE.fullmatch(h) for h in hashes):
            raise HTTPException(422,'Lista de hashes inválida.')
        mdir=media_dir_for(root,user['id'])
        missing=[h for h in dict.fromkeys(hashes) if not (mdir/(h+'.jpg')).is_file()]
        return dict(missing=missing)

    @app.put('/v1/me/media/{sha256}')
    async def media_upload(sha256:str,request:Request,user=Depends(current)):
        if not SHA256_RE.fullmatch(sha256):raise HTTPException(422,'Identificador de imagem inválido.')
        raw=await request.body()
        if len(raw)>MAX_MEDIA_BYTES:raise HTTPException(413,'Imagem precisa ter até 12 MB.')
        def save():
            mdir=media_dir_for(root,user['id'])
            return store_media_bytes(mdir,raw,expected_sha=sha256,label='Imagem')
        saved=await run_in_threadpool(save)
        return dict(ok=True,sha256=saved)

    @app.get('/v1/me/media/{sha256}')
    def media_download(sha256:str,user=Depends(current)):
        if not SHA256_RE.fullmatch(sha256):raise HTTPException(404,'Imagem não encontrada.')
        path=root/str(user['id'])/'media'/(sha256+'.jpg')
        try:
            with path.open('rb') as f:data=f.read(MAX_MEDIA_BYTES+1)
        except FileNotFoundError:raise HTTPException(404,'Imagem não encontrada.') from None
        if len(data)>MAX_MEDIA_BYTES:raise HTTPException(404,'Imagem não encontrada.')
        return Response(data,media_type='image/jpeg',headers={'Cache-Control':'private, max-age=31536000, immutable','X-Content-Type-Options':'nosniff'})

    @app.get('/v1/me/library')
    def download(request:Request,revision:int|None=None,user=Depends(current)):
        if revision is not None and not 0<=revision<2**63:raise HTTPException(422,'Revisão inválida.')
        use_media=request.headers.get('x-viblio-media')=='1'
        with connect() as db:row=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s'+(' AND revision=%s' if revision is not None else '')+' ORDER BY revision DESC LIMIT 1',(user['id'],revision) if revision is not None else (user['id'],)).fetchone()
        if not row:raise HTTPException(404,'Nenhum backup encontrado nesta conta.')
        snap=read_normalized(user,row) if use_media else hydrate_media(read(user,row),media_dir_for(root,user['id']))
        return Response(gzip.compress(canonical(snap),mtime=0),media_type='application/gzip')

    def process(raw,user,use_media):
        message=unpack(raw)
        if not isinstance(message,dict):raise HTTPException(422,'Pedido inválido.')
        try:identifier=uuid.UUID(message['request_id']);base_rev=message['base_revision']
        except (KeyError,ValueError,TypeError):raise HTTPException(422,'Pedido de sincronização inválido.') from None
        if type(base_rev) is not int or not 0<=base_rev<2**63:raise HTTPException(422,'Revisão inválida.')
        snapshot=validate_snapshot(message.get('snapshot'));resolutions=message.get('resolutions',{})
        if not isinstance(resolutions,dict) or any(v not in ('local','server') for v in resolutions.values()):raise HTTPException(422,'Resoluções inválidas.')
        request_hash=checksum(message);created=None;obsolete=[];kept_filenames=[]
        mdir=media_dir_for(root,user['id'])
        try:
            with connect() as db:
                db.execute('SELECT id FROM public.viblio_users WHERE id=%s FOR UPDATE',(user['id'],))
                previous=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s AND request_id=%s',(user['id'],identifier)).fetchone()
                if previous:
                    if previous['request_hash']!=request_hash:raise HTTPException(409,dict(code='request_reused',message='Pedido já utilizado com outros dados.'))
                    prev_snap=read_normalized(user,previous)
                    return dict(revision=previous['revision'],digest=previous['digest'],snapshot=present_for_client(prev_snap,user,use_media))
                # Normalize embedded Base64 -> media:<sha256> and verify all media:<sha256> exist on disk.
                snapshot=externalize_media(snapshot,mdir)
                verify_media_present(snapshot,mdir)
                latest=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 1',(user['id'],)).fetchone();revision=latest['revision'] if latest else 0
                if base_rev>revision:raise HTTPException(409,dict(code='revision',message='Revisão do aparelho incompatível.'))
                if resolutions and message.get('against_revision')!=revision:raise HTTPException(409,dict(code='changed_again',message='O servidor mudou novamente. Revise os conflitos.'))
                if message.get('strict_base') and revision!=base_rev:raise HTTPException(409,dict(code='remote_changed',message='Outro aparelho tem alterações. Toque em Sincronizar.'))
                merged=snapshot
                if latest and revision!=base_rev:
                    base=db.execute('SELECT snapshot FROM public.viblio_library_bases WHERE user_id=%s AND revision=%s',(user['id'],base_rev)).fetchone()
                    if not base and base_rev!=0:raise HTTPException(409,dict(code='base_expired',message='A base antiga expirou. Revise antes de combinar como nova importação.'))
                    empty={'library':{'schemaVersion':6,'currentBookId':None,**{k:[] for k in SECTIONS}},'appearance':{}}
                    merged,conflicts=merge_snapshot(base['snapshot'] if base else empty,snapshot,read_normalized(user,latest),resolutions,media_dir=mdir)
                    if conflicts:raise HTTPException(409,dict(code='conflicts',revision=revision,conflicts=conflicts,message='Escolha qual alteração manter no mesmo campo.'))
                    merged=validate_snapshot(merged)
                digest=checksum(merged)
                if latest and latest['digest']==digest:return dict(revision=revision,digest=digest,snapshot=present_for_client(merged,user,use_media))
                data=canonical(merged)
                if len(data)>MAX_SNAPSHOT:raise HTTPException(413,'O acervo completo precisa ter até 32 MB.')
                packed=gzip.compress(data,mtime=0)
                if len(packed)>MAX_PACKED:raise HTTPException(413,'Backup comprimido muito grande.')
                folder=root/str(user['id']);folder.mkdir(mode=0o700,exist_ok=True)
                filename=uuid.uuid4().hex+'.json.gz';created=folder/filename
                temporary=folder/(filename+'.tmp');temporary.write_bytes(packed);temporary.chmod(0o600);temporary.replace(created)
                db.execute('INSERT INTO public.viblio_library_versions(user_id,revision,request_id,request_hash,digest,filename) VALUES (%s,%s,%s,%s,%s,%s)',(user['id'],revision+1,identifier,request_hash,digest,filename))
                from psycopg.types.json import Jsonb
                from .activity import record_activity
                prior=db.execute('SELECT snapshot FROM public.viblio_library_bases WHERE user_id=%s AND revision=%s',(user['id'],revision)).fetchone() if latest else None
                record_activity(db,user['id'],prior['snapshot'] if prior else None,merged,revision+1)
                from .clubs import sync_club_progress
                sync_club_progress(db,user['id'],merged)
                db.execute('INSERT INTO public.viblio_library_bases(user_id,revision,snapshot) VALUES (%s,%s,%s)',(user['id'],revision+1,Jsonb(compact(merged))))
                db.execute('DELETE FROM public.viblio_library_bases WHERE user_id=%s AND revision<%s',(user['id'],max(1,revision+1-999)))
                obsolete=db.execute('DELETE FROM public.viblio_library_versions WHERE user_id=%s AND revision NOT IN (SELECT revision FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 10) RETURNING filename',(user['id'],user['id'])).fetchall()
                if obsolete:
                    kept_filenames=[r['filename'] for r in db.execute('SELECT filename FROM public.viblio_library_versions WHERE user_id=%s',(user['id'],)).fetchall()]
            created=None
            for old in obsolete:
                try:(root/str(user['id'])/old['filename']).unlink(missing_ok=True)
                except OSError:pass
            if obsolete and kept_filenames:
                cleanup_unused_media(root/str(user['id']),kept_filenames)
            return dict(revision=revision+1,digest=digest,snapshot=present_for_client(merged,user,use_media))
        except Exception:
            if created:created.unlink(missing_ok=True)
            raise

    @app.post('/v1/me/library/sync')
    async def sync(request:Request,user=Depends(current)):
        raw=await request.body()
        if len(raw)>MAX_PACKED:raise HTTPException(413,'Backup comprimido muito grande.')
        use_media=request.headers.get('x-viblio-media')=='1'
        result=await run_in_threadpool(process,raw,user,use_media)
        return Response(gzip.compress(canonical(result),mtime=0),media_type='application/gzip')
