# Viblio 0.4.0 — Feed, pessoas e clubes

Amigos oferece Feed, Clubes e Meus amigos, com cards de contorno fino e títulos serifados conforme a referência aprovada. Feed e lista de clubes seguem o livro atual do proprietário; dentro do clube, a capa do livro selecionado define a paleta local, inclusive nas discussões, sem modificar o tema das outras abas. Clube sem capa/livro escolhido usa tema neutro. Não são exibidos contadores fictícios de curtidas/comentários. A lista de adicionar mostra os nomes cadastrados, com pedido/aceite antes de acompanhar a estante e o feed. Capas do catálogo agora usa três colunas; a escolha permanece individual e a paginação preenche a grade.

## Consentimento e leitura

Compartilhar minhas leituras começa desligado. Ativar vale para novas atividades, sem publicar histórico anterior. Feed de amizades aceitas, com início, sessões (páginas e tempo), conclusão (tempo registrado e nota) e avaliações. Sessões sincronizadas após uso offline conservam sua data real, desde que posterior à ativação. Edição substitui o evento da mesma sessão; remoção/arquivamento o retira. Opt-out apaga atividades do proprietário. Não publica notas, metas, e-mails ou JSON privado.

Limites explícitos: retenção de 180 dias/10 mil eventos por pessoa e 512 eventos por sync, evitando publicação em massa por importação. Avaliações usam horário do recebimento, pois não possuem timestamp próprio. Feed/clubes precisam de rede; leitura pessoal mantém o mecanismo offline existente.

## Clubes

Uma pessoa pode estar em vários clubes e ter papéis diferentes em cada um. Criador começa admin; convite exige aceite. Vários admins podem coexistir. Promover não remove o admin atual; transferir promove o destino e rebaixa o remetente na mesma transação. Alterações concorrentes não deixam o clube sem admin. Admin global do aplicativo não recebe acesso automático a clubes privados.

Cada rodada permite indicação de livro da própria estante sincronizada, voto único alterável, seleção entre os mais votados e prazo geral. Admin define etapas por página/data, abre discussão e ajusta datas, podendo deslocar discussões futuras junto. Progresso médio e participantes no limite ajudam a decidir; não há alteração automática de datas. Vinculação compatível da própria edição acompanha o sync; página informada manualmente no clube não modifica a estante pessoal. Discussões são exclusivas de membros ativos e etapas abertas, com exclusão pelo autor/admin, paginação e histórico de rodadas.

## Ritmo e histórico

Dentro do livro, Seu ritmo destaca o total de tempo à esquerda e a média por página à direita. Os cálculos continuam baseados nas sessões salvas, com Tempo para concluir nos livros concluídos.

Tocar uma sessão, ou usar o lápis do dia, abre edição de data e duração em horas/minutos/segundos. Salvar é atômico; Cancelar preserva o registro. Apagar exige confirmação e remove somente a sessão escolhida, mantendo a página do livro e o cronômetro em andamento. Registros alterados enquanto a edição estava aberta exigem reabrir o formulário. Diálogos/rascunhos impedem substituição automática da biblioteca durante edição.

Tempo total, médias e estatísticas são recalculados; alterações/exclusões entram no sync automático. Feed remove a sessão apagada, corrige a duração editada e recalcula o total da conclusão quando uma sessão anterior muda. IDs e páginas não são recriados. O dia corrigido continua sendo a atribuição de calendário do histórico; os horários originais de início/fim são preservados.

## Verificação

- Build release não depurável, lint release e 155 testes unitários do Viblio e 300 testes da Central; código compartilhado compilado, sem falhas.
- PostgreSQL 17 descartável: 78 testes e 20 subtestes. Inclui diretório/consentimento, feed sem duplicação, edição, exclusão, arquivamento, opt-out, cursor composto, convites, papéis por clube, transferência, rebaixamentos concorrentes, livros privados, miniaturas autorizadas, votação, prazos, progresso, etapas fechadas, mensagens e histórico. Inclui a suíte anterior de isolamento, catálogo, metas privadas, conflitos e sincronização.
- Android nativo de leitura: cancelamento sem alteração, minutos inválidos recusados, edição de 50 para 30 minutos, total recalculado, cancelamento da exclusão, exclusão confirmada sem retroceder a página e rejeição de edição/exclusão com registro antigo. Captura real do novo alinhamento de Seu ritmo.
- Suíte nativa geral API 28: navegação, histórico, progresso, metas, perfis e amigos, com fixtures sintéticas autenticadas. Saída de Amigos e retorno às demais abas preservados.
- Verificação nativa social, também no APK release: sessões com páginas/duração, opt-in, diretório/pedido pendente, clube, progresso, controles de admin, envio de discussão e várias saídas/retornos. Grade de capas com paginação/deduplicação mantém a capa escolhida. Uma capa azul no clube altera sua paleta local e a saída restaura a paleta do livro atual sem mudá-la globalmente.
- Atualização real em Docker/PostgreSQL descartável, partindo do pacote API 0.3.1. Backup privado, migrações 008/009, reconstrução, healthcheck, repetição idempotente, falha de saúde simulada com retorno à imagem anterior e nova tentativa. `.env`, catálogo, identidade do volume e arquivo privado sentinela preservados. A migração 010 usada para simular falha pertence apenas ao teste e não entra no pacote.
- ZIP validado pelo próprio extrator do atualizador: nove migrações; 001–007 comparadas byte a byte com a versão publicada; sem `.env`, dumps ou caches.

Validação restrita a emulador e bancos `viblio_test*`; nenhuma conta real ou servidor do proprietário foi acessado. As capturas de desenvolvimento são fixtures, não bibliotecas de usuários. WAF, regras Cloudflare e acesso à origem dependem da implantação, conforme SECURITY.md. Sem notificações push.

## Entrega

APK versionCode 36, assinatura preservada (`5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`). Instale por cima, sem desinstalar. Atualize a API primeiro com o [guia](../viblio-api/UPDATE_0_4_0.md); mantenha o atualizador fora da pasta da API, configuração e volumes existentes.

Checksums exatos em [SHA256SUMS](../downloads/SHA256SUMS) e nos anexos da versão. O servidor real continua dependendo da instalação do ZIP pelo proprietário.

No servidor, depois de copiar o ZIP para `/tmp` conforme o guia:

```bash
python3 ~/atualizar-viblio.py --zip /tmp/viblio-api-0.4.0.zip --sha256 c52046f964f1470c0492a4bb5edf3bfa454f34def484bb5d6879d25ec361cb82
```
