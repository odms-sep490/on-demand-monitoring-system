"""Generalized Business-Aware AI Requirement Intelligence Engine.

Orchestrates:
1. Verified Business Knowledge Graph & Vietnamese Hybrid RAG (knowledge.py)
2. Grounded Semantic Requirement Extraction via local Qwen-Agent (extractor.py)
3. Adaptive Customer Interview Planner & Stopping Policy (planner.py)
4. Independent Description Quality Evaluator (evaluator.py)
5. Grounded Professional Mission Description Generator (generator.py)
"""
import hashlib
import json

from .evaluator import WEIGHTS, check_service_compatibility, evaluate_description
from .extractor import extract_with_qwen as extract, ground_extracted_facts, is_grounded_in_statement
from .generator import LABELS, generate_grounded_description
from .knowledge import retrieve_verified_knowledge as retrieve
from .models import Evidence, Extraction, FACT_FIELDS
from .planner import plan_next_step


def review_description(service, original, statements, action='review', agent_factory=None):
    if not service or not service.get('id'):
        raise ValueError('Chọn dịch vụ trước khi hoàn thiện mô tả.')
    if not original.strip() or len(original) > 2000 or len(statements) > 25:
        raise ValueError('Mô tả hoặc cuộc tư vấn vượt giới hạn.')
    if any(not isinstance(s, str) or len(s) > 2000 for s in statements):
        raise ValueError('Câu trả lời chưa hợp lệ.')

    # 1. Retrieve verified business knowledge for the selected live service
    sources = retrieve(service, ' '.join(statements))

    # 2. Extract semantic requirements with Qwen-Agent and ground strictly against customer statements
    extraction = extract(statements, sources, agent_factory)
    facts, rejected, uncertain_turns = ground_extracted_facts(extraction, statements, service['id'])

    # 3. Verify service compatibility & deliverable boundaries
    combined_stmts = ' '.join(statements)
    grounded_model_unsupported = [
        u.strip() for u in extraction.unsupported
        if u.strip() and is_grounded_in_statement(u.strip(), u.strip(), combined_stmts)
    ]
    unsupported = check_service_compatibility(service, facts, statements, grounded_model_unsupported)
    contradictions = [
        c.strip() for c in extraction.contradictions
        if c.strip() and c.strip().lower() not in FACT_FIELDS
    ]

    # 4. Adaptive interview planning & stopping policy
    plan = plan_next_step(
        service=service,
        facts=facts,
        extraction=extraction,
        uncertain_turns=uncertain_turns,
        rejected_facts=rejected,
        contradictions=contradictions,
        unsupported=unsupported,
        action=action,
    )

    # 5. Grounded description generation (only when requested or ready)
    draft, paragraphs = (
        generate_grounded_description(service, facts, plan['missingCritical'])
        if action == 'generate'
        else ('', [])
    )

    # 6. Independent description quality evaluation
    evaluation = evaluate_description(
        service=service,
        original_text=original,
        statements=statements,
        facts=facts,
        missing_critical=plan['missingCritical'],
        optional_improvements=plan['optionalImprovements'],
        contradictions=contradictions,
        unsupported_requests=unsupported,
        rejected_facts=rejected,
        generated_description=draft,
        source_references=sources,
        readiness_state=plan['readinessState'],
        next_question=plan['nextQuestion'],
    )

    return {
        'originalText': original,
        'statements': statements,
        'facts': facts,
        'unknownInformation': plan['missingCritical'],
        'optionalImprovements': plan['optionalImprovements'],
        'sourceReferences': sources,
        'readiness': plan['readiness'],
        'readinessState': plan['readinessState'],
        'interviewDepthLevel': plan['interviewDepthLevel'],
        'nextQuestionField': plan['nextQuestionField'],
        'nextQuestion': plan['nextQuestion'],
        'quickReplies': plan['quickReplies'],
        'generatedDescription': draft,
        'paragraphTrace': paragraphs if draft else [],
        'contradictions': contradictions,
        'unsupportedRequests': unsupported,
        'rejectedFacts': rejected,
        'dimensions': evaluation['dimensions'],
        'overallScore': evaluation['overallScore'],
        'score': evaluation['score'],
        'calibrationStatus': evaluation['calibrationStatus'],
        'confirmedStrengths': evaluation['confirmedStrengths'],
        'hardChecks': evaluation['hardChecks'],
        'evaluationEvidence': evaluation['evaluationEvidence'],
        'evidenceHash': hashlib.sha256(
            json.dumps(statements, ensure_ascii=False).encode()
        ).hexdigest(),
    }
