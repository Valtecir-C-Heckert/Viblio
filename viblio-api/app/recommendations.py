"""Personalized catalog recommendations anchored on the current reading book + separate wishlist ('Quero comprar')."""
import re
import unicodedata
from typing import Literal
from urllib.parse import urlparse

from fastapi import Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

MAX_WISHLIST_ITEMS = 500
CATALOG_ID_RE = re.compile(r'^-?[0-9]{1,19}$')


def _norm(value: str | None) -> str:
    text = unicodedata.normalize('NFKD', (value or '').strip().lower())
    text = ''.join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def _like_escape(value: str) -> str:
    return value.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')


def _book_complete(book: dict) -> bool:
    if not book.get('pagesKnown', True):
        return bool(book.get('readFinished'))
    total = book.get('totalPages') or 0
    return bool(total and (book.get('currentPage') or 0) >= total)


def _kind_clause(kind: str) -> str:
    manga = "(public.viblio_normalize(coalesce(categoria,'')) IN ('manga','mangas') OR public.viblio_normalize(coalesce(tipo,'')) IN ('manga','mangas'))"
    comic = "(public.viblio_normalize(coalesce(categoria,'')) IN ('quadrinho','quadrinhos','hq','comic','comics') OR public.viblio_normalize(coalesce(tipo,'')) IN ('quadrinho','quadrinhos','hq','comic','comics'))"
    if kind == 'manga':
        return manga
    if kind == 'comic':
        return comic
    if kind == 'book':
        return f"(NOT {manga} AND NOT {comic})"
    return "TRUE"


def _subgenres_of(book: dict) -> list[str]:
    out: list[str] = []
    raw_list = book.get('subgenres')
    if isinstance(raw_list, list):
        for item in raw_list:
            if isinstance(item, str) and item.strip():
                out.append(item.strip())
    raw_str = book.get('subgenre')
    if isinstance(raw_str, str) and raw_str.strip():
        for part in raw_str.split(';'):
            if part.strip():
                out.append(part.strip())
    seen = set()
    unique = []
    for item in out:
        key = _norm(item)
        if key and key not in seen:
            seen.add(key)
            unique.append(item)
    return unique


def _extract_profile(library: dict | None):
    if not isinstance(library, dict):
        return None, 'none', [], [], [], set(), set()
    collections = {c.get('id'): c for c in (library.get('collections') or []) if isinstance(c, dict) and c.get('id')}
    all_books = [b for b in (library.get('books') or []) if isinstance(b, dict)]
    active_books = [
        b for b in all_books
        if not b.get('archived', False) and not collections.get(b.get('collectionId'), {}).get('archived', False)
    ]

    owned_ids: set[str] = set()
    owned_keys: set[tuple[str, str]] = set()
    for b in all_books:
        cid = b.get('catalogId')
        if cid is not None and str(cid).strip():
            owned_ids.add(str(cid).strip())
        nt = _norm(b.get('title'))
        na = _norm(b.get('author'))
        if nt:
            owned_keys.add((nt, na))

    current_id = library.get('currentId')
    anchor = None
    anchor_kind = 'none'
    if current_id:
        for b in active_books:
            if b.get('id') == current_id:
                anchor = b
                anchor_kind = 'current'
                break
    if anchor is None:
        finished = [b for b in active_books if _book_complete(b)]
        if finished:
            anchor = max(finished, key=lambda b: (b.get('updatedAt') or 0, b.get('rating') or 0))
            anchor_kind = 'last_finished'
    if anchor is None and active_books:
        anchor = max(active_books, key=lambda b: (b.get('rating') or 0, b.get('updatedAt') or 0))
        anchor_kind = 'favorite'

    author_scores: dict[str, tuple[float, str]] = {}
    subgenre_scores: dict[str, tuple[float, str]] = {}
    genre_scores: dict[str, tuple[float, str]] = {}

    def bump(bucket: dict[str, tuple[float, str]], raw: str | None, weight: float):
        if not raw or not isinstance(raw, str):
            return
        cleaned = raw.strip()
        key = _norm(cleaned)
        if len(key) < 2:
            return
        prev = bucket.get(key)
        bucket[key] = ((prev[0] if prev else 0.0) + weight, prev[1] if prev else cleaned)

    for b in active_books:
        rating = b.get('rating')
        is_anchor = anchor is not None and b.get('id') == anchor.get('id')
        weight = 6.0 if is_anchor else (3.0 if (isinstance(rating, (int, float)) and rating >= 4.0) else (2.0 if _book_complete(b) else 1.0))
        bump(author_scores, b.get('author'), weight)
        bump(genre_scores, b.get('genre'), weight)
        for sub in _subgenres_of(b):
            bump(subgenre_scores, sub, weight)

    def ranked(bucket: dict[str, tuple[float, str]], anchor_values: list[str]) -> list[str]:
        ordered: list[str] = []
        seen: set[str] = set()
        for raw in anchor_values:
            key = _norm(raw)
            if len(key) >= 2 and key not in seen:
                seen.add(key)
                ordered.append(raw.strip())
        for key, (_, label) in sorted(bucket.items(), key=lambda kv: kv[1][0], reverse=True):
            if key not in seen:
                seen.add(key)
                ordered.append(label)
        return ordered[:5]

    anchor_authors = [anchor.get('author', '')] if anchor and anchor.get('author') else []
    anchor_subgenres = _subgenres_of(anchor) if anchor else []
    anchor_genres = [anchor.get('genre', '')] if anchor and anchor.get('genre') else []

    return (
        anchor,
        anchor_kind,
        ranked(author_scores, anchor_authors),
        ranked(subgenre_scores, anchor_subgenres),
        ranked(genre_scores, anchor_genres),
        owned_ids,
        owned_keys,
    )


def _valid_cover_url(url: str) -> str:
    cleaned = url.strip()
    if not cleaned:
        return ''
    parsed = urlparse(cleaned)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password:
        raise HTTPException(422, 'URL de capa inválida.')
    return cleaned


class WishlistInput(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    catalog_id: str = Field(min_length=1, max_length=24, pattern=r'^-?[0-9]{1,19}$')
    source: str = Field(default='', max_length=120)
    title: str = Field(min_length=1, max_length=500)
    author: str = Field(default='', max_length=300)
    genre: str = Field(default='', max_length=200)
    subgenre: str = Field(default='', max_length=300)
    publisher: str = Field(default='', max_length=200)
    saga: str = Field(default='', max_length=200)
    pages: int | None = Field(default=None, ge=1, le=100000)
    sequence: int | None = Field(default=None, ge=1, le=10000)
    cover_url: str = Field(default='', max_length=2048)
    isbn: str = Field(default='', max_length=32)
    category: str = Field(default='', max_length=100)
    type: str = Field(default='', max_length=100)


def _present_wishlist(row: dict, default_source: str) -> dict:
    return {
        'id': row['catalog_id'],
        'source': row['source'] or default_source,
        'title': row['title'],
        'author': row['author'],
        'genre': row['genre'],
        'subgenre': row['subgenre'],
        'publisher': row['publisher'],
        'saga': row['saga'],
        'pages': row['pages'],
        'sequence': row['sequence'],
        'cover_url': row['cover_url'],
        'isbn': row['isbn'],
        'category': row['category'],
        'type': row['type'],
        'added_at': int(row['added_at'].timestamp() * 1000),
    }


def mount_recommendations(app, connect, current, catalog_source, public_url, columns, present):
    @app.get('/v1/books/recommendations')
    def recommendations(
        kind: Literal['all', 'book', 'manga', 'comic'] = 'all',
        seed: int = Query(0, ge=0, le=10000),
        per_section: int = Query(10, ge=3, le=12),
        user=Depends(current),
    ):
        from .cover_gallery import canonical_rows

        kind_sql = _kind_clause(kind)
        with connect() as db:
            base = db.execute(
                """SELECT snapshot->'library' AS library
                   FROM public.viblio_library_bases
                   WHERE user_id=%s
                   ORDER BY revision DESC LIMIT 1""",
                (user['id'],),
            ).fetchone()
            wish_rows = db.execute(
                'SELECT catalog_id FROM public.viblio_wishlist WHERE user_id=%s',
                (user['id'],),
            ).fetchall()
            wishlist_ids = {r['catalog_id'] for r in wish_rows}

            anchor, anchor_kind, authors, subgenres, genres, owned_ids, owned_keys = _extract_profile(
                base['library'] if base else None
            )

            used_ids: set[str] = set(owned_ids)
            used_keys: set[tuple[str, str]] = set(owned_keys)

            def filter_and_take(rows: list[dict], limit: int) -> list[dict]:
                if not rows:
                    return []
                resolved = canonical_rows(rows, db, public_url)
                # Prefer items not yet in wishlist first, while still allowing discovery.
                ordered = sorted(
                    resolved,
                    key=lambda r: (1 if str(r['id']) in wishlist_ids else 0, 0 if (r.get('capa_url') or '').strip() else 1),
                )
                picked: list[dict] = []
                for row in ordered:
                    rid = str(row['id'])
                    key = (_norm(row.get('titulo')), _norm(row.get('autor')))
                    if rid in used_ids or (key[0] and key in used_keys):
                        continue
                    used_ids.add(rid)
                    if key[0]:
                        used_keys.add(key)
                    picked.append(present(row))
                    if len(picked) >= limit:
                        break
                return picked

            def query_column(column: str, terms: list[str], limit: int) -> tuple[str, list[dict]]:
                if not terms:
                    return '', []
                collected: list[dict] = []
                primary_label = ''
                order_expr = f"(CASE WHEN coalesce(capa_url,'')<>'' THEN 0 ELSE 1 END), ((abs(id) + {int(seed) * 997}) % 1000003), id"
                for term in terms:
                    if len(collected) >= limit:
                        break
                    pattern = '%' + _like_escape(term) + '%'
                    rows = db.execute(
                        f"""SELECT {columns} FROM public.valteca
                            WHERE {kind_sql}
                              AND public.viblio_normalize(coalesce({column},'')) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'
                            ORDER BY {order_expr}
                            LIMIT %s""",
                        (pattern, limit * 4),
                    ).fetchall()
                    batch = filter_and_take(rows, limit - len(collected))
                    if batch and not primary_label:
                        primary_label = term
                    collected.extend(batch)
                return primary_label, collected

            sections: list[dict] = []

            author_label, author_items = query_column('autor', authors, per_section)
            if author_items:
                sections.append({
                    'key': 'author',
                    'title': f'Mais de {author_label}',
                    'tag': 'Autor',
                    'items': author_items,
                })

            sub_label, sub_items = query_column('subgenero', subgenres, per_section)
            if sub_items:
                sections.append({
                    'key': 'subgenre',
                    'title': f'Em {sub_label}',
                    'tag': 'Subgênero',
                    'items': sub_items,
                })

            genre_label, genre_items = query_column('genero', genres, per_section)
            if genre_items:
                sections.append({
                    'key': 'genre',
                    'title': f'Em {genre_label}',
                    'tag': 'Gênero',
                    'items': genre_items,
                })

            # Fallback when library is empty or catalog has no matches for the user's current metadata.
            if not sections:
                order_expr = f"(CASE WHEN coalesce(capa_url,'')<>'' THEN 0 ELSE 1 END), ((abs(id) + {int(seed) * 997}) % 1000003), id"
                rows = db.execute(
                    f"""SELECT {columns} FROM public.valteca
                        WHERE {kind_sql}
                        ORDER BY {order_expr}
                        LIMIT %s""",
                    (per_section * 3,),
                ).fetchall()
                fallback_items = filter_and_take(rows, per_section)
                if fallback_items:
                    sections.append({
                        'key': 'featured',
                        'title': 'Destaques do catálogo',
                        'tag': 'Catálogo',
                        'items': fallback_items,
                    })

        return {
            'anchor_title': (anchor.get('title') or '').strip()[:200] if anchor else '',
            'anchor_kind': anchor_kind,
            'sections': sections,
            'catalog_source': catalog_source,
        }

    @app.get('/v1/me/wishlist')
    def list_wishlist(user=Depends(current)):
        with connect() as db:
            rows = db.execute(
                """SELECT catalog_id, source, title, author, genre, subgenre, publisher, saga,
                          pages, sequence, cover_url, isbn, category, type, added_at
                   FROM public.viblio_wishlist
                   WHERE user_id=%s
                   ORDER BY added_at DESC, catalog_id ASC
                   LIMIT %s""",
                (user['id'], MAX_WISHLIST_ITEMS),
            ).fetchall()
        return {
            'items': [_present_wishlist(r, catalog_source) for r in rows],
            'catalog_source': catalog_source,
        }

    @app.post('/v1/me/wishlist', status_code=201)
    def add_wishlist(body: WishlistInput, user=Depends(current)):
        cover_url = _valid_cover_url(body.cover_url)
        with connect() as db:
            existing = db.execute(
                'SELECT 1 FROM public.viblio_wishlist WHERE user_id=%s AND catalog_id=%s',
                (user['id'], body.catalog_id),
            ).fetchone()
            if not existing:
                count = db.execute(
                    'SELECT count(*) AS total FROM public.viblio_wishlist WHERE user_id=%s',
                    (user['id'],),
                ).fetchone()['total']
                if count >= MAX_WISHLIST_ITEMS:
                    raise HTTPException(422, f'Limite de {MAX_WISHLIST_ITEMS} livros em Quero comprar atingido.')
            row = db.execute(
                """INSERT INTO public.viblio_wishlist(
                       user_id, catalog_id, source, title, author, genre, subgenre,
                       publisher, saga, pages, sequence, cover_url, isbn, category, type
                   )
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                   ON CONFLICT (user_id, catalog_id) DO UPDATE SET
                       source=EXCLUDED.source, title=EXCLUDED.title, author=EXCLUDED.author,
                       genre=EXCLUDED.genre, subgenre=EXCLUDED.subgenre, publisher=EXCLUDED.publisher,
                       saga=EXCLUDED.saga, pages=EXCLUDED.pages, sequence=EXCLUDED.sequence,
                       cover_url=EXCLUDED.cover_url, isbn=EXCLUDED.isbn, category=EXCLUDED.category,
                       type=EXCLUDED.type, added_at=now()
                   RETURNING catalog_id, source, title, author, genre, subgenre, publisher, saga,
                             pages, sequence, cover_url, isbn, category, type, added_at""",
                (
                    user['id'], body.catalog_id, body.source or catalog_source, body.title, body.author,
                    body.genre, body.subgenre, body.publisher, body.saga, body.pages, body.sequence,
                    cover_url, body.isbn, body.category, body.type,
                ),
            ).fetchone()
        return {'item': _present_wishlist(row, catalog_source), 'catalog_source': catalog_source}

    @app.delete('/v1/me/wishlist/{catalog_id}')
    def remove_wishlist(catalog_id: str, user=Depends(current)):
        if not CATALOG_ID_RE.fullmatch(catalog_id):
            raise HTTPException(422, 'Identificador inválido.')
        with connect() as db:
            db.execute(
                'DELETE FROM public.viblio_wishlist WHERE user_id=%s AND catalog_id=%s',
                (user['id'], catalog_id),
            )
        return {'ok': True}
