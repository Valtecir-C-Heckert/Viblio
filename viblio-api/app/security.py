"""Bounded admission controls for the documented single-worker deployment.

Only direct socket peers and authenticated account IDs form buckets. Never trust
arbitrary forwarding headers or raw bearer strings supplied by callers.
"""
import ipaddress
import os
import re
import hashlib
import threading
import time
from collections import OrderedDict
from fastapi import HTTPException

def is_discussion_stream(path, method):
    return method=='GET' and re.fullmatch(r'/v1/clubs/[a-fA-F0-9-]{32,36}/stages/[a-fA-F0-9-]{32,36}/events',path) is not None

def client_peer(peer, headers=None):
    """Use direct socket peer by default; only honor CF-Connecting-IP when explicitly enabled on loopback."""
    host = peer or 'local'
    if os.environ.get('VIBLIO_TRUST_CF_IP') == '1' and host in ('127.0.0.1', '::1') and headers is not None:
        raw = None
        if isinstance(headers, dict):
            raw = headers.get('cf-connecting-ip') or headers.get('CF-Connecting-IP')
        else:
            for k, v in headers:
                if k.lower() == b'cf-connecting-ip':
                    raw = v.decode('latin-1', errors='ignore')
                    break
        if raw and len(raw) <= 64:
            try:
                return str(ipaddress.ip_address(raw.strip()))
            except ValueError:
                pass
    return host

def is_private_cover_get(path, method):
    if method != 'GET':
        return False
    return (
        (path.startswith('/v1/friends/') and path.endswith('/cover'))
        or (path.startswith('/v1/clubs/') and (path.endswith('/cover') or path.endswith('/library-cover')))
    )

class RateLimits:
    def __init__(self, maximum=8192, clock=time.monotonic):
        self.maximum=maximum; self.clock=clock; self.buckets=OrderedDict(); self.lock=threading.Lock()
    def allow(self, key, limit, seconds=60):
        now=self.clock()
        with self.lock:
            count,start=self.buckets.pop(key,(0,now))
            if now-start>=seconds:count,start=0,now
            self.buckets[key]=(count+1,start)
            while len(self.buckets)>self.maximum:self.buckets.popitem(last=False)
            return count<limit
    def account(self, user, request):
        path=request.url.path
        group,limit=(
            ('club-live',12) if is_discussion_stream(path,request.method)
            else ('friend-cover',240) if is_private_cover_get(path,request.method)
            else ('club-write',30) if path.startswith('/v1/clubs') and request.method!='GET'
            else ('clubs',60) if path.startswith('/v1/clubs')
            else ('feed-reaction',60) if path.startswith('/v1/friends/feed/') and path.endswith('/reaction') and request.method!='GET'
            else ('friend-write',20) if path.startswith('/v1/friends') and request.method!='GET'
            else ('friends',60) if path.startswith('/v1/friends')
            else ('media-write',180) if path.startswith('/v1/me/media') and request.method!='GET'
            else ('media-read',300) if path.startswith('/v1/me/media')
            else ('sync',20) if path.endswith('/library/sync')
            else ('library',60) if '/library' in path
            else ('catalog',120) if path.startswith('/v1/books')
            else ('account',60)
        )
        if not self.allow(('user',str(user['id']),group),limit):
            raise HTTPException(429,'Muitas requisições. Aguarde um minuto.',headers={'Retry-After':'60'})

class SecurityGate:
    def __init__(self, app, limits):
        self.app=app;self.limits=limits
        self.total=threading.BoundedSemaphore(16)
        self.streams=threading.BoundedSemaphore(32)
        self.bulk=threading.BoundedSemaphore(4)
        self.auth=threading.BoundedSemaphore(4)
    async def __call__(self, scope, receive, send):
        if scope['type']!='http':return await self.app(scope,receive,send)
        path=scope.get('path','');raw_peer=(scope.get('client') or ('local',0))[0]
        peer=client_peer(raw_peer, scope.get('headers', []))
        stream=is_discussion_stream(path,scope['method'])
        cover_get=is_private_cover_get(path,scope['method'])
        group,limit=('live',120) if stream else ('auth',120) if path.startswith('/v1/auth/') else ('read',600) if cover_get else ('bulk',120) if path.startswith(('/v1/friends','/v1/clubs')) else ('bulk',60) if path.endswith('/library/sync') or (path=='/v1/books' and scope['method']=='POST') else ('read',600)
        key=hashlib.sha256(peer.encode()).hexdigest()
        async def reject(status,message):
            import json
            await send({'type':'http.response.start','status':status,'headers':[(b'content-type',b'application/json'),(b'cache-control',b'no-store'),(b'retry-after',b'60')]})
            await send({'type':'http.response.body','body':json.dumps({'detail':message}).encode()})
        if not self.limits.allow(('peer',key,group),limit):return await reject(429,'Muitas requisições. Aguarde um minuto.')
        admission=self.streams if stream else self.total
        if not admission.acquire(blocking=False):return await reject(503,'Servidor ocupado. Tente novamente.')
        gate=self.bulk if group=='bulk' else self.auth if group=='auth' else None
        acquired=False
        try:
            if gate:
                acquired=gate.acquire(blocking=False)
                if not acquired:return await reject(503,'Servidor ocupado. Tente novamente.')
            async def headers(message):
                if message['type']=='http.response.start':
                    values=list(message.get('headers',[]))
                    # Includes errors and gzip snapshots. Private responses never enter shared caches;
                    # content-addressed /v1/me/media/ GET keeps its own private immutable header.
                    is_private_media_get = scope['method']=='GET' and path.startswith('/v1/me/media/') and message.get('status')==200
                    if not path.startswith(('/covers/','/v1/covers/')) and not is_private_media_get:
                        values=[(k,v) for k,v in values if k.lower()!=b'cache-control']
                        values.extend([(b'cache-control',b'no-store'),(b'pragma',b'no-cache')])
                    values.extend([(b'x-content-type-options',b'nosniff'),(b'referrer-policy',b'no-referrer')])
                    message={**message,'headers':values}
                await send(message)
            await self.app(scope,receive,headers)
        finally:
            if acquired:gate.release()
            admission.release()
