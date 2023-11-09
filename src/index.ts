import * as fs from "node:fs";
import { buildFromSource } from "./packageCompiler.js";
import { vpmRepositoryConfig } from "./vpmrepoconfig.js";
import { repoJson } from './vpmPackageJson.js';

const configJson = fs.readFileSync(process.argv[2] ?? "./vpmrepoconfig.json", { encoding: "utf-8"});

const repoConfig: vpmRepositoryConfig  = JSON.parse(configJson);

Promise.all(repoConfig.sources.map(s => buildFromSource(s.cloneURL, repoConfig, s.packages)))
  .then(rs => {
    const indexJson: repoJson = {
      id: repoConfig.id,
      name: repoConfig.name,
      author: repoConfig.author,
      url: `${repoConfig.baseURL}/index.json`,
      packages: rs.reduce((a, v) => {
        for(const [k, p] of Object.entries(v)) {
          if(k in a) {
            a[k].versions = {...a[k].versions, ...p.versions};
          } else {
            a[k] = p;
          }
        }
        return a;
      }, {}),
    };
    return fs.promises.writeFile(`${repoConfig.buildFolder ?? "./dist"}/index.json`, JSON.stringify(indexJson, undefined, 2), { encoding: "utf8" });
  });