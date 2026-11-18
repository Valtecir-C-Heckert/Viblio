"""Bounded notifications for the single-worker deployment; comments stay in PostgreSQL."""
import asyncio
import threading
from fastapi import HTTPException

class DiscussionEvents:
    def __init__(self):
        self.lock = threading.Lock()
        self.listeners = {}

    def subscribe(self, club, stage, user):
        key = object()
        queue = asyncio.Queue(maxsize=1)
        entry = (str(club), str(stage), str(user), asyncio.get_running_loop(), queue)
        with self.lock:
            if len(self.listeners) >= 32 or sum(e[2] == entry[2] for e in self.listeners.values()) >= 3:
                raise HTTPException(429, 'Muitas discussões abertas. Feche outra e tente novamente.', headers={'Retry-After': '60'})
            self.listeners[key] = entry
        return key, queue

    def unsubscribe(self, key):
        with self.lock:
            self.listeners.pop(key, None)

    def publish(self, club, stage=None):
        with self.lock:
            targets = [e for e in self.listeners.values() if e[0] == str(club) and (stage is None or e[1] == str(stage))]
        for _, _, _, loop, queue in targets:
            def notify(q=queue):
                if q.empty(): q.put_nowait(True)
            try: loop.call_soon_threadsafe(notify)
            except RuntimeError: pass  # An already disconnected client has no data to receive.
