export type unrecoverableError = "badPath" | "io";
export type recoverableError = "packageMismatch" | "versionMismatch";
export type errorType =  unrecoverableError | recoverableError;

export type baseErrorHandlingAction = "critical" | "error";
export type unrecoverableErrorAction = baseErrorHandlingAction | "warn" | "ignore";
export type errorRecoveryAction = baseErrorHandlingAction | "warn_replace" | "warn_keep" | "ignore_replace" | "ignore_keep";

export type errorHandlingSpec = Record<unrecoverableError, unrecoverableErrorAction> & Record<recoverableError, errorRecoveryAction>;

export type packageSources = {
  [packageId: string]: {
    path: string,
    tagPattern: string,
    version: string,
    onError?: Partial<errorHandlingSpec>,
  }
};

export type sourceRepository = {
  cloneURL: string;
  onError?: Partial<errorHandlingSpec>,
} & ({
  packages: packageSources;
} | {
  tagPattern: string,
  package: string,
  version: string,
  path: string,
});

export type buildConfig = {
  baseURL: string,
  buildFolder: string,
  onError?: Partial<errorHandlingSpec>,
};

export type vpmRepositoryConfig = buildConfig & {
  author: string,
  name: string,
  id: string,
  sources: sourceRepository[],
};
