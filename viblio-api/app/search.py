"""Smart multi-token, saga-aware, typo-tolerant catalog search with relevance ranking."""
import re
import unicodedata

STOP_TOKENS = {'de', 'da', 'do', 'das', 'dos', 'e', 'a', 'o', 'as', 'os', 'em', 'no', 'na', 'um', 'uma', 'the', 'of', 'and'}


def norm_text(value: str | None) -> str:
    text = unicodedata.normalize('NFKD', (value or '').strip().lower())
    text = ''.join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def like_escape(value: str) -> str:
    return value.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')


def kind_clause(kind: str) -> str:
    manga = "(public.viblio_normalize(coalesce(categoria,'')) IN ('manga','mangas') OR public.viblio_normalize(coalesce(tipo,'')) IN ('manga','mangas'))"
    comic = "(public.viblio_normalize(coalesce(categoria,'')) IN ('quadrinho','quadrinhos','hq','comic','comics') OR public.viblio_normalize(coalesce(tipo,'')) IN ('quadrinho','quadrinhos','hq','comic','comics'))"
    if kind == 'manga':
        return manga
    if kind == 'comic':
        return comic
    if kind == 'book':
        return f"(NOT {manga} AND NOT {comic})"
    return 'TRUE'


def _query_tokens(raw: str) -> list[str]:
    all_toks = [t for t in norm_text(raw).split() if t]
    meaningful = [t for t in all_toks if len(t) >= 3 and t not in STOP_TOKENS]
    if meaningful:
        return meaningful[:5]
    return [t for t in all_toks if len(t) >= 2][:5] or all_toks[:3]


def _score_row(row: dict, raw_norm: str, tokens: list[str]) -> tuple:
    t = norm_text(row.get('titulo'))
    a = norm_text(row.get('autor'))
    s = norm_text(row.get('saga'))
    combined = f'{t} {a} {s}'
    has_cover = 0 if (row.get('capa_url') or '').strip() else 1

    # Tier 0: exact title/author/saga match
    if raw_norm and (t == raw_norm or a == raw_norm or s == raw_norm):
        tier = 0
    # Tier 1: prefix match on title, author or saga
    elif raw_norm and (t.startswith(raw_norm) or a.startswith(raw_norm) or s.startswith(raw_norm)):
        tier = 1
    # Tier 2: exact substring match in title
    elif raw_norm and raw_norm in t:
        tier = 2
    # Tier 3: all tokens present in title or author or saga
    elif tokens and (all(tok in t for tok in tokens) or all(tok in a for tok in tokens) or all(tok in s for tok in tokens)):
        tier = 3
    # Tier 4: all tokens present across title + author + saga combined (e.g. "martha alerta")
    elif tokens and all(tok in combined for tok in tokens):
        tier = 4
    else:
        # Tier 5: fuzzy / partial token overlap (ranked by how many tokens matched + trigram similarity)
        hits = sum(1 for tok in tokens if tok in combined)
        sim = float(row.get('_sim') or 0.0)
        tier = 5 + max(0, len(tokens) - hits)
        return (tier, has_cover, -sim, len(t), int(row['id']))

    return (tier, has_cover, 0.0, len(t), int(row['id']))


def unified_search(db, columns: str, query: str, kind: str, limit: int, offset: int) -> tuple[list[dict], int | None]:
    """Single-request multi-token search across titulo, autor and saga with GIN BitmapOr + pg_trgm typo fallback."""
    raw_norm = norm_text(query)
    if len(raw_norm) < 2:
        return [], None
    tokens = _query_tokens(query)
    if not tokens:
        return [], None

    k_sql = kind_clause(kind)
    # Build per-token OR across titulo/autor/saga so PostgreSQL uses GIN trigram BitmapOr/BitmapAnd.
    token_clauses = []
    params: list = []
    for tok in tokens:
        pat = '%' + like_escape(tok) + '%'
        token_clauses.append(
            "(public.viblio_normalize(titulo) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\' "
            "OR public.viblio_normalize(autor) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\' "
            "OR public.viblio_normalize(saga) LIKE public.viblio_normalize(%s) ESCAPE E'\\\\')"
        )
        params.extend([pat, pat, pat])

    pool_limit = min(320, max(160, offset + limit + 120))
    rows = db.execute(
        f"""SELECT {columns}, 0.0::real AS _sim
            FROM public.valteca
            WHERE {k_sql}
              AND {' AND '.join(token_clauses)}
            ORDER BY id
            LIMIT %s""",
        [*params, pool_limit],
    ).fetchall()

    # Typo-tolerant pg_trgm fallback ONLY when exact multi-token returns fewer than 5 results.
    if len(rows) < 5 and len(raw_norm) >= 4:
        seen_ids = {r['id'] for r in rows}
        fuzzy_rows = db.execute(
            f"""SELECT {columns},
                       greatest(
                           similarity(public.viblio_normalize(coalesce(titulo, '')), public.viblio_normalize(%s)),
                           similarity(public.viblio_normalize(coalesce(autor, '')), public.viblio_normalize(%s)),
                           similarity(public.viblio_normalize(coalesce(saga, '')), public.viblio_normalize(%s))
                       ) AS _sim
                FROM public.valteca
                WHERE {k_sql}
                  AND (
                      public.viblio_normalize(titulo) %% public.viblio_normalize(%s)
                      OR public.viblio_normalize(autor) %% public.viblio_normalize(%s)
                      OR public.viblio_normalize(saga) %% public.viblio_normalize(%s)
                  )
                ORDER BY _sim DESC, id
                LIMIT 100""",
            (query, query, query, query, query, query),
        ).fetchall()
        for fr in fuzzy_rows:
            if fr['id'] not in seen_ids and float(fr.get('_sim') or 0.0) >= 0.22:
                seen_ids.add(fr['id'])
                rows.append(fr)

    if not rows:
        return [], None

    ranked = sorted(rows, key=lambda r: _score_row(r, raw_norm, tokens))
    page = ranked[offset : offset + limit]
    next_cursor = (offset + len(page)) if (offset + len(page)) < len(ranked) else None
    return page, next_cursor
