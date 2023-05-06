package net.m127;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.m127.json.config.Config;
import net.m127.json.config.MismatchHandling;
import net.m127.json.config.SourceRepository;
import net.m127.json.vpm.IndexJSON;
import net.m127.json.vpm.PackageJSON;
import net.m127.json.vpm.VersionJSON;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogBuilder;
import org.apache.logging.log4j.core.config.Configurator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RequiredArgsConstructor
@Log4j2
public class VPMBuilder {
    private static final char[] HEXCHARS = "0123456789abcdef".toCharArray();
    public static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, MapperFeature.USE_ANNOTATIONS)
        .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
        .build();
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length << 1];
        for(int i = 0, j = 0; i < bytes.length; i++, j += 2) {
            chars[j] = HEXCHARS[bytes[i] & 0xF];
            chars[j + 1] = HEXCHARS[(bytes[i] >> 4) & 0xF];
        }
        return String.valueOf(chars);
    }
    private static final FileVisitor<Path> DELETE_DIRECTORY = new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
            Files.delete(path);
            return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
            if(e == null) {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            } else {
                throw e;
            }
        }
    };
    static {
        Configurator.setRootLevel(Level.INFO);
    }
    public static void main(String[] args) throws IOException {
        final Config config;
        try(BufferedReader in = Files.newBufferedReader(Paths.get(args.length == 0 ? "config.json" : args[0]))) {
            config = MAPPER.readValue(in, Config.class);
        }
        Files.createDirectories(config.getBuildFolder());
        final IndexJSON vpmRepo = new IndexJSON();
        vpmRepo.setName(config.getRepositoryName());
        vpmRepo.setId(config.getRepositoryId());
        vpmRepo.setUrl(config.getBaseURL() + "index.json");
        vpmRepo.setAuthor(config.getRepositoryAuthor());
        for(SourceRepository src : config.getSources()) {
            Path gitDir = Files.createTempDirectory(src.getCloneURL().replaceAll("[^/]*+/", ""));
            try(
                Git git = Git.cloneRepository()
                    .setURI(src.getCloneURL())
                    .setBare(true)
                    .setGitDir(gitDir.toFile())
                    .call()
            ) {
                VPMBuilder vpmBuilder = new VPMBuilder(git.getRepository(), src, config.getBaseURL(),
                    config.getBuildFolder()
                );
                vpmBuilder.build(vpmRepo.getPackages());
            } catch(IOException | GitAPIException ex) {
                ex.printStackTrace();
            }
            Files.walkFileTree(gitDir, DELETE_DIRECTORY);
        }
        try(BufferedWriter out = Files.newBufferedWriter(config.getBuildFolder().resolve("index.json"))) {
            MAPPER.writeValue(out, vpmRepo);
        }
        new WebsiteBuilder(config).buildIndexHTML(vpmRepo);
    }
    
    private final Repository repo;
    private final SourceRepository config;
    private final String baseURL;
    private final Path buildDir;
    
    public void build(Map<String, VersionJSON> packages) throws IOException {
        try(RevWalk revWalk = new RevWalk(repo)) {
            final RefDatabase refs = repo.getRefDatabase();
            for(Ref ref : refs.getRefsByPrefix(Constants.R_TAGS)) {
                final RevTag tag = revWalk.parseTag(ref.getObjectId());
                final Matcher m = config.getTagPattern().matcher(tag.getTagName());
                if(!m.find()) continue;
                final String subPath = m.replaceFirst(config.getPath());
                final RevCommit commit = revWalk.parseCommit(tag.getObject());
                final String pkg = m.replaceFirst(config.getPkg());
                final String version = m.replaceFirst(config.getVersion());
                PackageJSON packageJSON;
                try(TreeWalk treeWalk = new TreeWalk(repo)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setFilter(PathFilter.create(subPath + "/package.json"));
                    treeWalk.setRecursive(true);
                    if(!treeWalk.next()) {
                        log.atError().log("package.json missing at tag {}", tag.getTagName());
                        continue;
                    }
                    packageJSON = MAPPER.readValue(
                        treeWalk.getObjectReader().open(treeWalk.getObjectId(0)).openStream(),
                        PackageJSON.class
                    );
                }
                
                
                try {
                    handleMismatch(
                        pkg,
                        packageJSON::getName,
                        log -> log.log(
                            "Package name mismatch at tag {}\nExpected: {}\nActual: {}",
                            tag.getTagName(),
                            pkg,
                            packageJSON.getName()
                        ),
                        packageJSON::setName,
                        () -> new RuntimeException("Fatal package name mismatch"),
                        ContinueException::new
                    );
                    handleMismatch(
                        version,
                        packageJSON::getVersion,
                        log -> log.log(
                            "Version mismatch at tag {}\nExpected: {}\nActual: {}",
                            tag.getTagName(),
                            version,
                            packageJSON.getVersion()
                        ),
                        packageJSON::setVersion,
                        () -> new RuntimeException("Fatal version mismatch"),
                        ContinueException::new
                    );
                } catch(ContinueException ex) {
                    continue;
                }
                
                Map<String, PackageJSON> versions = packages.computeIfAbsent(pkg, VersionJSON::newEmpty).getVersions();
                if(versions.containsKey(version)) log.atWarn().log("Duplicate version for '{}': {}", pkg, version);
                final String zipPath = "packages/" + pkg + "/" + pkg + "_" + version + ".zip";
                packageJSON.setUrl(baseURL + zipPath);
                final Path savePath = buildDir.resolve(zipPath);
                log.atInfo().log("Found tag for version {} of '{}'", version, pkg);
                try {
                    buildZip(commit, subPath, savePath, packageJSON);
                    versions.put(version, packageJSON);
                } catch(IOException ex) {
                    log.catching(ex);
                    Files.deleteIfExists(savePath);
                }
            }
        } catch(NoSuchAlgorithmException ignored) {
        }
    }
    
    private <T, E extends Throwable, R extends Throwable> void handleMismatch(
        T expected,
        Supplier<T> getter,
        Consumer<? super LogBuilder> logger,
        Consumer<T> setter,
        Supplier<E> fatal,
        Supplier<R> reset
    ) throws E, R {
        if(!Objects.equals(expected, getter.get())) {
            MismatchHandling mismatchHandling = config.getOnVersionMismatch();
            if(mismatchHandling.logBuilder != null) {
                logger.accept(mismatchHandling.logBuilder.apply(log));
            }
            switch(mismatchHandling) {
            case IGNORE:
            case WARN_IGNORE:
                break;
            case ERROR:
                throw reset.get();
            case FATAL:
                throw fatal.get();
            case WARN_REPLACE:
            case REPLACE:
                setter.accept(expected);
                break;
            }
        }
    }
    
    private void buildZip(
        RevCommit commit,
        String subPath,
        Path savePath,
        PackageJSON packageJSON
    ) throws IOException, NoSuchAlgorithmException {
        if(!Files.isDirectory(savePath.getParent())) Files.createDirectories(savePath.getParent());
        final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try(
            ZipOutputStream out = new ZipOutputStream(new DigestOutputStream(Files.newOutputStream(savePath), sha256));
            TreeWalk walk = new TreeWalk(repo);
        ) {
            walk.addTree(commit.getTree());
            walk.setFilter(PathFilter.create(subPath));
            if(!walk.next()) {
                throw new FileNotFoundException();
            }
            zipContents(walk, "", out, packageJSON);
        }
        
        packageJSON.setZipSHA256(toHex(sha256.digest()));
    }
    
    private void zipContents(
        TreeWalk walk,
        String prefix,
        ZipOutputStream out,
        PackageJSON packageJSON
    ) throws IOException {
        walk.enterSubtree();
        if(!walk.next()) return;
        final int depth = walk.getDepth();
        while(walk.getDepth() == depth) {
            final FileMode fileMode = walk.getFileMode();
            if(FileMode.TREE.equals(fileMode)) {
                final String zipPath = prefix + walk.getNameString() + "/";
                final ZipEntry entry = new ZipEntry(zipPath);
                out.putNextEntry(entry);
                zipContents(walk, zipPath, out, null);
            } else if(FileMode.REGULAR_FILE.equals(fileMode) || FileMode.EXECUTABLE_FILE.equals(fileMode)) {
                final ZipEntry entry = new ZipEntry(prefix + walk.getNameString());
                out.putNextEntry(entry);
                if(packageJSON != null && "package.json".equals(entry.getName())) {
                    MAPPER.writeValue(out, packageJSON);
                } else {
                    final ObjectId blobId = walk.getObjectId(0);
                    final ObjectLoader loader = walk.getObjectReader().open(blobId);
                    loader.copyTo(out);
                }
                out.closeEntry();
                if(!walk.next()) break;
            }
        }
    }
}
