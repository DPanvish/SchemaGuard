CREATE TABLE IF NOT EXISTS projects (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    dialect VARCHAR(64) NOT NULL,
    repository_url VARCHAR(512),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS analysis_runs (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES projects(id),
    base_commit VARCHAR(128) NOT NULL,
    head_commit VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    prioritization_score INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    tool_version VARCHAR(64) NOT NULL,
    markdown_report TEXT
);

CREATE TABLE IF NOT EXISTS schema_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES projects(id),
    commit_sha VARCHAR(128) NOT NULL,
    snapshot_hash VARCHAR(128) NOT NULL,
    schema_model_version VARCHAR(32) NOT NULL,
    schema_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS migration_artifacts (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES analysis_runs(id),
    file_path VARCHAR(512) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    parser_status VARCHAR(64) NOT NULL,
    source_metadata_json TEXT
);

CREATE TABLE IF NOT EXISTS findings (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES analysis_runs(id),
    rule_id VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    object_name VARCHAR(255) NOT NULL,
    source_location_json TEXT,
    evidence_json TEXT,
    remediation TEXT
);

CREATE TABLE IF NOT EXISTS service_contracts (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES projects(id),
    service_name VARCHAR(128) NOT NULL,
    contract_version INT NOT NULL,
    contract_yaml TEXT NOT NULL,
    checksum VARCHAR(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS policy_versions (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES projects(id),
    version INT NOT NULL,
    policy_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS sandbox_results (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES analysis_runs(id),
    status VARCHAR(64) NOT NULL,
    duration_ms BIGINT NOT NULL,
    postgres_version VARCHAR(64),
    result_json TEXT
);

-- Section 7.2 Suggested Indexes
CREATE INDEX IF NOT EXISTS idx_runs_project_started ON analysis_runs(project_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_findings_run_severity ON findings(run_id, severity);
CREATE INDEX IF NOT EXISTS idx_findings_run_rule ON findings(run_id, rule_id);
CREATE INDEX IF NOT EXISTS idx_snapshots_project_commit ON schema_snapshots(project_id, commit_sha);
CREATE INDEX IF NOT EXISTS idx_snapshots_project_hash ON schema_snapshots(project_id, snapshot_hash);
CREATE INDEX IF NOT EXISTS idx_artifacts_run_file ON migration_artifacts(run_id, file_path);
CREATE INDEX IF NOT EXISTS idx_contracts_project_service ON service_contracts(project_id, service_name, contract_version);