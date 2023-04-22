package net.m127;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;

public class Config {
    public static class SourceRepository {
        private static final String CLONE_URL = "cloneURL";
        private static final String TAG_PATTERN = "tagPattern";
        private static final String PATH = "path";
        public final String cloneURL;
        public final Pattern tagPattern;
        public final String path;
        public SourceRepository(JSONObject json) {
            this.cloneURL = json.getString(CLONE_URL);
            this.tagPattern = Pattern.compile(json.optString(TAG_PATTERN, "^(?<pkg>\\w++(?:\\.\\w++)*+)#(?<version>\\d++(?:\\.\\d++)*+)$"));
            this.path = json.optString(PATH, "${pkg}");
        }
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put(CLONE_URL, this.cloneURL);
            json.put(TAG_PATTERN, this.tagPattern);
            json.put(PATH, this.path);
            return json;
        }
    }
    private static final String ID = "repositoryId";
    private static final String NAME = "repositoryName";
    private static final String AUTHOR = "repositoryAuthor";
    private static final String URL = "baseURL";
    private static final String BUILDDIR = "buildFolder";
    private static final String SOURCES = "sources";
    public final String repositoryId;
    public final String repositoryName;
    public final String repositoryAuthor;
    public final String baseURL;
    public final Path buildFolder;
    public final List<SourceRepository> sources;
    public Config(JSONObject json) {
        this.repositoryId = json.getString(ID);
        this.repositoryName = json.optString(NAME, this.repositoryId);
        this.repositoryAuthor = json.optString(AUTHOR, "unknown");
        this.baseURL = json.getString(URL);
        this.buildFolder = Paths.get(json.optString(BUILDDIR, "dist"));
        final JSONArray array = json.getJSONArray(SOURCES);
        final List<SourceRepository> list = new ArrayList<>(array.length());
        for(int i=0;i<array.length();i++) {
            list.add(new SourceRepository(array.getJSONObject(i)));
        }
        this.sources = Collections.unmodifiableList(list);
    }
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put(ID, this.repositoryId);
        json.put(NAME, this.repositoryName);
        json.put(AUTHOR, this.repositoryAuthor);
        json.put(URL, this.baseURL);
        json.put(BUILDDIR, this.buildFolder);
        JSONArray array = new JSONArray(sources.size());
        ListIterator<SourceRepository> iterator = sources.listIterator();
        while(iterator.hasNext()) {
            array.put(iterator.nextIndex(), iterator.next().toJSON());
        }
        return json;
    }
}
