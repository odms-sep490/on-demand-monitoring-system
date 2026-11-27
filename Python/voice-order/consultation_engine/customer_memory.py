"""Session-level Customer Memory (Mem0-inspired) with strict cross-account isolation.

Retains customer goals, budget, preferred/rejected services, monitoring location,
requested deliverables, previous questions, unresolved concerns, and decisions.
Never shares memory across unrelated customer accounts.
"""
from __future__ import annotations

import re
import time
import unicodedata
from dataclasses import dataclass, field


def _norm(text: str) -> str:
    text = unicodedata.normalize('NFD', text.lower()).replace('đ', 'd')
    text = ''.join(ch for ch in text if unicodedata.category(ch) != 'Mn')
    return re.sub(r'[^a-z0-9\s.,]', ' ', text)


@dataclass
class CustomerSessionMemory:
    account_id: str
    session_id: str
    long_term_consent: bool = False
    goals: list[str] = field(default_factory=list)
    budget_vnd: int | None = None
    preferred_services: list[str] = field(default_factory=list)
    rejected_options: list[str] = field(default_factory=list)
    monitoring_location: str | None = None
    requested_deliverables: list[str] = field(default_factory=list)
    previous_questions: list[str] = field(default_factory=list)
    unresolved_concerns: list[str] = field(default_factory=list)
    previous_decisions: list[str] = field(default_factory=list)
    active_mode: str = 'CONSULTATION'
    updated_at: float = field(default_factory=time.time)

    def observe_message(self, message: str, catalog: dict | None = None) -> None:
        text = _norm(message)
        self.updated_at = time.time()

        # Extract budget ("3 triệu", "2.5 tr", "5000000")
        m_budget = re.search(r'(\d+(?:[.,]\d+)?)\s*(trieu|tr)\b', text)
        if m_budget:
            self.budget_vnd = int(float(m_budget.group(1).replace(',', '.')) * 1_000_000)

        # Extract implicit / explicit goals
        goal_signals = (
            ('xay nha', 'Theo dõi tiến độ xây nhà'),
            ('cong trinh', 'Giám sát tiến độ công trình'),
            ('mai nha xuong', 'Kiểm tra mái nhà xưởng'),
            ('bi dot', 'Kiểm tra dấu hiệu thấm dột mái'),
            ('nha xuong', 'Kiểm tra nhà xưởng'),
            ('chay rung', 'Giám sát nguy cơ cháy rừng'),
            ('rung', 'Giám sát rừng'),
            ('cay trong', 'Theo dõi cây trồng'),
        )
        for key, label in goal_signals:
            if key in text and label not in self.goals:
                self.goals.append(label)

        # Extract deliverables
        if ('chup anh' in text or 'chup hinh' in text) and 'PHOTO' not in self.requested_deliverables:
            self.requested_deliverables.append('PHOTO')
        if ('quay video' in text or 'quay phim' in text) and 'VIDEO' not in self.requested_deliverables:
            self.requested_deliverables.append('VIDEO')
        if 'bao cao' in text and 'PDF_REPORT' not in self.requested_deliverables:
            self.requested_deliverables.append('PDF_REPORT')

        # Track rejected services ("không phải nhà xưởng", "không chọn rừng")
        if catalog and isinstance(catalog.get('services'), list):
            for svc in catalog['services']:
                sname = _norm(svc.get('name', ''))
                if not sname:
                    continue
                if re.search(rf'(khong phai|khong chon|khong lay|bo)\s+[^,.]*{re.escape(sname.split()[-1])}', text):
                    if svc['name'] not in self.rejected_options:
                        self.rejected_options.append(svc['name'])

        # Track questions & concerns
        if '?' in message or re.search(r'\b(gia bao nhieu|khac gi|co duoc khong|mua thi sao|bao mat)\b', text):
            short_q = message.strip()[:160]
            if short_q and short_q not in self.previous_questions:
                self.previous_questions = (self.previous_questions + [short_q])[-8:]
        if re.search(r'\b(lo ngai|so|dat qua|mua|gio|bao mat|hoan tien)\b', text):
            concern = message.strip()[:120]
            if concern and concern not in self.unresolved_concerns:
                self.unresolved_concerns = (self.unresolved_concerns + [concern])[-5:]

    def to_compact_dict(self) -> dict:
        return {
            'goals': self.goals[-4:],
            'budgetVnd': self.budget_vnd,
            'preferredServices': self.preferred_services[-3:],
            'rejectedOptions': self.rejected_options[-3:],
            'monitoringLocation': self.monitoring_location,
            'requestedDeliverables': self.requested_deliverables,
            'previousQuestions': self.previous_questions[-3:],
            'unresolvedConcerns': self.unresolved_concerns[-3:],
            'previousDecisions': self.previous_decisions[-4:],
            'activeMode': self.active_mode,
        }


class CustomerMemoryManager:
    """Strictly isolated per-(account_id, session_id) session memory registry."""

    def __init__(self, max_sessions: int = 500):
        self._store: dict[tuple[str, str], CustomerSessionMemory] = {}
        self._max_sessions = max_sessions

    def get_or_create(self, account_id: str, session_id: str) -> CustomerSessionMemory:
        key = (account_id or 'anonymous', session_id or 'default')
        if key not in self._store:
            if len(self._store) >= self._max_sessions:
                oldest = min(self._store.items(), key=lambda kv: kv[1].updated_at)[0]
                del self._store[oldest]
            self._store[key] = CustomerSessionMemory(account_id=key[0], session_id=key[1])
        return self._store[key]

    def clear_session(self, account_id: str, session_id: str) -> None:
        self._store.pop((account_id or 'anonymous', session_id or 'default'), None)
