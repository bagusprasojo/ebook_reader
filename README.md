# Ebook Reader JavaFX (Offline DRM Reader)

## Scope v1
- Login online
- Fetch library online
- Download `.bookpkg` online (supports resume)
- Open and read offline
- Signature verification (`manifest.bin` + `license.sig`)
- Single page / two-page spread toggle
- Cover rule: page 1 on right, then spread starts 2-3
- Per-book text search (offline)
- SQLite local schema for bookmarks/highlights/notes/annotations

## Run
1. Set env profile (optional):
   - `-Dreader.env=dev|staging|prod`
2. Set decryption key (choose one):
   - fill `drm.masterKeyB64` in `src/main/resources/config/application-<env>.properties`, or
   - set OS env var `READER_MASTER_KEY_B64`
3. Run app:
   - `mvn javafx:run`

## Important
- Replace `src/main/resources/keys/public_key.pem` with backend signing public key.
- Current backend integration expects existing endpoint contracts:
  - `POST /api/auth/login`
  - `GET /api/books`
  - `POST /api/book/download`

## Notes
- Device id is auto-resolved from backend registered device list using local device hash; reader auto-registers device when missing.
- Local config override is stored at `~/.ebook-reader/config.properties` via menu `Setting > Config`.
- Bookmark, note, highlight, annotation, and reading progress are persisted to SQLite.
"# ebook_reader" 
