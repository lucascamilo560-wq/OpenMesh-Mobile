# OpenMesh Constitutional Invariants

| Campo | Valor |
|---|---|
| Status | Normativo para toda implementação posterior às ADRs de 2026-08-25 |
| Base | OpenMesh Architecture v1 + ADR-001/002/003/004/005/006/011 |
| Escopo | Core, runtime, stores, adapters, gateways, aplicações e documentação pública |

## Finalidade

Estas são as leis estruturais do OpenMesh. Uma otimização, biblioteca, rádio, gateway ou requisito de produto não pode violá-las como “detalhe de implementação”. Conflito exige ADR explícita, análise de segurança/compatibilidade e nova versão deste documento antes do merge.

Termos `DEVE`, `NÃO DEVE` e `SOMENTE` são normativos.

## Os 20 invariantes

### OM-C01 — Aplicação declara intenção, não transporte

A Application Agent DEVE expressar destino lógico, payload e policy. Ela NÃO DEVE selecionar BLE, Wi-Fi, Internet, gateway ou qualquer adapter concreto para obter a semântica normal de entrega.

### OM-C02 — Ausência de oportunidade não é falha

Se não há oportunidade válida agora, um objeto elegível DEVE permanecer armazenado em `WAITING` até nova avaliação, expiração, cancelamento autorizado ou remoção por policy. “Sem rota instantânea” NÃO DEVE ser traduzido automaticamente em falha de entrega.

### OM-C03 — O nó existe sem um rádio específico

O node runtime DEVE continuar correto com zero adapters ativos. BLE, Internet ou qualquer meio individual NÃO DEVE ser pré-condição ontológica do OpenMesh.

### OM-C04 — Adapter transporta; engine decide

Um `TransportAdapter` DEVE observar capacidades/oportunidades e mover bytes opacos. Ele NÃO DEVE escolher routing, prioridade global, copy budget, retenção ou significado de entrega.

### OM-C05 — Falhas de adapter são isoladas

Crash, timeout, permissão negada ou indisponibilidade de um adapter NÃO DEVE derrubar o node runtime, corromper o store ou bloquear oportunidades de outros adapters.

### OM-C06 — Identificadores não colapsam

`EndpointId`, `NodeId` criptográfico e `TransportAddress` DEVEM ser tipos e domínios distintos. Nenhuma conversão implícita entre eles é permitida.

### OM-C07 — Identidade criptográfica não é confiança humana

Prova de posse de chave autentica uma identidade criptográfica. Ela NÃO prova nome humano, autorização, reputação, honestidade, disponibilidade nem resistência a Sybil.

### OM-C08 — Relay não precisa de plaintext

O forwarding normal DEVE ser possível sem expor plaintext ou chaves de conteúdo ao relay ou adapter. Funcionalidade que exija inspeção deve ser um endpoint/application service explicitamente autorizado, não um relay disfarçado.

### OM-C09 — Níveis de evidência nunca são promovidos

`LinkWriteCompleted`, recepção de bytes, `NextHopAcceptedDurably`, `DestinationStored` e `AppDelivered` são fatos distintos. Um nível NÃO DEVE ser apresentado, persistido ou contado como outro.

### OM-C10 — Aceitação durável só existe após commit

Um nó SOMENTE pode emitir `NextHopAcceptedDurably` ou `DestinationStored` depois que objeto, estado e efeitos necessários estiverem em commit recuperável. Callback, RAM ou write de link não bastam.

### OM-C11 — Entrega final exige autoridade do endpoint

Quando a policy exige confirmação final, SOMENTE um receipt autenticado de uma identidade autorizada pelo endpoint, ligado inequivocamente ao objeto e ao tipo `AppDelivered`, pode tornar a entrega terminal. Ausência de receipt permanece desconhecida/pendente, não sucesso inferido.

### OM-C12 — Assimetria é first-class

Descoberta, envio e receipt NÃO DEVEM pressupor caminho bidirecional simultâneo. Receipts são objetos delay-tolerant e podem retornar depois, por outro relay ou transporte.

### OM-C13 — Autoridades e mutabilidade são separadas

Bytes protegidos do objeto, blocks mutáveis de protocolo, `DeliveryRecord` local e `LocalExecutionPolicy` DEVEM ter modelos, regras de mutação e persistência separados. Policy local NÃO DEVE vazar para o wire por serialização acidental.

### OM-C14 — Compatibilidade nunca é reinterpretação silenciosa

Bytes v1 DEVEM permanecer byte-exatos no domínio v1. Tradução, encapsulamento, negotiation e downgrade DEVEM ser explícitos e versionados. Falha de v2 NÃO DEVE acionar fallback inseguro silencioso.

### OM-C15 — Todo recurso controlável remotamente é bounded

Bytes, objetos, blocks, fragments, receipts, identities, tentativas, CPU criptográfica, rádio, wakeups, energia e custo financeiro DEVEM ter limites locais. Prioridade remota, inclusive `EMERGENCY`, NÃO concede recursos infinitos nem ignora admission.

### OM-C16 — Capability carrega epistemologia

Cada capability usada para decisão DEVE distinguir medição local, declaração remota, configuração e inferência histórica, com provenance, freshness/expiry e confidence quando aplicável. `unknown` NÃO equivale a zero ou falso.

### OM-C17 — Assinatura prova autoria, não verdade

Capability, receipt intermediário ou rota assinada prova quem fez a declaração e a integridade dos bytes. O planner NÃO DEVE tratar isso sozinho como prova de alcance, honestidade, custo, entrega ou ausência de ataque.

### OM-C18 — Dedup, replay, loops e cópias são persistentes e finitos

Identidade canônica, tombstones, hop/lifetime limits, copy budget e aplicação idempotente de receipts DEVEM sobreviver a restart conforme retention policy. Hints probabilísticos podem acelerar, mas NÃO podem ser a única defesa autoritativa.

### OM-C19 — Decisões são explicáveis e reavaliáveis

Cada reserva/transferência DEVE registrar quais constraints, evidências e versão de estratégia determinaram a escolha. O engine DEVE poder reavaliar quando oportunidade, energia, policy ou receipt mudar, sem reescrever a intenção original.

### OM-C20 — Criptografia é versionada, separada e fail-closed

Identity root, signing, recipient encryption, session e content keys DEVEM ter finalidades separadas; suites e domínios DEVEM ser versionados. Algoritmo desconhecido, binding inválido ou downgrade proibido DEVE falhar fechado, sem plaintext ou key reuse como fallback.

## Teste constitucional em revisão

Todo PR estrutural deve responder:

1. quais invariantes toca;
2. quais testes automatizados demonstram preservação;
3. qual novo consumo remoto foi bounded;
4. qual evidência sustenta qualquer transição de lifecycle;
5. como rollback/compatibilidade evitam reinterpretação silenciosa.

“Nenhum” é resposta válida quando comprovado. O checklist não substitui threat modeling nem ADR.

## Emenda

Uma emenda exige:

- ADR com motivação e alternativa que preserve a missão universal/delay-tolerant;
- análise de segurança, privacidade e custo de reversão;
- estratégia de compatibilidade para stores, wire e adapters existentes;
- atualização simultânea da Architecture v1 e dos testes de conformance afetados;
- aprovação antes de implementação que dependa da mudança.
