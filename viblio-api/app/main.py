"""Independent catalogue API; personal reading data remains in the Android SQLite library."""
import asyncio
import time
import hashlib
import hmac
import io
import json
import os
import re
import unicodedata
import uuid
import warnings
from contextlib import asynccontextmanager, contextmanager
from decimal import Decimal
from pathlib import Path
import queue
from typing import Annotated, Literal

import psycopg
from psycopg.rows import dict_row
from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, UploadFile, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.exceptions import RequestValidationError
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, ValidationError

MAX_UPLOAD = 12 * 1024 * 1024
MAX_BODY = MAX_UPLOAD + 64 * 1024
Image.MAX_IMAGE_PIXELS = 20_000_000
COLUMNS = 'id,titulo,autor,genero,subgenero,metricas_totais,metrica_tipo,saga,editora,capa_url,categoria,ordem,isbn,tipo'


def normalize(text):
    return ''.join(c for c in unicodedata.normalize('NFKD', text.strip().lower()) if not unicodedata.combining(c))


def isbn_key(text):
    return re.sub(r'[^0-9X]', '', text.upper())


def image_bytes(content):
    if not content or len(content) > MAX_UPLOAD:
        raise HTTPException(413 if len(content) > MAX_UPLOAD else 422, 'Capa vazia ou maior que 12 MB.')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(content)) as check:
                if check.format not in ('JPEG', 'PNG', 'WEBP'):
                    raise HTTPException(422, 'Use uma imagem JPEG, PNG ou WebP.')
                check.verify()
            with Image.open(io.BytesIO(content)) as image:
                image.load()
                image.thumbnail((2048, 2048))
                rgb = Image.new('RGB', image.size, 'white')
                if image.mode == 'RGBA':
                    rgb.paste(image, mask=image.getchannel('A'))
                else:
                    rgb.paste(image.convert('RGB'))
                output = io.BytesIO(); rgb.save(output, 'JPEG', quality=92)
                return output.getvalue()
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(422, 'Arquivo de imagem inválido.') from None


def present(row):
    metric = normalize(row['metrica_tipo'] or '')
    pages = row['metricas_totais'] if metric in ('pagina', 'paginas', 'page', 'pages') else None
    order = row['ordem']
    return {'id': str(row['id']), 'title': row['titulo'], 'author': row['autor'],
            'genre': row['genero'], 'subgenre': row['subgenero'], 'publisher': row['editora'],
            'saga': row['saga'], 'isbn': row['isbn'], 'cover_url': row['capa_url'],
            'category': row['categoria'], 'type': row['tipo'],
            'pages': pages if pages and 0 < pages <= 100000 else None,
            'sequence': int(order) if order is not None and order == int(order) and 1 <= order <= 10000 else None}


class BookInput(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    request_id: uuid.UUID
    catalog_id: str | None = Field(default=None, pattern=r'^-?[0-9]{1,19}$')
    title: str = Field(min_length=1, max_length=1000)
    author: str | None = Field(default=None, max_length=1000)
    genre: str | None = Field(default=None, max_length=300)
    subgenres: list[str] = Field(default_factory=list, max_length=3)
    publisher: str | None = Field(default=None, max_length=500)
    saga: str | None = Field(default=None, max_length=500)
    sequence: int | None = Field(default=None, ge=1, le=10000)
    pages: int | None = Field(default=None, ge=1, le=100000)
    isbn: str | None = Field(default=None, max_length=32)
    kind: Literal["book", "manga", "comic"] = "book"
    is_volume: bool = False


class BodyLimit:
    def __init__(self, app): self.app = app
    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http': return await self.app(scope, receive, send)
        headers = dict(scope.get('headers', []))
        try: declared = int(headers.get(b'content-length', b'0'))
        except ValueError: declared = MAX_BODY + 1
        limit=24*1024*1024 if scope.get('path')=='/v1/me/library/sync' else MAX_BODY if scope.get('path')=='/v1/books' and scope.get('method')=='POST' else 16*1024
        if declared < 0 or declared > limit:
            return await self.reject(send,scope.get('path')=='/v1/me/library/sync',limit)
        # Buffer a strictly bounded body before the multipart parser can spool unbounded input.
        body = bytearray(); size = 0; deadline=time.monotonic()+60
        while True:
            try:message = await asyncio.wait_for(receive(),timeout=max(0.001,min(15,deadline-time.monotonic())))
            except TimeoutError:
                await send({'type':'http.response.start','status':408,'headers':[(b'content-type',b'application/json')]})
                return await send({'type':'http.response.body','body':b'{"detail":"Tempo de envio excedido."}'})
            if message['type'] == 'http.disconnect': return
            size += len(message.get('body', b''))
            if size > limit: return await self.reject(send,scope.get('path')=='/v1/me/library/sync',limit)
            body.extend(message.get('body',b''))
            if not message.get('more_body'): break
        delivered = False
        async def limited_receive():
            nonlocal delivered
            if not delivered:
                delivered=True
                return {'type':'http.request','body':bytes(body),'more_body':False}
            return await receive()
        await self.app(scope, limited_receive, send)
    async def reject(self, send,library=False,limit=MAX_BODY):
        await send({'type': 'http.response.start', 'status': 413, 'headers': [(b'content-type', b'application/json')]})
        await send({'type': 'http.response.body', 'body': (b'{"detail":"Backup comprimido precisa ter ate 24 MB."}' if library else b'{"detail":"Requisicao maior que 16 KB."}' if limit==16*1024 else b'{"detail":"Envie uma capa de ate 12 MB."}')})


def create_app(database_url=None, api_token=None, storage=None, public_url=None):
    database_url = database_url or os.environ['DATABASE_URL']
    api_token = api_token or os.environ['VIBLIO_API_TOKEN']
    if len(api_token) < 32: raise ValueError('VIBLIO_API_TOKEN must have at least 32 characters')
    storage = Path(storage or os.environ.get('COVER_DIR', '/data/covers'))
    storage.mkdir(parents=True, exist_ok=True)
    public_url = (public_url or os.environ['PUBLIC_BASE_URL']).rstrip('/')
    if not re.match(r'^https?://[^/]+', public_url): raise ValueError('PUBLIC_BASE_URL must be an HTTP(S) URL')
    identity = storage / 'catalog-id'
    if not identity.exists():
        try:
            with identity.open('x') as file: file.write(str(uuid.uuid4()))
        except FileExistsError: pass
    catalog_source = identity.read_text().strip()
    pool_queue: queue.LifoQueue = queue.LifoQueue(maxsize=8)
    def close_pool():
        while True:
            try: conn, _ = pool_queue.get_nowait()
            except queue.Empty: break
            try: conn.close()
            except Exception: pass
    @asynccontextmanager
    async def lifespan(_app):
        try: yield
        finally: close_pool()
    @contextmanager
    def connect():
        conn = None
        now = time.monotonic()
        while conn is None:
            try: candidate, returned_at = pool_queue.get_nowait()
            except queue.Empty: break
            if candidate.closed or candidate.broken or now - returned_at > 120:
                try: candidate.close()
                except Exception: pass
            else:
                conn = candidate
        if conn is None:
            conn = psycopg.connect(database_url, row_factory=dict_row, connect_timeout=5)
        try:
            yield conn
            conn.commit()
        except BaseException:
            try:
                if not conn.closed and not conn.broken: conn.rollback()
            except Exception:
                pass
            if isinstance(conn, psycopg.Connection) and (conn.closed or conn.broken):
                try: conn.close()
                except Exception: pass
                conn = None
            raise
        finally:
            if conn is not None:
                if conn.closed or conn.broken:
                    try: conn.close()
                    except Exception: pass
                else:
                    try: pool_queue.put_nowait((conn, time.monotonic()))
                    except queue.Full:
                        try: conn.close()
                        except Exception: pass
    app = FastAPI(title='Viblio catalogue', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    from .security import RateLimits,SecurityGate
    limits=RateLimits();app.state.rate_limits=limits
    app.add_middleware(BodyLimit)
    app.add_middleware(SecurityGate,limits=limits)
    @app.exception_handler(RequestValidationError)
    async def invalid_fields(request,error):
        # FastAPI's default errors include raw input, which can contain a password.
        return JSONResponse(status_code=422,content={'detail':'Confira os campos informados.'})
    security = HTTPBearer(auto_error=False)
    def installation_only(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(security)]):
        if not credentials or not hmac.compare_digest(credentials.credentials, api_token):
            raise HTTPException(401, 'Chave de acesso inválida.', headers={'WWW-Authenticate': 'Bearer'})
    from .accounts import mount_accounts
    from .personal_library import mount_library
    lookup_user,current_user=mount_accounts(app,connect,api_token,catalog_source,limits)
    mount_library(app,connect,storage,current_user)
    from .friends import mount_friends
    mount_friends(app,connect,storage,current_user)
    from .activity import mount_activity
    mount_activity(app,connect,current_user)
    from .clubs import mount_clubs
    mount_clubs(app,connect,storage,current_user)
    from .recommendations import mount_recommendations
    mount_recommendations(app,connect,current_user,catalog_source,public_url,COLUMNS,present)
    def authenticate(request:Request,credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(security)]):
        if credentials and hmac.compare_digest(credentials.credentials,api_token):return
        lookup_user(credentials.credentials if credentials else '',request)
    @app.exception_handler(psycopg.Error)
    async def db_error(request, error):
        from fastapi.responses import JSONResponse
        return JSONResponse(status_code=503, content={'detail': 'Servidor indisponível. Tente novamente.'})
    @app.get('/v1/health', dependencies=[Depends(authenticate)])
    def health():
        with connect() as connection:
            connection.execute('SELECT 1 FROM public.valteca LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_api_requests LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_book_covers LIMIT 1')
            connection.execute('SELECT username FROM public.viblio_users LIMIT 1')
            connection.execute("SELECT public.viblio_cover_key('health')")
            connection.execute('SELECT status FROM public.viblio_friendships LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_library_bases LIMIT 1')
            connection.execute('SELECT share_activity,activity_enabled_at FROM public.viblio_users LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_activity LIMIT 1')
            connection.execute('SELECT book_id FROM public.viblio_club_progress LIMIT 1')
            connection.execute('SELECT source_user_id FROM public.viblio_club_proposals LIMIT 1')
            connection.execute('SELECT completed_at FROM public.viblio_club_rounds LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_club_events LIMIT 1')
            connection.execute('SELECT invited_by FROM public.viblio_club_members LIMIT 1')
            connection.execute('SELECT reply_to_id FROM public.viblio_club_messages LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_club_stage_reads LIMIT 1')
            connection.execute('SELECT 1 FROM public.viblio_wishlist LIMIT 1')
        return {'ok': True, 'catalog_source': catalog_source, 'api_version': 11, 'friends_api': 1, 'feed_api': 1, 'clubs_api': 3, 'club_events_api': 1, 'account_api': 1, 'recommendations_api': 1, 'wishlist_api': 1, 'username_login': True}
    @app.get('/v1/books', dependencies=[Depends(authenticate)])
    def search(q: str = Query('', max_length=300), author: str = Query('', max_length=300),
               isbn: str = Query('', max_length=32), limit: int = Query(20, ge=1, le=50),
               cursor: int | None = None):
        clauses = []; params = []
        def part(column, value):
            value = value.strip()
            if value:
                if len(value) < 3: raise HTTPException(422, 'Digite pelo menos três caracteres.')
                clauses.append(f"public.viblio_normalize({column}) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'")
                params.append('%' + value.replace('\\','\\\\').replace('%','\\%').replace('_','\\_') + '%')
        part('titulo', q); part('autor', author)
        if isbn.strip():
            key = isbn_key(isbn)
            if len(key) not in (10, 13): raise HTTPException(422, 'Informe ISBN com 10 ou 13 caracteres.')
            clauses.append("regexp_replace(upper(coalesce(isbn,'')), '[^0-9X]', '', 'g')=%s"); params.append(key)
        if not clauses: return {'items': [], 'next_cursor': None, 'catalog_source': catalog_source}
        if cursor is not None: clauses.append('id>%s'); params.append(cursor)
        params.append(limit + 1)
        with connect() as connection:
            rows = connection.execute(f"SELECT {COLUMNS} FROM public.valteca WHERE {' AND '.join(clauses)} ORDER BY id LIMIT %s", params).fetchall()
            from .cover_gallery import canonical_rows
            rows=canonical_rows(rows,connection,public_url)
        return {'items': [present(row) for row in rows[:limit]], 'next_cursor': str(rows[limit-1]['id']) if len(rows)>limit else None, 'catalog_source': catalog_source}
    @app.get('/v1/books/{book_id}/covers', dependencies=[Depends(authenticate)])
    def book_covers(book_id:int,limit:int=Query(30,ge=1,le=50),cursor:int=Query(0,ge=0,le=10000)):
        if not -(2**63)<=book_id<2**63:raise HTTPException(422,'Identificador inválido.')
        from .cover_gallery import gallery
        with connect() as connection:result=gallery(connection,book_id,public_url,limit,cursor)
        return dict(book_id=str(book_id),catalog_source=catalog_source,**result)
    @app.get('/v1/covers/{filename}')
    @app.get('/covers/{filename}')
    def cover(filename: str):
        if not re.fullmatch(r'[0-9a-f]{32}\.jpg', filename): raise HTTPException(404, 'Capa não encontrada.')
        path = storage / filename
        if not path.is_file() or path.is_symlink(): raise HTTPException(404, 'Capa não encontrada.')
        return FileResponse(path, media_type='image/jpeg', headers={'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'public, max-age=31536000, immutable'})
    @app.post('/v1/books', dependencies=[Depends(installation_only)])
    def create(metadata: Annotated[str, Form()], cover: Annotated[UploadFile | None, File()] = None):
        if len(metadata) > 16384: raise HTTPException(422, 'Metadados muito grandes.')
        try: book = BookInput.model_validate_json(metadata)
        except ValidationError: raise HTTPException(422, 'Confira os dados do livro.') from None
        if any(not tag.strip() or len(tag)>300 for tag in book.subgenres): raise HTTPException(422, 'Subgênero inválido.')
        if book.is_volume and book.sequence is None and book.catalog_id is None: raise HTTPException(422, 'Informe o número do volume antes de enviar ao catálogo.')
        content = cover.file.read(MAX_UPLOAD + 1) if cover else None
        # Format validation is also performed for existing-catalog personal cover overrides.
        converted = image_bytes(content) if content is not None else None
        fingerprint = hashlib.sha256(book.model_dump_json(exclude=({"kind"} if book.kind == "book" else set()) | ({"is_volume"} if not book.is_volume else set())).encode() + (content or b'')).hexdigest()
        request_id = str(book.request_id); filename = book.request_id.hex + '.jpg'; path = storage / filename
        uploaded_url = f'{public_url}/v1/covers/{filename}' if converted else None
        wrote = False
        try:
            with connect() as connection:
                connection.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s,0))', ('viblio-write',))
                previous = connection.execute('SELECT * FROM public.viblio_api_requests WHERE request_id=%s', (request_id,)).fetchone()
                if previous:
                    if previous['fingerprint'] != fingerprint: raise HTTPException(409, 'Esta tentativa já foi usada com outros dados.')
                    row = connection.execute(f'SELECT {COLUMNS} FROM public.valteca WHERE id=%s', (previous['book_id'],)).fetchone()
                    return {'book': present(row), 'uploaded_url': previous['uploaded_url'], 'catalog_source': catalog_source}
                row = None
                if book.catalog_id is not None:
                    try: value = int(book.catalog_id)
                    except ValueError: raise HTTPException(422, 'Identificador inválido.') from None
                    if not -(2**63) <= value < 2**63: raise HTTPException(422, 'Identificador inválido.')
                    row = connection.execute(f'SELECT {COLUMNS} FROM public.valteca WHERE id=%s', (value,)).fetchone()
                    if row is None: raise HTTPException(404, 'Livro selecionado não existe neste catálogo.')
                elif book.isbn and isbn_key(book.isbn):
                    row = connection.execute(f"SELECT {COLUMNS} FROM public.valteca WHERE regexp_replace(upper(coalesce(isbn,'')), '[^0-9X]', '', 'g')=%s ORDER BY id LIMIT 1", (isbn_key(book.isbn),)).fetchone()
                else:
                    row = connection.execute(f"SELECT {COLUMNS} FROM public.valteca WHERE public.viblio_normalize(titulo)=public.viblio_normalize(%s) AND public.viblio_normalize(coalesce(autor,''))=public.viblio_normalize(%s) AND public.viblio_normalize(coalesce(editora,''))=public.viblio_normalize(%s) AND (%s::integer IS NULL OR metricas_totais IS NULL OR public.viblio_normalize(coalesce(metrica_tipo,'')) NOT IN ('pagina','paginas','page','pages') OR metricas_totais=%s) AND (%s::integer IS NULL OR ordem=%s OR (%s AND ordem IS NULL)) AND (CASE WHEN public.viblio_normalize(coalesce(nullif(tipo,''),categoria,'')) IN ('manga','mangas') THEN 'manga' WHEN public.viblio_normalize(coalesce(nullif(tipo,''),categoria,'')) IN ('hq','quadrinho','quadrinhos','comic','comics') THEN 'comic' ELSE 'book' END)=%s ORDER BY id LIMIT 1", (book.title, book.author or '', book.publisher or '',book.pages,book.pages,book.sequence,book.sequence,not book.is_volume,book.kind)).fetchone()
                if converted:
                    temporary = storage / (filename + '.tmp')
                    temporary.write_bytes(converted); temporary.replace(path); wrote = True
                    connection.execute('INSERT INTO public.viblio_api_covers(id,fingerprint) VALUES (%s,%s)', (request_id, hashlib.sha256(converted).hexdigest()))
                if row is None:
                    row = connection.execute(f"INSERT INTO public.valteca(titulo,autor,genero,subgenero,metricas_totais,metrica_tipo,saga,editora,capa_url,categoria,ordem,isbn,tipo) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING {COLUMNS}", (book.title, book.author or None, book.genre or None, '; '.join(book.subgenres) or None, book.pages, 'páginas' if book.pages is not None else None, book.saga or None, book.publisher or None, uploaded_url, {'book':'Livro','manga':'Mangá','comic':'Quadrinho'}[book.kind], book.sequence, book.isbn or None, {'book':'Livro','manga':'Mangá','comic':'Quadrinho'}[book.kind])).fetchone()
                if converted:
                    connection.execute('INSERT INTO public.viblio_book_covers(book_id,cover_id,fingerprint) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING', (row['id'],request_id,hashlib.sha256(converted).hexdigest()))
                connection.execute('INSERT INTO public.viblio_api_requests(request_id,fingerprint,book_id,uploaded_url) VALUES (%s,%s,%s,%s)', (request_id, fingerprint, row['id'], uploaded_url))
            return {'book': present(row), 'uploaded_url': uploaded_url, 'catalog_source': catalog_source}
        except Exception:
            if wrote: path.unlink(missing_ok=True)
            raise
    return app
