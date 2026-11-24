"""Pydantic schemas for the Generalized Business-Aware AI Requirement Intelligence System."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

FactField = Literal[
    'objective',
    'subject',
    'scope',
    'directions',
    'concerns',
    'outputs',
    'constraints',
]

FACT_FIELDS: tuple[FactField, ...] = (
    'objective',
    'subject',
    'scope',
    'directions',
    'concerns',
    'outputs',
    'constraints',
)

ConfirmationStatus = Literal[
    'UNKNOWN',
    'INFERRED',
    'PROPOSED',
    'CONFIRMED',
    'INVALID',
    'NOT_APPLICABLE',
]

VerificationStatus = Literal[
    'VERIFIED',
    'PROPOSED',
    'CONFLICTING',
    'OUTDATED',
    'NEEDS_HUMAN_REVIEW',
]

ReadinessState = Literal[
    'INSUFFICIENT_INFORMATION',
    'MATERIAL_CLARIFICATION_REQUIRED',
    'SUFFICIENT_FOR_DRAFT',
    'READY_FOR_CUSTOMER_REVIEW',
    'NEEDS_OPERATIONAL_REVIEW',
]


class Evidence(BaseModel):
    model_config = ConfigDict(extra='forbid')
    field: FactField
    value: str = Field(min_length=1, max_length=1000)
    statement: int = Field(ge=0)
    quote: str = Field(min_length=1, max_length=1000)


class Extraction(BaseModel):
    """Flat, local-LLM-friendly extraction schema that also supports legacy `facts` list."""
    model_config = ConfigDict(extra='forbid')
    objective: str = Field(default='', max_length=500)
    subject: str = Field(default='', max_length=500)
    scope: str = Field(default='', max_length=500)
    directions: str = Field(default='', max_length=500)
    concerns: str = Field(default='', max_length=500)
    outputs: str = Field(default='', max_length=500)
    constraints: str = Field(default='', max_length=500)
    facts: list[Evidence] = Field(default_factory=list, max_length=35)
    contradictions: list[str] = Field(default_factory=list, max_length=5)
    unsupported: list[str] = Field(default_factory=list, max_length=5)
    missingCritical: list[str] = Field(default_factory=list, max_length=7)
    nextQuestion: str = Field(default='', max_length=500)
    questionField: str = Field(default='', max_length=80)
    quickReplies: list[str] = Field(default_factory=list, max_length=4)
    optionalImprovements: list[str] = Field(default_factory=list, max_length=7)
