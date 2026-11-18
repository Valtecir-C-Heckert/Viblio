"""Bounded admission controls for the documented single-worker deployment.

Only direct socket peers and authenticated account IDs form buckets. Never trust
arbitrary forwarding headers or raw bearer strings supplied by callers.
"""
import re
import hashlib
import threading
import time
from collections import OrderedDict
from fastapi import HTTPException

def is_discussion_stream(path, method):
    return method=='GET' and re.fullmatch(r'/v1/clubs/[a-fA-F0-9-]{36}/stages/[a-fA-F0-9-]{36}/events',path) is not None

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
        group,limit=('club-live',12) if is_discussion_stream(path,request.method) else ('club-write',30) if path.startswith('/v1/clubs') and request.method!='GET' else ('clubs',60) if path.startswith('/v1/clubs') else ('friend-write',20) if path.startswith('/v1/friends') and request.method!='GET' else ('friend-cover',90) if path.startswith('/v1/friends/') and path.endswith('/cover') else ('friends',60) if path.startswith('/v1/friends') else ('sync',20) if path.endswith('/library/sync') else ('library',60) if '/library' in path else ('catalog',120) if path.startswith('/v1/books') else ('account',60)
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
        path=scope.get('path','');peer=(scope.get('client') or ('local',0))[0]
        stream=is_discussion_stream(path,scope['method'])
        group,limit=('live',120) if stream else ('auth',120) if path.startswith('/v1/auth/') else ('bulk',120) if path.startswith(('/v1/friends','/v1/clubs')) else ('bulk',60) if path.endswith('/library/sync') or (path=='/v1/books' and scope['method']=='POST') else ('read',600)
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
                    # Includes errors and gzip snapshots. Private responses never enter caches.
                    if not path.startswith('/covers/'):
                        values=[(k,v) for k,v in values if k.lower()!=b'cache-control']
                        values.extend([(b'cache-control',b'no-store'),(b'pragma',b'no-cache')])
                    values.extend([(b'x-content-type-options',b'nosniff'),(b'referrer-policy',b'no-referrer')])
                    message={**message,'headers':values}
                await send(message)
            await self.app(scope,receive,headers)
        finally:
            if acquired:gate.release()
            admission.release()
