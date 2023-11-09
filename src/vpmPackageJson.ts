export type packageJson = {
  name: string,
  author: {name: string, email:string},
  displayName: string,
  version: string,
  url: string,
  zipSHA256?: string,
};

export type packageList = {
  [packageId: string]: {
    versions: {
      [version: string]: packageJson
    },
  }
};

export type repoJson = {
  id: string,
  name: string,
  url: string,
  author: string,
  packages: packageList,
};