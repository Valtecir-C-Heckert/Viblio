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


def test_club_cards_unread_counts_inviter_isolation_and_scoped_replies(api):
    a,b,outsider=setup(api);group=club(api,a,'Clube Aurora');other_group=club(api,outsider,'Clube Externo')
    result(sync(api,a,snapshot()));result(sync(api,outsider,snapshot()))
    call(api,a,f'/v1/clubs/{group}/invite',{'user_id':b['user']['id']})
    # Invited user sees inviter name, never private club internals before accepting.
    pending=next(e for e in get(api,b,'/v1/clubs')['items'] if e['id']==group)
    assert pending['status']=='invited' and pending['invited_by']==a['user']['username']
    assert pending['member_count'] is None and pending['book'] is None and pending['stage'] is None and pending['voting'] is None and pending['unread_count']==0
    call(api,b,f'/v1/clubs/{group}/join')
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    voting_card=next(e for e in get(api,b,'/v1/clubs')['items'] if e['id']==group)
    assert voting_card['member_count']==2 and voting_card['voting']=={'proposals':1,'votes':1}
    assert get(api,b,f'/v1/clubs/{group}')['proposals'][0]['proposed_by']==a['user']['username']
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    s1=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':40,'discuss_at':future(5),'is_open':True})['id']
    s2=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':80,'discuss_at':future(10),'is_open':True})['id']
    # Create a message in outsider's club to test cross-club reply isolation.
    other_p=call(api,outsider,f'/v1/clubs/{other_group}/proposals',{'book_id':'book'})['id']
    call(api,outsider,f'/v1/clubs/{other_group}/vote',{'proposal_id':other_p})
    call(api,outsider,f'/v1/clubs/{other_group}/start',{'proposal_id':other_p,'due_at':future(30)})
    other_stage=call(api,outsider,f'/v1/clubs/{other_group}/stages',{'page_limit':50,'discuss_at':future(5),'is_open':True})['id']
    foreign_msg=call(api,outsider,f'/v1/clubs/{other_group}/stages/{other_stage}/messages',{'body':'Privado'})['id']
    # Author's own message is read for author, unread for b.
    m1=call(api,a,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'Primeira impressão sobre o livro'})['id']
    m2=call(api,a,f'/v1/clubs/{group}/stages/{s2}/messages',{'body':'Etapa 2'})['id']
    assert next(e for e in get(api,a,'/v1/clubs')['items'] if e['id']==group)['unread_count']==0
    card_b=next(e for e in get(api,b,'/v1/clubs')['items'] if e['id']==group)
    assert card_b['unread_count']==2 and card_b['stage']['id']==s2 and card_b['stage']['index']==2 and card_b['book']['pages']==100
    stages_b=get(api,b,f'/v1/clubs/{group}')['stages']
    assert [s['unread'] for s in stages_b]==[1,1]
    # Reject cross-stage, cross-club and invalid reply_to_id.
    call(api,b,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'x','reply_to_id':m2},code=422)
    call(api,b,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'x','reply_to_id':foreign_msg},code=422)
    call(api,b,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'x','reply_to_id':True},code=422)
    # Valid same-stage reply and read-watermark update.
    reply=call(api,b,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'Concordo totalmente!','reply_to_id':m1})['id']
    items=get(api,b,f'/v1/clubs/{group}/stages/{s1}/messages')['items']
    child=next(x for x in items if x['id']==reply)
    assert child['reply_to']=={'id':m1,'username':a['user']['username'],'body':'Primeira impressão sobre o livro'}
    assert [s['unread'] for s in get(api,b,f'/v1/clubs/{group}')['stages']]==[0,1]
    assert next(e for e in get(api,a,'/v1/clubs')['items'] if e['id']==group)['unread_count']==1
    # Deleting parent clears reply_to without deleting child.
    call(api,a,f'/v1/clubs/{group}/stages/{s1}/messages/{m1}',method='DELETE')
    items_after=get(api,a,f'/v1/clubs/{group}/stages/{s1}/messages')['items']
    assert len(items_after)==1 and items_after[0]['id']==reply and items_after[0]['reply_to'] is None
    assert [s['unread'] for s in get(api,a,f'/v1/clubs/{group}')['stages']]==[0,0]
    # Health and auth/info advertise clubs_api 4 / api_version 12.
    assert api.get('/v1/auth/info').json()['clubs_api']==4
    assert get(api,a,'/v1/health')['api_version']==12


def test_stage_edit_page_limit_delete_and_metadata_privacy(api):
    import uuid, psycopg
    from app.accounts import digest
    from test_api import DSN
    def direct_user():
        identifier=uuid.uuid4();token=uuid.uuid4().hex+uuid.uuid4().hex;name='club_'+identifier.hex[:12]
        with psycopg.connect(DSN) as db:
            db.execute("INSERT INTO public.viblio_users(id,email,password_hash,is_admin,username) VALUES (%s,%s,'unused-test-hash',false,%s)",(identifier,str(identifier)+'@example.test',name))
            db.execute('INSERT INTO public.viblio_sessions(token_hash,user_id) VALUES (%s,%s)',(digest(token),identifier))
        return {'token':token,'user':{'id':str(identifier),'username':name}}
    a,b,outsider=direct_user(),direct_user(),direct_user();group=club(api,a,'Clube Metas');other=club(api,outsider,'Outro')
    invite(api,a,b,group)
    s=snapshot();s['library']['books'][0].update(catalogId='cat-42',catalogSource='src-1',currentPage=12)
    result(sync(api,a,s));result(sync(api,outsider,snapshot()))
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    other_p=call(api,outsider,f'/v1/clubs/{other}/proposals',{'book_id':'book'})['id']
    call(api,outsider,f'/v1/clubs/{other}/vote',{'proposal_id':other_p})
    call(api,outsider,f'/v1/clubs/{other}/start',{'proposal_id':other_p,'due_at':future(30)})
    other_stage=call(api,outsider,f'/v1/clubs/{other}/stages',{'page_limit':50,'discuss_at':future(5),'is_open':True})['id']

    # Owner gets own_book_id + catalog_id + my_page; another member gets catalog_id + own my_page, never owner's book_id.
    card_a=next(e for e in get(api,a,'/v1/clubs')['items'] if e['id']==group)
    card_b=next(e for e in get(api,b,'/v1/clubs')['items'] if e['id']==group)
    assert card_a['my_page']==12 and card_a['book']['catalog_id']=='src-1:cat-42' and card_a['book']['own_book_id']=='book'
    assert card_b['my_page']==0 and card_b['book']['catalog_id']=='src-1:cat-42' and card_b['book']['own_book_id']==''
    detail_b=get(api,b,f'/v1/clubs/{group}')
    assert detail_b['proposals'][0]['catalog_id']=='src-1:cat-42' and detail_b['proposals'][0]['own_book_id']==''

    s1=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':40,'discuss_at':future(5),'is_open':True})['id']
    s2=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':80,'discuss_at':future(10),'is_open':True})['id']
    call(api,b,f'/v1/clubs/{group}/stages/{s1}/messages',{'body':'Comentário preservado'})

    # Admin can update page_limit (e.g. 40 -> 47) preserving messages.
    call(api,a,f'/v1/clubs/{group}/stages/{s1}',{'page_limit':47,'discuss_at':future(6),'is_open':True},'PATCH')
    stages=get(api,a,f'/v1/clubs/{group}')['stages']
    assert [st['page_limit'] for st in stages]==[47,80]
    msgs=get(api,a,f'/v1/clubs/{group}/stages/{s1}/messages')
    assert msgs['stage']['page_limit']==47 and len(msgs['items'])==1

    # Duplicate page_limit -> 409; exceeding book pages -> 422; non-admin -> 403; cross-club -> 404.
    call(api,a,f'/v1/clubs/{group}/stages/{s1}',{'page_limit':80,'discuss_at':future(6),'is_open':True},'PATCH',409)
    call(api,a,f'/v1/clubs/{group}/stages/{s1}',{'page_limit':101,'discuss_at':future(6),'is_open':True},'PATCH',422)
    call(api,b,f'/v1/clubs/{group}/stages/{s1}',{'page_limit':48,'discuss_at':future(6),'is_open':True},'PATCH',403)
    call(api,b,f'/v1/clubs/{group}/stages/{s1}',method='DELETE',code=403)
    call(api,a,f'/v1/clubs/{group}/stages/{other_stage}',method='DELETE',code=404)

    # Admin can delete a stage in an active reading round; messages & reads cascade cleanly.
    call(api,a,f'/v1/clubs/{group}/stages/{s1}',method='DELETE')
    assert [st['id'] for st in get(api,a,f'/v1/clubs/{group}')['stages']]==[s2]
    get(api,a,f'/v1/clubs/{group}/stages/{s1}/messages',404)

    # Completed reading rounds remain immutable (cannot edit or delete stages).
    call(api,a,f'/v1/clubs/{group}/complete')
    call(api,a,f'/v1/clubs/{group}/stages/{s2}',{'page_limit':85,'discuss_at':future(10),'is_open':True},'PATCH',409)
    call(api,a,f'/v1/clubs/{group}/stages/{s2}',method='DELETE',code=409)


