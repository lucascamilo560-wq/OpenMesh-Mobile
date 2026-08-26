# OpenMesh ADR/conformance spike

Este diretório contém tooling de referência para as decisões ADR-001 e ADR-011. Ele não pertence a nenhum source set Gradle, não é biblioteca de produção e não altera o runtime.

## O que é medido

Os payloads de aplicação de 32 B, 64 B, 256 B, 1 KiB e 10 KiB são codificados em três perfis:

1. `envelope-v1`: bytes exatos do `MeshEnvelopeCodec` atual, com payload Base64, unicast, prioridade `NORMAL` e sem assinatura;
2. `bpv7-openmesh-minimal`: bundle BPv7 determinístico com EIDs `dtn`, CRC-16 no primary block, ADU tipado, block candidato de policy e Hop Count;
3. `bpv7-openmesh-bpsec`: o mesmo significado, com BIB-HMAC-SHA-256 sobre primary/policy e BCB-AES-256-GCM sobre o ADU, ambos com escopo completo.

O resultado mede tamanho total, overhead, expansão e quantidade de frames no caminho BLE atual (ATT MTU 23, 7 bytes de objeto por frame). A coluna MTU 247 é apenas sensibilidade: o cliente de dados atual não negocia esse MTU.

O JSON inclui ainda uma sensibilidade de equivalência contra o E2E v1 atual. Ela usa a estrutura exata dos codecs e hipóteses explícitas de comprimentos comuns do provider: chave P-256/X.509 de 91 bytes e assinatura ECDSA/DER de 70–72 bytes. Não é um quarto perfil primário nem um vetor criptográfico; providers válidos podem produzir outros comprimentos.

## Reproduzir

Pré-requisito: Python 3.12 (3.11+ deve funcionar). Em um ambiente virtual:

```bash
python3 -m pip install -r requirements.txt
python3 -m unittest -v test_conformance.py
python3 benchmark.py --check
python3 benchmark.py
```

Para regenerar os resultados após uma mudança deliberada do perfil:

```bash
python3 benchmark.py --write
python3 -m unittest -v test_conformance.py
```

`--check` falha se JSON, CSV ou os vetores de 32 B não forem byte-for-byte reproduzíveis.

## Limites honestos

- Isto é benchmark de wire size e frame amplification, não de CPU, rádio, airtime ou bateria.
- O block type 192 é privado/experimental e existe só para tornar a policy candidata mensurável; não é alocação de registry.
- Os EIDs incorporam o `om1` apenas como mapeamento singleton de compatibilidade. `EndpointId`, `NodeId` e `TransportAddress` continuam tipos distintos.
- As chaves e IVs são públicos e determinísticos para fixtures. Não são um mecanismo de geração de chaves/IVs.
- A construção de BIB-HMAC e BCB-AES-GCM é verificada contra vetores normativos do RFC 9173, mas não substitui a decisão de identidade assimétrica self-certifying nem constitui teste externo de interoperabilidade. Essa lacuna permanece explícita no ADR-011.
- O spike desabilita fragmentação BP. Segmentação/resume de CLA e a revisão dos problemas de fragmentação apontados nos errata do RFC 9171 permanecem decisão futura (ADR-013).

## Fontes normativas

- [RFC 9171 — Bundle Protocol Version 7](https://www.rfc-editor.org/rfc/rfc9171.html)
- [RFC 9171 — errata](https://www.rfc-editor.org/errata/rfc9171)
- [RFC 9172 — Bundle Protocol Security](https://www.rfc-editor.org/rfc/rfc9172.html)
- [RFC 9173 — Default Security Contexts](https://www.rfc-editor.org/rfc/rfc9173.html)
- [RFC 9713 — Administrative Record Types Registry](https://www.rfc-editor.org/info/rfc9713/)
- [RFC 9758 — Updates to the ipn URI Scheme](https://www.rfc-editor.org/info/rfc9758/)
