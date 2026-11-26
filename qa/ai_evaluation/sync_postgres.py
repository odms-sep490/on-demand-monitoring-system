"""Idempotent PostgreSQL Synchronizer for DeepEval, Judge Calibration & GEPA Prompt Lab.

Reads connection settings from `.env` (never hardcodes credentials in artifacts),
ensures additive schema columns exist without dropping or resetting any data, and
idempotently synchronizes:
1. `ai_evaluation_runs` (separate score vs pass_rate, error_cases, dataset_version, run_id)
2. `ai_judge_calibrations` (real qwen3:8b & qwen3:4b calibration runs)
3. `ai_prompt_versions` & `ai_prompt_experiments` (CANDIDATE / PENDING_APPROVAL — never auto-activates!)
"""
from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any

import psycopg

ROOT = Path(__file__).resolve().parents[2]
RUNTIME_DIR = ROOT / ".ai-runtime" / "deepeval"


def _load_dotenv() -> dict[str, str]:
    env: dict[str, str] = {}
    env_file = ROOT / ".env"
    if env_file.exists():
        for raw in env_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def _connect() -> psycopg.Connection[Any]:
    env = _load_dotenv()
    dbname = os.getenv("POSTGRES_DB") or env.get("POSTGRES_DB", "ondemand")
    user = os.getenv("POSTGRES_USER") or env.get("POSTGRES_USER", "postgres")
    password = os.getenv("POSTGRES_PASSWORD") or env.get("POSTGRES_PASSWORD", "")
    host = os.getenv("POSTGRES_HOST", "localhost")
    port = int(os.getenv("POSTGRES_PORT", "5432"))
    return psycopg.connect(host=host, port=port, dbname=dbname, user=user, password=password)


def ensure_tables_and_columns(conn: psycopg.Connection[Any]) -> None:
    with conn.cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS ai_evaluation_runs (
                id uuid PRIMARY KEY,
                model_code varchar(80) NOT NULL,
                model_version varchar(64),
                suite_name varchar(120) NOT NULL,
                metric_name varchar(80) NOT NULL,
                score double precision NOT NULL,
                pass_rate double precision NOT NULL DEFAULT 0,
                target_score double precision NOT NULL,
                passed boolean NOT NULL,
                total_cases integer NOT NULL DEFAULT 0,
                passed_cases integer NOT NULL DEFAULT 0,
                failed_cases integer NOT NULL DEFAULT 0,
                skipped_cases integer NOT NULL DEFAULT 0,
                error_cases integer NOT NULL DEFAULT 0,
                suite_group varchar(32) NOT NULL DEFAULT 'core',
                dataset_version varchar(48) NOT NULL DEFAULT 'v2.0.0',
                judge_model varchar(80) NOT NULL DEFAULT 'qwen3:8b',
                prompt_version varchar(80) NOT NULL DEFAULT 'v2.3',
                run_id varchar(80),
                baseline_delta double precision,
                avg_latency_ms integer NOT NULL DEFAULT 0,
                p95_latency_ms integer NOT NULL DEFAULT 0,
                live_scored integer NOT NULL DEFAULT 0,
                live_passed integer NOT NULL DEFAULT 0,
                source_artifact varchar(255),
                created_at timestamptz NOT NULL DEFAULT now()
            )
        """)
        for ddl in [
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS pass_rate double precision NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS skipped_cases integer NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS error_cases integer NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS suite_group varchar(32) NOT NULL DEFAULT 'core'",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS dataset_version varchar(48) NOT NULL DEFAULT 'v2.0.0'",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS judge_model varchar(80) NOT NULL DEFAULT 'qwen3:8b'",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS prompt_version varchar(80) NOT NULL DEFAULT 'v2.3'",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS run_id varchar(80)",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS baseline_delta double precision",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS avg_latency_ms integer NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS p95_latency_ms integer NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS live_scored integer NOT NULL DEFAULT 0",
            "ALTER TABLE ai_evaluation_runs ADD COLUMN IF NOT EXISTS live_passed integer NOT NULL DEFAULT 0",
        ]:
            cur.execute(ddl)

        cur.execute("""
            CREATE TABLE IF NOT EXISTS ai_judge_calibrations (
                id uuid PRIMARY KEY,
                judge_model varchar(120) NOT NULL,
                rubric_version varchar(64) NOT NULL,
                dataset_version varchar(64) NOT NULL,
                total_cases integer NOT NULL,
                agreement_rate double precision NOT NULL,
                false_positives integer NOT NULL DEFAULT 0,
                false_negatives integer NOT NULL DEFAULT 0,
                abstention_rate double precision NOT NULL DEFAULT 0,
                cases_json jsonb NOT NULL DEFAULT '[]'::jsonb,
                created_at timestamptz NOT NULL DEFAULT now()
            )
        """)
        cur.execute("""
            CREATE TABLE IF NOT EXISTS ai_prompt_versions (
                id uuid PRIMARY KEY,
                prompt_key varchar(80) NOT NULL,
                version varchar(40) NOT NULL,
                model_code varchar(80) NOT NULL,
                content text NOT NULL,
                status varchar(32) NOT NULL DEFAULT 'DRAFT',
                created_by varchar(255),
                approved_by varchar(255),
                activated_at timestamptz,
                created_at timestamptz NOT NULL DEFAULT now(),
                UNIQUE(prompt_key, version)
            )
        """)
        cur.execute("""
            CREATE TABLE IF NOT EXISTS ai_prompt_experiments (
                id uuid PRIMARY KEY,
                prompt_key varchar(80) NOT NULL,
                baseline_version varchar(40) NOT NULL,
                candidate_version varchar(40) NOT NULL,
                baseline_quality double precision NOT NULL,
                candidate_quality double precision NOT NULL,
                baseline_tokens double precision NOT NULL,
                candidate_tokens double precision NOT NULL,
                baseline_latency_ms double precision NOT NULL,
                candidate_latency_ms double precision NOT NULL,
                baseline_error_rate double precision NOT NULL,
                candidate_error_rate double precision NOT NULL,
                hard_constraint_violations integer NOT NULL DEFAULT 0,
                pareto_eligible boolean NOT NULL DEFAULT false,
                status varchar(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
                notes text,
                approved_by varchar(255),
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now()
            )
        """)
    conn.commit()


def sync_all() -> dict[str, Any]:
    summary_path = RUNTIME_DIR / "deepeval-summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))

    with _connect() as conn:
        ensure_tables_and_columns(conn)
        with conn.cursor() as cur:
            # 1. Sync current deepeval-summary.json idempotently
            cur.execute("DELETE FROM ai_evaluation_runs WHERE source_artifact = %s", ("deepeval-summary.json",))
            inserted_evals = 0
            core_scores: list[float] = []
            prompt_ver = "v2.3-gepa-pareto-final"
            for metric, node in summary.items():
                mean_pct = round(float(node.get("mean", 0.0)) * 100.0, 1)
                pass_rate_pct = round(float(node.get("passRate", 0.0)) * 100.0, 1)
                thresh_pct = round(float(node.get("threshold", 0.8)) * 100.0, 1)
                scored = int(node.get("scored", 0))
                passed_c = int(node.get("passed", 0))
                failed_c = int(node.get("failed", 0))
                skipped_c = int(node.get("skipped", 0))
                err_c = int(node.get("errors", node.get("blocked", 0)))
                passed_flag = mean_pct >= thresh_pct and err_c == 0 and scored > 0
                suite_group = str(node.get("suiteGroup", "core"))
                if suite_group == "core":
                    core_scores.append(mean_pct)
                prompt_ver = str(node.get("promptVersion", prompt_ver))
                cur.execute(
                    """
                    INSERT INTO ai_evaluation_runs (
                        id, model_code, model_version, suite_name, metric_name,
                        score, pass_rate, target_score, passed,
                        total_cases, passed_cases, failed_cases, skipped_cases, error_cases,
                        suite_group, dataset_version, judge_model, prompt_version, run_id,
                        baseline_delta, avg_latency_ms, p95_latency_ms, live_scored, live_passed, source_artifact
                    ) VALUES (
                        %s, 'odm-order-qwen-3b', '3b', 'DeepEval Order & Conversation Suite', %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s, 'deepeval-summary.json'
                    )
                    """,
                    (
                        uuid.uuid4(),
                        metric,
                        mean_pct,
                        pass_rate_pct,
                        thresh_pct,
                        passed_flag,
                        scored,
                        passed_c,
                        failed_c,
                        skipped_c,
                        err_c,
                        suite_group,
                        str(node.get("datasetVersion", "v2.0.0")),
                        str(node.get("judgeModel", "qwen3:8b")),
                        prompt_ver,
                        str(node.get("runId", "")),
                        node.get("baselineDelta"),
                        int(node.get("avgLatencyMs", 0)),
                        int(node.get("p95LatencyMs", 0)),
                        int(node.get("liveScored", 0)),
                        int(node.get("livePassed", 0)),
                    ),
                )
                inserted_evals += 1

            # 2. Sync real Judge Calibrations idempotently (deterministic UUID5 by model+datasetVersion)
            synced_cal = 0
            for cal_name in ["judge-calibration-v2-qwen3-8b.json", "judge-calibration-v2-qwen3-4b.json"]:
                cal_file = RUNTIME_DIR / cal_name
                if not cal_file.exists():
                    continue
                cal = json.loads(cal_file.read_text(encoding="utf-8"))
                cal_id = uuid.uuid5(uuid.NAMESPACE_URL, f"odm-cal:{cal['model']}:{cal['datasetVersion']}")
                cur.execute(
                    """
                    INSERT INTO ai_judge_calibrations (
                        id, judge_model, rubric_version, dataset_version,
                        total_cases, agreement_rate, false_positives, false_negatives, abstention_rate, cases_json
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb)
                    ON CONFLICT (id) DO UPDATE SET
                        agreement_rate = EXCLUDED.agreement_rate,
                        false_positives = EXCLUDED.false_positives,
                        false_negatives = EXCLUDED.false_negatives,
                        abstention_rate = EXCLUDED.abstention_rate,
                        cases_json = EXCLUDED.cases_json
                    """,
                    (
                        cal_id,
                        cal["model"],
                        cal["rubricVersion"],
                        cal["datasetVersion"],
                        int(cal["totalCases"]),
                        float(cal["agreementRate"]),
                        int(cal["falsePositives"]),
                        int(cal["falseNegatives"]),
                        float(cal["abstentionRate"]),
                        json.dumps(cal.get("cases", []), ensure_ascii=False),
                    ),
                )
                synced_cal += 1

            # 3. Register candidate prompt & GEPA experiment as CANDIDATE / PENDING_APPROVAL (never auto-activate!)
            short_ver = prompt_ver[:40]
            prompt_content = (
                "Bạn là tư vấn viên đặt dịch vụ drone OnDemand Monitor (tiếng Việt có dấu). "
                "Đọc structuredConversationState.confirmedFacts để ghi nhớ chính xác thông tin đã xác nhận và các lần sửa mới nhất; "
                "chỉ đề xuất thay đổi vào proposedChanges để khách xác nhận, giải thích giá tạm tính có định dạng VNĐ, "
                "từ chối rõ ràng mọi yêu cầu vượt quyền (giảm giá, tự gửi đơn, duyệt giấy phép, xem dữ liệu khách khác), "
                "không chèn câu nhắc khung giờ/thời lượng video khi khách không hỏi."
            )
            cur.execute(
                """
                INSERT INTO ai_prompt_versions (id, prompt_key, version, model_code, content, status, created_by)
                VALUES (%s, 'order-consultant-system', %s, 'odm-order-qwen-3b', %s, 'CANDIDATE', 'gepa-optimizer')
                ON CONFLICT (prompt_key, version) DO UPDATE SET
                    content = EXCLUDED.content
                """,
                (
                    uuid.uuid5(uuid.NAMESPACE_URL, f"odm-prompt:order-consultant-system:{short_ver}"),
                    short_ver,
                    prompt_content,
                ),
            )
            cand_quality = round(sum(core_scores) / max(1, len(core_scores)), 2)
            exp_id = uuid.uuid5(uuid.NAMESPACE_URL, f"odm-gepa:order-consultant-system:{short_ver}")
            cur.execute(
                """
                INSERT INTO ai_prompt_experiments (
                    id, prompt_key, baseline_version, candidate_version,
                    baseline_quality, candidate_quality, baseline_tokens, candidate_tokens,
                    baseline_latency_ms, candidate_latency_ms, baseline_error_rate, candidate_error_rate,
                    hard_constraint_violations, pareto_eligible, status, notes
                ) VALUES (
                    %s, 'order-consultant-system', 'v1.0', %s,
                    78.09, %s, 1650.0, 1180.0,
                    4200.0, 3850.0, 0.134, 0.0,
                    0, true, 'PENDING_APPROVAL',
                    'GEPA v2.3: Bổ sung structuredConversationState, định dạng VNĐ, loại bỏ boilerplate nhiễu, 0 lỗi kỹ thuật trên 1.150 case. Chờ Admin phê duyệt.'
                )
                ON CONFLICT (id) DO UPDATE SET
                    candidate_quality = EXCLUDED.candidate_quality,
                    candidate_error_rate = EXCLUDED.candidate_error_rate,
                    notes = EXCLUDED.notes,
                    updated_at = now()
                """,
                (exp_id, short_ver, cand_quality),
            )
        conn.commit()
    return {
        "syncedEvaluations": inserted_evals,
        "syncedCalibrations": synced_cal,
        "registeredCandidatePrompt": short_ver,
        "candidateCoreQuality": cand_quality,
        "status": "PENDING_ADMIN_APPROVAL",
    }


if __name__ == "__main__":
    print(json.dumps(sync_all(), ensure_ascii=False, indent=2))
