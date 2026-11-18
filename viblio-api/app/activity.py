"""Automatic reading activities; only accepted friends see the general feed projection."""
import base64
import json
import uuid
from datetime import datetime,timezone,timedelta
from fastapi import Depends,HTTPException,Query
from pydantic import BaseModel,ConfigDict,StrictBool
from psycopg.types.json import Jsonb

MAX_EVENTS_PER_SYNC=512
RETENTION_DAYS=180

class Sharing(BaseModel):
    model_config=ConfigDict(extra='forbid')
    enabled:StrictBool

def complete(book):
    return bool(book.get('readFinished')) if not book.get('pagesKnown',True) else bool(book.get('totalPages') and book.get('currentPage',0)>=book['totalPages'])

def record_activity(db,owner,previous,snapshot,revision):
    setting=db.execute('SELECT share_activity,activity_enabled_at FROM public.viblio_users WHERE id=%s',(owner,)).fetchone()
    if not setting['share_activity'] or not setting['activity_enabled_at']:return
    now=datetime.now(timezone.utc);start=max(setting['activity_enabled_at'],now-timedelta(days=RETENTION_DAYS))
    floor=int(start.timestamp()*1000);ceiling=int((now+timedelta(minutes=5)).timestamp()*1000)
    lib=snapshot['library'];old=(previous or {}).get('library',{})
    collections={r['id']:r for r in lib.get('collections',[])}
    books={r['id']:r for r in lib.get('books',[]) if not r.get('archived',False) and not collections.get(r.get('collectionId'),{}).get('archived',False)}
    before={r['id']:r for r in old.get('books',[])};old_sessions={r['id']:r for r in old.get('sessions',[])}
    sessions=lib.get('sessions',[]);keys=['session:'+r['id'] for r in sessions]
    db.execute('DELETE FROM public.viblio_activity WHERE user_id=%s AND (NOT(book_id=ANY(%s::text[])) OR occurred_at<%s OR (event_key LIKE \'session:%%\' AND NOT(event_key=ANY(%s::text[]))))',(owner,list(books),now-timedelta(days=RETENTION_DAYS),keys))
    changed=[r for r in sessions if r['bookId'] in books and old_sessions.get(r['id'])!=r and floor<=r['finishedAt']<=ceiling]
    invalid_keys=['session:'+r['id'] for r in sessions if old_sessions.get(r['id'])!=r and (r['bookId'] not in books or not floor<=r['finishedAt']<=ceiling or not r['durationMs'] and r['endPage']==r['startPage'])]
    if invalid_keys:db.execute('DELETE FROM public.viblio_activity WHERE user_id=%s AND event_key=ANY(%s::text[])',(owner,invalid_keys))
    changed=sorted(changed,key=lambda r:(r['finishedAt'],r['id']),reverse=True)[:MAX_EVENTS_PER_SYNC]
    durations={};first_read={}
    for r in sessions:
        durations[r['bookId']]=durations.get(r['bookId'],0)+r.get('durationMs',0)
    for r in old_sessions.values():first_read[r['bookId']]=True
    events={};finished_books=set();changed_books={r['bookId'] for r in changed}
    def event(key,book,kind,at,data):
        if not floor<=at<=ceiling:return
        row=dict(id=str(uuid.uuid5(owner,key)),event_key=key,book_id=book,kind=kind,occurred_at=datetime.fromtimestamp(at/1000,timezone.utc).isoformat(),data=data)
        if key not in events or kind!='started' or row['occurred_at']<events[key]['occurred_at']:events[key]=row
    for r in changed:
        b=books[r['bookId']];pages=max(0,r['endPage']-r['startPage'])
        if not pages and not r['durationMs']:continue
        finished=b.get('pagesKnown',True) and r['endPage']>=b['totalPages']
        data=dict(start_page=r['startPage'],end_page=r['endPage'],pages=pages,duration_ms=r['durationMs'],pages_known=b.get('pagesKnown',True))
        if finished:data.update(total_duration_ms=durations.get(b['id'],0),rating=b.get('rating'));finished_books.add(b['id'])
        event('session:'+r['id'],b['id'],'finished' if finished else 'read',r['finishedAt'],data)
        if b['id'] not in first_read and not before.get(b['id'],{}).get('currentPage',0):
            event('started:'+b['id'],b['id'],'started',r['startedAt'],{})
    for identifier,b in books.items():
        prior=before.get(identifier)
        if not prior:continue  # Adding/importing an already-read book is not a new reading activity.
        at=b.get('updatedAt',0)
        if not prior.get('currentPage',0) and b.get('currentPage',0)>0 and identifier not in changed_books:event('started:'+identifier,identifier,'started',at,{})
        if complete(b) and not complete(prior) and identifier not in finished_books:
            event('finished:'+identifier+':'+str(revision),identifier,'finished',at,dict(total_duration_ms=durations.get(identifier,0),rating=b.get('rating')))
        if b.get('rating') is not None and b.get('rating')!=prior.get('rating'):
            event('rated:'+identifier+':'+str(revision),identifier,'rated',int(now.timestamp()*1000),dict(rating=b['rating']))
    events=sorted(events.values(),key=lambda e:(e['occurred_at'],e['id']),reverse=True)[:MAX_EVENTS_PER_SYNC]
    if events:
        db.execute('''INSERT INTO public.viblio_activity(id,user_id,event_key,book_id,kind,occurred_at,data)
            SELECT e.id,%s,e.event_key,e.book_id,e.kind,e.occurred_at,e.data FROM jsonb_to_recordset(%s::jsonb)
            AS e(id uuid,event_key text,book_id text,kind text,occurred_at timestamptz,data jsonb)
            ON CONFLICT(user_id,event_key) DO UPDATE SET kind=EXCLUDED.kind,occurred_at=EXCLUDED.occurred_at,data=EXCLUDED.data''',(owner,Jsonb(events)))
    db.execute("""WITH totals AS (SELECT %s::jsonb data)
        UPDATE public.viblio_activity a SET data=jsonb_set(a.data,'{total_duration_ms}',coalesce(totals.data->a.book_id,'0'::jsonb))
        FROM totals WHERE a.user_id=%s AND a.kind='finished'
        AND a.data->'total_duration_ms' IS DISTINCT FROM coalesce(totals.data->a.book_id,'0'::jsonb)""",(Jsonb(durations),owner))
    db.execute('''DELETE FROM public.viblio_activity WHERE user_id=%s AND id IN
        (SELECT id FROM public.viblio_activity WHERE user_id=%s ORDER BY occurred_at DESC,id DESC OFFSET 10000)''',(owner,owner))

def encode_cursor(row):
    return base64.urlsafe_b64encode((row['occurred_at'].isoformat()+'|'+str(row['id'])).encode()).decode().rstrip('=')
def decode_cursor(value):
    try:
        raw=base64.b64decode(value+'='*((-len(value))%4),altchars=b'-_',validate=True).decode()
        date,identifier=raw.split('|');at=datetime.fromisoformat(date)
        if at.tzinfo is None:raise ValueError()
        return at,uuid.UUID(identifier)
    except (ValueError,TypeError,UnicodeError):raise HTTPException(422,'Cursor de atividades inválido.') from None

def mount_activity(app,connect,current):
    @app.get('/v1/me/activity')
    def sharing(user=Depends(current)):
        with connect() as db:row=db.execute('SELECT share_activity FROM public.viblio_users WHERE id=%s',(user['id'],)).fetchone()
        return dict(enabled=row['share_activity'])
    @app.patch('/v1/me/activity')
    def change_sharing(body:Sharing,user=Depends(current)):
        # Keep old clients compatible: sharing is automatic, never delete their feed.
        with connect() as db:
            db.execute('UPDATE public.viblio_users SET share_activity=true,activity_enabled_at=coalesce(activity_enabled_at,now()) WHERE id=%s',(user['id'],))
        return dict(enabled=True,mode='automatic')
    @app.get('/v1/friends/people')
    def people(cursor:uuid.UUID|None=None,q:str=Query('',max_length=32),user=Depends(current)):
        with connect() as db:
            rows=db.execute('''SELECT u.id,u.username,f.status,f.requested_by FROM public.viblio_users u
                LEFT JOIN public.viblio_friendships f ON f.user_low=least(u.id,%s) AND f.user_high=greatest(u.id,%s)
                WHERE u.id<>%s AND u.username IS NOT NULL AND strpos(lower(u.username),lower(%s))>0
                AND (%s::uuid IS NULL OR u.id>%s) ORDER BY u.id LIMIT 41''',(user['id'],user['id'],user['id'],q,cursor,cursor)).fetchall()
        return dict(items=[dict(id=str(r['id']),username=r['username'],relationship='accepted' if r['status']=='accepted' else ('incoming' if r['requested_by']!=user['id'] else 'outgoing') if r['status']=='pending' else 'none') for r in rows[:40]],next_cursor=str(rows[39]['id']) if len(rows)>40 else None)
    @app.get('/v1/friends/feed')
    def feed(cursor:str|None=Query(None,max_length=200),user=Depends(current)):
        position=decode_cursor(cursor) if cursor else None
        with connect() as db:
            friends=db.execute('''SELECT CASE WHEN user_low=%s THEN user_high ELSE user_low END id
                FROM public.viblio_friendships WHERE (user_low=%s OR user_high=%s) AND status='accepted' FOR SHARE''',(user['id'],user['id'],user['id'])).fetchall()
            actors=[r['id'] for r in friends]
            visibility=db.execute('SELECT id FROM public.viblio_users WHERE id=ANY(%s::uuid[]) AND share_activity',(actors,)).fetchall()
            actors=[r['id'] for r in visibility]
            rows=db.execute('''WITH candidate AS MATERIALIZED (
                SELECT a.* FROM public.viblio_activity a WHERE a.user_id=ANY(%s::uuid[]) AND a.occurred_at>=now()-interval '180 days'
                AND (%s::timestamptz IS NULL OR (a.occurred_at,a.id)<(%s,%s)) ORDER BY a.occurred_at DESC,a.id DESC LIMIT 41)
                SELECT a.*,u.username,b.revision,b.book FROM candidate a JOIN public.viblio_users u ON u.id=a.user_id AND u.share_activity
                LEFT JOIN LATERAL (SELECT s.revision,(SELECT e FROM jsonb_array_elements(s.snapshot->'library'->'books') e
                    WHERE e->>'id'=a.book_id AND coalesce(e->>'archived','false')='false' AND NOT EXISTS
                    (SELECT 1 FROM jsonb_array_elements(s.snapshot->'library'->'collections') c WHERE c->>'id'=e->>'collectionId' AND coalesce(c->>'archived','false')='true') LIMIT 1) book
                    FROM public.viblio_library_bases s WHERE s.user_id=a.user_id ORDER BY s.revision DESC LIMIT 1) b ON true
                ORDER BY a.occurred_at DESC,a.id DESC''',(actors,position[0] if position else None,position[0] if position else None,position[1] if position else None)).fetchall()
            setting=db.execute('SELECT share_activity FROM public.viblio_users WHERE id=%s',(user['id'],)).fetchone()
        from .friends import project
        items=[dict(id=str(r['id']),actor=dict(id=str(r['user_id']),username=r['username']),kind=r['kind'],occurred_at=int(r['occurred_at'].timestamp()*1000),book=project(r['book'],r['user_id'],r['revision']),**r['data']) for r in rows[:40] if r['book']]
        return dict(items=items,next_cursor=encode_cursor(rows[39]) if len(rows)>40 else None,sharing=setting['share_activity'],visible_actor_ids=[str(a) for a in actors])
