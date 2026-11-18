# Viblio 0.4.1

## Resultado

- Principal do clube: capa/livro, prazo final com data/hora/ano e dias restantes, progresso do grupo, etapas e comentários, feed do livro da rodada. Configurações dos admins ficam exclusivamente no menu ⋯; histórico e participantes têm páginas próprias.
- Imagem do clube na lista usa a capa da leitura escolhida. A principal e as discussões continuam com paleta local da capa do clube; sair restaura o tema do livro atual do proprietário.
- Iniciar leitura confirma livro e prazo final em formulário próprio. Admin pode ajustar prazo/etapas; concluir envia para Histórico e preserva discussão, capa e eventos da rodada. É possível abrir histórico enquanto há outra votação/leitura.
- Escolher livro consulta estantes sincronizadas de todos os integrantes ativos, com pesquisa e paginação. Indicação referencia separadamente quem indicou e quem possui o livro. Compartilha somente metadados bibliográficos/capa, sem progresso de outros livros, notas, metas, e-mails ou snapshots.
- Comentários têm contexto da etapa, campo com várias linhas, botão Publicar comentário, ordem mais recente primeiro e acesso condicionado à abertura. Navegação volta ao topo, evitando que o formulário fique fora da tela. Rascunhos/diálogos bloqueiam substituição automática da biblioteca; voltar pede confirmação para descartar comentário ainda não publicado.
- Feed geral: cada card recebe paleta da capa daquela leitura, com páginas à esquerda e tempo à direita. O fundo geral continua seguindo o livro atual do proprietário. Paletas/miniaturas privadas ficam em memória, com limites e invalidação por conta/navegação.
- Leituras compartilhadas automaticamente com amizades aceitas, sem confirmação/switch. Migração habilita contas atuais e novas, sem backfill indiscriminado de histórico anterior. Clientes antigos não conseguem desativar/apagar atividades via antigo switch. Notas/metas continuam privadas.
- Capas do catálogo: o caminho de livro sem vínculo não mostra mais a lista textual de edições antes da grade. Abre diretamente a galeria de três colunas; seleção muda capa/vínculo sem alterar páginas ou o progresso pessoal.

## Verificação

- APK release não depurável, assinatura de atualização preservada, versionCode 37. Build/lint release; 155 testes do Viblio e 300 da Central; compilação do código compartilhado sem APK novo da Central.
- PostgreSQL 17 descartável: 83 testes e 20 subtestes. Inclui todos os casos anteriores mais livros de outros integrantes sem amizade, privacidade da projeção, revogação de miniaturas, IDs locais iguais entre donos, origem correta do progresso, feed por rodada/livro, discussão fechada sem vazar texto, histórico após nova votação, atualização sem spam de eventos, progresso manual preservado e paginação de livros/feed sem repetição.
- Emulador API 28/release: card rosa a partir de JPEG rosa, tema global preservado, clube azul pela própria capa, configuração fora da principal, prazo final, envio de comentário, histórico, indicação da estante de outro membro, saídas/retornos sem crash e galeria de três colunas pelo caminho real do livro sem vínculo.
- Capturas de desenvolvimento com fixtures sintéticas, inspecionadas para distribuição de páginas/tempo e conteúdo da principal. Não são dados de pessoas reais.
- ZIP conferido pelo extrator do atualizador; migrações 001–009 comparadas byte a byte com API 0.4.0; sem `.env`, dumps, caches ou dados pessoais.
- Atualização real em Docker descartável, da API 0.4.0 para o novo esquema, com backup privado, preservação de conta/clube/proposta antiga, backfill do dono da indicação, ativação de compartilhamento, repetição, falha de saúde simulada/retorno à imagem anterior e nova tentativa. Nenhum servidor/aparelho pessoal acessado.

## Limites

Feed/clubes/comentários exigem conexão; leitura pessoal mantém sincronização offline existente. Prazos são ajustados pelos admins, não por automação. Histórico guarda até 100 rodadas/clube, eventos do grupo até 2000/rodada, comentários até 10 mil/clube e atividades pessoais seguem retenção de 180 dias/10 mil eventos. Sem curtidas/comentários no feed geral; comentários são das etapas do clube.

WAF/regras de borda e restrição da origem continuam dependendo da implantação. Não há mudança de túnel/DNS, permissões de leitura de notas privadas ou integração KOReader/HA nesta entrega.

Atualize a [API primeiro](../viblio-api/UPDATE_0_4_1.md) e depois instale o APK por cima. Checksums em [downloads/SHA256SUMS](../downloads/SHA256SUMS).

```bash
python3 ~/atualizar-viblio.py --zip /home/valtecir/viblio-api-0.4.1.zip --sha256 312ac973918b4cf51e860e710152bff35a4893bf87005975fb10e98877319511
```
