# Viblio 0.2.1 — entrada inicial e nome de usuário

## Mudanças

Nome de usuário único, normalizado em minúsculas, com entrada por nome ou e-mail. Cadastro pede e-mail e nome separados; o identificador da conta/biblioteca não muda quando o nome é definido ou editado. Contas anteriores continuam entrando por e-mail e podem definir o nome na Conta. Migração 005 aditiva/repetível: coluna opcional e índice único sem distinguir caixa. API mantém `account_api: 1` e sinaliza `username_login: true`; o formato legado de entrada por e-mail permanece aceito.

Sem sessão, o app abre um formulário inicial de entrada/cadastro/recuperação com o menu inferior oculto. Conectado, abre diretamente a Estante. Sair retorna à entrada. A aba Conta não renderiza formulário de autenticação, inclusive no modo offline. Conserva dados do perfil, definição do nome, saída e funções existentes de sincronização/backups/convites. Opção de uso offline permanece na entrada; não cria nem mistura contas. O botão Home Assistant foi removido da interface, preservando o pareamento privado anterior. A Central não recebeu mudanças.

## Validação

Build do APK e instrumentação, lint e 104 testes unitários do Viblio passaram, com zero falhas/erros/ignorados. API: 24 testes passaram em PostgreSQL 17 isolado, incluindo escolha de nome para conta antiga, nome único sem distinguir caixa, login por usuário/e-mail, conflito sem consumir convite, recuperação, isolamento e sincronização. Migrações 004 e 005 repetidas no banco de teste. Uma advertência de depreciação da dependência de testes.

Imagem Docker da API construída com as dependências congeladas já existentes. Manifesto e assinatura conferidos: Viblio 0.2.1, versionCode 13, minSdk 21, targetSdk 35, somente Internet. Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.

Instrumentação Android API 28 passou: entrada na abertura sem sessão/menu inferior, uso offline e Conta sem campos de login, cadastro com nome/e-mail/convite, sincronização, perfil com nome e sem botão HA, encerramento/reabertura da Activity com sessão salva abrindo diretamente, logout retornando à entrada, novo login por nome de usuário e notas/progresso preservados. Também passaram os testes existentes de navegação, capas, SQLite, arquivamento/restauração, JSON, perfis isolados e JPEG sem recompressão. Fixtures removidas e política temporária do harness restaurada. O teste aguarda a destruição da Activity anterior antes dos fixtures de persistência, evitando concorrência artificial com o checkpoint de leitura.

## Instalação e limites

Atualize a API/migração 005 antes do APK: [guia](../viblio-api/UPDATE_0_2_1.md). Instale por cima, sem desinstalar. Mantém SQLite 10, JSON 6, bibliotecas, capas, notas, histórico, convites e sessões. Não foi acessado ou alterado o servidor pessoal. Os limites de acervo/sincronização/retentiva da 0.2.0 continuam aplicáveis.

A atualização não configura domínio público, TLS, VPN ou encaminhamento de portas. O acesso externo para convidados ainda depende da configuração do endereço público. A apresentação no telefone real deve ser conferida após instalar; o ambiente usa emulador API 28.

## Artefatos

viblio-0.2.1.apk: 2584414 bytes; SHA-256 `03f70d739e0428684ac0b7a01f03b7e2451715c59ef130bf57570433d750a3f1`.

viblio-api-0.2.1.zip: 15979353 bytes; SHA-256 `4a99e035a2a2c6f14b44b2b1a5cb478336cee4261b8d2122fcae991fdfe0a1fe`.

ZIP validado: CRC íntegro, migração 005 e guia presentes, 20 wheels, fontes finais correspondentes e nenhum `.env` ou dado pessoal.
