"""Factual Answer Verification Gate for the production drone consultation chatbot.

Verifies generated answers against authoritative backend catalog + platform rules BEFORE
returning them to the customer. Never relies on LLM self-reported confidence scores.
Detects:
- Invented discounts or unauthorized price promises
- Unsupported guarantees (100% crack detection, unconditional 100% refund, guaranteed weather)
- Unsupported equipment claims (e.g., FLIR thermal camera when not in catalog)
- Invalid schedule promises (confirming next-day flights violating 7-day lead time)
- Unauthorized order submission / approval claims
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field


def _norm(text: str) -> str:
    text = unicodedata.normalize('NFD', text.lower()).replace('đ', 'd')
    text = ''.join(ch for ch in text if unicodedata.category(ch) != 'Mn')
    return re.sub(r'[^a-z0-9%\s]', ' ', text)


@dataclass(frozen=True)
class VerificationReport:
    passed: bool
    violations: tuple[str, ...] = field(default_factory=tuple)
    safe_correction: str | None = None


class AnswerVerifier:
    def verify(
        self,
        answer: str,
        *,
        catalog: dict | None = None,
        allow_draft_mutation: bool = True,
        proposed_changes: dict | None = None,
    ) -> VerificationReport:
        text = _norm(answer)
        violations: list[str] = []

        # 1. Invented discounts / unauthorized price modification
        if re.search(r'\b(da giam gia|giam gia \d+%|chiet khau \d+%|mien phi hoan toan)', text) and 'khong' not in text:
            violations.append('INVENTED_DISCOUNT')

        # 2. Unsupported 100% technical/refund guarantees
        if re.search(r'\b(dam bao|cam ket|chac chan)\b[^.]{0,45}\b100%', text) and not re.search(
            r'\b(khong the|khong|chua the)\s+(?:dam bao|cam ket|chac chan)\b', text
        ):
            violations.append('UNSUPPORTED_100_PERCENT_GUARANTEE')

        # 3. Claiming order was already created/submitted/approved inside draft chat
        if re.search(r'\b(da tao don|da gui don|da phe duyet don|don hang da duoc duyet)\b', text) and not re.search(
            r'\b(khong|chua)\s+(?:tao don|gui don|phe duyet)\b', text
        ):
            violations.append('FALSE_ORDER_SUBMISSION_CLAIM')

        # 4. Claiming confirmed tomorrow flight (violates 7-day lead-time rule)
        if re.search(r'\b(da xac nhan lich bay sang mai|da chot lich bay ngay mai|da dat lich bay ngay mai)\b', text):
            violations.append('SCHEDULE_LEAD_TIME_VIOLATION')

        # 5. Claiming thermal camera / FLIR when catalog has no thermal service
        catalog_text = _norm(str(catalog or ''))
        if re.search(r'\b(co san camera nhiet|trang bi flir|dung camera nhiet flir)\b', text) and 'flir' not in catalog_text and 'nhiet' not in catalog_text:
            violations.append('UNSUPPORTED_EQUIPMENT_CLAIM')

        # 6. Unauthorized draft mutation during pure consultation/comparison/information mode
        if not allow_draft_mutation and proposed_changes:
            non_empty = {k: v for k, v in proposed_changes.items() if v is not None and v != '' and v != []}
            if non_empty:
                violations.append('UNAUTHORIZED_DRAFT_MUTATION')

        if not violations:
            return VerificationReport(passed=True)

        return VerificationReport(
            passed=False,
            violations=tuple(violations),
            safe_correction=(
                'Xin lỗi anh/chị, thông tin vừa rồi chưa đủ căn cứ từ danh mục và quy định hiện hành của hệ thống. '
                'Tôi chỉ có thể tư vấn dựa trên dịch vụ đang có trong danh mục, giá tham khảo tạm tính và quy trình chuẩn (đặt lịch trước tối thiểu 7 ngày, quản lý xác nhận báo giá chính thức).'
            ),
        )
