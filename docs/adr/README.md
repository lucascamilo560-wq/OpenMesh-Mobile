# Architecture Decision Records

As ADRs deste diretório refinam a [OpenMesh Architecture v1](../OPENMESH_ARCHITECTURE_V1.md). Decisões aceitas governam implementações futuras; não afirmam que o runtime atual já as implemente.

## Ordem de decisão desta rodada

| Ordem lógica | ADR | Status | Decisão resumida |
|---:|---|---|---|
| 1 | [ADR-001](ADR-001-bpv7-wire-baseline.md) | `Accepted` | BPv7 é o baseline canônico do wire v2; inteligência OpenMesh permanece acima dele |
| 2 | [ADR-002](ADR-002-identifier-taxonomy.md) | `Accepted` | `EndpointId`, `NodeId` e `TransportAddress` são tipos e domínios distintos |
| 3 | [ADR-003](ADR-003-delivery-object-anatomy.md) | `Accepted` | Objeto protegido, estado de forwarding e policy local têm persistência e autoridade separadas |
| 4 | [ADR-005](ADR-005-transactional-delivery-store.md) | `Accepted` | Aceitação só existe após commit; estado do objeto, entrega e tentativa não é um único enum |
| 5 | [ADR-006](ADR-006-receipt-taxonomy.md) | `Accepted` | Link write, aceitação durável e entrega à aplicação são evidências diferentes |
| 6 | [ADR-004](ADR-004-transport-spi-opportunity.md) | `Accepted` | Adapters declaram fatos/oportunidades e movem bytes opacos; o engine decide |
| 7 | [ADR-011](ADR-011-identity-v2.md) | `Experimental` | Separação de chaves é obrigatória; suite e integração BPSec ainda exigem spike |

Esta ordem é deliberada: formato, nomes e anatomia vêm antes da persistência; persistência define o significado honesto dos receipts; só então o SPI pode reportar eventos sem inventar semântica de entrega. A identidade v2 fecha a rodada como experimento porque congelá-la prematuramente teria o maior custo de reversão.

## Status permitidos

- `Accepted`: decisão normativa para trabalho futuro.
- `Experimental`: direção preservada, mas detalhes bloqueiam uso de produção.
- `Rejected`: alternativa avaliada e recusada.
- `Superseded`: substituída por ADR posterior.

## Regra de governança

“Padrão da indústria” não é justificativa suficiente. Cada ADR declara invariantes atendidos, compromissos, ameaças, condições de conformidade e custo de reversão. Mudança que viole os [invariantes constitucionais](../OPENMESH_CONSTITUTIONAL_INVARIANTS.md) exige nova ADR e não pode ser escondida como detalhe de implementação.

## Escopo desta rodada

Este commit contém somente documentação, harness, fixtures e resultados em `docs/` e `spikes/`. Não há implementação de BPv7, store, adapter, identidade v2 ou qualquer mudança de comportamento no runtime.
