import pytest
from test_api import prepare_database
@pytest.fixture(scope="session",autouse=True)
def prepare():
    prepare_database()
