"""Exercise actual streaming HTTP, not TestClient's buffered response body."""
import asyncio
import socket
import threading
import time
from contextlib import ExitStack, contextmanager
import httpx
import pytest
import uvicorn
from app.live import DiscussionEvents
from test_accounts import api, headers, snapshot, sync, result
from test_friends import setup
from test_social import call, get, club, invite, future


def test_notifications_are_scoped_coalesced_and_bounded():
    async def scenario():
        bus=DiscussionEvents()
        one,q=bus.subscribe('club','stage','a')
        other,other_q=bus.subscribe('other','stage','b')
        for _ in range(100):bus.publish('club','stage')
        await asyncio.sleep(0)
        assert q.qsize()==1 and other_q.empty()
        assert await q.get() is True
        keys=[bus.subscribe('club',str(i),'a')[0] for i in range(2)]
        with pytest.raises(Exception) as failure:bus.subscribe('club','extra','a')
        assert failure.value.status_code==429
        for key in [one,other,*keys]:bus.unsubscribe(key)
        assert not bus.listeners
    asyncio.run(scenario())


@contextmanager
def live_server(app):
    sock=socket.socket();sock.bind(('127.0.0.1',0));sock.listen(64)
    server=uvicorn.Server(uvicorn.Config(app,log_level='critical',lifespan='off'))
    thread=threading.Thread(target=server.run,kwargs={'sockets':[sock]},daemon=True);thread.start()
    try:
        deadline=time.monotonic()+5
        while not server.started:
            assert thread.is_alive() and time.monotonic()<deadline
            time.sleep(.01)
        with httpx.Client(base_url=f'http://127.0.0.1:{sock.getsockname()[1]}',timeout=5) as client:yield client
    finally:
        server.should_exit=True;thread.join(8)
        if thread.is_alive():server.force_exit=True;thread.join(2)
        sock.close()
        assert not thread.is_alive()


def event(lines):
    for line in lines:
        if line.startswith('event: '):return line[7:]
    raise AssertionError('Stream ended without event')


def reading(api):
    a,b,c=setup(api);group=club(api,a);invite(api,a,b,group)
    result(sync(api,a,snapshot()))
    proposal=call(api,a,f'/v1/clubs/{group}/proposals',{'book_id':'book'})['id']
    call(api,a,f'/v1/clubs/{group}/vote',{'proposal_id':proposal})
    call(api,a,f'/v1/clubs/{group}/start',{'proposal_id':proposal,'due_at':future(30)})
    stage=call(api,a,f'/v1/clubs/{group}/stages',{'page_limit':50,'discuss_at':future(10),'is_open':True})['id']
    return a,b,c,group,stage


def test_stream_delivers_committed_create_delete_and_revocation(api):
    a,b,outsider,group,stage=reading(api)
    base=f'/v1/clubs/{group}/stages/{stage}'
    assert api.get('/v1/auth/info').json()['club_events_api']==1
    with live_server(api.app) as client:
        assert client.get(base+'/events',headers=headers(outsider)).status_code==403
        assert client.get(base+'/events').status_code==401
        with client.stream('GET',base+'/events',headers=headers(b)) as response:
            assert response.status_code==200 and response.headers['cache-control']=='no-store'
            lines=response.iter_lines();assert event(lines)=='ready'
            call(api,a,base+'/messages',{'body':'Immediate committed comment'})
            assert event(lines)=='changed'
            messages=get(api,b,base+'/messages')['items']
            assert messages[0]['body']=='Immediate committed comment'
            call(api,a,base+f"/messages/{messages[0]['id']}",method='DELETE')
            assert event(lines)=='changed' and get(api,b,base+'/messages')['items']==[]
            call(api,a,f"/v1/clubs/{group}/members/{b['user']['id']}",method='DELETE')
            assert event(lines)=='revoked'
            assert list(lines)==['data: {}','']
        assert not api.app.state.discussion_events.listeners


def test_stream_limit_preserves_regular_requests_and_releases_slots(api):
    a,b,c,group,stage=reading(api);path=f'/v1/clubs/{group}/stages/{stage}/events'
    with live_server(api.app) as client:
        with ExitStack() as stack:
            streams=[]
            for user in [a,a,a,b,b,b]:
                response=stack.enter_context(client.stream('GET',path,headers=headers(user)))
                streams.append(response.iter_lines())
                assert response.status_code==200 and event(streams[-1])=='ready'
            assert client.get(path,headers=headers(a)).status_code==429
            assert get(api,b,f'/v1/clubs/{group}')['id']==group
            call(api,a,path.removesuffix('/events')+'/messages',{'body':'Writes still work'})
        deadline=time.monotonic()+3
        while api.app.state.discussion_events.listeners and time.monotonic()<deadline:time.sleep(.01)
        assert not api.app.state.discussion_events.listeners
        with client.stream('GET',path,headers=headers(a)) as response:
            assert response.status_code==200 and event(response.iter_lines())=='ready'


def test_stream_closes_when_discussion_is_closed(api):
    a,b,c,group,stage=reading(api);base=f'/v1/clubs/{group}/stages/{stage}'
    with live_server(api.app) as client:
        with client.stream('GET',base+'/events',headers=headers(b)) as response:
            lines=response.iter_lines();assert event(lines)=='ready'
            call(api,a,base,{'page_limit':50,'discuss_at':future(10),'is_open':False},'PATCH')
            assert event(lines)=='revoked'
            assert client.get(base+'/messages',headers=headers(b)).status_code==403


def test_idle_stream_rechecks_expired_or_logged_out_sessions(api,monkeypatch):
    monkeypatch.setattr('app.clubs.DISCUSSION_HEARTBEAT',.05)
    a,b,c,group,stage=reading(api);base=f'/v1/clubs/{group}/stages/{stage}'
    with live_server(api.app) as client:
        with client.stream('GET',base+'/events',headers=headers(b)) as response:
            lines=response.iter_lines();assert event(lines)=='ready'
            call(api,b,'/v1/auth/logout')
            assert event(lines)=='revoked'
