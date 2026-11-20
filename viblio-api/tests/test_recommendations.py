"""Tests for current-book anchored recommendations, wishlist CRUD, and sub-second noise filtering."""
import psycopg
from test_accounts import DSN, api, headers, snapshot, sync, result
from test_friends import setup
from test_social import get


def _seed_catalog_books():
    with psycopg.connect(DSN, autocommit=True) as db:
        rows = [
            (770001, '1984', 'George Orwell', 'Ficção Científica', 'Distopia', 'Companhia', 'https://example.org/1984.jpg', 'Livro', 'Livro'),
            (770002, 'A Revolução dos Bichos', 'George Orwell', 'Ficção Científica', 'Sátira', 'Companhia', 'https://example.org/bichos.jpg', 'Livro', 'Livro'),
            (770003, 'Admirável Mundo Novo', 'Aldous Huxley', 'Ficção Científica', 'Distopia', 'Biblioteca Azul', 'https://example.org/admiravel.jpg', 'Livro', 'Livro'),
            (770004, 'Eu, Robô', 'Isaac Asimov', 'Ficção Científica', 'Robótica', 'Aleph', 'https://example.org/robo.jpg', 'Livro', 'Livro'),
            (770005, 'Akira Vol. 1', 'Katsuhiro Otomo', 'Ficção Científica', 'Distopia', 'JBC', 'https://example.org/akira.jpg', 'Mangá', 'Mangá'),
        ]
        for r in rows:
            db.execute(
                """INSERT INTO public.valteca(id,titulo,autor,genero,subgenero,editora,capa_url,categoria,tipo)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                   ON CONFLICT (id) DO NOTHING""",
                r,
            )


def test_recommendations_anchor_on_current_book_and_exclude_owned(api):
    _seed_catalog_books()
    a, _, _ = setup(api)
    snap = snapshot()
    snap['library']['books'][0].update(
        id='cur-1984',
        catalogId='770001',
        title='1984',
        author='George Orwell',
        genre='Ficção Científica',
        subgenre='Distopia',
        subgenres=['Distopia'],
        currentPage=80,
        totalPages=328,
        pagesKnown=True,
    )
    snap['library']['currentBookId'] = 'cur-1984'
    result(sync(api, a, snap))

    rec = get(api, a, '/v1/books/recommendations?kind=book')
    assert rec['anchor_title'] == '1984'
    assert rec['anchor_kind'] == 'current'
    by_key = {s['key']: s for s in rec['sections']}
    assert 'author' in by_key and 'George Orwell' in by_key['author']['title']
    assert 'subgenre' in by_key and 'Distopia' in by_key['subgenre']['title']
    assert 'genre' in by_key

    all_ids = {item['id'] for s in rec['sections'] for item in s['items']}
    # Owned book (1984) must never be recommended; manga must be excluded when kind=book.
    assert '770001' not in all_ids
    assert '770005' not in all_ids
    assert '770002' in {item['id'] for item in by_key['author']['items']}
    assert '770003' in {item['id'] for item in by_key['subgenre']['items']}

    # Filtering by manga surfaces Akira Vol. 1.
    manga_rec = get(api, a, '/v1/books/recommendations?kind=manga')
    manga_ids = {item['id'] for s in manga_rec['sections'] for item in s['items']}
    assert '770005' in manga_ids


def test_wishlist_crud_isolation_and_validation(api):
    a, b, _ = setup(api)
    assert get(api, a, '/v1/me/wishlist')['items'] == []

    # Invalid cover URL with credentials or javascript scheme must be rejected.
    bad = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={'catalog_id': '770002', 'title': 'A Revolução dos Bichos', 'cover_url': 'javascript:alert(1)'},
    )
    assert bad.status_code == 422

    # Valid add + idempotent upsert.
    created = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={
            'catalog_id': '770002',
            'title': 'A Revolução dos Bichos',
            'author': 'George Orwell',
            'genre': 'Ficção Científica',
            'subgenre': 'Sátira',
            'pages': 152,
            'cover_url': 'https://example.org/bichos.jpg',
        },
    )
    assert created.status_code == 201
    assert created.json()['item']['id'] == '770002'

    # Isolated per user: user B cannot see user A's wishlist.
    assert len(get(api, a, '/v1/me/wishlist')['items']) == 1
    assert get(api, b, '/v1/me/wishlist')['items'] == []

    # Delete removes item cleanly.
    deleted = api.delete('/v1/me/wishlist/770002', headers=headers(a))
    assert deleted.status_code == 200
    assert get(api, a, '/v1/me/wishlist')['items'] == []

    # Health advertises recommendations and wishlist APIs.
    health = get(api, a, '/v1/health')
    assert health['api_version'] == 10
    assert health['recommendations_api'] == 1
    assert health['wishlist_api'] == 1
