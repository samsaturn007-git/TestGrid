import type { components } from '@testgrid/contracts';

// Every type here comes from the generated contract (packages/contracts), never a copy of it.
type Schemas = components['schemas'];

export type Problem = Schemas['Problem'];
export type Project = Schemas['Project'];
export type PageProject = Schemas['PageProject'];
export type RunSummary = Schemas['RunSummary'];
export type RunTarget = Schemas['RunTarget'];
export type AttemptSummary = Schemas['AttemptSummary'];
export type CaseResult = Schemas['CaseResult'];
export type WorkerView = Schemas['WorkerView'];

export type RunStatus = Schemas['RunSummary']['status'];
export type TargetStatus = Schemas['RunTarget']['status'];
export type AttemptStatus = Schemas['AttemptSummary']['status'];
export type CaseStatus = Schemas['CaseResult']['status'];
export type WorkerState = Schemas['WorkerView']['state'];
export type Engine = Schemas['RunTarget']['engine'];

export type Capabilities = Schemas['Capabilities'];
export type Suite = Schemas['Suite'];
export type PageSuite = Schemas['PageSuite'];
export type SuiteDetail = Schemas['SuiteDetail'];
export type TestCase = Schemas['TestCase'];
export type CreateRunRequest = Schemas['CreateRunRequest'];
export type RunResponse = Schemas['RunResponse'];
export type FieldError = Schemas['FieldError'];
export type FixtureMode = Schemas['Fixture']['mode'];
export type PageRunSummary = Schemas['PageRunSummary'];
export type RunResults = Schemas['RunResults'];
export type RunResultsTarget = Schemas['RunResultsTarget'];
export type AttemptResults = Schemas['AttemptResults'];
export type RerunRequest = Schemas['RerunRequest'];
export type RerunScope = Schemas['RerunRequest']['scope'];
export type WorkersResponse = Schemas['WorkersResponse'];
export type RunCounts = Schemas['RunCounts'];

// PRD-06
export type ArtifactView = Schemas['ArtifactView'];
export type ArtifactKind = Schemas['ArtifactView']['kind'];
export type ArtifactState = Schemas['ArtifactView']['state'];
export type ArtifactLink = Schemas['Link'];
export type RunTimeline = Schemas['RunTimeline'];
export type TimelineTarget = Schemas['TimelineTarget'];
export type TimelineAttempt = Schemas['TimelineAttempt'];
export type HeartbeatGap = Schemas['HeartbeatGap'];
export type TimelineEvent = Schemas['TimelineEvent'];

// PRD-07
export type CaseHistoryView = Schemas['CaseHistoryView'];
export type EngineHistory = Schemas['CaseEngineHistory'];
export type CaseHealth = Schemas['CaseHealth'];
export type CaseHealthLabel = Schemas['CaseHealth']['label'];
export type CaseHistoryResult = Schemas['CaseHistoryResult'];
export type RunComparisonView = Schemas['RunComparisonView'];
export type ComparisonRow = Schemas['ComparisonRow'];
export type ComparisonRun = Schemas['ComparisonRun'];
export type ComparisonCategory = Schemas['ComparisonRow']['category'];
