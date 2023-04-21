package net.m127;

import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RequiredArgsConstructor
public class VPMBuilder {
    private static final Predicate<String> PACKAGE_JSON = Pattern.compile("/?package\\.json", Pattern.CASE_INSENSITIVE).asMatchPredicate();
    private static final Pattern TAG_NAME_FMT = Pattern.compile("^(?<pkg>\\w++(?:\\.\\w++)*+)#(?<vrsn>\\d++(?:\\.\\d++)*+)$");
    private static final HexFormat HEX = HexFormat.of();
    
    public static void main(String[] args) {
        Properties props = new Properties();
        try(BufferedReader in = Files.newBufferedReader(Path.of(args.length == 0 ? "repository.properties" : args[0]))) {
            props.load(in);
        } catch(IOException ignored) {}
        
        final File gd = new File(props.getProperty("gitRepository", "."));
        try(
            Git git = Git.open(gd);
            Repository repo = git.getRepository();
        ) {
            VPMBuilder vpmBuilder = new VPMBuilder(
                repo,
                gd.toPath().resolve(props.getProperty("buildPath", "dist")),
                props.getProperty("baseURL", "http://localhost/"),
                props.getProperty("repositoryName", "VPM Repository"),
                props.getProperty("repositoryId", ""),
                props.getProperty("repositoryAuthor", "unspecified")
            );
            vpmBuilder.build();
        } catch(IOException ex) {
            ex.printStackTrace();
        }
    }
    
    private static JSONObject path(JSONObject object, String ...elements) {
        for(String s:elements) {
            JSONObject o = object.optJSONObject(s);
            if(o == null) {
                o = new JSONObject();
                object.put(s, o);
            }
            object = o;
        }
        return object;
    }
    
    private final Repository repo;
    private final Path buildDir;
    private final String baseURL;
    private final String repoName;
    private final String repoId;
    private final String repoAuthor;
    
    public void build() throws IOException {
        Files.createDirectories(buildDir);
        final JSONObject vpmRepo = new JSONObject();
        vpmRepo.put("name", repoName);
        vpmRepo.put("id", repoId);
        vpmRepo.put("url", baseURL+"index.json");
        vpmRepo.put("author", repoAuthor);
        final JSONObject packages = path(vpmRepo, "packages");
        try(RevWalk revWalk = new RevWalk(repo)) {
            final RefDatabase refs = repo.getRefDatabase();
            for(Ref ref : refs.getRefsByPrefix(Constants.R_TAGS)) {
                final RevTag tag = revWalk.parseTag(ref.getObjectId());
                final Matcher m = TAG_NAME_FMT.matcher(tag.getTagName());
                if(!m.find()) continue;
                final RevCommit commit = revWalk.parseCommit(tag.getObject());
                final String pkg = m.group("pkg");
                final String version = m.group("vrsn");
                try {
                    JSONObject packageJson = buildZip(commit, pkg, version);
                    path(packages, pkg, "versions").put(version, packageJson);
                } catch(IOException ex) {
                    ex.printStackTrace();
                }
            }
        } catch(NoSuchAlgorithmException ignored) {}
        try(BufferedWriter out = Files.newBufferedWriter(buildDir.resolve("index.json"))) {
            vpmRepo.write(out, 2, 0);
        }
    }
    
    private JSONObject buildZip(RevCommit commit, String pkg, String version) throws IOException, NoSuchAlgorithmException {
        final String zipPath = pkg+"/"+pkg+"_"+version+".zip";
        final Path savePath = buildDir.resolve(zipPath);
        if(!Files.isDirectory(savePath.getParent())) Files.createDirectory(savePath.getParent());
        final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        JSONObject packageJson;
        try(
            ZipOutputStream out = new ZipOutputStream(new DigestOutputStream(Files.newOutputStream(savePath), sha256));
            TreeWalk walk = new TreeWalk(repo);
        ) {
            walk.addTree(commit.getTree());
            walk.setFilter(PathFilter.create(pkg));
            if(!walk.next()) {
                throw new FileNotFoundException(String.format("'%s' does not exist for tag '%s#%s'", pkg, pkg, version));
            }
            packageJson = zipContents(walk, "", out, json -> updatePackageJson(json, zipPath, version));
        }
        if(packageJson == null) {
            Files.delete(savePath);
            throw new FileNotFoundException(String.format("package.json does not exist for version %s of %s", version, pkg));
        }
        packageJson.put("zipSHA256", HEX.formatHex(sha256.digest()));
        return packageJson;
    }
    
    private JSONObject updatePackageJson(JSONObject packageJson, String zipPath, String version) {
        packageJson.put("version", version);
        packageJson.put("url", baseURL+zipPath);
        return packageJson;
    }
    
    private JSONObject zipContents(TreeWalk walk, String prefix, ZipOutputStream out, UnaryOperator<JSONObject> packageJsonModifier) throws IOException {
        walk.enterSubtree();
        if(!walk.next()) return null;
        final int depth = walk.getDepth();
        JSONObject packageJson = null;
        while(walk.getDepth() == depth) {
            final FileMode fileMode = walk.getFileMode();
            if(FileMode.TREE.equals(fileMode)) {
                final String zipPath = prefix+walk.getNameString()+"/";
                final ZipEntry entry = new ZipEntry(zipPath);
                out.putNextEntry(entry);
                zipContents(walk, zipPath, out, UnaryOperator.identity());
            } else if(FileMode.REGULAR_FILE.equals(fileMode) || FileMode.EXECUTABLE_FILE.equals(fileMode)) {
                final ZipEntry entry = new ZipEntry(prefix+walk.getNameString());
                out.putNextEntry(entry);
                final ObjectId blobId = walk.getObjectId(0);
                final ObjectLoader loader = walk.getObjectReader().open(blobId);
                if(PACKAGE_JSON.test(entry.getName())) {
                    try(InputStream in = loader.openStream()) {
                        packageJson = new JSONObject(new JSONTokener(in));
                        packageJson = packageJsonModifier.apply(packageJson);
                        packageJson.write(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                    }
                } else {
                    loader.copyTo(out);
                }
                out.closeEntry();
                if(!walk.next()) break;
            }
        }
        return packageJson;
    }
}
