# Viblio 0.3.2 — metas pessoais

## Comportamento

- Estatísticas → Metas é uma única seção expansível, inicialmente recolhida, entre Seu ritmo e Sua estante. O botão + cria uma meta sem exigir manter a seção aberta.
- Livros: círculo com quantidade concluída, alvo e restante. Páginas: contador de dígitos, alvo e restante. Compra: passos por livro e desbloqueio de recompensa, sem repetir barras de progresso.
- Nome, quantidade, período (dia, semana, mês, ano ou contínuo) e tipo (livros, mangás, quadrinhos ou todos) configuráveis. Recompensa de compra independente. Editar/remover preserva as demais metas.
- Páginas são calculadas a partir das sessões; corrigir uma página não inventa leitura. Sessões iniciadas antes da criação descontam o progresso já existente. Leituras arquivadas continuam contabilizadas. Conclusão de livro com páginas conhecidas exige sessão chegando à última página; correções/edições não geram conclusão. Reler não apaga conclusão já registrada no período.
- Metas normais podem incluir o histórico do período ao criar. Compras começam na criação e excluem livros já concluídos. Confirmar uma compra consome exatamente N livros únicos e registra título/autor/data e recompensa do ciclo; excedentes permanecem disponíveis. Um livro nunca é reutilizado em outro ciclo da mesma meta. Histórico continua após remoção do livro.
- As listas detalhadas identificam livros e, para páginas, sessões/páginas/tempo. Histórico de compras abre os livros daquele ciclo.

## Persistência e segurança

Configuração/ciclos ficam em SharedPreferences privadas e congeladas por perfil, sob `SHELF_READING_GOAL:<UUID>`. O protocolo atual já captura e restaura todas as chaves `SHELF_`, como valores opacos: clientes antigos preservam as metas; não precisam entender o formato. A preferência de expansão é local e não é enviada. Conflitos concorrentes na mesma meta são explícitos; metas com UUIDs diferentes mesclam independentemente. Amigos recebem somente a projeção pública existente, sem metas/recibos.

Limites: 24 metas, 256 ciclos por meta, 100 livros por compra, 5.000 livros em meta normal, 10 milhões de páginas de alvo e 1 MiB por configuração. Validação de UUIDs, tipos, limites e profundidade JSON antes do parsing. Dados inválidos não são sobrescritos: aparecem como Meta indisponível, com remoção. Escritas verificam a versão original e falham quando a meta mudou. Confirmação de compra recalcula os créditos antes de gravar. Editor aberto bloqueia substituição automática da biblioteca; diálogos fecham com o perfil.

Sem mudanças de endpoints, migrações, dependências, permissões ou formato do snapshot. **Não exige atualização da API**; testes usam o código atual da API 0.3.1. Nenhuma alteração nos fundos/capas, navegação de amigos ou estantes anteriores. Central apenas compilada para conferir o código compartilhado; nenhum APK da Central publicado.

## Verificação

- Build release do Viblio, APK de testes, lint release, testes unitários e compilação Kotlin da Central.
- 145 testes unitários, incluindo 16 novos casos de contagem/calendário/tipos/baseline/arquivamento/compras/créditos excedentes/reutilização/correções/futuro.
- API em PostgreSQL descartável: 67 testes e 20 subtestes; sincronização opaca, mesclagem independente, conflito no mesmo campo, isolamento entre contas e privacidade perante amigos.
- Android API 28: suíte nativa e verificações de criação/edição/remoção, seção inteira recolhida/expandida, persistência por perfil, captura/restauração pelo protocolo existente, contadores, compra/histórico, escrita obsoleta e JSON de profundidade inválida.
- Assinatura preservada; release sem flag debuggable. Atualização por cima da instalação existente, sem desinstalar.

Os testes usam somente emulador e banco `viblio_test`; nenhum servidor/aparelho pessoal acessado. Proteções públicas de infraestrutura (Cloudflare Tunnel/WAF/TLS) permanecem sob a implantação existente do servidor.

APK: `downloads/viblio-0.3.2.apk` — versionCode 31.

SHA-256: `795607c3a8d81e4151231af1c53b6bf1673f08d523d8108eccf9a316f8ae004d`. Certificado preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.

Capturas da seção nativa com dados fictícios: [aberta](images/viblio-0.3.2-goals-expanded.png) · [recolhida](images/viblio-0.3.2-goals-collapsed.png).
