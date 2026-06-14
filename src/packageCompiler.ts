import {createHash} from "node:crypto";
import {writeFile} from "node:fs/promises";
import AdmZip from "adm-zip";
import {Hash, mix, Type} from "@es-git/core";
import MemoryRepo from "@es-git/memory-repo";
import {CommitBody, default as objectMixin} from "@es-git/object-mixin";
import walkersMixing from "@es-git/walkers-mixin";
import loadAsMixin from "@es-git/load-as-mixin";
import fetchMixin from "@es-git/fetch-mixin";
import {errorHandlingSpec, errorType, sourceRepository} from './vpmrepoconfig.js';
import {packageJson} from './vpmPackageJson.js';

export class BuildError extends Error {
  constructor(
    public readonly type: errorType,
    message: string,
    options?: ErrorOptions
  ) {
    super(message, options);
  }
}

class Repo extends mix(MemoryRepo.default)
  .with(objectMixin.default)
  .with(walkersMixing.default)
  .with(loadAsMixin.default)
  .with(fetchMixin.default, fetch) {

  async getCommit(hash: Hash): Promise<CommitBody> {
    const obj = await super.loadObject(hash);
    if (!obj) throw new BuildError("io", "Object missing");
    switch (obj.type) {
      case Type.commit:
        return obj.body;
      case Type.tag:
        return await this.getCommit(obj.body.object);
      default:
        throw new BuildError("io", `Bad object type: ${obj.type}`);
    }
  }

  async getSubtreeHash(tree: Hash, ...path: string[]): Promise<Hash> {
    for (const p of path) {
      const subTree = (await this.loadTree(tree))[p]?.hash;
      if (!subTree) throw new BuildError("badPath", `Subtree '${path.join("/")}' does not exist`);
      tree = subTree;
    }
    return tree;
  }
}

type buildVersionArgs = {
  errorHandling: errorHandlingSpec,
  packagesBaseURL: string,
  packagesBuildFolder: string,
  repo: Repo,
  tree: Hash,
  commit: Hash,
  packageName: string,
  version: string,
};

async function buildVersion(
  {
    errorHandling,
    packagesBaseURL,
    packagesBuildFolder,
    repo,
    tree,
    commit,
    packageName,
    version
  }: buildVersionArgs
): Promise<packageJson> {
  const zip = new AdmZip();
  let pjp: string | undefined = undefined;
  for await (const file of repo.listFiles(tree)) {
    const path = file.path.join("/");
    const buff = Buffer.from(await repo.loadBlob(file.hash));
    if (/^package\.json$/i.test(path)) {
      pjp = buff.toString('utf8');
    } else {
      zip.addFile(path, buff);
    }
  }
  if (!pjp) throw new BuildError("badPath", "package.json is missing");
  const packageJson: packageJson = JSON.parse(pjp);
  if ((packageJson.name ??= packageName) !== packageName) {
    const msg = `Commit ${commit}: Package Name Mismatch: '${packageJson.name}' (from package.json) is not '${packageName}' (from config)`;
    // noinspection FallThroughInSwitchStatementJS
    switch (errorHandling.packageMismatch) {
      case "warn_keep":
        console.log(msg);
      case "ignore_keep":
        break;
      case "warn_replace":
        console.log(msg);
      case "ignore_replace":
        packageJson.name = packageName;
        break;
      default:
        throw new BuildError("packageMismatch", msg);
    }
  }
  if ((packageJson.version ??= version) !== version) {
    const msg = `Package: ${packageJson.name}, Commit ${commit}: Package Version Mismatch: '${packageJson.version}' (from package.json) is not '${version}' (from config)`;
    // noinspection FallThroughInSwitchStatementJS
    switch (errorHandling.versionMismatch) {
      case "warn_keep":
        console.log(msg);
      case "ignore_keep":
        break;
      case "warn_replace":
        console.log(msg);
      case "ignore_replace":
        packageJson.version = version;
        break;
      default:
        throw new BuildError("versionMismatch", msg);
    }
  }
  const fileName = `${packageJson.name}_${packageJson.version}_${commit}.zip`
  packageJson.url = `${packagesBaseURL}/${fileName}`;
  delete packageJson.zipSHA256;
  zip.addFile("package.json", Buffer.from(JSON.stringify(packageJson), 'utf8'));
  const buff = await zip.toBufferPromise();
  packageJson.zipSHA256 = createHash('sha256').update(buff).digest('hex');
  await writeFile(`${packagesBuildFolder}/${fileName}`, buff);
  return packageJson;
}

export async function buildSource(errorHandling: errorHandlingSpec, source: sourceRepository, packagesBaseURL: string, packagesBuildFolder: string): Promise<packageJson[]> {
  const repo = new Repo();
  const tags = (await repo.fetch(source.cloneURL, "refs/tags/*:refs/tags/*")).flatMap(ref => {
    if (!ref.name?.startsWith("refs/tags/")) return [];
    const tagName = ref.name.substring(10);
    if (tagName.endsWith("^{}")) return [];
    return {
      name: tagName,
      hash: ref.hash,
    };
  });
  if ("tagPattern" in source) {
    const rgx = new RegExp(source.tagPattern);
    const ret = await Promise.all(tags.filter(tag => rgx.test(tag.name)).map(async tag => {
      const packageName = tag.name.replace(rgx, source.package);
      const version = tag.name.replace(rgx, source.version);
      const subPath = tag.name.replace(rgx, source.path).split("/").filter(f => f);
      const commit = await repo.getCommit(tag.hash);
      const tree = await repo.getSubtreeHash(commit.tree, ...subPath);
      try {
        return await buildVersion({
          errorHandling,
          packagesBaseURL,
          packagesBuildFolder,
          repo,
          tree,
          commit: tag.hash,
          packageName,
          version
        });
      } catch (err) {
        if (err instanceof BuildError) {
          switch (errorHandling[err.type]) {
            case "critical":
              throw err;
            default:
              console.log(err);
          }
        } else throw new BuildError("io", ``, {
          cause: err,
        });
      }
    }));
    return ret.filter(p => p) as packageJson[];
  } else {
    const ret = await Promise.all(Object.entries(source.packages).flatMap(([packageName, info]) => {
      const rgx = new RegExp(info.tagPattern);
      const errHandling = {
        ...errorHandling,
        ...(info.onError ?? {})
      };
      return tags.flatMap(tag => {
        if (!rgx.test(tag.name)) return [];
        const version = tag.name.replace(rgx, info.version);
        const subPath = tag.name.replace(rgx, info.path).split("/").filter(f => f);
        return repo.getCommit(tag.hash).then(commit => repo.getSubtreeHash(commit.tree, ...subPath)
          .then(tree => buildVersion({
            errorHandling: errHandling,
            packagesBaseURL,
            packagesBuildFolder,
            repo,
            tree,
            commit: tag.hash,
            packageName,
            version
          }).catch(err => {
            if (err instanceof BuildError) {
              switch (errHandling[err.type]) {
                case "critical":
                  throw err;
                default:
                  console.log(err);
              }
            } else throw new BuildError("io", ``, {
              cause: err,
            });
          }))
        );
      })
    }));
    return ret.filter(p => p) as packageJson[];
  }
}
