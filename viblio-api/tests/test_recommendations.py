"""Tests for smart unified search, kind-locked recommendations, saga ordering, subgenre resilience, infinite rail pagination, wishlist CRUD, and 'added' feed events."""
import time
import psycopg
from test_accounts import DSN, api, headers, snapshot, sync, result
from test_friends import setup, accept
from test_social import get


def _seed_catalog_books():
    with psycopg.connect(DSN, autocommit=True) as db:
        rows = [
            (770001, '1984', 'George Orwell', 'Ficção Científica', 'Distopia', 'Companhia', 'https://example.org/1984.jpg', 'Livro', 'Livro', 'wk-1984', '9788535914849', None, None),
            (770002, 'A Revolução dos Bichos', 'George Orwell', 'Ficção Científica', 'Sátira', 'Companhia', 'https://example.org/bichos.jpg', 'Livro', 'Livro', 'wk-bichos', '9788535909555', None, None),
            (770003, 'Admirável Mundo Novo', 'Aldous Huxley', 'Ficção Científica', 'Ficção Distópica', 'Biblioteca Azul', 'https://example.org/admiravel.jpg', 'Livro', 'Livro', 'wk-admiravel', '9788525056009', None, None),
            (770004, 'Eu, Robô', 'Isaac Asimov', 'Ficção Científica', 'Robótica', 'Aleph', 'https://example.org/robo.jpg', 'Livro', 'Livro', 'wk-robo', '9788576572008', None, None),
            (770005, 'Akira Vol. 1', 'Katsuhiro Otomo', 'Ficção Científica', 'Distopia', 'JBC', 'https://example.org/akira.jpg', 'Mangá', 'Mangá', 'wk-akira-1', '9788545702870', 1, 'Akira'),
            # Martha Wells (Murderbot saga) + Vagabond regression fixture:
            (770010, 'Alerta Vermelho', 'Martha Wells', 'Ficção Científica', 'Space-Opera', 'Aleph', 'https://example.org/alerta1.jpg', 'Livro', 'Livro', 'wk-alerta', '9786586064018', 1, 'Murderbot'),
            (770011, 'Alerta Vermelho (Diários de um Robô-Assassino Livro 1)', 'Martha Wells', 'Ficção Científica', 'Space-Opera', 'Aleph', 'https://example.org/alerta2.jpg', 'Livro', 'Livro', 'wk-alerta', '9786586064999', 1, 'Murderbot'),
            (770013, 'Protocolo Rebelde', 'Martha Wells (Autor)', 'Ficção Científica', 'Space-Opera', 'Aleph', 'https://example.org/protocolo.jpg', 'Livro', 'Livro', 'wk-protocolo', '9786586064032', 3, 'Murderbot'),
            (770012, 'Condição Artificial', 'Wells, Martha', 'Ficção Científica', 'Space-Opera', 'Aleph', 'https://example.org/condicao.jpg', 'Livro', 'Livro', 'wk-condicao', '9786586064025', 2, 'Murderbot'),
            (770017, 'Estratégia de Saída', 'Martha Wells', 'Ficção Científica', 'Space Opera', 'Aleph', 'https://example.org/estrategia.jpg', 'Livro', 'Livro', 'wk-estrategia', '9786586064049', 4, 'Murderbot'),
            (770018, 'Duna', 'Frank Herbert', 'Ficção Científica', 'Space-Opera', 'Aleph', 'https://example.org/duna.jpg', 'Livro', 'Livro', 'wk-duna', '9788576573135', 1, 'Duna'),
            (770019, 'Livro Único Solo', 'Autor Sem Outros', 'Fantasia Sombria', None, 'Editora X', 'https://example.org/solo.jpg', 'Livro', 'Livro', 'wk-solo', '9788500000011', None, None),
            (770020, 'O Trono Sombrio', 'Outro Autor Fantasia', 'Fantasia Sombria', None, 'Editora X', 'https://example.org/trono.jpg', 'Livro', 'Livro', 'wk-trono', '9788500000028', None, None),
            (770014, 'Vagabond Vol. 1', 'Takehiko Inoue', 'Ação', 'Samurai', 'Panini', 'https://example.org/vagabond1.jpg', 'Mangá', 'Mangá', 'wk-vaga-1', '9788542600018', 1, 'Vagabond'),
            (770015, 'Vagabond Vol. 2', 'Takehiko Inoue', 'Ação', 'Samurai', 'Panini', 'https://example.org/vagabond2.jpg', 'Mangá', 'Mangá', 'wk-vaga-2', '9788542600025', 2, 'Vagabond'),
            (770016, 'A Guerra dos Mundos', 'H. G. Wells', 'Ficção Científica', 'Invasão', 'Suma', 'https://example.org/guerra.jpg', 'Livro', 'Livro', 'wk-hgwells', '9788556510000', None, None),
        ]
        # Extra Sci-Fi books to test infinite horizontal pagination (/v1/books/recommendations/more).
        for idx in range(1, 15):
            rows.append((
                770100 + idx,
                f'Crônica Estelar {idx}',
                f'Autor SciFi {idx}',
                'Ficção Científica',
                'Space Opera',
                'Aleph',
                f'https://example.org/scifi_{idx}.jpg',
                'Livro',
                'Livro',
                f'wk-scifi-{idx}',
                None,
                None,
                None,
            ))
        for r in rows:
            db.execute(
                """INSERT INTO public.valteca(id,titulo,autor,genero,subgenero,editora,capa_url,categoria,tipo,work_key,isbn,ordem,saga)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                   ON CONFLICT (id) DO NOTHING""",
                r,
            )


def test_recommendations_search_saga_subgenre_pagination_and_wishlist(api):
    _seed_catalog_books()
    a, b, _ = setup(api)

    # --- 1) Stemmed subgenre ('Distopia' matches 'Ficção Distópica') + kind locking on 'all' ---
    snap = snapshot()
    snap['library']['books'][0].update(
        id='cur-1984',
        catalogId='770001',
        title='1984',
        author='George Orwell',
        genre='Ficção Científica',
        subgenre='Distopia',
        subgenres=['Distopia'],
        kind='book',
        currentPage=80,
        totalPages=328,
        pagesKnown=True,
    )
    snap['library']['currentBookId'] = 'cur-1984'
    result(sync(api, a, snap))

    rec = get(api, a, '/v1/books/recommendations?kind=all')
    assert rec['anchor_title'] == '1984'
    assert rec['effective_kind'] == 'book'
    by_key = {s['key']: s for s in rec['sections']}
    assert 'author' in by_key and 'George Orwell' in by_key['author']['title']
    assert 'subgenre' in by_key and 'Distopia' in by_key['subgenre']['title']
    assert '770003' in {item['id'] for item in by_key['subgenre']['items']}
    all_ids = {item['id'] for s in rec['sections'] for item in s['items']}
    # Owned 1984 and Manga Akira must NOT appear when reading a book!
    assert '770001' not in all_ids and '770005' not in all_ids

    # --- 2) Saga ordering (Vol 2 -> Vol 3 -> Vol 4), catalog enrichment, NO Takehiko Inoue hijack ---
    snap_b = snapshot()
    # User B owns solo-author book (current) + 3 volumes of Vagabond (Takehiko Inoue)
    snap_b['library']['books'] = [
        {
            **snap_b['library']['books'][0],
            'id': 'cur-solo',
            'catalogId': '770019',
            'title': 'Livro Único Solo',
            'author': 'Autor Sem Outros',
            'genre': 'Fantasia',
            'subgenre': 'Sombria',
            'subgenres': ['Sombria'],
            'kind': 'book',
            'currentPage': 20,
            'totalPages': 200,
            'pagesKnown': True,
        },
        {
            **snap_b['library']['books'][0],
            'id': 'owned-vaga-1',
            'catalogId': '770014',
            'title': 'Vagabond Vol. 1',
            'author': 'Takehiko Inoue',
            'genre': 'Ação',
            'subgenre': 'Samurai',
            'subgenres': ['Samurai'],
            'kind': 'manga',
            'sequenceNumber': 1,
            'currentPage': 200,
            'totalPages': 200,
            'pagesKnown': True,
        },
    ]
    snap_b['library']['currentBookId'] = 'cur-solo'
    result(sync(api, b, snap_b))

    solo_rec = get(api, b, '/v1/books/recommendations?kind=all')
    solo_keys = {s['key']: s for s in solo_rec['sections']}
    # Author has 0 other books -> author section MUST be omitted (NEVER hijacked by Takehiko Inoue!)
    assert 'author' not in solo_keys
    # Subgenre 'Sombria' matches 'Fantasia Sombria' stored in genero!
    assert 'subgenre' in solo_keys
    assert '770020' in {i['id'] for i in solo_keys['subgenre']['items']}
    solo_all = {i['id'] for s in solo_rec['sections'] for i in s['items']}
    assert '770014' not in solo_all and '770015' not in solo_all and '770019' not in solo_all

    # Switching chip to 'manga' anchors on Vagabond Vol. 1 and suggests Vol. 2!
    manga_rec = get(api, b, '/v1/books/recommendations?kind=manga')
    manga_all = {i['id'] for s in manga_rec['sections'] for i in s['items']}
    assert '770015' in manga_all and '770014' not in manga_all

    # Live anchor on Alerta Vermelho (catalogId=770010, empty local subgenre/saga -> enriched from valteca!):
    martha_rec = get(
        api,
        b,
        '/v1/books/recommendations?kind=all&per_section=5&anchor_catalog_id=770010&anchor_kind=book&anchor_title=Alerta+Vermelho&anchor_author=Martha+Wells',
    )
    m_keys = {s['key']: s for s in martha_rec['sections']}
    # Saga section appears FIRST and ordered by sequence ascending: Vol 2 (770012), Vol 3 (770013), Vol 4 (770017)!
    assert 'saga' in m_keys
    assert [i['id'] for i in m_keys['saga']['items']] == ['770012', '770013', '770017']
    # Subgenre 'Space-Opera' enriched from valteca appears with Space Opera books (Duna + Crônica Estelar)!
    assert 'subgenre' in m_keys
    assert m_keys['subgenre']['has_more'] is True

    # Infinite horizontal pagination (/v1/books/recommendations/more): zero overlap with page 0!
    first_page_ids = [i['id'] for i in m_keys['subgenre']['items']]
    more = get(
        api,
        b,
        f"/v1/books/recommendations/more?section=subgenre&term={m_keys['subgenre']['term']}&kind=book&cursor={m_keys['subgenre']['next_cursor']}&limit=5&exclude_ids={','.join(first_page_ids)}",
    )
    more_ids = [i['id'] for i in more['items']]
    assert len(more_ids) == 5
    assert set(more_ids).isdisjoint(set(first_page_ids))
    assert '770010' not in more_ids and '770011' not in more_ids

    # --- 3) Smart Unified Search (/v1/books?query=...&kind=...) ---
    # Multi-word combined author + title ("martha alerta")
    s1 = get(api, a, '/v1/books?query=martha+alerta&kind=book')
    assert any(i['id'] == '770010' for i in s1['items'])
    # Inverted author ("wells martha")
    s2 = get(api, a, '/v1/books?query=wells+martha&kind=book')
    assert any(i['id'] == '770012' for i in s2['items'])
    # Saga search ("murderbot")
    s3 = get(api, a, '/v1/books?query=murderbot&kind=book')
    assert {'770010', '770012', '770013'} <= {i['id'] for i in s3['items']}
    # Typo-tolerant pg_trgm search ("takehino inoe" -> Takehiko Inoue!)
    s4 = get(api, a, '/v1/books?query=takehino+inoe&kind=manga')
    assert any('Takehiko Inoue' in (i['author'] or '') for i in s4['items'])
    # Kind filter prevents manga from leaking into book search
    s5 = get(api, a, '/v1/books?query=vagabond&kind=book')
    assert s5['items'] == []

    # --- 4) Wishlist CRUD + Health v12 ---
    assert get(api, a, '/v1/me/wishlist')['items'] == []
    bad = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={'catalog_id': '770002', 'title': 'A Revolução dos Bichos', 'cover_url': 'javascript:alert(1)'},
    )
    assert bad.status_code == 422
    created = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={'catalog_id': '770002', 'title': 'A Revolução dos Bichos', 'author': 'George Orwell', 'cover_url': 'https://example.org/bichos.jpg'},
    )
    assert created.status_code == 201
    assert len(get(api, a, '/v1/me/wishlist')['items']) == 1
    assert get(api, b, '/v1/me/wishlist')['items'] == []
    assert api.delete('/v1/me/wishlist/770002', headers=headers(a)).status_code == 200
    assert get(api, a, '/v1/me/wishlist')['items'] == []

    health = get(api, a, '/v1/health')
    assert health['api_version'] == 12
    assert health['recommendations_api'] == 2
    assert health['search_api'] == 2


def test_feed_records_added_book_but_skips_initial_sync_and_bulk_import(api):
    a, b, _ = setup(api)
    accept(api, a, b)
    now = int(time.time() * 1000)

    s = snapshot()
    s['library']['books'][0]['updatedAt'] = now
    result(sync(api, b, s))
    assert get(api, a, '/v1/friends/feed')['items'] == []

    s['library']['books'].append({
        **s['library']['books'][0],
        'id': 'new-book-1',
        'title': 'Condição Artificial',
        'author': 'Martha Wells',
        'currentPage': 0,
        'totalPages': 160,
        'updatedAt': now + 1000,
    })
    result(sync(api, b, s, 1))
    feed = get(api, a, '/v1/friends/feed')['items']
    assert len(feed) == 1 and feed[0]['kind'] == 'added' and feed[0]['book']['title'] == 'Condição Artificial'

    for idx in range(6):
        s['library']['books'].append({
            **s['library']['books'][0],
            'id': f'bulk-{idx}',
            'title': f'Livro Importado {idx}',
            'currentPage': 0,
            'totalPages': 120,
            'updatedAt': now + 2000 + idx,
        })
    result(sync(api, b, s, 2))
    feed_after = get(api, a, '/v1/friends/feed')['items']
    assert len(feed_after) == 1
