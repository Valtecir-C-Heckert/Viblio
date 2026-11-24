"""Invited members see only club data. Roles are scoped to each club and rechecked on every call."""
import uuid,base64,io,json,re,asyncio
from urllib.parse import urlencode
from psycopg.types.json import Jsonb
from datetime import datetime,timezone,timedelta
from pathlib import Path
from fastapi import Depends,HTTPException,Query,Request
from fastapi.responses import Response,StreamingResponse
from starlette.concurrency import run_in_threadpool
from starlette.background import BackgroundTask
from .live import DiscussionEvents
from .accounts import digest
from pydantic import BaseModel,ConfigDict,Field,field_validator
from PIL import Image
from .personal_library import unpack,MAX_PACKED,read_image_bytes
DISCUSSION_HEARTBEAT=15

class Input(BaseModel):model_config=ConfigDict(extra='forbid')
class Name(Input):
    name:str=Field(min_length=1,max_length=80)
    @field_validator('name')
    @classmethod
    def trim(cls,v):
        if not v.strip():raise ValueError('Informe um nome.')
        return v.strip()
class Invite(Input):user_id:uuid.UUID
class Role(Input):role:str=Field(pattern='^(admin|member)$')
class Proposal(Input):
    book_id:str=Field(min_length=1,max_length=200)
    source_user_id:uuid.UUID|None=None
class Vote(Input):proposal_id:uuid.UUID
class Schedule(Input):
    due_at:datetime
    shift_stages:bool=False
    @field_validator('due_at')
    @classmethod
    def date(cls,v):
        now=datetime.now(timezone.utc)
        if v.tzinfo is None or not now<=v<=now+timedelta(days=730):raise ValueError('Escolha um prazo futuro de até dois anos.')
        return v
class Start(Schedule):proposal_id:uuid.UUID
class Progress(Input):page:int=Field(ge=0,le=100000,strict=True)
class Stage(Input):
    page_limit:int=Field(ge=1,le=100000,strict=True)
    discuss_at:datetime
    is_open:bool=False
    @field_validator('discuss_at')
    @classmethod
    def date(cls,v):
        now=datetime.now(timezone.utc)
        if v.tzinfo is None or not datetime(1970,1,1,tzinfo=timezone.utc)<=v<=now+timedelta(days=730):raise ValueError('Data de discussão inválida.')
        return v
class Message(Input):
    body:str=Field(min_length=1,max_length=2000)
    reply_to_id:int|None=Field(default=None,ge=1,strict=True)
    @field_validator('body')
    @classmethod
    def trim(cls,v):
        if not v.strip():raise ValueError('Escreva uma mensagem.')
        return v.strip()
def catalog(b):return str(b['catalogSource'])+':'+str(b['catalogId']) if b.get('catalogSource') and b.get('catalogId') else None

def mark_stage_read(db,stage_id,user_id,message_id):
    if not message_id or message_id<1:return
    db.execute("""INSERT INTO public.viblio_club_stage_reads(stage_id,user_id,last_read_message_id,read_at)
        VALUES (%s,%s,%s,now()) ON CONFLICT(stage_id,user_id) DO UPDATE
        SET last_read_message_id=GREATEST(viblio_club_stage_reads.last_read_message_id,EXCLUDED.last_read_message_id),read_at=now()""",(stage_id,user_id,message_id))

def club_event(db,round_id,owner,kind,data):
    db.execute('INSERT INTO public.viblio_club_events(round_id,user_id,kind,data) VALUES (%s,%s,%s,%s)',(round_id,owner,kind,Jsonb(data)))
    db.execute('DELETE FROM public.viblio_club_events WHERE round_id=%s AND id IN (SELECT id FROM public.viblio_club_events WHERE round_id=%s ORDER BY id DESC OFFSET 2000)',(round_id,round_id))

def sync_club_progress(db,owner,snapshot):
    rows=db.execute("""SELECT p.round_id,p.book_id,p.page,b.catalog_id,b.pages FROM public.viblio_club_progress p
        JOIN public.viblio_club_rounds r ON r.id=p.round_id AND r.state='reading'
        JOIN public.viblio_club_members m ON m.club_id=r.club_id AND m.user_id=p.user_id AND m.status='active'
        JOIN public.viblio_club_proposals b ON b.id=r.selected_id WHERE p.user_id=%s""",(owner,)).fetchall()
    archived_colls={c['id'] for c in snapshot['library'].get('collections',[]) if isinstance(c,dict) and c.get('archived',False) and 'id' in c}
    books={b['id']:b for b in snapshot['library'].get('books',[]) if not b.get('archived',False) and b.get('collectionId','') not in archived_colls}
    for r in rows:
        b=books.get(r['book_id'])
        if b and b.get('pagesKnown',True) and b['totalPages']==r['pages'] and (not r['catalog_id'] or catalog(b)==r['catalog_id']):
            updated=db.execute('UPDATE public.viblio_club_progress SET page=%s,updated_at=now() WHERE round_id=%s AND user_id=%s AND book_id=%s AND page IS DISTINCT FROM %s RETURNING page',(b['currentPage'],r['round_id'],owner,r['book_id'],b['currentPage'])).fetchone()
            if updated:club_event(db,r['round_id'],owner,'progress',dict(page=updated['page'],previous_page=r['page']))

def mount_clubs(app,connect,storage,current):
    root=Path(storage)/'private-libraries'
    events=DiscussionEvents()
    app.state.discussion_events=events
    def access(db,c,u,admin=False,invited=False,lock=True):
        club=db.execute('SELECT id,name FROM public.viblio_clubs WHERE id=%s'+(' FOR UPDATE' if lock else ''),(c,)).fetchone()
        if not club:raise HTTPException(404,'Clube não encontrado.')
        m=db.execute('SELECT role,status FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s',(c,u)).fetchone()
        if not m or m['status']!='active' and not invited:raise HTTPException(404,'Clube não encontrado ou convite pendente.')
        if admin and (m['role']!='admin' or m['status']!='active'):raise HTTPException(403,'Somente administradores deste clube.')
        return dict(m,club_name=club['name'])
    def latest(db,c,r=None):
        row=db.execute('SELECT * FROM public.viblio_club_rounds WHERE club_id=%s'+(' AND id=%s' if r else '')+' ORDER BY created_at DESC LIMIT 1',(c,r) if r else (c,)).fetchone()
        if not row:raise HTTPException(404,'Leitura não encontrada.')
        return row
    def keep_admin(db,c,u):
        m=db.execute('SELECT role,status FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s',(c,u)).fetchone()
        if not m:raise HTTPException(404,'Participante não encontrado.')
        if m['role']=='admin' and m['status']=='active' and db.execute("SELECT count(*) n FROM public.viblio_club_members WHERE club_id=%s AND role='admin' AND status='active'",(c,)).fetchone()['n']<=1:raise HTTPException(409,'Mantenha pelo menos um administrador. Promova outra pessoa primeiro.')
    def book(db,u,identifier):
        row=db.execute("""SELECT e FROM (SELECT snapshot FROM public.viblio_library_bases WHERE user_id=%s ORDER BY revision DESC LIMIT 1) s,
            LATERAL jsonb_array_elements(s.snapshot->'library'->'books') e WHERE e->>'id'=%s AND coalesce(e->>'archived','false')='false' AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(s.snapshot->'library'->'collections') parent WHERE parent->>'id'=e->>'collectionId' AND coalesce(parent->>'archived','false')='true') LIMIT 1""",(u,identifier)).fetchone()
        if not row:raise HTTPException(404,'Sincronize o livro da sua estante antes de indicá-lo.')
        if not row['e'].get('pagesKnown',True):raise HTTPException(422,'Informe o total de páginas do livro.')
        return row['e']
    def photo(db,u,identifier):
        row=db.execute('SELECT filename FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 1',(u,)).fetchone()
        try:
            if not row:return None
            with (root/str(u)/row['filename']).open('rb') as f:raw=f.read(MAX_PACKED+1)
            if len(raw)>MAX_PACKED:return None
            b=next((b for b in unpack(raw)['library']['books'] if b['id']==identifier),None);art=b.get('coverData','') if b else ''
            data=read_image_bytes(root,u,art)
            if not data:return None
            with Image.open(io.BytesIO(data)) as im:
                if im.format!='JPEG' or max(im.size)>2048:return None
                im.thumbnail((360,540));out=io.BytesIO();im.convert('RGB').save(out,'JPEG',quality=86);data=out.getvalue()
            return data if len(data)<=256*1024 else None
        except (OSError,ValueError,Image.DecompressionBombError):return None
    def info(db,c,u,r=None,lock=False):
        role=access(db,c,u,lock=lock);round=latest(db,c,r)
        members=db.execute("""SELECT m.user_id id,u.username,m.role,m.status,
            coalesce((live.book->>'currentPage')::int,p.page,0) page FROM public.viblio_club_members m
            JOIN public.viblio_users u ON u.id=m.user_id
            LEFT JOIN public.viblio_club_progress p ON p.round_id=%s AND p.user_id=m.user_id
            LEFT JOIN public.viblio_club_proposals chosen ON chosen.id=%s
            LEFT JOIN LATERAL (SELECT (SELECT e FROM jsonb_array_elements(s.snapshot->'library'->'books') e
                WHERE e->>'id'=p.book_id AND coalesce(e->>'archived','false')='false'
                AND (e->>'totalPages')::int=chosen.pages
                AND (chosen.catalog_id IS NULL OR concat(e->>'catalogSource',':',e->>'catalogId')=chosen.catalog_id)
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(s.snapshot->'library'->'collections') parent WHERE parent->>'id'=e->>'collectionId' AND coalesce(parent->>'archived','false')='true') LIMIT 1) book
                FROM public.viblio_library_bases s WHERE s.user_id=m.user_id AND p.book_id IS NOT NULL AND %s='reading' ORDER BY revision DESC LIMIT 1) live ON true
            WHERE m.club_id=%s ORDER BY (m.role='admin') DESC,u.username LIMIT 100""",(round['id'],round['selected_id'],round['state'],c)).fetchall()
        proposals=db.execute("""SELECT b.id,b.title,b.author,b.pages,b.catalog_id,b.book_id,coalesce(b.source_user_id,b.proposed_by) owner_id,b.cover IS NOT NULL has_cover,pu.username proposed_by,count(v.user_id) FILTER(WHERE m.status='active') votes,coalesce(bool_or(v.user_id=%s),false) mine
            FROM public.viblio_club_proposals b LEFT JOIN public.viblio_users pu ON pu.id=b.proposed_by
            LEFT JOIN public.viblio_club_votes v ON v.proposal_id=b.id LEFT JOIN public.viblio_club_members m ON m.user_id=v.user_id AND m.club_id=%s
            WHERE b.round_id=%s GROUP BY b.id,pu.username ORDER BY votes DESC,b.title LIMIT 30""",(u,c,round['id'])).fetchall()
        stages=db.execute("""SELECT s.id,s.page_limit,s.discuss_at,(s.is_open OR s.discuss_at<=now()) is_open,
            (SELECT count(*) FROM public.viblio_club_messages m WHERE m.stage_id=s.id) comments,
            CASE WHEN (s.is_open OR s.discuss_at<=now()) THEN
                (SELECT count(*) FROM public.viblio_club_messages m WHERE m.stage_id=s.id AND m.user_id<>%s AND m.id>coalesce(sr.last_read_message_id,0))
            ELSE 0 END unread
            FROM public.viblio_club_stages s
            LEFT JOIN public.viblio_club_stage_reads sr ON sr.stage_id=s.id AND sr.user_id=%s
            WHERE s.round_id=%s ORDER BY s.page_limit LIMIT 20""",(u,u,round['id'])).fetchall()
        history=db.execute('SELECT r.id,r.state,r.due_at,r.completed_at,b.title,b.author,b.cover IS NOT NULL has_cover,b.id proposal_id FROM public.viblio_club_rounds r LEFT JOIN public.viblio_club_proposals b ON b.id=r.selected_id WHERE r.club_id=%s ORDER BY r.created_at DESC LIMIT 100',(c,)).fetchall()
        return dict(id=str(c),name=role['club_name'],role=role['role'],round=dict(id=str(round['id']),state=round['state'],selected_id=str(round['selected_id']) if round['selected_id'] else None,due_at=round['due_at'],started_at=round['started_at'],completed_at=round['completed_at']),members=[dict(id=str(m['id']),username=m['username'],role=m['role'],status=m['status'],page=m['page']) for m in members],proposals=[dict(id=str(p['id']),title=p['title'],author=p['author'],pages=p['pages'],proposed_by=p['proposed_by'] or '',votes=p['votes'],mine=p['mine'],cover=f"/v1/clubs/{c}/proposals/{p['id']}/cover" if p['has_cover'] else '',catalog_id=p['catalog_id'] or '',own_book_id=p['book_id'] if p['owner_id']==u else '') for p in proposals],stages=[dict(id=str(s['id']),page_limit=s['page_limit'],discuss_at=s['discuss_at'],is_open=s['is_open'],comments=s['comments'],unread=s['unread']) for s in stages],history=[dict(id=str(h['id']),state=h['state'],title=h['title'],author=h['author'],due_at=h['due_at'],completed_at=h['completed_at'],cover=f"/v1/clubs/{c}/proposals/{h['proposal_id']}/cover" if h['has_cover'] else '') for h in history])
    @app.get('/v1/clubs')
    def clubs(user=Depends(current)):
        with connect() as db:rows=db.execute("""SELECT c.id,c.name,m.role,m.status,r.state,r.due_at,
            b.id proposal_id,b.title,b.author,b.pages,b.catalog_id,b.book_id proposal_book_id,coalesce(b.source_user_id,b.proposed_by) proposal_owner_id,b.cover IS NOT NULL has_cover,
            coalesce((my_live.book->>'currentPage')::int,my_p.page,0) my_page,
            inv.username invited_by,
            (SELECT count(*) FROM public.viblio_club_members am WHERE am.club_id=c.id AND am.status='active') member_count,
            (SELECT count(*) FROM public.viblio_club_stages s
                JOIN public.viblio_club_messages msg ON msg.stage_id=s.id
                LEFT JOIN public.viblio_club_stage_reads sr ON sr.stage_id=s.id AND sr.user_id=%s
                WHERE s.round_id=r.id AND (s.is_open OR s.discuss_at<=now())
                AND msg.user_id<>%s AND msg.id>coalesce(sr.last_read_message_id,0)) unread_count,
            st.id stage_id,st.stage_index,st.page_limit stage_page_limit,st.is_open stage_is_open,st.discuss_at stage_discuss_at,
            (SELECT count(*) FROM public.viblio_club_proposals p WHERE p.round_id=r.id) proposal_count,
            (SELECT count(*) FROM public.viblio_club_votes v JOIN public.viblio_club_members vm ON vm.club_id=c.id AND vm.user_id=v.user_id AND vm.status='active' WHERE v.round_id=r.id) vote_count
            FROM public.viblio_clubs c JOIN public.viblio_club_members m ON m.club_id=c.id
            LEFT JOIN public.viblio_users inv ON inv.id=m.invited_by
            LEFT JOIN LATERAL (SELECT * FROM public.viblio_club_rounds WHERE club_id=c.id ORDER BY created_at DESC LIMIT 1) r ON true
            LEFT JOIN public.viblio_club_proposals b ON b.id=r.selected_id
            LEFT JOIN public.viblio_club_progress my_p ON my_p.round_id=r.id AND my_p.user_id=m.user_id
            LEFT JOIN LATERAL (SELECT (SELECT e FROM jsonb_array_elements(s.snapshot->'library'->'books') e
                WHERE e->>'id'=my_p.book_id AND coalesce(e->>'archived','false')='false'
                AND (e->>'totalPages')::int=b.pages
                AND (b.catalog_id IS NULL OR concat(e->>'catalogSource',':',e->>'catalogId')=b.catalog_id)
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(s.snapshot->'library'->'collections') parent WHERE parent->>'id'=e->>'collectionId' AND coalesce(parent->>'archived','false')='true') LIMIT 1) book
                FROM public.viblio_library_bases s WHERE s.user_id=m.user_id AND my_p.book_id IS NOT NULL AND r.state='reading' ORDER BY revision DESC LIMIT 1) my_live ON true
            LEFT JOIN LATERAL (
                SELECT s.id,s.page_limit,s.discuss_at,(s.is_open OR s.discuss_at<=now()) is_open,s.stage_index
                FROM (SELECT id,page_limit,discuss_at,is_open,row_number() OVER (ORDER BY page_limit) stage_index FROM public.viblio_club_stages WHERE round_id=r.id) s
                ORDER BY (s.is_open OR s.discuss_at<=now()) DESC, CASE WHEN (s.is_open OR s.discuss_at<=now()) THEN -s.page_limit ELSE s.page_limit END ASC
                LIMIT 1
            ) st ON r.state='reading'
            WHERE m.user_id=%s ORDER BY c.created_at DESC LIMIT 50""",(user['id'],user['id'],user['id'])).fetchall()
        def project(r):
            active=r['status']=='active'
            return dict(
                id=str(r['id']),name=r['name'],role=r['role'],status=r['status'],
                state=r['state'] if active else None,due_at=r['due_at'] if active else None,
                my_page=r['my_page'] if active and r['state']=='reading' else None,
                invited_by=r['invited_by'] if not active and r['invited_by'] else None,
                member_count=r['member_count'] if active else None,
                unread_count=r['unread_count'] if active else 0,
                stage=dict(id=str(r['stage_id']),index=r['stage_index'],page_limit=r['stage_page_limit'],is_open=r['stage_is_open'],discuss_at=r['stage_discuss_at']) if active and r['stage_id'] else None,
                voting=dict(proposals=r['proposal_count'],votes=r['vote_count']) if active and r['state']=='voting' else None,
                book=dict(title=r['title'],author=r['author'],pages=r['pages'],cover=f"/v1/clubs/{r['id']}/proposals/{r['proposal_id']}/cover" if r['has_cover'] else '',catalog_id=r['catalog_id'] or '',own_book_id=r['proposal_book_id'] if r['proposal_owner_id']==user['id'] else '') if active and r['title'] else None,
            )
        return dict(items=[project(r) for r in rows])
    @app.post('/v1/clubs')
    def create(body:Name,user=Depends(current)):
        c=uuid.uuid4()
        with connect() as db:
            db.execute('SELECT id FROM public.viblio_users WHERE id=%s FOR UPDATE',(user['id'],))
            if db.execute("SELECT count(*) n FROM public.viblio_club_members WHERE user_id=%s AND status='active'",(user['id'],)).fetchone()['n']>=50:raise HTTPException(409,'Limite de 50 clubes.')
            db.execute('INSERT INTO public.viblio_clubs(id,name) VALUES (%s,%s)',(c,body.name));db.execute("INSERT INTO public.viblio_club_members(club_id,user_id,role,status) VALUES (%s,%s,'admin','active')",(c,user['id']));db.execute('INSERT INTO public.viblio_club_rounds(id,club_id) VALUES (%s,%s)',(uuid.uuid4(),c))
        return dict(id=str(c))
    @app.get('/v1/clubs/{c}')
    def get(c:uuid.UUID,round_id:uuid.UUID|None=None,user=Depends(current)):
        with connect() as db:return info(db,c,user['id'],round_id)
    @app.post('/v1/clubs/{c}/invite')
    def invite(c:uuid.UUID,body:Invite,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            if not db.execute('SELECT id FROM public.viblio_users WHERE id=%s AND username IS NOT NULL FOR UPDATE',(body.user_id,)).fetchone():raise HTTPException(404,'Pessoa não encontrada.')
            if db.execute('SELECT count(*) n FROM public.viblio_club_members WHERE club_id=%s',(c,)).fetchone()['n']>=100:raise HTTPException(409,'Limite de 100 participantes.')
            if db.execute("SELECT count(*) n FROM public.viblio_club_members WHERE user_id=%s AND status='active'",(body.user_id,)).fetchone()['n']>=50:raise HTTPException(409,'Essa pessoa atingiu o limite de clubes.')
            if db.execute("SELECT count(*) n FROM public.viblio_club_members WHERE user_id=%s AND status='invited'",(body.user_id,)).fetchone()['n']>=50:raise HTTPException(409,'Essa pessoa tem muitos convites pendentes.')
            db.execute("INSERT INTO public.viblio_club_members(club_id,user_id,role,status,invited_by) VALUES (%s,%s,'member','invited',%s) ON CONFLICT DO NOTHING",(c,body.user_id,user['id']))
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/join')
    def join(c:uuid.UUID,user=Depends(current)):
        with connect() as db:
            m=access(db,c,user['id'],invited=True)
            if m['status']!='active':
                db.execute('SELECT id FROM public.viblio_users WHERE id=%s FOR UPDATE',(user['id'],))
                if db.execute("SELECT count(*) n FROM public.viblio_club_members WHERE user_id=%s AND status='active'",(user['id'],)).fetchone()['n']>=50:raise HTTPException(409,'Limite de 50 clubes.')
                db.execute("UPDATE public.viblio_club_members SET status='active',joined_at=now() WHERE club_id=%s AND user_id=%s",(c,user['id']))
                cur_round=db.execute("SELECT id,selected_id FROM public.viblio_club_rounds WHERE club_id=%s AND state='reading' ORDER BY created_at DESC LIMIT 1",(c,)).fetchone()
                if cur_round and cur_round['selected_id']:
                    db.execute("""INSERT INTO public.viblio_club_progress(round_id,user_id,page,book_id)
                        SELECT %s,%s,coalesce((b.book->>'currentPage')::int,0),b.book->>'id'
                        FROM public.viblio_club_proposals chosen
                        LEFT JOIN LATERAL (
                            SELECT (
                                SELECT e FROM jsonb_array_elements(coalesce(s.snapshot->'library'->'books','[]'::jsonb)) e
                                WHERE (%s=coalesce(chosen.source_user_id,chosen.proposed_by) AND e->>'id'=chosen.book_id OR chosen.catalog_id IS NOT NULL AND concat(e->>'catalogSource',':',e->>'catalogId')=chosen.catalog_id)
                                AND (e->>'totalPages')::int=chosen.pages AND coalesce(e->>'archived','false')='false'
                                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(s.snapshot->'library'->'collections','[]'::jsonb)) parent WHERE parent->>'id'=e->>'collectionId' AND coalesce(parent->>'archived','false')='true')
                                LIMIT 1
                            ) book FROM public.viblio_library_bases s WHERE s.user_id=%s ORDER BY revision DESC LIMIT 1
                        ) b ON true
                        WHERE chosen.id=%s
                        ON CONFLICT (round_id,user_id) DO NOTHING""",(cur_round['id'],user['id'],user['id'],user['id'],cur_round['selected_id']))
        return dict(ok=True)
    @app.patch('/v1/clubs/{c}/members/{other}')
    def role(c:uuid.UUID,other:uuid.UUID,body:Role,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            if body.role=='member':keep_admin(db,c,other)
            if not db.execute("UPDATE public.viblio_club_members SET role=%s WHERE club_id=%s AND user_id=%s AND status='active' RETURNING user_id",(body.role,c,other)).fetchone():raise HTTPException(404,'Participante ativo não encontrado.')
        events.publish(c)
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/transfer')
    def transfer(c:uuid.UUID,body:Invite,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            if body.user_id==user['id'] or not db.execute("SELECT 1 FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s AND status='active'",(c,body.user_id)).fetchone():raise HTTPException(422,'Escolha outro participante ativo.')
            db.execute("UPDATE public.viblio_club_members SET role='admin' WHERE club_id=%s AND user_id=%s",(c,body.user_id))
            db.execute("UPDATE public.viblio_club_members SET role='member' WHERE club_id=%s AND user_id=%s",(c,user['id']))
        events.publish(c)
        return dict(ok=True)
    @app.delete('/v1/clubs/{c}/members/{other}')
    def remove(c:uuid.UUID,other:uuid.UUID,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True);keep_admin(db,c,other);db.execute('DELETE FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s',(c,other));db.execute('DELETE FROM public.viblio_club_votes WHERE user_id=%s AND round_id IN (SELECT id FROM public.viblio_club_rounds WHERE club_id=%s)',(other,c));db.execute('DELETE FROM public.viblio_club_stage_reads WHERE user_id=%s AND stage_id IN (SELECT s.id FROM public.viblio_club_stages s JOIN public.viblio_club_rounds r ON r.id=s.round_id WHERE r.club_id=%s)',(other,c))
        events.publish(c)
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/leave')
    def leave(c:uuid.UUID,user=Depends(current)):
        with connect() as db:
            m=access(db,c,user['id'],invited=True)
            if m['status']=='active':keep_admin(db,c,user['id'])
            db.execute('DELETE FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s',(c,user['id']));db.execute('DELETE FROM public.viblio_club_votes WHERE user_id=%s AND round_id IN (SELECT id FROM public.viblio_club_rounds WHERE club_id=%s)',(user['id'],c));db.execute('DELETE FROM public.viblio_club_stage_reads WHERE user_id=%s AND stage_id IN (SELECT s.id FROM public.viblio_club_stages s JOIN public.viblio_club_rounds r ON r.id=s.round_id WHERE r.club_id=%s)',(user['id'],c))
        events.publish(c)
        return dict(ok=True)
    @app.delete('/v1/clubs/{c}')
    def delete(c:uuid.UUID,user=Depends(current)):
        with connect() as db:access(db,c,user['id'],True);db.execute('DELETE FROM public.viblio_clubs WHERE id=%s',(c,))
        events.publish(c)
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/rounds')
    def next_round(c:uuid.UUID,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            if latest(db,c)['state']!='completed':raise HTTPException(409,'Encerre a leitura atual antes de iniciar outra votação.')
            if db.execute('SELECT count(*) n FROM public.viblio_club_rounds WHERE club_id=%s',(c,)).fetchone()['n']>=100:raise HTTPException(409,'Limite de 100 leituras por clube.')
            db.execute('INSERT INTO public.viblio_club_rounds(id,club_id) VALUES (%s,%s)',(uuid.uuid4(),c))
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/proposals')
    def propose(c:uuid.UUID,body:Proposal,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id']);r=latest(db,c)
            if r['state']!='voting':raise HTTPException(409,'Votação encerrada.')
            source=body.source_user_id or user['id']
            if not db.execute("SELECT 1 FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s AND status='active'",(c,source)).fetchone():raise HTTPException(404,'Estante indisponível neste clube.')
            b=book(db,source,body.book_id)
            prior=db.execute('SELECT id FROM public.viblio_club_proposals WHERE round_id=%s AND (catalog_id=%s OR (coalesce(source_user_id,proposed_by)=%s AND book_id=%s))',(r['id'],catalog(b),source,body.book_id)).fetchone()
            if prior:return dict(id=str(prior['id']))
            if db.execute('SELECT count(*) n FROM public.viblio_club_proposals WHERE round_id=%s',(r['id'],)).fetchone()['n']>=30:raise HTTPException(409,'Limite de 30 livros por votação.')
            identifier=uuid.uuid4();db.execute('INSERT INTO public.viblio_club_proposals(id,round_id,proposed_by,book_id,title,author,pages,catalog_id,cover,source_user_id) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',(identifier,r['id'],user['id'],body.book_id,str(b['title'])[:500],str(b.get('author',''))[:200],b['totalPages'],catalog(b),photo(db,source,body.book_id),source))
        return dict(id=str(identifier))
    @app.post('/v1/clubs/{c}/vote')
    def vote(c:uuid.UUID,body:Vote,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id']);r=latest(db,c)
            if r['state']!='voting':raise HTTPException(409,'Votação encerrada.')
            if not db.execute('SELECT id FROM public.viblio_club_proposals WHERE id=%s AND round_id=%s',(body.proposal_id,r['id'])).fetchone():raise HTTPException(404,'Livro não encontrado nesta votação.')
            db.execute('INSERT INTO public.viblio_club_votes(round_id,user_id,proposal_id) VALUES (%s,%s,%s) ON CONFLICT(round_id,user_id) DO UPDATE SET proposal_id=EXCLUDED.proposal_id',(r['id'],user['id'],body.proposal_id))
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/start')
    def start(c:uuid.UUID,body:Start,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True);r=latest(db,c)
            if r['state']!='voting':raise HTTPException(409,'A leitura já começou.')
            options=info(db,c,user['id'])['proposals'];chosen=next((p for p in options if p['id']==str(body.proposal_id)),None)
            if not chosen or chosen['votes']<1 or chosen['votes']!=max(p['votes'] for p in options):raise HTTPException(409,'Escolha um dos mais votados, com pelo menos um voto.')
            db.execute("UPDATE public.viblio_club_rounds SET state='reading',selected_id=%s,due_at=%s,started_at=now() WHERE id=%s",(body.proposal_id,body.due_at,r['id']))
            db.execute("""INSERT INTO public.viblio_club_progress(round_id,user_id,page,book_id)
                SELECT %s,m.user_id,coalesce((b.book->>'currentPage')::int,0),b.book->>'id' FROM public.viblio_club_members m JOIN public.viblio_club_proposals chosen ON chosen.id=%s
                LEFT JOIN LATERAL (SELECT (SELECT e FROM jsonb_array_elements(coalesce(s.snapshot->'library'->'books','[]'::jsonb)) e
                    WHERE (m.user_id=coalesce(chosen.source_user_id,chosen.proposed_by) AND e->>'id'=chosen.book_id OR chosen.catalog_id IS NOT NULL AND concat(e->>'catalogSource',':',e->>'catalogId')=chosen.catalog_id)
                    AND (e->>'totalPages')::int=chosen.pages AND coalesce(e->>'archived','false')='false'
                    AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(s.snapshot->'library'->'collections','[]'::jsonb)) parent WHERE parent->>'id'=e->>'collectionId' AND coalesce(parent->>'archived','false')='true')
                    LIMIT 1) book FROM public.viblio_library_bases s WHERE s.user_id=m.user_id ORDER BY revision DESC LIMIT 1) b ON true
                WHERE m.club_id=%s AND m.status='active'""",(r['id'],body.proposal_id,c))
            club_event(db,r['id'],user['id'],'started',dict(title=chosen['title']))
            db.execute('UPDATE public.viblio_club_proposals SET cover=NULL WHERE round_id=%s AND id<>%s',(r['id'],body.proposal_id))
        return dict(ok=True)
    @app.patch('/v1/clubs/{c}/schedule')
    def schedule(c:uuid.UUID,body:Schedule,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True);r=latest(db,c)
            if r['state']!='reading':raise HTTPException(409,'Não há leitura em andamento.')
            if body.shift_stages:db.execute('UPDATE public.viblio_club_stages SET discuss_at=greatest(now(),discuss_at+(%s-%s::timestamptz)) WHERE round_id=%s AND discuss_at>now() AND NOT is_open',(body.due_at,r['due_at'],r['id']))
            elif db.execute('SELECT 1 FROM public.viblio_club_stages WHERE round_id=%s AND discuss_at>%s LIMIT 1',(r['id'],body.due_at)).fetchone():raise HTTPException(422,'Ajuste também as discussões que ultrapassam esse prazo.')
            db.execute('UPDATE public.viblio_club_rounds SET due_at=%s WHERE id=%s',(body.due_at,r['id']))
            club_event(db,r['id'],user['id'],'schedule',dict(due_at=body.due_at.isoformat()))
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/complete')
    def finish(c:uuid.UUID,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True);r=latest(db,c)
            if r['state']!='reading':raise HTTPException(409,'Não há leitura em andamento.')
            db.execute("UPDATE public.viblio_club_rounds SET state='completed',completed_at=now() WHERE id=%s",(r['id'],))
            club_event(db,r['id'],user['id'],'completed',{})
        return dict(ok=True)
    @app.patch('/v1/clubs/{c}/progress')
    def progress(c:uuid.UUID,body:Progress,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id']);r=latest(db,c)
            if r['state']!='reading':raise HTTPException(409,'Não há leitura em andamento.')
            pages=db.execute('SELECT pages FROM public.viblio_club_proposals WHERE id=%s',(r['selected_id'],)).fetchone()['pages']
            if body.page>pages:raise HTTPException(422,'A página ultrapassa o total do livro do clube.')
            previous=db.execute('SELECT page FROM public.viblio_club_progress WHERE round_id=%s AND user_id=%s',(r['id'],user['id'])).fetchone()
            db.execute('INSERT INTO public.viblio_club_progress(round_id,user_id,page) VALUES (%s,%s,%s) ON CONFLICT(round_id,user_id) DO UPDATE SET page=EXCLUDED.page,book_id=NULL,updated_at=now()',(r['id'],user['id'],body.page))
            if not previous or previous['page']!=body.page:club_event(db,r['id'],user['id'],'progress',dict(page=body.page,previous_page=previous['page'] if previous else 0))
        return dict(ok=True)
    @app.post('/v1/clubs/{c}/stages')
    def stage(c:uuid.UUID,body:Stage,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True);r=latest(db,c)
            if r['state']!='reading':raise HTTPException(409,'Inicie a leitura antes de criar etapas.')
            pages=db.execute('SELECT pages FROM public.viblio_club_proposals WHERE id=%s',(r['selected_id'],)).fetchone()['pages']
            if body.page_limit>pages or body.discuss_at>r['due_at']:raise HTTPException(422,'Confira o limite de páginas e a data dentro do prazo do livro.')
            if db.execute('SELECT count(*) n FROM public.viblio_club_stages WHERE round_id=%s',(r['id'],)).fetchone()['n']>=20:raise HTTPException(409,'Limite de 20 etapas.')
            if db.execute('SELECT id FROM public.viblio_club_stages WHERE round_id=%s AND page_limit=%s',(r['id'],body.page_limit)).fetchone():raise HTTPException(409,'Essa página já tem uma etapa.')
            identifier=uuid.uuid4();db.execute('INSERT INTO public.viblio_club_stages(id,round_id,page_limit,discuss_at,is_open) VALUES (%s,%s,%s,%s,%s)',(identifier,r['id'],body.page_limit,body.discuss_at,body.is_open))
            club_event(db,r['id'],user['id'],'stage',dict(stage_id=str(identifier),page_limit=body.page_limit))
        return dict(id=str(identifier))
    @app.patch('/v1/clubs/{c}/stages/{identifier}')
    def edit_stage(c:uuid.UUID,identifier:uuid.UUID,body:Stage,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            r=db.execute('SELECT s.*,r.due_at,r.state,p.pages FROM public.viblio_club_stages s JOIN public.viblio_club_rounds r ON r.id=s.round_id JOIN public.viblio_club_proposals p ON p.id=r.selected_id WHERE s.id=%s AND r.club_id=%s',(identifier,c)).fetchone()
            if not r:raise HTTPException(404,'Etapa não encontrada.')
            if r['state']!='reading':raise HTTPException(409,'Não é possível editar etapas de uma leitura encerrada.')
            if body.page_limit>r['pages'] or body.discuss_at>r['due_at']:raise HTTPException(422,'Confira o limite de páginas e a data dentro do prazo do livro.')
            if body.page_limit!=r['page_limit'] and db.execute('SELECT id FROM public.viblio_club_stages WHERE round_id=%s AND page_limit=%s AND id<>%s',(r['round_id'],body.page_limit,identifier)).fetchone():raise HTTPException(409,'Essa página já tem uma etapa.')
            db.execute('UPDATE public.viblio_club_stages SET page_limit=%s,discuss_at=%s,is_open=%s WHERE id=%s',(body.page_limit,body.discuss_at,body.is_open,identifier))
        events.publish(c,identifier)
        return dict(ok=True)
    @app.delete('/v1/clubs/{c}/stages/{identifier}')
    def delete_stage(c:uuid.UUID,identifier:uuid.UUID,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],True)
            r=db.execute('SELECT s.id,s.round_id,r.state FROM public.viblio_club_stages s JOIN public.viblio_club_rounds r ON r.id=s.round_id WHERE s.id=%s AND r.club_id=%s',(identifier,c)).fetchone()
            if not r:raise HTTPException(404,'Etapa não encontrada.')
            if r['state']!='reading':raise HTTPException(409,'Não é possível excluir etapas de uma leitura encerrada.')
            db.execute('DELETE FROM public.viblio_club_stages WHERE id=%s',(identifier,))
        events.publish(c,identifier)
        return dict(ok=True)
    def discussion(db,c,s,u,lock=True):
        role=access(db,c,u,lock=lock);r=db.execute('SELECT s.page_limit,s.discuss_at,s.round_id,(s.is_open OR s.discuss_at<=now()) opened FROM public.viblio_club_stages s JOIN public.viblio_club_rounds r ON r.id=s.round_id WHERE s.id=%s AND r.club_id=%s',(s,c)).fetchone()
        if not r:raise HTTPException(404,'Etapa não encontrada.')
        return r,role
    @app.get('/v1/clubs/{c}/stages/{identifier}/events')
    async def discussion_events(c:uuid.UUID,identifier:uuid.UUID,request:Request,user=Depends(current)):
        # Recheck on notifications and heartbeats without consuming an HTTP rate bucket.
        # No DB connection or row lock is held while waiting for another comment.
        token_hash=digest(request.headers.get('authorization','').split(' ',1)[-1])
        def authorized():
            with connect() as db:
                db.execute("SET LOCAL statement_timeout='5s'")
                valid=db.execute("""SELECT 1 FROM public.viblio_sessions session
                    JOIN public.viblio_club_members m ON m.user_id=session.user_id
                    JOIN public.viblio_club_rounds r ON r.club_id=m.club_id
                    JOIN public.viblio_club_stages s ON s.round_id=r.id
                    WHERE session.token_hash=%s AND session.user_id=%s AND session.expires_at>now()
                    AND m.club_id=%s AND m.status='active' AND s.id=%s
                    AND (s.is_open OR s.discuss_at<=now())""",(token_hash,user['id'],c,identifier)).fetchone()
            return bool(valid)
        if not await run_in_threadpool(authorized):raise HTTPException(403,'Discussão indisponível para esta conta.')
        key,queue=events.subscribe(c,identifier,user['id'])
        async def stream():
            try:
                yield 'event: ready\ndata: {}\n\n'
                deadline=asyncio.get_running_loop().time()+600
                while asyncio.get_running_loop().time()<deadline:
                    changed=True
                    try:await asyncio.wait_for(queue.get(),timeout=DISCUSSION_HEARTBEAT)
                    except asyncio.TimeoutError:changed=False
                    if not await run_in_threadpool(authorized):
                        yield 'event: revoked\ndata: {}\n\n'
                        return
                    yield 'event: changed\ndata: {}\n\n' if changed else ': heartbeat\n\n'
            finally:events.unsubscribe(key)
        return StreamingResponse(stream(),media_type='text/event-stream',headers={'Cache-Control':'no-store','X-Accel-Buffering':'no'},background=BackgroundTask(events.unsubscribe,key))
    @app.get('/v1/clubs/{c}/stages/{identifier}/messages')
    def messages(c:uuid.UUID,identifier:uuid.UUID,before:int|None=Query(None,ge=1,le=2**63-1),user=Depends(current)):
        with connect() as db:
            r,role=discussion(db,c,identifier,user['id'],lock=False)
            if not r['opened']:raise HTTPException(403,'A discussão ainda não abriu.')
            rows=db.execute("""SELECT m.id,m.user_id,u.username,m.body,m.sent_at,
                p.id reply_id,pu.username reply_username,p.body reply_body
                FROM public.viblio_club_messages m
                JOIN public.viblio_users u ON u.id=m.user_id
                LEFT JOIN public.viblio_club_messages p ON p.id=m.reply_to_id AND p.stage_id=m.stage_id
                LEFT JOIN public.viblio_users pu ON pu.id=p.user_id
                WHERE m.stage_id=%s AND (%s::bigint IS NULL OR m.id<%s)
                ORDER BY m.id DESC LIMIT 41""",(identifier,before,before)).fetchall()
            if before is None and rows:mark_stage_read(db,identifier,user['id'],rows[0]['id'])
        return dict(stage=dict(id=str(identifier),page_limit=r['page_limit'],discuss_at=r['discuss_at'],is_open=r['opened']),items=[dict(id=row['id'],user_id=str(row['user_id']),username=row['username'],body=row['body'],sent_at=row['sent_at'],reply_to=dict(id=row['reply_id'],username=row['reply_username'],body=row['reply_body'][:120]) if row['reply_id'] else None,can_delete=row['user_id']==user['id'] or role['role']=='admin') for row in rows[:40]],next_cursor=rows[39]['id'] if len(rows)>40 else None)
    @app.post('/v1/clubs/{c}/stages/{identifier}/messages')
    def post(c:uuid.UUID,identifier:uuid.UUID,body:Message,user=Depends(current)):
        with connect() as db:
            r,_=discussion(db,c,identifier,user['id'])
            if not r['opened']:raise HTTPException(403,'A discussão ainda não abriu.')
            if db.execute('SELECT count(*) n FROM public.viblio_club_messages m JOIN public.viblio_club_stages s ON s.id=m.stage_id JOIN public.viblio_club_rounds r ON r.id=s.round_id WHERE r.club_id=%s',(c,)).fetchone()['n']>=10000:raise HTTPException(409,'Limite de mensagens deste clube atingido.')
            if body.reply_to_id is not None and not db.execute('SELECT 1 FROM public.viblio_club_messages WHERE id=%s AND stage_id=%s',(body.reply_to_id,identifier)).fetchone():raise HTTPException(422,'Mensagem respondida não encontrada nesta etapa.')
            created=db.execute('INSERT INTO public.viblio_club_messages(stage_id,user_id,body,reply_to_id) VALUES (%s,%s,%s,%s) RETURNING id',(identifier,user['id'],body.body,body.reply_to_id)).fetchone()
            mark_stage_read(db,identifier,user['id'],created['id'])
        events.publish(c,identifier)
        return dict(ok=True,id=created['id'])
    @app.delete('/v1/clubs/{c}/stages/{identifier}/messages/{message}')
    def delete_message(c:uuid.UUID,identifier:uuid.UUID,message:int,user=Depends(current)):
        if not 1<=message<2**63:raise HTTPException(422,'Mensagem inválida.')
        with connect() as db:
            _,role=discussion(db,c,identifier,user['id']);r=db.execute('SELECT user_id FROM public.viblio_club_messages WHERE id=%s AND stage_id=%s',(message,identifier)).fetchone()
            if not r or r['user_id']!=user['id'] and role['role']!='admin':raise HTTPException(403,'Você não pode apagar esta mensagem.')
            db.execute('DELETE FROM public.viblio_club_messages WHERE id=%s',(message,))
        events.publish(c,identifier)
        return dict(ok=True)
    @app.get('/v1/clubs/{c}/books')
    def books(c:uuid.UUID,q:str=Query('',max_length=100),cursor:str|None=Query(None,max_length=4000),user=Depends(current)):
        after_owner=after_book=None
        if cursor:
            try:
                after_owner,after_book=json.loads(base64.urlsafe_b64decode(cursor+'='*((-len(cursor))%4)))
                after_owner=uuid.UUID(after_owner)
                if not isinstance(after_book,str) or len(after_book)>200:raise ValueError()
            except (ValueError,TypeError,UnicodeError):raise HTTPException(422,'Página de livros inválida.') from None
        with connect() as db:
            access(db,c,user['id'],lock=False)
            rows=db.execute("""SELECT m.user_id,u.username,s.revision,e.book FROM public.viblio_club_members m
                JOIN public.viblio_users u ON u.id=m.user_id
                JOIN LATERAL (SELECT revision,snapshot FROM public.viblio_library_bases WHERE user_id=m.user_id ORDER BY revision DESC LIMIT 1) s ON true
                JOIN LATERAL (SELECT coalesce(array_agg(parent->>'id') FILTER (WHERE coalesce(parent->>'archived','false')='true'), ARRAY[]::text[]) archived_colls
                    FROM jsonb_array_elements(coalesce(s.snapshot->'library'->'collections','[]'::jsonb)) parent) ac ON true
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(s.snapshot->'library'->'books','[]'::jsonb)) e(book)
                WHERE m.club_id=%s AND m.status='active' AND coalesce(e.book->>'archived','false')='false'
                AND coalesce(e.book->>'pagesKnown','true')='true'
                AND NOT (coalesce(e.book->>'collectionId','') = ANY(ac.archived_colls))
                AND strpos(lower(concat(e.book->>'title',' ',e.book->>'author')),lower(%s))>0
                AND (%s::uuid IS NULL OR (m.user_id,e.book->>'id')>(%s::uuid,%s::text))
                ORDER BY m.user_id,e.book->>'id' LIMIT 41""",(c,q,after_owner,after_owner,after_book)).fetchall()
        def project(r):
            b=r['book'];art=b.get('coverData');has=bool(art) and not (isinstance(art,dict) and art.get('_cover_hash')=='e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
            return dict(id=b['id'],owner=dict(id=str(r['user_id']),username=r['username']),title=str(b['title'])[:500],author=str(b.get('author',''))[:200],pages=b['totalPages'],cover='/v1/clubs/'+str(c)+'/library-cover?'+urlencode(dict(owner_id=str(r['user_id']),book_id=b['id'],revision=r['revision'])) if has else '')
        next_cursor=base64.urlsafe_b64encode(json.dumps([str(rows[39]['user_id']),rows[39]['book']['id']]).encode()).decode().rstrip('=') if len(rows)>40 else None
        return dict(items=[project(r) for r in rows[:40]],next_cursor=next_cursor)
    @app.get('/v1/clubs/{c}/library-cover')
    def library_cover(c:uuid.UUID,owner_id:uuid.UUID,book_id:str=Query(...,min_length=1,max_length=200),revision:int=Query(...,ge=1,le=2**63-1),user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],lock=False)
            if not db.execute("SELECT 1 FROM public.viblio_club_members WHERE club_id=%s AND user_id=%s AND status='active'",(c,owner_id)).fetchone():raise HTTPException(404,'Estante indisponível.')
            book(db,owner_id,book_id)
            latest_revision=db.execute('SELECT revision FROM public.viblio_library_versions WHERE user_id=%s ORDER BY revision DESC LIMIT 1',(owner_id,)).fetchone()
            if not latest_revision or latest_revision['revision']!=revision:raise HTTPException(404,'Capa atualizada. Reabra a seleção.')
            image=photo(db,owner_id,book_id)
            if not image:raise HTTPException(404,'Capa indisponível.')
        return Response(image,media_type='image/jpeg',headers={'Cache-Control':'no-store'})
    @app.get('/v1/clubs/{c}/feed')
    def feed(c:uuid.UUID,round_id:uuid.UUID|None=None,cursor:str|None=Query(None,max_length=200),user=Depends(current)):
        at=key=None
        if cursor:
            try:
                raw=base64.urlsafe_b64decode(cursor+'='*((-len(cursor))%4)).decode();timestamp,key=raw.split('|');at=datetime.fromisoformat(timestamp)
                if at.tzinfo is None or not re.fullmatch('(?:[em]:[0-9]{1,20}|a:[a-f0-9-]{36})',key):raise ValueError()
            except (ValueError,TypeError,UnicodeError):raise HTTPException(422,'Página de atividades inválida.') from None
        with connect() as db:
            access(db,c,user['id'],lock=False);r=latest(db,c,round_id)
            rows=db.execute("""WITH round_act AS MATERIALIZED (
                SELECT a.id,a.occurred_at,a.user_id,a.book_id,a.kind,
                       (a.data - ARRAY['rating','duration_ms','total_duration_ms']) AS data
                FROM public.viblio_activity a
                JOIN public.viblio_club_members eligible ON eligible.club_id=%s AND eligible.user_id=a.user_id AND eligible.status='active'
                WHERE a.kind IN ('started','read','finished')
                  AND a.occurred_at>=%s AND (%s::timestamptz IS NULL OR a.occurred_at<=%s)),
                act_users AS MATERIALIZED (SELECT DISTINCT user_id FROM round_act),
                act_bases AS MATERIALIZED (
                    SELECT DISTINCT ON (s.user_id) s.user_id,s.snapshot
                    FROM public.viblio_library_bases s JOIN act_users au ON au.user_id=s.user_id
                    ORDER BY s.user_id,s.revision DESC),
                matched_books AS MATERIALIZED (
                    SELECT ab.user_id,e->>'id' book_id
                    FROM act_bases ab
                    JOIN LATERAL (SELECT coalesce(array_agg(parent->>'id') FILTER (WHERE coalesce(parent->>'archived','false')='true'), ARRAY[]::text[]) archived_colls
                        FROM jsonb_array_elements(coalesce(ab.snapshot->'library'->'collections','[]'::jsonb)) parent) ac ON true
                    JOIN public.viblio_club_proposals selected ON selected.id=%s
                    CROSS JOIN LATERAL jsonb_array_elements(coalesce(ab.snapshot->'library'->'books','[]'::jsonb)) e
                    WHERE coalesce(e->>'archived','false')='false'
                    AND NOT (coalesce(e->>'collectionId','') = ANY(ac.archived_colls))
                    AND (ab.user_id=coalesce(selected.source_user_id,selected.proposed_by) AND e->>'id'=selected.book_id
                        OR selected.catalog_id IS NOT NULL AND concat(e->>'catalogSource',':',e->>'catalogId')=selected.catalog_id AND (e->>'totalPages')::int=selected.pages)),
                timeline AS (
                SELECT concat('e:',e.id) key,e.created_at at,e.user_id,e.kind,e.data,NULL::uuid stage_id FROM public.viblio_club_events e WHERE e.round_id=%s
                UNION ALL SELECT concat('m:',m.id),m.sent_at,m.user_id,'comment',jsonb_build_object('body',m.body,'page_limit',s.page_limit),s.id
                FROM public.viblio_club_messages m JOIN public.viblio_club_stages s ON s.id=m.stage_id WHERE s.round_id=%s AND (s.is_open OR s.discuss_at<=now())
                UNION ALL SELECT concat('a:',a.id),a.occurred_at,a.user_id,a.kind,a.data,NULL::uuid
                FROM round_act a JOIN matched_books mb ON mb.user_id=a.user_id AND mb.book_id=a.book_id)
                SELECT t.*,u.username FROM timeline t JOIN public.viblio_club_members member ON member.user_id=t.user_id AND member.club_id=%s AND member.status='active'
                JOIN public.viblio_users u ON u.id=t.user_id WHERE (%s::timestamptz IS NULL OR (t.at,t.key)<(%s,%s))
                ORDER BY t.at DESC,t.key DESC LIMIT 41""",(c,r['started_at'] or r['created_at'],r['completed_at'],r['completed_at'],r['selected_id'],r['id'],r['id'],c,at,at,key)).fetchall()
        position=base64.urlsafe_b64encode((rows[39]['at'].isoformat()+'|'+rows[39]['key']).encode()).decode().rstrip('=') if len(rows)>40 else None
        return dict(items=[dict(id=row['key'],actor=dict(id=str(row['user_id']),username=row['username']),kind=row['kind'],occurred_at=int(row['at'].timestamp()*1000),data=row['data'],stage_id=str(row['stage_id']) if row['stage_id'] else None) for row in rows[:40]],next_cursor=position)
    @app.get('/v1/clubs/{c}/proposals/{identifier}/cover')
    def cover(c:uuid.UUID,identifier:uuid.UUID,user=Depends(current)):
        with connect() as db:
            access(db,c,user['id'],lock=False);r=db.execute('SELECT b.cover FROM public.viblio_club_proposals b JOIN public.viblio_club_rounds r ON r.id=b.round_id WHERE b.id=%s AND r.club_id=%s',(identifier,c)).fetchone()
            if not r or not r['cover']:raise HTTPException(404,'Capa indisponível.')
        return Response(bytes(r['cover']),media_type='image/jpeg',headers={'Cache-Control':'no-store'})
