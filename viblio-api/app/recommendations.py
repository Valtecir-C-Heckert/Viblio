"""Personalized kind-aware catalog recommendations with infinite rail pagination + separate wishlist ('Quero comprar')."""
import re
import unicodedata
from typing import Literal
from urllib.parse import urlparse

from fastapi import Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

MAX_WISHLIST_ITEMS = 500
MAX_EXCLUDE_IDS = 220
MAX_RAIL_CURSOR = 20
CATALOG_ID_RE = re.compile(r'^-?[0-9]{1,19}$')
MAX_BIGINT = 2**63 - 1
MIN_BIGINT = -(2**63)


def _safe_bigint(value: str | None) -> int | None:
    if not value or not isinstance(value, str):
        return None
    s = value.strip()
    if not CATALOG_ID_RE.fullmatch(s):
        return None
    n = int(s)
    return n if MIN_BIGINT <= n <= MAX_BIGINT else None


ROLE_PARENS_RE = re.compile(r'\s*[\(\[][^\)\]]*[\)\]]')
VOLUME_MARKER_RE = re.compile(
    r'\b(?:vol(?:ume)?|livro|book|tomo|parte|pt|n[oº°]?)\s*#?\s*0*(\d{1,4})\b|#\s*0*(\d{1,4})\b',
    re.IGNORECASE,
)
TRAILING_NUM_RE = re.compile(r'^(.*\S)\s+0*(\d{1,3})$')
SUBTITLE_SPLIT_RE = re.compile(r'\s*(?::|\s[-–—|]\s)\s*')
AUTHOR_SPLIT_RE = re.compile(r'\s*(?:;|/|\||&|\be\b)\s*', re.IGNORECASE)
TAG_SPLIT_RE = re.compile(r'\s*[;,/|]\s*')
STEM_SUFFIX_RE = re.compile(r'(?:icas?|icos?|ias?|ios?|as?|os?|es?)$')
STOP_TAG_WORDS = {'de', 'da', 'do', 'das', 'dos', 'e', 'em', 'na', 'no', 'com', 'para', 'por', 'the', 'of', 'and'}


def _norm(value: str | None) -> str:
    text = unicodedata.normalize('NFKD', (value or '').strip().lower())
    text = ''.join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def _stem_token(tok: str) -> str:
    # Keep short words and Portuguese -ao nouns (ficcao, acao) intact; stem inflectional endings safely.
    if len(tok) < 5 or tok.endswith('ao') or tok.endswith('oes'):
        return tok
    stemmed = STEM_SUFFIX_RE.sub('', tok)
    return stemmed if len(stemmed) >= 4 else tok


def _tag_tokens(term: str) -> list[str]:
    raw = [t for t in _norm(term).split() if t and t not in STOP_TAG_WORDS]
    if not raw:
        raw = [t for t in _norm(term).split() if t]
    return raw


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


def _row_kind(row: dict) -> str:
    tags = {_norm(row.get('categoria')), _norm(row.get('tipo'))}
    if tags & {'manga', 'mangas'}:
        return 'manga'
    if tags & {'quadrinho', 'quadrinhos', 'hq', 'comic', 'comics'}:
        return 'comic'
    return 'book'


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


def _token_matches_words(target_tok: str, words: list[str]) -> bool:
    stem = _stem_token(target_tok)
    for w in words:
        if w == target_tok:
            return True
        if len(stem) >= 4 and (w.startswith(stem) or _stem_token(w) == stem):
            return True
    return False


def _tag_matches(row_value: str | None, term: str) -> bool:
    target_toks = _tag_tokens(term)
    if not target_toks or not row_value:
        return False
    for seg in TAG_SPLIT_RE.split(row_value):
        words = [w for w in _norm(seg).split() if w]
        if not words:
            continue
        if all(_token_matches_words(tok, words) for tok in target_toks):
            return True
    # Also check full field words in case a compound tag had separators (e.g. "Fantasia / Sombria").
    all_words = [w for w in _norm(row_value).split() if w]
    return bool(all_words) and all(_token_matches_words(tok, all_words) for tok in target_toks)


def _saga_matches(row_saga: str | None, term: str) -> bool:
    target_toks = _tag_tokens(term)
    if not target_toks or not row_saga:
        return False
    words = [w for w in _norm(row_saga).split() if w]
    return bool(words) and all(_token_matches_words(tok, words) for tok in target_toks)


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


def _build_owned(db, library: dict | None, live_anchor: dict | None = None) -> tuple[_WorkIndex, list[dict]]:
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
    if live_anchor:
        owned.add(
            catalog_id=live_anchor.get('catalogId'),
            title=live_anchor.get('title'),
            author=live_anchor.get('author'),
        )
    numeric_ids = [n for cid in owned.ids if (n := _safe_bigint(cid)) is not None]
    if numeric_ids:
        owned_cat_rows = db.execute(
            'SELECT id, work_key, titulo, autor, isbn, ordem FROM public.valteca WHERE id = ANY(%s)',
            (numeric_ids[:2000],),
        ).fetchall()
        for orow in owned_cat_rows:
            owned.add_row(orow)
    return owned, active_books


def _enrich_anchor_from_catalog(db, anchor: dict | None) -> dict | None:
    if not anchor:
        return None
    enriched = dict(anchor)
    need_meta = not (enriched.get('subgenre') or enriched.get('subgenres')) or not enriched.get('genre') or not enriched.get('saga') or not enriched.get('author')
    cid_num = _safe_bigint(str(enriched.get('catalogId') or ''))
    cat_row = None
    if cid_num is not None:
        cat_row = db.execute(
            'SELECT titulo, autor, genero, subgenero, saga, categoria, tipo FROM public.valteca WHERE id=%s',
            (cid_num,),
        ).fetchone()
    elif need_meta and isinstance(enriched.get('title'), str) and enriched.get('title'):
        t_toks = [t for t in _norm(enriched.get('title')).split() if len(t) >= 3][:2]
        a_toks = [t for t in _author_tokens(enriched.get('author') if isinstance(enriched.get('author'), str) else '') if len(t) >= 3][:2]
        if t_toks:
            clauses = ["public.viblio_normalize(titulo) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'" for _ in t_toks]
            params = ['%' + _like_escape(t) + '%' for t in t_toks]
            for at in a_toks:
                clauses.append("public.viblio_normalize(autor) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'")
                params.append('%' + _like_escape(at) + '%')
            cat_row = db.execute(
                f"SELECT titulo, autor, genero, subgenero, saga, categoria, tipo FROM public.valteca WHERE {' AND '.join(clauses)} ORDER BY id LIMIT 1",
                params,
            ).fetchone()
    if cat_row:
        if not enriched.get('author') and cat_row.get('autor'):
            enriched['author'] = cat_row['autor']
        if not enriched.get('genre') and cat_row.get('genero'):
            enriched['genre'] = cat_row['genero']
        if not (enriched.get('subgenre') or enriched.get('subgenres')) and cat_row.get('subgenero'):
            enriched['subgenre'] = cat_row['subgenero']
        if not enriched.get('saga') and cat_row.get('saga'):
            enriched['saga'] = cat_row['saga']
        if not enriched.get('kind'):
            enriched['kind'] = _row_kind(cat_row)
    return enriched


def _num_key(v) -> float:
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else 0.0


def _select_anchor(library: dict | None, active_books: list[dict], requested_kind: str, live_anchor: dict | None):
    anchor = None
    anchor_kind = 'none'

    # 1) Live anchor from device (current reading book) when compatible with requested_kind.
    if live_anchor and any(live_anchor.get(k) for k in ('catalogId', 'title', 'author', 'genre', 'subgenre', 'saga')):
        live_k = live_anchor.get('kind') or ''
        if requested_kind == 'all' or not live_k or live_k == requested_kind:
            anchor = dict(live_anchor)
            anchor_kind = 'current'

    # 2) Snapshot current book / scoped fallback by requested_kind.
    if anchor is None and active_books:
        scoped = active_books if requested_kind == 'all' else [b for b in active_books if (b.get('kind') or 'book') == requested_kind]
        if scoped:
            current_id = (library or {}).get('currentBookId') or (library or {}).get('currentId')
            if current_id:
                for b in scoped:
                    if b.get('id') == current_id:
                        anchor = dict(b)
                        anchor_kind = 'current'
                        break
            if anchor is None:
                in_progress = [b for b in scoped if _num_key(b.get('currentPage')) > 0 and not _book_complete(b)]
                if in_progress:
                    anchor = dict(max(in_progress, key=lambda b: _num_key(b.get('updatedAt'))))
                    anchor_kind = 'current'
            if anchor is None:
                finished = [b for b in scoped if _book_complete(b)]
                if finished:
                    anchor = dict(max(finished, key=lambda b: (_num_key(b.get('updatedAt')), _num_key(b.get('rating')))))
                    anchor_kind = 'last_finished'
            if anchor is None:
                anchor = dict(max(scoped, key=lambda b: (_num_key(b.get('rating')), _num_key(b.get('updatedAt')))))
                anchor_kind = 'favorite'

    # Effective kind: when user is on 'all', lock to the current book's kind so reading a book never leaks manga!
    if requested_kind != 'all':
        effective_kind = requested_kind
    elif anchor and anchor.get('kind') in ('book', 'manga', 'comic'):
        effective_kind = anchor['kind']
    else:
        effective_kind = 'all'

    return anchor, anchor_kind, effective_kind


def _series_dedup_tags(active_books: list[dict], effective_kind: str):
    """Rank fallback subgenres/genres within the SAME kind, counting each series/collection only once."""
    sub_scores: dict[str, tuple[float, str]] = {}
    genre_scores: dict[str, tuple[float, str]] = {}
    seen_series: set[str] = set()

    def bump(bucket, raw: str | None, weight: float):
        if not raw or not isinstance(raw, str):
            return
        cleaned = raw.strip()
        key = _norm(cleaned)
        if len(key) < 2:
            return
        prev = bucket.get(key)
        bucket[key] = ((prev[0] if prev else 0.0) + weight, prev[1] if prev else cleaned)

    for b in active_books:
        if effective_kind != 'all' and (b.get('kind') or 'book') != effective_kind:
            continue
        raw_saga = b.get('saga') if isinstance(b.get('saga'), str) else ''
        series_key = (b.get('collectionId') if isinstance(b.get('collectionId'), str) else '') or _norm(raw_saga) or str(b.get('id') or '')
        if series_key in seen_series:
            continue
        seen_series.add(series_key)
        rating = b.get('rating')
        weight = 3.0 if (isinstance(rating, (int, float)) and not isinstance(rating, bool) and rating >= 4.0) else (2.0 if _book_complete(b) else 1.0)
        raw_genre = b.get('genre') if isinstance(b.get('genre'), str) else ''
        for g in TAG_SPLIT_RE.split(raw_genre):
            bump(genre_scores, g, weight)
        for sub in _subgenres_of(b):
            bump(sub_scores, sub, weight)

    top_subs = [label for _, (_, label) in sorted(sub_scores.items(), key=lambda kv: kv[1][0], reverse=True)][:3]
    top_genres = [label for _, (_, label) in sorted(genre_scores.items(), key=lambda kv: kv[1][0], reverse=True)][:3]
    return top_subs, top_genres


def _valid_cover_url(url: str) -> str:
    cleaned = url.strip()
    if not cleaned:
        return ''
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in cleaned):
        raise HTTPException(422, 'URL de capa inválida.')
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


def _token_like_clauses(column: str, tokens: list[str]) -> tuple[str, list[str]]:
    sql_parts = []
    params = []
    for tok in tokens:
        stem = _stem_token(tok)
        pat = '%' + _like_escape(stem) + '%'
        sql_parts.append(f"public.viblio_normalize({column}) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'")
        params.append(pat)
    return ' AND '.join(sql_parts), params


def mount_recommendations(app, connect, current, catalog_source, public_url, columns, present):
    rec_columns = f'{columns}, work_key'

    def _fetch_section_page(
        db,
        section: str,
        term: str,
        effective_kind: str,
        limit: int,
        cursor: int,
        seed: int,
        owned: _WorkIndex,
        shown: _WorkIndex,
        wishlist_ids: set[str],
        extra_genre: str = '',
    ) -> tuple[list[dict], bool, int]:
        from .cover_gallery import canonical_rows

        kind_sql = _kind_clause(effective_kind)
        chunk_size = 160
        base_offset = 0 if section in ('saga', 'author') else (int(seed) % 6) * 24

        def run_sql(offset: int) -> tuple[list[dict], bool]:
            if section == 'saga':
                toks = sorted([t for t in _tag_tokens(term) if len(t) >= 3], key=len, reverse=True)[:3]
                if not toks:
                    norm_t = _norm(term)
                    if len(norm_t) < 2:
                        return [], False
                    toks = [norm_t]
                where, params = _token_like_clauses('saga', toks)
                rows = db.execute(
                    f"SELECT {rec_columns} FROM public.valteca WHERE {kind_sql} AND {where} ORDER BY id LIMIT %s OFFSET %s",
                    [*params, chunk_size, offset],
                ).fetchall()
                return rows, len(rows) >= chunk_size

            if section == 'author':
                toks = sorted([t for t in _author_tokens(term) if len(t) >= 3], key=len, reverse=True)[:3]
                if not toks:
                    norm_t = _norm(term)
                    if len(norm_t) < 2:
                        return [], False
                    toks = [norm_t]
                where_parts = ["public.viblio_normalize(autor) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\'" for _ in toks]
                params = ['%' + _like_escape(t) + '%' for t in toks]
                rows = db.execute(
                    f"SELECT {rec_columns} FROM public.valteca WHERE {kind_sql} AND {' AND '.join(where_parts)} ORDER BY id LIMIT %s OFFSET %s",
                    [*params, chunk_size, offset],
                ).fetchall()
                return rows, len(rows) >= chunk_size

            if section == 'subgenre':
                toks = sorted([t for t in _tag_tokens(term) if len(t) >= 3], key=len, reverse=True)[:3]
                if not toks:
                    norm_t = _norm(term)
                    if len(norm_t) < 2:
                        return [], False
                    toks = [norm_t]
                # Hyphen-agnostic, stem-aware GIN scan across BOTH subgenero and genero (handles "Fantasia Sombria" in genero!).
                sub_where, sub_params = _token_like_clauses('subgenero', toks)
                gen_where, gen_params = _token_like_clauses('genero', toks)
                rows = db.execute(
                    f"""SELECT {rec_columns} FROM public.valteca
                        WHERE {kind_sql} AND (({sub_where}) OR ({gen_where}))
                        ORDER BY id
                        LIMIT %s OFFSET %s""",
                    [*sub_params, *gen_params, chunk_size, offset],
                ).fetchall()
                return rows, len(rows) >= chunk_size

            if section == 'genre':
                toks = sorted([t for t in _tag_tokens(term) if len(t) >= 3], key=len, reverse=True)[:3]
                if not toks:
                    norm_t = _norm(term)
                    if len(norm_t) < 2:
                        return [], False
                    toks = [norm_t]
                where, params = _token_like_clauses('genero', toks)
                rows = db.execute(
                    f"SELECT {rec_columns} FROM public.valteca WHERE {kind_sql} AND {where} ORDER BY id LIMIT %s OFFSET %s",
                    [*params, chunk_size, offset],
                ).fetchall()
                return rows, len(rows) >= chunk_size

            # Featured fallback
            rows = db.execute(
                f"SELECT {rec_columns} FROM public.valteca WHERE {kind_sql} ORDER BY id LIMIT %s OFFSET %s",
                (chunk_size, offset),
            ).fetchall()
            return rows, len(rows) >= chunk_size

        def verifier(row: dict) -> bool:
            if section == 'saga':
                return _saga_matches(row.get('saga'), term)
            if section == 'author':
                return _author_matches(row.get('autor'), term)
            if section == 'subgenre':
                if _tag_matches(row.get('subgenero'), term) or _tag_matches(row.get('genero'), term):
                    return True
                if extra_genre and _tag_matches(row.get('genero'), f'{extra_genre} {term}'):
                    return True
                return False
            if section == 'genre':
                return _tag_matches(row.get('genero'), term)
            return True

        picked_rows: list[dict] = []
        raw_has_more = False
        remaining_in_chunk = False
        sql_cursor = cursor

        # Scan up to 3 chunks if an earlier chunk was mostly owned/excluded so scrolling never stalls early.
        for _ in range(3):
            offset = base_offset + sql_cursor * chunk_size
            rows, chunk_full = run_sql(offset)
            if not rows and sql_cursor == 0 and base_offset > 0:
                rows, chunk_full = run_sql(0)
            raw_has_more = chunk_full
            matched = [r for r in rows if verifier(r) and not owned.matches_row(r) and not shown.matches_row(r)]
            if matched:
                resolved = canonical_rows(matched, db, public_url)
                if section == 'saga':
                    ordered = sorted(
                        resolved,
                        key=lambda r: (
                            0 if r.get('ordem') is not None else 1,
                            float(r['ordem']) if r.get('ordem') is not None else 999999.0,
                            0 if (r.get('capa_url') or '').strip() else 1,
                            int(r['id']),
                        ),
                    )
                else:
                    ordered = sorted(
                        resolved,
                        key=lambda r: (
                            0 if (r.get('capa_url') or '').strip() else 1,
                            1 if str(r['id']) in wishlist_ids else 0,
                            _seed_rank(r['id'], seed),
                            len(r.get('titulo') or ''),
                        ),
                    )
                for row in ordered:
                    if owned.matches_row(row) or shown.matches_row(row):
                        continue
                    if len(picked_rows) < limit:
                        shown.add_row(row)
                        picked_rows.append(row)
                    else:
                        remaining_in_chunk = True
                        break
            if len(picked_rows) >= limit or not chunk_full or sql_cursor >= MAX_RAIL_CURSOR:
                break
            sql_cursor += 1

        # If more unique items remained in the SAME SQL chunk, keep sql_cursor so next call (with exclude_ids) serves them;
        # otherwise advance sql_cursor by 1 to the next SQL chunk.
        has_more = (remaining_in_chunk or raw_has_more) and sql_cursor < MAX_RAIL_CURSOR
        next_cursor = sql_cursor if remaining_in_chunk else (sql_cursor + 1)
        return [present(r) for r in picked_rows], has_more, next_cursor

    @app.get('/v1/books/recommendations')
    def recommendations(
        kind: Literal['all', 'book', 'manga', 'comic'] = 'all',
        seed: int = Query(0, ge=0, le=10000),
        per_section: int = Query(10, ge=3, le=12),
        anchor_catalog_id: str = Query('', max_length=24),
        anchor_kind: str = Query('', max_length=16),
        anchor_title: str = Query('', max_length=200),
        anchor_author: str = Query('', max_length=200),
        anchor_genre: str = Query('', max_length=200),
        anchor_subgenre: str = Query('', max_length=200),
        anchor_saga: str = Query('', max_length=200),
        user=Depends(current),
    ):
        live_anchor = None
        clean_ak = anchor_kind.strip().lower()
        if clean_ak not in ('book', 'manga', 'comic'):
            clean_ak = ''
        if any(s.strip() for s in (anchor_catalog_id, anchor_title, anchor_author, anchor_genre, anchor_subgenre, anchor_saga)):
            live_anchor = {
                'catalogId': str(n) if (n := _safe_bigint(anchor_catalog_id)) is not None else '',
                'kind': clean_ak,
                'title': anchor_title.strip(),
                'author': anchor_author.strip(),
                'genre': anchor_genre.strip(),
                'subgenre': anchor_subgenre.strip(),
                'saga': anchor_saga.strip(),
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

            library = base['library'] if base else None
            owned, active_books = _build_owned(db, library, live_anchor)
            anchor, anchor_kind_label, effective_kind = _select_anchor(library, active_books, kind, live_anchor)
            anchor = _enrich_anchor_from_catalog(db, anchor)
            if kind == 'all' and effective_kind == 'all' and anchor and anchor.get('kind') in ('book', 'manga', 'comic'):
                effective_kind = anchor['kind']

            fallback_subs, fallback_genres = _series_dedup_tags(active_books, effective_kind)

            anchor_saga = (anchor.get('saga') if isinstance(anchor.get('saga'), str) else '').strip() if anchor else ''
            anchor_authors = _split_authors(anchor.get('author') if isinstance(anchor.get('author'), str) else '') if anchor else []
            anchor_subs = _subgenres_of(anchor) if anchor else []
            raw_anchor_genre = (anchor.get('genre') if isinstance(anchor.get('genre'), str) else '') if anchor else ''
            anchor_genres = [g.strip() for g in TAG_SPLIT_RE.split(raw_anchor_genre) if g.strip()]
            primary_genre = anchor_genres[0] if anchor_genres else ''

            # Dedup subgenre & genre candidate lists (bounded to <= 4 to prevent query amplification).
            sub_candidates: list[str] = []
            seen_sub: set[str] = set()
            for raw in (anchor_subs if anchor_subs else fallback_subs):
                k = _norm(raw)
                if len(k) >= 2 and k not in seen_sub:
                    seen_sub.add(k)
                    sub_candidates.append(raw)
                    if len(sub_candidates) >= 4:
                        break

            genre_candidates: list[str] = []
            seen_gen: set[str] = set()
            for raw in (anchor_genres + fallback_genres):
                k = _norm(raw)
                if len(k) >= 2 and k not in seen_gen:
                    seen_gen.add(k)
                    genre_candidates.append(raw)
                    if len(genre_candidates) >= 4:
                        break

            shown = _WorkIndex()
            sections: list[dict] = []

            # 1) Mais da saga <Saga> (ordered by sequence/ordem ascending!)
            if len(_norm(anchor_saga)) >= 3:
                saga_items, saga_more, saga_next = _fetch_section_page(
                    db, 'saga', anchor_saga, effective_kind, per_section, 0, seed, owned, shown, wishlist_ids,
                )
                if saga_items:
                    sections.append({
                        'key': 'saga',
                        'title': f'Mais da saga {anchor_saga}',
                        'tag': 'Saga',
                        'term': anchor_saga,
                        'kind': effective_kind,
                        'has_more': saga_more,
                        'next_cursor': saga_next,
                        'items': saga_items,
                    })

            # 2) Mais de <Autor> — STRICTLY anchor's author ONLY (omitted if 0 unowned books; never hijacked by another author!).
            for author_term in anchor_authors[:2]:
                author_items, author_more, author_next = _fetch_section_page(
                    db, 'author', author_term, effective_kind, per_section, 0, seed, owned, shown, wishlist_ids,
                )
                if author_items:
                    sections.append({
                        'key': 'author',
                        'title': f'Mais de {author_term}',
                        'tag': 'Autor',
                        'term': author_term,
                        'kind': effective_kind,
                        'has_more': author_more,
                        'next_cursor': author_next,
                        'items': author_items,
                    })
                    break

            # 3) Em <Subgênero> — hyphen-agnostic, stem-aware across subgenero + genero!
            for sub_term in sub_candidates:
                sub_items, sub_more, sub_next = _fetch_section_page(
                    db, 'subgenre', sub_term, effective_kind, per_section, 0, seed, owned, shown, wishlist_ids,
                    extra_genre=primary_genre,
                )
                if sub_items:
                    sections.append({
                        'key': 'subgenre',
                        'title': f'Em {sub_term}',
                        'tag': 'Subgênero',
                        'term': sub_term,
                        'kind': effective_kind,
                        'has_more': sub_more,
                        'next_cursor': sub_next,
                        'items': sub_items,
                    })
                    break

            # 4) Em <Gênero>
            for gen_term in genre_candidates:
                gen_items, gen_more, gen_next = _fetch_section_page(
                    db, 'genre', gen_term, effective_kind, per_section, 0, seed, owned, shown, wishlist_ids,
                )
                if gen_items:
                    sections.append({
                        'key': 'genre',
                        'title': f'Em {gen_term}',
                        'tag': 'Gênero',
                        'term': gen_term,
                        'kind': effective_kind,
                        'has_more': gen_more,
                        'next_cursor': gen_next,
                        'items': gen_items,
                    })
                    break

            # 5) Catalog highlights fallback only when library is empty or zero matches exist.
            if not sections:
                feat_items, feat_more, feat_next = _fetch_section_page(
                    db, 'featured', '', effective_kind, per_section, 0, seed, owned, shown, wishlist_ids,
                )
                if feat_items:
                    sections.append({
                        'key': 'featured',
                        'title': 'Destaques do catálogo',
                        'tag': 'Catálogo',
                        'term': '',
                        'kind': effective_kind,
                        'has_more': feat_more,
                        'next_cursor': feat_next,
                        'items': feat_items,
                    })

        return {
            'anchor_title': (anchor.get('title') or '').strip()[:200] if anchor else '',
            'anchor_kind': anchor_kind_label,
            'effective_kind': effective_kind,
            'sections': sections,
            'catalog_source': catalog_source,
        }

    @app.get('/v1/books/recommendations/more')
    def recommendations_more(
        section: Literal['saga', 'author', 'subgenre', 'genre', 'featured'],
        term: str = Query('', max_length=200),
        kind: Literal['all', 'book', 'manga', 'comic'] = 'all',
        cursor: int = Query(0, ge=0, le=MAX_RAIL_CURSOR),
        seed: int = Query(0, ge=0, le=10000),
        limit: int = Query(10, ge=3, le=12),
        exclude_ids: str = Query('', max_length=4500),
        user=Depends(current),
    ):
        clean_term = term.strip()
        if section != 'featured' and len(_norm(clean_term)) < 2:
            raise HTTPException(422, 'Termo da seção inválido.')

        excluded: list[int] = []
        if exclude_ids.strip():
            for raw in exclude_ids.split(',')[:MAX_EXCLUDE_IDS]:
                n = _safe_bigint(raw)
                if n is not None:
                    excluded.append(n)

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

            owned, _ = _build_owned(db, base['library'] if base else None)
            shown = _WorkIndex()
            if excluded:
                ex_rows = db.execute(
                    'SELECT id, work_key, titulo, autor, isbn, ordem FROM public.valteca WHERE id = ANY(%s)',
                    (excluded,),
                ).fetchall()
                for er in ex_rows:
                    shown.add_row(er)
                for eid in excluded:
                    shown.ids.add(str(eid))

            items, has_more, next_cursor = _fetch_section_page(
                db, section, clean_term, kind, limit, cursor, seed, owned, shown, wishlist_ids,
            )

        return {
            'items': items,
            'has_more': has_more,
            'next_cursor': next_cursor,
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
        if _safe_bigint(body.catalog_id) is None:
            raise HTTPException(422, 'Identificador inválido.')
        cover_url = _valid_cover_url(body.cover_url)
        with connect() as db:
            db.execute('SELECT id FROM public.viblio_users WHERE id=%s FOR UPDATE', (user['id'],))
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
        if _safe_bigint(catalog_id) is None:
            raise HTTPException(422, 'Identificador inválido.')
        with connect() as db:
            db.execute(
                'DELETE FROM public.viblio_wishlist WHERE user_id=%s AND catalog_id=%s',
                (user['id'], catalog_id),
            )
        return {'ok': True}
