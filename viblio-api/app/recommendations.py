"""Personalized catalog recommendations anchored on the current reading book + separate wishlist ('Quero comprar')."""
import re
import unicodedata
from typing import Literal
from urllib.parse import urlparse

from fastapi import Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

MAX_WISHLIST_ITEMS = 500
CATALOG_ID_RE = re.compile(r'^-?[0-9]{1,19}$')
ROLE_PARENS_RE = re.compile(r'\s*[\(\[][^\)\]]*[\)\]]')
VOLUME_MARKER_RE = re.compile(
    r'\b(?:vol(?:ume)?|livro|book|tomo|parte|pt|n[oº°]?)\s*#?\s*0*(\d{1,4})\b|#\s*0*(\d{1,4})\b',
    re.IGNORECASE,
)
TRAILING_NUM_RE = re.compile(r'^(.*\S)\s+0*(\d{1,3})$')
SUBTITLE_SPLIT_RE = re.compile(r'\s*(?::|\s[-–—|]\s)\s*')
AUTHOR_SPLIT_RE = re.compile(r'\s*(?:;|/|\||&|\be\b)\s*', re.IGNORECASE)
TAG_SPLIT_RE = re.compile(r'\s*[;,/|]\s*')


def _norm(value: str | None) -> str:
    text = unicodedata.normalize('NFKD', (value or '').strip().lower())
    text = ''.join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def _clean_isbn(value: str | None) -> str:
    cleaned = re.sub(r'[^0-9X]', '', (value or '').upper())
    return cleaned if len(cleaned) in (10, 13) else ''


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


def _split_authors(raw: str | None) -> list[str]:
    if not raw or not isinstance(raw, str):
        return []
    cleaned = ROLE_PARENS_RE.sub('', raw).strip()
    if not cleaned:
        return []
    parts = [p.strip() for p in AUTHOR_SPLIT_RE.split(cleaned) if p.strip()]
    out: list[str] = []
    seen: set[frozenset[str]] = set()
    for part in parts:
        # Handle catalog "Last, First" format cleanly -> "First Last"
        if part.count(',') == 1:
            left, right = (s.strip() for s in part.split(',', 1))
            if left and right and len(left.split()) <= 3 and len(right.split()) <= 3:
                part = f'{right} {left}'
        tokens = frozenset(_norm(part).split())
        if not tokens or tokens in seen:
            continue
        seen.add(tokens)
        out.append(part)
    return out


def _author_tokens(name: str | None) -> list[str]:
    cleaned = ROLE_PARENS_RE.sub('', name or '').strip()
    return [t for t in _norm(cleaned).split() if t]


def _author_matches(row_author: str | None, target_author: str) -> bool:
    target_tokens = _author_tokens(target_author)
    if not target_tokens:
        return False
    sig_tokens = [t for t in target_tokens if len(t) >= 2]
    initials = [t for t in target_tokens if len(t) == 1]
    segments = _split_authors(row_author)
    if not segments and row_author:
        segments = [row_author]
    for seg in segments:
        seg_tokens = set(_author_tokens(seg))
        if not seg_tokens:
            continue
        if sig_tokens:
            if not all(tok in seg_tokens for tok in sig_tokens):
                continue
            if initials and not all(any(st.startswith(init) for st in seg_tokens) for init in initials):
                continue
            return True
        elif all(tok in seg_tokens for tok in target_tokens):
            return True
    return False


def _tag_matches(row_value: str | None, term: str) -> bool:
    target = _norm(term)
    if len(target) < 2 or not row_value:
        return False
    padded_target = f' {target} '
    for seg in TAG_SPLIT_RE.split(row_value):
        ns = _norm(seg)
        if not ns:
            continue
        if ns == target or padded_target in f' {ns} ':
            return True
    return False


def _extract_volume(title: str | None, sequence=None) -> int | None:
    if sequence is not None:
        try:
            val = int(sequence)
            if val == float(sequence) and 1 <= val <= 10000:
                return val
        except (TypeError, ValueError):
            pass
    if not title:
        return None
    norm = _norm(title)
    m = VOLUME_MARKER_RE.search(norm)
    if m:
        num = int(m.group(1) or m.group(2))
        if 1 <= num <= 10000:
            return num
    m2 = TRAILING_NUM_RE.match(norm)
    if m2:
        prefix, num_str = m2.group(1), m2.group(2)
        num = int(num_str)
        if len(prefix) >= 3 and 1 <= num <= 500:
            return num
    return None


def _base_title(title: str | None) -> str:
    if not title:
        return ''
    stripped = ROLE_PARENS_RE.sub('', title).strip()
    parts = SUBTITLE_SPLIT_RE.split(stripped, maxsplit=1)
    head = parts[0].strip() if parts else stripped
    chosen = head if len(_norm(head)) >= 4 else stripped
    norm = _norm(chosen)
    # Strip volume suffix so "Vagabond Vol. 1" -> base "vagabond" (volume compared separately).
    no_vol = VOLUME_MARKER_RE.sub('', norm).strip()
    if len(no_vol) >= 3:
        norm = re.sub(r'\s+', ' ', no_vol).strip()
    m = TRAILING_NUM_RE.match(norm)
    if m and len(m.group(1)) >= 3 and 1 <= int(m.group(2)) <= 500:
        norm = m.group(1).strip()
    return norm


def _authors_overlap(a1: frozenset[str], a2: frozenset[str]) -> bool:
    if not a1 or not a2:
        return False
    sig1 = {t for t in a1 if len(t) >= 3}
    sig2 = {t for t in a2 if len(t) >= 3}
    if sig1 and sig2:
        return bool(sig1 & sig2)
    return bool(a1 & a2)


def _same_work(
    t1_full: str, t1_base: str, a1: frozenset[str], v1: int | None,
    t2_full: str, t2_base: str, a2: frozenset[str], v2: int | None,
) -> bool:
    if not t1_full or not t2_full:
        return False
    # Never conflate different volumes of the same series (e.g. Vol. 1 vs Vol. 2).
    if v1 is not None and v2 is not None and v1 != v2:
        return False
    both_authors = bool(a1 and a2)
    if both_authors and not _authors_overlap(a1, a2):
        return False
    if t1_full == t2_full:
        return True
    if t1_base and t1_base == t2_base:
        return len(t1_base) >= (3 if both_authors else 5)
    if both_authors and t1_base and t2_base:
        shorter, longer = (t1_base, t2_full) if len(t1_base) <= len(t2_base) else (t2_base, t1_full)
        if len(shorter) >= 5 and longer.startswith(shorter + ' '):
            return True
    return False


class _WorkIndex:
    """Tracks owned or already-selected works across catalog IDs, work_keys, ISBNs and title/edition variants."""

    def __init__(self):
        self.ids: set[str] = set()
        self.work_keys: set[str] = set()
        self.isbns: set[str] = set()
        self.works: list[tuple[str, str, frozenset[str], int | None]] = []

    def add(self, *, catalog_id: str | None = None, work_key: str | None = None,
            isbn: str | None = None, title: str | None = None,
            author: str | None = None, sequence=None):
        if catalog_id is not None and str(catalog_id).strip():
            self.ids.add(str(catalog_id).strip())
        wk = (work_key or '').strip()
        if wk:
            self.work_keys.add(wk)
        ci = _clean_isbn(isbn)
        if ci:
            self.isbns.add(ci)
        tf = _norm(title)
        if tf:
            tb = _base_title(title)
            at = frozenset(_author_tokens(author))
            vol = _extract_volume(title, sequence)
            self.works.append((tf, tb, at, vol))

    def matches_row(self, row: dict) -> bool:
        rid = str(row['id'])
        if rid in self.ids:
            return True
        wk = (row.get('work_key') or '').strip()
        if wk and wk in self.work_keys:
            return True
        ci = _clean_isbn(row.get('isbn'))
        if ci and ci in self.isbns:
            return True
        tf = _norm(row.get('titulo'))
        if not tf:
            return False
        tb = _base_title(row.get('titulo'))
        at = frozenset(_author_tokens(row.get('autor')))
        vol = _extract_volume(row.get('titulo'), row.get('ordem'))
        for ow_tf, ow_tb, ow_at, ow_vol in self.works:
            if _same_work(tf, tb, at, vol, ow_tf, ow_tb, ow_at, ow_vol):
                return True
        return False

    def add_row(self, row: dict):
        self.add(
            catalog_id=str(row['id']),
            work_key=row.get('work_key'),
            isbn=row.get('isbn'),
            title=row.get('titulo'),
            author=row.get('autor'),
            sequence=row.get('ordem'),
        )


def _subgenres_of(book: dict) -> list[str]:
    out: list[str] = []
    raw_list = book.get('subgenres')
    if isinstance(raw_list, list):
        for item in raw_list:
            if isinstance(item, str) and item.strip():
                out.append(item.strip())
    raw_str = book.get('subgenre')
    if isinstance(raw_str, str) and raw_str.strip():
        for part in TAG_SPLIT_RE.split(raw_str):
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


def _extract_profile(library: dict | None, live_anchor: dict | None = None):
    owned = _WorkIndex()
    active_books: list[dict] = []
    if isinstance(library, dict):
        collections = {c.get('id'): c for c in (library.get('collections') or []) if isinstance(c, dict) and c.get('id')}
        all_books = [b for b in (library.get('books') or []) if isinstance(b, dict)]
        active_books = [
            b for b in all_books
            if not b.get('archived', False) and not collections.get(b.get('collectionId'), {}).get('archived', False)
        ]
        for b in active_books:
            owned.add(
                catalog_id=b.get('catalogId'),
                isbn=b.get('isbn'),
                title=b.get('title'),
                author=b.get('author'),
                sequence=b.get('sequenceNumber'),
            )

    anchor = None
    anchor_kind = 'none'
    if live_anchor and any(live_anchor.get(k) for k in ('title', 'author', 'genre', 'subgenre')):
        anchor = live_anchor
        anchor_kind = 'current'
        owned.add(
            title=live_anchor.get('title'),
            author=live_anchor.get('author'),
        )
    elif isinstance(library, dict):
        current_id = library.get('currentBookId') or library.get('currentId')
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
        is_anchor = anchor is not None and anchor.get('id') and b.get('id') == anchor.get('id')
        weight = 6.0 if is_anchor else (3.0 if (isinstance(rating, (int, float)) and rating >= 4.0) else (2.0 if _book_complete(b) else 1.0))
        for a in _split_authors(b.get('author')):
            bump(author_scores, a, weight)
        for g in TAG_SPLIT_RE.split(b.get('genre') or ''):
            bump(genre_scores, g, weight)
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
        return ordered[:3]

    anchor_authors = _split_authors(anchor.get('author')) if anchor else []
    anchor_subgenres = _subgenres_of(anchor) if anchor else []
    anchor_genres = [g.strip() for g in TAG_SPLIT_RE.split(anchor.get('genre') or '') if g.strip()] if anchor else []

    return (
        anchor,
        anchor_kind,
        ranked(author_scores, anchor_authors),
        ranked(subgenre_scores, anchor_subgenres),
        ranked(genre_scores, anchor_genres),
        owned,
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


def _seed_rank(row_id: int, seed: int) -> int:
    return ((abs(int(row_id)) * 2654435761) + (int(seed) * 1000003)) & 0xFFFFFFFF


def mount_recommendations(app, connect, current, catalog_source, public_url, columns, present):
    rec_columns = f'{columns}, work_key'

    @app.get('/v1/books/recommendations')
    def recommendations(
        kind: Literal['all', 'book', 'manga', 'comic'] = 'all',
        seed: int = Query(0, ge=0, le=10000),
        per_section: int = Query(10, ge=3, le=12),
        anchor_title: str = Query('', max_length=200),
        anchor_author: str = Query('', max_length=200),
        anchor_genre: str = Query('', max_length=200),
        anchor_subgenre: str = Query('', max_length=200),
        user=Depends(current),
    ):
        from .cover_gallery import canonical_rows

        kind_sql = _kind_clause(kind)
        live_anchor = None
        if any(s.strip() for s in (anchor_title, anchor_author, anchor_genre, anchor_subgenre)):
            live_anchor = {
                'title': anchor_title.strip(),
                'author': anchor_author.strip(),
                'genre': anchor_genre.strip(),
                'subgenre': anchor_subgenre.strip(),
            }

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

            anchor, anchor_kind, authors, subgenres, genres, owned = _extract_profile(
                base['library'] if base else None,
                live_anchor=live_anchor,
            )

            # Enrich owned index with work_key, ISBN and canonical titles from public.valteca in 1 fast PK query.
            numeric_ids = [int(cid) for cid in owned.ids if CATALOG_ID_RE.fullmatch(cid)]
            if numeric_ids:
                owned_cat_rows = db.execute(
                    'SELECT id, work_key, titulo, autor, isbn, ordem FROM public.valteca WHERE id = ANY(%s)',
                    (numeric_ids[:2000],),
                ).fetchall()
                for orow in owned_cat_rows:
                    owned.add_row(orow)

            shown = _WorkIndex()

            def filter_and_take(rows: list[dict], limit: int, verifier) -> list[dict]:
                if not rows:
                    return []
                matched = [r for r in rows if verifier(r) and not owned.matches_row(r) and not shown.matches_row(r)]
                if not matched:
                    return []
                resolved = canonical_rows(matched, db, public_url)
                ordered = sorted(
                    resolved,
                    key=lambda r: (
                        0 if (r.get('capa_url') or '').strip() else 1,
                        1 if str(r['id']) in wishlist_ids else 0,
                        _seed_rank(r['id'], seed),
                        len(r.get('titulo') or ''),
                    ),
                )
                picked: list[dict] = []
                for row in ordered:
                    if owned.matches_row(row) or shown.matches_row(row):
                        continue
                    shown.add_row(row)
                    picked.append(present(row))
                    if len(picked) >= limit:
                        break
                return picked

            def query_author_section(terms: list[str], limit: int) -> tuple[str, list[dict]]:
                for term in terms:
                    tokens = sorted([t for t in _author_tokens(term) if len(t) >= 3], key=len, reverse=True)[:3]
                    if not tokens:
                        norm_term = _norm(term)
                        if len(norm_term) < 2:
                            continue
                        tokens = [norm_term]
                    # Uses viblio_author_search GIN index directly (no coalesce wrapper, no full-table ORDER BY).
                    clauses = [
                        "public.viblio_normalize(autor) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'"
                        for _ in tokens
                    ]
                    params = ['%' + _like_escape(tok) + '%' for tok in tokens]
                    rows = db.execute(
                        f"""SELECT {rec_columns} FROM public.valteca
                            WHERE {kind_sql}
                              AND {' AND '.join(clauses)}
                            LIMIT 160""",
                        params,
                    ).fetchall()
                    batch = filter_and_take(rows, limit, lambda r, t=term: _author_matches(r.get('autor'), t))
                    if batch:
                        # Strict 1-author-per-section: never mix other authors under this author's heading.
                        return term, batch
                return '', []

            def query_tag_section(column: str, terms: list[str], limit: int) -> tuple[str, list[dict]]:
                offset = (int(seed) % 8) * 20
                for term in terms:
                    norm_term = _norm(term)
                    if len(norm_term) < 2:
                        continue
                    pattern = '%' + _like_escape(term) + '%'
                    # Uses viblio_genre_search / viblio_subgenre_search GIN index without sorting tens of thousands of rows.
                    rows = db.execute(
                        f"""SELECT {rec_columns} FROM public.valteca
                            WHERE {kind_sql}
                              AND public.viblio_normalize({column}) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'
                            LIMIT 160 OFFSET %s""",
                        (pattern, offset),
                    ).fetchall()
                    if offset > 0 and len(rows) < limit * 2:
                        rows = db.execute(
                            f"""SELECT {rec_columns} FROM public.valteca
                                WHERE {kind_sql}
                                  AND public.viblio_normalize({column}) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'
                                LIMIT 160""",
                            (pattern,),
                        ).fetchall()
                    batch = filter_and_take(rows, limit, lambda r, t=term, c=column: _tag_matches(r.get(c), t))
                    if batch:
                        # Strict 1-tag-per-section: never mix unrelated genres/subgenres under this heading.
                        return term, batch
                return '', []

            sections: list[dict] = []

            author_label, author_items = query_author_section(authors, per_section)
            if author_items:
                sections.append({
                    'key': 'author',
                    'title': f'Mais de {author_label}',
                    'tag': 'Autor',
                    'items': author_items,
                })

            sub_label, sub_items = query_tag_section('subgenero', subgenres, per_section)
            if sub_items:
                sections.append({
                    'key': 'subgenre',
                    'title': f'Em {sub_label}',
                    'tag': 'Subgênero',
                    'items': sub_items,
                })

            genre_label, genre_items = query_tag_section('genero', genres, per_section)
            if genre_items:
                sections.append({
                    'key': 'genre',
                    'title': f'Em {genre_label}',
                    'tag': 'Gênero',
                    'items': genre_items,
                })

            # Fallback only when library is empty or catalog has zero matches for user metadata.
            if not sections:
                offset = (int(seed) % 10) * 15
                rows = db.execute(
                    f"""SELECT {rec_columns} FROM public.valteca
                        WHERE {kind_sql}
                        LIMIT 120 OFFSET %s""",
                    (offset,),
                ).fetchall()
                if offset > 0 and not rows:
                    rows = db.execute(
                        f"""SELECT {rec_columns} FROM public.valteca
                            WHERE {kind_sql}
                            LIMIT 120""",
                    ).fetchall()
                fallback_items = filter_and_take(rows, per_section, lambda _r: True)
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
