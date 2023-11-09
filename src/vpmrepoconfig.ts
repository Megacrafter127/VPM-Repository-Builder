export type packageSources = {
  [packageId: string]: {
    path: string,
    tagPattern: string,
    version: string,
  }
};

export type sourceRepository = {
  cloneURL: string;
  packages: packageSources;
};

export type buildConfig = {
  baseURL: string,
  buildFolder: string,
};

export type vpmRepositoryConfig = buildConfig & {
  author: string,
  name: string,
  id: string,
  sources: sourceRepository[],
};
