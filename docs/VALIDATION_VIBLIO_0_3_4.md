# Viblio 0.3.4 — envio pendente, Amigos e compra

## Correções

AccountManager conserva a tentativa automática quando outra operação está ocupada. Ao terminar, reagenda a conferência em vez de perder o evento até o próximo minuto. Alterações feitas durante uma requisição permanecem pendentes para novo envio. Retornar ao primeiro plano volta a conferir o estado local. Formulários, rascunhos, conflitos, revogação de sessão, isolamento de perfis e backoff continuam impedindo substituições inseguras. O texto de confirmação não declara sincronizada uma operação que tem outra tentativa pendente. Não há promessa de execução após o Android encerrar o processo.

FriendsPanel consulta resumos novos a cada 30 segundos, somente enquanto visível. Usa no máximo sete páginas de amigos e uma consulta de estante por ciclo, sem extrapolar o limite normal de 200 amigos. Para até 40 itens carregados, renova a página da estante; em listas maiores, preserva a paginação e atualiza o livro atual pelo resumo. Mantém filtros, rolagem, conteúdo digitado e dados após erros temporários. Evita atualização durante digitação recente ou um diálogo de consulta. Backoff: 60–300 segundos após falhas. Sair, fechar ou trocar perfil invalida resultados antigos; 401/403/404 limpam os dados privados da consulta. A atualização manual permanece disponível.

## Meta de compra

O ciclo mostra capas reais (quando disponíveis), nomes, vagas restantes e a quantidade lida. Um módulo pequeno destaca a recompensa e o estado bloqueada/liberada. Ao atingir a meta, Registrar compra abre a confirmação existente. O excedente é identificado para o próximo ciclo. Compras mantêm a mesma contagem, verificação de versão, consumo de livros únicos e recibos privados de 0.3.2. Não muda armazenamento, schema ou sincronismo das metas. A seção inteira permanece expansível, com a paleta atual e as divisórias contínuas; os contadores de livros e páginas não foram redesenhados.

## Verificação

- Build release Viblio, APK de testes, lint release, testes unitários e compilação Kotlin compartilhada da Central.
- Teste Android de uma consulta de identidade bloqueada: altera página sem rascunho, dispara tentativa durante busy e confirma envio após liberação, em menos que o ciclo periódico.
- Teste de segunda alteração durante upload: primeira resposta não encerra o envio pendente; o servidor de teste recebe a página mais nova. Verifica retomada após stop/start e restaura o perfil isolado.
- Amigos: página aumenta e é corrigida para baixo sem toque no recarregar; consulta do livro mostra o novo progresso. Mantém verificações existentes de TLS, capa, navegação, falhas temporárias, paginação, revisão e biblioteca pessoal intacta.
- Metas: capas fictícias no emulador, recompensa liberada/bloqueada, confirmação, consumo exato e excedente no ciclo seguinte, histórico e dados por perfil. Capturas reais de Estatísticas.
- Somente emulador e fixtures locais; nenhum servidor ou aparelho pessoal alterado. API e migrações inalteradas: não há ZIP novo de servidor. O código da API já passou na auditoria isolada de atualização de página em ambos os sentidos.

Instalar sobre o existente, sem desinstalar, nos dois aparelhos. APK não depurável, certificado mantido.

Capturas nativas com livros fictícios: [compra liberada](../downloads/viblio-0.3.4-preview.png) · [Estatísticas com o ciclo seguinte bloqueado](images/viblio-0.3.4-statistics.png).

145 testes unitários passaram. A suíte nativa geral e as verificações específicas de fila e atualização de páginas passaram.

A comparação das coleções usa igualdade estrutural, sem serializar toda a estante na UI a cada atualização. O caso de amizade encerrada é testado: limpa os livros e apresenta a mensagem correta de acesso encerrado.

APK versionCode 33; SHA-256: `75e22fe236386ec144cb05dd70fd1f7634569137c087e2e9dc3a36cdf1eb645d`.
