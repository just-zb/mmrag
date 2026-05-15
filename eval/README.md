# Evaluation

Offline Ragas evaluator + curated QA dataset for the multimodal-RAG chat path. The Spring Boot backend writes one structured JSON line per chat query; this directory consumes those traces, joins them against a curated 30-pair QA dataset, computes the six metrics the thesis reports (two deterministic IR + four LLM-judged Ragas), and writes a long-form CSV that the thesis figure-renderer reads directly.

## Files

| Path | Purpose |
|---|---|
| `qa.jsonl` | The 30-pair curated QA dataset described in thesis §4.2: 12 text-only, 12 visually-grounded, 6 mixed. Each record carries a query, gold answer, and gold-context chunk identifiers. |
| `run_ragas.py` | The evaluator. Reads `logs/ragas-trace.jsonl` and `qa.jsonl`, joins on the query string, computes Recall@5, MRR, Faithfulness, Answer Relevance, Context Recall, Context Precision, and writes `results.csv`. |
| `requirements.txt` | Python deps. |
| `md5_map.json` | (You provide) maps the chat-handler's `fileMd5` strings to the human-readable filenames used in `qa.jsonl`. Optional; without it `qa.jsonl` must be edited to use the same MD5 strings the trace records. |

## Workflow

The thesis evaluation is a 3 × 4 full-factorial run: three F1 ingestion architectures × four F2 retrieval strategies. Each cell is one indexing + chat pass that produces one batch of trace lines.

### One-time setup

```bash
pip install -r eval/requirements.txt
export OPENAI_API_KEY=...     # Ragas judge LLM (gpt-4o-2024-08-06)
```

### For each F1 cell (ingestion architecture)

```bash
# pick one: TEXT_ONLY | UNIFIED | DESCRIPTION
export INGESTION_ARCHITECTURE=DESCRIPTION
mvn spring-boot:run                           # Spring Boot picks up the env var
# Reindex the corpus through the chat backend's upload endpoint so the
# image rows are produced under this architecture.
```

### For each F2 cell (retrieval strategy), within a given F1

```bash
# pick one: BM25_ONLY | DENSE_ONLY | WEIGHTED_HYBRID | HYBRID_PLUS_RERANK
export RETRIEVAL_MODE=HYBRID_PLUS_RERANK
export RAGAS_TRACER_ENABLED=true              # so the chat handler writes traces
mvn spring-boot:run

# Issue every query in qa.jsonl through the chat WebSocket. The
# RagasTracerInterceptor appends one JSON object to logs/ragas-trace.jsonl
# per query. Each trace line carries mode + params + the retrieved hits +
# the generated answer + a per-modality count breakdown.
```

### After all cells are collected

```bash
python eval/run_ragas.py \
    --trace ./logs/ragas-trace.jsonl \
    --qa    ./eval/qa.jsonl \
    --out   ./eval/results.csv \
    --judge gpt-4o-2024-08-06 \
    --md5-map ./eval/md5_map.json
```

The output `results.csv` is long-form (one row per QA pair × cell) with columns

```
qid, stratum, ingestion, mode, query,
recall_at_5, mrr,
faithfulness, answer_relevancy, context_recall, context_precision
```

The thesis figure-renderer (in the separate thesis repo, `figures/render_figs.py`) groups by `(ingestion, mode)`, takes per-cell means, and produces the F1 × F2 line plot, heatmap, and stratum table.

## Notes

- The `qa.jsonl` records use human-readable filenames (`arch_diagram.docx`, `deploy_guide.docx`, ...) for clarity. The chat-handler trace records `fileMd5` instead, so you must either (a) provide `md5_map.json` to the evaluator, or (b) edit `qa.jsonl` once your corpus is indexed and copy the real MD5s.
- `gpt-4o-2024-08-06` is intentionally a different model family from the Claude generator: this mitigates self-judge bias on the four Ragas metrics. The two Group I metrics (Recall@5, MRR) are deterministic and unaffected by the judge.
- The judge incurs cost: ~30 queries × 4 metrics × N cells × ~2k tokens of context each. A full 3 × 4 factorial is ~360 metric calls.
- Failure modes: if Ragas is not installed, `run_ragas.py` still runs and emits `nan` for the four Group II metrics so Group I can be inspected without the LLM dependency.
