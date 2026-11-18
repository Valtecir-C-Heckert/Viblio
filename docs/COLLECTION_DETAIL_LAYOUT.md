Collection detail layout revision after 0.4.41

- Replace “SUA COLEÇÃO” with “Seus volumes”; contain the cover grid and add-volume action inside the genre-colored glass panel.
- Remove the redundant “Abrir volume 1” action. Tapping a volume cover opens that volume.
- Fix the percentage label in the horizontal progress row to wrap its content; MATCH_PARENT previously left the weighted read-count label with zero width and excessive wrapped height.
- Add a collapsible title-level reading history, grouped by day and volume. It aggregates existing manual dates and session dates without displaying a timer or durations.
- Add dates by selecting a possessed volume. Editing a date only receives the sessions/manual dates for that volume, so another volume’s records cannot be altered accidentally.
- No schema or JSON changes. Existing volume dates remain the source of truth.

Validation: assembleDebug and compileDebugAndroidTestKotlin passed; all 154 JVM tests passed; lintDebug passed (0 errors, 79 warnings). Native visual validation cannot run: the available emulator reports “Service activity: not found”.

Follow-up adjustments:

- Ratings show stars only; half-star dragging and spoken accessibility values remain.
- Horizontal gestures on a collection title navigate among titles of the same kind, following shelf order and applicable filters. Rating gestures are excluded from title navigation.
- Manga/comic titles support up to three distinct subgenres through the shared genre editor and centered detail tiles. Suggestions include books and existing collection titles. Genre remains the source of card color.
- Collection subgenres persist in SQLite version 9, optional JSON fields, and unsaved editor state. New volumes inherit them; previously customized volumes are not overwritten.
- Missing covers use the same white `ic_books_nav` artwork as the Estante tab.

Subgenre validation: 156 JVM tests passed; debug build, Android test compilation and lint passed (0 errors, 79 warnings). A host SQLite check executed the production collection ALTER statement against a version-8 table and verified retention of title metadata and cover. Android SQLite/JSON round-trip checks were extended and compiled but could not run because the emulator has no Activity service. Final icon change: Kotlin compilation and JVM test verification recorded in the task result.
