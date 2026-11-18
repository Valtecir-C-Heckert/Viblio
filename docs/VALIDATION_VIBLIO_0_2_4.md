# Viblio 0.2.4 — navegação e histórico

APK release `br.com.viblio`, versão 0.2.4/code 16. API 0.2.3 preservada, sem migrações novas ou APK da Central.

## Comportamento

Estante na navegação inferior abre explicitamente a lista ao sair do Livro atual, inclusive quando o painel compartilha a aba interna da Estante. O livro atual e a sessão permanecem salvos. Formulários não salvos pedem confirmação; operações de arquivo em andamento continuam protegidas.

No Viblio, o histórico expandido reúne datas, sessões e paginação no mesmo card. Os dias usam divisores simples, sem fundos/bordas independentes. Controles para editar datas, recolher e carregar mais dias continuam disponíveis. A Central mantém sua apresentação anterior pelo sinalizador de interface desativado por padrão.

## Validação concluída

- Build release, APK de instrumentação e lintRelease do Viblio; compilação Kotlin da Central: sucesso.
- 48 testes Python da API/updater e 7 subtestes passaram no harness descartável.
- Instrumentação nativa completa executada contra o APK release, em Android API 28 e PostgreSQL/API descartáveis: sucesso.
- Regressão específica cria cinco sessões, abre Livro atual, expande o histórico, verifica um único card e dias sem fundo próprio, carrega a quinta data, verifica sessões intactas e retorna à lista pelo atalho Estante. Reabre Livro atual e verifica também Conta → Estante, preservando o ID atual.
- Fluxos existentes de pesquisa/capas/cache, contas/convites/perfis, sincronização, edição pessoal, leitura, arquivo/restauração, inventário de volumes e persistência também passaram.
- Certificado de atualização preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. minSdk 21, targetSdk 35, sem depuração; permissões Internet e estado de rede.
- APK distribuído é o mesmo usado na instrumentação. SHA-256: `94264e1d86af147a5516a645217287f5b98a8498eee8c235021f4ece6a5d2e74`.
- Publicador de Releases validado localmente tanto com APK+API quanto com APK apenas. Mantém anexos existentes e verifica checksums antes de publicar.

## Conexão remota

Não houve alteração da configuração de rede no APK. Se `/v1/auth/info` abre pelo endereço Tailscale no navegador do mesmo celular, a API está acessível pela VPN. O endereço salvo no aplicativo precisa apontar para esse servidor; o Tailscale não substitui automaticamente um endereço LAN. Na entrada, expandir Servidor e usar a URL Tailscale com a porta da API. Nenhum endereço pessoal foi embutido no aplicativo.

A validação não acessou o servidor pessoal nem confirma a configuração de entrada no telefone. Nenhum dado pessoal ou credencial foi incluído nos artefatos. Não foi necessária nova execução dos testes unitários de modelos, pois esta versão altera navegação e apresentação, verificadas na instrumentação.

## Instalação

Instalar o APK sobre o Viblio existente, sem desinstalar. No GitHub autenticado, abrir Releases → Viblio 0.2.4 → anexo APK. Não há ZIP de API 0.2.4: estas correções usam a API 0.2.3 existente.
