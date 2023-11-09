import {createHash} from "node:crypto";
import {mkdir, writeFile} from "node:fs/promises";
import AdmZip from "adm-zip";
import {Hash, mix, Type} from "@es-git/core";
import MemoryRepo from "@es-git/memory-repo";
import {CommitBody, default as objectMixin} from "@es-git/object-mixin";
import walkersMixing from "@es-git/walkers-mixin";
import loadAsMixin from "@es-git/load-as-mixin";
import fetchMixin from "@es-git/fetch-mixin";
import {buildConfig, packageSources} from './vpmrepoconfig.js';
import {packageJson, packageList} from './vpmPackageJson.js';

class Repo extends mix(MemoryRepo.default)
  .with(objectMixin.default)
  .with(walkersMixing.default)
  .with(loadAsMixin.default)
  .with(fetchMixin.default, fetch) {

  async getCommit(hash: Hash): Promise<CommitBody> {
    const obj = await super.loadObject(hash);
    if (!obj) throw new Error("Object missing");
    switch (obj.type) {
      case Type.commit:
        return obj.body;
      case Type.tag:
        return await this.getCommit(obj.body.object);
      default:
        throw new Error("Bad object type");
    }
  }

  async getSubtreeHash(tree: Hash, ...path: string[]): Promise<Hash> {
    for (const p of path) {
      const subTree = (await this.loadTree(tree))[p]?.hash;
      if (!subTree) throw new Error(`Subtree '${path.join("/")}' does not exist`);
      tree = subTree;
    }
    return tree;
  }
}

async function buildSingleVersionFromSource(repo: Repo, version: string, url: string, outPath: string, inPath: string, subTree: Hash): Promise<packageJson> {
  const archive = new AdmZip();
  let pjp: string | undefined = undefined;
  for await(const file of repo.listFiles(subTree)) {
    const path = file.path.join("/");
    const buff = Buffer.from(await repo.loadBlob(file.hash));
    if (/^package\.json$/i.test(path)) {
      pjp = buff.toString("utf8");
    } else {
      archive.addFile(path, buff);
    }
  }
  if (!pjp) {
    throw new Error(`Missing package.json in '${inPath}'`);
  }
  const packageJson: packageJson = JSON.parse(pjp);
  packageJson.url = url;
  packageJson.version = version;
  archive.addFile("package.json", Buffer.from(JSON.stringify(packageJson), "utf8"));
  const buff = await archive.toBufferPromise();
  packageJson.zipSHA256 = createHash("sha256").update(buff).digest('hex');
  await writeFile(outPath, buff);
  return packageJson
}

async function buildSinglePackageFromSource(repo: Repo, baseURL: string, packageFolder: string, packageName: string, tags: {
  name: string,
  commit: CommitBody
}[], tagRegex: RegExp, versionString: string, inPath: string): Promise<Record<string, packageJson>> {
  const pathPrefix = inPath.split("/").filter(s => s);
  const buildResults = await Promise.all(tags.flatMap(tag => {
    if (!tagRegex.test(tag.name)) return [];
    const version = tag.name.replace(tagRegex, versionString);
    const zipPath = `${packageName}_${version}.zip`;
    return repo.getSubtreeHash(tag.commit.tree, ...pathPrefix)
      .then(subTree => buildSingleVersionFromSource(repo, version, `${baseURL}/${zipPath}`, `${packageFolder}/${zipPath}`, inPath, subTree))
      .catch(err => {
        throw new Error(`Error while building ${version} of ${packageName}`, {
          cause: err
        });
      });
  }));
  return Object.fromEntries(buildResults.map(packageJson => [packageJson.version, packageJson] as const));
}

export async function buildFromSource(remoteURL: string, config: buildConfig, packages: packageSources): Promise<packageList> {
  const packageFolder = `${config.buildFolder ?? "./dist"}/packages`;
  await mkdir(packageFolder, {recursive: true});
  const repo = new Repo();
  const refs = await repo.fetch(remoteURL, "refs/tags/*:refs/tags/*");
  const tags = await Promise.all(refs.flatMap(ref => {
    const tagName = /^refs\/tags\/(?<tag>.*)$/.exec(ref.name ?? "")?.groups?.["tag"];
    if (!tagName || tagName.endsWith("^{}")) return [];
    return repo.getCommit(ref.hash).then(commit => ({
      name: tagName,
      commit,
    }));
  }));
  return Object.fromEntries<{
    versions: Record<string, packageJson>
  }>(await Promise.all(Object.entries(packages).map(async ([pkg, src]) => {
    return [pkg, {
      versions: await buildSinglePackageFromSource(repo, `${config.baseURL}/packages`, packageFolder, pkg, tags, new RegExp(src.tagPattern), src.version, src.path),
    }] as const;
  })));
}