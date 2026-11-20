import concurrent.futures
from fastapi.testclient import TestClient
from app.main import create_app
from app.security import RateLimits
from test_api import DSN,TOKEN,AUTH
from test_accounts import admin,invited,headers

def test_limits_are_atomic_bounded_and_expire():
    now=[0];limits=RateLimits(maximum=3,clock=lambda:now[0])
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        results=list(pool.map(lambda _:limits.allow('same',10),range(80)))
    assert sum(results)==10
    now[0]=60;assert limits.allow('same',10)
    for i in range(100):limits.allow(i,1)
    assert len(limits.buckets)==3

def test_private_headers_small_auth_bodies_and_forged_forwarding(tmp_path):
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as api:
        assert api.get('/v1/auth/info').headers['cache-control']=='no-store'
        assert api.get('/v1/me').headers['cache-control']=='no-store'
        oversized=api.post('/v1/auth/login',content=b'x'*16385)
        assert oversized.status_code==413 and oversized.headers['cache-control']=='no-store'
        assert api.post('/v1/auth/login',content=b'',headers={'content-length':'-1'}).status_code==413
        for i in range(117):
            assert api.get('/v1/auth/info',headers={'X-Forwarded-For':f'10.0.0.{i}'}).status_code==200
        blocked=api.get('/v1/auth/info',headers={'X-Forwarded-For':'8.8.8.8'})
        assert blocked.status_code==429 and blocked.headers['retry-after']=='60'

def test_account_limits_shared_between_sessions_and_independent_users(tmp_path):
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as api:
        owner=admin(api);a=invited(api,owner);b=invited(api,owner)
        # Account quota keys are validated IDs, never raw attacker-controlled tokens.
        for _ in range(60):assert api.get('/v1/me/library/status',headers=headers(a)).status_code==200
        assert api.get('/v1/me/library/status',headers=headers(a)).status_code==429
        assert api.get('/v1/me/library/status',headers=headers(b)).status_code==200
        limited=api.app.state.rate_limits
        limited.allow(('peer','unused','read'),1)
        assert api.get('/v1/health',headers=AUTH).status_code==200

def test_concurrency_refuses_excess_and_releases_slots():
    import asyncio
    from app.security import SecurityGate
    async def scenario():
        entered=0;release=asyncio.Event()
        async def downstream(scope,receive,send):
            nonlocal entered
            entered+=1
            await release.wait()
            await send({'type':'http.response.start','status':200,'headers':[]})
            await send({'type':'http.response.body','body':b'ok'})
        gate=SecurityGate(downstream,RateLimits());responses=[]
        scope={'type':'http','path':'/v1/me/library/sync','method':'POST','client':('test',1)}
        async def send(message):responses.append(message)
        async def receive():return {'type':'http.request','body':b''}
        tasks=[asyncio.create_task(gate(scope,receive,send)) for _ in range(4)]
        await asyncio.sleep(0)
        assert entered==4
        await gate(scope,receive,send)
        assert [m['status'] for m in responses if m['type']=='http.response.start']==[503]
        release.set();await asyncio.gather(*tasks)
        await gate(scope,receive,send)
        assert entered==5
    asyncio.run(scenario())


def test_invalid_forms_do_not_echo_credentials(tmp_path):
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as api:
        response=api.post('/v1/auth/login',json={'identifier':'private-reader','password':'short-xyz'})
        assert response.status_code==422
        assert 'short-xyz' not in response.text and 'private-reader' not in response.text
        assert response.headers['cache-control']=='no-store'


def test_sse_stream_classification_handles_non_hyphenated_uuids():
    from app.security import is_discussion_stream
    u1='11111111111141118111111111111111'
    u2='22222222222242228222222222222222'
    assert is_discussion_stream(f'/v1/clubs/{u1}/stages/{u2}/events', 'GET')
    assert is_discussion_stream('/v1/clubs/11111111-1111-4111-8111-111111111111/stages/22222222-2222-4222-8222-222222222222/events', 'GET')
    assert not is_discussion_stream(f'/v1/clubs/{u1}/stages/{u2}/events', 'POST')


def test_non_ascii_bearer_and_bigint_bounds_and_v1_covers_cache(tmp_path):
    import gzip
    from app.personal_library import unpack
    from fastapi import HTTPException
    import pytest
    with TestClient(create_app(DSN,TOKEN,tmp_path,'http://localhost:8088')) as api:
        # Non-ASCII bearer must return 401, never 500 TypeError
        bad=api.get('/v1/health',headers={'Authorization':'Bearer \xe9\xe9\xe9'})
        assert bad.status_code==401
        # Unauthenticated sync rejected before reading body
        unauth_sync=api.post('/v1/me/library/sync',content=b'{}')
        assert unauth_sync.status_code==401
        # Out-of-range 64-bit bigint cursor returns 422, not 503
        big=api.get('/v1/books?q=teste&cursor=9999999999999999999',headers=AUTH)
        assert big.status_code==422
        # /v1/covers/* keeps immutable Cache-Control
        cover_name='a'*32+'.jpg'
        (tmp_path/cover_name).write_bytes(b'\xff\xd8\xff\xd9')
        cov=api.get('/v1/covers/'+cover_name)
        assert cov.status_code==200 and 'immutable' in cov.headers.get('cache-control','')
    # NaN and deep nesting rejected in unpack()
    with pytest.raises(HTTPException) as exc1:
        unpack(gzip.compress(b'{"a": NaN}'))
    assert exc1.value.status_code==422
    with pytest.raises(HTTPException) as exc2:
        unpack(gzip.compress(('['*20 + ']'*20).encode()))
    assert exc2.value.status_code==422

