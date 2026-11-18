# Viblio 0.2.8 — entrega com domínio público

APK release `br.com.viblio`, versão 0.2.8/code 20, origem padrão `https://api.viblio.com.br`, sem depuração. API 0.2.3 preservada, sem migrações ou ZIP novo da API. Não foi gerado APK da Central.

## Comportamento entregue

Entrada e cadastro não pedem endereço de servidor e não mostram configuração da primeira conta. Convite continua de uso único, com validade e revogação no servidor; o administrador copia apenas o código, sem IP privado ou chave da instalação. Quem já tem conta entra com usuário/e-mail e senha, sem convite novo.

O vínculo confirmado Usar minha estante atual conserva a biblioteca local sem JSON; perfis previamente associados a outras contas permanecem isolados. A correção de filtros mantém a seleção/ordenação por tipo mesmo com ordem manual principal guardada, incluindo cancelamento de organização. Histórico, estatísticas e ícones da 0.2.7 preservados.

Sessões existentes mantêm o endereço anteriormente salvo até sair e entrar novamente. Não se redirecionam silenciosamente credenciais de uma sessão para outro host. Identidade local continua baseada no servidor/usuário, não na URL. Instalar sobre a versão anterior e entrar na mesma conta para usar o endereço público, sem desinstalar.

## Evidências desta entrega

- O proprietário configurou o túnel e relatou API healthy após ajustar PUBLIC_BASE_URL. Confirmou explicitamente JSON pelo endereço público no celular com dados móveis e sem Wi-Fi/Tailscale, em 01/11/2026.
- A conexão independente deste ambiente ao domínio foi bloqueada pelo proxy do próprio ambiente (403 no CONNECT, servidor envoy). Isso não foi classificado como erro da API/Cloudflare. A validação externa é a confirmação do proprietário; cadastro/login reais e capas no servidor pessoal pelo APK ainda serão conferidos no aparelho.
- assembleRelease, assembleDebugAndroidTest e lintRelease: passaram sem override de origem. Fonte gerada conferida: PUBLIC_API_URL exatamente `https://api.viblio.com.br`.
- Instrumentação local completa contra **esse APK release**, em Android API 28: passou. Regressão nova confirma formulário público sem servidor e sem chave/bootstrap, com usuário/e-mail/senha/convite, além de retornar à entrada e usar a Estante offline.
- Passaram também os cenários de filtros aplicados pelo diálogo, recriação/restauração, troca de tipo e cancelamento de organização, mantendo a ordem manual guardada; regressões de estatísticas, histórico, navegação, inventário, persistência e isolamento local de perfis.
- Não foram feitas requisições autenticadas ao servidor pessoal, usados dados pessoais ou criadas contas reais pelo ambiente. O teste local do APK final não executou o fluxo de rede da API. O cadastro com preservação de capa/nota/páginas/sessões/datas, sincronização e reentrada contra API/PostgreSQL descartáveis passou na preparação, documentada em VALIDATION_VIBLIO_0_2_8_PREPARATION.md; não é apresentado como teste desse endpoint público.
- Assinatura preservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. Manifesto minSdk 21, targetSdk 35, somente Internet e estado da rede. Sem credenciais da API/banco/túnel e sem biblioteca pessoal.
- APK distribuído idêntico ao usado na instrumentação final. SHA-256: `f66b00e6de706b0d4afe18dd8ec6834d2d07ff30a13ad2aaa223dea4d014f6ac`.

## Instalação e teste no telefone

GitHub autenticado → Releases → Viblio 0.2.8 → anexo APK. Instale por cima, sem desinstalar. Se estiver em uma sessão com endereço antigo, Conta → Sair da conta, depois entrar na mesma conta. Com Wi-Fi/Tailscale desligados, conferir catálogo, capa e sincronização; depois gerar um convite de teste e verificar em outro aparelho a estante privada.

Servidor, DNS, túnel e cache de borda permanecem uma implantação externa. Manter somente a API publicada e seguir o bypass de cache/proteções do roteiro; não confiar irrestritamente em cabeçalhos de IP encaminhados. Não há autoescalonamento do servidor doméstico nem promessa de disponibilidade enquanto ele estiver desligado.
