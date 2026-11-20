"""Private snapshots, bounded images and optimistic three-way field merge."""
import base64,copy,gzip,hashlib,io,json,math,re,uuid
from pathlib import Path
from datetime import date
from fastapi import Depends,HTTPException,Request
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool
from PIL import Image
MAX_PACKED=24*1024*1024
MAX_SNAPSHOT=32*1024*1024
MISSING=object()
SECTIONS=('books','collections','sessions','readingDates')
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
                art=row.get('coverData','')
                if not isinstance(art,str):raise HTTPException(422,'Capa inválida.')
                if art:
                    try:
                        if not art.startswith('data:image/jpeg;base64,'):raise ValueError()
                        pixels=base64.b64decode(art.split(',',1)[1],validate=True)
                        if len(pixels)>12*1024*1024:raise ValueError()
                        with Image.open(io.BytesIO(pixels)) as image:
                            if image.format!='JPEG' or max(image.size)>2048:raise ValueError()
                            image.verify()
                    except Exception:raise HTTPException(422,'Capa JPEG inválida.') from None
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
    if isinstance(value,dict):return {k:({'_cover_hash':hashlib.sha256(v.encode()).hexdigest()} if k=='coverData' and isinstance(v,str) else compact(v)) for k,v in value.items()}
    if isinstance(value,list):return [compact(v) for v in value]
    return value
def same(a,b):
    if a is MISSING or b is MISSING:return a is b
    if isinstance(a,dict) and set(a)=={'_cover_hash'} and isinstance(b,str):return a['_cover_hash']==hashlib.sha256(b.encode()).hexdigest()
    if isinstance(b,dict) and set(b)=={'_cover_hash'} and isinstance(a,str):return b['_cover_hash']==hashlib.sha256(a.encode()).hexdigest()
    return compact(a)==compact(b)
def merge_snapshot(base,local,server,resolutions=None):
    base,local,server=[collection_inventory(s) for s in (base,local,server)]
    conflicts=[];resolutions=resolutions or {}
    def preview(v):
        if v is MISSING:return '(excluído)'
        if isinstance(v,str) and v.startswith('data:image/'):return '(imagem)'
        if isinstance(v,dict):return str(v.get('title') or v.get('id') or '(registro)')[:180]
        return str(v)[:180]
    def merge(old,mine,theirs,path):
        if same(mine,theirs):return mine
        if same(mine,old):return theirs
        if same(theirs,old):return mine
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
    def status_for(db,user):
        rows=db.execute('SELECT revision,digest,created_at FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 10',(user['id'],)).fetchall()
        return dict(revision=rows[0]['revision'] if rows else 0,digest=rows[0]['digest'] if rows else '',versions=rows)
    @app.get('/v1/me/library/status')
    def status(user=Depends(current)):
        with connect() as db:return status_for(db,user)
    @app.get('/v1/me/library')
    def download(revision:int|None=None,user=Depends(current)):
        if revision is not None and not 0<=revision<2**63:raise HTTPException(422,'Revisão inválida.')
        with connect() as db:row=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s'+(' AND revision=%s' if revision is not None else '')+' ORDER BY revision DESC LIMIT 1',(user['id'],revision) if revision is not None else (user['id'],)).fetchone()
        if not row:raise HTTPException(404,'Nenhum backup encontrado nesta conta.')
        return Response(gzip.compress(canonical(read(user,row)),mtime=0),media_type='application/gzip')
    def process(raw,user):
        message=unpack(raw)
        if not isinstance(message,dict):raise HTTPException(422,'Pedido inválido.')
        try:identifier=uuid.UUID(message['request_id']);base_rev=message['base_revision']
        except (KeyError,ValueError,TypeError):raise HTTPException(422,'Pedido de sincronização inválido.') from None
        if type(base_rev) is not int or not 0<=base_rev<2**63:raise HTTPException(422,'Revisão inválida.')
        snapshot=validate_snapshot(message.get('snapshot'));resolutions=message.get('resolutions',{})
        if not isinstance(resolutions,dict) or any(v not in ('local','server') for v in resolutions.values()):raise HTTPException(422,'Resoluções inválidas.')
        request_hash=checksum(message);created=None;obsolete=[]
        try:
            with connect() as db:
                db.execute('SELECT id FROM public.viblio_users WHERE id=%s FOR UPDATE',(user['id'],))
                previous=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s AND request_id=%s',(user['id'],identifier)).fetchone()
                if previous:
                    if previous['request_hash']!=request_hash:raise HTTPException(409,dict(code='request_reused',message='Pedido já utilizado com outros dados.'))
                    return dict(revision=previous['revision'],digest=previous['digest'],snapshot=read(user,previous))
                latest=db.execute('SELECT * FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 1',(user['id'],)).fetchone();revision=latest['revision'] if latest else 0
                if base_rev>revision:raise HTTPException(409,dict(code='revision',message='Revisão do aparelho incompatível.'))
                if resolutions and message.get('against_revision')!=revision:raise HTTPException(409,dict(code='changed_again',message='O servidor mudou novamente. Revise os conflitos.'))
                if message.get('strict_base') and revision!=base_rev:raise HTTPException(409,dict(code='remote_changed',message='Outro aparelho tem alterações. Toque em Sincronizar.'))
                merged=snapshot
                if latest and revision!=base_rev:
                    base=db.execute('SELECT snapshot FROM public.viblio_library_bases WHERE user_id=%s AND revision=%s',(user['id'],base_rev)).fetchone()
                    if not base and base_rev!=0:raise HTTPException(409,dict(code='base_expired',message='A base antiga expirou. Revise antes de combinar como nova importação.'))
                    empty={'library':{'schemaVersion':6,'currentBookId':None,**{k:[] for k in SECTIONS}},'appearance':{}}
                    merged,conflicts=merge_snapshot(base['snapshot'] if base else empty,snapshot,read(user,latest),resolutions)
                    if conflicts:raise HTTPException(409,dict(code='conflicts',revision=revision,conflicts=conflicts,message='Escolha qual alteração manter no mesmo campo.'))
                    merged=validate_snapshot(merged)
                digest=checksum(merged)
                if latest and latest['digest']==digest:return dict(revision=revision,digest=digest,snapshot=merged)
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
            created=None
            for old in obsolete:
                try:(root/str(user['id'])/old['filename']).unlink(missing_ok=True)
                except OSError:pass
            return dict(revision=revision+1,digest=digest,snapshot=merged)
        except Exception:
            if created:created.unlink(missing_ok=True)
            raise
    @app.post('/v1/me/library/sync')
    async def sync(request:Request,user=Depends(current)):
        raw=await request.body()
        if len(raw)>MAX_PACKED:raise HTTPException(413,'Backup comprimido muito grande.')
        result=await run_in_threadpool(process,raw,user)
        return Response(gzip.compress(canonical(result),mtime=0),media_type='application/gzip')
