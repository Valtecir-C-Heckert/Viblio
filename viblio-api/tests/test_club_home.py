"""Club home shares only book choices, explicit group progress and stage comments."""
from datetime import datetime,timezone,timedelta
from test_accounts import api,headers,snapshot,sync,result
from test_friends import setup,data
from test_social import call,get,club,invite,future


def test_all_members_books_and_foreign_nomination_are_scoped(api):
    a,b,c=setup(api);group=club(api,a);invite(api,a,b,group)
    own=snapshot();own['library']['books'][0].update(title='Owned by A',currentPage=3)
    result(sync(api,a,own));other=data();other['library']['books'][0].update(title='Owned by B',currentPage=42)
    result(sync(api,b,other))
    listing=get(api,a,f'/v1/clubs/{group}/books')
    assert len(listing['items'])==2
    for forbidden in ('NEVER SHARE','notes','appearance','email','rating','currentPage','sessions'):
        assert forbidden not in str(listing)
    chosen=next(book for book in listing['items'] if book['owner']['id']==b['user']['id'])
    assert api.get(chosen['cover'],headers=headers(a)).status_code==200
    assert api.get(chosen['cover'],headers=headers(c)).status_code==404
    get(api,c,f'/v1/clubs/{group}/books',404)
    call(api,a,f'/v1/clubs/{group}/proposals',{'source_user_id':c['user']['id'],'book_id':'book'},code=404)
    nomination=call(api,a,f'/v1/clubs/{group}/proposals',{'source_user_id':b['user']['id'],'book_id':'book'})['id']
    # Same local ID belonging to another member is a different candidate.
    assert call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']!=nomination
    assert call(api,b,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']==nomination
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':nomination})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':nomination,'due_at':future(30)})
    members=get(api,a,f'/v1/clubs/{group}')['members']
    assert next(m for m in members if m['id']==b['user']['id'])['page']==42
    assert next(m for m in members if m['id']==a['user']['id'])['page']==0
    cards=get(api,a,'/v1/clubs')['items'];entry=next(e for e in cards if e['id']==group)
    assert entry['book']['title']=='Owned by B' and entry['book']['cover']
    call(api,b,f'/v1/clubs/{group}/leave')
    assert api.get(chosen['cover'],headers=headers(a)).status_code==404
    assert len(get(api,a,f'/v1/clubs/{group}/books')['items'])==1


def test_club_feed_is_bound_to_round_and_comments_gate_and_history(api):
    a,b,outsider=setup(api);group=club(api,a);invite(api,a,b,group)
    result(sync(api,a,snapshot()))
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    round_id=get(api,a,f'/v1/clubs/{group}')['round']['id']
    stage=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':50,'discuss_at':future(10),'is_open':True})['id']
    call(api,b,f'/v1/clubs/{group}/progress',{'page':30},'PATCH')
    path=f'/v1/clubs/{group}/stages/{stage}/messages'
    call(api,b,path,{'body':'Comment on this book'})
    feed=get(api,a,f'/v1/clubs/{group}/feed')['items']
    assert {'started','stage','progress','comment'}<={e['kind'] for e in feed}
    assert next(e for e in feed if e['kind']=='progress')['data']['page']==30
    assert next(e for e in feed if e['kind']=='comment')['data']['page_limit']==50
    assert get(api,b,path)['stage']['is_open']
    # Closing a future discussion hides its body from the timeline as well.
    call(api,a,f'/v1/clubs/{group}/stages/{stage}',{'page_limit':50,'discuss_at':future(10),'is_open':False},'PATCH')
    assert not any(e['kind']=='comment' for e in get(api,b,f'/v1/clubs/{group}/feed')['items'])
    get(api,b,path,403)
    call(api,a,f'/v1/clubs/{group}/stages/{stage}',{'page_limit':50,'discuss_at':future(10),'is_open':True},'PATCH')
    get(api,outsider,f'/v1/clubs/{group}/feed',404)
    get(api,a,f'/v1/clubs/{group}/feed?cursor=bad',422)
    call(api,a,f'/v1/clubs/{group}/complete')
    assert get(api,b,f'/v1/clubs/{group}')['history'][0]['completed_at']
    call(api,a,f'/v1/clubs/{group}/rounds')
    assert get(api,b,f'/v1/clubs/{group}/feed')['items']==[]
    old=get(api,b,f'/v1/clubs/{group}?round_id={round_id}')
    assert old['round']['state']=='completed' and old['stages'][0]['id']==stage
    assert any(e['kind']=='comment' for e in get(api,b,f'/v1/clubs/{group}/feed?round_id={round_id}')['items'])


def test_sync_logs_only_page_changes_and_keeps_manual_override(api):
    a,b,_=setup(api);group=club(api,a);invite(api,a,b,group);s=snapshot();result(sync(api,a,s))
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    s['library']['books'][0]['currentPage']=20;result(sync(api,a,s,1))
    assert len([e for e in get(api,b,f'/v1/clubs/{group}/feed')['items'] if e['kind']=='progress'])==1
    s['library']['books'][0]['author']='Changed';result(sync(api,a,s,2))
    assert len([e for e in get(api,b,f'/v1/clubs/{group}/feed')['items'] if e['kind']=='progress'])==1
    call(api,a,f'/v1/clubs/{group}/progress',{'page':40},'PATCH')
    s['library']['books'][0]['currentPage']=30;result(sync(api,a,s,3))
    members=get(api,b,f'/v1/clubs/{group}')['members']
    assert next(m for m in members if m['id']==a['user']['id'])['page']==40


def test_reading_is_automatic_and_club_feed_contains_only_selected_book(api):
    from test_social import session
    a,b,_=setup(api);group=club(api,a);invite(api,a,b,group)
    one=snapshot();one['library']['books'][0].update(catalogId='edition',catalogSource='catalog')
    other=snapshot();other['library']['books'][0].update(catalogId='edition',catalogSource='catalog')
    other['library']['books'].append(dict(id='private',title='Different book',kind='book',totalPages=100,currentPage=0))
    result(sync(api,a,one));result(sync(api,b,other))
    nomination=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':nomination})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':nomination,'due_at':future(30)})
    session(other,0,20);result(sync(api,b,other,1))
    feed=get(api,a,f'/v1/clubs/{group}/feed')['items']
    assert any(e['kind']=='read' and e['data']['pages']==20 and e['actor']['id']==b['user']['id'] for e in feed)
    assert get(api,a,'/v1/friends/feed')['items']==[] # club membership does not create friendship
    second=dict(other['library']['sessions'][0],id='other-book',bookId='private')
    other['library']['sessions'].append(second);other['library']['books'][1]['currentPage']=20
    result(sync(api,b,other,2))
    assert len([e for e in get(api,a,f'/v1/clubs/{group}/feed')['items'] if e['kind']=='read'])==1


def test_candidate_and_feed_pagination_have_no_duplicates(api):
    a,b,_=setup(api);group=club(api,a);invite(api,a,b,group)
    s=snapshot();s['library']['books']=[dict(id='b'+str(n).zfill(2),title='Book '+str(n),kind='book',totalPages=100,currentPage=0) for n in range(45)];s['library']['currentBookId']='b00'
    result(sync(api,a,s))
    first=get(api,b,f'/v1/clubs/{group}/books');second=get(api,b,f'/v1/clubs/{group}/books?cursor='+first['next_cursor'])
    assert len(first['items'])==40 and len(second['items'])==5 and second['next_cursor'] is None
    assert not ({x['id'] for x in first['items']} & {x['id'] for x in second['items']})
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'b00'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    for n in range(1,25):call(api,a,f'/v1/clubs/{group}/progress',{'page':n},'PATCH')
    for n in range(1,21):call(api,b,f'/v1/clubs/{group}/progress',{'page':n},'PATCH')
    first=get(api,b,f'/v1/clubs/{group}/feed');second=get(api,b,f'/v1/clubs/{group}/feed?cursor='+first['next_cursor'])
    assert len(first['items'])==40 and len(second['items'])==5 and second['next_cursor'] is None
    assert not ({x['id'] for x in first['items']} & {x['id'] for x in second['items']})
