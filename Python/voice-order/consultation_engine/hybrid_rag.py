"""Agentic Hybrid RAG Retriever (LightRAG / RAGFlow / LlamaIndex-inspired).

Combines:
1. Lexical BM25-style keyword matching
2. Character 3-gram semantic similarity (handles Vietnamese typos, ASR slips, regional variants)
3. Lightweight Knowledge Graph entity expansion
4. Metadata scope/category filtering
5. Cross-signal reranking
6. Live backend catalog overlay (volatile services, checklists, indicative prices ALWAYS come
   from the live authorized backend snapshot, never stale static text).
"""
from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass

from .drone_knowledge_base import (
    DRONE_KNOWLEDGE_DOCS,
    KNOWLEDGE_GRAPH_EDGES,
    KnowledgeDocument,
    KnowledgeScope,
)


def _norm(text: str) -> str:
    text = unicodedata.normalize('NFD', text.lower()).replace('đ', 'd')
    text = ''.join(ch for ch in text if unicodedata.category(ch) != 'Mn')
    return re.sub(r'[^a-z0-9\s]', ' ', text)


def _tokens(text: str) -> list[str]:
    return [tok for tok in _norm(text).split() if len(tok) >= 2]


def _char_ngrams(text: str, n: int = 3) -> set[str]:
    clean = re.sub(r'\s+', ' ', _norm(text)).strip()
    if len(clean) < n:
        return {clean} if clean else set()
    return {clean[i : i + n] for i in range(len(clean) - n + 1)}


def _jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    if not inter:
        return 0.0
    return inter / len(a | b)


@dataclass(frozen=True)
class RetrievedChunk:
    doc_id: str
    title: str
    category: str
    scope: str
    content: str
    score: float
    source: str  # 'KB_HYBRID' | 'KB_GRAPH' | 'LIVE_CATALOG'


class AgenticHybridRAG:
    def __init__(self, docs: tuple[KnowledgeDocument, ...] = DRONE_KNOWLEDGE_DOCS):
        self._docs = docs
        self._doc_tokens = {d.doc_id: set(_tokens(f"{d.title} {' '.join(d.keywords)} {d.content_vi}")) for d in docs}
        self._doc_ngrams = {d.doc_id: _char_ngrams(f"{d.title} {' '.join(d.keywords)}") for d in docs}
        self._df: dict[str, int] = {}
        for tok_set in self._doc_tokens.values():
            for tok in tok_set:
                self._df[tok] = self._df.get(tok, 0) + 1
        self._n_docs = max(1, len(docs))

    def _idf(self, token: str) -> float:
        df = self._df.get(token, 0)
        return math.log((self._n_docs + 1) / (df + 1)) + 1.0

    def retrieve(
        self,
        query: str,
        *,
        catalog: dict | None = None,
        lang: str = 'vi',
        scope_filter: KnowledgeScope | None = None,
        category_filter: str | None = None,
        top_k: int = 4,
    ) -> list[RetrievedChunk]:
        q_norm = _norm(query)
        q_tokens = _tokens(query)
        q_ngrams = _char_ngrams(query)

        # 1. Lexical + Semantic scoring
        scores: dict[str, float] = {}
        matched_entities: set[str] = set()
        for doc in self._docs:
            if scope_filter and doc.scope != scope_filter:
                continue
            if category_filter and doc.category != category_filter:
                continue

            kw_score = sum(2.5 for kw in doc.keywords if kw in q_norm)
            doc_toks = self._doc_tokens[doc.doc_id]
            bm25_like = sum(self._idf(t) for t in q_tokens if t in doc_toks)
            sem_score = _jaccard(q_ngrams, self._doc_ngrams[doc.doc_id]) * 6.0
            total = kw_score + bm25_like + sem_score
            if total > 0.4:
                scores[doc.doc_id] = total
                if kw_score >= 2.5 or bm25_like >= 2.0:
                    matched_entities.update(doc.entities)

        # 2. Knowledge Graph 1-hop entity expansion
        expanded_entities: set[str] = set()
        for entity in matched_entities:
            expanded_entities.update(KNOWLEDGE_GRAPH_EDGES.get(entity, ()))
        graph_boosted: set[str] = set()
        for doc in self._docs:
            if scope_filter and doc.scope != scope_filter:
                continue
            if any(ent in expanded_entities for ent in doc.entities):
                prev = scores.get(doc.doc_id, 0.0)
                if prev == 0.0:
                    graph_boosted.add(doc.doc_id)
                scores[doc.doc_id] = prev + 1.15

        # 3. Rerank KB documents
        doc_map = {d.doc_id: d for d in self._docs}
        ranked_ids = sorted(scores.keys(), key=lambda did: scores[did], reverse=True)
        chunks: list[RetrievedChunk] = []
        for did in ranked_ids[:top_k]:
            doc = doc_map[did]
            chunks.append(
                RetrievedChunk(
                    doc_id=doc.doc_id,
                    title=doc.title,
                    category=doc.category,
                    scope=doc.scope,
                    content=doc.content_vi if lang == 'vi' else doc.content_en,
                    score=round(scores[did], 3),
                    source='KB_GRAPH' if did in graph_boosted else 'KB_HYBRID',
                )
            )

        # 4. Live backend catalog overlay (source of truth for services, checklists, indicative prices)
        if catalog and isinstance(catalog.get('services'), list):
            live_lines: list[str] = []
            for svc in catalog['services']:
                name = svc.get('name', '').strip()
                if not name:
                    continue
                price = svc.get('indicativeStandardPrice') or svc.get('defaultPrice')
                price_txt = (
                    f"{int(price):,}".replace(',', '.') + ' VNĐ (giá tham khảo tạm tính)'
                    if isinstance(price, (int, float)) and price > 0
                    else 'chờ quản lý báo giá'
                )
                desc = (svc.get('description') or '').strip()
                live_lines.append(f"- {name}: {desc or 'Dịch vụ giám sát drone'} | {price_txt}")
            if live_lines:
                chunks.insert(
                    0,
                    RetrievedChunk(
                        doc_id='live_catalog_services',
                        title='Danh mục dịch vụ sống từ hệ thống',
                        category='live_catalog',
                        scope='PLATFORM_SERVICE',
                        content='Dịch vụ đang hoạt động hiện tại:\n' + '\n'.join(live_lines),
                        score=99.0,
                        source='LIVE_CATALOG',
                    ),
                )

        return chunks[: top_k + (1 if catalog else 0)]
