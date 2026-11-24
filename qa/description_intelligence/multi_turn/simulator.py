"""Adaptive Multi-Turn Customer Simulator reacting contextually to real AI questions."""
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.engine import review_description


class SimulatedCustomer:
    """Contextual customer persona with known/unknown facts and correction capability."""

    def __init__(self, goal: str, known_facts: dict[str, str], initial_message: str, max_turns: int = 4):
        self.goal = goal
        self.known_facts = dict(known_facts)
        self.initial_message = initial_message
        self.max_turns = max_turns
        self.used_fields: set[str] = set()

    def reply(self, next_question: str, next_field: str | None) -> str:
        if next_field and next_field in self.known_facts and next_field not in self.used_fields:
            self.used_fields.add(next_field)
            return self.known_facts[next_field]
        q_lower = (next_question or '').lower()
        for field, answer in self.known_facts.items():
            if field in self.used_fields:
                continue
            if (field == 'subject' and ('đối tượng' in q_lower or 'khu vực nào' in q_lower)) or \
               (field == 'scope' and ('phạm vi' in q_lower or 'toàn bộ' in q_lower)) or \
               (field == 'concerns' and ('chú ý' in q_lower or 'bất thường' in q_lower)) or \
               (field == 'outputs' and ('kết quả' in q_lower or 'đầu ra' in q_lower)):
                self.used_fields.add(field)
                return answer
        return 'Tôi chưa rõ chi tiết này, nhờ đội vận hành tư vấn thêm.'


def run_adaptive_session(service: dict, customer: SimulatedCustomer, agent_factory=None) -> dict:
    statements = [customer.initial_message]
    transcript = []
    started = time.monotonic()
    last_result = None

    for turn in range(customer.max_turns):
        action = 'generate' if turn == customer.max_turns - 1 else 'review'
        res = review_description(service, customer.initial_message, list(statements), action=action, agent_factory=agent_factory)
        last_result = res
        transcript.append({
            'turn': turn,
            'statements': list(statements),
            'readinessState': res['readinessState'],
            'facts': {k: v['value'] for k, v in res['facts'].items()},
            'nextQuestion': res['nextQuestion'],
            'nextQuestionField': res['nextQuestionField'],
            'hardChecks': res['hardChecks'],
        })
        if res['readinessState'] in ('SUFFICIENT_FOR_DRAFT', 'READY_FOR_CUSTOMER_REVIEW') and not res['nextQuestion']:
            if action != 'generate':
                res = review_description(service, customer.initial_message, list(statements), action='generate', agent_factory=agent_factory)
                last_result = res
            break
        if res['nextQuestion']:
            statements.append(customer.reply(res['nextQuestion'], res['nextQuestionField']))
        else:
            break

    return {
        'serviceName': service['name'],
        'turns': len(transcript),
        'durationSeconds': round(time.monotonic() - started, 2),
        'finalReadinessState': last_result['readinessState'] if last_result else 'BLOCKED',
        'finalFacts': {k: v['value'] for k, v in (last_result or {}).get('facts', {}).items()},
        'generatedDescription': (last_result or {}).get('generatedDescription', ''),
        'hardChecks': (last_result or {}).get('hardChecks', {}),
        'transcript': transcript,
    }
