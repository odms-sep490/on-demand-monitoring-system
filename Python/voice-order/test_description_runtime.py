"""Agent double for local browser integration only; never used by startup script."""
import os
if os.getenv('DESCRIPTION_TEST_DOUBLE') != '1':
    import unittest
    raise unittest.SkipTest('Browser fixture requires DESCRIPTION_TEST_DOUBLE=1')
import description_agent

def fixture_generate(request):
    if not request.answers and not request.skipQuestions and request.mode == 'GENERATE':
        return description_agent.Draft(status='COLLECTING_INFORMATION', extractedFacts={},
            missingFields=['attention'], followUpQuestions=[description_agent.Question(
                questionId='attention', questionText='Bạn muốn chú ý hạng mục nào?',
                questionType='TEXT', options=[], relatedField='attention', required=False)],
            orderDescription='',flightPurposeDraft='',warnings=[],sourceFieldPaths=['description'])
    description=request.context['description'].strip()
    answer=' '.join(request.answers.values()).strip()
    return description_agent.Draft(status='READY_FOR_PREVIEW',extractedFacts={},missingFields=[],followUpQuestions=[],
        orderDescription=description + (' Nội dung cần chú ý: '+answer if answer else ''),
        flightPurposeDraft='Thu thập tư liệu bằng drone phục vụ yêu cầu: '+description,
        warnings=[],sourceFieldPaths=['description'])

description_agent.generate=fixture_generate
app=description_agent.app
