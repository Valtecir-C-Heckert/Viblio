# Viblio

Aplicativo Android independente de biblioteca pessoal, controle de leitura, metas, amigos e clubes de leitura, acompanhado da API própria (`viblio-api/`).

- **Aplicativo Android (`viblio/`)**: Kotlin nativo + Views/XML, `applicationId = br.com.viblio`, `minSdk 21`, `compileSdk/targetSdk 35`. Veja [viblio/README.md](viblio/README.md).
- **Servidor / API (`viblio-api/`)**: FastAPI + PostgreSQL + Docker Compose + migrações versionadas + atualizador. Veja [viblio-api/README.md](viblio-api/README.md) e [viblio-api/UPDATER.md](viblio-api/UPDATER.md).
- **Downloads e histórico de versões (`downloads/`)**: APKs assinados, pacotes da API e checksums em [downloads/README.md](downloads/README.md) e [downloads/SHA256SUMS](downloads/SHA256SUMS).
- **Documentação e validações (`docs/`)**: guias da biblioteca, acesso público, catálogo e relatórios `VALIDATION_VIBLIO_*.md`.

> O aplicativo **Central da casa** (`br.com.centralcasa`) e a integração do dashboard do Home Assistant (`home-assistant-viblio/`) permanecem no repositório separado [`Valtecir-C-Heckert/Home-assistant`](https://github.com/Valtecir-C-Heckert/Home-assistant).

## Build e testes locais

```bash
# APK de debug, testes unitários e lint
./gradlew --no-daemon :viblio:assembleDebug :viblio:testDebugUnitTest :viblio:lintDebug

# APK de release (sem depuração, preservando o certificado de instalação)
./gradlew --no-daemon :viblio:assembleRelease
```
