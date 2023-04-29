package net.m127.json.vpm;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Data
public class PackageJSON {
    private String name;
    private String displayName;
    private String description;
    private String version;
    private AuthorJSON author;
    private String[] keywords;
    private String url;
    private String zipSHA256;
    private Map<String, String> dependencies = new HashMap<>();
    private Map<String, String> vpmDependencies = new HashMap<>();
    
    @JsonIgnore
    @JsonAnySetter
    @Getter(onMethod_ = @JsonAnyGetter)
    private Map<String, Object> unknown;
}
