# Relatório do spike de wire size e conformidade

| Campo | Valor |
|---|---|
| Escopo | ADR-001 e insumos de ADR-011; nenhuma alteração de runtime |
| Base auditada | `main@df2dcca20d697cb77bed790d924fe940bd25aaf0` |
| Base arquitetural | `5b286223175ae249bb292a57222a2835762d25ba` |
| Data de referência | 2026-08-25 (`America/Sao_Paulo`) |
| Resultado | **Go para BPv7 como baseline v2; no-go para congelar agora o perfil de identidade/BPSec** |

## Pergunta respondida

O overhead de BPv7 torna o formato impraticável no caminho BLE atual, em comparação com o `MeshEnvelopeCodec` v1 realmente implementado?

O experimento separa três perguntas que não podem ser misturadas:

1. custo do objeto v1 plaintext atual;
2. custo de um perfil BPv7 mínimo com o mesmo significado de entrega;
3. custo de um candidato BPv7+BPSec com confidencialidade e integridade simétricas.

O terceiro perfil **ainda não satisfaz sozinho** a identidade assimétrica self-certifying do OpenMesh. Essa propriedade é deliberadamente deixada para ADR-011, em vez de esconder bytes de uma construção criptográfica não decidida dentro do benchmark.

## Método reproduzível

- Payload determinístico: `byte[i] = ((i * 73) + 41) mod 251`.
- Tamanhos: 32 B, 64 B, 256 B, 1 KiB e 10 KiB.
- Mesmos IDs lógicos, `NORMAL`, TTL de 72 horas e limite de 12 hops.
- `envelope-v1`: reprodução byte a byte do codec atual, incluindo Base64.
- `bpv7-openmesh-minimal`: deterministic CBOR, primary block com CRC-16/X-25, ADU tipado, Hop Count e policy experimental mensurável.
- `bpv7-openmesh-bpsec`: BIB-HMAC-SHA-256 no primary/policy e BCB-AES-256-GCM no payload, ambos com escopo 7.
- Sem BP fragmentation e sem status reports solicitados.
- BLE atual: ATT MTU 23, 20 bytes de valor ATT e header OpenMesh de 13 bytes, portanto **7 bytes de objeto por frame**.
- MTU 247: sensibilidade apenas; 231 bytes de objeto por frame. O cliente atual não negocia esse MTU.

Reprodução e checagem:

```bash
python3 -m pip install -r requirements.txt
python3 -m unittest -v test_conformance.py
python3 benchmark.py --check
python3 benchmark.py
```

`--check` compara os resultados e fixtures versionados byte a byte. A suíte contém 13 testes, inclusive vetores dos exemplos 3 e 4 do RFC 9173, CRC X-25, deterministic CBOR, separação das chaves de teste e modelo de tamanho do E2E v1.

## Resultados primários

| Payload | Perfil | Bytes wire | Overhead | Frames MTU 23 | Frames MTU 247 | Δ vs v1 |
|---:|---|---:|---:|---:|---:|---:|
| 32 B | Envelope v1 | 235 | 203 | 34 | 2 | 0,0% |
| 32 B | BPv7 mínimo | 241 | 209 | 35 | 2 | +2,553% |
| 32 B | BPv7+BPSec | 510 | 478 | 73 | 3 | +117,021% |
| 64 B | Envelope v1 | 279 | 215 | 40 | 2 | 0,0% |
| 64 B | BPv7 mínimo | 273 | 209 | 39 | 2 | −2,151% |
| 64 B | BPv7+BPSec | 542 | 478 | 78 | 3 | +94,265% |
| 256 B | Envelope v1 | 535 | 279 | 77 | 3 | 0,0% |
| 256 B | BPv7 mínimo | 467 | 211 | 67 | 3 | −12,710% |
| 256 B | BPv7+BPSec | 736 | 480 | 106 | 4 | +37,570% |
| 1 KiB | Envelope v1 | 1.559 | 535 | 223 | 7 | 0,0% |
| 1 KiB | BPv7 mínimo | 1.235 | 211 | 177 | 6 | −20,783% |
| 1 KiB | BPv7+BPSec | 1.504 | 480 | 215 | 7 | −3,528% |
| 10 KiB | Envelope v1 | 13.847 | 3.607 | 1.979 | 60 | 0,0% |
| 10 KiB | BPv7 mínimo | 10.451 | 211 | 1.493 | 46 | −24,525% |
| 10 KiB | BPv7+BPSec | 10.720 | 480 | 1.532 | 47 | −22,583% |

### Leitura correta

- BPv7 mínimo está praticamente empatado em 32 B, já é menor em 64 B e melhora progressivamente porque transporta bytes brutos em vez de Base64.
- O candidato BPSec tem custo fixo aproximado de 478–480 B. Isso é severo para mensagens pequenas, mas não autoriza comparar sua segurança com o envelope v1 plaintext como se fossem equivalentes.
- A explosão atual de frames vem principalmente do orçamento de **7 bytes úteis por frame**, não de BPv7 isoladamente. Para 32 B, o perfil seguro cai de 73 frames no caminho atual para 3 frames na sensibilidade MTU 247.
- Context compression não foi usada. Inventar uma codificação BLE especial antes de validar o perfil canônico criaria dois wires e risco de divergência.

## Sensibilidade contra o E2E v1 atual

Para uma comparação mais justa, o JSON modela o tamanho da construção existente `SecureMeshMessage` sem fabricar um vetor criptográfico. O modelo usa os codecs atuais e hipóteses explícitas comuns: chave P-256 X.509 de 91 B (124 caracteres Base64), nonce de 12 B, tag GCM de 16 B e assinatura ECDSA DER de 70–72 B. Nessa faixa a assinatura ocupa 96 caracteres Base64. Providers válidos podem codificar chaves ou assinaturas em outros tamanhos, portanto isto é sensibilidade, não medida universal exata.

| Payload | E2E v1 modelado | BPv7+BPSec | Diferença BPSec | Frames atuais: v1 / BPSec |
|---:|---:|---:|---:|---:|
| 32 B | 602 | 510 | −92 (−15,282%) | 86 / 73 |
| 64 B | 642 | 542 | −100 (−15,576%) | 92 / 78 |
| 256 B | 898 | 736 | −162 (−18,040%) | 129 / 106 |
| 1 KiB | 1.922 | 1.504 | −418 (−21,748%) | 275 / 215 |
| 10 KiB | 14.210 | 10.720 | −3.490 (−24,560%) | 2.030 / 1.532 |

Isto elimina a conclusão enganosa de que “segurança BPv7 custa sempre mais que a segurança atual”. O candidato medido é menor em todos os cinco tamanhos. Ainda assim, não é security-equivalent completo: HMAC simétrico não substitui prova de origem assimétrica, rotação nem distribuição da chave de conteúdo.

## Conformidade e limites

Confirmado no harness:

- estrutura BPv7 com primary/canonical blocks e deterministic CBOR;
- cálculo CRC-16 conforme RFC 9171 e errata verificado 8043;
- Abstract Security Block como CBOR Sequence, não como array externo;
- IPPT, AAD, ciphertext, tag e HMAC reproduzindo fixtures normativas do RFC 9173;
- um único target por BCB, evitando reutilização de IV entre targets;
- chaves públicas de fixture separadas por propósito;
- resultados determinísticos e hashes SHA-256 por objeto.

Ainda não confirmado:

- interoperabilidade com uma implementação BPv7 externa;
- aceitação do block type OpenMesh por registry ou ecossistema;
- perfil assimétrico self-certifying e distribuição/encapsulamento de chave;
- custo de CPU, airtime, retransmissões, wakeups e energia em aparelhos reais;
- comportamento com MTU negociado, batching, resume ou perda de frames;
- fragmentação BP segura. Errata mantidos do RFC 9171 mostram problemas de composição com extension/security blocks; o perfil inicial proíbe fragmentação BP e delega segmentação ao CLA.

## Decisão que os dados sustentam

**Go:** adotar BPv7 como formato canônico de objetos v2 e manter a inteligência OpenMesh acima dele. O custo mínimo não é proibitivo e o ganho sobre Base64 cresce com o payload.

**No-go:** congelar como produção o block type 192, os EIDs de fixture, as chaves simétricas, o IV determinístico ou o perfil de identidade. São instrumentos mensuráveis, não decisões de registry ou criptografia.

Antes de qualquer writer v2 de runtime, permanecem gates obrigatórios:

1. round-trip e rejeição de entradas inválidas em um parser endurecido;
2. interoperabilidade externa com pelo menos uma implementação BPv7/BPSec;
3. ADR-011 promovida de `Experimental` após avaliação criptográfica e Android;
4. definição dos extension/admin record types e estratégia de registro;
5. benchmark em dois aparelhos com MTU, perda, resume, energia e limites maliciosos.

## Artefatos auditáveis

- `benchmark.py`: gerador e encoder de referência;
- `test_conformance.py`: suíte de conformance;
- `benchmark.json`: parâmetros, decomposição, hashes e sensibilidade completa;
- `benchmark.csv`: resultados primários para análise;
- `openmesh-32-byte-vectors.json`: bytes completos dos três perfis;
- `rfc9173-vectors.json`: trechos normativos mínimos usados pelos testes.

## Fontes normativas

- [RFC 9171 — Bundle Protocol Version 7](https://www.rfc-editor.org/rfc/rfc9171.html)
- [Errata do RFC 9171](https://www.rfc-editor.org/errata/rfc9171)
- [RFC 9172 — Bundle Protocol Security](https://www.rfc-editor.org/rfc/rfc9172.html)
- [RFC 9173 — Default Security Contexts](https://www.rfc-editor.org/rfc/rfc9173.html)
- [RFC 9713 — Administrative Record Types Registry](https://www.rfc-editor.org/info/rfc9713/)
- [RFC 9758 — Updates to the ipn URI Scheme](https://www.rfc-editor.org/info/rfc9758/)
