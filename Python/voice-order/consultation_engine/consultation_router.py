"""LangGraph-inspired Consultation Mode Router & Implicit Needs Analyzer.

Dynamically switches between 7 modes based on the latest user intention:
- CONSULTATION
- INFORMATION
- COMPARISON
- RECOMMENDATION
- REQUEST_CREATION
- EDITING
- CUSTOMER_SUPPORT

Crucial safety rule: Pure consultation, information, comparison, recommendation,
or hypothetical questions NEVER authorize mutating the order draft.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Literal

ConsultationMode = Literal[
    'CONSULTATION',
    'INFORMATION',
    'COMPARISON',
    'RECOMMENDATION',
    'REQUEST_CREATION',
    'EDITING',
    'CUSTOMER_SUPPORT',
]


def _norm(text: str) -> str:
    text = unicodedata.normalize('NFD', text.lower()).replace('đ', 'd')
    text = ''.join(ch for ch in text if unicodedata.category(ch) != 'Mn')
    return re.sub(r'[^a-z0-9\s]', ' ', text)


@dataclass(frozen=True)
class RouteDecision:
    mode: ConsultationMode
    allows_draft_mutation: bool
    implicit_goal: str | None
    recommended_service_keywords: tuple[str, ...]
    budget_vnd: int | None
    is_interruption: bool
    reason: str


class ConsultationRouter:
    def route(self, message: str, *, has_active_draft: bool = False) -> RouteDecision:
        text = _norm(message)
        is_interruption = bool(re.search(r'\b(khoan|doi chut|dung lai|tu tu|a khoan)\b', text))

        budget_vnd: int | None = None
        m_budget = re.search(r'(\d+(?:[.,]\d+)?)\s*(trieu|tr)\b', text)
        if m_budget:
            budget_vnd = int(float(m_budget.group(1).replace(',', '.')) * 1_000_000)

        # 1. Comparison mode ("Dịch vụ công trình khác gì nhà xưởng?") -> NEVER mutates draft
        if re.search(r'\b(khac gi|so sanh|khac nhau|nen chon .* hay)\b', text) and re.search(
            r'\b(dich vu|goi|cong trinh|nha xuong|rung|khu vuc|mai)\b', text
        ):
            return RouteDecision(
                mode='COMPARISON',
                allows_draft_mutation=False,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer is comparing services; explain differences without mutating draft.',
            )

        # 2. Customer support / post-flight / payment / refund / account
        if re.search(
            r'\b(vnpay|payos|dat coc|hoa don|hoan tien|huy don|nghiem thu|chinh sua|khieu nai|tai khoan|dang nhap|dang ky|hotline|bao mat|ho tro truc tiep)\b',
            text,
        ) and not re.search(r'\b(bao hiem|10 ty|vo dieu kien|chi phi|bao nhieu tien)\b', text):
            return RouteDecision(
                mode='CUSTOMER_SUPPORT',
                allows_draft_mutation=False,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer support / payment / post-flight query.',
            )

        # 3. Pricing / policy / technical information & unsupported-claim questions -> NEVER mutates draft
        if re.search(
            r'\b(gia bao nhieu|bao nhieu tien|hoi gia|chi phi|re hon|tang gia|bao gia|quy trinh|hoat dong nhu the nao|bao lau|thoi tiet|mua|gio|giay phep|an toan|bao tri|camera nhiet|flir|bao hiem|cam ket|dam bao|giam gia|chiet khau|bay cao|khach hang khac|tokyo|new york|khac nhau the nao)\b',
            text,
        ) and not re.search(r'\b(chon dich vu|doi sang|dat lich ngay|cap nhat|nghi bi dot|mai nha xuong)\b', text):
            return RouteDecision(
                mode='INFORMATION',
                allows_draft_mutation=False,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer asked an informational/pricing/policy question; pause workflow and answer first.',
            )

        # 4. Implicit customer needs (consultation / recommendation without forcing draft mutation)
        if re.search(r'\b(xay nha|khong co thoi gian toi xem|khong co thoi gian ra cong truong|dang xay)\b', text):
            return RouteDecision(
                mode='RECOMMENDATION',
                allows_draft_mutation=False,
                implicit_goal='Theo dõi tiến độ xây nhà từ xa',
                recommended_service_keywords=('cong trinh', 'xay dung'),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Implicit construction progress monitoring need.',
            )
        if re.search(r'\b(mai .* dot|nghi bi dot|tham dot|kiem tra mai)\b', text):
            return RouteDecision(
                mode='RECOMMENDATION',
                allows_draft_mutation=False,
                implicit_goal='Kiểm tra mái nhà xưởng nghi bị dột',
                recommended_service_keywords=('nha xuong', 'cong nghiep'),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Implicit industrial roof inspection need.',
            )
        if re.search(
            r'\b(khong biet|chua biet|chua ro|nen chon goi nao|chon cai nao|tu van giup|bat dau tu dau)\b',
            text,
        ) and budget_vnd is None:
            return RouteDecision(
                mode='CONSULTATION',
                allows_draft_mutation=False,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer is unsure which service to pick; ask 1 concise goal question.',
            )
        if budget_vnd is not None and not re.search(r'\b(chon dich vu|doi sang|chot goi|dat lich)\b', text):
            return RouteDecision(
                mode='RECOMMENDATION',
                allows_draft_mutation=False,
                implicit_goal=f'Tư vấn gói phù hợp ngân sách khoảng {budget_vnd:,} VNĐ',
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Budget-constrained consultation; rank real catalog options.',
            )

        # 5. Editing / Undo / Reselect / Navigation
        if has_active_draft and re.search(
            r'\b(chon lai|doi sang|doi lai|sua lai|hoan tac|undo|redo|quay lai|bo chon|khong phai|bo quay video|them quay video)\b',
            text,
        ):
            return RouteDecision(
                mode='EDITING',
                allows_draft_mutation=True,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer explicitly requested editing or reverting draft.',
            )

        # 6. Explicit request creation / selection / resume
        if re.search(r'\b(chon|lay goi|dat lich|tiep tuc|duoc roi|muon giam sat|can chup anh|quay video|lien he|so dien thoai)\b', text):
            return RouteDecision(
                mode='REQUEST_CREATION',
                allows_draft_mutation=True,
                implicit_goal=None,
                recommended_service_keywords=(),
                budget_vnd=budget_vnd,
                is_interruption=is_interruption,
                reason='Customer authorized draft creation or progression.',
            )

        return RouteDecision(
            mode='CONSULTATION',
            allows_draft_mutation=False,
            implicit_goal=None,
            recommended_service_keywords=(),
            budget_vnd=budget_vnd,
            is_interruption=is_interruption,
            reason='Default consultative conversation.',
        )
