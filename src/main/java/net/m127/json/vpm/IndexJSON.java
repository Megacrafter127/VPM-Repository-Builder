package net.m127.json.vpm;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class IndexJSON {
    private String author;
    private String name;
    private String id;
    private String url;
    private Map<String, VersionJSON> packages = new HashMap<>();
}
