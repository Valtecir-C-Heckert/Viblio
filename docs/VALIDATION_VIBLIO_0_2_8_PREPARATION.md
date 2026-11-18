# Viblio 0.2.8 — preparação para convidados

A última entrega pública continua sendo o APK 0.2.7. A preparação de código 0.2.8/code 20 foi compilada/testada com origem pública vazia e não foi publicada como APK: o usuário ainda vai configurar o domínio/túnel. Não foi usada uma URL fictícia na entrega, acessado o Debian pessoal ou registrado um domínio.

## Implementação

- O vínculo confirmado da estante local já existia. A opção agora diz Usar minha estante atual e explica os itens preservados. Perfis de outra conta continuam separados; conta já mapeada volta ao perfil correspondente sem exigir novo convite.
- O convite mostra apenas seu código, validade no horário local e Copiar código. Não inclui endereço privado ou chave da instalação. O mecanismo de uso único, expiração, revogação e autorização administrativa continua no servidor existente.
- A origem HTTPS pode ser fornecida por `VIBLIO_PUBLIC_API_URL` ou `-PviblioPublicApiUrl`, gerando uma pequena constante Kotlin. Validação recusa HTTP, credenciais, caminho, query, fragmento, IP e domínio local/Tailscale. Sem origem informada, mantém a conexão manual de desenvolvimento.
- Na entrada com origem fixa, servidor e configuração da primeira conta ficam ocultos. Sessões existentes mantêm o endereço guardado até o novo login. O APK público não tenta bootstrap com uma chave antiga; instalação não inicializada deve ser concluída pelo administrador antes de distribuir o APK.
- API, PostgreSQL, migrações e bibliotecas do usuário não foram alterados. Não foi necessário um ZIP novo da API ou APK da Central.

## Verificações concluídas

- assembleRelease, assembleDebugAndroidTest e lintRelease: sucesso, com origem pública vazia nesta preparação.
- Instrumentação completa em Android API 28 contra o APK release e PostgreSQL/API descartáveis: passou.
- Cadastro por convite com estante local preenchida: antes da confirmação, revisão remota zero e estante local intacta; depois de confirmar, mantém banco local/IDs, capa JPEG idêntica, nota, páginas, sessão e data. Valida sincronização, alteração offline/online, saída, login sem convite, reabertura do perfil e revogação, além dos testes anteriores de isolamento entre perfis.
- A execução completa rodou os 48 testes de API/updater e quatro testes iniciais do helper. Após reforçar a validação do helper, cinco testes focados passaram, incluindo DNS/HTTPS, idempotência, alteração exclusiva da URL, backup protegido fora do contexto Docker, recusa de chaves duplicadas/symlink/pasta de backup compartilhada sem mudar permissões alheias.
- Configuração Compose do túnel validada com `docker compose config --quiet`, usando dados sintéticos em pasta temporária.
- Imagem oficial cloudflared fixada por digest: CLI confirmou versão 2026.10.7 e `--token-file`. Smoke test com usuário 1000, filesystem somente leitura, sem capabilities e sem rede conseguiu ler um arquivo 600 e recusou o token sintético. Nenhum túnel real foi estabelecido.
- Gerador de origem validado com HTTPS de exemplo apenas como fonte gerada; HTTP, credenciais e caminho foram recusados. Restaurado imediatamente para origem vazia; não se compilou APK com o endereço de exemplo.

## Entrega e pendências

O [roteiro completo](VIBLIO_ACESSO_PUBLICO.md) contém backup, domínio/DNS, token em arquivo protegido, instalação do túnel separado, publicação somente de `/v1/`, bypass de cache da API, teste 4G/5G sem Tailscale, alteração exclusiva de PUBLIC_BASE_URL, retorno seguro e teste do convidado.

Ainda falta executar no servidor pessoal, confirmar o domínio e seu `server_id` acessível pelo celular sem VPN, compilar/testar o login com essa origem real e publicar o APK final. Disponibilidade do servidor, DNS, Cloudflare, regras de borda e teste de celular não foram simulados como se estivessem prontos. As proteções de borda exigem a implantação descrita no guia; não há autoescalonamento do Debian.

## Domínio escolhido após a preparação

O usuário escolheu `https://api.viblio.com.br` como origem padrão. O build e o roteiro foram atualizados; `generateServerConfig` passou e o conteúdo gerado foi conferido com esse endereço exato. As verificações anteriores com origem vazia descrevem a execução histórica de preparação; testes isolados futuros precisam passar `-PviblioPublicApiUrl=` explicitamente. Não houve novo APK publicado, alteração de DNS ou implantação no servidor. O teste externo em dados móveis sem VPN continua pendente antes da entrega pública.

## Persistência dos filtros com organização manual principal

Corrigidos dois pontos que descartavam a seleção: habilitar a organização manual no novo painel e carregar filtros quando SHELF_MANUAL_DEFAULT estava ativo. Agora as preferências do tipo são lidas normalmente; a organização manual é usada quando a ordenação salva está vazia. Cancelar uma organização restaura os filtros anteriores; Limpar no diálogo sai do modo manual e aplica a ordenação padrão. Nenhum registro de livros ou posição manual é apagado, e a ação explícita Minha ordem · limpar filtros continua disponível.

Build release, APK de instrumentação e lintRelease do Viblio, mais compileDebugKotlin da Central: passaram. Os testes usaram `-PviblioPublicApiUrl=` exclusivamente para manter a rede real fora da regressão local. Instrumentação local completa contra esse release, em emulador API 28: passou, sem conexão ao servidor pessoal ou execução de testes de API nesta rodada.

A regressão nativa aplica pelo diálogo real gênero Fantasia, Em leitura e título decrescente, acrescenta busca textual e verifica a lista de capas. Confere recriação de painel sem Bundle, restauração de estado, parada/retorno, passagem por outro tipo e cancelamento de uma organização. Verifica também que a ordem manual principal guardada permanece idêntica. O teste aguarda o Handler do AlertDialog aplicar a confirmação antes de conferir o resultado; não pressupõe callback síncrono do botão.

Esta correção integra a preparação da próxima versão; não foi publicado outro APK nem alterada a API, seu banco ou o domínio público pendente de implantação.
