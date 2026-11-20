"""Invitation-only accounts and expiring opaque sessions."""
import base64
import hashlib
import hmac
import re
import secrets
import uuid
from datetime import datetime,timedelta,timezone
from typing import Annotated
import psycopg
from fastapi import Depends,HTTPException,Request
from fastapi.security import HTTPAuthorizationCredentials,HTTPBearer
from pydantic import BaseModel,ConfigDict,Field,field_validator,model_validator


def digest(value): return hashlib.sha256(value.encode()).hexdigest()
def password_hash(password,salt=None):
    salt=salt or secrets.token_bytes(16)
    key=hashlib.scrypt(password.encode(),salt=salt,n=16384,r=8,p=1,maxmem=64*1024*1024,dklen=32)
    return 'scrypt$16384$'+base64.b64encode(salt).decode()+'$'+base64.b64encode(key).decode()
def password_matches(password,encoded):
    try:
        _,cost,salt,_=encoded.split('$')
        return cost=='16384' and hmac.compare_digest(password_hash(password,base64.b64decode(salt)),encoded)
    except (ValueError,TypeError): return False
def normalize_username(value):
    if value is None:return None
    value=value.strip().lower()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_.]{2,31}',value):raise ValueError('Use de 3 a 32 letras, números, ponto ou sublinhado, começando com letra ou número.')
    return value
class Username(BaseModel):
    model_config=ConfigDict(extra='forbid')
    username:str
    _username=field_validator('username')(normalize_username)
class SignIn(BaseModel):
    model_config=ConfigDict(extra='forbid')
    identifier:str|None=Field(default=None,min_length=3,max_length=254)
    email:str|None=Field(default=None,min_length=3,max_length=254)
    password:str=Field(min_length=12,max_length=128)
    @model_validator(mode='after')
    def identity(self):
        if bool(self.identifier)==bool(self.email):raise ValueError('Informe nome de usuário ou e-mail.')
        self.identifier=(self.identifier or self.email).strip().lower()
        return self
class Login(BaseModel):
    model_config=ConfigDict(extra='forbid')
    email:str=Field(min_length=3,max_length=254)
    username:str|None=None
    _username=field_validator('username')(normalize_username)
    password:str=Field(min_length=12,max_length=128)
    @field_validator('email')
    @classmethod
    def normalize(cls,value):
        value=value.strip().lower()
        if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',value): raise ValueError('Confira o e-mail.')
        return value
class Register(Login): invitation:str=Field(min_length=32,max_length=128)
class Reset(BaseModel):
    model_config=ConfigDict(extra='forbid')
    code:str=Field(min_length=32,max_length=128)
    password:str=Field(min_length=12,max_length=128)
class InviteInput(BaseModel): days:int=Field(default=7,ge=1,le=30)

def mount_accounts(app,connect,install_key,source,limits):
    bearer=HTTPBearer(auto_error=False)
    def lookup(token,request=None):
        if not token or len(token)>200: raise HTTPException(401,'Entre novamente para acessar sua conta.')
        with connect() as db:
            user=db.execute('SELECT u.id,u.email,u.is_admin,u.username FROM public.viblio_users u JOIN public.viblio_sessions s ON s.user_id=u.id WHERE s.token_hash=%s AND s.expires_at>now()',(digest(token),)).fetchone()
        if not user: raise HTTPException(401,'Sua sessão expirou. Entre novamente; a Estante local está preservada.')
        if request is not None:limits.account(user,request)
        return user
    def current(request:Request,credentials:Annotated[HTTPAuthorizationCredentials|None,Depends(bearer)]):return lookup(credentials.credentials if credentials else '',request)
    def admin(user=Depends(current)):
        if not user['is_admin']:raise HTTPException(403,'Apenas o administrador pode fazer isso.')
        return user
    def installation(credentials:Annotated[HTTPAuthorizationCredentials|None,Depends(bearer)]):
        if not credentials or not hmac.compare_digest(credentials.credentials,install_key):raise HTTPException(401,'Chave da instalação inválida.')
    def public(user):return dict(id=str(user['id']),email=user['email'],admin=user['is_admin'],server_id=source,username=user.get('username'))
    def session(db,user):
        token=secrets.token_urlsafe(32)
        db.execute('DELETE FROM public.viblio_sessions WHERE expires_at<now()')
        db.execute('INSERT INTO public.viblio_sessions(token_hash,user_id) VALUES (%s,%s)',(digest(token),user['id']))
        return dict(token=token,user=public(user))
    def throttle(request,email,action):
        peer=request.client.host if request.client else 'local'
        with connect() as db:
            db.execute("DELETE FROM public.viblio_auth_attempts WHERE started_at<now()-interval '1 day'")
            denied=False
            for bucket,limit in [(digest(action+':email:'+email),12),(digest(action+':peer:'+peer),120)]:
                row=db.execute("INSERT INTO public.viblio_auth_attempts(bucket,attempts) VALUES (%s,1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN viblio_auth_attempts.started_at<now()-interval '15 minutes' THEN 1 ELSE viblio_auth_attempts.attempts+1 END,started_at=CASE WHEN viblio_auth_attempts.started_at<now()-interval '15 minutes' THEN now() ELSE viblio_auth_attempts.started_at END RETURNING attempts",(bucket,)).fetchone()
                denied |= row['attempts']>limit
        if denied:raise HTTPException(429,'Muitas tentativas. Aguarde 15 minutos.',headers={'Retry-After':'900'})
    @app.get('/v1/auth/info')
    def info():
        with connect() as db:ready=db.execute('SELECT EXISTS(SELECT 1 FROM public.viblio_users) AS ready').fetchone()['ready']
        return dict(api_version=11,account_api=1,username_login=True,friends_api=1,feed_api=1,clubs_api=3,club_events_api=1,recommendations_api=1,wishlist_api=1,server_id=source,initialized=ready,registration='invitation')
    @app.post('/v1/auth/bootstrap',dependencies=[Depends(installation)])
    def bootstrap(body:Login):
        hashed=password_hash(body.password)
        with connect() as db:
            db.execute("SELECT pg_advisory_xact_lock(hashtextextended('viblio-first-account',0))")
            if db.execute('SELECT 1 FROM public.viblio_users LIMIT 1').fetchone():raise HTTPException(409,'A conta administradora já foi criada. Use um convite.')
            user=db.execute('INSERT INTO public.viblio_users(id,email,password_hash,is_admin,username) VALUES (%s,%s,%s,true,%s) RETURNING id,email,is_admin,username',(uuid.uuid4(),body.email,hashed,body.username)).fetchone()
            return session(db,user)
    @app.post('/v1/auth/register')
    def register(body:Register,request:Request):
        throttle(request,body.email,'register');hashed=password_hash(body.password)
        try:
            with connect() as db:
                invite=db.execute('SELECT * FROM public.viblio_invites WHERE token_hash=%s FOR UPDATE',(digest(body.invitation),)).fetchone()
                if not invite or invite['revoked'] or invite['used_by'] or invite['expires_at']<=datetime.now(timezone.utc):raise HTTPException(400,'Convite inválido, expirado ou já utilizado.')
                user=db.execute('INSERT INTO public.viblio_users(id,email,password_hash,username) VALUES (%s,%s,%s,%s) RETURNING id,email,is_admin,username',(uuid.uuid4(),body.email,hashed,body.username)).fetchone()
                db.execute('UPDATE public.viblio_invites SET used_by=%s,used_at=now() WHERE id=%s',(user['id'],invite['id']))
                return session(db,user)
        except psycopg.errors.UniqueViolation:raise HTTPException(409,'Nome de usuário ou e-mail já utilizado. Se já tem conta, use Entrar.') from None
    @app.post('/v1/auth/login')
    def login(body:SignIn,request:Request):
        throttle(request,body.identifier,'login')
        with connect() as db:
            user=db.execute('SELECT id,email,is_admin,username,password_hash FROM public.viblio_users WHERE email=%s OR lower(username)=%s',(body.identifier,body.identifier)).fetchone()
            dummy='scrypt$16384$MDAwMDAwMDAwMDAwMDAwMA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
            matched=password_matches(body.password,user['password_hash'] if user else dummy)
            if not user or not matched:raise HTTPException(401,'Usuário, e-mail ou senha inválidos.')
            return session(db,user)
    @app.get('/v1/me')
    def me(user=Depends(current)):return public(user)
    @app.patch('/v1/me')
    def update_name(body:Username,user=Depends(current)):
        try:
            with connect() as db:
                updated=db.execute('UPDATE public.viblio_users SET username=%s WHERE id=%s RETURNING id,email,is_admin,username',(body.username,user['id'])).fetchone()
                return public(updated)
        except psycopg.errors.UniqueViolation:raise HTTPException(409,'Esse nome de usuário já está em uso.') from None
    @app.post('/v1/auth/logout')
    def logout(user=Depends(current),credentials:Annotated[HTTPAuthorizationCredentials|None,Depends(bearer)]=None):
        with connect() as db:db.execute('DELETE FROM public.viblio_sessions WHERE token_hash=%s AND user_id=%s',(digest(credentials.credentials),user['id']))
        return {'ok':True}
    @app.post('/v1/admin/invitations')
    def invitation(body:InviteInput,user=Depends(admin)):
        code=secrets.token_urlsafe(32);identifier=uuid.uuid4()
        with connect() as db:row=db.execute('INSERT INTO public.viblio_invites(id,token_hash,created_by,expires_at) VALUES (%s,%s,%s,%s) RETURNING expires_at',(identifier,digest(code),user['id'],datetime.now(timezone.utc)+timedelta(days=body.days))).fetchone()
        return dict(id=str(identifier),code=code,expires_at=row['expires_at'])
    @app.get('/v1/admin/invitations')
    def invitations(user=Depends(admin)):
        with connect() as db:rows=db.execute('SELECT id,expires_at,used_at,revoked FROM public.viblio_invites WHERE created_by=%s ORDER BY created_at DESC LIMIT 100',(user['id'],)).fetchall()
        return {'items':rows}
    @app.delete('/v1/admin/invitations/{identifier}')
    def revoke(identifier:uuid.UUID,user=Depends(admin)):
        with connect() as db:db.execute('UPDATE public.viblio_invites SET revoked=true WHERE id=%s AND created_by=%s',(identifier,user['id']))
        return {'ok':True}
    @app.get('/v1/admin/users')
    def users(user=Depends(admin)):
        with connect() as db:rows=db.execute('SELECT id,email,is_admin FROM public.viblio_users ORDER BY created_at LIMIT 1000').fetchall()
        return {'items':rows}
    @app.post('/v1/admin/users/{identifier}/recovery')
    def recovery(identifier:uuid.UUID,user=Depends(admin)):
        code=secrets.token_urlsafe(32)
        with connect() as db:
            if not db.execute('SELECT 1 FROM public.viblio_users WHERE id=%s',(identifier,)).fetchone():raise HTTPException(404,'Conta não encontrada.')
            db.execute('DELETE FROM public.viblio_recovery WHERE user_id=%s',(identifier,))
            db.execute('INSERT INTO public.viblio_recovery(token_hash,user_id) VALUES (%s,%s)',(digest(code),identifier))
        return dict(code=code,valid_hours=24)
    @app.post('/v1/auth/reset-password')
    def reset(body:Reset,request:Request):
        throttle(request,digest(body.code),'reset');hashed=password_hash(body.password)
        with connect() as db:
            row=db.execute('SELECT user_id FROM public.viblio_recovery WHERE token_hash=%s AND expires_at>now() FOR UPDATE',(digest(body.code),)).fetchone()
            if not row:raise HTTPException(400,'Código de recuperação inválido ou expirado.')
            user=db.execute('UPDATE public.viblio_users SET password_hash=%s WHERE id=%s RETURNING id,email,is_admin,username',(hashed,row['user_id'])).fetchone()
            db.execute('DELETE FROM public.viblio_sessions WHERE user_id=%s',(user['id'],));db.execute('DELETE FROM public.viblio_recovery WHERE user_id=%s',(user['id'],))
            return session(db,user)
    return lookup,current
