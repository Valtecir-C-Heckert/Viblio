"""Social consent, independent club roles, atomic administration and reading rounds."""
import copy
import time
import concurrent.futures
from datetime import datetime,timezone,timedelta
from test_accounts import api,headers,snapshot,sync,result
from test_friends import setup,accept,data


def call(api,user,path,body=None,method='POST',code=200):
    r=api.request(method,path,headers=headers(user),json=body)
    assert r.status_code==code,r.text
    return r.json()

def get(api,user,path,code=200):return call(api,user,path,method='GET',code=code)
def future(days):return (datetime.now(timezone.utc)+timedelta(days=days)).isoformat()
def club(api,a,name='Clube de leitura'):return call(api,a,'/v1/clubs',{'name':name})['id']
def invite(api,a,b,c):
    call(api,a,f'/v1/clubs/{c}/invite',{'user_id':b['user']['id']})
    call(api,b,f'/v1/clubs/{c}/join')
def role(api,a,b,c,value,code=200):return call(api,a,f"/v1/clubs/{c}/members/{b['user']['id']}",{'role':value},'PATCH',code)

def enabled(api,u):call(api,u,'/v1/me/activity',{'enabled':True},'PATCH')
def session(s,start=0,end=20,identifier='s1'):
    now=int(time.time()*1000)
    s['library']['books'][0].update(currentPage=end,updatedAt=now)
    s['library']['sessions'].append(dict(id=identifier,bookId='book',startPage=start,endPage=end,durationMs=60000,startedAt=now,finishedAt=now+1))
    return s


def test_directory_contains_only_names_and_requires_friend_acceptance(api):
    a,b,c=setup(api)
    # Query keeps this test independent of users created by the rest of the suite.
    query='?q='+b['user']['username']
    listing=get(api,a,'/v1/friends/people'+query)
    assert listing['items']==[dict(id=b['user']['id'],username=b['user']['username'],relationship='none')]
    assert 'email' not in str(listing) and 'library' not in str(listing)
    call(api,a,'/v1/friends/requests',{'username':b['user']['username']})
    assert get(api,a,'/v1/friends/people'+query)['items'][0]['relationship']=='outgoing'
    assert get(api,b,'/v1/friends/people?q='+a['user']['username'])['items'][0]['relationship']=='incoming'
    assert get(api,a,'/v1/friends/feed')['items']==[]
    call(api,b,'/v1/friends/'+a['user']['id']+'/accept')
    assert get(api,a,'/v1/friends/people'+query)['items'][0]['relationship']=='accepted'
    get(api,c,'/v1/friends/people?cursor=invalid',422)
    assert api.get('/v1/friends/people').status_code==401


def test_automatic_activity_offline_time_dedup_edits_and_revoke(api):
    a,b,c=setup(api);accept(api,a,b)
    s=data();result(sync(api,b,s))
    assert get(api,b,'/v1/me/activity')=={'enabled':True}
    enabled(api,b)
    # Initial old snapshot is never backfilled.
    assert get(api,a,'/v1/friends/feed')['items']==[]
    session(s,7,25);result(sync(api,b,s,1))
    feed=get(api,a,'/v1/friends/feed');assert len(feed['items'])==1,feed
    event=feed['items'][0]
    assert event['kind']=='read' and event['pages']==18 and event['duration_ms']==60000
    assert event['occurred_at']==s['library']['sessions'][0]['finishedAt']
    assert event['book']['page']==25
    for private in ('NEVER SHARE','notes','appearance','email','coverData','SHELF_READING_GOAL'):
        assert private not in str(feed)
    assert get(api,c,'/v1/friends/feed')['items']==[]
    # Same library/retry does not duplicate; an edited session replaces its event.
    result(sync(api,b,s,2));assert len(get(api,a,'/v1/friends/feed')['items'])==1
    s['library']['sessions'][0]['durationMs']=90000;result(sync(api,b,s,2))
    edited=get(api,a,'/v1/friends/feed')['items'][0]
    assert edited['id']==event['id'] and edited['duration_ms']==90000
    changed=call(api,b,'/v1/me/activity',{'enabled':False},'PATCH')
    assert changed['enabled'] and changed['mode']=='automatic'
    assert len(get(api,a,'/v1/friends/feed')['items'])==1
    enabled(api,b)
    session(s,25,50,'s2');result(sync(api,b,s,3))
    assert len(get(api,a,'/v1/friends/feed')['items'])==2
    call(api,a,'/v1/friends/'+b['user']['id'],method='DELETE')
    assert get(api,a,'/v1/friends/feed')['items']==[]
    get(api,a,'/v1/friends/feed?cursor=invalid',422)


def test_activity_finished_rating_archive_and_deleted_session(api):
    a,b,_=setup(api);accept(api,a,b);s=snapshot();result(sync(api,b,s));enabled(api,b)
    session(s,0,100);s['library']['books'][0]['rating']=4.5;result(sync(api,b,s,1))
    events=get(api,a,'/v1/friends/feed')['items']
    assert {e['kind'] for e in events}=={'started','finished','rated'},events
    done=next(e for e in events if e['kind']=='finished')
    assert done['total_duration_ms']==60000 and done['rating']==4.5
    s['library']['sessions']=[];result(sync(api,b,s,2))
    assert all(e['id']!=done['id'] for e in get(api,a,'/v1/friends/feed')['items'])
    s['library']['books'][0]['archived']=True;result(sync(api,b,s,3))
    assert get(api,a,'/v1/friends/feed')['items']==[]


def test_club_roles_scoped_invitation_and_multiple_admins(api):
    a,b,c=setup(api);one=club(api,a);two=club(api,b,'Outro clube')
    get(api,b,f'/v1/clubs/{one}',404)
    call(api,a,f'/v1/clubs/{one}/invite',{'user_id':b['user']['id']})
    assert next(row for row in get(api,b,'/v1/clubs')['items'] if row['id']==one)['status']=='invited'
    get(api,b,f'/v1/clubs/{one}',404)
    role(api,a,b,one,'admin',404) # cannot grant privileges before consent
    call(api,b,f'/v1/clubs/{one}/join')
    assert get(api,b,f'/v1/clubs/{one}')['role']=='member'
    assert get(api,b,f'/v1/clubs/{two}')['role']=='admin'
    call(api,b,f'/v1/clubs/{one}/invite',{'user_id':c['user']['id']},code=403)
    role(api,a,a,one,'member',409)
    call(api,a,f'/v1/clubs/{one}/leave',code=409)
    role(api,a,b,one,'admin');assert get(api,b,f'/v1/clubs/{one}')['role']=='admin'
    role(api,b,a,one,'member');role(api,a,b,one,'member',403)
    # Transfer demotes the current admin atomically, with no effect on other clubs.
    call(api,b,f'/v1/clubs/{one}/transfer',{'user_id':a['user']['id']})
    assert get(api,b,f'/v1/clubs/{one}')['role']=='member'
    assert get(api,a,f'/v1/clubs/{one}')['role']=='admin'
    assert get(api,b,f'/v1/clubs/{two}')['role']=='admin'
    get(api,c,f'/v1/clubs/{one}',404)
    call(api,b,f'/v1/clubs/{one}',method='DELETE',code=403)
    call(api,a,f'/v1/clubs/{one}',method='DELETE')
    get(api,a,f'/v1/clubs/{one}',404)


def test_concurrent_admin_self_demotion_preserves_one(api):
    a,b,_=setup(api);c=club(api,a);invite(api,a,b,c);role(api,a,b,c,'admin')
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda u:api.patch(f"/v1/clubs/{c}/members/{u['user']['id']}",headers=headers(u),json={'role':'member'}).status_code,(a,b)))
    assert sorted(results)==[200,409]
    members=get(api,a,f'/v1/clubs/{c}')['members']
    assert sum(m['role']=='admin' for m in members)==1


def test_club_voting_progress_stages_discussion_schedule_and_history(api):
    a,b,outsider=setup(api);c=club(api,a);invite(api,a,b,c)
    s=data();result(sync(api,a,s));other=snapshot();other['library']['books'][0]['title']='Other';result(sync(api,b,other))
    proposal=call(api,a,f'/v1/clubs/{c}/proposals',{'book_id':'book'})['id']
    assert call(api,a,f'/v1/clubs/{c}/proposals',{'book_id':'book'})['id']==proposal
    alternative=call(api,b,f'/v1/clubs/{c}/proposals',{'book_id':'book'})['id']
    info=get(api,b,f'/v1/clubs/{c}');cover=next(p for p in info['proposals'] if p['id']==proposal)['cover']
    assert cover and api.get(cover,headers=headers(b)).status_code==200
    assert api.get(cover,headers=headers(outsider)).status_code==404
    assert 'NEVER SHARE' not in str(info) and 'notes' not in str(info)
    call(api,a,f'/v1/clubs/{c}/start',{'proposal_id':proposal,'due_at':future(10)},code=409)
    call(api,a,f'/v1/clubs/{c}/vote',{'proposal_id':proposal})
    call(api,b,f'/v1/clubs/{c}/vote',{'proposal_id':proposal})
    assert get(api,b,f'/v1/clubs/{c}')['proposals'][0]['votes']==2
    call(api,a,f'/v1/clubs/{c}/start',{'proposal_id':alternative,'due_at':future(10)},code=409)
    call(api,b,f'/v1/clubs/{c}/start',{'proposal_id':proposal,'due_at':future(10)},code=403)
    call(api,a,f'/v1/clubs/{c}/start',{'proposal_id':proposal,'due_at':future(10)})
    current=get(api,a,f'/v1/clubs/{c}');round_id=current['round']['id']
    assert next(m for m in current['members'] if m['id']==a['user']['id'])['page']==7
    # Own saved page links to club progress. Manual club page is isolated from own library.
    s['library']['books'][0]['currentPage']=35;result(sync(api,a,s,1))
    assert next(m for m in get(api,b,f'/v1/clubs/{c}')['members'] if m['id']==a['user']['id'])['page']==35
    call(api,b,f'/v1/clubs/{c}/progress',{'page':101},'PATCH',422)
    call(api,b,f'/v1/clubs/{c}/progress',{'page':40},'PATCH')
    assert get(api,b,'/v1/me/library/status')['revision']==1
    call(api,b,f'/v1/clubs/{c}/stages',{'page_limit':50,'discuss_at':future(5)},code=403)
    stage=call(api,a,f'/v1/clubs/{c}/stages',{'page_limit':50,'discuss_at':future(5)})['id']
    path=f'/v1/clubs/{c}/stages/{stage}/messages'
    get(api,b,path,403);call(api,b,path,{'body':'spoiler'},code=403)
    call(api,a,f'/v1/clubs/{c}/schedule',{'due_at':future(3)},'PATCH',422)
    call(api,a,f'/v1/clubs/{c}/schedule',{'due_at':future(3),'shift_stages':True},'PATCH')
    updated=get(api,a,f'/v1/clubs/{c}')['stages'][0]
    assert updated['is_open'] # shifted earlier than now, clamped to now
    call(api,b,path,{'body':'Gostei desta etapa.'})
    messages=get(api,a,path)['items'];assert messages[0]['username']==b['user']['username']
    call(api,a,path+'/'+str(messages[0]['id']),method='DELETE')
    assert get(api,b,path)['items']==[]
    get(api,outsider,path,404)
    call(api,a,f'/v1/clubs/{c}/complete');call(api,a,f'/v1/clubs/{c}/rounds')
    assert get(api,b,f'/v1/clubs/{c}')['round']['state']=='voting'
    assert get(api,b,f'/v1/clubs/{c}?round_id={round_id}')['round']['state']=='completed'
    call(api,b,f'/v1/clubs/{c}/leave');assert api.get(cover,headers=headers(b)).status_code==404


def test_cross_club_resources_inputs_and_private_libraries(api):
    a,b,_=setup(api);one=club(api,a);two=club(api,b)
    s=snapshot();s['library']['collections']=[dict(id='m',title='Archived',kind='manga',archived=True)]
    s['library']['books'][0].update(kind='manga',collectionId='m',sequenceNumber=1)
    result(sync(api,a,s))
    call(api,a,f'/v1/clubs/{one}/proposals',{'book_id':'book'},code=404)
    get(api,a,f'/v1/clubs/{one}?round_id='+get(api,b,f'/v1/clubs/{two}')['round']['id'],404)
    call(api,a,f'/v1/clubs/{one}/transfer',{'user_id':b['user']['id']},code=422)
    call(api,a,'/v1/clubs',{'name':' '},code=422)
    call(api,a,'/v1/clubs',{'name':'x','role':'admin'},code=422)
    assert api.get('/v1/clubs').status_code==401


def test_feed_keyset_pagination_and_moved_session_time(api):
    a,b,_=setup(api);accept(api,a,b);s=snapshot();result(sync(api,b,s));enabled(api,b)
    for n in range(41):session(s,n,n+1,'session-'+str(n))
    result(sync(api,b,s,1));first=get(api,a,'/v1/friends/feed')
    assert len(first['items'])==40 and first['next_cursor']
    second=get(api,a,'/v1/friends/feed?cursor='+first['next_cursor'])
    assert len(second['items'])==2 and not second['next_cursor']
    assert not ({e['id'] for e in first['items']} & {e['id'] for e in second['items']})
    # Moving a session before opt-in removes its prior published activity.
    removed=s['library']['sessions'][0]
    removed['startedAt']=1;removed['finishedAt']=2
    result(sync(api,b,s,2))
    items=get(api,a,'/v1/friends/feed')['items']
    assert len(items)==40 and not get(api,a,'/v1/friends/feed')['next_cursor'] is None
    assert all(e.get('end_page')!=1 for e in items)


def test_edit_and_delete_earlier_session_recalculates_finished_time(api):
    a,b,_=setup(api);accept(api,a,b);s=snapshot();result(sync(api,b,s));enabled(api,b)
    session(s,0,40,'first');result(sync(api,b,s,1))
    session(s,40,100,'last');result(sync(api,b,s,2))
    s['library']['sessions'][0]['durationMs']=120000;result(sync(api,b,s,3))
    finished=next(e for e in get(api,a,'/v1/friends/feed')['items'] if e['kind']=='finished')
    assert finished['duration_ms']==60000 and finished['total_duration_ms']==180000
    s['library']['sessions']=s['library']['sessions'][1:];result(sync(api,b,s,4))
    feed=get(api,a,'/v1/friends/feed')['items']
    finished=next(e for e in feed if e['kind']=='finished')
    assert finished['total_duration_ms']==60000
    assert not any(e['kind']=='read' for e in feed)
    assert finished['book']['page']==100


def test_feed_includes_own_activity_cover_and_respects_friend_sharing(api):
    import psycopg
    from test_api import DSN
    a,b,outsider=setup(api);accept(api,a,b)
    sa=data();sb=data()
    result(sync(api,a,sa));result(sync(api,b,sb))
    enabled(api,a);enabled(api,b)
    session(sa,7,20,'a1');result(sync(api,a,sa,1))
    session(sb,7,30,'b1');result(sync(api,b,sb,1))

    feed_a=get(api,a,'/v1/friends/feed')
    assert a['user']['id'] in feed_a['visible_actor_ids']
    assert b['user']['id'] in feed_a['visible_actor_ids']
    actor_ids=[e['actor']['id'] for e in feed_a['items']]
    assert a['user']['id'] in actor_ids and b['user']['id'] in actor_ids
    own=next(e for e in feed_a['items'] if e['actor']['id']==a['user']['id'])
    cover_url=own['book']['cover']
    assert cover_url.startswith('/v1/friends/'+a['user']['id']+'/cover')
    own_cover=api.get(cover_url,headers=headers(a))
    assert own_cover.status_code==200 and own_cover.headers['content-type']=='image/jpeg'
    assert own_cover.headers['cache-control']=='no-store'
    assert api.get(cover_url,headers=headers(outsider)).status_code==404
    assert api.get('/v1/friends/'+a['user']['id']+'/library',headers=headers(a)).status_code==404

    # If a user disables share_activity, they still see their own events, while friends do not.
    with psycopg.connect(DSN) as db:
        db.execute('UPDATE public.viblio_users SET share_activity=false WHERE id=%s',(b['user']['id'],))
    session(sb,30,45,'b2');result(sync(api,b,sb,2))
    feed_b=get(api,b,'/v1/friends/feed')
    b_own_pages=[e.get('end_page') for e in feed_b['items'] if e['actor']['id']==b['user']['id']]
    assert 45 in b_own_pages and 30 in b_own_pages
    feed_a_after=get(api,a,'/v1/friends/feed')
    assert all(e['actor']['id']!=b['user']['id'] for e in feed_a_after['items'])
    assert any(e['actor']['id']==a['user']['id'] for e in feed_a_after['items'])

