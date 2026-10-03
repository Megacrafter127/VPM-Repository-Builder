import {mkdir, readFile, writeFile} from "node:fs/promises";
import {buildSource, BuildError} from "./packageCompiler.js";
import {errorHandlingSpec, vpmRepositoryConfig} from "./vpmrepoconfig.js";
import {packageList, repoJson} from './vpmPackageJson.js';

const baseErrorHandling: errorHandlingSpec = {
  badPath: "error",
  io: "critical",
  packageMismatch: "error",
  versionMismatch: "error",
};

const configJson = await readFile(process.argv[2] ?? "./vpmrepoconfig.json", {encoding: "utf-8"});

const repoConfig: vpmRepositoryConfig = JSON.parse(configJson);

const errorHandling = {
  ...baseErrorHandling,
  ...(repoConfig.onError ?? {}),
}

const buildFolder = repoConfig.buildFolder ?? "./dist";

const packageBuildFolder = `${buildFolder}/packages`;

await mkdir(packageBuildFolder, {recursive: true});

const packages = await Promise.all(repoConfig.sources.map(s => buildSource(
  errorHandling,
  s,
  `${repoConfig.baseURL}/packages`,
  packageBuildFolder
).catch(err => {
  if (err instanceof BuildError) {
    switch (errorHandling[err.type]) {
      case "error":
        return undefined;
      default:
        throw err;
    }
  } else throw err;
})));

const indexJson: repoJson = {
  id: repoConfig.id,
  name: repoConfig.name,
  author: repoConfig.author,
  url: `${repoConfig.baseURL}/index.json`,
  packages: packages.flat().reduce<packageList>((a, v) => {
    if (v) {
      const versions = (a[v.name] ??= {versions: {}})?.versions;
      versions[v.version] = v;
    }
    return a;
  }, {}),
};

await writeFile(`${buildFolder}/index.json`, JSON.stringify(indexJson, undefined, 2), {encoding: "utf8"});

const packageDescriptions: string[] = Object.entries(indexJson.packages)
  .sort(([a], [b]) => a.localeCompare(b))
  .flatMap(([pkg, {versions}]) => {
  return [
    `<hr>`,
    `<h2>${pkg}</h2>`,
    ...Object.entries(versions)
      .sort(([a], [b]) => {
        const rgx = /(\d+)\.(\d+)\.(\d+)/;
        const am = rgx.exec(a);
        const bm = rgx.exec(b);
        if(am && bm) {
          const [, ama, ami, ap] = am;
          const [, bma, bmi, bp] = bm;
          let diff = (+ama)-(+bma);
          if(diff !== 0) return diff;
          diff = (+ami)-(+bmi);
          if(diff !== 0) return diff;
          diff = (+ap)-(+bp);
          if(diff !== 0) return diff;
        } else if(am) {
          return 1;
        } else if(bm) {
          return -1;
        }
        return a.localeCompare(b);
      })
      .map(([v, pkg]) => `<a href="${pkg.url}">${v}</a>`)
  ];
})

const html = `
<!DOCTYPE html>
<html lang="en">
  <body>
    <h1>${indexJson.name}</h1>
    <a href='${indexJson.url}'>${indexJson.url}</a> <a href='vcc://vpm/addRepo?url=${encodeURIComponent(indexJson.url)}'><button>Add to VCC</button></a>
    ${packageDescriptions.join("\n    ")}
  </body>
</html>
`;

await writeFile(`${buildFolder}/index.html`, html, {encoding: "utf8"});