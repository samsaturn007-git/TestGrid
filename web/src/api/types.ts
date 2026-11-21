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
