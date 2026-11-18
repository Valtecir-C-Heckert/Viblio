# Viblio 0.3.1

## Atualizar no servidor

Baixe o ZIP da API e coloque-o na raiz do seu Nextcloud. No terminal de `srvValtecir`:

```bash
sudo cp /DATA/AppData/nextcloud/var/www/html/data/valtecir/files/viblio-api-0.3.1.zip /tmp/viblio-api-0.3.1.zip
sudo chown "$(id -un):$(id -gn)" /tmp/viblio-api-0.3.1.zip
chmod 600 /tmp/viblio-api-0.3.1.zip
test -f ~/atualizar-viblio.py || cp /opt/viblio/api/scripts/atualizar-viblio.py ~/atualizar-viblio.py
python3 ~/atualizar-viblio.py --zip /tmp/viblio-api-0.3.1.zip --sha256 f1f198ca9ecdbbeb9099dc968873b20d4d9aeefa725e0aa246c0969ceeb3c49d
```

Se o ZIP estiver em outra pasta, localize-o e use esse caminho no `cp`:

```bash
sudo find /DATA/AppData/nextcloud/var/www/html/data/valtecir/files -type f -name 'viblio-api-0.3.1.zip'
```

O atualizador guarda os backups e preserva `.env` e volumes. Sem migração nova. Depois confira:

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Aguarde `healthy`. Instale o APK por cima, sem desinstalar ou limpar dados. [Guia completo](../viblio-api/UPDATE_0_3_1.md).

## Implementação

- Paleta do card de leitura usa a capa do próprio livro. Mantém o fundo de capa ampliada/escurecida em todos os livros, os detalhes existentes e a paleta das telas gerais.
- Registro manual de leitura com horas/minutos ao avançar pela barra. Sessão e progresso gravados em uma transação, com rejeição de página desatualizada e proteção do rascunho do cronômetro. Cancelar não grava; corrigir página não cria sessão; voltar páginas mantém o histórico. O diálogo bloqueia substituição automática da biblioteca enquanto aberto e é fechado ao destruir o perfil.
- Status de leitura distingue livro nunca iniciado de leitura pausada, em andamento ou concluída.
- Consulta nativa somente de leitura reutilizada em Pesquisa e Amigos. Imagens privadas usam loader autenticado próprio, descartado ao fechar; busca mantém a ação Adicionar à estante. Sem gravar biblioteca ao consultar amigo.
- Página do livro atual na lista de amigos enviada em projeção SQL explícita, sem carregar/exportar snapshot privado, notas, avaliações ou sessões. Mesma autorização de amizades aceitas e de capas; sem migração nova.
- Ao sair da aba Amigos, limpa seleção e posição. Background/retorno ao app mantém apenas a navegação em andamento quando adequado.
- Falhas transitórias mantêm itens carregados. Paginação com revisão vencida reinicia uma vez sem apagar a estante durante a espera. 401/403/404 de acesso à estante limpam o conteúdo privado. Erros de miniatura não alteram a lista de livros.
- Rolagem de arraste com passos menores a cada 16 ms. A saída de uma célula não cancela o movimento; o contêiner também recebe eventos nos espaços entre capas. Drop/fim/cancelamento encerram o movimento.
- Títulos visuais ocultos no modo capas em todos os zooms, mantendo nomes para acessibilidade. Lombadas preservadas, com ajuste de fonte sem quebrar palavras como Sacramentadora.

## Verificação

- Build release, AndroidTest, compilação Kotlin da Central e 129 testes unitários aprovados. Lint release: zero erros, 47 avisos preexistentes.
- API: 65 testes e 20 subtestes aprovados em PostgreSQL descartável, incluindo progresso atual e exclusão de campos privados/autorizações/revogação de capas.
- Suíte nativa completa aprovada no emulador API 28: consulta da Pesquisa com adição explícita, registro de leitura manual de 25 minutos e páginas 0→20, correção sem sessão, rejeição de progresso desatualizado, novos status e preservação dos dados pessoais.
- Sacramentadora verificada em lombadas de quatro larguras, sem quebra da palavra. Texturas e largura por páginas preservadas. Organização manual, persistência, cancelamento e títulos ocultos após reflow passaram.
- No APK final, Amigos passou novamente com falha temporária de paginação, repetição do cursor correto, preservação dos livros, conflito de revisão e recuperação, consulta com página 42/100 e retorno à lista ao trocar de aba.
- Card de livro azul não atual foi verificado azul com um vermelho como leitura atual, sem alterar o destaque global nem o livro atual. Fundo ampliado de cada capa preservado. Teste focado executado com dados limpos do emulador.
- API e migrações empacotadas comparadas byte a byte com fontes; nenhum servidor pessoal foi utilizado. Nenhum APK da Central nesta entrega.

## Instalação

APK 0.3.1, versionCode 30, `br.com.viblio`, release sem depuração e mesmo certificado de assinatura. API 0.3.1 necessária para mostrar página atual na lista de amigos. Não exige migração adicional; pacote inclui histórico 001–007 intacto. Ver `viblio-api/UPDATE_0_3_1.md`. Instalar APK por cima; preservar dados e contas.

## API empacotada

ZIP: `viblio-api-0.3.1.zip`, 16026554 bytes, SHA-256 `f1f198ca9ecdbbeb9099dc968873b20d4d9aeefa725e0aa246c0969ceeb3c49d`. CRCs verificados, migrações publicadas comparadas byte a byte, sem .env, chaves ou dumps.

Depois de copiar o ZIP do Nextcloud para `/tmp`, atualizar no servidor:

```bash
python3 ~/atualizar-viblio.py --zip /tmp/viblio-api-0.3.1.zip --sha256 f1f198ca9ecdbbeb9099dc968873b20d4d9aeefa725e0aa246c0969ceeb3c49d
```

## APK

`viblio-0.3.1.apk`: 4414045 bytes. SHA-256 `8e3278749c370e06bdf12755dda671bd836946df2c1a8401d3ed9fcba71a4141`. Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
