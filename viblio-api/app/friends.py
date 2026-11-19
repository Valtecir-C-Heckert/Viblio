"""Accepted friends receive an allowlisted, read-only projection, never a snapshot."""
import base64
import io
import re
import uuid
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlencode
from fastapi import Depends, HTTPException, Query
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from PIL import Image
from .accounts import normalize_username
from .personal_library import MAX_PACKED, unpack

class FriendRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    username:str=Field(min_length=3,max_length=32)
    _username=field_validator('username')(normalize_username)

def pair(a,b):return tuple(sorted((a,b)))
def visible(book,collections):
    return not book.get('archived',False) and not collections.get(book.get('collectionId'),{}).get('archived',False)
def cover_path(owner,book,revision):
    art=book.get('coverData')
    has_cover=bool(art) and not (isinstance(art,dict) and art.get('_cover_hash')=='e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    return '/v1/friends/'+str(owner)+'/cover?'+urlencode(dict(book_id=book['id'],revision=revision)) if has_cover else ''
def project(book,owner,revision,collection=False):
    fields={'id':200,'title':500,'author':200,'publisher':200,'genre':100,'saga':200,'collectionId':200}
    result={k:str(book.get(k) or '')[:size] for k,size in fields.items()}
    result.update(kind=book.get('kind','book'),item_type='collection' if collection else 'book',
                  cover=cover_path(owner,book,revision),sequence=book.get('sequenceNumber'),
                  pages=book.get('totalPages') if book.get('pagesKnown',True) else None,
                  page=book.get('currentPage',0),complete=book.get('readFinished',False) if not book.get('pagesKnown',True) else bool(book.get('totalPages') and book.get('currentPage',0)>=book['totalPages']))
    return result

def mount_friends(app,connect,storage,current):
    root=Path(storage)/'private-libraries'
    def allowed(db,actor,other):
        if actor==other:raise HTTPException(404,'Amigo não encontrado.')
        low,high=pair(actor,other)
        row=db.execute("SELECT status FROM public.viblio_friendships WHERE user_low=%s AND user_high=%s AND status='accepted' FOR SHARE",(low,high)).fetchone()
        if not row:raise HTTPException(404,'Amigo não encontrado ou amizade encerrada.')
        user=db.execute('SELECT id,username FROM public.viblio_users WHERE id=%s',(other,)).fetchone()
        if not user:raise HTTPException(404,'Amigo não encontrado.')
        return dict(id=str(user['id']),username=user['username'] or 'Leitor')
    def lock_users(db,a,b):
        db.execute('SELECT id FROM public.viblio_users WHERE id IN (%s,%s) ORDER BY id FOR UPDATE',(a,b)).fetchall()
    def count(db,actor,status):
        return db.execute('SELECT count(*) n FROM public.viblio_friendships WHERE (user_low=%s OR user_high=%s) AND status=%s',(actor,actor,status)).fetchone()['n']
    @app.post('/v1/friends/requests')
    def request_friend(body:FriendRequest,user=Depends(current)):
        if not user['username']:raise HTTPException(409,'Defina seu nome de usuário em Conta antes de adicionar amigos.')
        with connect() as db:
            target=db.execute('SELECT id FROM public.viblio_users WHERE lower(username)=%s',(body.username,)).fetchone()
            if not target:raise HTTPException(404,'Nome de usuário não encontrado.')
            other=target['id'];actor=user['id']
            if other==actor:raise HTTPException(422,'Você não pode adicionar sua própria conta.')
            lock_users(db,actor,other);low,high=pair(actor,other)
            previous=db.execute('SELECT status,requested_by FROM public.viblio_friendships WHERE user_low=%s AND user_high=%s',(low,high)).fetchone()
            if previous:
                if previous['status']=='accepted' or previous['requested_by']==actor:return dict(status=previous['status'])
                raise HTTPException(409,'Você já recebeu um pedido dessa pessoa. Aceite em Amigos.')
            if any(count(db,owner,'pending')>=100 or count(db,owner,'accepted')>=200 for owner in (actor,other)):
                raise HTTPException(409,'Limite de amizades ou pedidos atingido. Resolva os pedidos pendentes.')
            db.execute('INSERT INTO public.viblio_friendships(user_low,user_high,requested_by) VALUES (%s,%s,%s)',(low,high,actor))
        return dict(status='pending')
    @app.post('/v1/friends/{other}/accept')
    def accept(other:uuid.UUID,user=Depends(current)):
        with connect() as db:
            lock_users(db,user['id'],other);low,high=pair(user['id'],other)
            row=db.execute("SELECT requested_by,status FROM public.viblio_friendships WHERE user_low=%s AND user_high=%s FOR UPDATE",(low,high)).fetchone()
            if not row or row['requested_by']==user['id']:raise HTTPException(404,'Pedido recebido não encontrado.')
            if row['status']=='accepted':return dict(status='accepted')
            if any(count(db,owner,'accepted')>=200 for owner in (user['id'],other)):raise HTTPException(409,'Limite de amigos atingido.')
            db.execute("UPDATE public.viblio_friendships SET status='accepted',accepted_at=now() WHERE user_low=%s AND user_high=%s",(low,high))
        return dict(status='accepted')
    @app.delete('/v1/friends/{other}')
    def remove(other:uuid.UUID,user=Depends(current)):
        low,high=pair(user['id'],other)
        with connect() as db:db.execute('DELETE FROM public.viblio_friendships WHERE user_low=%s AND user_high=%s',(low,high))
        return dict(ok=True)
    @app.get('/v1/friends')
    def friends(cursor:uuid.UUID|None=None,user=Depends(current)):
        with connect() as db:
            rows=db.execute("""SELECT u.id,u.username,s.revision,s.current FROM public.viblio_friendships f
                JOIN public.viblio_users u ON u.id=CASE WHEN f.user_low=%s THEN f.user_high ELSE f.user_low END
                LEFT JOIN LATERAL (SELECT b.revision,CASE WHEN nullif(b.snapshot->'library'->>'currentBookId','') IS NOT NULL THEN (SELECT jsonb_build_object('id',e->>'id','title',left(e->>'title',500),'author',left(e->>'author',200),'currentPage',coalesce(e->'currentPage','0'::jsonb),'totalPages',e->'totalPages','pagesKnown',coalesce(e->'pagesKnown','true'::jsonb),'readFinished',coalesce(e->'readFinished','false'::jsonb),'coverData',e ? 'coverData' AND e->'coverData'<>'""'::jsonb AND e->'coverData'<>jsonb_build_object('_cover_hash','e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'))
                    FROM jsonb_array_elements(b.snapshot->'library'->'books') e
                    WHERE e->>'id'=b.snapshot->'library'->>'currentBookId' AND coalesce(e->>'archived','false')='false'
                    AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(b.snapshot->'library'->'collections') c WHERE c->>'id'=e->>'collectionId' AND coalesce(c->>'archived','false')='true') LIMIT 1) END AS current
                    FROM public.viblio_library_bases b WHERE b.user_id=u.id ORDER BY b.revision DESC LIMIT 1) s ON true
                WHERE (f.user_low=%s OR f.user_high=%s) AND f.status='accepted' AND (%s::uuid IS NULL OR u.id>%s)
                ORDER BY u.id LIMIT 31""",(user['id'],user['id'],user['id'],cursor,cursor)).fetchall()
            requests=db.execute("""SELECT u.id,u.username,f.requested_by FROM public.viblio_friendships f
                JOIN public.viblio_users u ON u.id=CASE WHEN f.user_low=%s THEN f.user_high ELSE f.user_low END
                WHERE (f.user_low=%s OR f.user_high=%s) AND f.status='pending' ORDER BY f.created_at LIMIT 100""",(user['id'],user['id'],user['id'])).fetchall()
        result=[]
        for row in rows[:30]:
            book=row['current'];reading=None
            if book:
                view=project(book,row['id'],row['revision'])
                reading={key:view[key] for key in ('id','title','author','cover','page','pages','complete')}
            result.append(dict(id=str(row['id']),username=row['username'] or 'Leitor',current=reading))
        return dict(items=result,next_cursor=str(rows[29]['id']) if len(rows)>30 else None,
                    requests=[dict(id=str(r['id']),username=r['username'] or 'Leitor',incoming=r['requested_by']!=user['id']) for r in requests])
    @app.get('/v1/friends/{other}/library')
    def library(other:uuid.UUID,kind:str=Query('all',pattern='^(all|book|manga|comic)$'),q:str=Query('',max_length=80),
                collection_id:str=Query('',max_length=200),cursor:int=Query(0,ge=0,le=10000),revision:int|None=Query(None,ge=1),user=Depends(current)):
        with connect() as db:
            friend=allowed(db,user['id'],other)
            row=db.execute("SELECT revision,snapshot->'library'->'books' books,snapshot->'library'->'collections' collections FROM public.viblio_library_bases WHERE user_id=%s ORDER BY revision DESC LIMIT 1",(other,)).fetchone()
            if not row:return dict(friend=friend,revision=0,items=[],next_cursor=None,collection=None)
            if revision is not None and revision!=row['revision']:raise HTTPException(409,'A estante mudou. Atualize antes de continuar.')
            collections={c['id']:c for c in row['collections']};books=[b for b in row['books'] if visible(b,collections)]
            target=collections.get(collection_id)
            if collection_id and (not target or target.get('archived',False)):raise HTTPException(404,'Coleção não encontrada.')
            by_collection={}
            for book in books:by_collection.setdefault(book.get('collectionId',''),[]).append(book)
            matches=[]
            for book in books:
                if (collection_id and book.get('collectionId')==collection_id) or (not collection_id and not book.get('collectionId')):
                    matches.append(project(book,other,row['revision']))
            if not collection_id:
                for collection in collections.values():
                    if collection.get('archived',False):continue
                    volumes=by_collection.get(collection['id'],[])
                    primary=next((b for b in volumes if b.get('sequenceNumber')==1),None)
                    item=project(collection,other,row['revision'],True)
                    if primary and (primary_cover:=cover_path(other,primary,row['revision'])):item['cover']=primary_cover
                    item['volumes']=len(volumes);matches.append(item)
            matches=[b for b in matches if (kind=='all' or b['kind']==kind) and q.casefold() in (b['title']+' '+b['author']).casefold()]
            matches.sort(key=lambda b:((b.get('sequence') or 0) if collection_id else 0,b['title'].casefold(),b['id']))
            page=matches[cursor:cursor+40]
            return dict(friend=friend,revision=row['revision'],items=page,next_cursor=cursor+40 if len(matches)>cursor+40 else None,
                        collection=dict(id=target['id'],title=str(target['title'])[:500]) if target else None)
    @lru_cache(maxsize=6)
    def cover_sources(owner,filename):
        if not re.fullmatch(r'[a-f0-9]{32}\.json\.gz',filename):raise HTTPException(404,'Capa indisponível.')
        try:
            with (root/str(owner)/filename).open('rb') as file:raw=file.read(MAX_PACKED+1)
        except FileNotFoundError:raise HTTPException(404,'Capa indisponível. Atualize a estante.') from None
        if len(raw)>MAX_PACKED:raise HTTPException(413,'Acervo acima do limite.')
        library=unpack(raw)['library'];collections={c['id']:c for c in library.get('collections',[])}
        return {b['id']:b.get('coverData','') for b in library.get('books',[])+library.get('collections',[]) if visible(b,collections)}
    @lru_cache(maxsize=512)
    def thumbnail(owner,filename,book_id):
        art=cover_sources(owner,filename).get(book_id,'')
        if not isinstance(art,str) or not art.startswith('data:image/jpeg;base64,'):raise HTTPException(404,'Capa indisponível.')
        try:
            data=base64.b64decode(art.split(',',1)[1],validate=True)
            if len(data)>12*1024*1024:raise ValueError()
            with Image.open(io.BytesIO(data)) as image:
                if image.format!='JPEG' or max(image.size)>2048:raise ValueError()
                image.thumbnail((160,240));out=io.BytesIO();image.convert('RGB').save(out,'JPEG',quality=80)
            result=out.getvalue()
            if len(result)>96*1024:raise ValueError()
            return result
        except (ValueError,OSError):raise HTTPException(404,'Capa indisponível.') from None
    @app.get('/v1/friends/{other}/cover')
    def cover(other:uuid.UUID,book_id:str=Query(min_length=1,max_length=200),revision:int=Query(ge=1),user=Depends(current)):
        with connect() as db:
            allowed(db,user['id'],other)
            row=db.execute('SELECT revision,filename FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 1',(other,)).fetchone()
            if not row or revision!=row['revision']:raise HTTPException(404,'Capa desatualizada. Atualize a estante.')
            data=thumbnail(other,row['filename'],book_id)
            return Response(data,media_type='image/jpeg',headers={'Cache-Control':'no-store'})
