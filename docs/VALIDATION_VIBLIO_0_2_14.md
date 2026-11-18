# Viblio 0.2.14 — Amigos e bordas de progresso

## Falha reproduzida

Entrar em Amigos, carregar dados por HTTPS e sair para outra aba encerrava o app. A resposta HTTP local não reproduzia o problema. Um servidor TLS local com conexão persistente reproduziu a exceção no Android API 28:

```text
android.os.NetworkOnMainThreadException
  ConscryptFileDescriptorSocket.shutdownAndFreeSslNative
  ConscryptFileDescriptorSocket.close
  okhttp3.internal.connection.RealConnectionPool.evictAll
  okhttp3.ConnectionPool.evictAll
  br.com.viblio.account.AccountApi.close
```

O encerramento de um socket TLS pode enviar `close_notify`. Antes, `FriendsPanel.hide()` chamava `AccountApi.close()` na interface, e esta fechava o pool nessa mesma thread. Agora, marca o cliente encerrado imediatamente e executa cancelamento/limpeza no executor do próprio cliente, encerrando esse executor em seguida. Chamadas repetidas de `close()` são seguras. Não se desativou StrictMode nem a validação TLS.

## Mudanças de interface e privacidade

- As cores da lista são individuais: nome e contorno de cada amigo acompanham a capa atual dele. Ao abrir sua estante, os destaques seguem essa mesma capa, inclusive nas coleções.
- Usa uma paleta local do painel, sem alterar o destaque global do livro atual do dono da conta. Cores são ajustadas para contraste mínimo de 4,6 no fundo ativo escuro; dourado quando não há capa/cor.
- Reaproveita a miniatura privada autenticada para extrair a cor fora da interface; no máximo dois trabalhos simultâneos, 4 MiB de cache de imagem em RAM, JPEG até 160×240 e 96 KiB. Carrega imagens visíveis primeiro; apenas a capa atual do amigo selecionado tem prioridade independente da visibilidade.
- Ao sair, limpa miniaturas, cores e callbacks. Respostas tardias não redesenham o painel encerrado. Nenhuma miniatura de amigos é persistida no armazenamento da biblioteca.
- Ao restaurar uma estante de amigo sem os metadados em memória, consulta a lista paginada existente, no máximo sete páginas, antes de abrir a estante. Atualizar também renova a referência de leitura atual.
- A estante de amigos usa grade de capas com títulos e indicadores de progresso, abrindo coleções e volumes. A pinça passa pelo mesmo `ReadingScrollView` do host e altera de 2 a 6 colunas, sem reconsultar o servidor. Não importa livros nem altera a biblioteca pessoal. Textos explicativos da tela removidos; ícone de atualizar usa uma seta circular contínua.
- Bordas das capas do Viblio passam a retangulares, incluindo a ponta do progresso. A espessura e o recuo acompanham a largura entre 55% e 125% da referência de 100 dp. Cores e cálculo percentual preservados. Central mantém os cantos e tamanhos anteriores.
- Uma miniatura do menu inferior que esteja aguardando um perfil já substituído pela sincronização deixa de propagar falha de acesso ao banco encerrado.

## Verificação

- Build release, AndroidTest, 129 testes unitários e compilação Kotlin da Central: aprovados. Lint release: zero erros, 47 avisos preexistentes.
- Teste nativo focado: TLS persistente local, fechamento repetido na interface, saída durante trabalho de imagem/cor, saída/retorno do painel e troca real de Amigos para Estante, Livro atual, Pesquisa e Estatísticas: aprovado.
- Grade nativa e zoom: verificados 2, 3 e 6 filhos por linha; coleção abre seus volumes e mantém navegação de retorno. Biblioteca própria, progresso e livro atual inalterados.
- Fixture de capa azul: nome do amigo e aba de filtro assumem o azul com contraste; paleta global permanece igual. Solicitar, aceitar, excluir amizade, erro de usuário inexistente e rejeição de URL de capa externa mantidos.
- Suíte nativa completa no APK de distribuição (`downloads/viblio-0.2.14.apk`) no emulador API 28: aprovada. Inclui navegação, tema do próprio livro, scanner/retorno, deep links e isolamento de perfis.
- API/SQL não foram alterados; nenhum servidor pessoal foi usado para testes. Não foi necessário gerar outro ZIP de API.

Capturas nativas sintéticas: [Amigos](images/viblio-0.2.14-friends.png), [2 colunas](images/viblio-0.2.14-friend-shelf-2-columns.png), [3 colunas](images/viblio-0.2.14-friend-shelf-3-columns.png), [6 colunas](images/viblio-0.2.14-friend-shelf-6-columns.png).

O fixture TLS usa certificado/chave exclusivos de teste em `src/androidTest/assets/localhost-test.p12`, senha pública `fixture-only`. Apenas o cliente de teste confia nesse certificado; o cliente normal continua com o verificador e as autoridades padrão. O fixture não entra no APK de distribuição e não acessa o servidor pessoal.

## Instalação

Somente APK 0.2.14, versionCode 26, pacote `br.com.viblio`, release sem depuração. Instalar por cima, sem limpar os dados nem desinstalar. Mesmo certificado da versão anterior. Biblioteca, contas e IDs mantidos. API compatível: 0.2.13, sem ZIP, migração ou comando de atualização adicionais. Sem APK da Central nesta entrega.

## Artefato

- APK: `viblio-0.2.14.apk`, 4.403.895 bytes.
- SHA-256: `1a316ddce849ad3ad3f587f7eb7cb83b0d7615543c5b18dc13ef71905f5b7590`.
- Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
- Manifesto sem `debuggable=true`, backup de sistema desativado. Certificado/PKCS12 de teste ausente do APK.
